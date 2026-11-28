package com.abodenestly.sync

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** SHOP-09 (the seam): which tables, what an event does, and that a burst of events stays cheap. Twin of iOS `RealtimeTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val realtime = FakeRealtime()
    private val allTables = RealtimeTables.tables(shopping = true, pantry = true, maintenance = true)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun start(tables: List<String> = allTables, kick: () -> Unit): Job =
        scope.launch { runHouseholdRealtime(realtime, household.toString(), tables, kick) }

    @Test
    fun `AC1 an event calls kick once and the payload is never passed on`() = runBlocking<Unit> {
        val kicks = AtomicInteger()
        val job = start { kicks.incrementAndGet() }
        awaitUntil { realtime.active == 1 }

        // `kick` takes no argument, so there is nothing to read; the payload is thrown away.
        realtime.fireEvent("""{"type":"UPDATE","record":{"checked":true}}""")

        assertEquals(1, kicks.get())
        job.cancelAndJoin()
    }

    @Test
    fun `the subscription is for the household and carries exactly the ten published tables when every module is on`() = runBlocking<Unit> {
        val job = start { }
        awaitUntil { realtime.active == 1 }

        val call = realtime.calls.single()
        assertEquals(household.toString(), call.householdId)
        assertEquals(
            setOf("location", "invite", "activity", "attachment", "notification_pref", "store", "shopping_list", "list_item", "store_item_order", "item_price"),
            call.tables.toSet(),
        )
        assertEquals(10, call.tables.size)
        job.cancelAndJoin()
    }

    @Test
    fun `AC4 with shopping off only the five core tables are bound and no shopping table`() = runBlocking<Unit> {
        val job = start(RealtimeTables.tables(shopping = false, pantry = true, maintenance = true)) { }
        awaitUntil { realtime.active == 1 }

        val tables = realtime.calls.single().tables
        assertEquals(listOf("location", "invite", "activity", "attachment", "notification_pref"), tables)
        assertTrue(tables.none { it in RealtimeTables.shopping })
        job.cancelAndJoin()
    }

    @Test
    fun `the subscribed status catches up with one kick`() = runBlocking<Unit> {
        val kicks = AtomicInteger()
        val job = start { kicks.incrementAndGet() }
        awaitUntil { realtime.active == 1 }

        realtime.fireStatus(RealtimeStatus.Subscribed)

        assertEquals(1, kicks.get())
        job.cancelAndJoin()
    }

    @Test
    fun `AC3 an error or closed status neither throws nor kicks`() = runBlocking<Unit> {
        val kicks = AtomicInteger()
        val job = start { kicks.incrementAndGet() }
        awaitUntil { realtime.active == 1 }

        realtime.fireStatus(RealtimeStatus.Error)
        realtime.fireStatus(RealtimeStatus.Closed)

        assertEquals(0, kicks.get())
        assertTrue(job.isActive)
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling the run unsubscribes`() = runBlocking<Unit> {
        val job = start { }
        awaitUntil { realtime.active == 1 }

        job.cancelAndJoin()

        assertEquals(0, realtime.active)
    }

    @Test
    fun `a cancelled subscription ignores later events and cancelling twice is harmless`() = runBlocking<Unit> {
        val kicks = AtomicInteger()
        val subscription = realtime.subscribe(household.toString(), allTables) { kicks.incrementAndGet() }
        subscription.cancel()
        subscription.cancel()

        realtime.fireEvent()

        assertEquals(0, kicks.get())
        assertFalse(realtime.active > 0)
    }

    @Test
    fun `NoRealtime subscribes to nothing and cancels cleanly`() = runBlocking<Unit> {
        val job = scope.launch { runHouseholdRealtime(NoRealtime, household.toString(), allTables) { } }
        delay(50)
        assertTrue(job.isActive)
        job.cancelAndJoin()
    }

    @Test
    fun `a burst of 5 events during a slow pass costs at most 2 passes with the real engine`() = runBlocking<Unit> {
        registry.open(household)
        val transport = FakeTransport()
        val gate = CompletableDeferred<Unit>()
        transport.pullGate = gate
        val engine = LiveSyncEngine(transport, registry, isOnline = { true }, scope = scope)
        val job = start { engine.kick() }
        awaitUntil { realtime.active == 1 }

        realtime.fireEvent()
        awaitUntil { transport.pulls.size == 1 }
        // Four more while the first pull is still in flight; every one of them waits for the same next pass.
        repeat(4) { realtime.fireEvent() }
        delay(200)
        gate.complete(Unit)

        awaitUntil { transport.pulls.size >= 2 }
        delay(300)
        assertEquals(2, transport.pulls.size)
        job.cancelAndJoin()
    }
}
