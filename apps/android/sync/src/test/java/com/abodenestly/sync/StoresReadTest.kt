package com.abodenestly.sync

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SHOP-05 read model: `observeStores` and `ListDetail.stores` on a real Room file in Robolectric's temp context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoresReadTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private suspend fun stores() = withTimeout(5_000) { registry.observeStores(household).first() }

    private fun order(storeId: String, key: String) = StoreItemOrderEntity(storeId, key, hid, meanPosition = 1.0, samples = 2)

    @Test
    fun `stores come by name with the deleted ones dropped`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { dao ->
            dao.upsertStore(StoreEntity("s1", hid, "Test Market"))
            dao.upsertStore(StoreEntity("s2", hid, "Corner Shop"))
            dao.upsertStore(StoreEntity("s3", hid, "Closed", deletedAt = "2026-01-01T00:00:00.000Z"))
        }
        assertEquals(listOf("Corner Shop", "Test Market"), stores().map { it.name })
    }

    @Test
    fun `learned counts only that store's order rows`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { dao ->
            dao.upsertStore(StoreEntity("s1", hid, "Test Market"))
            dao.upsertStore(StoreEntity("s2", hid, "Corner Shop"))
            dao.upsertStoreItemOrder(order("s1", "milk"))
            dao.upsertStoreItemOrder(order("s1", "bread"))
            dao.upsertStoreItemOrder(order("s1", "eggs"))
            dao.upsertStoreItemOrder(order("s2", "milk"))
        }
        assertEquals(mapOf("Test Market" to 3, "Corner Shop" to 1), stores().associate { it.name to it.learned })
    }

    @Test
    fun `a store without order rows has learned 0`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", hid, "Test Market")) }
        assertEquals(listOf(StoreRow("s1", "Test Market", 0)), stores())
    }

    @Test
    fun `another household's stores are not listed`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", uuid(2).toString(), "Elsewhere")) }
        assertEquals(emptyList<StoreRow>(), stores())
    }

    @Test
    fun `the list detail carries the live stores`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { dao ->
            dao.upsertStore(StoreEntity("s1", hid, "Test Market"))
            dao.upsertStore(StoreEntity("s2", hid, "Closed", deletedAt = "2026-01-01T00:00:00.000Z"))
        }
        registry.upsert(household, "shopping_list", buildJsonObject { put("id", "l1"); put("name", "Groceries") })
        val detail = withTimeout(5_000) { registry.observeListDetail(household, "l1").first() }
        assertEquals(listOf(StoreRecord("s1", "Test Market")), detail.stores)
    }
}
