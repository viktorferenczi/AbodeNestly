package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.applyPull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** A `shopping_list` row as a pull delivers it (no outbox row). */
fun pulledList(
    id: String, name: String, createdAt: String = "2026-01-01T00:00:00.000Z",
    template: Boolean = false, archivedAt: String? = null,
): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
    put("is_template", JsonPrimitive(template))
    put("archived_at", archivedAt?.let { JsonPrimitive(it) } ?: JsonNull)
    put("created_at", JsonPrimitive(createdAt))
    put("deleted_at", JsonNull)
}

/** Stores the household row (its `module_shopping`, null for "not known") and [lists] the way a pull does. */
suspend fun DatabaseRegistry.pullHousehold(household: UUID, shopping: Boolean?, vararg lists: JsonObject) {
    val row = buildJsonObject {
        put("id", JsonPrimitive(household.toString()))
        put("module_shopping", shopping?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    applyPull(
        household,
        SyncPullPage(cursor = 1, changes = mapOf("household" to listOf(row), "shopping_list" to lists.toList())),
    )
}
