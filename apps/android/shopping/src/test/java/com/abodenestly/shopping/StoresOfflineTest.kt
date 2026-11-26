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
private class StoresTransport : SyncTransport {
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

/** TESTING §6 for SHOP-05 (AC4): fail the network, create a store, then switch to a recording fake and sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoresOfflineTest {
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
    fun `AC4 a store created offline shows at once with one outbox upsert, and the next sync pushes exactly it`() = runBlocking<Unit> {
        registry.open(household)
        val transport = StoresTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val vm = StoresViewModel(
            household, registry, kick = { engine.run() }, isOnline = MutableStateFlow(false),
            now = { now }, newId = { "s1" }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded }

        val id = vm.create("Corner Shop")

        assertEquals("s1", id)
        awaitUntil { vm.state.value.rows.map { it.name } == listOf("Corner Shop") }
        awaitUntil { engine.phase.value == SyncPhase.Offline }
        assertEquals(1, registry.outboxForTesting().count { it.tableName == "store" })

        // Back online: the recording fake accepts everything.
        transport.offline = false
        engine.run()
        val mutation = transport.pushes.last().mutations.single { it.table == "store" }
        assertEquals("upsert", mutation.op)
        assertEquals("s1", mutation.rowId)
        assertEquals("Corner Shop", mutation.data!!["name"]!!.jsonPrimitive.content)
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
