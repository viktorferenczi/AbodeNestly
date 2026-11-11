package com.abodenestly.core

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.Text
import androidx.test.core.app.ActivityScenario
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.shared.AppConfig
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.TopBar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E-CORE-1, first half: sign in with a real PKCE magic link against `supabase start`.
 *
 * Lives in `:core`, so it cannot start `:app`'s MainActivity. It hosts the real [SignInScreen],
 * [SessionViewModel] and [SupabaseAuthService] in a plain activity instead, and delivers the
 * `abodenestly-dev://auth/callback?code=…` URL to `auth.handle` (what MainActivity does with the intent).
 * "Relaunch" closes the activity and builds a fresh service, which must read the persisted session.
 *
 * Needs instrumentation args `anonKey` and `serviceRoleKey` (apps/android/README.md); skipped without them.
 * Never uses Apple or Google (TESTING §5).
 */
class SignInE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private lateinit var config: AppConfig
    private var user: E2E.TestUser? = null
    private val localPart get() = user!!.localPart
    private val email get() = user!!.email

    @Before
    fun createUser() {
        val keys = E2E.keys()
        assumeTrue("Pass serviceRoleKey and anonKey instrumentation args to run the e2e journey", keys != null)
        this.keys = keys
        config = keys!!.config()
        // TESTING §5: every run creates its own user and deletes it at the end.
        user = E2E.createUser(keys)
    }

    @After
    fun deleteUser() {
        user?.let { E2E.deleteUser(keys!!, it.id) }
    }

    @Test
    fun magicLinkLandsOnWelcomeWithDisplayNameAndSurvivesRelaunch() {
        // AC1: signed out → sign-in screen.
        var auth = SupabaseAuthService(config)
        var scenario = launch(auth, reset = true)
        rule.waitUntil(10_000) { exists("signin.title") }

        rule.onNodeWithTag("signin.email").performTextInput(email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }

        // AC4: a real PKCE exchange. The verify URL answers with a redirect to the app's custom scheme;
        // handing that Location to the same install lets the SDK use the stored code verifier.
        runBlocking { auth.handle(E2E.callbackFromMagicLink(email)) }

        // AC4: no household yet → Welcome. AC8: the profile trigger set display_name to the email local part.
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("topbar.trailing").assertTextEquals(localPart)

        // AC6: closed and reopened (a fresh service, no reset) → still signed in.
        scenario.close()
        auth = SupabaseAuthService(config)
        scenario = launch(auth, reset = false)
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("topbar.trailing").assertTextEquals(localPart)
        scenario.close()
    }

    // MARK: Hosting

    private fun launch(auth: SupabaseAuthService, reset: Boolean): ActivityScenario<ComponentActivity> {
        if (reset) runBlocking { auth.signOut() }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true })
        val signIn = SignInViewModel(auth, config.authCallbackUrl)
        return ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn) } }
            session.start()
        }
    }

    /** What `:app`'s AppRoot shows, reduced to the parts this journey asserts on. */
    @Composable
    private fun Host(session: SessionViewModel, signIn: SignInViewModel) {
        val state by session.state.collectAsState()
        when (state.status) {
            SessionStatus.Loading -> Unit
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = true)
            SessionStatus.SignedIn -> {
                TopBar("AbodeNestly", state.profile?.displayName)
                // Only the landing rule may put "Welcome" here: no household → Destination.Welcome (AC4).
                if (state.destination == Destination.Welcome) Text("Welcome", modifier = Modifier.testTag("welcome.title"))
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
