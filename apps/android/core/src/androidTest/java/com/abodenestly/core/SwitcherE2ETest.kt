package com.abodenestly.core

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.Landing
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.switcher.InMemoryHouseholdsCache
import com.abodenestly.core.switcher.SwitcherSheet
import com.abodenestly.core.switcher.SwitcherViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.TopBar
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * E2E-CORE-2: a person in two households switches between them; the tab is kept, only the target household's
 * local rows are shown, and the switch works offline. Households and members are seeded with the service role
 * (the API is not involved); one local list per household is seeded through `DatabaseRegistry(seedLists)`.
 * Port of iOS `SwitcherUITests`.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys.
 */
class SwitcherE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private var user: E2E.TestUser? = null
    private val alphaId = UUID.randomUUID()
    private val betaId = UUID.randomUUID()

    /** The test flips this to take the device "offline" for the app's own online checks. */
    @Volatile private var online = true

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
        // The households first: they reference the user through `created_by`; members cascade.
        for (id in listOf(alphaId, betaId)) E2E.delete(keys, "household", "id=eq.$id")
        user?.let { E2E.deleteUser(keys, it.id) }
    }

    private fun insert(keys: E2E.Keys, table: String, row: JSONObject) {
        val (status, body, _) = E2E.request("${E2E.SUPABASE_URL}/rest/v1/$table", "POST", row.toString(), keys)
        assertEquals("insert into $table: $body", 201, status)
    }

    /** Alpha (Pantry on) is joined first, so it is where the person lands; Beta has Pantry off. */
    private fun seedHouseholds(keys: E2E.Keys, user: E2E.TestUser) {
        insert(keys, "household", JSONObject().put("id", alphaId.toString()).put("name", "Alpha").put("created_by", user.id))
        insert(keys, "member", JSONObject().put("household_id", alphaId.toString()).put("user_id", user.id).put("role", "owner").put("joined_at", "2026-01-01T00:00:00Z"))
        insert(keys, "household", JSONObject().put("id", betaId.toString()).put("name", "Beta").put("created_by", user.id).put("module_pantry", false))
        insert(keys, "member", JSONObject().put("household_id", betaId.toString()).put("user_id", user.id).put("role", "member").put("joined_at", "2026-02-01T00:00:00Z"))
    }

    @Test
    fun testCORE07_switchKeepsTab_showsOnlyTargetRows_andWorksOffline() {
        val keys = keys!!
        val user = user!!
        seedHouseholds(keys, user)
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        val api = HttpApiService(config, auth)
        runBlocking { auth.currentSession(); auth.signOut() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = DatabaseRegistry(context, seedLists = mapOf(alphaId to "Alpha list", betaId to "Beta list"))
        val householdsCache = InMemoryHouseholdsCache()
        val session = SessionViewModel(
            auth, InMemoryLastHouseholdStore(), isOnline = { online },
            database = database, householdsCache = householdsCache, api = api,
        )
        val signIn = SignInViewModel(auth, config.authCallbackUrl)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent { AbodeTheme { Host(session, signIn, api, database, householdsCache) } }
            session.start()
        }

        rule.waitUntil(10_000) { exists("signin.email") }
        rule.onNodeWithTag("signin.email").performTextInput(user.email)
        rule.onNodeWithTag("signin.send").performClick()
        rule.waitUntil(10_000) { exists("signin.linkSent") }
        runBlocking { auth.handle(E2E.callbackFromMagicLink(user.email)) }

        // Lands on Alpha's Shopping tab (all modules on, Pantry included).
        awaitTag("tab.shopping.content", 20_000)
        assertTrue(exists("tab.pantry"))
        awaitTitle("Alpha")
        awaitLists("Alpha list")

        // Switch to Beta: same tab, only Beta's rows, Pantry gone.
        switchTo(betaId)
        awaitTitle("Beta")
        assertEquals(AppTab.Shopping, session.state.value.selectedTab)
        assertFalse(exists("tab.pantry"))
        awaitLists("Beta list")

        // Offline: switching back needs no network.
        online = false
        switchTo(alphaId)
        awaitTitle("Alpha")
        assertEquals(AppTab.Shopping, session.state.value.selectedTab)
        awaitLists("Alpha list")
        scenario.close()
    }

    /** The parts of `:app`'s AppRoot this journey drives: the TopBar switcher entry, tabs and the local lists. */
    @Composable
    private fun Host(
        session: SessionViewModel, signIn: SignInViewModel, api: HttpApiService,
        database: DatabaseRegistry, householdsCache: InMemoryHouseholdsCache,
    ) {
        val state by session.state.collectAsState()
        when (state.status) {
            SessionStatus.Loading -> Unit
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = true)
            SessionStatus.SignedIn -> {
                if (state.destination == null) return
                var showSwitcher by remember { mutableStateOf(false) }
                val tabs = Landing.visibleTabs(state.household)
                val selected = if (state.selectedTab in tabs) state.selectedTab else AppTab.Home
                Column(Modifier.fillMaxSize()) {
                    TopBar(state.householdName ?: "AbodeNestly", onTitleClick = { showSwitcher = true })
                    Box(Modifier.weight(1f).testTag("tab.${selected.key}.content")) {
                        if (selected == AppTab.Shopping) LocalLists(database)
                    }
                    Row {
                        tabs.forEach { tab ->
                            TextButton(onClick = { session.selectTab(tab) }, modifier = Modifier.testTag("tab.${tab.key}")) { Text(tab.key) }
                        }
                    }
                }
                if (showSwitcher) {
                    val viewModel = remember {
                        SwitcherViewModel(
                            checkNotNull(session.userId), state.household?.id, null, api, householdsCache, database,
                            isOnline = { online }, perform = { id, settings -> session.switchTo(id, settings) },
                        )
                    }
                    SwitcherSheet(viewModel, onDismiss = { showSwitcher = false }, onCreateOrJoin = { showSwitcher = false })
                }
            }
        }
    }

    @Composable
    private fun LocalLists(database: DatabaseRegistry) {
        val current by database.current.collectAsState()
        var names by remember { mutableStateOf("") }
        LaunchedEffect(current) {
            names = if (current == null) "" else runCatching { database.lists().joinToString(", ") { it.name } }.getOrDefault("")
        }
        Text(names, modifier = Modifier.testTag("shopping.local-lists"))
    }

    private fun switchTo(id: UUID) {
        rule.onNodeWithTag("topbar.title").performClick()
        awaitTag("switcher.row.$id", 10_000)
        rule.onNodeWithTag("switcher.row.$id").performClick()
        rule.waitUntil(15_000) { !exists("switcher.create") }
    }

    private fun awaitTitle(name: String) {
        // The title's accessibility state is the household name; its text carries the drop-down arrow.
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasTestTag("topbar.title")).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.StateDescription) == name
        }
        rule.onNodeWithTag("topbar.title").assertContentDescriptionEquals("Households")
    }

    private fun awaitLists(expected: String) {
        awaitTag("shopping.local-lists")
        try {
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("shopping.local-lists")).fetchSemanticsNodes().firstOrNull()
                    ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == expected
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Local lists should be '$expected'. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
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
