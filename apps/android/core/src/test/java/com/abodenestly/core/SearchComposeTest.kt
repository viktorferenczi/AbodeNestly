package com.abodenestly.core

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.search.InMemoryRecentSearchStore
import com.abodenestly.core.search.SearchContent
import com.abodenestly.core.search.SearchTarget
import com.abodenestly.core.search.SearchViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncPullPage
import com.abodenestly.sync.SyncPushBody
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.SyncTransport
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.PantryItemEntity
import com.abodenestly.sync.SearchModule
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

private val ALL_MODULES = SearchModule.entries.toSet()

/** One case per State and Interaction of DESIGN B8 (TESTING §7). The 150 ms debounce runs on a test scheduler; the rows come from a real Room file. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class SearchComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)
    private val scheduler = TestCoroutineScheduler()
    private val recents = InMemoryRecentSearchStore()
    private val opened = mutableListOf<SearchTarget>()

    /** Every method throws [IOException]; a search that touched the network would fail loudly or show up in [apiCalls]. */
    private val apiCalls = AtomicInteger()
    private val offlineTransport = object : SyncTransport {
        override suspend fun push(body: SyncPushBody): SyncResult<SyncPushResult> { apiCalls.incrementAndGet(); throw IOException("offline") }
        override suspend fun pull(householdId: String, since: Long, limit: Int): SyncResult<SyncPullPage> { apiCalls.incrementAndGet(); throw IOException("offline") }
        override suspend fun refreshSession() { apiCalls.incrementAndGet() }
    }
    private val offlineApi: ApiService = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, _, _ ->
        apiCalls.incrementAndGet()
        throw IOException("offline")
    } as ApiService

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun seed() = runBlocking {
        registry.open(household)
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("l1", household.toString(), "list1", "Milk"))
            dao.upsertPantryItem(PantryItemEntity("p1", household.toString(), "Milk powder"))
        }
    }

    private fun show(modules: Set<SearchModule> = ALL_MODULES, debounceMs: Long = 150) {
        seed()
        val vm = SearchViewModel(
            household, modules, { q, m -> registry.searchNames(household, q, m) }, recents,
            navigate = { opened += it.target }, debounceMs = debounceMs, scope = CoroutineScope(StandardTestDispatcher(scheduler)),
        )
        rule.setContent {
            AbodeTheme {
                val ui by vm.state.collectAsState()
                SearchContent(ui, vm::onEvent)
            }
        }
        scheduler.runCurrent()
        rule.waitForIdle()
    }

    private fun type(text: String) {
        rule.onNodeWithTag("search.field").performTextInput(text)
        scheduler.runCurrent()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Runs the virtual clock while the real database thread answers. */
    private fun await(tag: String) = rule.waitUntil(5_000) { scheduler.runCurrent(); exists(tag) }

    private fun debounce() {
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
    }

    @Test
    fun `CORE-09 AC1 the empty query shows recent searches and tapping one fills the field and searches`() {
        runBlocking { recents.record(household, "oat"); recents.record(household, "milk") }
        show()
        rule.onNodeWithTag("search.empty").assertIsDisplayed()
        rule.onAllNodesWithTag("search.recent").assertCountEquals(2)
        rule.onAllNodesWithTag("search.recent")[0].assertTextContains("milk")
        rule.onAllNodesWithTag("search.recent")[0].performClick()
        scheduler.runCurrent()
        rule.onNodeWithTag("search.field").assertTextContains("milk")
        debounce()
        await("search.row")
        assertTrue(!exists("search.empty"))
    }

    @Test
    fun `CORE-09 AC1 the search field is focused when the screen opens`() {
        show()
        rule.onNodeWithTag("search.field").assertIsFocused()
    }

    @Test
    fun `CORE-09 AC2 no-results appears at 150 ms and not at 149 ms`() {
        show()
        type("zzz")
        scheduler.advanceTimeBy(149)
        scheduler.runCurrent()
        rule.waitForIdle()
        assertTrue(exists("search.empty"))
        assertTrue(!exists("search.noResults"))
        scheduler.advanceTimeBy(1)
        await("search.noResults")
        assertTrue(!exists("search.empty"))
    }

    @Test
    fun `CORE-09 AC3 a list item and a pantry item are grouped under Shopping and Pantry`() {
        show()
        type("milk")
        debounce()
        await("search.section.pantry")
        rule.onNodeWithTag("search.section.shopping").assertIsDisplayed()
        assertTrue(!exists("search.section.maintenance"))
        rule.onAllNodesWithTag("search.row").assertCountEquals(2)
        val top = { tag: String -> rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("search.section.shopping") < top("search.section.pantry"))
        rule.onNodeWithText("List item").assertIsDisplayed()
        rule.onNodeWithText("Pantry item").assertIsDisplayed()
    }

    @Test
    fun `CORE-09 AC4 with pantry off no Pantry section shows although the pantry row is stored`() {
        show(setOf(SearchModule.Shopping, SearchModule.Maintenance))
        type("milk")
        debounce()
        await("search.section.shopping")
        rule.waitForIdle()
        assertTrue(!exists("search.section.pantry"))
        rule.onAllNodesWithTag("search.row").assertCountEquals(1)
    }

    @Test
    fun `CORE-09 AC5 offline the results come from the local database and no request is made`() {
        val offline = NetworkMonitor(online = false)
        // The app kicks a pull on foreground; offline it must not reach the (always throwing) API, and Search never does.
        LiveSyncEngine(offlineTransport, registry, isOnline = { offline.isOnline.value }, scope = CoroutineScope(Dispatchers.Unconfined)).kick()
        show()
        type("milk")
        debounce()
        await("search.section.shopping")
        rule.onNodeWithTag("search.section.pantry").assertIsDisplayed()
        assertEquals(0, apiCalls.get())
    }

    @Test
    fun `CORE-09 tapping a row opens its target and records the query as a recent search`() {
        show()
        type("milk")
        debounce()
        await("search.row")
        rule.onAllNodesWithTag("search.row")[0].performClick()
        scheduler.runCurrent()
        assertEquals(listOf(SearchTarget(SearchModule.Shopping, "list_item", "l1", "list1")), opened)
        assertEquals(listOf("milk"), runBlocking { recents.read(household) })
    }

    @Test
    fun `CORE-09 the search key searches the field text before the debounce and records it when it found something`() {
        show()
        type("milk")
        scheduler.advanceTimeBy(50)
        scheduler.runCurrent()
        rule.onNodeWithTag("search.field").performImeAction()
        await("search.row")
        assertEquals(listOf("milk"), runBlocking { recents.read(household) })
    }

    @Test
    fun `CORE-09 the search key shows no-results at once and records nothing`() {
        show()
        type("zzz")
        scheduler.advanceTimeBy(50)
        scheduler.runCurrent()
        rule.onNodeWithTag("search.field").performImeAction()
        await("search.noResults")
        assertTrue(runBlocking { recents.read(household) }.isEmpty())
    }

    @Test
    fun `CORE-09 the search key records the field text, not the previous query`() {
        show()
        type("milk")
        debounce()
        await("search.row")
        rule.onNodeWithTag("search.field").performTextReplacement("bread")
        scheduler.runCurrent()
        rule.onNodeWithTag("search.field").performImeAction()
        await("search.noResults")
        assertTrue(runBlocking { recents.read(household) }.isEmpty())
    }
}
