package com.abodenestly.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/** Tables the server accepts in `POST /sync` today (SHOP-08-api). Everything else stays queued and is never sent. */
val PUSHABLE_TABLES: Set<String> = setOf("store", "shopping_list", "list_item", "location")

/** The household-scoped tables SPEC §6.2 lets a client write. Names go into SQL, so [Outbox] checks against this set. */
internal val WRITABLE_TABLES: Set<String> = PUSHABLE_TABLES + setOf("pantry_item", "recipe", "asset", "maint_task", "contractor")

/** Columns the server owns; they never go out in `data` (SPEC §6.2). */
internal val SERVER_OWNED_KEYS: Set<String> = setOf("id", "household_id", "created_at", "updated_at", "sync_seq", "name_key", "share_token")

/** The chip's numbers: queued writes the server accepts, and failed ones. */
data class SyncCounts(val pending: Int, val failed: Int)

/** A change the server turned down, as the sheet lists it. [reason] is the wire text (`rls_denied`, …). */
data class FailedChange(val seq: Long, val table: String, val op: String, val rowId: String, val reason: String, val detail: String?)

// Writes (SPEC §6.1): the local row and the outbox row in one transaction, so the two cannot diverge. Callers kick sync.

/**
 * Upserts [row] (must carry `id`) into the local [table] and queues the SPEC §6.2 upsert. An existing local row keeps
 * the columns [row] does not name. Throws [DatabaseNotOpenException] when [householdId] is not the open household.
 */
