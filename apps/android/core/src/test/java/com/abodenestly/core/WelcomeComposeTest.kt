package com.abodenestly.core

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.welcome.WelcomeOutcome
import com.abodenestly.core.welcome.WelcomeScreen
import com.abodenestly.core.welcome.WelcomeViewModel
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

/** One case per State and Interaction of DESIGN B2 (TESTING §7). Runs on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
// A tall screen keeps both cards and Sign out on screen; the default 320x470 puts Join below the fold.
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class WelcomeComposeTest {
    @get:Rule val rule = createComposeRule()

    private val api = FakeApiService()
    private val outcomes = mutableListOf<WelcomeOutcome>()
    private var signedOut = 0

    private fun show(online: Boolean = true, displayName: String? = "Ada") {
        // Unconfined: state changes land synchronously, so assertions after `waitForIdle` are deterministic.
        val vm = WelcomeViewModel(api, onLanded = { outcomes += it }, scope = CoroutineScope(Job() + Dispatchers.Unconfined))
        rule.setContent {
            AbodeTheme { WelcomeScreen(vm, displayName = displayName, isOnline = online, onSignOut = { signedOut++ }) }
        }
    }

    /** The message is the field's supporting text, so it is merged into the field's node (one TalkBack control). */
    private fun error(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun typeName(text: String) = rule.onNodeWithTag("welcome.name").performTextInput(text)
    private fun typeCode(text: String) = rule.onNodeWithTag("welcome.code").performTextInput(text)

    @Test
    fun `AC1 idle greets by display name and shows both cards`() {
        show()
        rule.onNodeWithTag("welcome.title").assertTextEquals("Welcome, Ada")
        for (tag in listOf("welcome.name", "welcome.create", "welcome.code", "welcome.join", "welcome.signout")) {
            rule.onNodeWithTag(tag).assertExists()
        }
        rule.onNodeWithTag("welcome.create").assertIsEnabled()
        rule.onNodeWithTag("welcome.join").assertIsEnabled()
        rule.onNodeWithTag("offline-banner").assertDoesNotExist()
        rule.onNodeWithTag("welcome.createError", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("welcome.joinError", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("welcome.creating").assertDoesNotExist()
    }

    @Test
    fun `AC1 without a display name the title is just Welcome`() {
        show(displayName = null)
        rule.onNodeWithTag("welcome.title").assertTextEquals("Welcome")
    }

    @Test
    fun `AC2 creating shows a spinner and disables both buttons`() {
        api.hold()
        show()
        typeName("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("welcome.creating").assertExists()
        rule.onNodeWithTag("welcome.create").assertIsNotEnabled()
        rule.onNodeWithTag("welcome.join").assertIsNotEnabled()
        api.release()
    }

    @Test
    fun `AC3 a create error is shown and the typed name stays`() {
        api.createResult = ApiResult.Failure(ApiFailure.Network)
        show()
        typeName("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()
        rule.waitForIdle()
        error("welcome.createError").assertTextEquals("Couldn't reach the server. Check your connection and try again.")
        rule.onNodeWithTag("welcome.name").assertTextContains("Test Home")
        rule.onNodeWithTag("welcome.create").assertIsEnabled()
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `AC4 offline shows the banner, disables both buttons and keeps the text`() {
        show(online = false)
        typeName("Test Home")
        rule.onNodeWithTag("offline-banner").assertExists()
        rule.onNodeWithTag("welcome.create").assertIsNotEnabled()
        rule.onNodeWithTag("welcome.join").assertIsNotEnabled()
        rule.onNodeWithTag("welcome.name").assertTextContains("Test Home")
        rule.onNodeWithTag("welcome.signout").assertIsEnabled()
    }

    @Test
    fun `AC5 an invalid code shows error-invalid-code under the code field`() {
        api.acceptResult = ApiResult.Failure(ApiFailure.InvalidCode)
        show()
        typeCode("ABC123")
        rule.onNodeWithTag("welcome.join").performClick()
        rule.waitForIdle()
        error("welcome.joinError").assertTextEquals("That code isn't valid or has expired. Check it and try again.")
        rule.onNodeWithTag("welcome.code").assertTextContains("ABC123")
        error("welcome.createError").assertDoesNotExist()
    }

    @Test
    fun `tapping Create records CreateHousehold and reports Created`() {
        val household = Fixtures.household(1)
        api.createResult = ApiResult.Success(household)
        show()
        typeName("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Call>(Call.CreateHousehold("Test Home")), api.calls)
        assertEquals(listOf<WelcomeOutcome>(WelcomeOutcome.Created(household)), outcomes)
    }

    @Test
    fun `tapping Join records AcceptInvite and reports Joined`() {
        api.acceptResult = ApiResult.Success(Fixtures.id(7))
        show()
        typeCode("ABC123")
        rule.onNodeWithTag("welcome.join").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Call>(Call.AcceptInvite("ABC123")), api.calls)
        assertEquals(listOf<WelcomeOutcome>(WelcomeOutcome.Joined(Fixtures.id(7))), outcomes)
    }

    @Test
    fun `an empty name shows the validation message without calling the API`() {
        show()
        rule.onNodeWithTag("welcome.create").performClick()
        rule.waitForIdle()
        error("welcome.createError").assertTextEquals("Enter a name for your household.")
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `Sign out calls back`() {
        show()
        rule.onNodeWithTag("welcome.signout").performClick()
        assertEquals(1, signedOut)
    }
}
