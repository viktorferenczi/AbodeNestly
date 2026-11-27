package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.FakeRealtime
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.RealtimeTables
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.SyncPushBody
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.SyncTransport
import com.abodenestly.sync.runHouseholdRealtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** What the network does: throws an `IOException` (as an unreachable server does) or accepts and answers. */
private class FlakyServer : SyncTransport {
    @Volatile var offline = true
    @Volatile var page = SyncPullPage(cursor = 0)
    val attempts = AtomicInteger()

    override suspend fun push(body: SyncPushBody): SyncResult<SyncPushResult> {
        attempts.incrementAndGet()
        if (offline) throw IOException("offline")
        return SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id }))
    }

    override suspend fun pull(householdId: String, since: Long, limit: Int): SyncResult<SyncPullPage> {
        attempts.incrementAndGet()
        if (offline) throw IOException("offline")
        return SyncResult.Success(if (since < page.cursor) page else SyncPullPage(cursor = since))
    }

    override suspend fun refreshSession() = Unit
}

/**
 * TESTING §6 for SHOP-09: the transport throws `IOException`, a Realtime event kicks, and the kick fails without damage:
 * the local rows and the queued change stay. Then the transport works, the next event pulls, and the row applies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeOfflineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun itemOutbox() = runBlocking { registry.outboxForTesting().filter { it.tableName == "list_item" } }

    private suspend fun items() = registry.observeListDetail(household, "l1").first().items.associate { it.name to it.checked }

    @Test
    fun `an event while the transport throws IOException keeps every local row, and the next event after it recovers applies the pull`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("i1", household.toString(), "l1", "Milk", nameKey = "milk", checked = false, createdAt = "2026-11-01T00:00:00.000Z"))
        }
        registry.upsert(household, "list_item", buildJsonObject { put("id", "i2"); put("list_id", "l1"); put("name", "Eggs") })
        assertEquals(1, itemOutbox().size)

        val server = FlakyServer()
        val engine = LiveSyncEngine(server, registry, isOnline = { true }, scope = scope)
        val realtime = FakeRealtime()
        scope.launch { runHouseholdRealtime(realtime, household.toString(), RealtimeTables.tables(true, true, true)) { engine.kick() } }
        awaitUntil { realtime.active == 1 }

        realtime.fireEvent()
        awaitUntil { server.attempts.get() >= 1 }
        delay(100)

        // The kick failed offline; nothing was lost.
        assertEquals(mapOf("Milk" to false, "Eggs" to false), items())
        assertEquals(1, itemOutbox().size)

        // Back online: the server has Milk checked by someone else; the next event pushes Eggs and pulls Milk.
        server.offline = false
        server.page = SyncPullPage(cursor = 5, changes = mapOf("list_item" to listOf(milkCheckedByAnotherMember())))
        realtime.fireEvent()

        awaitUntil { items()["Milk"] == true }
        awaitUntil { itemOutbox().isEmpty() }
        assertEquals(mapOf("Milk" to true, "Eggs" to false), items())
    }
}
