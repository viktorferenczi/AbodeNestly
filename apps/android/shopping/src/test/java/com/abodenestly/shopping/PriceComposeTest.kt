package com.abodenestly.shopping

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * SHOP-12 on DESIGN C2 (caption) and C3 (price field, Last bought), states `new` and `edit`, on a real Room file. The
 * sheet's body is mounted inline: Robolectric never drives the popup. Texts that depend on the phone's locale are
 * matched by their locale-free part.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class PriceComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private val ids = AtomicInteger()
    private lateinit var vm: ListDetailViewModel

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    /** A list "Groceries" (with the store "Test Market" unless [withStore] is false) holding Milk, and a pulled price of 1.20 if [priced]. */
    private fun show(withStore: Boolean = true, priced: Boolean = false, note: String? = null) {
        runBlocking {
            registry.open(household)
            if (withStore) createStore(registry, household, "Test Market", now, "s1")
            createList(registry, household, "Groceries", if (withStore) "s1" else null, now, "l1")
            registry.withSearchDao {
                it.upsertListItem(
                    ListItemEntity("i1", hid, "l1", "Milk", nameKey = "milk", checked = false, quantity = 2.0, unit = "l", note = note, createdAt = "2026-11-01T00:00:00.000Z"),
                )
            }
            if (priced) registry.pullPrices(household, pulledPrice("p1", "s1", "milk", "1.20", "2026-11-03T10:00:00.000Z"))
        }
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { }, isOnline = MutableStateFlow(true),
            delayer = { gate.await() }, now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        rule.setContent {
            AbodeTheme {
                ListDetailScreenHost(vm, onBack = {}) { isNew, initial, history, failed, onSave, onDelete, onClose ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ItemSheetContent(isNew, initial, history, failed, onSave, onDelete, onClose)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String, unmerged: Boolean = false) = rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String, unmerged: Boolean = false) = rule.waitUntil(15_000) { exists(tag, unmerged) }

    private fun awaitGone(tag: String, unmerged: Boolean = false) = rule.waitUntil(15_000) { !exists(tag, unmerged) }

    private fun priceOutbox() = runBlocking { registry.outboxForTesting().filter { it.tableName == "item_price" } }

    private fun openMilk() {
        await("list.row.Milk")
        rule.onNodeWithTag("list.row.Milk").performClick()
        await("item.sheet")
    }

    private fun typeNew(name: String) {
        await("quickadd.field")
        if (name.isNotEmpty()) rule.onNodeWithTag("quickadd.field").performTextInput(name)
        rule.onNodeWithTag("quickadd.details").performClick()
        await("item.sheet")
    }

    private fun hasLabel(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `AC3 edit with no store has no price field`() {
        show(withStore = false)
        openMilk()
        assertFalse(exists("itemsheet.price"))
        assertFalse(hasLabel("Price at Test Market"))
    }

    @Test
    fun `AC3 new with no store has no price field`() {
        show(withStore = false)
        typeNew("Milk")
        assertFalse(exists("itemsheet.price"))
    }

    @Test
    fun `state edit offers the price field labelled with the store, always empty on open`() {
        show(priced = true)
        openMilk()
        await("itemsheet.price")
        rule.waitUntil(15_000) { hasLabel("Price at Test Market") }
        assertEquals("", rule.onNodeWithTag("itemsheet.price").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun `state new offers the price field, and Last bought appears once a name is typed`() {
        show(priced = true)
        typeNew("")
        await("itemsheet.price")
        assertFalse(exists("itemsheet.lastBought"))
        rule.onNodeWithTag("item.name").performTextInput("Milk")
        await("itemsheet.lastBought")
        rule.onNodeWithTag("itemsheet.lastBought").assertTextContains("€1.20 at Test Market", substring = true)
    }

    @Test
    fun `AC2 the row shows the last price and the store name in one TalkBack node`() {
        show(priced = true)
        await("list.row.Milk.price", unmerged = true)
        rule.onNodeWithTag("list.row.Milk.price", useUnmergedTree = true).assertTextEquals("€1.20 · Test Market")
        rule.onNodeWithTag("list.row.Milk").assertContentDescriptionEquals("Milk, 2 l, €1.20 · Test Market")
    }

    @Test
    fun `AC2 the sheet line in edit reads Last bought with the date, the price and the store`() {
        show(priced = true)
        openMilk()
        await("itemsheet.lastBought")
        val line = rule.onNodeWithTag("itemsheet.lastBought")
        line.assertTextContains("Last bought: ", substring = true)
        line.assertTextContains("€1.20 at Test Market", substring = true)
    }

    @Test
    fun `a row with no price has no caption and no Last bought line`() {
        show()
        await("list.row.Milk")
        assertFalse(exists("list.row.Milk.price", unmerged = true))
        openMilk()
        assertFalse(exists("itemsheet.lastBought"))
    }

    @Test
    fun `entering 1,5 and saving updates the caption to the new price and queues one item_price`() {
        show(priced = true)
        openMilk()
        await("itemsheet.price")
        rule.onNodeWithTag("itemsheet.price").performTextInput("1,5")
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("item.sheet")
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag("list.row.Milk.price", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { rule.onNodeWithTag("list.row.Milk.price", useUnmergedTree = true).assertTextEquals("€1.50 · Test Market") }.isSuccess
        }
        rule.waitUntil(15_000) { priceOutbox().size == 1 }
        assertTrue(priceOutbox().single().data.contains("\"price\":1.5"))
    }

    @Test
    fun `state new saves the item with its price in one go`() {
        show()
        typeNew("Bread")
        await("itemsheet.price")
        rule.onNodeWithTag("itemsheet.price").performTextInput("2.4")
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("item.sheet")
        rule.waitUntil(15_000) { priceOutbox().size == 1 }
        await("list.row.Bread.price", unmerged = true)
        rule.onNodeWithTag("list.row.Bread.price", useUnmergedTree = true).assertTextEquals("€2.40 · Test Market")
        assertTrue(priceOutbox().single().data.contains("\"name_key\":\"bread\""))
    }

    @Test
    fun `an invalid price shows the message and turns Save off, and clearing it turns Save back on`() {
        show()
        openMilk()
        await("itemsheet.price")
        rule.onNodeWithTag("itemsheet.price").performTextInput("1.234")
        await("itemsheet.priceError")
        rule.onNodeWithTag("itemsheet.priceError").assertTextEquals("Enter a price like 1.20")
        rule.onNodeWithTag("item.save").assertIsNotEnabled()
        rule.onNodeWithTag("itemsheet.price").performTextClearance()
        awaitGone("itemsheet.priceError")
        rule.onNodeWithTag("item.save").assertIsEnabled()
        assertEquals(0, priceOutbox().size)
    }

    @Test
    fun `an empty price field writes no price`() {
        show(priced = true, note = "lactose free")
        openMilk()
        rule.onNodeWithTag("item.note").performTextClearance()
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("item.sheet")
        assertEquals(0, priceOutbox().size)
    }

    @Test
    fun `the price field comes after the note and Last bought after the price`() {
        show(priced = true, note = "lactose free")
        openMilk()
        await("itemsheet.lastBought")
        val note = rule.onNodeWithTag("item.note").fetchSemanticsNode().boundsInRoot
        val price = rule.onNodeWithTag("itemsheet.price").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("itemsheet.lastBought").fetchSemanticsNode().boundsInRoot
        val delete = rule.onNodeWithTag("item.delete").fetchSemanticsNode().boundsInRoot
        assertTrue("price below note", price.top >= note.bottom)
        assertTrue("Last bought below price", last.top >= price.bottom)
        assertTrue("Delete below Last bought", delete.top >= last.bottom)
    }
}
