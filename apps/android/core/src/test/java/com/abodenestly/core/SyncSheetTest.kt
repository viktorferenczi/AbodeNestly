package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.sync.SyncSheetEvent
import com.abodenestly.core.sync.SyncSheetViewModel
import com.abodenestly.core.sync.rejectionText
import com.abodenestly.shared.AppConfig
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncFailure
import com.abodenestly.sync.SyncMutation
import com.abodenestly.sync.SyncPhase
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.SyncPushBody
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncRejected
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.upsert
import com.abodenestly.ui.SyncChipState
import com.abodenestly.ui.syncChipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** SHOP-08 logic in `:core`: the chip's precedence, the sheet's state, and the HTTP transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSheetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    // --- chip ---

    @Test
    fun `the chip reads syncing, then offline, then N to sync, else nothing`() {
        assertEquals(SyncChipState.Syncing, syncChipState(syncing = true, offline = true, pending = 2, failed = 1))
        assertEquals(SyncChipState.Offline, syncChipState(syncing = false, offline = true, pending = 2, failed = 1))
        assertEquals(SyncChipState.Pending(3), syncChipState(syncing = false, offline = false, pending = 2, failed = 1))
        assertEquals(SyncChipState.Pending(1), syncChipState(syncing = false, offline = false, pending = 0, failed = 1))
        assertEquals(SyncChipState.Hidden, syncChipState(syncing = false, offline = false, pending = 0, failed = 0))
    }

    @Test
    fun `a rejection reads the way web and iOS word it`() {
        assertEquals("not allowed", rejectionText("rls_denied"))
        assertEquals("invalid", rejectionText("validation"))
        assertEquals("module turned off", rejectionText("module_disabled"))
        assertEquals("unknown", rejectionText("unknown_table"))
        assertEquals("unknown", rejectionText("from_the_future"))
    }

    // --- sheet state ---

    private fun row(n: Int) = buildJsonObject { put("id", "i$n"); put("list_id", "l1"); put("name", "Item $n") }

    private fun viewModel(sync: FakeSyncEngine, online: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        SyncSheetViewModel(household, registry, sync, online, CoroutineScope(Dispatchers.Default))

    @Test
    fun `AC4 - the state counts queued writes and its chip reads N to sync`() = runBlocking {
        registry.open(household)
        repeat(3) { registry.upsert(household, "list_item", row(it)) }
        val vm = viewModel(FakeSyncEngine())
        val state = withTimeout(5_000) { vm.state.first { it.pending == 3 } }
        assertEquals(SyncChipState.Pending(3), state.chip)
        assertEquals("Up to date", state.statusText)
    }

    @Test
    fun `offline and syncing change the status line and the chip`() = runBlocking {
        registry.open(household)
        val engine = FakeSyncEngine()
        val online = MutableStateFlow(true)
        val vm = viewModel(engine, online)
        online.value = false
        assertEquals(SyncChipState.Offline, withTimeout(5_000) { vm.state.first { !it.online } }.chip)
        online.value = true
        engine.phase.value = SyncPhase.Syncing
        val syncing = withTimeout(5_000) { vm.state.first { it.phase == SyncPhase.Syncing } }
        assertEquals(SyncChipState.Syncing, syncing.chip)
        assertEquals("Syncing…", syncing.statusText)
    }

    @Test
    fun `Sync now kicks, Retry and Discard hand the sequence to the engine`() = runBlocking {
        registry.open(household)
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.onEvent(SyncSheetEvent.SyncNow)
        vm.onEvent(SyncSheetEvent.Retry(4))
        vm.onEvent(SyncSheetEvent.Discard(5))
        withTimeout(5_000) { while (engine.retried.isEmpty() || engine.discarded.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(1, engine.kicks.get())
        assertEquals(listOf(4L), engine.retried.toList())
        assertEquals(listOf(5L), engine.discarded.toList())
    }

    @Test
    fun `AC3 - a rejected change shows in the state with its table, action and reason`() = runBlocking {
        registry.open(household)
        registry.upsert(household, "list_item", row(1))
        val mutationId = registry.outboxForTesting().single().id
        val api = FakeApiService()
        api.pushResult = SyncResult.Success(SyncPushResult(rejected = listOf(SyncRejected(mutationId, "validation", "bad"))))
        LiveSyncEngine(api, registry, isOnline = { true }, scope = CoroutineScope(Dispatchers.Unconfined)).run()
        val state = withTimeout(5_000) { viewModel(FakeSyncEngine()).state.first { it.failed.isNotEmpty() } }
        assertEquals(listOf("list_item"), state.failed.map { it.table })
        assertEquals(listOf("upsert"), state.failed.map { it.op })
        assertEquals(listOf("validation"), state.failed.map { it.reason })
        assertEquals(SyncChipState.Pending(1), state.chip)
    }

    // --- HttpApiService as the /sync transport ---

    private val config = AppConfig("https://api.test", "https://web.test", "https://sb.test", "anon", "abodenestly-dev")

    private fun reply(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun http(respond: (Request) -> Response, auth: FakeAuthService = FakeAuthService()): HttpApiService {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> respond(chain.request()) }).build()
        return HttpApiService(config, auth, client)
    }

    private fun bodyOf(request: Request): String = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test
    fun `push posts the envelope to the sync route with the bearer token`() = runBlocking {
        var seen: Request? = null
        var sent = ""
        val service = http({ request ->
            seen = request
            sent = bodyOf(request)
            reply(request, 200, """{"applied":["m1"],"rejected":[{"id":"m2","reason":"validation","detail":"x"}]}""")
        })
        val mutation = SyncMutation("m1", "list_item", "upsert", "i1", buildJsonObject { put("name", "Milk") }, "2026-11-25T10:00:00Z")
        val result = service.push(SyncPushBody("h1", listOf(mutation))) as SyncResult.Success<SyncPushResult>
        assertEquals("POST", seen!!.method)
        assertEquals("https://api.test/sync", seen!!.url.toString())
        assertEquals("Bearer fake-token", seen!!.header("Authorization"))
        val json = Json.parseToJsonElement(sent).jsonObject
        assertEquals("h1", json["household_id"]!!.jsonPrimitive.content)
        val first = json["mutations"]!!.jsonArray[0].jsonObject
        assertEquals("i1", first["row_id"]!!.jsonPrimitive.content)
        assertEquals("2026-11-25T10:00:00Z", first["client_ts"]!!.jsonPrimitive.content)
        assertEquals(listOf("m1"), result.value.applied)
        assertEquals("validation", result.value.rejected.single().reason)
    }

    @Test
    fun `pull gets the page for the household from the cursor`() = runBlocking {
        var seen: Request? = null
        val service = http({ request ->
            seen = request
            reply(request, 200, """{"cursor":12,"has_more":true,"changes":{"list_item":[{"id":"i1","name":"Milk","new_column":1}]}}""")
        })
        val page = (service.pull("h1", 7, 500) as SyncResult.Success<SyncPullPage>).value
        assertEquals("GET", seen!!.method)
        assertEquals("https://api.test/sync?household_id=h1&since=7&limit=500", seen!!.url.toString())
        assertEquals(12L, page.cursor)
        assertTrue(page.hasMore)
        assertEquals(1, page.changes.getValue("list_item").size)
    }

    @Test
    fun `503 retryable is Retryable, other failures are Other, and an unreachable server is Network`() = runBlocking {
        val retryable = http({ reply(it, 503, """{"error":{"code":"retryable","message":"try again"}}""") })
        assertEquals(SyncResult.Failure(SyncFailure.Retryable), retryable.pull("h", 0, 500))
        val broken = http({ reply(it, 500, """{"error":{"code":"internal","message":"x"}}""") })
        assertEquals(SyncResult.Failure(SyncFailure.Other), broken.pull("h", 0, 500))
        val garbage = http({ reply(it, 200, "not json") })
        assertEquals(SyncResult.Failure(SyncFailure.Other), garbage.pull("h", 0, 500))
        val offline = http({ throw IOException("no route") })
        assertEquals(SyncResult.Failure(SyncFailure.Network), offline.push(SyncPushBody("h", emptyList())))
    }

    @Test
    fun `a 401 refreshes the session once and retries`() = runBlocking {
        val auth = FakeAuthService()
        var calls = 0
        val service = http({ request -> if (calls++ == 0) reply(request, 401, "{}") else reply(request, 200, """{"cursor":1}""") }, auth)
        val page = (service.pull("h", 0, 500) as SyncResult.Success<SyncPullPage>).value
        assertEquals(1L, page.cursor)
        assertEquals(listOf(false, true), auth.tokenRequests.toList())
    }

    @Test
    fun `refreshSession asks the auth service for a fresh token`() = runBlocking {
        val auth = FakeAuthService()
        http({ reply(it, 200, "{}") }, auth).refreshSession()
        assertEquals(listOf(true), auth.tokenRequests.toList())
    }
}
