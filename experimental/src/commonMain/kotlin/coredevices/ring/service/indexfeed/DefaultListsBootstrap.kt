@file:OptIn(ExperimentalTime::class)

package coredevices.ring.service.indexfeed

import co.touchlab.kermit.Logger
import coredevices.indexai.data.entity.ListDocument
import coredevices.ring.data.entity.room.indexfeed.CachedList
import coredevices.ring.database.Preferences
import coredevices.util.Platform
import coredevices.util.isAndroid
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coredevices.ring.database.firestore.dao.FirestoreListsDao
import coredevices.ring.database.room.repository.ListRepository
import dev.gitlive.firebase.firestore.DocumentSnapshot
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Android seeds Room independently; iOS retains its original cloud-first bootstrap. */
class DefaultListsBootstrap(
    private val firestoreDao: FirestoreListsDao,
    private val repo: ListRepository,
    private val preferences: Preferences,
    private val platform: Platform,
) {
    private val logger = Logger.withTag("ListsBootstrap")
    private val cloudMutex = Mutex()

    /** No auth, Firestore access, or cloud mutex on the Android local path. */
    suspend fun ensureLocal() {
        if (platform.isAndroid) repo.insertIfMissing(defaultDocuments(Instant.fromEpochMilliseconds(0)))
    }

    suspend fun ensure(): Boolean {
        if (!platform.isAndroid) return ensureLegacy()
        return cloudMutex.withLock {
            val uid = Firebase.auth.currentUser?.uid ?: return@withLock false
            reconcileAndroidDefaultLists(
                repo = repo,
                canSync = { preferences.backupEnabled.value && Firebase.auth.currentUser?.uid == uid },
                createIfAbsent = { id, doc -> firestoreDao.createListIfAbsent(uid, id, doc) },
                migrateTodos = { firestoreDao.renameDefaultTodosIfUnchanged(uid) },
            )
        }
    }


    /**
     * Idempotent. Returns true when a write happened this call, false when the
     * defaults were already present. Caller can ignore the return value.
     */
    private suspend fun ensureLegacy(): Boolean {
        // Source of truth is Firestore. We check each of the three default
        // lists individually so that if the user deletes one list (or all of
        // them) on the Firebase Console, the next ensure() recreates only the
        // missing ones. Trusting Room here would leave us in sync with a
        // stale local mirror if remote was wiped.
        val now = Clock.System.now()
        val toWrite = mutableListOf<Pair<String, ListDocument>>()

        suspend fun maybeAdd(id: String, doc: () -> ListDocument): DocumentSnapshot? {
            val remote = runCatching { firestoreDao.getListSnapshot(id) }.getOrNull()
            if (remote == null) {
                // Network failure — be conservative and skip. We'll try again
                // on the next auth event.
                logger.w { "ensure: snapshot for $id failed, deferring" }
                return null
            }
            if (!remote.exists) toWrite += id to doc()
            return remote
        }

        maybeAdd(LIST_NOTES_SELF_ID) {
            ListDocument(
                createdAt = now, updatedAt = now,
                title = "Notes to self", icon = "📓",
                listKind = "note", seed = SEED_NOTES_SELF,
            )
        }
        val todosSnapshot = maybeAdd(LIST_TODOS_ID) {
            ListDocument(
                createdAt = now, updatedAt = now,
                title = TODOS_RENAMED_TITLE, icon = "⏰",
                listKind = "note", seed = SEED_TODOS,
            )
        }
        maybeAdd(LIST_SHOPPING_ID) {
            ListDocument(
                createdAt = now, updatedAt = now,
                title = "Shopping list", icon = "🛒",
                listKind = "checklist", seed = SEED_SHOPPING,
            )
        }

        // One-time rename for users seeded before the "Todos" → "Reminders"
        // rename. Gated on the stored title still being the old default, so a
        // user who renamed the list themselves is left untouched. The system
        // list is unencrypted (seed != null), so its title is readable here.
        val renamed = maybeRenameDefaultTodos(todosSnapshot, now)

        if (toWrite.isEmpty()) return renamed
        logger.i { "ensure: writing ${toWrite.size} default list(s): ${toWrite.map { it.first }}" }
        // Bootstrap is the only write path that has to hit Firestore directly
        // (we just confirmed the doc is missing remotely, so we can't rely on
        // the manual Sync-now pipeline to create it later — the user might
        // never tap it). All OTHER list/item writes are Room-only and
        // pushed via Sync-now.
        firestoreDao.writeBatch(toWrite)
        repo.writeBatch(toWrite)
        return true
    }

    /** Returns true when a rename write happened this call. */
    private suspend fun maybeRenameDefaultTodos(snapshot: DocumentSnapshot?, now: Instant): Boolean {
        if (snapshot == null || !snapshot.exists) return false
        val existing = runCatching { snapshot.data<ListDocument>() }.getOrNull() ?: return false
        if (!isDefaultTodosListTitle(existing.seed, existing.title)) return false

        // The Firestore snapshot can lag a local rename that hasn't synced yet.
        // If the local mirror is no longer the default, the user renamed it —
        // don't clobber that with "Reminders".
        val local = repo.getById(LIST_TODOS_ID)
        if (local != null && !isDefaultTodosListTitle(local.seed, local.title)) return false

        val renamed = existing.copy(title = TODOS_RENAMED_TITLE, updatedAt = now)
        logger.i { "ensure: renaming default '$DEFAULT_TODOS_TITLE' list -> '$TODOS_RENAMED_TITLE'" }
        firestoreDao.setList(LIST_TODOS_ID, renamed)
        repo.setList(LIST_TODOS_ID, renamed)
        return true
    }

    companion object {
        // Stable Firestore doc IDs. Ingest references LIST_TODOS_ID directly.
        const val LIST_NOTES_SELF_ID = "list_notes_self"
        const val LIST_TODOS_ID = "list_todos"
        const val LIST_SHOPPING_ID = "list_shopping"

        // Seed marker values stored on ListDocument.seed.
        const val SEED_NOTES_SELF = "notes_self"
        const val SEED_TODOS = "todos"
        const val SEED_SHOPPING = "shopping"

        // The Todos list was originally seeded with this title; it now seeds as
        // TODOS_RENAMED_TITLE and existing users are migrated (see ensure()).
        const val DEFAULT_TODOS_TITLE = "Todos"
        const val TODOS_RENAMED_TITLE = "Reminders"

        internal fun defaultDocuments(now: Instant): List<Pair<String, ListDocument>> = listOf(
            LIST_NOTES_SELF_ID to ListDocument(
                createdAt = now, updatedAt = now, title = "Notes to self", icon = "📓",
                listKind = "note", seed = SEED_NOTES_SELF,
            ),
            LIST_TODOS_ID to ListDocument(
                createdAt = now, updatedAt = now, title = TODOS_RENAMED_TITLE, icon = "⏰",
                listKind = "note", seed = SEED_TODOS,
            ),
            LIST_SHOPPING_ID to ListDocument(
                createdAt = now, updatedAt = now, title = "Shopping list", icon = "🛒",
                listKind = "checklist", seed = SEED_SHOPPING,
            ),
        )

        /** Match the complete local seed, not just old timestamps or a seed marker. */
        internal fun isPristinePlaceholder(list: CachedList): Boolean =
            !list.locked && defaultDocuments(Instant.fromEpochMilliseconds(0)).any { (id, doc) ->
                list.firestoreId == id && list.toDocument() == doc
            }

        internal fun isMigratableRemoteTodos(doc: ListDocument): Boolean =
            !doc.deleted && doc.encrypted == null && isDefaultTodosListTitle(doc.seed, doc.title)

        internal fun allowsTodosMigration(local: CachedList?): Boolean =
            local == null || isPristinePlaceholder(local) ||
                (!local.locked && !local.deleted && isDefaultTodosListTitle(local.seed, local.title))

        /**
         * True when the stored Todos list still has its original default title
         * and is the system-seeded list — i.e. safe to rename to "Reminders".
         * Returns false once renamed, for user-renamed lists, and for any
         * non-system list, so the migration converges and never clobbers a
         * user's own title.
         */
        fun isDefaultTodosListTitle(seed: String?, title: String): Boolean =
            seed == SEED_TODOS && title == DEFAULT_TODOS_TITLE
    }
}

