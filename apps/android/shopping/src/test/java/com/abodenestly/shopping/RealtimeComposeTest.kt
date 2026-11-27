package com.abodenestly.shopping

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.FakeRealtime
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.RealtimeStatus
import com.abodenestly.sync.RealtimeTables
import com.abodenestly.sync.SyncFailure
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.SyncPushBody
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.SyncTransport
import com.abodenestly.sync.runHouseholdRealtime
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The stub server: accepts every push, and answers a pull with [page] (or with a network failure while [offline]). */
private class RealtimeServer : SyncTransport {
    @Volatile var offline = false
    @Volatile var page = SyncPullPage(cursor = 0)
    val pulls = AtomicInteger()

    /** Every call to the server, push or pull: offline, the push fails first and the pull is never tried. */
    val attempts = AtomicInteger()

    override suspend fun push(body: SyncPushBody): SyncResult<SyncPushResult> {
        attempts.incrementAndGet()
        return if (offline) SyncResult.Failure(SyncFailure.Network) else SyncResult.Success(SyncPushResult(applied = body.mutations.map { it.id }))
    }

    override suspend fun pull(householdId: String, since: Long, limit: Int): SyncResult<SyncPullPage> {
        attempts.incrementAndGet()
        pulls.incrementAndGet()
        return if (offline) SyncResult.Failure(SyncFailure.Network) else SyncResult.Success(if (since < page.cursor) page else SyncPullPage(cursor = since))
    }

    override suspend fun refreshSession() = Unit
}

/**
 * SHOP-09 on DESIGN C2: the real list screen and view model on a real Room file, the real engine with a stub server, and a
 * fake Realtime that fires events. Nothing recreates the view model between the event and the check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class RealtimeComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private val server = RealtimeServer()
    private val realtime = FakeRealtime()
    private lateinit var vm: ListDetailViewModel

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun show(online: Boolean) {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
            registry.withSearchDao {
                it.upsertListItem(
                    ListItemEntity("i1", hid, "l1", "Milk", nameKey = "milk", checked = false, createdAt = "2026-11-01T00:00:00.000Z"),
                )
            }
        }
        val engine = LiveSyncEngine(server, registry, isOnline = { true }, scope = scope)
        scope.launch { runHouseholdRealtime(realtime, hid, RealtimeTables.tables(true, true, true)) { engine.kick() } }
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { }, isOnline = MutableStateFlow(online),
            delayer = { gate.await() }, now = { now }, newId = { "n1" }, scope = scope,
        )
        rule.setContent { AbodeTheme { ListDetailScreenHost(vm, onBack = {}) { _, _, _, _, _, _, _ -> } } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { realtime.active == 1 }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    @Test
    fun `AC2 another member checked off Milk, and after the event's pull Milk is under Checked without recreating the view model`() {
        show(online = true)
        await("list.row.Milk")
        assertFalse(exists("list.checked"))
        val viewModel = vm
        server.page = SyncPullPage(cursor = 5, changes = mapOf("list_item" to listOf(milkCheckedByAnotherMember())))

        // The payload is whatever the server sent; the screen only changes because the pull applied the row.
        realtime.fireEvent("""{"type":"UPDATE"}""")

        await("list.checked")
        rule.onNodeWithTag("list.checked").assertTextContains("Checked (1)")
        rule.waitUntil(5_000) { !exists("list.row.Milk") }
        assertTrue(viewModel === vm)
        assertEquals(1, server.pulls.get())
    }

    @Test
    fun `AC3 offline, an event and an error status leave the local rows and raise no realtime error`() {
        show(online = false)
        server.offline = true
        await("list.row.Milk")
        assertTrue(exists("offline-banner"))

        realtime.fireStatus(RealtimeStatus.Error)
        realtime.fireEvent()
        rule.waitUntil(5_000) { server.attempts.get() >= 1 }
        rule.waitForIdle()

        // The rows are the local ones and the only notice is the usual offline banner.
        assertTrue(exists("list.row.Milk"))
        assertFalse(exists("list.checked"))
        assertFalse(exists("list.error"))
        assertTrue(exists("offline-banner"))
    }
}
