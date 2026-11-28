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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.doubleOrNull
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
private class AssistantTransport : SyncTransport {
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
 * TESTING §6 for SHOP-15 (AC3): the network refuses, the assistant adds "2 litres of milk", the item is stored locally with
 * one outbox upsert, and the failed kick keeps it; then the network is back and the next sync pushes exactly it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantShortcutOfflineTest {
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
    fun `SHOP-15 AC3 an item added by the assistant offline is stored with one outbox upsert, kept by a failed sync and pushed later`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true, pulledList("l1", "Groceries"))
        val transport = AssistantTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)

        val result = AssistantShortcut.add("2 litres of milk", household, uuid(7), registry, now) { "i1" }
        assertEquals(AssistantResult.Added("Milk", "Groceries"), result)
        engine.run()

        // Stored locally with its quantity and unit, and the failed pass kept the queued change.
        val row = registry.observeListDetail(household, "l1").first().items.single()
        assertEquals("Milk", row.name)
        assertEquals("2 l", ItemSheetLogic.formatQty(row.quantity, row.unit))
        assertEquals(SyncPhase.Offline, engine.phase.value)
        assertEquals(1, registry.outboxForTesting().count { it.tableName == "list_item" })

        // Back online: the recording fake accepts everything and gets exactly that row.
        transport.offline = false
        engine.run()
        val mutation = transport.pushes.last().mutations.single { it.table == "list_item" }
        assertEquals("upsert", mutation.op)
        assertEquals("i1", mutation.rowId)
        assertEquals("Milk", mutation.data!!["name"]!!.jsonPrimitive.content)
        assertEquals(2.0, mutation.data!!["quantity"]!!.jsonPrimitive.doubleOrNull)
        assertEquals("l", mutation.data!!["unit"]!!.jsonPrimitive.content)
        assertEquals("l1", mutation.data!!["list_id"]!!.jsonPrimitive.content)
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
