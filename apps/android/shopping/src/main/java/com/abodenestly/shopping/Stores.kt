package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.StoreRecord
import com.abodenestly.sync.StoreRow
import com.abodenestly.sync.patch
import com.abodenestly.sync.remove
import com.abodenestly.sync.syncStamp
import com.abodenestly.sync.upsert
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID

/** Why a store name cannot be saved. */
sealed interface StoreNameProblem {
    val message: String

    data object Empty : StoreNameProblem {
        override val message: String get() = "Give the store a name"
    }

    /** [name] is the store that already has the name. */
    data class Duplicate(val name: String) : StoreNameProblem {
        override val message: String get() = "You already have $name"
    }
}

/** A store delete waiting out its undo window. The store is already hidden from the rows. */
data class PendingStoreDelete(val id: String, val name: String) {
    val message: String get() = "Deleted $name"
}

/** DESIGN C4 logic, kept apart from the screens so it is table-testable. Twins: web `Stores.ts`, iOS `Stores`. */
object Stores {
    /** By name, ignoring case; the id breaks ties so the order never flickers. */
    fun sorted(rows: List<StoreRow>): List<StoreRow> = rows.sortedWith(compareBy<StoreRow>({ it.name.lowercase() }, { it.id }))

    /** "learned 3 items"; nothing when no item has a learned position yet. */
    fun learnedLabel(count: Int): String? = if (count <= 0) null else "learned $count ${if (count == 1) "item" else "items"}"

    /** Trimmed and compared ignoring case, against live stores only. [exceptId] leaves one store out. */
    fun validate(name: String, stores: List<StoreRecord>, exceptId: String? = null): StoreNameProblem? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return StoreNameProblem.Empty
        val twin = stores.firstOrNull { it.id != exceptId && it.name.trim().equals(trimmed, ignoreCase = true) }
        return twin?.let { StoreNameProblem.Duplicate(it.name.trim()) }
    }

    /**
     * The `store` row for a new store. The server owns `updated_at` and `sync_seq`, so the local row carries
     * placeholders until the next pull replaces it (same shape as [createList]).
     */
    fun row(id: String, householdId: UUID, name: String, now: Instant): JsonObject {
        val stamp = syncStamp(now)
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("household_id", JsonPrimitive(householdId.toString()))
            put("name", JsonPrimitive(name.trim()))
            put("deleted_at", JsonNull)
            put("created_at", JsonPrimitive(stamp))
            put("updated_at", JsonPrimitive(stamp))
            put("sync_seq", JsonPrimitive(0))
        }
    }

    /** Only `store_id` travels, so a rename made on another device is not overwritten. */
    fun storeFields(storeId: String?): JsonObject = buildJsonObject { put("store_id", storeId?.let { JsonPrimitive(it) } ?: JsonNull) }
}

// Writes (SPEC §6.1): the local row and the outbox row in one transaction. Callers kick sync.

/** Creates a store and queues its upsert. Returns the new store's id. Throws `DatabaseNotOpenException` for another household. */
suspend fun createStore(database: DatabaseRegistry, householdId: UUID, name: String, now: Instant, id: String = UUID.randomUUID().toString()): String {
    database.upsert(householdId, "store", Stores.row(id, householdId, name, now), now)
    return id
}

/** Soft delete. Lists keep their `store_id`: their chip hides a deleted store, and Recently deleted restores the link. */
suspend fun deleteStore(database: DatabaseRegistry, householdId: UUID, id: String, now: Instant) =
    database.remove(householdId, "store", id, now)

/** Points a list at [storeId], or at no store. */
suspend fun setListStore(database: DatabaseRegistry, householdId: UUID, listId: String, storeId: String?, now: Instant) =
    database.patch(householdId, "shopping_list", listId, Stores.storeFields(storeId), now)
