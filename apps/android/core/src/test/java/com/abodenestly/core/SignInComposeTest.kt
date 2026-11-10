package com.abodenestly.core

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.abodenestly.core.auth.AuthError
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.FakeAuthService.Call
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One case per State and Interaction of DESIGN B1 (TESTING §7). Runs on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInComposeTest {
    @get:Rule val rule = createComposeRule()

    private val redirect = "abodenestly-dev://auth/callback"

    /** The resend countdown ticks only when the test sends on this channel, so 30 s take no real time. */
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private val auth = FakeAuthService()

    // Unconfined: state changes land synchronously, so assertions after `waitForIdle` are deterministic.
    private fun viewModel() = SignInViewModel(
        auth, redirect, sleep = { ticks.receive() }, scope = CoroutineScope(Job() + Dispatchers.Unconfined),
    )

    private fun show(online: Boolean = true) {
        val vm = viewModel()
        rule.setContent { AbodeTheme { SignInScreen(vm, isOnline = online) } }
    }

    private fun typeEmail(text: String) = rule.onNodeWithTag("signin.email").performTextInput(text)

    @Test
    fun `AC1 idle renders the form and both providers`() {
        show()
        for (tag in listOf("signin.title", "signin.email", "signin.send", "signin.apple", "signin.google")) {
            rule.onNodeWithTag(tag).assertExists()
        }
        rule.onNodeWithTag("signin.title").assertTextEquals("Sign in to AbodeNestly")
        rule.onNodeWithTag("signin.send").assertIsEnabled()
        rule.onNodeWithTag("signin.spinner").assertDoesNotExist()
        rule.onNodeWithTag("signin.error").assertDoesNotExist()
        rule.onNodeWithTag("offline-banner").assertDoesNotExist()
    }

    @Test
    fun `AC2 sending shows a spinner on the button and disables every action`() {
        auth.holdSignIn()
        show()
        typeEmail("ada@example.com")
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("signin.spinner").assertExists()
        rule.onNodeWithTag("signin.send").assertIsNotEnabled()
        rule.onNodeWithTag("signin.apple").assertIsNotEnabled()
        rule.onNodeWithTag("signin.google").assertIsNotEnabled()
        auth.releaseSignIn()
    }

    @Test
    fun `AC2 link sent replaces the form and resend unlocks after 30 s`() {
        show()
        typeEmail("ada@example.com")
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitForIdle()

        assertEquals(listOf<Call>(Call.SendMagicLink("ada@example.com", redirect)), auth.calls)
        rule.onNodeWithTag("signin.linkSent").assertExists()
        rule.onNodeWithTag("signin.email").assertDoesNotExist()
        rule.onNodeWithTag("signin.apple").assertDoesNotExist()
        rule.onNodeWithTag("signin.resend").assertIsNotEnabled().assertTextEquals("Resend (30 s)")

        repeat(29) { ticks.trySend(Unit) }
        rule.waitForIdle()
        rule.onNodeWithTag("signin.resend").assertIsNotEnabled().assertTextEquals("Resend (1 s)")

        ticks.trySend(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag("signin.resend").assertIsEnabled().assertTextEquals("Resend")

        rule.onNodeWithTag("signin.resend").performClick()
        rule.waitForIdle()
        assertEquals(2, auth.calls.size)
        rule.onNodeWithTag("signin.resend").assertIsNotEnabled()
    }

    @Test
    fun `AC3 invalid email shows an inline error and sends nothing`() {
        show()
        typeEmail("nope")
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("signin.error").assertTextEquals("Enter a valid email address.")
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `rate limited error is shown under the field`() {
        auth.sendMagicLinkError = AuthError.RateLimited
        show()
        typeEmail("ada@example.com")
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("signin.error").assertTextEquals("Too many attempts. Wait a minute and try again.")
        rule.onNodeWithTag("signin.send").assertIsEnabled()
    }

    @Test
    fun `network error is shown under the field`() {
        auth.sendMagicLinkError = AuthError.Network
        show()
        typeEmail("ada@example.com")
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("signin.error")
            .assertTextEquals("Couldn't reach the server. Check your connection and try again.")
    }

    @Test
    fun `AC5 Apple starts the Apple flow`() {
        show()
        rule.onNodeWithTag("signin.apple").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Call>(Call.SignInWithApple), auth.calls)
    }

    @Test
    fun `AC5 Google starts the Google flow`() {
        show()
        rule.onNodeWithTag("signin.google").performClick()
        rule.waitForIdle()
        assertEquals(listOf<Call>(Call.SignInWithGoogle), auth.calls)
    }

    @Test
    fun `AC5 a provider spinner sits on the tapped control only`() {
        auth.holdSignIn()
        show()
        rule.onNodeWithTag("signin.google").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("signin.google.spinner").assertExists()
        rule.onNodeWithTag("signin.apple.spinner").assertDoesNotExist()
        rule.onNodeWithTag("signin.spinner").assertDoesNotExist()
        auth.releaseSignIn()
    }

    @Test
    fun `AC5 cancelling a provider returns to idle without an error`() {
        auth.appleError = AuthError.Cancelled
        show()
        rule.onNodeWithTag("signin.apple").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("signin.error").assertDoesNotExist()
        rule.onNodeWithTag("signin.apple").assertIsEnabled()
    }

    @Test
    fun `AC7 offline shows the banner and disables every action`() {
        show(online = false)
        rule.onNodeWithTag("offline-banner").assertExists()
        rule.onNodeWithTag("signin.send").assertIsNotEnabled()
        rule.onNodeWithTag("signin.apple").assertIsNotEnabled()
        rule.onNodeWithTag("signin.google").assertIsNotEnabled()
    }

    @Test
    fun `AC6 a fresh start with a stored session is signed in without any sign-in call`() {
        val stored = FakeAuthService(Fixtures.session)
        val vm = SessionViewModel(
            stored, InMemoryLastHouseholdStore(), isOnline = { true },
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        vm.start()
        assertEquals(SessionStatus.SignedIn, vm.state.value.status)
        assertTrue(stored.calls.isEmpty())
    }
}
