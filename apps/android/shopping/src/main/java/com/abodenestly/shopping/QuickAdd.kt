package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.sync.syncStamp
import com.abodenestly.sync.upsert
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID

/** One autocomplete line: [label] is what the row shows, [key] the `name_key` it matched on. */
data class Suggestion(val key: String, val label: String)

const val SUGGESTION_LIMIT = 5

/** SHOP-02 logic, kept apart from the view so it is table-testable. Twins: web `QuickAdd.ts`, iOS `QuickAdd`. */
object QuickAdd {
    private fun key(item: ListItemRecord): String = item.nameKey?.takeIf { it.isNotEmpty() } ?: NameKey.make(item.name)

    private class Group(var label: String, var latest: String, var count: Int)

    private class Ranked(val key: String, val label: String, val prefix: Boolean, val count: Int, val latest: String)

    /**
     * Suggestions for [query], matched on `name_key`. A name matches when its key, or any word in it, starts with the
     * query ("mi" finds Milk and Oat milk, not Salami). Rows show the casing of the household's most recent entry.
     * Names already unchecked on [listId] are left out so a suggestion never makes a duplicate line. [history] is
     * expected to hold live or once-checked rows only (the read model's `history`); deleted unchecked rows are skipped anyway.
     */
    fun suggest(history: List<ListItemRecord>, query: String, listId: String, limit: Int = SUGGESTION_LIMIT): List<Suggestion> {
        val q = NameKey.make(query)
        if (q.isEmpty()) return emptyList()
        val onList = history.filter { it.listId == listId && it.deletedAt == null && !it.checked }.map(::key).toSet()
        val groups = LinkedHashMap<String, Group>()
        for (item in history) {
            if (item.deletedAt != null && !item.checked) continue
            val k = key(item)
            val created = item.createdAt ?: ""
            val group = groups[k]
            if (group == null) {
                groups[k] = Group(item.name, created, 1)
            } else {
                group.count += 1
                if (created > group.latest) {
                    group.label = item.name
                    group.latest = created
                }
            }
        }
        val ranked = ArrayList<Ranked>()
        for ((k, group) in groups) {
            if (k in onList) continue
            val prefix = k.startsWith(q)
            if (!prefix && k.split(' ').none { it.startsWith(q) }) continue
            ranked += Ranked(k, group.label, prefix, group.count, group.latest)
        }
        val order = compareByDescending<Ranked> { it.prefix }
            .thenByDescending { it.count }
            .thenByDescending { it.latest }
            .thenBy { it.key }
        return ranked.sortedWith(order).take(limit).map { Suggestion(it.key, it.label) }
    }

    /**
     * The full `list_item` row for a manual add. The server derives `name_key` itself; the local value only serves
     * matching until the next pull replaces the row, and `upsert` never queues it (nor the stamps and `sync_seq`).
     */
    fun row(
        id: String, listId: String, name: String, userId: UUID?, now: Instant,
        quantity: Double? = null, unit: String? = null, note: String? = null,
        /** SHOP-10: only for a product a lookup found; `list_item.barcode` is a foreign key to `product`. */
        barcode: String? = null,
    ): JsonObject {
        val trimmed = name.trim()
        val stamp = syncStamp(now)
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("list_id", JsonPrimitive(listId))
            put("name", JsonPrimitive(trimmed))
            put("name_key", JsonPrimitive(NameKey.make(trimmed)))
            put("quantity", quantity?.let { JsonPrimitive(it) } ?: JsonNull)
            put("unit", unit?.let { JsonPrimitive(it) } ?: JsonNull)
            put("note", note?.let { JsonPrimitive(it) } ?: JsonNull)
            put("barcode", barcode?.let { JsonPrimitive(it) } ?: JsonNull)
            put("checked", JsonPrimitive(false))
            put("checked_at", JsonNull)
            put("checked_by", JsonNull)
            put("manual_position", JsonNull)
            put("source", JsonPrimitive("manual"))
            put("pantry_item_id", JsonNull)
            put("maint_task_id", JsonNull)
            put("created_by", userId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("deleted_at", JsonNull)
            put("created_at", JsonPrimitive(stamp))
            put("updated_at", JsonPrimitive(stamp))
            put("sync_seq", JsonPrimitive(0))
        }
    }
}

/** Adds an unchecked row through the outbox (local row and queued upsert in one transaction). Callers kick sync. */
suspend fun addItem(database: DatabaseRegistry, householdId: UUID, row: JsonObject, now: Instant) =
    database.upsert(householdId, "list_item", row, now)
