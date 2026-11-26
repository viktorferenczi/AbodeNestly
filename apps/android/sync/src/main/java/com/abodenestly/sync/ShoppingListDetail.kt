package com.abodenestly.sync

/**
 * The columns of a `list_item` row the C2 detail and its autocomplete read (SHOP-02). Local projection, not a contract
 * entity (same as [ListRecord]). Stamps are fixed-width UTC text, so comparing the text compares the time. [nameKey] is
 * null on a row stored without one; callers then derive it from [name].
 */
data class ListItemRecord(
    val id: String,
    val listId: String,
    val name: String,
    val nameKey: String?,
    val checked: Boolean,
    val deletedAt: String?,
    val createdAt: String?,
)

/**
 * One emission of [DatabaseRegistry.observeListDetail]. [list] is null when the list is unknown or deleted. [items] are
 * the live items of this list; [history] is the household's live items plus those that were checked before they were
 * cleared or deleted, from every list.
 */
data class ListDetail(
    val list: ListRecord? = null,
    /** The list's store when it points to a live store. */
    val storeName: String? = null,
    val items: List<ListItemRecord> = emptyList(),
    val history: List<ListItemRecord> = emptyList(),
)
