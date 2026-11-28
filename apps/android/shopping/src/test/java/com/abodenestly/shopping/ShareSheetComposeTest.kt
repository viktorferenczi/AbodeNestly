package com.abodenestly.shopping

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.setShareToken
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * DESIGN C8 for SHOP-13 (TESTING §7), on a real Room file: the List actions entry, the sheet's states off, on and offline, and
 * the interactions. The sheet's body is mounted inline (Robolectric never drives the popup). The API is a pair of recording lambdas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class ShareSheetComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private val shares = AtomicInteger()
    private val stops = AtomicInteger()
    @Volatile private var shareResult: ApiResult<String> = ApiResult.Success("https://api.test/shared/tok123")
    @Volatile private var stopResult: ApiResult<Unit> = ApiResult.Success(Unit)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(15_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(15_000) { !exists(tag) }

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    private fun seedList(token: String? = null) = runBlocking {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        if (token != null) registry.setShareToken(household, "l1", token)
    }

    private fun showDetail(online: Boolean = true, withShare: Boolean = true, listId: String = "l1") {
        val vm = ListDetailViewModel(
            household, listId, uuid(7), registry, kick = { }, isOnline = MutableStateFlow(online),
            delayer = { gate.await() }, now = { now }, newId = { "n" }, scope = scope,
        )
        val setup = ShareSetup(
            "http://localhost:5173",
            share = { shares.incrementAndGet(); shareResult },
            stop = { stops.incrementAndGet(); stopResult },
        )
        rule.setContent {
            AbodeTheme {
                ListDetailScreenHost(
                    vm, onBack = {}, share = if (withShare) setup else null,
                    shareSheet = { model, isOnline, _ ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { ShareSheetContent(model, isOnline, onSystemShare = { }) }
                    },
                ) { _, _, _, _, _, _, _ -> }
            }
        }
        rule.waitForIdle()
    }

    private fun openShare() {
        await("list.actions")
        rule.onNodeWithTag("list.actions").performClick()
        await("list.actions.share")
        rule.onNodeWithTag("list.actions.share").performClick()
        await("share.sheet")
    }

    private fun clipboardText(): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return manager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `SHOP-13 Share is the first item of the List actions menu, even on a list with nothing checked`() {
        seedList()
        showDetail()
        await("list.actions")
        rule.onNodeWithTag("list.actions").performClick()
        await("list.actions.share")
        rule.onNodeWithText("Share").assertIsDisplayed()
        assertFalse(exists("list.actions.clearChecked"))
        assertFalse(exists("list.actions.addAll"))
    }

    @Test
    fun `SHOP-13 without a share setup the menu has no Share`() {
        seedList()
        showDetail(withShare = false)
        await("list.title")
        assertFalse(exists("list.actions"))
    }

    @Test
    fun `SHOP-13 a gone list has no menu`() {
        runBlocking { registry.open(household) }
        showDetail(listId = "gone")
        await("list.gone")
        assertFalse(exists("list.actions"))
    }

    @Test
    fun `SHOP-13 AC1 sharing is off, so the switch is off and there is no link`() {
        seedList()
        showDetail()
        openShare()
        rule.onNodeWithTag("share.switch").assertIsOff()
        rule.onNodeWithText("Share with a link").assertIsDisplayed()
        assertFalse(exists("share.link"))
        assertFalse(exists("share.stop"))
    }

    @Test
    fun `SHOP-13 AC2 turning the switch on shows the link, Copy link and the unchecked-items line`() {
        seedList()
        showDetail()
        openShare()
        rule.onNodeWithTag("share.switch").performClick()
        await("share.link")
        assertEquals(1, shares.get())
        rule.onNodeWithTag("share.switch").assertIsOn()
        rule.onNodeWithTag("share.link").assertTextEquals("http://localhost:5173/shared/tok123")
        rule.onNodeWithText("Copy link").assertIsDisplayed()
        rule.onNodeWithText("Share…").assertIsDisplayed()
        rule.onNodeWithText("Anyone with the link can view unchecked items").assertIsDisplayed()
        rule.onNodeWithText("The current link will stop working").assertIsDisplayed()
    }

    @Test
    fun `SHOP-13 a list that already has a token opens on`() {
        seedList(token = "tok123")
        showDetail()
        openShare()
        rule.onNodeWithTag("share.switch").assertIsOn()
        rule.onNodeWithTag("share.link").assertTextEquals("http://localhost:5173/shared/tok123")
        assertEquals(0, shares.get())
    }

    @Test
    fun `SHOP-13 AC3 Stop sharing deletes and the switch is off`() {
        seedList(token = "tok123")
        showDetail()
        openShare()
        rule.onNodeWithTag("share.stop").performClick()
        awaitGone("share.link")
        assertEquals(1, stops.get())
        rule.onNodeWithTag("share.switch").assertIsOff()
        assertFalse(exists("share.stop"))
    }

    @Test
    fun `SHOP-13 turning the switch off deletes too`() {
        seedList(token = "tok123")
        showDetail()
        openShare()
        rule.onNodeWithTag("share.switch").performClick()
        awaitGone("share.link")
        assertEquals(1, stops.get())
    }

    @Test
    fun `SHOP-13 Copy link puts the link on the clipboard and says Link copied politely`() {
        seedList(token = "tok123")
        showDetail()
        openShare()
        rule.onNodeWithTag("share.copy").performClick()
        await("share.copied")
        assertEquals("http://localhost:5173/shared/tok123", clipboardText())
        rule.onNodeWithTag("share.copied").assertTextEquals("Link copied").assert(liveRegion(LiveRegionMode.Polite))
    }

    @Test
    fun `SHOP-13 a failed turn-on returns the switch to off with an assertive error`() {
        shareResult = ApiResult.Failure(ApiFailure.Network)
        seedList()
        showDetail()
        openShare()
        rule.onNodeWithTag("share.switch").performClick()
        await("share.error")
        rule.onNodeWithTag("share.error").assertTextEquals("Could not turn on sharing. Try again.").assert(liveRegion(LiveRegionMode.Assertive))
        rule.onNodeWithTag("share.switch").assertIsOff()
        assertFalse(exists("share.link"))
    }

    @Test
    fun `SHOP-13 a failed stop keeps it on with an assertive error`() {
        stopResult = ApiResult.Failure(ApiFailure.Unknown)
        seedList(token = "tok123")
        showDetail()
        openShare()
        rule.onNodeWithTag("share.stop").performClick()
        await("share.error")
        rule.onNodeWithTag("share.error").assertTextEquals("Could not stop sharing. Try again.").assert(liveRegion(LiveRegionMode.Assertive))
        rule.onNodeWithTag("share.switch").assertIsOn()
        rule.onNodeWithTag("share.link").assertIsDisplayed()
    }

    @Test
    fun `SHOP-13 AC4 offline the switch is disabled, no call is made and the message shows`() {
        seedList()
        showDetail(online = false)
        openShare()
        rule.onNodeWithTag("share.switch").assertIsNotEnabled()
        rule.onNodeWithText("Sharing needs a connection").assertIsDisplayed()
        rule.onNodeWithTag("share.switch").performClick()
        rule.waitForIdle()
        assertEquals(0, shares.get())
        rule.onNodeWithTag("share.switch").assertIsOff()
    }

    @Test
    fun `SHOP-13 offline a known link stays visible and copyable, and Stop sharing is disabled`() {
        seedList(token = "tok123")
        showDetail(online = false)
        openShare()
        rule.onNodeWithTag("share.link").assertTextEquals("http://localhost:5173/shared/tok123")
        rule.onNodeWithTag("share.stop").assertIsNotEnabled()
        rule.onNodeWithTag("share.copy").assertIsEnabled()
        rule.onNodeWithTag("share.copy").performClick()
        await("share.copied")
        assertEquals("http://localhost:5173/shared/tok123", clipboardText())
        assertEquals(0, stops.get())
    }
}
