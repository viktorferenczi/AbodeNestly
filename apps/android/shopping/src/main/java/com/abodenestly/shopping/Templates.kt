package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.sync.ListRecord
import com.abodenestly.sync.liveListItems
import com.abodenestly.sync.upsertAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID

/** An entry of the C2 "List actions" menu. */
enum class ListAction { Share, AddAll, ClearChecked }

/** What "Add all to list" did. */
sealed interface AddAllResult {
    data class Added(val count: Int, val listName: String) : AddAllResult
    data object Empty : AddAllResult
    data object Failed : AddAllResult

    /** The status line for a result; a failure is shown in the sheet instead. */
    val message: String?
        get() = when (this) {
            is Added -> "Added $count ${if (count == 1) "item" else "items"} to $listName"
            Empty -> EMPTY_TEMPLATE_MESSAGE
            Failed -> null
        }
}

const val EMPTY_TEMPLATE_MESSAGE = "Nothing to add: this template is empty"
const val ADD_ALL_ERROR = "Could not add the items. Try again."

/** How long the status line stays (no Undo). */
const val STATUS_MILLIS = 5_000L

/**
 * DESIGN C1/C2 template logic (SHOP-11), kept apart from the views so it is table-testable. Twins: web `Templates.ts`,
 * iOS `Templates`.
 */
object Templates {
    /** Lists a template can be added to: active (not archived; deleted rows are never read) and not a template, oldest first like C1. */
    fun targets(lists: List<ListRecord>): List<ListRecord> =
        lists.filter { it.archivedAt == null && !it.isTemplate }.sortedWith(compareBy<ListRecord>({ it.createdAt ?: "" }, { it.id }))

    /** The template's live items in the order C2 shows them: unchecked oldest first, then checked, newest check first. */
    fun displayOrder(items: List<ListItemRecord>, templateId: String): List<ListItemRecord> {
        val live = items.filter { it.listId == templateId && it.deletedAt == null }
        val unchecked = live.filter { !it.checked }.sortedWith(compareBy<ListItemRecord>({ it.createdAt ?: "" }, { it.id }))
        val checked = live.filter { it.checked }.sortedWith(CheckOff.newestFirst)
        return unchecked + checked
    }

    /**
     * One new `list_item` row per live template item: fresh id, name, quantity, unit, note and barcode copied, and
     * everything else a manual add's defaults (unchecked, no position, no pantry or task link, `created_by` the current
     * user). Each row's `created_at` is one millisecond after the previous one, so C2's oldest-first sort keeps the
     * template's order locally; `created_at` is server-owned, so the order collapses after the next pull.
     */
    fun copies(
        items: List<ListItemRecord>,
        templateId: String,
        targetListId: String,
        householdId: UUID,
        userId: UUID?,
        now: Instant,
        uuid: () -> String = { UUID.randomUUID().toString() },
    ): List<JsonObject> = displayOrder(items, templateId).mapIndexed { index, source ->
        val row = QuickAdd.row(
            uuid(), targetListId, source.name, userId, now.plusMillis(index.toLong()),
            quantity = source.quantity, unit = source.unit, note = source.note, barcode = source.barcode,
        )
        // The name as stored (QuickAdd.row trims it), and the household the local row belongs to.
        buildJsonObject {
            row.forEach { (key, value) -> put(key, value) }
            put("name", JsonPrimitive(source.name))
            put("household_id", JsonPrimitive(householdId.toString()))
        }
    }

    /**
     * Copies every live item of [templateId] (a delete still inside its undo window is still live, as on web and iOS) onto [targetListId] in
     * one transaction and returns how many. Nothing is written for an empty template. The caller kicks sync once
     * afterwards, and only when the count is above 0.
     */
    suspend fun addAllToList(
        database: DatabaseRegistry,
        householdId: UUID,
        templateId: String,
        targetListId: String,
        userId: UUID?,
        now: Instant,
        uuid: () -> String = { UUID.randomUUID().toString() },
    ): Int {
        val items = database.liveListItems(householdId, templateId)
        val rows = copies(items, templateId, targetListId, householdId, userId, now, uuid)
        if (rows.isEmpty()) return 0
        database.upsertAll(householdId, "list_item", rows, now)
        return rows.size
    }

    /**
     * The List actions menu. "Share" (SHOP-13) is first when [canShare]: the list has loaded and is not gone, and the app can
     * share. On a template "Add all to list" follows; "Clear checked" only when something is checked; a normal list never
     * offers Add all.
     */
    fun actions(isTemplate: Boolean, hasChecked: Boolean, canShare: Boolean = false): List<ListAction> = buildList {
        if (canShare) add(ListAction.Share)
        if (isTemplate) add(ListAction.AddAll)
        if (hasChecked) add(ListAction.ClearChecked)
    }
}
