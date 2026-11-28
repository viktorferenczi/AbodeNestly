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
import kotlinx.coroutines.awaitCancellation
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
import java.util.concurrent.atomic.AtomicInteger

/** What the network does: refuses (as `HttpApiService` reports an `IOException`) or accepts everything. */
private class TemplatesTransport : SyncTransport {
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
 * TESTING §6 for SHOP-11 (AC4): the network refuses, a template with two items is added to Groceries and Groceries shows
 * them, with two `list_item` rows queued for it; then the network accepts, a sync runs and the push carries both upserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplatesOfflineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    @Test
    fun `AC4 Add all offline queues one upsert per item for the target, and the next sync pushes exactly them`() = runBlocking<Unit> {
        registry.open(household)
        val transport = TemplatesTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val ids = AtomicInteger()
        val lists = ListsViewModel(
            household, registry, kick = { engine.run() }, isOnline = MutableStateFlow(false), delayer = { awaitCancellation() },
            now = { now }, newId = { "list${ids.incrementAndGet()}" }, scope = scope,
        )
        awaitUntil { lists.state.value.loaded }
        lists.onEvent(ListsEvent.Create("Groceries", null))
        awaitUntil { lists.state.value.opened == "list1" }
        lists.onEvent(ListsEvent.Create("Weekly", null, isTemplate = true))
        awaitUntil { lists.state.value.opened == "list2" }
        // Two items on the template, added the way the quick-add bar does.
        val milk = QuickAdd.row("i1", "list2", "Milk", uuid(7), now.plusSeconds(1))
        val eggs = QuickAdd.row("i2", "list2", "Eggs", uuid(7), now.plusSeconds(2))
        addItem(registry, household, milk, now.plusSeconds(1))
        addItem(registry, household, eggs, now.plusSeconds(2))
        val vm = ListDetailViewModel(
            household, "list2", uuid(7), registry, kick = { engine.run() }, isOnline = MutableStateFlow(false),
            delayer = { awaitCancellation() }, now = { now.plusSeconds(10) }, newId = { "copy${ids.incrementAndGet()}" }, scope = scope,
        )
        awaitUntil { vm.state.value.targets.map { it.id } == listOf("list1") && vm.state.value.unchecked.size == 2 }

        vm.onEvent(ListDetailEvent.AddAll("list1"))

        awaitUntil { vm.state.value.status != null }
        assertEquals("Added 2 items to Groceries", vm.state.value.status)
        // The target shows them at once, from the local database.
        assertEquals(listOf("Milk", "Eggs"), registry.observeListDetail(household, "list1").first().items.sortedBy { it.createdAt }.map { it.name })
        awaitUntil { engine.phase.value == SyncPhase.Offline }
        val queued = registry.outboxForTesting().filter { it.tableName == "list_item" && it.rowId.startsWith("copy") }
        assertEquals(2, queued.size)

        // Back online: the recording fake accepts everything.
        transport.offline = false
        engine.run()
        val pushed = transport.pushes.last().mutations.filter { it.table == "list_item" && it.rowId.startsWith("copy") }
        assertEquals(2, pushed.size)
        assertEquals(setOf("upsert"), pushed.map { it.op }.toSet())
        assertEquals(setOf("list1"), pushed.map { it.data!!["list_id"]!!.jsonPrimitive.content }.toSet())
        assertEquals(setOf("Milk", "Eggs"), pushed.map { it.data!!["name"]!!.jsonPrimitive.content }.toSet())
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
