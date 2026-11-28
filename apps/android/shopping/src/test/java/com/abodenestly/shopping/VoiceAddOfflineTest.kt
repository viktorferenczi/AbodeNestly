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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
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
private class VoiceTransport : SyncTransport {
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
 * TESTING §6 for SHOP-14 (AC2): the network refuses, an on-device recognizer hears "2 litres milk", Add stores the item
 * locally with one outbox upsert, and the failed kick keeps it; then the network is back and the next sync pushes exactly it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAddOfflineTest {
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
    fun `SHOP-14 AC2 an item added by voice offline shows at once with one outbox upsert, and the next sync pushes exactly it`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        val transport = VoiceTransport()
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { engine.run() }, isOnline = MutableStateFlow(false),
            now = { now }, newId = { "i1" }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded && vm.state.value.detail.list != null }
        // Offline recognition is on the device: this fake can do it, so listening starts.
        val recognizer = FakeSpeechRecognizerDevice(onDevice = true)
        val model = vm.makeVoice(recognizer, Locale.US)

        model.start()
        assertEquals(listOf(true), recognizer.offlineStarts.toList())
        recognizer.emit(VoiceEvent.Final("2 litres milk"))
        assertEquals(VoicePhase.Parsed("2 litres milk", SpokenItem("Milk", 2.0, "l")), model.state.value.phase)
        assertTrue(model.addParsed())

        // Shown at once, from the local row, with its quantity and unit.
        awaitUntil { vm.state.value.unchecked.map { it.name } == listOf("Milk") }
        val row = registry.observeListDetail(household, "l1").first().items.single()
        assertEquals("2 l", ItemSheetLogic.formatQty(row.quantity, row.unit))
        // The kick ran a sync that could not reach the server; the row and its queued change are kept.
        awaitUntil { engine.phase.value == SyncPhase.Offline }
        assertEquals(1, registry.outboxForTesting().count { it.tableName == "list_item" })

        // Back online: the recording fake accepts everything.
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
