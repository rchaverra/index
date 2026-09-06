package coredevices

import BugReportButton
import CoreNav
import DocumentAttachment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import co.touchlab.kermit.Logger
import com.eygraber.uri.Uri
import com.mmk.kmpnotifier.notification.NotifierManager
import com.russhwolf.settings.Settings
import coredevices.indexai.database.dao.ConversationMessageDao
import coredevices.libindex.LibIndex
import coredevices.libindex.device.IndexPlatformBluetoothAssociations
import coredevices.ring.bugreport.IndexSettingsSummary
import coredevices.ring.bugreport.RecentRecordingExport
import coredevices.pebble.ui.TopBarParams
import coredevices.ring.RingDelegate
import coredevices.ring.agent.ShortcutActionHandler
import coredevices.ring.agent.LlmMode
import coredevices.ring.database.Preferences
import coredevices.ring.database.room.repository.McpSandboxRepository
import coredevices.ring.database.room.repository.RecordingRepository
import coredevices.ring.service.RingSync
import coredevices.ring.service.recordings.RecordingProcessingQueue
import coredevices.ring.storage.RecordingStorage
import coredevices.ring.ui.navigation.RingRoutes
import coredevices.ring.ui.navigation.addRingRoutes
import coredevices.ring.ui.screens.home.FeedTabContents
import coredevices.ring.ui.screens.home.IndexFeedScreen
import coredevices.ring.ui.theme.IndexThemeHost
import coredevices.util.CoreConfigHolder
import coredevices.util.Permission
import coredevices.util.PermissionRequester
import coredevices.util.Platform
import coredevices.util.isAndroid
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.ModelManager
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import coredevices.ring.service.indexfeed.observeDefaultListsBootstrap
import io.rebble.libpebblecommon.plugin.PhoneNetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import rememberOpenDocumentLauncher
import size
import kotlin.time.Clock

