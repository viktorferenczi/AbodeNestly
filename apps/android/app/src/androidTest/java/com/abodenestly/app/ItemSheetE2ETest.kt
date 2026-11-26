package com.abodenestly.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
 * E2E-SHOP-1, the item-edit part (TESTING §8, SHOP-03-android) against the real API: a household with an empty list. The
 * real `ShoppingTab` runs on a real `DatabaseRegistry` and `LiveSyncEngine`: pull, open the list, quick add "Milk", tap
 * the row, set quantity 2, unit "l" and note "lactose free", Save (the row shows "2 l" and the note), reopen, clear the
 * note, Save (the note is gone, "2 l" stays). After sync the server row has quantity 2, unit "l" and note null.
 * Port of iOS `ItemSheetUITests`.
 *
 * Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and the `anonKey` / `serviceRoleKey`
 * instrumentation args (apps/android/README.md); skipped without the keys.
 */
class ItemSheetE2ETest {
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
    fun testSHOP03_setQuantityUnitAndNoteThenClearTheNote() {
        val keys = keys!!
        val user = user!!
        insert(keys, "household", JSONObject().put("id", householdId.toString()).put("name", "Sheet Home").put("created_by", user.id))
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

        // The real engine on the real database, pulled once so the list and its history are local.
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

        // The overview shows the pulled list; open it.
        awaitTag("lists.row.Groceries", 20_000)
        rule.onNodeWithTag("lists.row.Groceries").performClick()
        awaitTag("quickadd.field", 10_000)

        // Quick add "Milk": a row with no quantity, unit or note.
        rule.onNodeWithTag("quickadd.field").performTextInput("Milk")
        rule.onNodeWithTag("quickadd.add").performClick()
        awaitTag("list.row.Milk", 10_000)

        // AC2: quantity 2, unit l and a note show on the row.
        rule.onNodeWithTag("list.row.Milk").performClick()
        awaitTag("item.sheet", 10_000)
        rule.onNodeWithTag("stepper.plus").performClick()
        rule.onNodeWithTag("stepper.plus").performClick()
        rule.onNodeWithTag("item.unit").performTextInput("l")
        rule.onNodeWithTag("item.note").performTextInput("lactose free")
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("item.sheet", 10_000)
        awaitTag("list.row.Milk.qty", 10_000, unmerged = true)
        rule.onNodeWithTag("list.row.Milk.qty", useUnmergedTree = true).assertTextEquals("2 l")
        rule.onNodeWithTag("list.row.Milk.note", useUnmergedTree = true).assertTextEquals("lactose free")

        // AC3: clearing the note removes it; the quantity stays.
        rule.onNodeWithTag("list.row.Milk").performClick()
        awaitTag("item.sheet", 10_000)
        rule.onNodeWithTag("item.note").performTextClearance()
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("item.sheet", 10_000)
        awaitGone("list.row.Milk.note", 10_000, unmerged = true)
        rule.onNodeWithTag("list.row.Milk.qty", useUnmergedTree = true).assertTextEquals("2 l")
        engine.kick()

        // The server row has the quantity and unit, and no note.
        val deadline = System.currentTimeMillis() + 20_000
        var row: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            row = E2E.rows(keys, "list_item", "list_id=eq.$listId&name=eq.Milk&deleted_at=is.null&select=quantity,unit,note").firstOrNull()
            if (row != null && row.optDouble("quantity") == 2.0 && row.optString("unit") == "l" && row.isNull("note")) break
            Thread.sleep(500)
        }
        scenario.close()
        assertEquals(2.0, row!!.getDouble("quantity"), 0.0)
        assertEquals("l", row.getString("unit"))
        assertTrue(row.isNull("note"))
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