/** Shared startup gate: backup changes matter on Android; iOS remains auth-only. */
internal fun defaultListsBootstrapAccounts(
    accounts: Flow<String?>,
    backupEnabled: Flow<Boolean>,
    android: Boolean,
): Flow<String?> = if (android) {
    combine(accounts, backupEnabled) { uid, enabled -> uid.takeIf { enabled } }.distinctUntilChanged()
} else {
    accounts.distinctUntilChanged()
}

/** Android cancels obsolete account/backup work; retain iOS's sequential auth handling. */
internal suspend fun observeDefaultListsBootstrap(
    accounts: Flow<String?>,
    backupEnabled: Flow<Boolean>,
    android: Boolean,
    reconcile: suspend () -> Unit,
    connectivityAvailable: Flow<Boolean> = flowOf(true),
) {
    val eligible = defaultListsBootstrapAccounts(accounts, backupEnabled, android)
    if (android) {
        combine(eligible, connectivityAvailable.distinctUntilChanged()) { uid, connected -> uid to connected }
            .flatMapLatest { (uid, connected) ->
                if (uid != null && connected) flow<Unit> { reconcile() } else emptyFlow<Unit>()
            }
            .collect { }
    } else {
        eligible.collect { if (it != null) reconcile() }
    }
}

/** Room reads happen before the conditional remote operations, never inside their callbacks. */
internal suspend fun reconcileAndroidDefaultLists(
    repo: ListRepository,
    canSync: () -> Boolean,
    createIfAbsent: suspend (String, ListDocument) -> Boolean,
    migrateTodos: suspend () -> Boolean,
): Boolean {
    var changed = false
    for ((id, default) in DefaultListsBootstrap.defaultDocuments(Clock.System.now())) {
        if (!canSync()) return changed
        val local = repo.getById(id)
        // A locked row has no usable cleartext. Do not recreate it from blanked content.
        if (local?.locked == true) continue
        val candidate = if (local == null || DefaultListsBootstrap.isPristinePlaceholder(local)) {
            default
        } else {
            local.toDocument()
        }
        if (!canSync()) return changed
        changed = deferDefaultListFailure { createIfAbsent(id, candidate) } || changed
    }
    val localTodos = if (canSync()) repo.getById(DefaultListsBootstrap.LIST_TODOS_ID) else return changed
    if (canSync() && DefaultListsBootstrap.allowsTodosMigration(localTodos)) {
        changed = deferDefaultListFailure { migrateTodos() } || changed
    }
    // No promotion/upsert here. The existing pull path applies newer remote documents.
    return changed
}

internal suspend fun deferDefaultListFailure(operation: suspend () -> Boolean): Boolean = try {
    operation()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.withTag("ListsBootstrap").w(e) { "Default list cloud operation deferred" }
    false
}
