package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.applyPull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.util.UUID

/** An `item_price` row as a pull delivers it: the price is a JSON number carrying exactly [price]'s digits. */
fun pulledPrice(id: String, storeId: String, nameKey: String, price: String, seenAt: String, createdAt: String = seenAt): JsonObject = buildJsonObject {
    put("id", id)
    put("store_id", storeId)
    put("name_key", nameKey)
    put("price", JsonPrimitive(BigDecimal(price)))
    put("seen_at", seenAt)
    put("created_at", createdAt)
    put("deleted_at", JsonNull)
}

/** Stores [rows] the way a pull does (no outbox row), so a test starts from prices that came from the server. */
suspend fun DatabaseRegistry.pullPrices(household: UUID, vararg rows: JsonObject) {
    applyPull(household, SyncPullPage(cursor = 1, changes = mapOf("item_price" to rows.toList())))
}
