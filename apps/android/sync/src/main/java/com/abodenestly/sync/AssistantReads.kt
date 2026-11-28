package com.abodenestly.sync

import java.util.UUID

/**
 * SHOP-15: the local household row's `module_shopping`. Null when the row has not been pulled yet (or the flag is still
 * empty), so the caller can tell "off" from "not known". Twin: iOS `HouseholdDatabase.shoppingModuleEnabled`.
 * Throws [DatabaseNotOpenException] when [householdId] is not the open household.
 */
suspend fun DatabaseRegistry.shoppingModuleEnabled(householdId: UUID): Boolean? =
    withOpenFor(householdId) { it.shoppingLists().moduleShopping(householdId.toString()) }

/** SHOP-15: the household's live lists, read once. Throws [DatabaseNotOpenException] when [householdId] is not the open one. */
suspend fun DatabaseRegistry.shoppingListRecords(householdId: UUID): List<ListRecord> =
    withOpenFor(householdId) { db -> db.shoppingLists().liveLists(householdId.toString()).map { DatabaseRegistry.toRecord(it) } }
