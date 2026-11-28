package com.abodenestly.sync

import java.util.UUID

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
    /** SHOP-11: copied with the item when a template is added to a list. */
    val barcode: String? = null,
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
    /** SHOP-05: the household's live stores, for the picker, so it needs no second observation. */
    val stores: List<StoreRecord> = emptyList(),
    val items: List<ListItemRecord> = emptyList(),
    val history: List<ListItemRecord> = emptyList(),
    /** SHOP-12: the household's live price observations and the currency to show them in. */
    val prices: List<ItemPriceRecord> = emptyList(),
    val currency: String = DatabaseRegistry.DEFAULT_CURRENCY,
)

/** What a rename must check before it overwrites a row: whether it still exists and what it is called now. */
data class ListItemIdentity(val name: String, val deletedAt: String?)

/** The stored `list_item` row's identity, or null when there is none. Throws [DatabaseNotOpenException] when [householdId] is not the open household. */
suspend fun DatabaseRegistry.listItemIdentity(householdId: UUID, id: String): ListItemIdentity? =
    withOpenFor(householdId) { db -> db.search().listItem(householdId.toString(), id)?.let { ListItemIdentity(it.name, it.deletedAt) } }

/** The live items of one list, read once (SHOP-11 "Add all"). Throws [DatabaseNotOpenException] when [householdId] is not the open household. */
suspend fun DatabaseRegistry.liveListItems(householdId: UUID, listId: String): List<ListItemRecord> =
    withOpenFor(householdId) { db -> db.shoppingLists().liveItems(householdId.toString(), listId).map { DatabaseRegistry.toItemRecord(it) } }
