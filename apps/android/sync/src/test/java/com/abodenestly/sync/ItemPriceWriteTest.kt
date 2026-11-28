package com.abodenestly.sync

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal

/** SHOP-12: [writeItem] stores the item change and the price in one transaction, and money never passes through a Double. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemPriceWriteTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun priceRow(id: String, price: BigDecimal, storeId: String? = "s1"): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("store_id", storeId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("name_key", JsonPrimitive("milk"))
        put("price", JsonPrimitive(price))
        put("seen_at", JsonPrimitive("2026-11-26T08:00:00.000Z"))
    }

    @Test
    fun `an item and its price are stored and queued together`() = runBlocking {
        registry.open(household)
        registry.writeItem(household, ItemChange.Upsert(listItem("i1", "Milk")), priceRow("p1", BigDecimal("1.10")))

        val queued = registry.outboxForTesting()
        assertEquals(listOf("list_item", "item_price"), queued.map { it.tableName })
        // name_key is sent for item_price (and only there); server-owned columns are not.
        assertTrue(queued[1].data, queued[1].data.contains("\"name_key\":\"milk\""))
        assertTrue(queued[1].data, queued[1].data.contains("\"price\":1.10"))
        assertEquals("1.1", registry.observeListDetail(household, "l1").first().prices.single().price.toPlainString())
    }

    @Test
    fun `a price alone, or an item patch alone, writes just that`() = runBlocking {
        registry.open(household)
        registry.writeItem(household, null, priceRow("p1", BigDecimal("2")))
        assertEquals(listOf("item_price"), registry.outboxForTesting().map { it.tableName })
        registry.writeItem(household, null, null)
        assertEquals(1, registry.outboxForTesting().size)
    }

    @Test
    fun `when the price cannot be stored the item change is rolled back with it`() = runBlocking {
        registry.open(household)
        // No store_id: the NOT NULL column refuses the local row after both mutations were queued.
        val failed = runCatching { registry.writeItem(household, ItemChange.Upsert(listItem("i1", "Milk")), priceRow("p1", BigDecimal("1.5"), storeId = null)) }
        assertTrue(failed.isFailure)
        assertEquals(0, registry.outboxForTesting().size)
        assertNull(registry.withSearchDao { it.listItem(household.toString(), "i1") })
    }

    @Test
    fun `a pulled price is stored as canonical text whether the server sent a number or a string`() = runBlocking {
        registry.open(household)
        fun row(id: String, price: kotlinx.serialization.json.JsonElement) = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("store_id", JsonPrimitive("s1"))
            put("name_key", JsonPrimitive("milk"))
            put("price", price)
            put("seen_at", JsonPrimitive("2026-11-26T08:00:00.000Z"))
        }
        registry.applyPull(
            household,
            SyncPullPage(
                cursor = 1,
                changes = mapOf("item_price" to listOf(row("a", JsonPrimitive(BigDecimal("0.30"))), row("b", JsonPrimitive("19.990")), row("c", JsonPrimitive(BigDecimal("10.00"))))),
            ),
        )
        val stored = registry.observeListDetail(household, "l1").first().prices.associate { it.id to it.price.toPlainString() }
        assertEquals(mapOf("a" to "0.3", "b" to "19.99", "c" to "10"), stored)
    }

    @Test
    fun `canonicalPrice drops trailing zeros and never uses an exponent`() {
        assertEquals("1.1", canonicalPrice(BigDecimal("1.10")).toString())
        assertEquals("10", canonicalPrice(BigDecimal("10.00")).toString())
        assertEquals("0", canonicalPrice(BigDecimal("0.00")).toString())
        assertEquals("0.01", canonicalPrice(BigDecimal("0.010")).toString())
        assertEquals("1000", priceText(BigDecimal("1E+3")))
    }
}
