package com.abodenestly.core

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.contract.models.Member
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.core.switcher.InMemoryHouseholdsCache
import com.abodenestly.core.switcher.SwitcherContent
import com.abodenestly.core.switcher.SwitcherViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** One case per State and Interaction of DESIGN B3 (TESTING §7). Tests the sheet's content, not the bottom-sheet popup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class SwitcherComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val api = FakeApiService()
    private val cache = InMemoryHouseholdsCache()
    private val me = Fixtures.userId
    private val alpha = HouseholdWithRole(Fixtures.household(1, "Alpha"), Member.Role.owner)
    private val beta = HouseholdWithRole(Fixtures.household(2, "Beta"), Member.Role.member)
    private val performed = mutableListOf<Pair<UUID, Boolean>>()
    private var dismissed = 0
    private var createOrJoin = 0
    private var online = true

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun tag(n: Int) = "switcher.row.${id(n)}"

    private fun show(
        cached: List<HouseholdWithRole>,
        currentN: Int = 1,
        perform: suspend (UUID, Boolean) -> Unit = { target, settings -> performed += target to settings },
    ) {
        runBlocking { cache.write(me, cached) }
        val current = cached.firstOrNull { it.household.id == id(currentN) }
        val vm = SwitcherViewModel(
            me, id(currentN), current, api, cache, registry, isOnline = { online }, perform = perform,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        rule.setContent { AbodeTheme { SwitcherContent(vm, onDismiss = { dismissed++ }, onCreateOrJoin = { createOrJoin++ }) } }
    }

    private fun waitFor(tag: String) = rule.waitUntil(5_000) {
        rule.onAllNodesWithTagCount(tag) > 0
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun `AC1 - one household still opens and offers create or join`() {
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha))
        show(listOf(alpha))
        waitFor(tag(1))
        rule.onNodeWithTag("switcher.title").assertTextContains("Households")
        rule.onNodeWithTag(tag(1)).assertIsSelected().assertTextContains("Alpha", substring = true)
        rule.onNodeWithTag(tag(1)).assertTextContains("Owner", substring = true)
        rule.onNodeWithTag("switcher.switching").assertDoesNotExist()
        rule.onNodeWithTag("switcher.create").assertTextContains("Create or join another").performClick()
        assertEquals(1, createOrJoin)
        assertEquals("the current household is not switched to", emptyList<Pair<UUID, Boolean>>(), performed)
    }

    @Test
    fun `AC2 - many lists every household with role, pending count and the current checked`() {
        // Beta's file is read while another household is open, so its queue is counted from disk.
        runBlocking {
            registry.open(id(2)); registry.enqueueForTesting(); registry.enqueueForTesting()
            registry.open(id(1))
        }
        api.myHouseholdsResult = ApiResult.Success(listOf(beta, alpha))
        show(listOf(beta, alpha))
        waitFor(tag(2))
        rule.waitUntil(5_000) { rule.onNodeWithTag(tag(2)).fetchSemanticsNode().config.toString().contains("2 to sync") }
        rule.onNodeWithTag(tag(1)).assertIsSelected().assertTextContains("Owner", substring = true)
        rule.onNodeWithTag(tag(2)).assertTextContains("Member · 2 to sync", substring = true)
        // Sorted by name: Alpha above Beta.
        val top = { t: String -> rule.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top(tag(1)) < top(tag(2)))
    }

    @Test
    fun `AC2 - tapping another household switches and dismisses`() {
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha, beta))
        show(listOf(alpha, beta))
        waitFor(tag(2))
        rule.onNodeWithTag(tag(2)).performClick()
        rule.waitUntil(5_000) { dismissed == 1 }
        assertEquals(listOf(id(2) to false), performed)
    }

    @Test
    fun `tapping the current household dismisses without a switch`() {
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha, beta))
        show(listOf(alpha, beta))
        waitFor(tag(1))
        rule.onNodeWithTag(tag(1)).performClick()
        rule.waitUntil(5_000) { dismissed == 1 }
        assertEquals(emptyList<Pair<UUID, Boolean>>(), performed)
    }

    @Test
    fun `AC3 - the switching state shows while the open is held and the rows are disabled`() {
        val held = CompletableDeferred<Unit>()
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha, beta))
        show(listOf(alpha, beta), perform = { target, settings -> performed += target to settings; held.await() })
        waitFor(tag(2))
        rule.onNodeWithTag(tag(2)).performClick()
        waitFor("switcher.switching")
        rule.onNodeWithTag("switcher.switching").assertTextContains("Switching to Beta…", substring = true)
        rule.onNodeWithTag(tag(1)).assertIsNotEnabled()
        rule.onNodeWithTag(tag(2)).assertIsNotEnabled()
        rule.onNodeWithTag("switcher.create").assertIsNotEnabled()
        assertEquals(0, dismissed)
        held.complete(Unit)
        rule.waitUntil(5_000) { dismissed == 1 }
        rule.onNodeWithTag("switcher.switching").assertDoesNotExist()
    }

    @Test
    fun `a failed switch shows the error, keeps the sheet and enables the rows again`() {
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha, beta))
        show(listOf(alpha, beta), perform = { _, _ -> throw IllegalStateException("boom") })
        waitFor(tag(2))
        rule.onNodeWithTag(tag(2)).performClick()
        waitFor("switcher.error")
        rule.onNodeWithTag("switcher.error").assertTextContains("Couldn't switch households. Try again.")
        rule.onNodeWithTag(tag(2)).assertIsEnabled()
        assertEquals(0, dismissed)
    }

    @Test
    fun `long-press opens that household's settings and is not also a tap`() {
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha, beta))
        show(listOf(alpha, beta))
        waitFor(tag(2))
        rule.onNodeWithTag(tag(2)).performTouchInput { longClick() }
        rule.waitUntil(5_000) { dismissed == 1 }
        assertEquals("one switch, asking for Settings", listOf(id(2) to true), performed)
    }

    @Test
    fun `AC5 - offline makes no API call and the switch still completes`() {
        online = false
        show(listOf(alpha, beta))
        waitFor(tag(2))
        rule.onNodeWithTag(tag(2)).performClick()
        rule.waitUntil(5_000) { dismissed == 1 }
        assertEquals(listOf(id(2) to false), performed)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `online the list refreshes from the server`() {
        api.myHouseholdsResult = ApiResult.Success(listOf(alpha, beta))
        show(listOf(alpha))
        waitFor(tag(2))
        assertEquals(listOf(FakeApiService.Call.FetchMyHouseholds(me)), api.calls)
        assertEquals(2, runBlocking { cache.read(me) }.size)
    }
}
