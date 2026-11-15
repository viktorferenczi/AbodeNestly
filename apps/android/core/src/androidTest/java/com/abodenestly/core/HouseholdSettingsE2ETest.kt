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
import com.abodenestly.core.auth.Landing
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.settings.HouseholdSettingsScreen
import com.abodenestly.core.settings.InMemoryHouseholdCache
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * E2E-CORE-3: an owner creates a household, opens Settings and switches Pantry off. The server row has
 * `module_pantry = false` and the tab rule no longer yields Pantry, without a restart. Port of iOS
 * `HouseholdSettingsUITests`. "No pantry badges" is n/a until the list stories land.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys.
 */
class HouseholdSettingsE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private var owner: E2E.TestUser? = null

    @Before
    fun createUser() {
        val keys = E2E.keys()
        assumeTrue("Pass serviceRoleKey and anonKey instrumentation args to run the e2e journey", keys != null)
        this.keys = keys
        owner = E2E.createUser(keys!!)
    }

    @After
    fun cleanUp() {
        val keys = keys ?: return
        owner?.let { user ->
            runCatching { E2E.rows(keys, "household", "created_by=eq.${user.id}&select=id") }.getOrNull()
                ?.forEach { E2E.delete(keys, "household", "id=eq.${it.getString("id")}") }
            E2E.deleteUser(keys, user.id)
        }
    }

    @Test
    fun testCORE05_ownerSwitchesPantryOffAndTheTabGoes() {
        val keys = keys!!
        val owner = owner!!
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        val api = HttpApiService(config, auth)
        runBlocking { auth.currentSession(); auth.signOut() }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true })
        val welcome = WelcomeViewModel(api, onLanded = session::land)
        val signIn = SignInViewModel(auth, config.authCallbackUrl)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn, welcome, api) } }
            session.start()
        }

        rule.waitUntil(10_000) { exists("signin.email") }
        rule.onNodeWithTag("signin.email").performTextInput(owner.email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }
        runBlocking { auth.handle(E2E.callbackFromMagicLink(owner.email)) }
        awaitTag("welcome.title")
        rule.onNodeWithTag("welcome.name").performTextInput("Test Home")
        rule.onNodeWithTag("welcome.create").performClick()
        awaitTag("tab.home.content")
        assertTrue(AppTab.Pantry in Landing.visibleTabs(session.state.value.household))

        rule.onNodeWithTag("home.settings").performClick()
        awaitTag("settings.module.pantry")
        rule.onNodeWithTag("settings.module.pantry").performClick()
        rule.waitUntil(15_000) { session.state.value.household?.modulePantry == false }

        assertFalse(AppTab.Pantry in Landing.visibleTabs(session.state.value.household))
        assertEquals(listOf(AppTab.Shopping, AppTab.Maintenance, AppTab.Home), Landing.visibleTabs(session.state.value.household))
        val row = E2E.rows(keys, "household", "created_by=eq.${owner.id}&select=module_pantry").single()
        assertFalse("module_pantry should be false on the server", row.getBoolean("module_pantry"))
        scenario.close()
    }

    /** The parts of `:app`'s AppRoot this journey drives: Home with a Settings link, and Settings. */
    @Composable
    private fun Host(session: SessionViewModel, signIn: SignInViewModel, welcome: WelcomeViewModel, api: HttpApiService) {
        val state by session.state.collectAsState()
        when (state.status) {
            SessionStatus.Loading -> Unit
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = true)
            SessionStatus.SignedIn -> when (val destination = state.destination) {
                Destination.Welcome ->
                    WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = true, onSignOut = session::signOut)
                is Destination.Household -> {
                    var settings by rememberSaveable { mutableStateOf(false) }
                    Column {
                        if (settings) {
                            HouseholdSettingsScreen(
                                destination.id, checkNotNull(session.userId), api, remember { InMemoryHouseholdCache() }, isOnline = true,
                                onHouseholdChanged = session::updateHousehold, onLeft = session::leftHousehold,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Text("Home", modifier = Modifier.testTag("tab.home.content"))
                            TextButton(onClick = { settings = true }, modifier = Modifier.testTag("home.settings")) { Text("Settings") }
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
