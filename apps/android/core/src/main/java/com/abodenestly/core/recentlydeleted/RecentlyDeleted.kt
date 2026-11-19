package com.abodenestly.core.recentlydeleted

import com.abodenestly.sync.DeletedRow
import com.abodenestly.sync.SearchModule
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** A restorable row with its days left; `maint_task` supplies its `title` as [name]. */
data class DeletedItem(val table: String, val id: String, val name: String, val deletedAt: Instant, val daysLeft: Int) {
    val key: String get() = "$table:$id"
}

data class DeletedSection(val module: SearchModule, val items: List<DeletedItem>)

/** DESIGN B11 / CORE-10. Twins: iOS `RecentlyDeleted`, web `RecentlyDeleted.ts`. */
object RecentlyDeleted {
    /** SPEC §5: the server purges rows soft-deleted for more than this many days. */
    const val RETENTION_DAYS = 30

    /** Whole days elapsed are subtracted, so a row deleted a moment ago has 30 days left; a deleted_at in the future (clock skew) counts as just now. */
    fun daysLeft(deletedAt: Instant, now: Instant): Int {
        val elapsed = Duration.between(deletedAt, now).coerceAtLeast(Duration.ZERO)
        return RETENTION_DAYS - elapsed.toDays().toInt()
    }

    /**
     * One section per enabled module, in the order Shopping, Pantry, Maintenance. Rows with no days left are dropped: the
     * server has purged (or is about to purge) them and sends no tombstone, so this age filter keeps them off the screen
     * (AC4). Rows of a module not in [modules] are dropped too (AC5). Newest deleted first.
     */
    fun sections(rows: List<DeletedRow>, modules: Set<SearchModule>, now: Instant): List<DeletedSection> =
        SearchModule.entries.filter { it in modules }.map { module ->
            val items = rows.mapNotNull { row ->
                if (row.table !in module.tables) return@mapNotNull null
                val at = parse(row.deletedAt) ?: return@mapNotNull null
                DeletedItem(row.table, row.id, row.name, at, daysLeft(at, now)).takeIf { it.daysLeft > 0 }
            }
            DeletedSection(module, items.sortedWith(compareByDescending<DeletedItem> { it.deletedAt }.thenBy { it.key }))
        }

    private fun parse(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}
