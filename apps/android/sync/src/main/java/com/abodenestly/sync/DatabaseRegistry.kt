package com.abodenestly.sync

import android.content.Context
import com.abodenestly.contract.models.Activity
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The modules whose names CORE-09 searches, with the local tables each owns (SPEC §4 CORE-09; open question in the story). */
enum class SearchModule(val key: String, val tables: List<String>) {
    Shopping("shopping", listOf("list_item", "store")),
    Pantry("pantry", listOf("pantry_item", "location", "recipe")),
    Maintenance("maintenance", listOf("asset", "maint_task", "contractor")),
}

/** One name found in a local table. [listId] is set for list items only (the list they sit on). */
data class SearchRow(
    val table: String,
    val id: String,
    val name: String,
    val listId: String? = null,
    val deletedAt: String? = null,
)

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
            val db = Room.databaseBuilder(context.applicationContext, HouseholdDatabase::class.java, fileName(id)).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
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
            val db = Room.databaseBuilder(context.applicationContext, HouseholdDatabase::class.java, fileName(id)).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
            try {
                db.outbox().count()
            } finally {
                db.close()
            }
        }
    }

    // Activity (CORE-08)

    /**
     * Stores pulled rows of household [id]. Dropped when [id] is not the open household: a pull that was in flight
     * when the person switched must not write into the new household's file.
     */
    suspend fun upsertActivity(id: UUID, rows: List<Activity>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val open = database
            if (open == null || mutableCurrent.value != id) return@withLock
            val own = rows.filter { it.householdId == id }
            if (own.isNotEmpty()) open.activity().upsertAll(own.map(::toEntity))
        }
    }

    /**
     * The household's newest stored rows, re-emitted when they change (cached rows read offline, SPEC §6.5). Follows the
     * open database: nothing is emitted while [id] is not the open household, and a switch away ends the inner flow.
     */
    fun activityFeed(id: UUID): Flow<List<Activity>> = current.flatMapLatest { open ->
        val db = database
        if (open != id || db == null) emptyFlow()
        else db.activity().observeFeed(id.toString(), FEED_ROWS)
            .map { rows -> rows.mapNotNull(::toActivity) }
            // The database may close under the collector during a switch.
            .catch { }
    }

    // Search (CORE-09)

    /**
     * The live rows of [modules]' tables, re-emitted when those tables change; a blank [query] reads nothing. The
     * name match is `Search.group`'s, so every live row of the household comes back. Only enabled modules' tables are queried (AC4). Follows the open database like
     * [activityFeed]; nothing is emitted while [id] is not the open household.
     */
    fun searchNames(id: UUID, query: String, modules: Set<SearchModule>): Flow<List<SearchRow>> {
        val q = query.trim()
        if (q.isEmpty() || modules.isEmpty()) return flowOf(emptyList())
        return current.flatMapLatest { open ->
            val db = database
            if (open != id || db == null) emptyFlow()
            else {
                val dao = db.search()
                val hid = id.toString()
                val tables = modules.flatMap { it.tables }.toSet()
                val flows = buildList<Flow<List<SearchRow>>> {
                    if ("list_item" in tables) add(dao.listItems(hid).map { r -> r.map { SearchRow("list_item", it.id, it.name, it.listId, it.deletedAt) } })
                    if ("store" in tables) add(dao.stores(hid).map { r -> r.map { SearchRow("store", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("pantry_item" in tables) add(dao.pantryItems(hid).map { r -> r.map { SearchRow("pantry_item", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("location" in tables) add(dao.locations(hid).map { r -> r.map { SearchRow("location", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("recipe" in tables) add(dao.recipes(hid).map { r -> r.map { SearchRow("recipe", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("asset" in tables) add(dao.assets(hid).map { r -> r.map { SearchRow("asset", it.id, it.name, deletedAt = it.deletedAt) } })
                    if ("maint_task" in tables) add(dao.maintTasks(hid).map { r -> r.map { SearchRow("maint_task", it.id, it.title, deletedAt = it.deletedAt) } })
                    if ("contractor" in tables) add(dao.contractors(hid).map { r -> r.map { SearchRow("contractor", it.id, it.name, deletedAt = it.deletedAt) } })
                }
                combine(flows) { parts -> parts.toList().flatten() }
                    // The database may close under the collector during a switch.
                    .catch { }
            }
        }
    }

    /** Test seam (and the future module stories' pull): runs [block] against the search DAO of the open household. */
    suspend fun <T> withSearchDao(block: suspend (SearchDao) -> T): T = withOpen { block(it.search()) }

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
        const val FEED_ROWS = 100

        /** Fixed width, so comparing the text compares the time. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun stamp(at: OffsetDateTime): String = STAMP.format(at)

        fun toEntity(a: Activity) = ActivityEntity(
            id = a.id.toString(), householdId = a.householdId.toString(), actorId = a.actorId?.toString(),
            module = a.module.value, entity = a.entity, entityId = a.entityId.toString(), action = a.action,
            summary = a.summary, createdAt = stamp(a.createdAt), updatedAt = stamp(a.updatedAt),
            deletedAt = a.deletedAt?.let(::stamp), syncSeq = a.syncSeq,
        )

        fun toActivity(e: ActivityEntity): Activity? = runCatching {
            Activity(
                id = UUID.fromString(e.id), householdId = UUID.fromString(e.householdId),
                actorId = e.actorId?.let(UUID::fromString),
                module = Activity.Module.entries.first { it.value == e.module },
                entity = e.entity, entityId = UUID.fromString(e.entityId), action = e.action, summary = e.summary,
                createdAt = OffsetDateTime.parse(e.createdAt), updatedAt = OffsetDateTime.parse(e.updatedAt),
                deletedAt = e.deletedAt?.let(OffsetDateTime::parse), syncSeq = e.syncSeq,
            )
        }.getOrNull()
    }
}
