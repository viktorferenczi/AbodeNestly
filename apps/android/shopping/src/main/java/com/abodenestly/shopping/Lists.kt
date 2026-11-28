package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListOverview
import com.abodenestly.sync.ListRecord
import com.abodenestly.sync.patch
import com.abodenestly.sync.remove
import com.abodenestly.sync.syncStamp
import com.abodenestly.sync.upsert
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID

/** How long a swipe waits before it is written; Undo inside the window cancels it (DESIGN A6). */
const val UNDO_MILLIS = 5_000L

/** One row of the C1 overview. */
data class ListRowModel(
    val id: String,
    val name: String,
    /** The list's store when it points to a live store. */
    val storeName: String?,
    /** "3 of 12" (checked of live items), or "No items". */
    val count: String,
)

/**
 * The three C1 sections. DESIGN C1 logic, kept apart from the screen so it is table-testable. Twins: web `Lists.ts`,
 * iOS `Lists.sections`.
 */
data class ListsSections(
    val lists: List<ListRowModel> = emptyList(),
    val templates: List<ListRowModel> = emptyList(),
    val archived: List<ListRowModel> = emptyList(),
) {
    /**
     * DESIGN C1 `empty`: the household has no list at all, archived ones included (iOS `ListSections.isEmpty`, web
     * `empty`). An archived-only household stays populated: the Lists header, the Archived section and the FAB.
     */
    val isEmpty: Boolean get() = lists.isEmpty() && templates.isEmpty() && archived.isEmpty()

    companion object {
        /**
         * Splits a household's lists into Lists, Templates and Archived. Lists and Templates: oldest first (a list with no
         * `created_at` yet comes first, then by name), so the default list leads and new ones land at the bottom. Archived:
         * newest archived first. [hidden] are lists with a swipe still inside its undo window.
         */
        fun make(overview: ListOverview, hidden: Set<String> = emptySet()): ListsSections {
            val storeNames = overview.stores.associate { it.id to it.name }
            fun row(list: ListRecord): ListRowModel {
                val count = overview.counts[list.id]
                return ListRowModel(
                    id = list.id,
                    name = list.name,
                    storeName = list.storeId?.let { storeNames[it] },
                    count = if (count == null) "No items" else "${count.checked} of ${count.all}",
                )
            }
            val live = overview.lists.filter { it.id !in hidden }
            // Text order is time order (fixed-width UTC stamps); the name and id break ties so the order never flickers.
            val byCreated = compareBy<ListRecord>({ it.createdAt ?: "" }, { it.name.lowercase() }, { it.id })
            val active = live.filter { it.archivedAt == null }.sortedWith(byCreated)
            return ListsSections(
                lists = active.filter { !it.isTemplate }.map(::row),
                templates = active.filter { it.isTemplate }.map(::row),
                archived = live.filter { it.archivedAt != null }.sortedByDescending { it.archivedAt }.map(::row),
            )
        }
    }
}

// Writes (SPEC §6.1): the local row and the outbox row in one transaction. Callers kick sync.

/**
 * Creates a list and queues its upsert. The server owns `updated_at` and `sync_seq`; the local row carries a
 * `created_at` of its own so the list sorts last until the next pull replaces it. Returns the new list's id.
 * Throws `DatabaseNotOpenException` when [householdId] is not the open household.
 */
suspend fun createList(
    database: DatabaseRegistry,
    householdId: UUID,
    name: String,
    storeId: String?,
    now: Instant,
    id: String = UUID.randomUUID().toString(),
    /** SHOP-11: a template files under Templates and offers Add all to list. */
    isTemplate: Boolean = false,
): String {
    val stamp = syncStamp(now)
    database.upsert(
        householdId, "shopping_list",
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name.trim()))
            put("store_id", storeId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("is_template", JsonPrimitive(isTemplate))
            put("archived_at", JsonNull)
            put("deleted_at", JsonNull)
            put("created_at", JsonPrimitive(stamp))
            put("updated_at", JsonPrimitive(stamp))
            put("sync_seq", JsonPrimitive(0))
        },
        now,
    )
    return id
}

// Only the changed column travels, so a rename made on another device is not overwritten.

suspend fun archiveList(database: DatabaseRegistry, householdId: UUID, id: String, now: Instant) =
    database.patch(householdId, "shopping_list", id, buildJsonObject { put("archived_at", JsonPrimitive(syncStamp(now))) }, now)

suspend fun restoreList(database: DatabaseRegistry, householdId: UUID, id: String, now: Instant) =
    database.patch(householdId, "shopping_list", id, buildJsonObject { put("archived_at", JsonNull) }, now)

suspend fun deleteList(database: DatabaseRegistry, householdId: UUID, id: String, now: Instant) =
    database.remove(householdId, "shopping_list", id, now)
