@file:OptIn(kotlin.time.ExperimentalTime::class)

package coredevices.coreapp.ring.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.russhwolf.settings.SharedPreferencesSettings
import coredevices.ring.data.entity.room.indexfeed.CachedList
import coredevices.ring.database.PreferencesImpl
import coredevices.ring.database.firestore.dao.FirestoreListsDao
import coredevices.ring.database.room.RingDatabase
import coredevices.ring.database.room.repository.ListRepository
import coredevices.ring.service.indexfeed.DefaultListsBootstrap
import coredevices.ring.service.indexfeed.reconcileAndroidDefaultLists
import coredevices.util.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Real Room/repository coverage. Does not install or configure Firebase. */
class DefaultListsBootstrapRoomTest {
    private lateinit var db: RingDatabase
    private lateinit var repo: ListRepository
    private lateinit var bootstrap: DefaultListsBootstrap
    private val defaults = DefaultListsBootstrap.defaultDocuments(Instant.fromEpochMilliseconds(0))

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<RingDatabase>(context = context.applicationContext)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ListRepository(db.cachedListDao())
        bootstrap = DefaultListsBootstrap(
            FirestoreListsDao { error("Local bootstrap must not resolve Firestore") },
            repo,
            PreferencesImpl(
                SharedPreferencesSettings(
                    InstrumentationRegistry.getInstrumentation().targetContext
                        .getSharedPreferences("default_lists_bootstrap_test", 0),
                ),
            ),
            object : Platform {
                override val name = "Android"
                override val deviceModelName = "test"
                override suspend fun openUrl(url: String) = error("unused")
                override suspend fun runWithBgTask(name: String, task: suspend () -> Unit) = task()
            },
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun repeatedConcurrentBootstrapCreatesExactlyThreeRows() = runBlocking {
        coroutineScope {
            repeat(24) { launch(Dispatchers.Default) { bootstrap.ensureLocal() } }
        }
        assertEquals(3, repo.localCount())
        for ((id, doc) in defaults) assertEquals(CachedList.fromDocument(id, doc), repo.getById(id))
    }

    @Test
    fun insertIgnorePreservesCustomDeletedAndLockedRowsIncludingEpochEdits() = runBlocking {
        val original = defaults.mapIndexed { index, (id, doc) ->
            CachedList.fromDocument(id, doc).let {
                when (index) {
                    0 -> it.copy(title = "My notes", icon = "X", listKind = "bullets")
                    1 -> it.copy(deleted = true)
                    else -> it.copy(locked = true, title = "")
                }
            }
        }
        db.cachedListDao().upsertAll(original)
        coroutineScope {
            repeat(24) { launch(Dispatchers.Default) { bootstrap.ensureLocal() } }
        }
        assertEquals(original.toSet(), repo.getAllForSyncFlow().first().toSet())
        // The new bootstrap operation must not change the normal upsert contract.
        val edited = original.first().toDocument().copy(title = "Edited again")
        repo.setList(original.first().firestoreId, edited)
        assertEquals(edited, repo.getById(original.first().firestoreId)?.toDocument())
    }

    @Test
    fun concurrentOrdinaryEditCannotBeReplacedByBootstrap() = runBlocking {
        val (id, doc) = defaults.first()
        val edited = doc.copy(title = "Concurrent edit")
        coroutineScope {
            repeat(24) { launch(Dispatchers.Default) { bootstrap.ensureLocal() } }
            launch(Dispatchers.Default) { repo.setList(id, edited) }
        }
        assertEquals(edited, repo.getById(id)?.toDocument())
    }

    @Test
    fun localRowsAreVisibleWhileRemoteCreationWaits() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val remote = launch {
            reconcileAndroidDefaultLists(repo, { true }, { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }, { false })
        }
        try {
            started.await()
            bootstrap.ensureLocal()
            assertEquals(3, repo.getAllFlow().first().size)
            assertTrue(repo.getAllFlow().first().all(DefaultListsBootstrap::isPristinePlaceholder))
        } finally {
            remote.cancelAndJoin()
        }
    }

    @Test
    fun remoteCreationPreservesCustomizedRoomRowsAndPlaceholderTimestamps() = runBlocking {
        bootstrap.ensureLocal()
        val (id, doc) = defaults.first()
        repo.setList(id, doc.copy(title = "Personal notes"))
        val before = repo.getAllForSyncFlow().first().toSet()
        reconcileAndroidDefaultLists(repo, { true }, { candidateId, candidate ->
            if (candidateId == id) assertEquals("Personal notes", candidate.title)
            true
        }, { false })
        assertEquals(before, repo.getAllForSyncFlow().first().toSet())
    }
}
