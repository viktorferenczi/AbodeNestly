package com.abodenestly.core

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.welcome.WelcomeScreen
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.core.welcome.land
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E-CORE-1, create half: sign in, create a household from Welcome, land on Home and check the server
 * seeded the defaults. Port of iOS `WelcomeUITests`.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys. Hosts the real [WelcomeScreen],
 * [SessionViewModel], [SupabaseAuthService] and [HttpApiService] in a plain activity, like [SignInE2ETest].
 */
class WelcomeE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private var user: E2E.TestUser? = null

    @Before
    fun createUser() {
        val keys = E2E.keys()
        assumeTrue("Pass serviceRoleKey and anonKey instrumentation args to run the e2e journey", keys != null)
        this.keys = keys
        user = E2E.createUser(keys!!)
    }

    @After
    fun cleanUp() {
        val keys = keys ?: return
        val user = user ?: return
        // The household first: it references the user through `created_by`.
        runCatching { E2E.rows(keys, "household", "created_by=eq.${user.id}&select=id") }.getOrNull()
            ?.forEach { E2E.delete(keys, "household", "id=eq.${it.getString("id")}") }
        E2E.deleteUser(keys, user.id)
    }

    @Test
    fun createHouseholdLandsOnHomeWithDefaultsSeeded() {
        val keys = keys!!
        val user = user!!
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        // Await initialization first: signing out before the stored session loads leaves the last journey's session in place.
        runBlocking { auth.currentSession(); auth.signOut() }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true })
        val signIn = SignInViewModel(auth, config.authCallbackUrl)
        val welcome = WelcomeViewModel(HttpApiService(config, auth), onLanded = session::land)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn, welcome) } }
            session.start()
        }

        rule.waitUntil(10_000) { exists("signin.title") }
        rule.onNodeWithTag("signin.email").performTextInput(user.email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }
        runBlocking { auth.handle(E2E.callbackFromMagicLink(user.email)) }

        // Welcome greets the person by display name (the profile trigger uses the email local part).
        rule.waitUntil(15_000) { exists("welcome.title") }
        rule.onNodeWithTag("welcome.title").assertTextEquals("Welcome, ${user.localPart}")

        rule.onNodeWithTag("welcome.name").performTextInput("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()

        // B4: the new household opens on its Home tab.
        rule.waitUntil(15_000) { exists("tab.home.content") }

        // The server seeded one list and the three default locations.
        val households = E2E.rows(keys, "household", "created_by=eq.${user.id}&select=id,name")
        assertEquals(1, households.size)
        assertEquals("Test Home", households.single().getString("name"))
        val householdId = households.single().getString("id")
        val lists = E2E.rows(keys, "shopping_list", "household_id=eq.$householdId&select=name")
        assertEquals(listOf("Groceries"), lists.map { it.getString("name") })
        val locations = E2E.rows(keys, "location", "household_id=eq.$householdId&select=name")
        assertEquals(setOf("Fridge", "Freezer", "Pantry"), locations.map { it.getString("name") }.toSet())
        scenario.close()
    }

    /** What `:app`'s AppRoot shows, reduced to the parts this journey asserts on. */
    @Composable
    private fun Host(session: SessionViewModel, signIn: SignInViewModel, welcome: WelcomeViewModel) {
        val state by session.state.collectAsState()
        when (state.status) {
            SessionStatus.Loading -> Unit
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = true)
            SessionStatus.SignedIn -> when (val destination = state.destination) {
                Destination.Welcome ->
                    WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = true, onSignOut = session::signOut)
                is Destination.Household ->
                    if (destination.tab == AppTab.Home) Text("Home", modifier = Modifier.testTag("tab.home.content"))
                else -> Unit
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
