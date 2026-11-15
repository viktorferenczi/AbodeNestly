package com.abodenestly.core

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abodenestly.contract.models.Member
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.member
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.members.InMemoryMembersCache
import com.abodenestly.core.members.MembersScreen
import com.abodenestly.ui.AbodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CORE-04 AC2 / DESIGN B6: rotation, font scale or dark mode must not cut the 5 s undo window short. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class MembersConfigChangeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeApiService()
    private val cache = InMemoryMembersCache()
    private val bob = member(11, "Bob")

    // The rule allows one setContent per test, so each activity instance hosts its own ComposeView.
    private fun content() = rule.activityRule.scenario.onActivity { activity ->
        activity.setContentView(ComposeView(activity).apply {
            setContent {
                AbodeTheme {
                    MembersScreen(id(1), id(10), api, isOnline = true, webBaseUrl = "http://localhost:5173", cache = cache, onLeft = {})
                }
            }
        })
    }

    @Test
    fun `CORE-04 - AC2 a configuration change keeps the undo toast and sends no DELETE`() {
        api.membersResult = ApiResult.Success(listOf(member(10, "Ada Lovelace", Member.Role.owner), bob))
        content()
        rule.waitForIdle()
        rule.onNodeWithTag("members.row.${bob.id}.menu").performClick()
        rule.onNodeWithTag("members.row.${bob.id}.remove").performClick()
        rule.onNodeWithText("Removed Bob").assertExists()

        rule.activityRule.scenario.recreate()
        content()
        rule.waitForIdle()

        assertTrue(api.calls.none { it is Call.RemoveMember })
        rule.onNodeWithTag("undo-toast").assertExists()
        rule.onNodeWithText("Removed Bob").assertExists()
        rule.onNodeWithTag("members.row.${bob.id}").assertDoesNotExist()
    }
}
