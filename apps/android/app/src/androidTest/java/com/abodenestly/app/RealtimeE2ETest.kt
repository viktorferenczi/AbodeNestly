package com.abodenestly.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertTextContains
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
import com.abodenestly.shopping.ShoppingTab
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.RealtimeTables
import com.abodenestly.sync.runHouseholdRealtime
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * E2E-SHOP-2, the realtime part (TESTING §8, SHOP-09-android) against the real stack: a household with a list and Milk.
 * The real `ShoppingTab` runs on a real `DatabaseRegistry` and `LiveSyncEngine`, with the real Realtime subscription of
 * `SupabaseAuthService` kicking the engine. Another member checks Milk off through PostgREST (the service role stands in
 * for them); with no action in the app, Milk appears under Checked within 10 seconds.
 *
 * Needs `supabase start` (the Realtime service included), `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` /
 * `serviceRoleKey` instrumentation args (apps/android/README.md); skipped without the keys. Like the other journeys it
 * runs locally, not in CI.
 */
class RealtimeE2ETest {
    @get:Rule val rule = createEmptyComposeRule()

    private var keys: E2E.Keys? = null
    private var user: E2E.TestUser? = null
    private val householdId = UUID.randomUUID()
    private val listId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
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
    fun testSHOP09_anotherMembersCheckOffAppearsWithoutAnyAction() {
        val keys = keys!!
        val user = user!!
        insert(keys, "household", JSONObject().put("id", householdId.toString()).put("name", "Realtime Home").put("created_by", user.id))
        insert(keys, "member", JSONObject().put("household_id", householdId.toString()).put("user_id", user.id).put("role", "owner"))
        insert(keys, "shopping_list", JSONObject().put("id", listId.toString()).put("household_id", householdId.toString()).put("name", "Groceries"))
        insert(
            keys, "list_item",
            JSONObject().put("id", itemId.toString()).put("household_id", householdId.toString()).put("list_id", listId.toString()).put("name", "Milk"),
        )

        // Sign in through the magic link, without the sign-in screen: the sync transport and Realtime need the session.
        val config = keys.config()
        val auth = SupabaseAuthService(config)
        val api = HttpApiService(config, auth)
        runBlocking {
            auth.currentSession()
            auth.signOut()
            auth.sendMagicLink(user.email, config.authCallbackUrl)
            auth.handle(E2E.callbackFromMagicLink(user.email))
        }

        val engine = LiveSyncEngine(api, registry, isOnline = { true }, scope = scope)
        runBlocking {
            registry.open(householdId)
            engine.run()
        }
        // The same subscription the app keeps (`HouseholdRealtimeEffect`): every published table, a change kicks the engine.
        val realtime = auth.realtimeService()
        scope.launch {
            runHouseholdRealtime(realtime, householdId.toString(), RealtimeTables.tables(shopping = true, pantry = true, maintenance = true)) {
                engine.kick()
            }
        }

        val scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent {
                AbodeTheme {
                    ShoppingTab(householdId, registry, engine, MutableStateFlow(true), UUID.fromString(user.id))
                }
            }
        }

        // The list shows Milk, unchecked.
        awaitTag("lists.row.Groceries", 20_000)
        rule.onNodeWithTag("lists.row.Groceries").performClick()
        awaitTag("list.row.Milk", 10_000)
        // Give the channel time to join; the catch-up pull on joining also covers an event sent before that.
        Thread.sleep(3_000)

        // Another member checks Milk off. Nothing is done in the app from here on.
        val (status, body, _) = E2E.request(
            "${E2E.SUPABASE_URL}/rest/v1/list_item?id=eq.$itemId", "PATCH",
            JSONObject().put("checked", true).put("checked_at", "2026-11-27T09:00:00Z").put("checked_by", user.id).toString(), keys,
        )
        assertEquals("patch list_item: $body", 204, status)

        awaitTag("list.checked", 10_000)
        rule.onNodeWithTag("list.checked").assertTextContains("Checked (1)")
        awaitGone("list.row.Milk", 10_000)
        scenario.close()
    }

    private fun awaitGone(tag: String, millis: Long = 15_000) {
        try {
            rule.waitUntil(millis) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("'$tag' still there after $millis ms. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun awaitTag(tag: String, millis: Long = 15_000) {
        try {
            rule.waitUntil(millis) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("No '$tag' after $millis ms. UI:\n" + rule.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }
}
