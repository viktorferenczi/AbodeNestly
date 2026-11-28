package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ItemPriceRecord
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.PUSHABLE_TABLES
import com.abodenestly.sync.StoreRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** SHOP-12: the parse, lookup and format rules (table-driven), and the write path on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PriceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val gate = CompletableDeferred<Unit>()
    private val ids = AtomicInteger()

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    // --- parse ---

    @Test
    fun `parse accepts blank, whole numbers and at most two decimals with a dot or a comma`() {
        assertEquals(PriceParse.Empty, Price.parse(""))
        assertEquals(PriceParse.Empty, Price.parse("   "))
        for ((text, plain) in listOf("1.20" to "1.2", "1,20" to "1.2", "0,3" to "0.3", "19.99" to "19.99", "12" to "12", "0" to "0", "10.00" to "10", " 2,5 " to "2.5")) {
            val parsed = Price.parse(text)
            assertTrue("$text -> $parsed", parsed is PriceParse.Value)
            assertEquals(text, plain, (parsed as PriceParse.Value).value.toPlainString())
        }
    }

    @Test
    fun `parse rejects negatives, three decimals, letters, stray marks and non-ASCII digits`() {
        for (text in listOf("-1", "1.234", "abc", "1,", ".5", "1.2.3", "1 2", "1e3", "€1", "\u0661\u0662")) {
            assertEquals(text, PriceParse.Invalid, Price.parse(text))
        }
    }

    @Test
    fun `a parsed price keeps its digits where a Double could not`() {
        val value = (Price.parse("0,3") as PriceParse.Value).value
        assertEquals(0, BigDecimal("0.3").compareTo(value))
        assertEquals("0.3", JsonPrimitive(value).toString())
        assertEquals("10", JsonPrimitive((Price.parse("10.00") as PriceParse.Value).value).toString())
    }

    // --- lookup ---

    private fun rec(id: String, store: String, key: String, price: String, seenAt: String, createdAt: String? = null, deletedAt: String? = null) =
        ItemPriceRecord(id, store, key, BigDecimal(price), seenAt, createdAt, deletedAt)

    @Test
    fun `lastPrice picks the newest seen_at, then created_at, then id, and skips deleted rows`() {
        val rows = listOf(
            rec("a", "s1", "milk", "1.00", "2026-11-01T00:00:00.000Z"),
            rec("b", "s1", "milk", "1.10", "2026-11-02T00:00:00.000Z"),
            rec("c", "s1", "milk", "9.99", "2026-11-09T00:00:00.000Z", deletedAt = "2026-11-10T00:00:00.000Z"),
            rec("d", "s2", "milk", "0.80", "2026-11-20T00:00:00.000Z"),
            rec("e", "s1", "bread", "2.00", "2026-11-20T00:00:00.000Z"),
        )
        assertEquals("b", Price.lastPrice(rows, "s1", "milk")!!.id)
        assertEquals("d", Price.lastPriceAnywhere(rows, "milk")!!.id)
        assertNull(Price.lastPrice(rows, "s3", "milk"))
        val tie = listOf(
            rec("a", "s1", "milk", "1", "2026-11-02T00:00:00.000Z", createdAt = "2026-11-02T00:00:01.000Z"),
            rec("b", "s1", "milk", "2", "2026-11-02T00:00:00.000Z", createdAt = "2026-11-02T00:00:02.000Z"),
        )
        assertEquals("b", Price.lastPrice(tie, "s1", "milk")!!.id)
        val sameStamp = listOf(rec("a", "s1", "milk", "1", "2026-11-02T00:00:00.000Z"), rec("b", "s1", "milk", "2", "2026-11-02T00:00:00.000Z"))
        assertEquals("b", Price.lastPrice(sameStamp, "s1", "milk")!!.id)
    }

    @Test
    fun `lastBought uses the list store when there is one, else any store, and drops the name of a deleted store`() {
        val prices = listOf(
            rec("a", "s1", "milk", "1.20", "2026-11-03T10:00:00.000Z"),
            rec("b", "s2", "milk", "0.99", "2026-11-10T10:00:00.000Z"),
        )
        val stores = listOf(StoreRecord("s1", "Test Market"))
        val atStore = Price.lastBought("  MILK ", prices, stores, "s1")!!
        assertEquals("1.20", atStore.price.toPlainString())
        assertEquals("Test Market", atStore.storeName)
        val anywhere = Price.lastBought("Milk", prices, stores, null)!!
        assertEquals("0.99", anywhere.price.toPlainString())
        assertNull("s2 is not a live store", anywhere.storeName)
        assertNull(Price.lastBought("Bread", prices, stores, "s1"))
        assertNull(Price.lastBought("   ", prices, stores, null))
        assertNull(Price.lastBought("Milk", prices, stores, "s3"))
    }

    // --- format ---

    @Test
    fun `the caption and the sheet line show the money in the household currency`() {
        val last = LastBought(BigDecimal("1.20"), "2026-11-03T10:00:00.000Z", "Test Market")
        assertEquals("€1.20 · Test Market", Price.caption(last, "EUR", Locale.UK))
        assertEquals("€1.20", Price.caption(last.copy(storeName = null), "EUR", Locale.UK))
        assertEquals("Last bought: 3 Nov, €1.20 at Test Market", Price.lastBoughtLine(last, "EUR", Locale.UK, ZoneOffset.UTC))
        assertEquals("Last bought: 3 Nov, €1.20", Price.lastBoughtLine(last.copy(storeName = null), "EUR", Locale.UK, ZoneOffset.UTC))
        assertEquals("£19.99 · Test Market", Price.caption(last.copy(price = BigDecimal("19.99")), "GBP", Locale.UK))
    }

    // --- the row ---

    @Test
    fun `the price row carries the store, the normalised name_key, the digits and seen_at`() {
        val row = Price.row("p1", hid, "s1", "  Whole  Milk", BigDecimal("1.10"), now)
        assertEquals("whole milk", row["name_key"]!!.jsonPrimitive.content)
        assertEquals("s1", row["store_id"]!!.jsonPrimitive.content)
        assertEquals("2026-11-26T08:00:00.000Z", row["seen_at"]!!.jsonPrimitive.content)
        assertEquals("1.1", row["price"]!!.toString())
        assertFalse(row["price"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `item_price is pushed`() {
        assertTrue("item_price" in PUSHABLE_TABLES)
    }

    // --- the write (AC1) ---

    private fun viewModel(): ListDetailViewModel = ListDetailViewModel(
        household, "l1", uuid(7), registry, kick = { }, isOnline = MutableStateFlow(true),
        delayer = { gate.await() }, now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
    )

    private suspend fun seed(withStore: Boolean = true) {
        registry.open(household)
        if (withStore) createStore(registry, household, "Test Market", now, "s1")
        createList(registry, household, "Groceries", if (withStore) "s1" else null, now, "l1")
        registry.withSearchDao {
            it.upsertListItem(ListItemEntity("i1", hid, "l1", "Milk", nameKey = "milk", checked = false, createdAt = "2026-11-01T00:00:00.000Z"))
        }
    }

    private fun outbox(table: String) = runBlocking { registry.outboxForTesting().filter { it.tableName == table } }

    private suspend fun ready(vm: ListDetailViewModel) = awaitUntil { vm.state.value.loaded && vm.state.value.detail.items.isNotEmpty() }

    @Test
    fun `AC1 a new item with a price queues the item and one item_price for that store and name_key`() = runBlocking<Unit> {
        seed()
        val vm = viewModel()
        ready(vm)

        vm.onEvent(ListDetailEvent.OpenNew("  Whole  Milk"))
        awaitUntil { vm.state.value.sheet != null }
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "  Whole  Milk", priceText = "1,10")))
        awaitUntil { vm.state.value.sheet == null }

        assertEquals(1, outbox("list_item").size)
        val queued = outbox("item_price").single()
        assertEquals("upsert", queued.op)
        val data = Json.parseToJsonElement(queued.data).jsonObject
        assertEquals("s1", data["store_id"]!!.jsonPrimitive.content)
        assertEquals("whole milk", data["name_key"]!!.jsonPrimitive.content)
        assertEquals("2026-11-26T08:00:00.000Z", data["seen_at"]!!.jsonPrimitive.content)
        assertFalse(data["price"]!!.jsonPrimitive.isString)
        // The exact digits, in the stored JSON text itself.
        assertTrue(queued.data, queued.data.contains("\"price\":1.1,") || queued.data.contains("\"price\":1.1}"))
        assertFalse(data.keys.any { it in setOf("id", "household_id", "created_at", "updated_at", "sync_seq") })
        val local = registry.observeListDetail(household, "l1").first().prices.single()
        assertEquals("1.1", local.price.toPlainString())
        assertEquals("whole milk", local.nameKey)
    }

    @Test
    fun `prices round-trip exactly through parse, the local row text and the outbox JSON text`() = runBlocking<Unit> {
        seed()
        val vm = viewModel()
        ready(vm)
        for ((typed, digits) in listOf("1.10" to "1.1", "0,3" to "0.3", "19.99" to "19.99")) {
            vm.onEvent(ListDetailEvent.OpenEdit("i1"))
            awaitUntil { vm.state.value.sheet != null }
            vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk", priceText = typed)))
            awaitUntil { vm.state.value.sheet == null }
            val text = outbox("item_price").last().data
            assertTrue("$typed in $text", text.contains("\"price\":$digits,") || text.contains("\"price\":$digits}"))
        }
        // BigDecimal keeps its scale, so "1.1" (not "1.10") proves the local TEXT column holds the canonical digits.
        val stored = registry.observeListDetail(household, "l1").first().prices.map { it.price.toPlainString() }.sorted()
        assertEquals(listOf("0.3", "1.1", "19.99"), stored)
    }

    @Test
    fun `an empty price writes nothing, and a price-only save still writes and closes the sheet`() = runBlocking<Unit> {
        seed()
        val vm = viewModel()
        ready(vm)

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        awaitUntil { vm.state.value.sheet != null }
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk")))
        awaitUntil { vm.state.value.sheet == null }
        assertEquals(0, outbox("list_item").size)
        assertEquals(0, outbox("item_price").size)

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        awaitUntil { vm.state.value.sheet != null }
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk", priceText = "2")))
        awaitUntil { vm.state.value.sheet == null }
        assertEquals("a price-only save patches no item column", 0, outbox("list_item").size)
        assertEquals(1, outbox("item_price").size)
    }

    @Test
    fun `an invalid price cannot be saved`() {
        for (text in listOf("-1", "1.234", "abc")) {
            assertNull(text, ItemSheetLogic.fields(ItemForm(name = "Milk", priceText = text)))
        }
        assertNotNull(ItemSheetLogic.fields(ItemForm(name = "Milk", priceText = "1,5")))
    }

    @Test
    fun `AC3 a list without a store records no price`() = runBlocking<Unit> {
        seed(withStore = false)
        val vm = viewModel()
        ready(vm)

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        awaitUntil { vm.state.value.sheet != null }
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk", priceText = "1.20")))
        awaitUntil { vm.state.value.sheet == null }

        assertEquals(0, outbox("item_price").size)
    }

    @Test
    fun `a price pulled as a JSON number lands as its canonical text and shows as a caption`() = runBlocking<Unit> {
        seed()
        registry.pullPrices(household, pulledPrice("p1", "s1", "milk", "1.20", "2026-11-03T10:00:00.000Z"))
        val vm = viewModel()
        ready(vm)
        awaitUntil { vm.state.value.detail.prices.isNotEmpty() }

        assertEquals("1.2", vm.state.value.detail.prices.single().price.toPlainString())
        assertEquals("€1.20 · Test Market", vm.state.value.caption(vm.state.value.detail.items.single(), Locale.UK))
        assertEquals("EUR", vm.state.value.detail.currency)
    }
}