suspend fun DatabaseRegistry.upsert(householdId: UUID, table: String, row: JsonObject, now: Instant = Instant.now()) {
    requireWritable(table)
    val rowId = (row["id"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("A row needs an id")
    withOpenFor(householdId) { db ->
        db.runInTransaction(Callable {
            val raw = db.openHelper.writableDatabase
            // Queue first: a local row that cannot be stored (NOT NULL) must take the queued mutation back with it.
            queue(raw, table, "upsert", rowId, row.without(SERVER_OWNED_KEYS), now)
            writeLocal(raw, table, householdId, rowId, row)
            Unit
        })
    }
}

/**
 * Changes some columns of one row: updates the local row and queues a partial upsert (the server merges it, so a
 * rename made elsewhere survives). With [localOnly] only the local row changes, for columns the server does not hold.
 */
suspend fun DatabaseRegistry.patch(householdId: UUID, table: String, id: String, fields: JsonObject, now: Instant = Instant.now(), localOnly: Boolean = false) {
    requireWritable(table)
    withOpenFor(householdId) { db ->
        db.runInTransaction(Callable {
            val raw = db.openHelper.writableDatabase
            val cv = contentValues(raw, table, fields.without(setOf("id")))
            // A table with a local copy but no such row: nothing changed, so nothing is queued (a bare partial upsert would insert a bare row on the server).
            if (hasLocalTable(raw, table) && !rowExists(raw, table, id)) return@Callable Unit
            if (cv.size() > 0) raw.update(table, SQLiteDatabase.CONFLICT_NONE, cv, "id = ?", arrayOf(id))
            // Nothing left after dropping server-owned columns (SPEC §6.2): nothing to send.
            val sendable = fields.without(SERVER_OWNED_KEYS)
            if (!localOnly && sendable.isNotEmpty()) queue(raw, table, "upsert", id, sendable, now)
            Unit
        })
    }
}

/** Soft delete (`deleted_at`), locally and as a queued `delete`. */
suspend fun DatabaseRegistry.remove(householdId: UUID, table: String, id: String, now: Instant = Instant.now()) {
    requireWritable(table)
    withOpenFor(householdId) { db ->
        db.runInTransaction(Callable {
            val raw = db.openHelper.writableDatabase
            if (hasLocalTable(raw, table) && !rowExists(raw, table, id)) return@Callable Unit
            if (columnsOf(raw, table).contains("deleted_at")) {
                val cv = ContentValues().apply { put("deleted_at", DatabaseRegistry.stamp(now.atOffset(java.time.ZoneOffset.UTC))) }
                raw.update(table, SQLiteDatabase.CONFLICT_NONE, cv, "id = ?", arrayOf(id))
            }
            queue(raw, table, "delete", id, null, now)
            Unit
        })
    }
}

/**
 * Queues a pantry quantity delta (SPEC §6.2). Never write `pantry_item.quantity` directly. Queue-only for now: the
 * optimistic local update arrives with PANT-* when the local `pantry_item` table has a quantity.
 */
suspend fun DatabaseRegistry.delta(householdId: UUID, pantryItemId: String, delta: Double, reason: String, now: Instant = Instant.now()) {
    withOpenFor(householdId) { db ->
        db.runInTransaction(Callable {
            val data = buildJsonObject { put("field", "quantity"); put("delta", delta); put("reason", reason) }
            queue(db.openHelper.writableDatabase, "pantry_item", "delta", pantryItemId, data, now)
            Unit
        })
    }
}

private fun requireWritable(table: String) = require(table in WRITABLE_TABLES) { "Not a syncable table: $table" }

private fun JsonObject.without(keys: Set<String>): JsonObject = JsonObject(filterKeys { it !in keys })

private fun queue(raw: SupportSQLiteDatabase, table: String, op: String, rowId: String, data: JsonObject?, now: Instant) {
    val cv = ContentValues().apply {
        put("id", UUID.randomUUID().toString())
        put("table_name", table)
        put("op", op)
        put("row_id", rowId)
        put("data", (data ?: JsonObject(emptyMap())).toString())
        put("client_ts", now.toString())
    }
    raw.insert("outbox", SQLiteDatabase.CONFLICT_ABORT, cv)
}

private fun hasLocalTable(raw: SupportSQLiteDatabase, table: String) = columnsOf(raw, table).isNotEmpty()

private fun rowExists(raw: SupportSQLiteDatabase, table: String, id: String): Boolean =
    raw.query("SELECT 1 FROM `$table` WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

private fun writeLocal(raw: SupportSQLiteDatabase, table: String, householdId: UUID, rowId: String, row: JsonObject) {
    val columns = columnsOf(raw, table)
    if (columns.isEmpty()) return // a table with no local copy yet (pantry, …): only the queued write matters
    val cv = contentValues(raw, table, row.without(setOf("id")))
    if ("household_id" in columns) cv.put("household_id", householdId.toString())
    if (rowExists(raw, table, rowId)) {
        if (cv.size() > 0) raw.update(table, SQLiteDatabase.CONFLICT_NONE, cv, "id = ?", arrayOf(rowId))
    } else {
        cv.put("id", rowId)
        raw.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
    }
}

// JSON → SQLite

private val PLAIN_NAME = Regex("[a-z_][a-z0-9_]*")

private val columnCache = ConcurrentHashMap<String, Set<String>>()

/** The columns of a local table (empty when the table does not exist), read once per database file. */
internal fun columnsOf(raw: SupportSQLiteDatabase, table: String): Set<String> =
    // Pulled table names come from the server and go into SQL: only plain identifiers get that far (same gate as iOS).
    if (!PLAIN_NAME.matches(table)) emptySet() else columnCache.getOrPut("${raw.path}#$table") {
        raw.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildSet { while (c.moveToNext()) add(c.getString(name)) }
        }
    }

/** [row]'s keys the local [table] has, as SQLite values. Unknown keys are ignored (a newer server may send more). */
internal fun contentValues(raw: SupportSQLiteDatabase, table: String, row: JsonObject): ContentValues {
    val columns = columnsOf(raw, table)
    return ContentValues().apply { row.forEach { (key, value) -> if (key in columns) putJson(key, value) } }
}

private fun ContentValues.putJson(key: String, value: JsonElement) {
    when {
        value is JsonNull -> putNull(key)
        value is JsonPrimitive && value.isString ->
            put(key, if (key.endsWith("_at")) DatabaseRegistry.normalizeStamp(value.content) else value.content)
        value is JsonPrimitive -> {
            val bool = value.booleanOrNull
            val long = value.longOrNull
            val double = value.doubleOrNull
            when {
                bool != null -> put(key, if (bool) 1L else 0L)
                long != null -> put(key, long)
                double != null -> put(key, double)
                else -> put(key, value.content)
            }
        }
        else -> put(key, value.toString()) // arrays and objects are stored as JSON text
    }
}
