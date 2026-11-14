package com.abodenestly.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abodenestly.contract.models.Member
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.member
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.api.MemberRow
import com.abodenestly.core.members.InMemoryMembersCache
import com.abodenestly.core.members.MembersScreen
import com.abodenestly.ui.AbodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One case per State and Interaction of DESIGN B6 member rows (TESTING §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class MembersComposeTest {
    @get:Rule val rule = createComposeRule()

    private val api = FakeApiService()
    private val cache = InMemoryMembersCache()
    private val me = id(10)
    private val owner = member(10, "Ada Lovelace", Member.Role.owner)
    private val bob = member(11, "Bob")
    private var online by mutableStateOf(true)
    private var left = 0

    private fun show(vararg rows: MemberRow, viewer: java.util.UUID = me) {
        api.membersResult = ApiResult.Success(rows.toList())
        rule.setContent {
            AbodeTheme {
                MembersScreen(id(1), viewer, api, isOnline = online, webBaseUrl = "http://localhost:5173", cache = cache, onLeft = { left++ })
            }
        }
        rule.waitForIdle()
    }

    private fun tag(row: MemberRow, suffix: String = "") = "members.row.${row.id}$suffix"

    @Test
    fun `CORE-04 - AC1 owner view shows avatar, name, role and You`() {
        show(owner, bob)
        rule.onNodeWithTag(tag(owner, ".name"), useUnmergedTree = true).assertTextEquals("Ada Lovelace")
        rule.onNodeWithTag(tag(owner, ".role"), useUnmergedTree = true).assertTextEquals("Owner")
        rule.onNodeWithTag(tag(owner, ".you"), useUnmergedTree = true).assertTextEquals("You")
        rule.onNodeWithTag(tag(bob, ".role"), useUnmergedTree = true).assertTextEquals("Member")
        rule.onNodeWithTag(tag(bob, ".you"), useUnmergedTree = true).assertDoesNotExist()
        rule.onAllNodesWithText("AL", useUnmergedTree = true).assertCountEquals(1)
        // The owner has a menu on others, not on themself.
        rule.onNodeWithTag(tag(bob, ".menu")).assertExists()
        rule.onNodeWithTag(tag(owner, ".menu")).assertDoesNotExist()
    }

    @Test
    fun `CORE-04 - AC2 Remove starts the toast and hides the row, Undo brings it back`() {
        show(owner, bob)
        rule.onNodeWithTag(tag(bob, ".menu")).performClick()
        rule.onNodeWithTag(tag(bob, ".remove")).performClick()
        rule.onNodeWithTag("undo-toast").assertExists()
        rule.onNodeWithText("Removed Bob").assertExists()
        rule.onNodeWithTag(tag(bob)).assertDoesNotExist()
        assertTrue(api.calls.none { it is Call.RemoveMember })
        rule.onNodeWithTag("members.leave").assertIsNotEnabled()

        rule.onNodeWithTag("undo-toast.undo").performClick()
        rule.onNodeWithTag("undo-toast").assertDoesNotExist()
        rule.onNodeWithTag(tag(bob)).assertExists()
        assertTrue(api.calls.none { it is Call.RemoveMember })
    }

    @Test
    fun `CORE-04 - AC5 a member has no menu and can leave`() {
        val asMember = member(10, "Ada")
        show(asMember, member(11, "Bob", Member.Role.owner))
        rule.onNodeWithTag("members.row.${id(11)}.menu").assertDoesNotExist()
        rule.onNodeWithTag(tag(asMember, ".menu")).assertDoesNotExist()
        rule.onNodeWithTag("members.leave").assertIsEnabled().performClick()
        rule.onNodeWithText("Leaving household").assertExists()
    }

    @Test
    fun `CORE-04 - AC6 offline shows the banner and disables every change`() {
        kotlinx.coroutines.runBlocking { cache.write(listOf(owner, bob), id(1)) }
        online = false
        show(owner, bob)
        rule.onNodeWithTag("offline-banner").assertExists()
        rule.onNodeWithTag(tag(bob, ".name"), useUnmergedTree = true).assertTextEquals("Bob")
        rule.onNodeWithTag("members.leave").assertIsNotEnabled()
        rule.onNodeWithTag("members.invite").assertIsNotEnabled()
        rule.onNodeWithTag(tag(bob, ".menu")).assertIsNotEnabled()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `CORE-04 - menu items are disabled when the connection drops while open`() {
        show(owner, bob)
        rule.onNodeWithTag(tag(bob, ".menu")).performClick()
        rule.onNodeWithTag(tag(bob, ".remove")).assertIsEnabled()
        online = false
        rule.waitForIdle()
        rule.onNodeWithTag(tag(bob, ".remove")).assertIsNotEnabled()
        rule.onNodeWithTag(tag(bob, ".makeOwner")).assertIsNotEnabled()
    }

    @Test
    fun `CORE-04 - overflow Make owner promotes and loads again`() {
        show(owner, bob)
        rule.onNodeWithTag(tag(bob, ".menu")).performClick()
        rule.onNodeWithTag(tag(bob, ".makeOwner")).performClick()
        rule.waitForIdle()
        assertEquals(Call.MakeOwner(id(1), bob.id), api.calls.filterIsInstance<Call.MakeOwner>().single())
        assertEquals(2, api.calls.filterIsInstance<Call.FetchMembers>().size)
    }

    @Test
    fun `CORE-04 - an owner has no Make owner item for another owner`() {
        val cy = member(12, "Cy", Member.Role.owner)
        show(owner, cy)
        rule.onNodeWithTag(tag(cy, ".menu")).performClick()
        rule.onNodeWithTag(tag(cy, ".makeOwner")).assertDoesNotExist()
        rule.onNodeWithTag(tag(cy, ".remove")).assertExists()
    }

    @Test
    fun `CORE-04 - a failed Make owner shows the dialog`() {
        api.makeOwnerResult = ApiResult.Failure(ApiFailure.Unknown)
        show(owner, bob)
        rule.onNodeWithTag(tag(bob, ".menu")).performClick()
        rule.onNodeWithTag(tag(bob, ".makeOwner")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Couldn't make Bob an owner. Try again.").assertExists()
        rule.onNodeWithTag("members.alert.ok").performClick()
        rule.onNodeWithTag("members.alert").assertDoesNotExist()
    }

    @Test
    fun `CORE-04 - Leave as the last owner shows the dialog and sends nothing`() {
        show(owner, bob)
        rule.onNodeWithTag("members.leave").performClick()
        rule.onNodeWithText("You're the last owner. Make someone else an owner before leaving.").assertExists()
        rule.onNodeWithTag("undo-toast").assertDoesNotExist()
        rule.onNodeWithTag("members.alert.ok").performClick()
        rule.onNodeWithTag("members.alert").assertDoesNotExist()
        assertTrue(api.calls.none { it is Call.RemoveMember })
        assertEquals(0, left)
    }

    @Test
    fun `CORE-04 - a missing profile falls back to Member and M`() {
        val hidden = member(11, name = null)
        show(owner, hidden)
        rule.onNodeWithTag(tag(hidden, ".name"), useUnmergedTree = true).assertTextEquals("Member")
        rule.onAllNodesWithText("M", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `CORE-04 - load failure shows the error and Try again recovers`() {
        api.membersResult = ApiResult.Failure(ApiFailure.Network)
        rule.setContent {
            AbodeTheme { MembersScreen(id(1), me, api, true, "http://localhost:5173", cache, onLeft = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("members.error").assertExists()
        api.membersResult = ApiResult.Success(listOf(owner))
        rule.onNodeWithTag("members.retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("members.error").assertDoesNotExist()
        rule.onNodeWithTag(tag(owner)).assertExists()
    }

    @Test
    fun `CORE-04 - offline with no cache says to connect`() {
        online = false
        show()
        rule.onNodeWithTag("members.connect").assertExists()
    }
}
