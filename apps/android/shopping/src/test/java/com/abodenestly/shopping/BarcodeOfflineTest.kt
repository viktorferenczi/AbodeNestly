package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.ProductLookupResult
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncFailure
import com.abodenestly.sync.SyncPhase
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.SyncPushBody
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** What the network does: refuses (as `HttpApiService` reports an `IOException`) or accepts everything. */
private class BarcodeTransport : SyncTransport {
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

/**
 * TESTING §6 for SHOP-10 (AC4): the lookup fails with an `IOException`, a code is entered manually and the item shows with
 * the code as its name; then the API returns the product, a sync runs, the drain renames the item and the push carries
 * the `list_item` patch with `name` and `barcode`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarcodeOfflineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-27T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    @Test
    fun `AC4 a code entered while the lookup cannot reach the API is added by its code, then renamed by the drain and pushed`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        val transport = BarcodeTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val store = BarcodeStore.inMemory()
        val api = FakeApiService()
        val reachable = java.util.concurrent.atomic.AtomicBoolean(false)
        // The fake API throws IOException until the network is back, then answers like the server.
        val lookup: suspend (String) -> ProductLookupResult = { code ->
            if (!reachable.get()) throw IOException("no route to host")
            api.product(code)
        }
        val vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { engine.run() }, isOnline = MutableStateFlow(true),
            now = { now }, newId = { "i1" }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded && vm.state.value.detail.list != null }
        val model = vm.makeScanner(FakeScannerDevice(), lookup, store, scope)

        // Manual entry: the lookup throws, so the code is the name and the lookup waits.
        model.setCode("3017620422003")
        model.submitCode()

        assertEquals(ScannerPhase.Queued("3017620422003"), model.state.value.phase)
        awaitUntil { vm.state.value.unchecked.map { it.name } == listOf("3017620422003") }
        assertEquals(listOf("3017620422003"), registry.observeListDetail(household, "l1").first().items.map { it.name })
        awaitUntil { engine.phase.value == SyncPhase.Offline }
        assertEquals(1, registry.outboxForTesting().count { it.tableName == "list_item" })
        assertEquals(listOf(PendingLookup(household.toString(), "i1", "3017620422003")), store.pending())

        // Back online: the add goes out as the code-named row.
        transport.offline = false
        reachable.set(true)
        api.productResult = ProductLookupResult.Found(nutella())
        engine.run()
        val added = transport.pushes.last().mutations.single { it.table == "list_item" }
        assertEquals("3017620422003", added.data!!["name"]!!.jsonPrimitive.content)
        assertEquals(0, registry.outboxForTesting().size)

        // The drain after that sync run finds the product, renames the item and asks for another sync.
        BarcodeLookupDrainer().drain(DrainRequest(registry, household, lookup, { true }, store, onRenamed = { engine.run() }))

        assertEquals(listOf(FakeApiService.Call.Product("3017620422003")), api.calls.filterIsInstance<FakeApiService.Call.Product>())
        assertEquals(listOf("Nutella"), registry.observeListDetail(household, "l1").first().items.map { it.name })
        val patch = transport.pushes.last().mutations.single()
        assertEquals("list_item", patch.table)
        assertEquals("upsert", patch.op)
        assertEquals("i1", patch.rowId)
        assertEquals("Nutella", patch.data!!["name"]!!.jsonPrimitive.content)
        assertEquals("3017620422003", patch.data!!["barcode"]!!.jsonPrimitive.content)
        assertNull(patch.data!!["name_key"])
        assertEquals(0, store.pending().size)
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
