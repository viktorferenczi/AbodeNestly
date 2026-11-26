package com.abodenestly.shopping

import android.content.Context
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** What the network does: refuses (as `HttpApiService` reports an `IOException`) or accepts everything. */
private class SwitchableTransport : SyncTransport {
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

/** TESTING §6, same steps as `web/e2e/SHOP-01-lists.offline.spec.ts`: fail the network, create a list, switch to a recording fake, sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListsOfflineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    @Test
    fun `a list created offline shows at once with one outbox row, and the next sync pushes exactly it`() = runBlocking<Unit> {
        registry.open(household)
        val transport = SwitchableTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val vm = ListsViewModel(
            household, registry, kick = { engine.run() }, isOnline = MutableStateFlow(true), newId = { "l1" }, scope = scope,
        )

        vm.onEvent(ListsEvent.Create("Hardware", null))
        awaitUntil { vm.state.value.opened == "l1" }

        // Shown at once, from the local row.
        awaitUntil { vm.state.value.sections.lists.map { it.name } == listOf("Hardware") }
        assertEquals(1, registry.observeSyncCounts(household).first().pending)
        assertEquals(1, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Offline, engine.phase.value)

        // Back online: the recording fake accepts everything.
        transport.offline = false
        engine.run()
        val captured = transport.pushes.last()
        assertEquals(household.toString(), captured.householdId)
        val mutation = captured.mutations.single()
        assertEquals("shopping_list", mutation.table)
        assertEquals("upsert", mutation.op)
        assertEquals("l1", mutation.rowId)
        assertEquals("Hardware", mutation.data!!["name"]!!.jsonPrimitive.content)
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
