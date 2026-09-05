@file:OptIn(kotlin.time.ExperimentalTime::class)

package coredevices.ring.database.firestore.dao

import coredevices.firestore.CollectionDao
import coredevices.indexai.data.entity.ListDocument
import coredevices.ring.service.indexfeed.DefaultListsBootstrap
import kotlin.time.Clock
import dev.gitlive.firebase.firestore.DocumentReference
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Firestore DAO for lists. Path: `lists/{uid}/lists/{listId}`.
 * The 3 system seed lists (`list_notes_self`, `list_todos`, `list_shopping`) live
 * here alongside any user-created lists.
 */
class FirestoreListsDao(dbProvider: () -> FirebaseFirestore) : CollectionDao("lists", dbProvider) {
    private val collection get() = authenticatedId?.let { db.collection("$it/lists") }
        ?: throw IllegalStateException("Not authenticated — cannot access lists")

    /** The account/reference is fixed before entering a callback that Firestore can retry. */
    suspend fun createListIfAbsent(uid: String, id: String, list: ListDocument): Boolean {
        val firestore = db
        val reference = firestore.collection("lists/$uid/lists").document(id)
        return firestore.runTransaction {
            if (get(reference).exists) {
                false
            } else {
                set(reference, list)
                true
            }
        }
    }

    /** Android migration: recheck the current document on every transaction attempt. */
    suspend fun renameDefaultTodosIfUnchanged(uid: String): Boolean {
        val firestore = db
        val reference = firestore.collection("lists/$uid/lists")
            .document(DefaultListsBootstrap.LIST_TODOS_ID)
        val now = Clock.System.now()
        return firestore.runTransaction {
            val snapshot = get(reference)
            if (!snapshot.exists) return@runTransaction false
            val existing = snapshot.data<ListDocument>()
            if (!DefaultListsBootstrap.isMigratableRemoteTodos(existing)) return@runTransaction false
            // Merge only these two fields, retaining concurrent icon/kind edits and unknown fields.
            set(reference, existing.copy(
                title = DefaultListsBootstrap.TODOS_RENAMED_TITLE,
                updatedAt = maxOf(now, existing.updatedAt),
            ), "title", "updatedAt")
            true
        }
    }

    suspend fun addList(list: ListDocument): DocumentReference {
        return collection.add(list)
    }

    suspend fun setList(id: String, list: ListDocument) {
        collection.document(id).set(list)
    }

    suspend fun deleteList(id: String) {
        collection.document(id).delete()
    }

    fun getList(id: String): DocumentReference {
        return collection.document(id)
    }

    suspend fun getListSnapshot(id: String): DocumentSnapshot {
        return collection.document(id).get()
    }

    fun changesFlow(): Flow<QuerySnapshot> {
        return collection.snapshots
    }

    suspend fun getAll(): QuerySnapshot {
        return collection.get()
    }

    /** Ordinary sync writes and the legacy iOS bootstrap. Android placeholders use create-if-absent. */
    suspend fun writeBatch(lists: List<Pair<String, ListDocument>>) {
        if (lists.isEmpty()) return
        val batch = db.batch()
        for ((id, doc) in lists) {
            batch.set(collection.document(id), doc)
        }
        batch.commit()
    }
}