class ExperimentalDevices(
    private val ringSync: RingSync,
    private val recordingStorage: RecordingStorage,
    private val ringDelegate: RingDelegate,
    private val sandboxRepository: McpSandboxRepository,
    private val recordingRepository: RecordingRepository,
    private val conversationMessageDao: ConversationMessageDao,
    private val preferences: Preferences,
    private val shortcutActionHandler: ShortcutActionHandler,
    private val libIndex: LibIndex,
    private val permissionRequester: PermissionRequester,
    /** Touched here so Koin instantiates the singleton at app start;
     *  the syncer's init block attaches its observers immediately and
     *  runs for the rest of the process lifetime (mirrors how
     *  RecordingProcessingQueue's recording observer kicks off). */
    private val indexFeedSyncService: coredevices.ring.service.indexfeed.IndexFeedSyncService,
    private val defaultListsBootstrap: coredevices.ring.service.indexfeed.DefaultListsBootstrap,
    private val indexSettingsSummary: IndexSettingsSummary,
    private val coreConfigHolder: CoreConfigHolder,
    private val platform: Platform,
    private val phoneNetworkMonitor: PhoneNetworkMonitor,
    private val settings: Settings,
    private val modelManager: ModelManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    fun appInit() {
        if (platform.isAndroid) {
            // PHASE 2B: Force Index to be enabled in Android phone mode so that
            // required permissions are included in the missing-permissions set
            // and Index features are active by default.
            if (!coreConfigHolder.config.value.enableIndex) {
                coreConfigHolder.update(coreConfigHolder.config.value.copy(enableIndex = true))
            }
            scope.launch {
                try {
                    val recommendedModel = modelManager.getRecommendedSTTModel().modelSlug
                    applyAndroidPhoneModeLocalDefaults(
                        settings,
                        preferences,
                        coreConfigHolder,
                        recommendedModel,
                    )
                    ensureNextAndroidPhoneLocalModel(recommendedModel)
                    modelManager.modelDownloadStatus.collect { status ->
                        if (status is coredevices.util.models.ModelDownloadStatus.Idle) {
                            ensureNextAndroidPhoneLocalModel(recommendedModel)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.withTag("ExperimentalDevices").w(e) {
                        "Android phone-mode local defaults initialization deferred"
                    }
                }
            }
        }
        libIndex.init(
            permissionRequester.missingPermissions.distinctUntilChanged { old, new ->
                (Permission.Bluetooth in old && Permission.Bluetooth !in new) || (Permission.Bluetooth !in old && Permission.Bluetooth in new)
            }.map {
                Permission.Bluetooth !in it
            }
        )
        indexFeedSyncService.hashCode()

        // Android local seeds never wait for the independent cloud observer.
        if (platform.isAndroid) scope.launch {
            try { defaultListsBootstrap.ensureLocal() } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.withTag("ExperimentalDevices").w(e) { "Local default list initialization failed" }
            }
        }

        scope.launch {
            observeDefaultListsBootstrap(
                accounts = flow {
                    emit(Firebase.auth.currentUser?.uid)
                    Firebase.auth.authStateChanged.collect { emit(it?.uid) }
                },
                backupEnabled = preferences.backupEnabled,
                android = platform.isAndroid,
                connectivityAvailable = phoneNetworkMonitor.connection.map { route ->
                    route != null && route != "None"
                },
                reconcile = {
                    try { defaultListsBootstrap.ensure() } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.withTag("ExperimentalDevices").w(e) { "Cloud default list initialization deferred" }
                    }
                },
            )
        }
    }

    private suspend fun ensureNextAndroidPhoneLocalModel(recommendedSpeechModel: String) {
        val downloaded = modelManager.getDownloadedModelSlugs()
        if (coreConfigHolder.config.value.sttConfig.mode == CactusSTTMode.LocalOnly &&
            recommendedSpeechModel !in downloaded
        ) {
            modelManager.getAvailableSTTModels()
                .firstOrNull { it.slug == recommendedSpeechModel }
                ?.let {
                    modelManager.downloadSTTModel(
                        it,
                        allowMetered = true,
                        userInitiated = false,
                    )
                }
            return
        }

        val languageModel = modelManager.getRecommendedLanguageModel()
        if (preferences.llmMode.value == LlmMode.LocalOnly && languageModel !in downloaded) {
            modelManager.getAvailableLanguageModels()
                .firstOrNull { it.slug == languageModel }
                ?.let {
                    modelManager.downloadLanguageModel(
                        it,
                        allowMetered = true,
                        userInitiated = false,
                    )
                }
        }
    }

    suspend fun init() {
        withContext(Dispatchers.IO) {
            sandboxRepository.seedDatabase()
        }
        ringDelegate.init()
        if (preferences.ringPairedOld.value && preferences.ringPaired.value == null) {
            // Prompt user to re-pair to migrate
            NotifierManager.getLocalNotifier().notify {
                title = "Re-pairing required"
                body = "Please re-pair your Index 01 device to continue using it."
            }
        }
    }

    fun onBackgroundSync() {
        ringDelegate.onBackgroundSync()
    }

    fun handleDeepLink(uri: Uri): Boolean {
        return shortcutActionHandler.handleDeepLink(uri)
    }

    fun addExperimentalRoutes(builder: NavGraphBuilder, coreNav: CoreNav) {
        builder.addRingRoutes(coreNav)
    }

    fun badCollectionsDir(): Path? = RingSync.badCollectionsDir

    @Composable
    fun IndexScreen(coreNav: CoreNav, topBarParams: TopBarParams) {
        val recordingQueue = koinInject<RecordingProcessingQueue>()
        val recordingRepo = koinInject<RecordingRepository>()
        val recordingStorage = koinInject<RecordingStorage>()
        val prefs = koinInject<Preferences>()
        val isDebugEnabled by prefs.debugDetailsEnabled.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val launchWavImportDialog = rememberOpenDocumentLauncher {
            it?.firstOrNull()?.let { file ->
                val id = "imported-${Clock.System.now()}"
                scope.launch(Dispatchers.IO) {
                    recordingStorage.openRecordingSink(
                        id = id,
                        sampleRate = 16000,
                        mimeType = "audio/wav",
                    ).buffered().use { sink ->
                        file.source.buffered().use {
                            it.skip(44) // Skip WAV header
                            it.transferTo(sink)
                        }
                    }
                    recordingQueue.queueLocalAudioProcessing(id)
                    topBarParams.showSnackbar("Imported WAV file")
                }
            }
        }
        // The chrome's TopAppBar is hidden tab-wide by WatchHomeScreen
        // whenever currentTab == Index, so we don't manage `setHidden`
        // here — doing it per-screen would race with detail screens
        // (their own DetailTopBar + the chrome would show double until
        // the next compositional pass).
        androidx.compose.runtime.DisposableEffect(Unit) {
            topBarParams.title("")
            topBarParams.searchAvailable(null)
            topBarParams.actions { /* moved into IndexFeedScreen.IndexHeader */ }
            onDispose { /* nothing to clean up */ }
        }
        IndexThemeHost {
            IndexFeedScreen(
                coreNav = coreNav,
                scrollToTop = topBarParams.scrollToTop,
                headerActions = {
                    BugReportButton(
                        coreNav,
                        pebble = false,
                        screenContext = mapOf("screen" to "IndexFeed"),
                    )
                    if (isDebugEnabled) {
                        IconButton(
                            onClick = { launchWavImportDialog(listOf("audio/*")) },
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = "Debug")
                        }
                    }
                    IconButton(
                        onClick = { coreNav.navigateTo(RingRoutes.Settings) },
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
        // (Legacy `FeedTabContents` is no longer referenced from here.
        // It used to be kept-alive via a `::FeedTabContents` callable
        // reference, but Kotlin/Native 2.3 crashes during IR lowering on
        // `@Composable` function references whose arity exceeds Function6
        // — KT-bug, "Unexpected number of type arguments". The function
        // is public top-level so no static analysis will drop it; the
        // callable-ref keep-alive was always cosmetic.)
    }

    suspend fun exportOutput(id: String): List<DocumentAttachment> {
        val logger = co.touchlab.kermit.Logger.withTag("ExperimentalDevices")
        val attachments = mutableListOf<DocumentAttachment>()
        // Export both the processed audio and the original raw capture for the
        // reported recording, so the bug report carries both for comparison.
        for (useOriginal in listOf(false, true)) {
            try {
                val path = recordingStorage.exportRecording(id, useOriginalAudio = useOriginal)
                val suffix = if (useOriginal) "-original" else ""
                attachments.add(
                    DocumentAttachment(
                        fileName = "recording$suffix.wav",
                        mimeType = "audio/wav",
                        source = SystemFileSystem.source(path).buffered(),
                        size = path.size(),
                    )
                )
            } catch (e: Exception) {
                val variant = if (useOriginal) "original" else "processed"
                logger.w(e) { "Failed to export $variant audio for recording $id" }
            }
        }
        return attachments
    }

    /**
     * Export the most recent [limit] recordings for a bug report: one
     * `recent_recordings.json` capturing each recording's [LocalRecording],
     * entries and conversation messages (the data shown in `RecordingDetails`),
     * plus one WAV per recording entry that has audio. Audio export per entry is
     * best-effort — an un-uploaded or missing file is skipped, not fatal.
     */
    suspend fun exportRecentRecordings(limit: Int = 10): List<DocumentAttachment> = withContext(Dispatchers.IO) {
        val logger = co.touchlab.kermit.Logger.withTag("ExperimentalDevices")
        val recordings = recordingRepository.getRecentRecordings(limit)
        if (recordings.isEmpty()) return@withContext emptyList()

        val attachments = mutableListOf<DocumentAttachment>()
        val exports = recordings.map { recording ->
            val entries = recordingRepository.getRecordingEntriesFlow(recording.id).first()
            val messages = conversationMessageDao.getMessagesForRecording(recording.id).first()

            entries.mapNotNull { it.fileName }.distinct().forEach { fileName ->
                // Export both the processed audio (what was transcribed) and the
                // original raw capture, so bug reports carry both for comparison.
                for (useOriginal in listOf(false, true)) {
                    try {
                        val path = recordingStorage.exportRecording(fileName, useOriginalAudio = useOriginal)
                        val suffix = if (useOriginal) "-original" else ""
                        attachments.add(
                            DocumentAttachment(
                                fileName = "recording-${recording.id}-$fileName$suffix.wav",
                                mimeType = "audio/wav",
                                source = SystemFileSystem.source(path).buffered(),
                                size = path.size(),
                            )
                        )
                    } catch (e: Exception) {
                        val variant = if (useOriginal) "original" else "processed"
                        logger.w(e) { "Failed to export $variant audio for recording ${recording.id} ($fileName)" }
                    }
                }
            }

            RecentRecordingExport(recording, entries, messages)
        }

        val json = Json.encodeToString(exports)
        val buffer = Buffer().apply { writeString(json) }
        attachments.add(
            DocumentAttachment(
                fileName = "recent_recordings.json",
                mimeType = "application/json",
                source = buffer,
                size = buffer.size,
            )
        )
        attachments
    }

    suspend fun debugSummary(): String {
        return buildString {
            ringSync.lastRingSummary()?.let {
                append(it)
                append("\n")
            }
            append("Index Debug enabled: ${preferences.debugDetailsEnabled.value}\n")
            append("LLM mode: ${preferences.llmMode.value}\n")
            append(runCatching { indexSettingsSummary.summary() }
                .getOrElse { "\nIndex Settings unavailable: ${it.message}" })
        }
    }
}

internal const val ANDROID_PHONE_LOCAL_DEFAULTS_INITIALIZED =
    "android_phone_local_defaults_initialized"

/**
 * Applies the fork's offline defaults once. The completion marker is written only
 * after both preferences are stored, so a process interruption safely retries.
 * Later choices made in Settings are preserved on every subsequent launch.
 */
internal suspend fun applyAndroidPhoneModeLocalDefaults(
    settings: Settings,
    preferences: Preferences,
    coreConfigHolder: CoreConfigHolder,
    recommendedSpeechModel: String,
) {
    if (settings.getBoolean(ANDROID_PHONE_LOCAL_DEFAULTS_INITIALIZED, false)) return

    val config = coreConfigHolder.config.value
    coreConfigHolder.update(
        config.copy(
            sttConfig = config.sttConfig.copy(
                mode = CactusSTTMode.LocalOnly,
                modelName = recommendedSpeechModel,
            ),
        )
    )
    preferences.setLlmMode(LlmMode.LocalOnly)
    settings.putBoolean(ANDROID_PHONE_LOCAL_DEFAULTS_INITIALIZED, true)
}
