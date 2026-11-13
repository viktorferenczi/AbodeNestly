package com.abodenestly.core

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.members.MembersScreen
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.welcome.WelcomeScreen
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.core.welcome.land
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E-CORE-1, invite half: user A creates a household and reads its invite code from Home → Members → Invite;
 * user B signs in, joins with that code on Welcome and lands on Home. Port of iOS `InviteSheetUITests`.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys. Hosts the real screens, view models
 * and services in a plain activity, like [WelcomeE2ETest].
 */
class InviteSheetE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private var owner: E2E.TestUser? = null
    private var joiner: E2E.TestUser? = null

    @Before
    fun createUsers() {
        val keys = E2E.keys()
        assumeTrue("Pass serviceRoleKey and anonKey instrumentation args to run the e2e journey", keys != null)
        this.keys = keys
        owner = E2E.createUser(keys!!)
        joiner = E2E.createUser(keys)
    }

    @After
    fun cleanUp() {
        val keys = keys ?: return
        owner?.let { user ->
            // Households first: they reference the owner through `created_by`; member rows cascade with them.
            runCatching { E2E.rows(keys, "household", "created_by=eq.${user.id}&select=id") }.getOrNull()
                ?.forEach { E2E.delete(keys, "household", "id=eq.${it.getString("id")}") }
            E2E.deleteUser(keys, user.id)
        }
        joiner?.let { E2E.deleteUser(keys, it.id) }
    }

    @Test
    fun testCORE03_inviteCodeLetsASecondUserJoin() {
        val keys = keys!!
        val owner = owner!!
        val joiner = joiner!!
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        val api = HttpApiService(config, auth)
        // Await initialization first: signing out before the stored session loads leaves the last journey's session in place.
        runBlocking { auth.currentSession(); auth.signOut() }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true })
        val welcome = WelcomeViewModel(api, onLanded = session::land)
        // A fresh sign-in view model per person, so B does not inherit A's "link sent" state and email.
        var signIn by mutableStateOf(SignInViewModel(auth, config.authCallbackUrl))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn, welcome, api, config.webBaseUrl) } }
            session.start()
        }

        // User A creates a household, then opens Home → Members → Invite.
        signInThroughMagicLink(auth, owner)
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("welcome.name").performTextInput("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()
        rule.waitUntil(15_000) { exists("tab.home.content") }
        rule.onNodeWithTag("home.members").performClick()
        rule.onNodeWithTag("members.invite").performClick()
        rule.waitUntil(15_000) { exists("invite.code") }
        val code = rule.onNodeWithTag("invite.code", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        assertFalse(code.isEmpty())
        rule.onNodeWithTag("invite.share").assertIsEnabled()
        rule.onNodeWithTag("invite.close").performClick()
        rule.waitUntil(5_000) { !exists("invite.sheet") }

        // Sign out; user B signs in, enters the code on Welcome and lands on Home.
        rule.onNodeWithTag("home.signout").performClick()
        awaitTag("signin.title", 10_000)
        signIn = SignInViewModel(auth, config.authCallbackUrl)
        signInThroughMagicLink(auth, joiner)
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("welcome.code").performTextInput(code)
        rule.onNodeWithTag("welcome.join").performClick()
        rule.waitUntil(15_000) { exists("tab.home.content") }

        // Both users are active members of the household, and B joined as a member.
        val household = E2E.rows(keys, "household", "created_by=eq.${owner.id}&select=id").single().getString("id")
        val members = E2E.rows(keys, "member", "household_id=eq.$household&left_at=is.null&select=user_id,role")
        assertEquals(setOf(owner.id, joiner.id), members.map { it.getString("user_id") }.toSet())
        assertEquals("member", members.single { it.getString("user_id") == joiner.id }.getString("role"))
        scenario.close()
    }

    private fun signInThroughMagicLink(auth: SupabaseAuthService, user: E2E.TestUser) {
        rule.waitUntil(10_000) { exists("signin.title") }
        rule.onNodeWithTag("signin.email").performTextInput(user.email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }
        runBlocking { auth.handle(E2E.callbackFromMagicLink(user.email)) }
    }

    /** What `:app`'s AppRoot shows, reduced to the parts this journey drives: Home with a Members link, and Sign out. */
    @Composable
    private fun Host(session: SessionViewModel, signIn: SignInViewModel, welcome: WelcomeViewModel, api: HttpApiService, webBaseUrl: String) {
        val state by session.state.collectAsState()
        when (state.status) {
            SessionStatus.Loading -> Unit
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = true)
            SessionStatus.SignedIn -> when (val destination = state.destination) {
                Destination.Welcome ->
                    WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = true, onSignOut = session::signOut)
                is Destination.Household -> if (destination.tab == AppTab.Home) {
                    var members by rememberSaveable { mutableStateOf(false) }
                    Column {
                        TextButton(onClick = session::signOut, modifier = Modifier.testTag("home.signout")) { Text("Sign out") }
                        if (members) {
                            MembersScreen(destination.id, api, isOnline = true, webBaseUrl = webBaseUrl, modifier = Modifier.weight(1f))
                        } else {
                            Text("Home", modifier = Modifier.testTag("tab.home.content"))
                            TextButton(onClick = { members = true }, modifier = Modifier.testTag("home.members")) { Text("Members") }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    /** Waits for [tag]; a timeout carries the visible UI tree so a CI log shows where the journey stopped. */
    private fun awaitTag(tag: String, millis: Long = 15_000) {
        try {
            rule.waitUntil(millis) { exists(tag) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("No '$tag' after $millis ms. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
