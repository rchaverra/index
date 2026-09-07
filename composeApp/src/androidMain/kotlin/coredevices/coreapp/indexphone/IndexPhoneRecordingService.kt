package coredevices.coreapp.indexphone

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import coredevices.coreapp.MainActivity
import coredevices.ring.service.recordings.RecordingProcessingQueue
import coredevices.ring.service.button.RingGesture
import coredevices.ring.storage.RecordingStorage
import coredevices.ring.util.AudioRecorder
import coredevices.util.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * Keeps the phone microphone recording boundary available after Index leaves the foreground.
 *
 * Android only allows a microphone foreground service to be created while the app has an eligible
 * foreground state. [armIfPermitted] is therefore called by [MainActivity] while it is resumed.
 * The accessibility service only sends recording commands to an already-armed instance.
 */
class IndexPhoneRecordingService : Service(), KoinComponent {
    private val recordingStorage: RecordingStorage by inject()
    private val recordingQueue: RecordingProcessingQueue by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeSession: RecordingSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isArmed = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground(startId)) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_HOLD_RECORDING -> startHoldRecording(
                intent.getStringExtra(EXTRA_BUTTON_SEQUENCE) ?: HOLD_BUTTON_SEQUENCE,
            )
            ACTION_STOP_HOLD_RECORDING -> stopHoldRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isArmed = false
        activeSession?.recorder?.let { recorder ->
            // AudioRecord.read is blocking, so stop it before cancelling the writer scope.
            runBlocking(Dispatchers.IO) { runCatching { recorder.stopRecording() } }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startHoldRecording(buttonSequence: String) {
        if (activeSession != null) {
            Log.w(TAG, "Ignoring Volume Up hold because a phone recording is already active")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Ignoring Volume Up hold because microphone permission is missing")
            return
        }

        val session = try {
            RecordingSession(
                fileId = "phone_recording-${Uuid.random()}",
                recorder = getKoin().get(),
                buttonSequence = buttonSequence,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create the phone audio recorder", e)
            return
        }
        activeSession = session
        session.writerJob = serviceScope.launch {
            try {
                session.recorder.use { recorder ->
                    val source = recorder.startRecording()
                    session.started.complete(Unit)
                    Log.i(TAG, "Volume Up hold recording started fileId=${session.fileId}")
                    val sink = recordingStorage.openOriginalRecordingSink(
                        session.fileId,
                        recorder.sampleRate,
                        RAW_AUDIO_MIME,
                    )
                    source.use {
                        sink.use {
                            source.buffered().transferTo(sink)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!session.started.isCompleted) session.started.completeExceptionally(e)
                Log.e(TAG, "Phone recording failed fileId=${session.fileId}", e)
            }
        }
    }

    private fun stopHoldRecording() {
        val session = activeSession ?: run {
            Log.w(TAG, "Ignoring Volume Up release because no phone recording is active")
            return
        }
        if (session.stopping) return
        session.stopping = true

        serviceScope.launch {
            try {
                session.started.await()
                session.recorder.stopRecording()
                session.writerJob?.join()

                val (source, info) = recordingStorage.openRecordingSource(
                    session.fileId,
                    useOriginalAudio = true,
                )
                val processedSink = recordingStorage.openRecordingSink(
                    session.fileId,
                    info.cachedMetadata.sampleRate,
                    info.cachedMetadata.mimeType,
                )
                source.use { src ->
                    processedSink.buffered().use { dst -> src.transferTo(dst) }
                }
                recordingQueue.queueLocalAudioProcessing(
                    fileId = session.fileId,
                    buttonSequence = session.buttonSequence,
                )
                Log.i(TAG, "Volume Up hold recording queued fileId=${session.fileId}")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to stop or queue phone recording fileId=${session.fileId}", e)
            } finally {
                if (activeSession === session) activeSession = null
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManager.IMPORTANCE_MIN,
        )
            .setName("Index phone controls")
            .setDescription("Keeps Volume Up recording ready")
            .setShowBadge(false)
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun enterForeground(startId: Int): Boolean {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Index phone controls ready")
            .setContentText("Hold Volume Up to record")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .build()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to arm Index phone recording", e)
            isArmed = false
            stopSelf(startId)
            false
        }
    }

    private class RecordingSession(
        val fileId: String,
        val recorder: AudioRecorder,
        val buttonSequence: String,
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        var writerJob: Job? = null,
        @Volatile var stopping: Boolean = false,
    )

    companion object {
        private const val TAG = "IndexPhoneRecording"
        private const val NOTIFICATION_CHANNEL_ID = "index_phone_controls_silent_v2"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_ARM = "coredevices.coreapp.indexphone.ARM"
        private const val ACTION_START_HOLD_RECORDING =
            "coredevices.coreapp.indexphone.START_HOLD_RECORDING"
        private const val ACTION_STOP_HOLD_RECORDING =
            "coredevices.coreapp.indexphone.STOP_HOLD_RECORDING"
        private const val EXTRA_BUTTON_SEQUENCE = "button_sequence"
        private const val RAW_AUDIO_MIME = "audio/raw"
        private const val HOLD_BUTTON_SEQUENCE = "long"

        @Volatile
        private var isArmed = false

        fun isReadyForVolumeGestures(): Boolean = isArmed

        fun armIfPermitted(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, IndexPhoneRecordingService::class.java).apply {
                action = ACTION_ARM
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Unable to request phone recording arm", it) }
        }

        fun startGestureRecording(context: Context, gesture: RingGesture) {
            require(gesture == RingGesture.Hold || gesture == RingGesture.ClickHold)
            sendToArmedService(
                context = context,
                action = ACTION_START_HOLD_RECORDING,
                buttonSequence = gesture.sequence.joinToString(" ") { it.name.lowercase() },
            )
        }

        fun stopHoldRecording(context: Context) = sendToArmedService(
            context,
            ACTION_STOP_HOLD_RECORDING,
        )

        private fun sendToArmedService(
            context: Context,
            action: String,
            buttonSequence: String? = null,
        ) {
            if (!isArmed) {
                Log.w(TAG, "Ignoring $action because phone recording is not armed")
                return
            }
            runCatching {
                context.startService(Intent(context, IndexPhoneRecordingService::class.java).apply {
                    this.action = action
                    buttonSequence?.let { putExtra(EXTRA_BUTTON_SEQUENCE, it) }
                })
            }.onFailure { Log.e(TAG, "Unable to deliver $action", it) }
        }
    }
}
