package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.sync.DatabaseNotOpenException
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListDetail
import com.abodenestly.sync.ListRecord
import com.abodenestly.sync.setShareToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** SHOP-13 logic (DESIGN C8): the token and link rules, the sheet's transitions, and the local write on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareSheetTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val kicks = AtomicInteger()

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    // --- Pure logic ---

    @Test
    fun `token is the last path segment of an absolute path, percent-decoded`() {
        assertEquals("tok123", Share.token("https://api.test/shared/tok123"))
        assertEquals("tok123", Share.token("https://api.test/shared/tok123/"))
        assertEquals("tok123", Share.token("http://localhost:3000/shared/tok123?x=1#y"))
        assertEquals("a b/c", Share.token("https://api.test/shared/a%20b%2Fc"))
        assertEquals("a+b", Share.token("https://api.test/shared/a+b"))
        assertEquals("tok123", Share.token("/shared/tok123"))
    }

    @Test
    fun `token is none for an empty, relative or non-URL text`() {
        assertNull(Share.token(""))
        assertNull(Share.token("http://api.test"))
        assertNull(Share.token("http://api.test/"))
        assertNull(Share.token("not a url"))
        assertNull(Share.token("tok123"))
        assertNull(Share.token("shared/tok123"))
    }

    @Test
    fun `link is the web origin plus shared and the encoded token`() {
        assertEquals("http://localhost:5173/shared/tok123", Share.link("http://localhost:5173", "tok123"))
        assertEquals("https://app.example.com/shared/tok123", Share.link("https://app.example.com/", "tok123"))
        assertEquals("https://app.example.com/shared/a%2Fb%20c", Share.link("https://app.example.com", "a/b c"))
    }

    @Test
    fun `state transitions only change the token on success`() {
        val off = ShareState(null)
        assertFalse(off.isOn)
        val turning = off.begin(ShareBusy.TurningOn)
        assertTrue(turning.isBusy)
        assertNull(turning.token)
        assertEquals(ShareState("t"), turning.turnedOn("t"))
        val failed = turning.failed(ShareError.TurnOn)
        assertFalse(failed.isOn)
        assertEquals(ShareError.TurnOn, failed.error)
        val on = ShareState("t")
        assertEquals("t", on.begin(ShareBusy.Stopping).failed(ShareError.Stop).token)
        assertEquals(ShareState(null), on.stopped())
        assertNull(on.failed(ShareError.Stop).begin(ShareBusy.Stopping).error)
        assertEquals("Could not turn on sharing. Try again.", ShareError.TurnOn.message)
        assertEquals("Could not stop sharing. Try again.", ShareError.Stop.message)
    }

    @Test
    fun `actions offer Share first, templates too, and only when sharing is available`() {
        assertEquals(listOf(ListAction.Share), Templates.actions(isTemplate = false, hasChecked = false, canShare = true))
        assertEquals(listOf(ListAction.Share, ListAction.ClearChecked), Templates.actions(isTemplate = false, hasChecked = true, canShare = true))
        assertEquals(listOf(ListAction.Share, ListAction.AddAll), Templates.actions(isTemplate = true, hasChecked = false, canShare = true))
        assertEquals(
            listOf(ListAction.Share, ListAction.AddAll, ListAction.ClearChecked),
            Templates.actions(isTemplate = true, hasChecked = true, canShare = true),
        )
        assertEquals(emptyList<ListAction>(), Templates.actions(isTemplate = false, hasChecked = false, canShare = false))
    }

    @Test
    fun `Share is not offered before the list has loaded or on a gone list`() {
        val list = ListRecord("l1", "Groceries", null, false, null, null)
        assertFalse(ListDetailUiState().canShare)
        assertFalse(ListDetailUiState(loaded = false, detail = ListDetail(list = list)).canShare)
        assertFalse(ListDetailUiState(loaded = true, detail = ListDetail(list = null)).canShare)
        assertTrue(ListDetailUiState(loaded = true, detail = ListDetail(list = list)).canShare)
    }

    @Test
    fun `forApi calls the list's UUID and fails a text that is not one without a call`() {
        val api = FakeApiService()
        val setup = ShareSetup.forApi("http://localhost:5173", api)
        val id = uuid(5)
        assertEquals(ApiResult.Success("https://api.test/shared/tok123"), runBlocking { setup.share(id.toString()) })
        assertEquals(ApiResult.Success(Unit), runBlocking { setup.stop(id.toString()) })
        assertEquals(listOf(FakeApiService.Call.ShareList(id), FakeApiService.Call.StopSharing(id)), api.calls)
        assertEquals(ApiResult.Failure(ApiFailure.NotFound), runBlocking { setup.share("l1") })
        assertEquals(ApiResult.Failure(ApiFailure.NotFound), runBlocking { setup.stop("l1") })
        assertEquals(2, api.calls.size)
    }

    // --- The model, with fake calls ---

    private class Calls {
        val shares = AtomicInteger()
        val stops = AtomicInteger()
        val recorded = CopyOnWriteArrayList<String?>()
        val copied = CopyOnWriteArrayList<String>()
        @Volatile var shareResult: ApiResult<String> = ApiResult.Success("https://api.test/shared/tok123")
        @Volatile var stopResult: ApiResult<Unit> = ApiResult.Success(Unit)
        @Volatile var online = true
    }

    private fun model(calls: Calls, token: String? = null, delayer: suspend (Long) -> Unit = { CompletableDeferred<Unit>().await() }): ShareSheetModel = ShareSheetModel(
        initialToken = token,
        webBase = "http://localhost:5173",
        share = { calls.shares.incrementAndGet(); calls.shareResult },
        stop = { calls.stops.incrementAndGet(); calls.stopResult },
        record = { calls.recorded += it },
        isOnline = { calls.online },
        copy = { calls.copied += it },
        scope = scope,
        delayer = delayer,
    )

    @Test
    fun `AC1 a list with no token starts off, and one with a token starts on with its link`() {
        val calls = Calls()
        assertFalse(model(calls).state.value.share.isOn)
        assertNull(model(calls).state.value.link)
        val on = model(calls, token = "tok123").state.value
        assertTrue(on.share.isOn)
        assertEquals("http://localhost:5173/shared/tok123", on.link)
    }

    @Test
    fun `AC2 turning on posts, builds the link from the token and records it`() {
        val calls = Calls()
        val m = model(calls)
        m.setOn(true)
        assertEquals(1, calls.shares.get())
        assertEquals("http://localhost:5173/shared/tok123", m.state.value.link)
        assertTrue(m.state.value.share.isOn)
        assertNull(m.state.value.share.error)
        assertEquals(listOf<String?>("tok123"), calls.recorded.toList())
    }

    @Test
    fun `AC3 turning off deletes, returns the switch to off and records null`() {
        val calls = Calls()
        val m = model(calls, token = "tok123")
        m.setOn(false)
        assertEquals(1, calls.stops.get())
        assertFalse(m.state.value.share.isOn)
        assertNull(m.state.value.link)
        assertEquals(listOf<String?>(null), calls.recorded.toList())
    }

    @Test
    fun `AC4 offline disables the switch and makes no API call`() {
        val calls = Calls().also { it.online = false }
        val off = model(calls)
        assertFalse(off.canChange)
        off.setOn(true)
        off.turnOn()
        assertEquals(0, calls.shares.get())
        assertFalse(off.state.value.share.isOn)
        assertNull(off.state.value.share.error)
        val on = model(calls, token = "tok123")
        on.setOn(false)
        on.turnOff()
        assertEquals(0, calls.stops.get())
        assertTrue(on.state.value.share.isOn)
        assertTrue(calls.recorded.isEmpty())
    }

    @Test
    fun `offline a known link stays copyable`() {
        val calls = Calls().also { it.online = false }
        val m = model(calls, token = "tok123")
        m.copyLink()
        assertEquals(listOf("http://localhost:5173/shared/tok123"), calls.copied.toList())
        assertTrue(m.state.value.copied)
    }

    @Test
    fun `a failed turn-on returns the switch to off with the error and records nothing`() {
        val calls = Calls().also { it.shareResult = ApiResult.Failure(ApiFailure.Network) }
        val m = model(calls)
        m.turnOn()
        assertFalse(m.state.value.share.isOn)
        assertEquals(ShareError.TurnOn, m.state.value.share.error)
        assertTrue(calls.recorded.isEmpty())
    }

    @Test
    fun `a POST URL with no token counts as a failure`() {
        val calls = Calls().also { it.shareResult = ApiResult.Success("http://api.test") }
        val m = model(calls)
        m.turnOn()
        assertFalse(m.state.value.share.isOn)
        assertEquals(ShareError.TurnOn, m.state.value.share.error)
        assertTrue(calls.recorded.isEmpty())
    }

    @Test
    fun `a call that throws counts as a failure`() {
        val m = ShareSheetModel(
            initialToken = null, webBase = "http://localhost:5173", share = { error("boom") }, stop = { error("boom") },
            record = { }, isOnline = { true }, copy = { }, scope = scope,
        )
        m.turnOn()
        assertEquals(ShareError.TurnOn, m.state.value.share.error)
    }

    @Test
    fun `a failed stop keeps it on with the error and records nothing`() {
        val calls = Calls().also { it.stopResult = ApiResult.Failure(ApiFailure.Unknown) }
        val m = model(calls, token = "tok123")
        m.turnOff()
        assertTrue(m.state.value.share.isOn)
        assertEquals(ShareError.Stop, m.state.value.share.error)
        assertEquals("http://localhost:5173/shared/tok123", m.state.value.link)
        assertTrue(calls.recorded.isEmpty())
    }

    @Test
    fun `turning on while on, or off while off, calls nothing`() {
        val calls = Calls()
        model(calls, token = "tok123").turnOn()
        model(calls).turnOff()
        assertEquals(0, calls.shares.get())
        assertEquals(0, calls.stops.get())
    }

    @Test
    fun `Link copied shows until the window ends`() {
        val calls = Calls()
        val gate = CompletableDeferred<Unit>()
        val m = model(calls, token = "tok123", delayer = { gate.await() })
        assertFalse(m.state.value.copied)
        m.copyLink()
        assertTrue(m.state.value.copied)
        gate.complete(Unit)
        assertFalse(m.state.value.copied)
    }

    @Test
    fun `copy does nothing while sharing is off`() {
        val calls = Calls()
        val m = model(calls)
        m.copyLink()
        assertTrue(calls.copied.isEmpty())
        assertFalse(m.state.value.copied)
    }

    // --- The local write, on a real Room file ---

    private fun detailVm(listId: String = "l1", kick: suspend () -> Unit = { kicks.incrementAndGet() }): ListDetailViewModel {
        runBlocking {
            registry.open(household)
            if (listId == "l1") createList(registry, household, "Groceries", null, now, "l1")
        }
        val vm = ListDetailViewModel(
            household, listId, uuid(7), registry, kick = kick, isOnline = MutableStateFlow(true),
            delayer = { CompletableDeferred<Unit>().await() }, now = { now }, newId = { "n" }, scope = scope,
        )
        runBlocking { awaitUntil { vm.state.value.loaded } }
        return vm
    }

    private fun localToken(listId: String = "l1"): String? = runBlocking { registry.observeListDetail(household, listId).first().list?.shareToken }

    private fun makeShare(vm: ListDetailViewModel, calls: Calls): ShareSheetModel = vm.makeShare(
        webBase = "http://localhost:5173",
        share = { calls.shares.incrementAndGet(); calls.shareResult },
        stop = { calls.stops.incrementAndGet(); calls.stopResult },
        copy = { calls.copied += it },
    )

    @Test
    fun `a successful POST writes share_token locally with no outbox row and one kick, and a DELETE clears it`() {
        val vm = detailVm()
        val calls = Calls()
        val m = makeShare(vm, calls)
        // Creating the list queued its own upsert; the share writes must add none.
        val before = runBlocking { registry.pendingCount(household) }
        assertFalse(m.state.value.share.isOn)
        m.turnOn()
        runBlocking { awaitUntil { localToken() == "tok123" && kicks.get() == 1 } }
        assertEquals(before, runBlocking { registry.pendingCount(household) })
        assertEquals(1, kicks.get())

        // A new sheet starts from the local token.
        assertEquals("tok123", makeShare(vm, calls).state.value.share.token)

        m.turnOff()
        runBlocking { awaitUntil { localToken() == null && kicks.get() == 2 } }
        assertEquals(before, runBlocking { registry.pendingCount(household) })
        assertEquals(1, calls.stops.get())
    }

    @Test
    fun `a kick that writes the old value does not leave it, for both on and stop`() {
        val stale = AtomicInteger()
        var staleToken: String? = null
        val vm = detailVm(kick = { kicks.incrementAndGet(); registry.setShareToken(household, "l1", staleToken); stale.incrementAndGet() })
        val calls = Calls()
        val m = makeShare(vm, calls)

        // Turn on: a pull in flight writes the old value (none) back during the kick.
        m.turnOn()
        runBlocking { awaitUntil { stale.get() == 1 } }
        runBlocking { awaitUntil { localToken() == "tok123" } }
        assertEquals("tok123", localToken())

        // Stop: a pull in flight writes the old token back during the kick.
        staleToken = "tok123"
        m.turnOff()
        runBlocking { awaitUntil { stale.get() == 2 } }
        runBlocking { awaitUntil { localToken() == null } }
        assertNull(localToken())
    }

    @Test
    fun `setShareToken says false for a list that is not there and throws for another household`() {
        runBlocking {
            registry.open(household)
            assertFalse(registry.setShareToken(household, "missing", "x"))
        }
        val other = uuid(2)
        val thrown = runCatching { runBlocking { registry.setShareToken(other, "l1", "x") } }.exceptionOrNull()
        assertTrue(thrown is DatabaseNotOpenException)
    }

    @Test
    fun `OpenShare opens the sheet on a loaded list and CloseShare closes it`() {
        val vm = detailVm()
        vm.onEvent(ListDetailEvent.OpenShare)
        assertTrue(vm.state.value.shareOpen)
        vm.onEvent(ListDetailEvent.CloseShare)
        assertFalse(vm.state.value.shareOpen)
    }

    @Test
    fun `OpenShare does nothing on a gone list`() {
        val vm = detailVm(listId = "gone")
        assertTrue(vm.state.value.isGone)
        vm.onEvent(ListDetailEvent.OpenShare)
        assertFalse(vm.state.value.shareOpen)
    }
}
