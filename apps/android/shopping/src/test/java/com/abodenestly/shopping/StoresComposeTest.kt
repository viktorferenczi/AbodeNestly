package com.abodenestly.shopping

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.StoreEntity
import com.abodenestly.sync.StoreItemOrderEntity
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * DESIGN C4 (empty, populated, offline) and the C2 store chip and picker for SHOP-05 (TESTING §7), on a real Room file.
 * The undo window is a gate the test never opens. The sheet and the picker bodies are mounted inline: Robolectric never
 * drives the popup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class StoresComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private val online = MutableStateFlow(true)
    private val backs = AtomicInteger()
    private val manages = AtomicInteger()
    private val opens = AtomicInteger()
    private val ids = AtomicInteger()

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun seedStore(id: String, name: String) = runBlocking {
        registry.withSearchDao { it.upsertStore(StoreEntity(id, hid, name)) }
    }

    private fun seedOrder(storeId: String, count: Int) = runBlocking {
        registry.withSearchDao { dao -> repeat(count) { i -> dao.upsertStoreItemOrder(StoreItemOrderEntity(storeId, "item$i", hid, 1.0, 1)) } }
    }

    private fun seedList(storeId: String? = null) = runBlocking { createList(registry, household, "Groceries", storeId, now, "l1") }

    private fun storeOutbox() = runBlocking { registry.outboxForTesting().filter { it.tableName == "store" } }

    private fun listOutbox() = runBlocking { registry.outboxForTesting().filter { it.tableName == "shopping_list" } }

    private fun exists(tag: String, unmerged: Boolean = false) = rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(5_000) { !exists(tag) }

    private fun awaitText(tag: String, text: String) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tag).and(hasText(text))).fetchSemanticsNodes().isNotEmpty() }

    // --- C4 Stores ---

    private fun showStores(isOnline: Boolean = true, seed: () -> Unit = {}) {
        runBlocking { registry.open(household) }
        seed()
        online.value = isOnline
        val vm = StoresViewModel(
            household, registry, kick = { }, isOnline = online,
            delayer = { gate.await() }, now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        rule.setContent {
            AbodeTheme {
                StoresScreenHost(vm, onBack = { backs.incrementAndGet() }) { stores, onSave, onClose ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { StoreSheetContent(stores, onSave, onClose) }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `AC1 no stores shows the empty state, and its button opens the sheet`() {
        showStores()
        await("stores.empty")
        rule.onNodeWithText("No stores yet").assertIsDisplayed()
        rule.onNodeWithText("Name the shops you go to, then pick one on a list.").assertIsDisplayed()
        rule.onNodeWithTag("stores.title").assertTextEquals("Stores")
        rule.onNodeWithTag("stores.new").assertContentDescriptionEquals("New store")
        assertFalse(exists("stores.list"))
        rule.onNodeWithTag("stores.empty.new").performClick()
        await("storesheet")
    }

    @Test
    fun `AC2 adding Test Market closes the sheet and the row shows the name`() {
        showStores()
        await("stores.empty")
        rule.onNodeWithTag("stores.new").performClick()
        await("storesheet")
        rule.onNodeWithTag("storesheet.name").performTextInput("  Test Market ")
        rule.onNodeWithTag("storesheet.save").performClick()

        await("stores.row.Test Market")
        awaitGone("storesheet")
        assertFalse(exists("stores.empty"))
        assertEquals(1, storeOutbox().size)
    }

    @Test
    fun `AC2 populated shows the rows by name and learned N items only when N is above 0`() {
        showStores(seed = {
            seedStore("s1", "Test Market")
            seedStore("s2", "Corner Shop")
            seedOrder("s2", 3)
        })
        await("stores.row.Corner Shop")
        rule.onNodeWithTag("stores.row.Test Market").assertIsDisplayed()
        rule.onNodeWithTag("stores.row.Corner Shop").assertTextContains("learned 3 items")
        rule.onNodeWithTag("stores.row.Test Market.delete").assertContentDescriptionEquals("Delete Test Market")
        assertFalse(exists("stores.row.Test Market.learned", unmerged = true))
        assertFalse(exists("stores.empty"))
    }

    @Test
    fun `the sheet blocks an empty name and a duplicate inline, and Save stays off`() {
        showStores(seed = { seedStore("s1", "Test Market") })
        await("stores.row.Test Market")
        rule.onNodeWithTag("stores.new").performClick()
        await("storesheet")
        // Nothing typed yet: no nagging, but nothing to save either.
        assertFalse(exists("storesheet.problem"))
        rule.onNodeWithTag("storesheet.save").assertIsNotEnabled()

        rule.onNodeWithTag("storesheet.name").performTextInput("   ")
        await("storesheet.problem")
        rule.onNodeWithTag("storesheet.problem").assertTextEquals("Give the store a name")
        rule.onNodeWithTag("storesheet.save").assertIsNotEnabled()

        rule.onNodeWithTag("storesheet.name").performTextInput("x")
        rule.onNodeWithTag("storesheet.save").assertIsEnabled()

        // "   x" is no duplicate; a case-insensitive twin of Test Market is.
        rule.onNodeWithTag("storesheet.cancel").performClick()
        awaitGone("storesheet")
        rule.onNodeWithTag("stores.new").performClick()
        await("storesheet")
        rule.onNodeWithTag("storesheet.name").performTextInput("test market")
        await("storesheet.problem")
        rule.onNodeWithTag("storesheet.problem").assertTextEquals("You already have Test Market")
        rule.onNodeWithTag("storesheet.save").assertIsNotEnabled()
        assertEquals(0, storeOutbox().size)
    }

    @Test
    fun `Cancel closes the sheet and writes nothing`() {
        showStores()
        await("stores.empty")
        rule.onNodeWithTag("stores.new").performClick()
        await("storesheet")
        rule.onNodeWithTag("storesheet.name").performTextInput("Aldi")
        rule.onNodeWithTag("storesheet.cancel").performClick()
        awaitGone("storesheet")
        assertEquals(0, storeOutbox().size)
        assertFalse(exists("stores.row.Aldi"))
    }

    @Test
    fun `Delete shows the Deleted toast and hides the row, Undo brings it back with nothing written`() {
        showStores(seed = { seedStore("s1", "Test Market") })
        await("stores.row.Test Market")
        rule.onNodeWithTag("stores.row.Test Market.delete").performClick()

        await("undo-toast")
        rule.onNodeWithText("Deleted Test Market").assertIsDisplayed()
        awaitGone("stores.row.Test Market")
        assertEquals(0, storeOutbox().size)

        rule.onNodeWithTag("undo-toast.undo").performClick()
        awaitGone("undo-toast")
        await("stores.row.Test Market")
        assertEquals(0, storeOutbox().size)
    }

    @Test
    fun `the TalkBack Delete action does the same as the button`() {
        showStores(seed = { seedStore("s1", "Test Market") })
        await("stores.row.Test Market")
        val action = rule.onNodeWithTag("stores.row.Test Market").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Delete" }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
        await("undo-toast")
        rule.onNodeWithText("Deleted Test Market").assertIsDisplayed()
    }

    @Test
    fun `offline shows the banner and Back goes back`() {
        showStores(isOnline = false)
        await("stores.empty")
        rule.onNodeWithText("You're offline. Changes will sync when you reconnect.").assertIsDisplayed()
        rule.onNodeWithTag("stores.back").performClick()
        assertEquals(1, backs.get())
    }

    // --- C2 chip and picker ---

    private fun showDetail(seed: () -> Unit) {
        runBlocking { registry.open(household) }
        seed()
        val vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { }, isOnline = online,
            now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        rule.setContent {
            AbodeTheme {
                ListDetailScreenHost(
                    vm, onBack = {}, onManageStores = { manages.incrementAndGet() },
                    picker = { stores, current, failed, onCreate, onSave, onManage, onClose ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            StorePickerContent(stores, current, failed, onCreate, onSave, onManage, onClose)
                        }
                    },
                ) { _, _, _, _, _, _, _ -> }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `AC3 the chip reads Add store, the picker picks Test Market, Save makes the chip read Test Market`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedList() })
        await("list.store")
        awaitText("list.store", "Add store")
        rule.onNodeWithTag("list.store").assertContentDescriptionEquals("Store: none")
        val before = listOutbox().size

        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        rule.onNodeWithTag("storepicker.none").assertIsSelected()
        rule.onNodeWithTag("storepicker.row.Test Market").assertIsNotSelected()
        rule.onNodeWithTag("storepicker.row.Test Market").performClick()
        rule.onNodeWithTag("storepicker.row.Test Market").assertIsSelected()
        rule.onNodeWithTag("storepicker.save").performClick()

        awaitGone("storepicker.sheet")
        awaitText("list.store", "Test Market")
        rule.onNodeWithTag("list.store").assertContentDescriptionEquals("Store: Test Market")
        assertEquals(before + 1, listOutbox().size)
    }

    @Test
    fun `picking No store takes the store off the list, and the chip falls back to Add store`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedList(storeId = "s1") })
        awaitText("list.store", "Test Market")
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        rule.onNodeWithTag("storepicker.row.Test Market").assertIsSelected()
        rule.onNodeWithTag("storepicker.none").performClick()
        rule.onNodeWithTag("storepicker.save").performClick()

        awaitGone("storepicker.sheet")
        awaitText("list.store", "Add store")
        assertEquals(2, listOutbox().size)
    }

    @Test
    fun `Save without a change closes the picker and writes nothing`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedList(storeId = "s1") })
        awaitText("list.store", "Test Market")
        val before = listOutbox().size
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        rule.onNodeWithTag("storepicker.save").performClick()
        awaitGone("storepicker.sheet")
        assertEquals(before, listOutbox().size)
    }

    @Test
    fun `Cancel closes the picker and writes nothing`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedList() })
        awaitText("list.store", "Add store")
        val before = listOutbox().size
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        rule.onNodeWithTag("storepicker.row.Test Market").performClick()
        rule.onNodeWithTag("storepicker.cancel").performClick()
        awaitGone("storepicker.sheet")
        awaitText("list.store", "Add store")
        assertEquals(before, listOutbox().size)
    }

    @Test
    fun `the inline New store creates the store and selects it, and Save assigns it`() {
        showDetail(seed = { seedList() })
        awaitText("list.store", "Add store")
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        rule.onNodeWithTag("storepicker.new").performTextInput(" Corner Shop ")
        rule.onNodeWithTag("storepicker.add").performClick()

        await("storepicker.row.Corner Shop")
        rule.onNodeWithTag("storepicker.row.Corner Shop").assertIsSelected()
        rule.onNodeWithTag("storepicker.none").assertIsNotSelected()
        assertEquals(1, storeOutbox().size)

        rule.onNodeWithTag("storepicker.save").performClick()
        awaitGone("storepicker.sheet")
        awaitText("list.store", "Corner Shop")
    }

    @Test
    fun `the inline New store blocks a duplicate and an empty name`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedList() })
        awaitText("list.store", "Add store")
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        assertFalse(exists("storepicker.problem"))
        rule.onNodeWithTag("storepicker.add").assertIsNotEnabled()

        rule.onNodeWithTag("storepicker.new").performTextInput("TEST market")
        await("storepicker.problem")
        rule.onNodeWithTag("storepicker.problem").assertTextEquals("You already have Test Market")
        rule.onNodeWithTag("storepicker.add").assertIsNotEnabled()
        assertEquals(0, storeOutbox().size)
    }

    @Test
    fun `Manage stores closes the picker and opens Stores`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedList() })
        awaitText("list.store", "Add store")
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.sheet")
        rule.onNodeWithTag("storepicker.manage").performClick()
        awaitGone("storepicker.sheet")
        assertEquals(1, manages.get())
    }

    @Test
    fun `a store deleted elsewhere while the picker is open drops out, and the selection falls back to the list's store`() {
        showDetail(seed = { seedStore("s1", "Test Market"); seedStore("s2", "Corner Shop"); seedList() })
        awaitText("list.store", "Add store")
        rule.onNodeWithTag("list.store").performClick()
        await("storepicker.row.Corner Shop")
        rule.onNodeWithTag("storepicker.row.Corner Shop").performClick()
        rule.onNodeWithTag("storepicker.row.Corner Shop").assertIsSelected()

        runBlocking { registry.withSearchDao { it.upsertStore(StoreEntity("s2", hid, "Corner Shop", "2026-11-26T09:00:00.000Z")) } }

        awaitGone("storepicker.row.Corner Shop")
        rule.onNodeWithTag("storepicker.none").assertIsSelected()
        rule.onNodeWithTag("storepicker.row.Test Market").assertIsNotSelected()
    }

    // --- Lists ---

    @Test
    fun `Lists has a Stores button that opens Stores`() {
        runBlocking { registry.open(household) }
        val vm = ListsViewModel(
            household, registry, kick = { }, isOnline = online,
            delayer = { gate.await() }, now = { now }, newId = { "created" }, scope = scope,
        )
        rule.setContent {
            AbodeTheme {
                ListsScreenHost(vm, onOpen = {}, onOpenStores = { opens.incrementAndGet() }) { _, _, _, _ -> }
            }
        }
        await("lists.stores")
        rule.onNodeWithTag("lists.stores").assertContentDescriptionEquals("Stores")
        rule.onNodeWithTag("lists.stores").performClick()
        assertEquals(1, opens.get())
    }
}
