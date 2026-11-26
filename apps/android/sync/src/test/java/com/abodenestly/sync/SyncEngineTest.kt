package com.abodenestly.sync

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** SHOP-08 sync engine: the write API, the push/pull loop and its failure paths, on real Room files in Robolectric's temp context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val transport = FakeTransport()
    private val pauses = mutableListOf<Long>()
    private var online = true

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun engine() = LiveSyncEngine(
        transport, registry, isOnline = { online }, scope = CoroutineScope(Dispatchers.Unconfined), pause = { pauses += it },
    )

    private suspend fun items(id: java.util.UUID = household) = registry.withSearchDao { it.listItems(id.toString()).first() }.map { it.name }.sorted()

    // --- AC1: one transaction ---

    @Test
    fun `AC1 - an offline write is one local row and exactly one outbox row`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        assertEquals(listOf("Milk"), items())
        val outbox = registry.outboxForTesting()
        assertEquals(1, outbox.size)
        assertEquals("list_item", outbox[0].tableName)
        assertEquals("upsert", outbox[0].op)
        assertEquals("i1", outbox[0].rowId)
        assertTrue(transport.pushes.isEmpty())
    }

    @Test
    fun `AC1 - a write that fails locally rolls back both rows`() = runBlocking {
        registry.open(household)
        // No list_id and no name: the local insert violates NOT NULL after the outbox row was written, and both must go.
        val bad = buildJsonObject { put("id", "i2") }
        val failed = runCatching { registry.upsert(household, "list_item", bad) }.isFailure
        assertTrue(failed)
        assertEquals(emptyList<String>(), items())
        assertEquals(0, registry.outboxForTesting().size)
    }

    @Test
    fun `server-owned columns never go out in data`() = runBlocking {
        registry.open(household)
        val row = buildJsonObject {
            put("id", "i1"); put("list_id", "l1"); put("name", "Milk"); put("household_id", "x")
            put("created_at", "2026-01-01T00:00:00Z"); put("updated_at", "2026-01-01T00:00:00Z"); put("sync_seq", 5); put("name_key", "milk")
        }
        registry.upsert(household, "list_item", row)
        val data = SyncJson.parseToJsonElement(registry.outboxForTesting().single().data) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("list_id", "name"), data.keys)
    }

    @Test
    fun `a table that cannot be synced is refused before any SQL`() = runBlocking {
        registry.open(household)
        val refused = runCatching { registry.upsert(household, "outbox; DROP TABLE meta", listItem("i1", "x")) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }

    @Test
    fun `remove soft-deletes locally and queues a delete`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        registry.remove(household, "list_item", "i1")
        assertEquals(emptyList<String>(), items())
        assertEquals(listOf("upsert", "delete"), registry.outboxForTesting().map { it.op })
    }

    @Test
    fun `a write for another household is refused`() = runBlocking {
        registry.open(household)
        val refused = runCatching { registry.upsert(uuid(2), "list_item", listItem("i1", "Milk")) }.exceptionOrNull()
        assertTrue(refused is DatabaseNotOpenException)
    }

    // --- AC2: push, then pull ---

    @Test
    fun `AC2 - reconnecting pushes both rows in order, then pulls from the cursor`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        registry.upsert(household, "list_item", listItem("i2", "Eggs"))
        transport.pullResults.add(
            SyncResult.Success(SyncPullPage(cursor = 7, hasMore = false, changes = mapOf("list_item" to listOf(listItem("i3", "Bread"))))),
        )
        engine().run()
        assertEquals(1, transport.pushes.size)
        assertEquals(listOf("i1", "i2"), transport.pushes[0].mutations.map { it.rowId })
        assertEquals(household.toString(), transport.pushes[0].householdId)
        assertEquals(listOf(FakeTransport.PullCall(household.toString(), 0, 500)), transport.pulls.toList())
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(listOf("Bread", "Eggs", "Milk"), items())
        assertEquals(7L, registry.cursorOf(household))
    }

    @Test
    fun `has_more pulls the next page from the new cursor`() = runBlocking {
        registry.open(household)
        transport.pullResults.add(SyncResult.Success(SyncPullPage(cursor = 3, hasMore = true, changes = mapOf("list_item" to listOf(listItem("i1", "A"))))))
        transport.pullResults.add(SyncResult.Success(SyncPullPage(cursor = 5, hasMore = false, changes = mapOf("list_item" to listOf(listItem("i2", "B"))))))
        engine().run()
        assertEquals(listOf(0L, 3L), transport.pulls.map { it.since })
        assertEquals(listOf("A", "B"), items())
        assertEquals(5L, registry.cursorOf(household))
    }

    @Test
    fun `offline there is no request and the queue stays`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        online = false
        val engine = engine()
        engine.run()
        assertTrue(transport.pushes.isEmpty() && transport.pulls.isEmpty())
        assertEquals(SyncPhase.Offline, engine.phase.value)
        assertEquals(1, registry.outboxForTesting().size)
    }

    @Test
    fun `a network failure keeps the rows, skips the pull and reports offline`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushResults.add(SyncResult.Failure(SyncFailure.Network))
        val engine = engine()
        engine.run()
        assertEquals(1, registry.outboxForTesting().size)
        assertTrue(transport.pulls.isEmpty())
        assertEquals(SyncPhase.Offline, engine.phase.value)
    }

    @Test
    fun `a retryable failure is retried once after a pause, then the rows are kept`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushResults.add(SyncResult.Failure(SyncFailure.Retryable))
        engine().run()
        assertEquals(2, transport.pushes.size)
        assertEquals(listOf(1_000L), pauses)
        assertEquals(0, registry.outboxForTesting().size)

        registry.upsert(household, "list_item", listItem("i2", "Eggs"))
        transport.pushResults.add(SyncResult.Failure(SyncFailure.Retryable))
        transport.pushResults.add(SyncResult.Failure(SyncFailure.Retryable))
        val pullsBefore = transport.pulls.size
        engine().run()
        assertEquals(1, registry.outboxForTesting().size)
        assertEquals(pullsBefore, transport.pulls.size)
    }

    @Test
    fun `rows of tables the server does not accept stay queued and are never sent`() = runBlocking {
        registry.open(household)
        registry.enqueueForTesting("notification_pref")
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        engine().run()
        assertEquals(listOf("list_item"), transport.pushes.single().mutations.map { it.table })
        assertEquals(listOf("notification_pref"), registry.outboxForTesting().map { it.tableName })
    }

    // --- rejected pushes ---

    private fun rejecting(reason: String): (SyncPushBody) -> SyncResult<SyncPushResult> =
        { body -> SyncResult.Success(SyncPushResult(rejected = body.mutations.map { SyncRejected(it.id, reason, "because") })) }

    @Test
    fun `AC3 - a rejected mutation moves to outbox_failed with its reason and detail`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushDefault = rejecting("validation")
        engine().run()
        assertEquals(0, registry.outboxForTesting().size)
        val failed = registry.withOpen { it.outbox().failed() }.single()
        assertEquals("validation", failed.reason)
        assertEquals("because", failed.detail)
        assertEquals("i1", failed.rowId)
        assertEquals(1, registry.observeSyncCounts(household).first().failed)
        // The pass still pulled: the push reached the server.
        assertEquals(1, transport.pulls.size)
    }

    @Test
    fun `an unknown rejection reason is kept as sent`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushDefault = rejecting("from_the_future")
        engine().run()
        assertEquals("from_the_future", registry.withOpen { it.outbox().failed() }.single().reason)
    }

    @Test
    fun `rls_denied is retried once after a session refresh`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushResults.add(SyncResult.Success(SyncPushResult(rejected = listOf(SyncRejected(registry.outboxForTesting().single().id, "rls_denied")))))
        engine().run()
        assertEquals(1, transport.refreshes)
        assertEquals(2, transport.pushes.size)
        assertEquals(0, registry.outboxForTesting().size)
        assertEquals(0, registry.withOpen { it.outbox().failed() }.size)
    }

    @Test
    fun `a second rls_denied is final`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushDefault = rejecting("rls_denied")
        engine().run()
        assertEquals(1, transport.refreshes)
        assertEquals(2, transport.pushes.size)
        assertEquals("rls_denied", registry.withOpen { it.outbox().failed() }.single().reason)
    }

    @Test
    fun `a reply that answers for none of the rows ends the push instead of spinning`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushDefault = { SyncResult.Success(SyncPushResult()) }
        engine().run()
        assertEquals(1, transport.pushes.size)
        assertEquals(1, registry.outboxForTesting().size)
    }

    @Test
    fun `Retry puts the change back at its old place with the same mutation id`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        val original = registry.outboxForTesting().single()
        transport.pushDefault = rejecting("validation")
        val engine = engine()
        engine.run()
        val seq = registry.withOpen { it.outbox().failed() }.single().seq
        assertEquals(original.seq, seq)

        transport.pushDefault = { body -> SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id })) }
        engine.retry(seq)
        awaitUntil { transport.pushes.size == 2 }
        assertEquals(listOf(original.id), transport.pushes[1].mutations.map { it.id })
        awaitUntil { registry.outboxForTesting().isEmpty() }
        assertEquals(0, registry.withOpen { it.outbox().failed() }.size)
    }

    @Test
    fun `Discard drops the local row of a change the server never took, and resets the cursor`() = runBlocking {
        registry.open(household)
        registry.writeCursor(9)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        transport.pushDefault = rejecting("validation")
        val engine = engine()
        engine.run()
        registry.writeCursor(9)
        val seq = registry.withOpen { it.outbox().failed() }.single().seq
        transport.pushDefault = { body -> SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id })) }
        engine.discard(seq)
        awaitUntil { transport.pulls.size >= 2 }
        assertEquals(emptyList<String>(), items())
        assertEquals(0, registry.withOpen { it.outbox().failed() }.size)
        // The follow-up pass pulled from 0 (the fake's default page keeps the cursor at 0).
        assertEquals(0L, transport.pulls.last().since)
    }

    @Test
    fun `Discard leaves the cursor alone while another change still targets the row`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        registry.upsert(household, "list_item", listItem("i1", "Milk 2"))
        transport.pushDefault = rejecting("validation")
        engine().run()
        registry.writeCursor(9)
        val failed = registry.withOpen { it.outbox().failed() }
        registry.discardFailed(household, failed[0].seq)
        assertEquals(9L, registry.cursorOf(household))
        registry.discardFailed(household, failed[1].seq)
        assertEquals(0L, registry.cursorOf(household))
    }

    @Test
    fun `Discard keeps the local row while another change still targets it`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("i1", "Milk"))
        registry.upsert(household, "list_item", listItem("i1", "Milk 2"))
        transport.pushDefault = rejecting("validation")
        val engine = engine()
        engine.run()
        val failed = registry.withOpen { it.outbox().failed() }
        assertEquals(2, failed.size)
        transport.pushDefault = { body -> SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id })) }
        engine.discard(failed[0].seq)
        awaitUntil { registry.withOpen { it.outbox().failed() }.size == 1 }
        assertEquals(listOf("Milk 2"), items())
        engine.discard(failed[1].seq)
        awaitUntil { items().isEmpty() }
    }

    // --- coalescing ---

    @Test
    fun `concurrent kicks share one pass and a kick mid-pass causes exactly one more`() = runBlocking {
        registry.open(household)
        val engine = engine()
        transport.pullGate = CompletableDeferred()
        val first = async(Dispatchers.Default) { engine.run() }
        awaitUntil { transport.pulls.size == 1 }
        engine.kick()
        engine.kick()
        assertEquals(1, transport.pulls.size)
        transport.pullGate!!.complete(Unit)
        first.await()
        awaitUntil { transport.pulls.size == 2 }
        kotlinx.coroutines.delay(200) // a third pass would show up here
        assertEquals(2, transport.pulls.size)
        assertEquals(SyncPhase.Idle, engine.phase.value)
    }

    @Test
    fun `a household switch during a pull writes nothing into the new household's file`() = runBlocking {
        val other = uuid(2)
        registry.open(household)
        val engine = engine()
        transport.pullResults.add(SyncResult.Success(SyncPullPage(cursor = 8, changes = mapOf("list_item" to listOf(listItem("i1", "Milk"))))))
        transport.pullGate = CompletableDeferred()
        val pass = async(Dispatchers.Default) { engine.run() }
        awaitUntil { transport.pulls.size == 1 }
        registry.open(other)
        transport.pullGate!!.complete(Unit)
        pass.await()
        assertEquals(emptyList<String>(), items(other))
        assertEquals(0L, registry.cursorOf(other))
    }

    @Test
    fun `with no household open there is nothing to do`() = runBlocking {
        engine().run()
        assertTrue(transport.pushes.isEmpty() && transport.pulls.isEmpty())
    }

    // --- applyPull ---

    @Test
    fun `applyPull skips unknown tables and columns and keeps a row that has a queued change`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", listItem("mine", "Mine, local"))
        val page = SyncPullPage(
            cursor = 4,
            changes = mapOf(
                "list_item" to listOf(
                    listItem("mine", "Mine, server"),
                    buildJsonObject { put("id", "i2"); put("list_id", "l"); put("name", "Other"); put("column_from_the_future", "x") },
                ),
                "table_from_the_future" to listOf(buildJsonObject { put("id", "z") }),
                "outbox" to listOf(buildJsonObject { put("id", "evil") }),
            ),
        )
        assertTrue(registry.applyPull(household, page))
        assertEquals(listOf("Mine, local", "Other"), items())
        assertEquals(1, registry.outboxForTesting().size)
        assertEquals(4L, registry.cursorOf(household))
    }

    @Test
    fun `applyPull normalizes server timestamps so the activity feed reads them`() = runBlocking {
        registry.open(household)
        val actor = uuid(5).toString()
        val row = buildJsonObject {
            put("id", uuid(10).toString()); put("household_id", household.toString()); put("actor_id", actor)
            put("module", "shopping"); put("entity", "list_item"); put("entity_id", uuid(11).toString())
            put("action", "created"); put("summary", "Ada added Milk")
            put("created_at", "2026-11-23T17:47:27.123456+00:00"); put("updated_at", "2026-11-23T17:47:27.123456+00:00")
            put("sync_seq", 12)
        }
        assertTrue(registry.applyPull(household, SyncPullPage(cursor = 12, changes = mapOf("activity" to listOf(row)))))
        val feed = registry.activityFeed(household).first()
        assertEquals(listOf("Ada added Milk"), feed.map { it.summary })
        assertEquals(java.time.OffsetDateTime.parse("2026-11-23T17:47:27.123Z"), feed.single().createdAt)
    }

    @Test
    fun `applyPull to a household that is not open writes nothing`() = runBlocking {
        registry.open(household)
        val page = SyncPullPage(cursor = 3, changes = mapOf("list_item" to listOf(listItem("i1", "Milk"))))
        assertFalse(registry.applyPull(uuid(2), page))
        assertEquals(emptyList<String>(), items())
        assertEquals(0L, registry.cursorOf(household))
    }

    @Test
    fun `a pulled tombstone hides the row and booleans and numbers land as SQLite values`() = runBlocking {
        registry.open(household)
        val row = buildJsonObject {
            put("id", "i1"); put("list_id", "l"); put("name", "Milk"); put("checked", true); put("quantity", 1.5)
            put("deleted_at", "2026-11-23T10:00:00+00:00")
        }
        registry.applyPull(household, SyncPullPage(cursor = 1, changes = mapOf("list_item" to listOf(row))))
        assertEquals(emptyList<String>(), items())
        val stored = registry.withOpen { it.search().listItems(household.toString()).first() } // live rows only
        assertTrue(stored.isEmpty())
        val deleted = registry.withOpen { db -> db.openHelper.writableDatabase.query("SELECT checked, quantity, deleted_at FROM list_item WHERE id = 'i1'").use { c -> c.moveToFirst(); Triple(c.getInt(0), c.getDouble(1), c.getString(2)) } }
        assertEquals(Triple(1, 1.5, "2026-11-23T10:00:00.000Z"), deleted)
    }
}
