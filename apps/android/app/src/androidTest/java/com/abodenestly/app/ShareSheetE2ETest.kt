package com.abodenestly.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.shopping.ShareSetup
import com.abodenestly.shopping.ShoppingTab
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * E2E-SHOP-4, the app's half (TESTING §8, SHOP-13-android) against the real API: a household with a list "Groceries". The real
 * `ShoppingTab` runs on a real `DatabaseRegistry` and `LiveSyncEngine`: open Groceries, List actions → Share, turn the switch on
 * and see the link `<web origin>/shared/<token>`; the server has the token on the list row. Stop sharing turns the switch off
 * and clears the server's token. The public page opens on web (E2E-SHOP-4 web).
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys. CI does not run androidTest.
 */
class ShareSheetE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private var user: E2E.TestUser? = null
    private val householdId = UUID.randomUUID()
    private val listId = UUID.randomUUID()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = DatabaseRegistry(context)

    @Before
    fun createUser() {
        val keys = E2E.keys()
        assumeTrue("Pass serviceRoleKey and anonKey instrumentation args to run the e2e journey", keys != null)
        this.keys = keys
        user = E2E.createUser(keys!!)
    }

    @After
    fun cleanUp() {
        scope.cancel()
        runBlocking { registry.close() }
        context.deleteDatabase(registry.fileName(householdId))
        val keys = keys ?: return
        // The household first: it references the user through `created_by`; members, lists and items cascade.
        E2E.delete(keys, "household", "id=eq.$householdId")
        user?.let { E2E.deleteUser(keys, it.id) }
    }

    private fun insert(keys: E2E.Keys, table: String, row: JSONObject) {
        val (status, body, _) = E2E.request("${E2E.SUPABASE_URL}/rest/v1/$table", "POST", row.toString(), keys)
        assertEquals("insert into $table: $body", 201, status)
    }

    private fun serverToken(keys: E2E.Keys): String? =
        E2E.rows(keys, "shopping_list", "id=eq.$listId&select=share_token").firstOrNull()?.takeIf { !it.isNull("share_token") }?.getString("share_token")

    @Test
    fun testSHOP13_shareAListFromTheListActionsMenu() {
        val keys = keys!!
        val user = user!!
        insert(keys, "household", JSONObject().put("id", householdId.toString()).put("name", "Share Home").put("created_by", user.id))
        insert(keys, "member", JSONObject().put("household_id", householdId.toString()).put("user_id", user.id).put("role", "owner"))
        insert(keys, "shopping_list", JSONObject().put("id", listId.toString()).put("household_id", householdId.toString()).put("name", "Groceries"))

        // Sign in through the magic link, without the sign-in screen: the API calls need the session.
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        val api = HttpApiService(config, auth)
        runBlocking {
            auth.currentSession()
            auth.signOut()
            auth.sendMagicLink(user.email, config.authCallbackUrl)
            auth.handle(E2E.callbackFromMagicLink(user.email))
        }

        // The real engine on the real database, pulled once so the list is local.
        val engine = LiveSyncEngine(api, registry, isOnline = { true }, scope = scope)
        runBlocking {
            registry.open(householdId)
            engine.run()
        }

        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent {
                AbodeTheme {
                    ShoppingTab(
                        householdId, registry, engine, MutableStateFlow(true), UUID.fromString(user.id),
                        share = ShareSetup.forApi(config.webBaseUrl, api),
                    )
                }
            }
        }

        awaitTag("lists.row.Groceries", 20_000)
        rule.onNodeWithTag("lists.row.Groceries").performClick()
        awaitTag("list.actions", 10_000)
        rule.onNodeWithTag("list.actions").performClick()
        awaitTag("list.actions.share", 10_000)
        rule.onNodeWithTag("list.actions.share").performClick()

        // AC1: sharing is off.
        awaitTag("share.switch", 10_000)
        rule.onNodeWithTag("share.switch").assertIsOff()

        // AC2: on shows the link on the web origin, and the server has the token.
        rule.onNodeWithTag("share.switch").performClick()
        awaitTag("share.link", 15_000)
        rule.onNodeWithTag("share.switch").assertIsOn()
        val token = serverToken(keys)
        assertNotNull("the server has the share token", token)
        rule.onNodeWithTag("share.link").assertTextEquals("${config.webBaseUrl}/shared/$token")

        // AC3: Stop sharing turns the switch off and the server forgets the token.
        rule.onNodeWithTag("share.stop").performClick()
        awaitGone("share.link", 15_000)
        rule.onNodeWithTag("share.switch").assertIsOff()
        scenario.close()
        assertTrue("the server cleared the token", serverToken(keys) == null)
    }

    private fun awaitGone(tag: String, millis: Long = 15_000, unmerged: Boolean = false) {
        try {
            rule.waitUntil(millis) { rule.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().isEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("'$tag' still there after $millis ms. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun awaitTag(tag: String, millis: Long = 15_000, unmerged: Boolean = false) {
        try {
            rule.waitUntil(millis) { rule.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("No '$tag' after $millis ms. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }
}
