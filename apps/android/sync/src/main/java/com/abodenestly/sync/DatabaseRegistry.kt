package com.abodenestly.sync

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** A read or write that needs the open household database, with none open. */
class DatabaseNotOpenException : IllegalStateException("No household database is open")

/** A row of the placeholder `shopping_list` table. */
data class ShoppingList(val id: UUID, val householdId: UUID, val name: String)

/**
 * Opens and closes the household databases; port of iOS `HouseholdDatabase` (the actor). Switching closes the
 * database of the household being left before the next opens. [seedLists] (E2E only) names one list to insert
 * when a household is opened without any.
 */
open class DatabaseRegistry(
    private val context: Context,
    private val seedLists: Map<UUID, String> = emptyMap(),
) {
    private val mutex = Mutex()
    private var database: HouseholdDatabase? = null
    private val mutableCurrent = MutableStateFlow<UUID?>(null)

    /** The household whose database is open, if any. */
    val current: StateFlow<UUID?> = mutableCurrent.asStateFlow()

    fun fileName(id: UUID): String = "household-$id.db"

    /**
     * Opens (creating it on first use) the household's file and seeds `meta.cursor = 0`. Opening the open one is a no-op.
     * [isCurrent] is asked under the registry lock once the file is ready, before anything is swapped: when it says the
     * caller is stale (signed out, another account) the new file is closed and the open one stays; returns `false`.
     */
    open suspend fun open(id: UUID, isCurrent: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (mutableCurrent.value == id && database != null) return@withLock isCurrent()
            val db = Room.databaseBuilder(context.applicationContext, HouseholdDatabase::class.java, fileName(id)).build()
            try {
                db.meta().putIfAbsent(MetaEntity(CURSOR, "0"))
                val seed = seedLists[id]
                if (seed != null && db.shoppingLists().all().isEmpty()) {
                    db.shoppingLists().upsert(ShoppingListEntity(UUID.randomUUID().toString(), id.toString(), seed))
                }
            } catch (e: Throwable) {
                db.close()
                throw e
            }
            if (!isCurrent()) {
                db.close()
                return@withLock false
            }
            closeLocked()
            database = db
            mutableCurrent.value = id
            true
        }
    }

    /** Releases the open database. Safe to call when nothing is open. */
    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { closeLocked() } }

    private fun closeLocked() {
        database?.close()
        database = null
        mutableCurrent.value = null
    }

    // Cursor: SHOP-08's pull loop moves it; this story only stores it.

    /** `last_seq` is a bigint (SPEC §6.1), so the cursor is a `Long`. */
    suspend fun readCursor(): Long = withOpen { it.meta().get(CURSOR)?.toLongOrNull() ?: 0L }

    suspend fun writeCursor(value: Long) = withOpen { it.meta().put(MetaEntity(CURSOR, value.toString())) }

    /**
     * Queued writes of a household. Counts on the open database when it is that household's own; otherwise a
     * short-lived read of the file, if it exists (a household never opened has nothing queued).
     */
    suspend fun pendingCount(id: UUID): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val open = database
            if (open != null && mutableCurrent.value == id) return@withLock open.outbox().count()
            if (!context.applicationContext.getDatabasePath(fileName(id)).exists()) return@withLock 0
            val db = Room.databaseBuilder(context.applicationContext, HouseholdDatabase::class.java, fileName(id)).build()
            try {
                db.outbox().count()
            } finally {
                db.close()
            }
        }
    }

    // Placeholder lists (replaced by SHOP-01/08).

    suspend fun lists(): List<ShoppingList> = withOpen { db ->
        db.shoppingLists().all().mapNotNull { row ->
            val id = runCatching { UUID.fromString(row.id) }.getOrNull()
            val household = runCatching { UUID.fromString(row.householdId) }.getOrNull()
            if (id == null || household == null) null else ShoppingList(id, household, row.name)
        }
    }

    suspend fun insertList(list: ShoppingList) = withOpen {
        it.shoppingLists().upsert(ShoppingListEntity(list.id.toString(), list.householdId.toString(), list.name))
    }

    /** Test seam: queues one mutation so pending counts have something to count. */
    suspend fun enqueueForTesting(table: String = "shopping_list", rowId: UUID = UUID.randomUUID()) = withOpen {
        it.outbox().insert(
            OutboxEntity(id = UUID.randomUUID().toString(), tableName = table, op = "upsert", rowId = rowId.toString(), data = "{}", clientTs = Instant.now().toString()),
        )
    }

    private suspend fun <T> withOpen(block: suspend (HouseholdDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(database ?: throw DatabaseNotOpenException()) }
    }

    private companion object {
        const val CURSOR = "cursor"
    }
}
