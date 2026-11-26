package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncFailure
import com.abodenestly.sync.SyncPhase
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.upsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TESTING §6, same steps as `web/e2e/SHOP-08-sync.offline.spec.ts`: fail the network, write, switch to a recording fake, sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSheetOfflineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    @Test
    fun `an offline write is queued, and the next sync pushes exactly that mutation`() = runBlocking {
        registry.open(household)
        val api = FakeApiService()
        // The network throws IOException: the transport reports Network, as HttpApiService does.
        api.pushResults.add(SyncResult.Failure(SyncFailure.Network))
        val engine = LiveSyncEngine(api, registry, isOnline = { true }, scope = CoroutineScope(Dispatchers.Unconfined))

        registry.upsert(household, "list_item", buildJsonObject { put("id", "i1"); put("list_id", "l1"); put("name", "Milk") })
        engine.run()
        assertEquals(1, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Offline, engine.phase.value)
        assertEquals(0, api.calls.count { it is FakeApiService.Call.Pull })

        // Back online: the recording fake accepts everything.
        engine.run()
        val pushes = api.calls.filterIsInstance<FakeApiService.Call.Push>()
        assertEquals(2, pushes.size)
        val captured = pushes.last().body
        assertEquals(household.toString(), captured.householdId)
        assertEquals(listOf("list_item" to "i1"), captured.mutations.map { it.table to it.rowId })
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }
}
