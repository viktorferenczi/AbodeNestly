package com.abodenestly.shopping

import com.abodenestly.sync.ListItemRecord
import com.abodenestly.sync.syncStamp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/** SHOP-04 logic, kept apart from the view model so it is table-testable. Twins: web `CheckOff.ts`, iOS `CheckOff`. */
object CheckOff {
    /** The three columns a check-off sets; `checked_at` is the millisecond UTC text. */
    fun checkFields(userId: UUID?, now: Instant): JsonObject = JsonObject(
        mapOf(
            "checked" to JsonPrimitive(true),
            "checked_at" to JsonPrimitive(syncStamp(now)),
            "checked_by" to (userId?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        ),
    )

    /** Unchecking puts all three back. */
    fun uncheckFields(): JsonObject = JsonObject(
        mapOf("checked" to JsonPrimitive(false), "checked_at" to JsonNull, "checked_by" to JsonNull),
    )

    /** The all-checked state (AC4): nothing left to buy and nothing about to be checked, but something checked. */
    fun allChecked(unchecked: Int, pendingChecks: Int, checked: Int): Boolean = unchecked == 0 && pendingChecks == 0 && checked > 0

    /** Newest check-off first; a row without `checkedAt` falls back to `createdAt`, then the id, so the order is stable. */
    val newestFirst: Comparator<ListItemRecord> =
        compareByDescending<ListItemRecord> { it.checkedAt ?: "" }
            .thenByDescending { it.createdAt ?: "" }
            .thenBy { it.id }
}

/** An action inside its undo window. The change is written when the window ends, so Undo has nothing to take back. */
sealed interface PendingAction {
    /** The toast's and the timer's key. A delete or check is keyed by its item (one action per item), a clear by its own id. */
    val id: String
    val message: String

    /** Item ids this action hides from the list. A pending check hides nothing: the row shows ticked in place. */
    val hiddenIds: List<String> get() = emptyList()

    data class Delete(override val id: String, val name: String) : PendingAction {
        override val message: String get() = "Deleted $name"
        override val hiddenIds: List<String> get() = listOf(id)
    }

    data class Check(override val id: String, val name: String) : PendingAction {
        override val message: String get() = "Checked $name"
    }

    data class Clear(override val id: String, val itemIds: List<String>) : PendingAction {
        override val message: String get() = if (itemIds.size == 1) "Cleared 1 item" else "Cleared ${itemIds.size} items"
        override val hiddenIds: List<String> get() = itemIds
    }
}
