package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncFailure
import com.abodenestly.sync.SyncPhase
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.SyncPushBody
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.SyncTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** What the network does: refuses (as `HttpApiService` reports an `IOException`) or accepts everything. */
private class PriceTransport : SyncTransport {
    @Volatile var offline = true
    val pushes = CopyOnWriteArrayList<SyncPushBody>()

    override suspend fun push(body: SyncPushBody): SyncResult<SyncPushResult> {
        pushes += body
        return if (offline) SyncResult.Failure(SyncFailure.Network) else SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id }))
    }

    override suspend fun pull(householdId: String, since: Long, limit: Int): SyncResult<SyncPullPage> =
        if (offline) SyncResult.Failure(SyncFailure.Network) else SyncResult.Success(SyncPullPage(cursor = since))

    override suspend fun refreshSession() = Unit
}

/** TESTING §6 for SHOP-12 (AC4): fail the network, enter a price, then switch to a recording fake and sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PriceOfflineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    @Test
    fun `AC4 a price entered offline shows at once with one outbox upsert, and the next sync pushes exactly it`() = runBlocking<Unit> {
        registry.open(household)
        createStore(registry, household, "Test Market", now, "s1")
        createList(registry, household, "Groceries", "s1", now, "l1")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("i1", household.toString(), "l1", "Milk", nameKey = "milk", checked = false, createdAt = "2026-11-01T00:00:00.000Z"))
        }
        val transport = PriceTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { engine.run() }, isOnline = MutableStateFlow(false),
            delayer = { CompletableDeferred<Unit>().await() }, now = { now }, newId = { "p1" }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded && vm.state.value.detail.items.isNotEmpty() && vm.state.value.currentStoreId != null }
        assertEquals(null, vm.state.value.caption(vm.state.value.detail.items.single(), Locale.UK))

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        awaitUntil { vm.state.value.sheet != null }
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk", priceText = "1,20")))

        // The caption comes from the local table, with the network down.
        awaitUntil { vm.state.value.caption(vm.state.value.detail.items.single(), Locale.UK) == "€1.20 · Test Market" }
        awaitUntil { engine.phase.value == SyncPhase.Offline }
        assertEquals(1, registry.outboxForTesting().count { it.tableName == "item_price" })

        // Back online: the recording fake accepts everything.
        transport.offline = false
        engine.run()
        val mutation = transport.pushes.last().mutations.single { it.table == "item_price" }
        assertEquals("upsert", mutation.op)
        assertEquals("p1", mutation.rowId)
        val data = mutation.data!!
        assertEquals("milk", data["name_key"]!!.jsonPrimitive.content)
        assertEquals("s1", data["store_id"]!!.jsonPrimitive.content)
        assertEquals("1.2", data["price"]!!.jsonPrimitive.content)
        assertFalse("the price goes as a JSON number", data["price"]!!.jsonPrimitive.isString)
        assertTrue(data.containsKey("seen_at"))
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
