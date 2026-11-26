package com.abodenestly.sync

/**
 * The columns of a `list_item` row the C2 detail, its autocomplete and the C3 sheet read (SHOP-02, SHOP-03). Local projection, not a contract
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
    /** SHOP-03: all three are null until the user sets them (C3). */
    val quantity: Double? = null,
    val unit: String? = null,
    val note: String? = null,
    /** SHOP-04: when it was checked off (UTC stamp); null while unchecked or for a row checked before the stamp existed. */
    val checkedAt: String? = null,
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
