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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** SHOP-01 read model: `observeListOverview` on a real Room file in Robolectric's temp context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShoppingListsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private suspend fun list(id: String, name: String) =
        registry.upsert(household, "shopping_list", buildJsonObject { put("id", id); put("name", name) })

    @Test
    fun `the overview carries the live lists, the counts without deleted items, and live stores only`() = runBlocking {
        registry.open(household)
        list("l1", "Groceries")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("i1", hid, "l1", "Milk", checked = true))
            dao.upsertListItem(ListItemEntity("i2", hid, "l1", "Eggs", checked = false))
            dao.upsertListItem(ListItemEntity("i3", hid, "l1", "Bread"))
            dao.upsertListItem(ListItemEntity("i4", hid, "l1", "Gone", checked = true, deletedAt = "2026-01-01T00:00:00.000Z"))
            dao.upsertStore(StoreEntity("s1", hid, "Test Market"))
            dao.upsertStore(StoreEntity("s2", hid, "Closed Shop", deletedAt = "2026-01-01T00:00:00.000Z"))
        }
        val overview = registry.observeListOverview(household).first()
        assertEquals(listOf("Groceries"), overview.lists.map { it.name })
        assertEquals(ItemCount(checked = 1, all = 3), overview.counts["l1"])
        assertEquals(listOf(StoreRecord("s1", "Test Market")), overview.stores)
    }

    @Test
    fun `a soft-deleted list is not in the overview, an archived one is`() = runBlocking {
        registry.open(household)
        list("l1", "Groceries")
        list("l2", "Old")
        registry.patch(household, "shopping_list", "l2", buildJsonObject { put("archived_at", "2026-02-01T00:00:00.000Z") })
        list("l3", "Deleted")
        registry.remove(household, "shopping_list", "l3")
        val lists = registry.observeListOverview(household).first().lists.sortedBy { it.name }
        assertEquals(listOf("Groceries", "Old"), lists.map { it.name })
        assertNull(lists[0].archivedAt)
        assertEquals("2026-02-01T00:00:00.000Z", lists[1].archivedAt)
    }

    @Test
    fun `the overview re-emits after an upsert`() = runBlocking {
        registry.open(household)
        list("l1", "Groceries")
        val seen = CopyOnWriteArrayList<List<String>>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            registry.observeListOverview(household).collect { seen += it.lists.map { l -> l.name } }
        }
        awaitUntil { seen.isNotEmpty() }
        list("l2", "Hardware")
        awaitUntil { seen.any { it.contains("Hardware") } }
        job.cancel()
        assertEquals(listOf("Groceries", "Hardware"), seen.last().sorted())
    }

    @Test
    fun `nothing is emitted for a household that is not open`() = runBlocking {
        registry.open(household)
        list("l1", "Groceries")
        val other = withTimeoutOrNull(300) { registry.observeListOverview(uuid(2)).toList() }
        // The flow never completes and never emits: the timeout wins.
        assertNull(other)
        withTimeout(5_000) { registry.observeListOverview(household).first() }
    }
}
