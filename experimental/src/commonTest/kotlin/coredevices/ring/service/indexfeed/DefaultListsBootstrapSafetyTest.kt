@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package coredevices.ring.service.indexfeed

import com.russhwolf.settings.MapSettings
import coredevices.indexai.data.entity.ListDocument
import coredevices.ring.data.entity.room.indexfeed.CachedList
import coredevices.ring.database.PreferencesImpl
import coredevices.ring.database.firestore.dao.FirestoreListsDao
import coredevices.ring.database.room.dao.CachedListDao
import coredevices.ring.database.room.repository.ListRepository
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.LIST_TODOS_ID
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.defaultDocuments
import coredevices.ring.service.indexfeed.DefaultListsBootstrap.Companion.isPristinePlaceholder
import coredevices.util.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Policy tests use an in-memory DAO double; real INSERT IGNORE is covered by the Room device test.
 * Remote lambdas verify orchestration only, not Firestore transaction atomicity. */
class DefaultListsBootstrapSafetyTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val seeds get() = defaultDocuments(epoch)
    private val rows get() = seeds.map { (id, doc) -> CachedList.fromDocument(id, doc) }

    private class MemoryLists : CachedListDao {
        val rows = mutableMapOf<String, CachedList>()
        override suspend fun insertIfMissing(lists: List<CachedList>) {
            lists.forEach { if (it.firestoreId !in rows) rows[it.firestoreId] = it }
        }
        override suspend fun upsert(list: CachedList) { rows[list.firestoreId] = list }
        override suspend fun upsertAll(lists: List<CachedList>) { lists.forEach { upsert(it) } }
        override suspend fun getById(id: String) = rows[id]
        override fun getByIdFlow(id: String) = flowOf(rows[id])
        override fun getAllFlow() = flowOf(rows.values.filterNot { it.deleted })
        override fun getAllForSyncFlow(): Flow<List<CachedList>> = flowOf(rows.values.toList())
        override suspend fun getBySeed(seed: String) = rows.values.firstOrNull { it.seed == seed }
        override suspend fun count() = rows.size
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun deleteAll() { rows.clear() }
        override suspend fun countLocked() = rows.values.count { it.locked }
    }

    private fun bootstrap(repo: ListRepository, android: Boolean = true) = DefaultListsBootstrap(
        FirestoreListsDao { error("Local initialization must not resolve Firestore") },
        repo,
        PreferencesImpl(MapSettings()),
        object : Platform {
            override val name = if (android) "Android" else "iOS"
            override val deviceModelName = "test"
            override suspend fun openUrl(url: String) = error("unused")
            override suspend fun runWithBgTask(name: String, task: suspend () -> Unit) = task()
        },
    )

    @Test
    fun localInitializationCompletesWhileRemoteCreationIsSuspended() = runTest {
        val dao = MemoryLists()
        val repo = ListRepository(dao)
        val started = CompletableDeferred<Unit>()
        val remote = launch {
            reconcileAndroidDefaultLists(repo, { true }, { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }, { false })
        }
        started.await()
        bootstrap(repo).ensureLocal()
        assertEquals(rows.toSet(), dao.rows.values.toSet())
        remote.cancelAndJoin()
    }

    @Test
    fun localInitializationIsAndroidOnlyAndNeverTouchesCloud() = runTest {
        val dao = MemoryLists()
        val repo = ListRepository(dao)
        bootstrap(repo, android = false).ensureLocal()
        assertTrue(dao.rows.isEmpty())
        bootstrap(repo).ensureLocal()
        bootstrap(repo).ensureLocal()
        assertEquals(rows.toSet(), dao.rows.values.toSet())
    }

    @Test
    fun everyDefaultFieldAndStateMustMatchToBeAPlaceholder() {
        for (row in rows) {
            assertTrue(isPristinePlaceholder(row))
            val changed = listOf(
                row.copy(firestoreId = "custom"), row.copy(seed = null),
                row.copy(title = "Personal"), row.copy(icon = "X"), row.copy(listKind = "bullets"),
                row.copy(createdAt = Instant.fromEpochMilliseconds(1)),
                row.copy(updatedAt = Instant.fromEpochMilliseconds(1)),
                row.copy(deleted = true), row.copy(locked = true),
            )
            changed.forEach { assertFalse(isPristinePlaceholder(it), it.toString()) }
        }
    }

    @Test
    fun remoteAbsenceUsesLocalCustomizationAndNeverPromotesOrReplacesRoom() = runTest {
        val dao = MemoryLists()
        val repo = ListRepository(dao)
        bootstrap(repo).ensureLocal()
        val custom = dao.rows.getValue(LIST_TODOS_ID).copy(title = "My tasks", icon = "X", listKind = "bullets")
        dao.upsert(custom)
        val before = dao.rows.toMap()
        val created = mutableMapOf<String, ListDocument>()
        reconcileAndroidDefaultLists(repo, { true }, { id, doc -> created[id] = doc; true }, { error("custom title") })
        assertEquals(custom.toDocument(), created[LIST_TODOS_ID])
        assertEquals(before, dao.rows)
        assertTrue(created.filterKeys { it != LIST_TODOS_ID }.values.all { it.updatedAt > epoch })
    }

    @Test
    fun existingRemoteOrDeferredCreationLeavesRoomAlone() = runTest {
        val dao = MemoryLists()
        val repo = ListRepository(dao)
        bootstrap(repo).ensureLocal()
        val before = dao.rows.toMap()
        assertFalse(reconcileAndroidDefaultLists(repo, { true }, { _, _ -> false }, { false }))
        assertFalse(reconcileAndroidDefaultLists(repo, { true }, { _, _ -> error("offline") }, { false }))
        assertEquals(before, dao.rows)
    }

    @Test
    fun tombstonesArePreservedAndLockedContentIsNeverRecreated() = runTest {
        val dao = MemoryLists()
        val repo = ListRepository(dao)
        rows.forEach { dao.upsert(it.copy(deleted = true)) }
        val locked = rows.first().copy(locked = true, title = "")
        dao.upsert(locked)
        val before = dao.rows.toMap()
        bootstrap(repo).ensureLocal()
        val created = mutableMapOf<String, ListDocument>()
        reconcileAndroidDefaultLists(repo, { true }, { id, doc -> created[id] = doc; true }, { error("deleted Todos") })
        assertTrue(locked.firestoreId !in created)
        assertTrue(created.values.all { it.deleted })
        assertEquals(before, dao.rows)
    }

    @Test
    fun pushBeforePullAndPushAfterFailedPullCannotBatchWritePlaceholders() = runTest {
        // Same boundary called by the automatic observer and syncNow, with no successful pull.
        for (remoteFails in listOf(false, true)) {
            val conditional = mutableListOf<String>()
            val edited = rows.first().copy(title = "Edited")
            val batched = mutableListOf<CachedList>()
            val applied = uploadListDocuments(rows + edited, true, { id, _ ->
                conditional += id
                if (remoteFails) error("offline") else false // document already exists
            }, { batched += it })
            assertEquals(rows.map { it.firestoreId }, conditional)
            assertEquals(listOf(edited), batched)
            assertEquals(listOf(edited), applied)
        }
    }

    @Test
    fun placeholderOnlyUploadNeverCallsBatchAndRemainsRetryable() = runTest {
        var attempts = 0
        repeat(2) {
            val applied = uploadListDocuments(rows, true, { _, doc ->
                attempts++
                assertTrue(doc.updatedAt > epoch)
                true
            }, { error("no ordinary lists") })
            assertTrue(applied.isEmpty())
        }
        assertEquals(6, attempts)
    }

    @Test
    fun iosUploadDoesNotUseAndroidPlaceholderPolicy() = runTest {
        var uploaded = emptyList<CachedList>()
        assertEquals(rows, uploadListDocuments(rows, false, { _, _ -> error("Android only") }, { uploaded = it }))
        assertEquals(rows, uploaded)
    }

    @Test
    fun backupOffPreventsCreationAndMigration() = runTest {
        assertFalse(reconcileAndroidDefaultLists(ListRepository(MemoryLists()), { false },
            { _, _ -> error("backup off") }, { error("backup off") }))
    }

    @Test
    fun backupDisabledDuringReconciliationStopsSubsequentOperations() = runTest {
        var enabled = true
        var creates = 0
        reconcileAndroidDefaultLists(ListRepository(MemoryLists()), { enabled }, { _, _ ->
            creates++
            enabled = false
            true
        }, { error("backup off") })
        assertEquals(1, creates)
    }

    @Test
    fun signInAndEnablingBackupWhileSignedInReconcileWithoutDuplicates() = runTest {
        val accounts = MutableStateFlow<String?>(null)
        val backup = MutableStateFlow(true)
        var calls = 0
        val observer = launch { observeDefaultListsBootstrap(accounts, backup, true, reconcile = { calls++ }) }
        runCurrent()
        assertEquals(0, calls)
        accounts.value = "account-a"
        runCurrent()
        assertEquals(1, calls)
        accounts.value = "account-a"
        runCurrent()
        assertEquals(1, calls)
        backup.value = false
        runCurrent()
        assertEquals(1, calls)
        backup.value = true
        runCurrent()
        assertEquals(2, calls)
        observer.cancelAndJoin()
    }

    @Test
    fun signInWithBackupOffWaitsUntilEnabled() = runTest {
        val accounts = MutableStateFlow<String?>(null)
        val backup = MutableStateFlow(false)
        var calls = 0
        val observer = launch { observeDefaultListsBootstrap(accounts, backup, true, reconcile = { calls++ }) }
        runCurrent()
        accounts.value = "account-a"
        runCurrent()
        assertEquals(0, calls)
        backup.value = true
        runCurrent()
        assertEquals(1, calls)
        observer.cancelAndJoin()
    }

    @Test
    fun accountOrBackupChangesCancelObsoleteReconciliationWithoutOverlap() = runTest {
        val accounts = MutableStateFlow<String?>("account-a")
        val backup = MutableStateFlow(true)
        var active = 0
        var calls = 0
        val observer = launch {
            observeDefaultListsBootstrap(accounts, backup, true, reconcile = {
                calls++
                active++
                assertEquals(1, active)
                try { awaitCancellation() } finally { active-- }
            })
        }
        runCurrent()
        accounts.value = "account-b"
        runCurrent()
        assertEquals(2, calls)
        backup.value = false
        runCurrent()
        assertEquals(0, active)
        observer.cancelAndJoin()
    }

    @Test
    fun iosBootstrapRemainsAuthOnlyEvenWithBackupOff() = runTest {
        val accounts = MutableStateFlow<String?>(null)
        val backup = MutableStateFlow(false)
        var calls = 0
        val observer = launch { observeDefaultListsBootstrap(accounts, backup, false, reconcile = { calls++ }) }
        runCurrent()
        accounts.value = "account-a"
        runCurrent()
        assertEquals(1, calls)
        backup.value = true
        runCurrent()
        assertEquals(1, calls)
        observer.cancelAndJoin()
    }

    @Test
    fun eligibleOfflineFailureRetriesOnceWhenConnectivityReturns() = runTest {
        val accounts = MutableStateFlow<String?>("account-a")
        val backup = MutableStateFlow(true)
        val connectivity = MutableStateFlow(true)
        var calls = 0
        val observer = launch {
            observeDefaultListsBootstrap(accounts, backup, true, reconcile = {
                calls++
                deferDefaultListFailure {
                    if (calls == 1) error("offline")
                    true
                }
            }, connectivityAvailable = connectivity)
        }
        runCurrent()
        assertEquals(1, calls)
        connectivity.value = false
        runCurrent()
        connectivity.value = true
        runCurrent()
        assertEquals(2, calls)
        connectivity.value = true
        runCurrent()
        assertEquals(2, calls)
        observer.cancelAndJoin()
    }

    @Test
    fun connectivityRestorationDoesNotRetryWhenBackupDisabledOrAccountChanges() = runTest {
        val accounts = MutableStateFlow<String?>("account-a")
        val backup = MutableStateFlow(false)
        val connectivity = MutableStateFlow(false)
        var calls = 0
        val observer = launch {
            observeDefaultListsBootstrap(accounts, backup, true, { calls++ }, connectivity)
        }
        runCurrent()
        connectivity.value = true
        runCurrent()
        assertEquals(0, calls)
        backup.value = true
        runCurrent()
        assertEquals(1, calls)
        accounts.value = "account-b"
        connectivity.value = false
        runCurrent()
        connectivity.value = true
        runCurrent()
        assertEquals(2, calls)
        observer.cancelAndJoin()
    }

    @Test
    fun cancellationIsNotDeferredOrFollowedByBatchWrites() = runTest {
        val cancellation = CancellationException("stop")
        var caught: CancellationException? = null
        try {
            uploadListDocuments(rows, true, { _, _ -> throw cancellation }, { error("cancelled") })
        } catch (e: CancellationException) { caught = e }
        assertTrue(caught === cancellation)
        caught = null
        try {
            reconcileAndroidDefaultLists(ListRepository(MemoryLists()), { true },
                { _, _ -> throw cancellation }, { error("cancelled") })
        } catch (e: CancellationException) { caught = e }
        assertTrue(caught === cancellation)
    }

    @Test
    fun newRemindersPlaceholderAllowsLegacyRemoteMigrationWithoutRoomWrite() = runTest {
        val dao = MemoryLists()
        val repo = ListRepository(dao)
        bootstrap(repo).ensureLocal()
        val before = dao.rows.toMap()
        var migrations = 0
        reconcileAndroidDefaultLists(repo, { true }, { _, _ -> false }, { migrations++; true })
        assertEquals(1, migrations)
        assertEquals(before, dao.rows)
    }

    @Test
    fun migrationRequiresUnmodifiedLegacyRemoteTitleAndNondeletedReadableState() {
        val legacy = seeds.first { it.first == LIST_TODOS_ID }.second.copy(title = "Todos")
        assertTrue(DefaultListsBootstrap.isMigratableRemoteTodos(legacy))
        assertTrue(DefaultListsBootstrap.isMigratableRemoteTodos(legacy.copy(icon = "X", listKind = "bullets")))
        assertFalse(DefaultListsBootstrap.isMigratableRemoteTodos(legacy.copy(title = "My tasks")))
        assertFalse(DefaultListsBootstrap.isMigratableRemoteTodos(legacy.copy(title = "Reminders")))
        assertFalse(DefaultListsBootstrap.isMigratableRemoteTodos(legacy.copy(seed = null)))
        assertFalse(DefaultListsBootstrap.isMigratableRemoteTodos(legacy.copy(deleted = true)))
        val local = CachedList.fromDocument(LIST_TODOS_ID, legacy)
        assertTrue(DefaultListsBootstrap.allowsTodosMigration(local))
        assertFalse(DefaultListsBootstrap.allowsTodosMigration(local.copy(title = "My tasks")))
        assertFalse(DefaultListsBootstrap.allowsTodosMigration(local.copy(locked = true)))
        assertFalse(DefaultListsBootstrap.allowsTodosMigration(local.copy(deleted = true)))
    }
}
