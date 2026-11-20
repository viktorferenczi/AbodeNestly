package com.abodenestly.core.notificationprefs

import com.abodenestly.sync.NotificationPrefId
import com.abodenestly.sync.NotificationPrefRow
import com.abodenestly.sync.SearchModule
import java.util.UUID

/** The contract's `NotificationType` (SPEC §8), in DESIGN B9 order. */
enum class NotificationType(val key: String, val label: String) {
    EatSoon("eat_soon", "Eat soon"),
    LowStock("low_stock", "Low stock"),
    TaskDue("task_due", "Task due"),
    Warranty("warranty", "Warranty"),
    Activity("activity", "Activity"),
}

/** DESIGN B9 / CORE-12. Twins: iOS `NotificationPrefs`, web `NotificationPrefs.ts`. */
object NotificationPrefs {
    /** Rows of a disabled module are hidden; Activity is core and always stays. */
    fun visibleTypes(modules: Set<SearchModule>): List<NotificationType> = NotificationType.entries.filter { type ->
        when (type) {
            NotificationType.EatSoon, NotificationType.LowStock -> SearchModule.Pantry in modules
            NotificationType.TaskDue, NotificationType.Warranty -> SearchModule.Maintenance in modules
            NotificationType.Activity -> true
        }
    }

    /** A missing row means enabled. */
    fun enabled(rows: List<NotificationPrefRow>, type: NotificationType): Boolean =
        rows.firstOrNull { it.type == type.key }?.enabled ?: true

    /** UUID v5 `row_id` of a switch, the same value on every platform (golden-value test on each). */
    fun rowId(userId: UUID, householdId: UUID, type: NotificationType): UUID = NotificationPrefId.rowId(userId, householdId, type.key)
}
