package com.abodenestly.shopping

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.QuickAddBar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * One case per State and Interaction of DESIGN C3 (TESTING §7), on a real Room file. The sheet's body is mounted inline:
 * Robolectric never drives the popup. The undo window is a gate the test never opens, so nothing waits in real time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class ItemSheetComposeTest {
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

    private fun seedMilk(quantity: Double? = 2.0, unit: String? = "l", note: String? = "lactose free", checked: Boolean = false) = runBlocking {
        registry.withSearchDao {
            it.upsertListItem(
                ListItemEntity("i1", hid, "l1", "Milk", nameKey = "milk", checked = checked, quantity = quantity, unit = unit, note = note, createdAt = "2026-11-01T00:00:00.000Z"),
            )
        }
    }

    private fun show(seed: () -> Unit = {}) {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        seed()
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

    private fun await(tag: String, unmerged: Boolean = false) = rule.waitUntil(5_000) { exists(tag, unmerged) }

    private fun awaitGone(tag: String, unmerged: Boolean = false) = rule.waitUntil(5_000) { !exists(tag, unmerged) }

    private fun itemOutbox() = runBlocking { registry.outboxForTesting().filter { it.tableName == "list_item" } }

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

    @Test
    fun `state new shows the title, the typed name, Cancel and Save, and no Delete`() {
        show()
        typeNew("Mil")
        rule.onNodeWithTag("item.title").assertTextEquals("New item")
        rule.onNodeWithTag("item.name").assertTextContains("Mil")
        rule.onNodeWithTag("item.save").assertIsEnabled()
        rule.onNodeWithTag("item.cancel").assertIsDisplayed()
        assertFalse(exists("item.delete"))
    }

    @Test
    fun `AC1 Save on a new item adds it with no quantity, unit or note, closes the sheet and clears the bar`() {
        show()
        typeNew("Milk")
        rule.onNodeWithTag("item.save").performClick()
        await("list.row.Milk")
        awaitGone("item.sheet")
        rule.waitUntil(5_000) { vm.state.value.query == "" }
        rule.waitUntil(5_000) { itemOutbox().size == 1 }
        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(JsonNull, data["quantity"])
        assertEquals(JsonNull, data["unit"])
        assertEquals(JsonNull, data["note"])
        assertFalse(exists("list.row.Milk.qty", unmerged = true))
        assertFalse(exists("list.row.Milk.note", unmerged = true))
    }

    @Test
    fun `state edit shows the fields of the row, the row shows quantity, unit and note, and Delete is there`() {
        show(seed = { seedMilk() })
        await("list.row.Milk")
        rule.onNodeWithTag("list.row.Milk.qty", useUnmergedTree = true).assertTextEquals("2 l")
        rule.onNodeWithTag("list.row.Milk.note", useUnmergedTree = true).assertTextEquals("lactose free")
        rule.onNodeWithTag("list.row.Milk").assertContentDescriptionEquals("Milk, 2 l, lactose free")
        openMilk()
        rule.onNodeWithTag("item.title").assertTextEquals("Edit item")
        rule.onNodeWithTag("item.name").assertTextContains("Milk")
        rule.onNodeWithTag("stepper.field").assertTextContains("2")
        rule.onNodeWithTag("item.unit").assertTextContains("l")
        rule.onNodeWithTag("item.note").assertTextContains("lactose free")
        rule.onNodeWithTag("item.delete").assertIsDisplayed()
    }

    @Test
    fun `AC2 setting quantity, unit and note shows them on the row`() {
        show(seed = { seedMilk(null, null, null) })
        await("list.row.Milk")
        assertFalse(exists("list.row.Milk.qty", unmerged = true))
        openMilk()
        rule.onNodeWithTag("stepper.plus").performClick()
        rule.onNodeWithTag("stepper.plus").performClick()
        rule.onNodeWithTag("item.unit").performTextInput("l")
        rule.onNodeWithTag("item.note").performTextInput("lactose free")
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("item.sheet")
        await("list.row.Milk.qty", unmerged = true)
        rule.onNodeWithTag("list.row.Milk.qty", useUnmergedTree = true).assertTextEquals("2 l")
        rule.onNodeWithTag("list.row.Milk.note", useUnmergedTree = true).assertTextEquals("lactose free")
        rule.waitUntil(5_000) { itemOutbox().size == 1 }
        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(setOf("quantity", "unit", "note"), data.keys)
    }

    @Test
    fun `AC3 clearing the note removes it from the row, keeps 2 l and queues only note null`() {
        show(seed = { seedMilk() })
        openMilk()
        rule.onNodeWithTag("item.note").performTextClearance()
        rule.onNodeWithTag("item.save").performClick()
        awaitGone("list.row.Milk.note", unmerged = true)
        rule.onNodeWithTag("list.row.Milk.qty", useUnmergedTree = true).assertTextEquals("2 l")
        rule.waitUntil(5_000) { itemOutbox().size == 1 }
        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(JsonObject(mapOf("note" to JsonNull)), data)
    }

    @Test
    fun `plus and minus step the quantity`() {
        show()
        typeNew("Milk")
        rule.onNodeWithTag("stepper.plus").performClick()
        rule.onNodeWithTag("stepper.plus").performClick()
        rule.onNodeWithTag("stepper.field").assertTextContains("2")
        rule.onNodeWithTag("stepper.minus").performClick()
        rule.onNodeWithTag("stepper.field").assertTextContains("1")
        rule.onNodeWithTag("stepper.plus").assertContentDescriptionEquals("Increase quantity")
        rule.onNodeWithTag("stepper.minus").assertContentDescriptionEquals("Decrease quantity")
    }

    @Test
    fun `typing 0,5 is accepted and saved as a half`() {
        show()
        typeNew("Flour")
        rule.onNodeWithTag("stepper.field").performTextInput("0,5")
        assertFalse(exists("item.qtyError"))
        rule.onNodeWithTag("item.save").assertIsEnabled()
        rule.onNodeWithTag("item.save").performClick()
        rule.waitUntil(5_000) { itemOutbox().size == 1 }
        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(0.5, data["quantity"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `a unit chip fills the unit field`() {
        show()
        typeNew("Rice")
        rule.onNodeWithTag("item.unit.suggestion.kg").performClick()
        rule.onNodeWithTag("item.unit").assertTextContains("kg")
    }

    @Test
    fun `Save is off for a blank name and for an invalid quantity, with the message`() {
        show()
        typeNew("")
        rule.onNodeWithTag("item.save").assertIsNotEnabled()
        rule.onNodeWithTag("item.name").performTextInput("Milk")
        rule.onNodeWithTag("item.save").assertIsEnabled()
        rule.onNodeWithTag("stepper.field").performTextInput("abc")
        await("item.qtyError")
        rule.onNodeWithText("Enter a number above 0").assertIsDisplayed()
        rule.onNodeWithTag("item.save").assertIsNotEnabled()
        rule.onNodeWithTag("stepper.field").performTextClearance()
        rule.onNodeWithTag("stepper.field").performTextInput("1.2345")
        await("item.qtyError")
        rule.onNodeWithTag("item.save").assertIsNotEnabled()
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `Cancel closes the sheet and writes nothing`() {
        show(seed = { seedMilk() })
        openMilk()
        rule.onNodeWithTag("item.note").performTextClearance()
        rule.onNodeWithTag("item.cancel").performClick()
        awaitGone("item.sheet")
        rule.onNodeWithTag("list.row.Milk.note", useUnmergedTree = true).assertTextEquals("lactose free")
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `a checked row opens the sheet too`() {
        show(seed = { seedMilk(checked = true) })
        await("list.checked")
        rule.onNodeWithTag("list.checked").performClick()
        await("list.checked.row.Milk")
        rule.onNodeWithTag("list.checked.row.Milk.qty", useUnmergedTree = true).assertTextEquals("2 l")
        rule.onNodeWithTag("list.checked.row.Milk").performClick()
        await("item.sheet")
        rule.onNodeWithTag("item.title").assertTextEquals("Edit item")
    }

    @Test
    fun `Delete hides the row and shows the undo toast, and Undo brings the row back without a write`() {
        show(seed = { seedMilk() })
        openMilk()
        rule.onNodeWithTag("item.delete").performClick()
        awaitGone("list.row.Milk")
        awaitGone("item.sheet")
        await("undo-toast")
        rule.onNodeWithText("Deleted Milk").assertIsDisplayed()
        rule.onNodeWithTag("undo-toast.undo").performClick()
        await("list.row.Milk")
        awaitGone("undo-toast")
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `a save that fails shows the message and keeps the sheet open`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { }, isOnline = MutableStateFlow(true),
            delayer = { gate.await() }, now = { now }, newId = { "n" }, scope = scope,
        )
        rule.setContent { AbodeTheme { ListDetailScreenHost(vm, onBack = {}) { isNew, initial, history, failed, onSave, onDelete, onClose ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                ItemSheetContent(isNew, initial, history, failed, onSave, onDelete, onClose)
            }
        } } }
        typeNew("Milk")
        // The database closes under the open sheet (a switch away), so the write throws.
        runBlocking { registry.close() }
        rule.onNodeWithTag("item.save").performClick()
        await("item.error")
        rule.onNodeWithText(SAVE_ERROR).assertIsDisplayed()
        assertTrue(exists("item.sheet"))
    }

    @Test
    fun `the Details button is only there when the caller passes onDetails and sends the typed text`() {
        var sent: String? = null
        val withDetails = mutableStateOf(false)
        rule.setContent {
            AbodeTheme {
                QuickAddBar(
                    text = "Soap", onTextChange = {}, suggestions = emptyList(), onPick = {}, onSubmit = {},
                    onDetails = if (withDetails.value) ({ sent = it }) else null,
                )
            }
        }
        assertFalse(exists("quickadd.details"))
        rule.runOnIdle { withDetails.value = true }
        rule.waitForIdle()
        rule.onNodeWithTag("quickadd.details").performClick()
        assertEquals("Soap", sent)
    }
}
