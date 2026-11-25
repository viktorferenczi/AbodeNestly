package com.abodenestly.sync

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Tables that exist only on this device; a pull must never write them. */
private val LOCAL_ONLY_TABLES = setOf("outbox", "outbox_failed", "meta", "attachment_outbox")

/**
 * Applies one pulled page to household [householdId]'s file: every known row by key (tombstones included), then the
 * cursor, in one transaction (SPEC §6.3). Returns `false` and writes nothing when that household is not the open one
 * any more, so a pull in flight during a switch cannot write into the new file. Rows with a queued or failed change
 * of their own are skipped: the local value wins until its push lands. Unknown tables and columns are ignored.
 */
suspend fun DatabaseRegistry.applyPull(householdId: UUID, page: SyncPullPage): Boolean = try {
    withOpenFor(householdId) { db ->
        db.runInTransaction(Callable {
            val raw = db.openHelper.writableDatabase
            val queued = buildSet {
                raw.query("SELECT table_name, row_id FROM outbox UNION SELECT table_name, row_id FROM outbox_failed").use { c ->
                    while (c.moveToNext()) add("${c.getString(0)}:${c.getString(1)}")
                }
            }
            for ((table, rows) in page.changes) {
                if (table in LOCAL_ONLY_TABLES || columnsOf(raw, table).isEmpty()) continue
                for (row in rows) {
                    val id = (row["id"] as? JsonPrimitive)?.contentOrNull
                    if (id != null && "$table:$id" in queued) continue
                    val cv = contentValues(raw, table, row)
                    if (cv.size() > 0) raw.insert(table, SQLiteDatabase.CONFLICT_REPLACE, cv)
                }
            }
            raw.insert("meta", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                put("key", DatabaseRegistry.CURSOR)
                put("value", page.cursor.toString())
            })
            true
        })
    }
} catch (e: DatabaseNotOpenException) {
    false
}

// What the engine needs from the open household's file. All of them name the household so a switch cannot redirect them.

/** The pull cursor of household [householdId]. */
internal suspend fun DatabaseRegistry.cursorOf(householdId: UUID): Long =
    withOpenFor(householdId) { it.meta().get(DatabaseRegistry.CURSOR)?.toLongOrNull() ?: 0L }

/** The next batch to push: queued rows of [PUSHABLE_TABLES] in insertion order. */
internal suspend fun DatabaseRegistry.pendingBatch(householdId: UUID, limit: Int): List<OutboxEntity> =
    withOpenFor(householdId) { it.outbox().pushable(PUSHABLE_TABLES.toList(), limit) }

/** Removes the rows the server [applied] and moves the [rejected] ones to `outbox_failed`, in one transaction. */
internal suspend fun DatabaseRegistry.settlePush(householdId: UUID, applied: Collection<String>, rejected: List<SyncRejected>, now: Instant) {
    withOpenFor(householdId) { db ->
        db.withTransaction {
            val dao = db.outbox()
            for (r in rejected) {
                val row = dao.byId(r.id) ?: continue
                dao.insertFailed(OutboxFailedEntity(row.seq, row.id, row.tableName, row.op, row.rowId, row.data, row.clientTs, r.reason, r.detail, now.toString()))
                dao.deleteByIds(listOf(row.id))
            }
            if (applied.isNotEmpty()) dao.deleteByIds(applied.toList())
        }
    }
}

/** Puts a failed change back in the queue at its old `seq`, keeping its mutation id. */
internal suspend fun DatabaseRegistry.retryFailed(householdId: UUID, seq: Long) {
    withOpenFor(householdId) { db ->
        db.withTransaction {
            val dao = db.outbox()
            val failed = dao.failedBySeq(seq) ?: return@withTransaction
            dao.insert(OutboxEntity(failed.seq, failed.id, failed.tableName, failed.op, failed.rowId, failed.data, failed.clientTs))
            dao.deleteFailed(seq)
        }
    }
}

/**
 * Drops a failed change. When nothing else targets its row, the local row goes too (a row the server never took must
 * not stay on screen), and the cursor returns to 0 so the next pull brings the server's version of anything dropped.
 */
internal suspend fun DatabaseRegistry.discardFailed(householdId: UUID, seq: Long) {
    withOpenFor(householdId) { db ->
        db.withTransaction {
            val dao = db.outbox()
            val failed = dao.failedBySeq(seq) ?: return@withTransaction
            dao.deleteFailed(seq)
            val otherwise = dao.countTargeting(failed.tableName, failed.rowId) + dao.countFailedTargeting(failed.tableName, failed.rowId)
            if (otherwise == 0 && failed.tableName in PUSHABLE_TABLES) {
                db.openHelper.writableDatabase.delete(failed.tableName, "id = ?", arrayOf(failed.rowId))
            }
            db.meta().put(MetaEntity(DatabaseRegistry.CURSOR, "0"))
        }
    }
}

/** Whether household [householdId] has a queued write the server accepts; the app asks when it goes to the background. */
suspend fun DatabaseRegistry.hasPushablePending(householdId: UUID): Boolean =
    try {
        withOpenFor(householdId) { it.outbox().pushable(PUSHABLE_TABLES.toList(), 1).isNotEmpty() }
    } catch (_: DatabaseNotOpenException) {
        false
    }
