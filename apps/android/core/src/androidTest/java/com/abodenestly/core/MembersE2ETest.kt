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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.members.InMemoryMembersCache
import com.abodenestly.core.members.MembersScreen
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.welcome.WelcomeScreen
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.core.welcome.land
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E-CORE-1, members half: A creates a household and invites B; B joins and sees "You" on their own row, no menu
 * on A's and Leave enabled. A then sees B's row, is told they are the last owner on Leave, and removes B from the
 * overflow menu; five seconds later B's `member.left_at` is set. Port of iOS `MembersUITests`.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys.
 */
class MembersE2ETest {
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
            runCatching { E2E.rows(keys, "household", "created_by=eq.${user.id}&select=id") }.getOrNull()
                ?.forEach { E2E.delete(keys, "household", "id=eq.${it.getString("id")}") }
            E2E.deleteUser(keys, user.id)
        }
        joiner?.let { E2E.deleteUser(keys, it.id) }
    }

    @Test
    fun testCORE04_ownerRemovesAMemberAfterTheUndoWindow() {
        val keys = keys!!
        val owner = owner!!
        val joiner = joiner!!
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        val api = HttpApiService(config, auth)
        runBlocking { auth.currentSession(); auth.signOut() }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true })
        val welcome = WelcomeViewModel(api, onLanded = session::land)
        var signIn by mutableStateOf(SignInViewModel(auth, config.authCallbackUrl))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn, welcome, api, config.webBaseUrl) } }
            session.start()
        }

        // A creates the household and reads the invite code.
        signInThroughMagicLink(auth, owner)
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("welcome.name").performTextInput("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()
        awaitTag("tab.home.content")
        rule.onNodeWithTag("home.members").performClick()
        rule.onNodeWithTag("members.invite").performClick()
        awaitTag("invite.code")
        val code = rule.onNodeWithTag("invite.code", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        rule.onNodeWithTag("invite.close").performClick()
        rule.waitUntil(5_000) { !exists("invite.sheet") }

        // B joins and opens Members.
        rule.onNodeWithTag("home.signout").performClick()
        awaitTag("signin.title", 10_000)
        signIn = SignInViewModel(auth, config.authCallbackUrl)
        signInThroughMagicLink(auth, joiner)
        awaitTag("welcome.title")
        rule.onNodeWithTag("welcome.code").performTextInput(code)
        rule.onNodeWithTag("welcome.join").performClick()
        awaitTag("tab.home.content")
        rule.onNodeWithTag("home.members").performClick()
        awaitTag("members.row.${joiner.id}.you")
        awaitTag("members.row.${owner.id}.name")
        assertTrue(!exists("members.row.${owner.id}.menu"))
        assertTrue(!exists("members.row.${joiner.id}.menu"))
        rule.onNodeWithTag("members.leave").assertIsEnabled()

        // A returns: sees B, is blocked from leaving, removes B.
        rule.onNodeWithTag("home.signout").performClick()
        awaitTag("signin.title", 10_000)
        signIn = SignInViewModel(auth, config.authCallbackUrl)
        signInThroughMagicLink(auth, owner)
        awaitTag("tab.home.content")
        rule.onNodeWithTag("home.members").performClick()
        awaitTag("members.row.${joiner.id}.name")
        rule.onNodeWithTag("members.leave").performClick()
        awaitTag("members.alert")
        rule.onNodeWithTag("members.alert.ok").performClick()
        rule.waitUntil(5_000) { !exists("members.alert") }

        rule.onNodeWithTag("members.row.${joiner.id}.menu").performClick()
        rule.onNodeWithTag("members.row.${joiner.id}.remove").performClick()
        awaitTag("undo-toast")
        rule.onNodeWithTag("members.leave").assertIsNotEnabled()

        val household = E2E.rows(keys, "household", "created_by=eq.${owner.id}&select=id").single().getString("id")
        val query = "household_id=eq.$household&user_id=eq.${joiner.id}&select=left_at"
        val deadline = System.currentTimeMillis() + 20_000
        var leftAt: Boolean
        do {
            Thread.sleep(500)
            leftAt = !E2E.rows(keys, "member", query).single().isNull("left_at")
        } while (!leftAt && System.currentTimeMillis() < deadline)
        assertTrue("B's member row should be marked left after the undo window", leftAt)
        rule.waitUntil(5_000) { !exists("members.row.${joiner.id}") }
        scenario.close()
    }

    private fun signInThroughMagicLink(auth: SupabaseAuthService, user: E2E.TestUser) {
        rule.waitUntil(10_000) { exists("signin.email") }
        rule.onNodeWithTag("signin.email").performTextInput(user.email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }
        runBlocking { auth.handle(E2E.callbackFromMagicLink(user.email)) }
    }

    /** The parts of `:app`'s AppRoot this journey drives: Home with a Members link, Members, and Sign out. */
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
                            MembersScreen(
                                destination.id, checkNotNull(session.userId), api, isOnline = true, webBaseUrl = webBaseUrl,
                                cache = remember { InMemoryMembersCache() }, onLeft = session::leftHousehold,
                                modifier = Modifier.weight(1f),
                            )
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

    private fun awaitTag(tag: String, millis: Long = 15_000) {
        try {
            rule.waitUntil(millis) { exists(tag) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("No '$tag' after $millis ms. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
