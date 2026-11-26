package com.abodenestly.sync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** SHOP-02 read model: `observeListDetail` on a real Room file in Robolectric's temp context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShoppingListDetailTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private suspend fun list(id: String, name: String, storeId: String? = null) =
        registry.upsert(
            household, "shopping_list",
            buildJsonObject { put("id", id); put("name", name); if (storeId != null) put("store_id", storeId) },
        )

    private suspend fun detail(listId: String) = withTimeout(5_000) { registry.observeListDetail(household, listId).first() }

    @Test
    fun `the detail carries the list and the name of its live store`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", hid, "Test Market")) }
        list("l1", "Groceries", storeId = "s1")
        val d = detail("l1")
        assertEquals("Groceries", d.list?.name)
        assertEquals("Test Market", d.storeName)
    }

    @Test
    fun `a deleted store gives no store name`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", hid, "Closed Shop", deletedAt = "2026-01-01T00:00:00.000Z")) }
        list("l1", "Groceries", storeId = "s1")
        val d = detail("l1")
        assertNotNull(d.list)
        assertNull(d.storeName)
    }

    @Test
    fun `items are this list's live rows and history is the household's live or once checked rows`() = runBlocking<Unit> {
        registry.open(household)
        list("l1", "Groceries")
        list("l2", "Hardware")
        val gone = "2026-01-01T00:00:00.000Z"
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("i1", hid, "l1", "Milk", nameKey = "milk", checked = false))
            dao.upsertListItem(ListItemEntity("i2", hid, "l1", "Typo", checked = false, deletedAt = gone))
            dao.upsertListItem(ListItemEntity("i3", hid, "l1", "Eggs", checked = true, deletedAt = gone))
            dao.upsertListItem(ListItemEntity("i4", hid, "l2", "Bolts", checked = false))
            dao.upsertListItem(ListItemEntity("i5", hid, "l2", "Gone checked", checked = true, deletedAt = gone))
        }
        val d = detail("l1")
        assertEquals(listOf("i1"), d.items.map { it.id })
        assertEquals(listOf("i1", "i3", "i4", "i5"), d.history.map { it.id }.sorted())
        assertEquals("milk", d.items.single().nameKey)
        assertEquals(listOf(false, true), d.history.filter { it.id in setOf("i1", "i3") }.sortedBy { it.id }.map { it.checked })
    }

    @Test
    fun `a deleted or unknown list has no list`() = runBlocking<Unit> {
        registry.open(household)
        list("l1", "Groceries")
        registry.remove(household, "shopping_list", "l1")
        assertNull(detail("l1").list)
        assertNull(detail("missing").list)
    }

    @Test
    fun `the detail re-emits after an upsert`() = runBlocking<Unit> {
        registry.open(household)
        list("l1", "Groceries")
        val seen = CopyOnWriteArrayList<List<String>>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            registry.observeListDetail(household, "l1").collect { seen += it.items.map { i -> i.name } }
        }
        awaitUntil { seen.isNotEmpty() }
        registry.upsert(household, "list_item", buildJsonObject { put("id", "i1"); put("list_id", "l1"); put("name", "Milk") })
        awaitUntil { seen.any { it.contains("Milk") } }
        job.cancel()
        assertEquals(listOf("Milk"), seen.last())
    }

    @Test
    fun `nothing is emitted for a household that is not open`() = runBlocking<Unit> {
        registry.open(household)
        list("l1", "Groceries")
        val other = withTimeoutOrNull(300) { registry.observeListDetail(uuid(2), "l1").toList() }
        // The flow never completes and never emits: the timeout wins.
        assertNull(other)
        assertEquals("Groceries", detail("l1").list?.name)
    }
}
