package com.abodenestly.sync

/**
 * The columns of a `shopping_list` row the C1 overview reads. Local projection, not a contract entity (the Kotlin
 * `:contract` has no shopping types; same as iOS `ListRecord`). Stamps are fixed-width UTC text, so comparing the text
 * compares the time.
 */
data class ListRecord(
    val id: String,
    val name: String,
    val storeId: String?,
    val isTemplate: Boolean,
    val archivedAt: String?,
    val createdAt: String?,
)

/** A live store, for the chip and the picker. */
data class StoreRecord(val id: String, val name: String)

/** Live items of one list: [checked] of [all]. */
data class ItemCount(val checked: Int, val all: Int)

/** One emission of [DatabaseRegistry.observeListOverview]. [counts] is keyed by list id. */
data class ListOverview(
    val lists: List<ListRecord> = emptyList(),
    val counts: Map<String, ItemCount> = emptyMap(),
    val stores: List<StoreRecord> = emptyList(),
)

/** [at] as the fixed-width UTC text the local tables and the outbox use (`2026-11-26T08:00:00.000Z`). */
fun syncStamp(at: java.time.Instant): String = DatabaseRegistry.stamp(at.atOffset(java.time.ZoneOffset.UTC))
