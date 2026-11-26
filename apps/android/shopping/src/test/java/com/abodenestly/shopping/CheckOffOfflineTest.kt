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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** What the network does: refuses (as `HttpApiService` reports an `IOException`) or accepts everything. */
private class CheckOffTransport : SyncTransport {
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
 * TESTING §6 for SHOP-04 (AC6): fail the network, check an item off and let the undo window pass, then switch to a
 * recording fake and sync. The window is a gate the test opens, so nothing waits in real time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckOffOfflineTest {
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
    fun `AC6 a check-off after the window offline is one outbox upsert, and the next sync pushes exactly it`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("i1", household.toString(), "l1", "Milk", nameKey = "milk", checked = false, createdAt = "2026-11-01T00:00:00.000Z"))
        }
        val transport = CheckOffTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val gate = CompletableDeferred<Unit>()
        val vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { engine.run() }, isOnline = MutableStateFlow(false),
            delayer = { gate.await() }, now = { now }, newId = { "n1" }, scope = scope,
        )
        awaitUntil { vm.state.value.unchecked.map { it.name } == listOf("Milk") }

        vm.onEvent(ListDetailEvent.Check("i1"))
        // Inside the window nothing is queued.
        assertEquals(0, registry.outboxForTesting().size)
        gate.complete(Unit)

        awaitUntil { vm.state.value.checked.map { it.name } == listOf("Milk") }
        assertEquals(true, registry.observeListDetail(household, "l1").first().items.single().checked)
        awaitUntil { engine.phase.value == SyncPhase.Offline }
        assertEquals(1, registry.outboxForTesting().count { it.tableName == "list_item" })

        // Back online: the recording fake accepts everything.
        transport.offline = false
        engine.run()
        val mutation = transport.pushes.last().mutations.single { it.table == "list_item" }
        assertEquals("upsert", mutation.op)
        assertEquals("i1", mutation.rowId)
        assertEquals(setOf("checked", "checked_at", "checked_by"), mutation.data!!.keys)
        assertEquals("true", mutation.data!!["checked"]!!.jsonPrimitive.content)
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
