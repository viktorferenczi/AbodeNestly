package com.abodenestly.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.SupabaseAuthService
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * E2E-SHOP-3, the create-and-assign part (TESTING §8, SHOP-05-android) against the real API: a household with a list
 * "Groceries". The real `ShoppingTab` runs on a real `DatabaseRegistry` and `LiveSyncEngine`: pull, open Stores from the
 * Lists screen (empty state), add Test Market (the row shows the name), go back, open Groceries, tap the store chip, pick
 * Test Market and Save (the chip reads Test Market). After sync the server has the store and `shopping_list.store_id` is
 * its id. SHOP-06-android adds the re-sort. Port of iOS `StoresUITests`.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys.
 */
class StoresE2ETest {
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

    @Test
    fun testSHOP05_createStoreAndAssignItToAList() {
        val keys = keys!!
        val user = user!!
        insert(keys, "household", JSONObject().put("id", householdId.toString()).put("name", "Store Home").put("created_by", user.id))
        insert(keys, "member", JSONObject().put("household_id", householdId.toString()).put("user_id", user.id).put("role", "owner"))
        insert(keys, "shopping_list", JSONObject().put("id", listId.toString()).put("household_id", householdId.toString()).put("name", "Groceries"))

        // Sign in through the magic link, without the sign-in screen: the sync transport needs the session.
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
                    ShoppingTab(householdId, registry, engine, MutableStateFlow(true), UUID.fromString(user.id))
                }
            }
        }

        // AC1: Stores from the Lists screen has no stores yet.
        awaitTag("lists.row.Groceries", 20_000)
        rule.onNodeWithTag("lists.stores").performClick()
        awaitTag("stores.empty", 10_000)

        // AC2: add Test Market; the row shows the name.
        rule.onNodeWithTag("stores.empty.new").performClick()
        awaitTag("storesheet.name", 10_000)
        rule.onNodeWithTag("storesheet.name").performTextInput("Test Market")
        rule.onNodeWithTag("storesheet.save").performClick()
        awaitTag("stores.row.Test Market", 10_000)
        awaitGone("storesheet.name", 10_000)

        // Back to Lists, open Groceries: the chip asks for a store.
        rule.onNodeWithTag("stores.back").performClick()
        awaitTag("lists.row.Groceries", 10_000)
        rule.onNodeWithTag("lists.row.Groceries").performClick()
        awaitTag("list.store", 10_000)
        rule.onNodeWithTag("list.store").assertContentDescriptionEquals("Store: none")

        // AC3: pick Test Market and Save; the chip reads Test Market.
        rule.onNodeWithTag("list.store").performClick()
        awaitTag("storepicker.row.Test Market", 10_000)
        rule.onNodeWithTag("storepicker.row.Test Market").performClick()
        rule.onNodeWithTag("storepicker.save").performClick()
        awaitGone("storepicker.save", 10_000)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("list.store").and(hasText("Test Market"))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("list.store").assertContentDescriptionEquals("Store: Test Market")
        engine.kick()

        // The server has the store, and the list points at it.
        val deadline = System.currentTimeMillis() + 20_000
        var storeId: String? = null
        var listStore: String? = null
        while (System.currentTimeMillis() < deadline) {
            storeId = E2E.rows(keys, "store", "household_id=eq.$householdId&name=eq.Test%20Market&select=id").firstOrNull()?.getString("id")
            listStore = E2E.rows(keys, "shopping_list", "id=eq.$listId&select=store_id").firstOrNull()?.optString("store_id")
            if (storeId != null && listStore == storeId) break
            Thread.sleep(500)
        }
        scenario.close()
        assertTrue("the store reached the server", storeId != null)
        assertEquals(storeId, listStore)
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
