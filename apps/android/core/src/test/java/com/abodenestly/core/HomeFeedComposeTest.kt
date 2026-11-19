package com.abodenestly.core

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.contract.models.Activity
import com.abodenestly.core.Fixtures.activity
import com.abodenestly.core.Fixtures.homeNow
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.home.ActivityPull
import com.abodenestly.core.home.HomeEvent
import com.abodenestly.core.home.HomeModules
import com.abodenestly.core.home.HomeScreen
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

private val ALL_ON = HomeModules(shopping = true, pantry = true, maintenance = true)
private val ALL_OFF = HomeModules(shopping = false, pantry = false, maintenance = false)

/** One case per State and Interaction of DESIGN B4 (TESTING §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class HomeFeedComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)
    private val sync = FakeSyncEngine()
    private val events = mutableListOf<HomeEvent>()

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun show(modules: HomeModules? = ALL_ON, rows: List<Activity> = emptyList(), engine: SyncEngine = sync) {
        runBlocking {
            registry.open(household)
            registry.upsertActivity(household, rows)
        }
        rule.setContent { AbodeTheme { HomeScreen(household, modules, registry, engine, onNavigate = { events += it }) } }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun settle() = rule.waitUntil(5_000) { exists("home.empty") || exists("home.feed.row") || exists("home.allOff") }

    /** The window is relative to the wall clock inside the screen, so rows are dated from it. */
    private fun fresh(summary: String, ago: Duration, over: (Activity) -> Activity = { it }) =
        over(activity(summary, Duration.ZERO)).let {
            val at = java.time.OffsetDateTime.now().minus(ago)
            it.copy(summary = summary, createdAt = at, updatedAt = at)
        }

    @Test
    fun `AC1 - no activity shows the empty-activity state with an invite button`() {
        show()
        settle()
        rule.onNodeWithTag("home.empty").assertIsDisplayed()
        rule.onNodeWithText("Things your household does show up here.").assertIsDisplayed()
        rule.onNodeWithTag("home.empty.invite").performClick()
        assertEquals(listOf<HomeEvent>(HomeEvent.OpenMembers), events)
        rule.onAllNodesWithTag("home.feed.row").assertCountEquals(0)
    }

    @Test
    fun `AC2 - shows sentences newest first, at most 20, from the last 30 days only`() {
        val rows = listOf(
            fresh("Anna checked off Milk", Duration.ofSeconds(5)),
            fresh("Ben added Eggs", Duration.ofMinutes(1)),
            fresh("Too old", Duration.ofDays(31)),
        ) + (0 until 22).map { fresh("Filler $it", Duration.ofMinutes(5L + it)) }
        show(rows = rows)
        settle()
        rule.onAllNodesWithTag("home.feed.row").assertCountEquals(20)
        rule.onNodeWithText("Anna checked off Milk").assertIsDisplayed()
        rule.onNodeWithText("Ben added Eggs").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("home.empty").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Too old").fetchSemanticsNodes().isEmpty())
        // Newest first: Anna's row sits above Ben's.
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Anna checked off Milk") < top("Ben added Eggs"))
    }

    @Test
    fun `AC3 - every module off shows the settings prompt, no Today cards, and the feed still lists core events`() {
        show(ALL_OFF, listOf(fresh("Ben joined the household", Duration.ofSeconds(5)) .copy(module = Activity.Module.core)))
        settle()
        rule.onNodeWithTag("home.allOff").assertIsDisplayed()
        rule.onNodeWithText("Every module is off. Turn one on in Settings.").assertIsDisplayed()
        rule.onNodeWithText("Ben joined the household").assertIsDisplayed()
        assertTrue(!exists("home.today.shopping"))
        rule.onNodeWithTag("home.allOff.settings").performClick()
        assertEquals(listOf<HomeEvent>(HomeEvent.OpenSettings), events)
    }

    @Test
    fun `a module that is off hides its Today card and the others open their module`() {
        show(HomeModules(shopping = false, pantry = true, maintenance = true))
        settle()
        assertTrue(!exists("home.today.shopping"))
        rule.onNodeWithTag("home.today.maintenance").assertIsDisplayed()
        rule.onNodeWithTag("home.today.pantry").performClick()
        assertEquals(listOf<HomeEvent>(HomeEvent.OpenModule(AppTab.Pantry)), events)
    }

    @Test
    fun `footer links open Members, Settings and Profile`() {
        show()
        settle()
        rule.onNodeWithTag("home.members").performClick()
        rule.onNodeWithTag("home.settings").performClick()
        rule.onNodeWithTag("home.profile").performClick()
        assertEquals(listOf(HomeEvent.OpenMembers, HomeEvent.OpenSettings, HomeEvent.OpenProfile), events)
    }

    @Test
    fun `AC4 - releasing a pull to refresh calls kick`() {
        show()
        settle()
        rule.onNodeWithTag("home.list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f) }
        rule.waitUntil(5_000) { sync.kicks.get() == 1 }
        assertEquals(1, sync.kicks.get())
    }

    @Test
    fun `AC5 - offline the cached feed is shown and a pull makes no request`() {
        val api = FakeApiService()
        val offline = NetworkMonitor(online = false)
        val engine = ActivityPull(api, registry, isOnline = { offline.isOnline.value }, scope = CoroutineScope(Dispatchers.Unconfined), now = { homeNow })
        show(rows = listOf(fresh("Anna checked off Milk", Duration.ofSeconds(5))), engine = engine)
        settle()
        rule.onNodeWithText("Anna checked off Milk").assertIsDisplayed()
        rule.onNodeWithTag("home.list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f) }
        rule.waitForIdle()
        assertTrue(api.calls.isEmpty())
        rule.onNodeWithText("Anna checked off Milk").assertIsDisplayed()
    }

    @Test
    fun `a row pulled while the screen is open appears`() {
        show()
        settle()
        rule.onNodeWithTag("home.empty").assertIsDisplayed()
        runBlocking { registry.upsertActivity(household, listOf(fresh("Cleo joined the household", Duration.ofSeconds(5)))) }
        rule.waitUntil(5_000) { exists("home.feed.row") }
        rule.onNodeWithText("Cleo joined the household").assertIsDisplayed()
    }
}
