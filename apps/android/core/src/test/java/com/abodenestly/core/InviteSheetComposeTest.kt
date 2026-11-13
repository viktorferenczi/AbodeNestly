package com.abodenestly.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.abodenestly.contract.models.Invite
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.invite
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.invite.InviteSheetContent
import com.abodenestly.core.invite.InviteSheetViewModel
import com.abodenestly.core.members.MembersScreen
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One case per State and Interaction of DESIGN B6 (TESTING §7). Tests the sheet's content, not the bottom-sheet popup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class InviteSheetComposeTest {
    @get:Rule val rule = createComposeRule()

    private val api = FakeApiService()
    private val shared = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private val webBase = "http://localhost:5173"

    private fun show(online: Boolean = true): InviteSheetViewModel {
        val vm = InviteSheetViewModel(id(1), api, copy = { copied += it }, scope = CoroutineScope(Job() + Dispatchers.Unconfined))
        rule.setContent { AbodeTheme { Host(vm, online) } }
        return vm
    }

    @Composable
    private fun Host(vm: InviteSheetViewModel, online: Boolean) {
        val state by vm.state.collectAsState()
        val oldCodeMayWork by vm.oldCodeMayWork.collectAsState()
        val notice by vm.notice.collectAsState()
        // Same wiring as `InviteSheet`: loads when online, never while offline.
        androidx.compose.runtime.LaunchedEffect(online) { if (online) vm.loadIfNeeded() }
        InviteSheetContent(
            state, oldCodeMayWork, notice, online, webBase,
            onNewCode = vm::newCode, onCopy = vm::copyCode, onShare = { shared += it },
            onRetry = vm::loadIfNeeded, onClose = {},
        )
    }

    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun `AC1 the code, meta and enabled buttons show with no banner`() {
        api.liveInviteResult = ApiResult.Success(invite("abc123def456", uses = 0))
        show()
        rule.waitForIdle()
        text("invite.code").assertTextEquals("abc123def456")
        text("invite.meta").assertTextEquals("10 uses left · Expires 19 Nov")
        for (tag in listOf("invite.copy", "invite.newCode", "invite.share")) rule.onNodeWithTag(tag).assertIsEnabled()
        rule.onNodeWithTag("offline-banner").assertDoesNotExist()
        rule.onNodeWithTag("invite.close").assertExists()
    }

    @Test
    fun `AC2 New code swaps in the new code and retires the old one`() {
        val old = invite("aaaaaaaaaaaa")
        api.liveInviteResult = ApiResult.Success(old)
        api.createInviteResults.add(ApiResult.Success(invite("bbbbbbbbbbbb")))
        show()
        rule.onNodeWithTag("invite.newCode").performClick()
        rule.waitForIdle()
        text("invite.code").assertTextEquals("bbbbbbbbbbbb")
        rule.onAllNodesWithText("aaaaaaaaaaaa").assertCountEquals(0)
        assertTrue(Call.RetireInvite(old.id) in api.calls)
        rule.onNodeWithTag("invite.oldCode", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `AC2 while rotating the spinner shows and Share, Copy and New code are disabled`() {
        api.liveInviteResult = ApiResult.Success(invite("aaaaaaaaaaaa"))
        show()
        api.hold()
        rule.onNodeWithTag("invite.newCode").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("invite.rotating", useUnmergedTree = true).assertExists()
        for (tag in listOf("invite.share", "invite.copy", "invite.newCode")) rule.onNodeWithTag(tag).assertIsNotEnabled()
        api.release()
    }

    @Test
    fun `AC2 a failed retire says the old code may still work`() {
        api.liveInviteResult = ApiResult.Success(invite("aaaaaaaaaaaa"))
        api.createInviteResult = ApiResult.Success(invite("bbbbbbbbbbbb"))
        api.retireResult = ApiResult.Failure(ApiFailure.Unknown)
        show()
        rule.onNodeWithTag("invite.newCode").performClick()
        rule.waitForIdle()
        text("invite.oldCode").assertTextEquals("The new code is ready, but the old code may still work.")
        text("invite.code").assertTextEquals("bbbbbbbbbbbb")
    }

    @Test
    fun `a failed New code keeps the old code and says so`() {
        api.liveInviteResult = ApiResult.Success(invite("aaaaaaaaaaaa"))
        api.createInviteResult = ApiResult.Failure(ApiFailure.Network)
        show()
        rule.onNodeWithTag("invite.newCode").performClick()
        rule.waitForIdle()
        text("invite.error").assertTextEquals("Couldn't create a new code. The current code still works.")
        text("invite.code").assertTextEquals("aaaaaaaaaaaa")
        rule.onNodeWithTag("invite.newCode").assertIsEnabled()
    }

    @Test
    fun `AC3 Share passes the join link to onShare`() {
        api.liveInviteResult = ApiResult.Success(invite("abc123def456"))
        show()
        rule.onNodeWithTag("invite.share").performClick()
        assertEquals(listOf("http://localhost:5173/join/abc123def456"), shared)
    }

    @Test
    fun `AC5 offline with an invite shows the banner and disables New code and Share`() {
        // Seed the invite while online, then flip offline in the content directly.
        val live = invite("abc123def456")
        rule.setContent {
            AbodeTheme {
                InviteSheetContent(
                    com.abodenestly.core.invite.InviteSheetState.Ready(live), false, null, false, webBase,
                    onNewCode = {}, onCopy = {}, onShare = {}, onRetry = {}, onClose = {},
                )
            }
        }
        rule.onNodeWithTag("offline-banner").assertExists()
        rule.onNodeWithTag("invite.newCode").assertIsNotEnabled()
        rule.onNodeWithTag("invite.share").assertIsNotEnabled()
        text("invite.code").assertTextEquals("abc123def456")
    }

    @Test
    fun `AC5 offline with no invite shows Connect and makes no API call`() {
        show(online = false)
        rule.waitForIdle()
        text("invite.connect").assertTextEquals("Connect to see the invite code.")
        rule.onNodeWithTag("offline-banner").assertExists()
        rule.onNodeWithTag("invite.share").assertIsNotEnabled()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `Copy shows Code copied`() {
        api.liveInviteResult = ApiResult.Success(invite("abc123def456"))
        show()
        rule.onNodeWithTag("invite.copy").performClick()
        rule.waitForIdle()
        text("invite.notice").assertTextEquals("Code copied")
        assertEquals(listOf("abc123def456"), copied)
    }

    @Test
    fun `a load error shows the message and Try again loads the invite`() {
        api.liveInviteResult = ApiResult.Failure(ApiFailure.Network)
        show()
        text("invite.loadError").assertTextEquals("Couldn't load the invite code. Try again.")
        api.liveInviteResult = ApiResult.Success(invite("abc123def456"))
        rule.onNodeWithTag("invite.retry").performClick()
        rule.waitForIdle()
        text("invite.code").assertTextEquals("abc123def456")
        rule.onNodeWithTag("invite.loadError", useUnmergedTree = true).assertDoesNotExist()
    }

    /** The sheet has no role branch (SPEC §4 CORE-03), so Members → Invite works the same whatever role the live invite has. */
    private fun membersOpensSheet(role: Invite.Role) {
        api.liveInviteResult = ApiResult.Success(invite("abc123def456", role = role))
        rule.setContent { AbodeTheme { MembersScreen(id(1), api, isOnline = true, webBaseUrl = webBase) } }
        rule.onNodeWithTag("members.invite").assertExists()
        rule.onNodeWithTag("invite.sheet").assertDoesNotExist()
        rule.onNodeWithTag("members.invite").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("invite.sheet").assertExists()
        text("invite.code").assertTextEquals("abc123def456")
        rule.onNodeWithTag("invite.newCode").assertIsEnabled()
        rule.onNodeWithTag("invite.share").assertIsEnabled()
    }

    @Test
    fun `owner sees Invite and the sheet opens`() = membersOpensSheet(Invite.Role.owner)

    @Test
    fun `member sees Invite and the sheet opens`() = membersOpensSheet(Invite.Role.member)
}
