package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.ui.QuantityText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private fun record(id: String, name: String = id, unit: String? = null, deletedAt: String? = null, quantity: Double? = null, note: String? = null) =
    ListItemRecord(id, "l1", name, name.lowercase(), checked = false, deletedAt = deletedAt, createdAt = null, quantity = quantity, unit = unit, note = note)

/** SHOP-03 logic: the form rules (table-driven, same cases as web and iOS) and the sheet's writes, on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemSheetTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    // --- pure logic ---

    private fun fields(name: String = "Milk", quantity: String = "", unit: String = "", note: String = "") =
        ItemSheetLogic.fields(ItemForm(name, quantity, unit, note))

    @Test
    fun `fields parses the quantity, trims and turns blanks into null`() {
        assertEquals(2.0, fields(quantity = "2")!!.quantity!!, 0.0)
        assertEquals(0.5, fields(quantity = "0,5")!!.quantity!!, 0.0)
        assertEquals(0.5, fields(quantity = " 0.5 ")!!.quantity!!, 0.0)
        assertEquals(1.25, fields(quantity = "1.250")!!.quantity!!, 0.0)
        assertNull(fields(quantity = "")!!.quantity)
        assertEquals("l", fields(unit = "  l ")!!.unit)
        assertNull(fields(unit = "   ")!!.unit)
        assertNull(fields(note = "   ")!!.note)
        assertEquals("lactose free", fields(note = " lactose free ")!!.note)
        assertEquals("Milk", fields(name = "  Milk ")!!.name)
    }

    @Test
    fun `fields is null for a blank name or a bad quantity`() {
        assertNull(fields(name = "   "))
        for (bad in listOf("1.2345", "0", "0.0", "-1", "abc", "1,2,3", "1e3", ".5", "2 l")) {
            assertNull("quantity '$bad'", fields(quantity = bad))
        }
    }

    @Test
    fun `quantityError flags only non-blank text that is not a quantity`() {
        assertEquals(false, ItemSheetLogic.quantityError(""))
        assertEquals(false, ItemSheetLogic.quantityError("  "))
        assertEquals(false, ItemSheetLogic.quantityError("0,5"))
        assertEquals(true, ItemSheetLogic.quantityError("0"))
        assertEquals(true, ItemSheetLogic.quantityError("1.2345"))
        assertEquals(true, ItemSheetLogic.quantityError("abc"))
    }

    @Test
    fun `formatQty shows the number and the unit, and nothing without a quantity`() {
        assertEquals("2 l", ItemSheetLogic.formatQty(2.0, "l"))
        assertEquals("0.5 kg", ItemSheetLogic.formatQty(0.5, "kg"))
        assertEquals("3", ItemSheetLogic.formatQty(3.0, null))
        assertEquals("", ItemSheetLogic.formatQty(null, "l"))
        assertEquals("", ItemSheetLogic.formatQty(null, null))
        assertEquals("1.25 kg", ItemSheetLogic.formatQty(1.25, "kg"))
        assertEquals("0.1", ItemSheetLogic.formatQty(0.1, null))
    }

    @Test
    fun `unitSuggestions lists presets first, then household units without duplicates, deleted rows ignored`() {
        val history = listOf(
            record("a", unit = "L"), // a preset in other casing
            record("b", unit = "Bunch"),
            record("c", unit = "bunch"),
            record("d", unit = "  "),
            record("e", unit = "crate", deletedAt = "2026-01-01T00:00:00.000Z"),
            record("f", unit = null),
        )
        assertEquals(ItemSheetLogic.UNIT_PRESETS + "Bunch", ItemSheetLogic.unitSuggestions(history))
        assertEquals(ItemSheetLogic.UNIT_PRESETS, ItemSheetLogic.unitSuggestions(emptyList()))
    }

    @Test
    fun `unitSuggestions filters by what was typed, ignoring case`() {
        val history = listOf(record("a", unit = "Bunch"))
        assertEquals(listOf("kg"), ItemSheetLogic.unitSuggestions(history, "K"))
        assertEquals(listOf("pcs", "pack"), ItemSheetLogic.unitSuggestions(history, "p"))
        assertEquals(listOf("bottle", "Bunch"), ItemSheetLogic.unitSuggestions(history, "b"))
        assertEquals(emptyList<String>(), ItemSheetLogic.unitSuggestions(history, "zz"))
    }

    @Test
    fun `step adds one, and takes one away until it clears`() {
        assertEquals("1", QuantityText.step("", 1))
        assertEquals("3", QuantityText.step("2", 1))
        assertEquals("1.5", QuantityText.step("0,5", 1))
        assertEquals("", QuantityText.step("1", -1))
        assertEquals("", QuantityText.step("0.5", -1))
        assertEquals("1.5", QuantityText.step("2.5", -1))
        assertEquals("", QuantityText.step("", -1))
        assertEquals("abc", QuantityText.step("abc", 1))
    }

    @Test
    fun `changes holds only the changed columns, cleared ones as null`() {
        val before = record("i1", "Milk", unit = "l", quantity = 2.0, note = "lactose free")
        assertTrue(ItemSheetLogic.changes(before, ItemFields("Milk", 2.0, "l", "lactose free")).isEmpty)
        val cleared = ItemSheetLogic.changes(before, ItemFields("Milk", 2.0, "l", null))
        assertEquals(setOf("note"), cleared.fields.keys)
        assertEquals(JsonNull, cleared.fields["note"])
        assertNull(cleared.nameKey)
        val renamed = ItemSheetLogic.changes(before, ItemFields("Whole Milk", 2.0, "l", "lactose free"))
        assertEquals(setOf("name"), renamed.fields.keys)
        assertEquals("whole milk", renamed.nameKey)
    }

    // --- writes ---

    private fun viewModel(kicks: AtomicInteger = AtomicInteger(), delayer: suspend (Long) -> Unit = { }) = ListDetailViewModel(
        household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
        delayer = delayer, now = { now }, newId = { "n1" }, scope = scope,
    )

    private fun seedList() = runBlocking { createList(registry, household, "Groceries", null, now, "l1") }

    private fun seedMilk(quantity: Double? = 2.0, unit: String? = "l", note: String? = "lactose free") = runBlocking {
        registry.withSearchDao {
            it.upsertListItem(
                ListItemEntity("i1", hid, "l1", "Milk", nameKey = "milk", checked = false, quantity = quantity, unit = unit, note = note, createdAt = "2026-11-01T00:00:00.000Z"),
            )
        }
    }

    private suspend fun itemOutbox() = registry.outboxForTesting().filter { it.tableName == "list_item" }

    private suspend fun localItem(id: String): ListItemRecord = registry.observeListDetail(household, "l1").first().items.first { it.id == id }

    private suspend fun ListDetailViewModel.ready(): ListDetailViewModel {
        awaitUntil { state.value.loaded && state.value.detail.list != null }
        return this
    }

    @Test
    fun `AC1 a new item with no quantity, unit or note exists, with three nulls in the row and in the queued upsert`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks).ready()

        vm.onEvent(ListDetailEvent.OpenNew("Milk"))
        assertEquals(SheetState.New("Milk"), vm.state.value.sheet)
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk")))
        awaitUntil { itemOutbox().size == 1 }

        val item = localItem("n1")
        assertEquals("Milk", item.name)
        assertNull(item.quantity)
        assertNull(item.unit)
        assertNull(item.note)
        val queued = itemOutbox().single()
        assertEquals("upsert", queued.op)
        val data = Json.parseToJsonElement(queued.data).jsonObject
        for (key in listOf("quantity", "unit", "note")) {
            assertTrue("$key is sent", key in data.keys)
            assertEquals(JsonNull, data[key])
        }
        awaitUntil { vm.state.value.sheet == null }
        awaitUntil { kicks.get() == 1 }
        assertEquals(1, kicks.get())
    }

    @Test
    fun `a new item with all three set stores them and clears the bar`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        val vm = viewModel().ready()
        vm.onEvent(ListDetailEvent.QueryChanged("Mil"))

        vm.onEvent(ListDetailEvent.OpenNew("Mil"))
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm("Milk", "2", "l", "lactose free")))
        awaitUntil { itemOutbox().size == 1 }

        val item = localItem("n1")
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals("l", item.unit)
        assertEquals("lactose free", item.note)
        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(2.0, data["quantity"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("l", data["unit"]!!.jsonPrimitive.content)
        awaitUntil { vm.state.value.query == "" }
        assertEquals(null, vm.state.value.sheet)
    }

    @Test
    fun `an invalid form is not saved`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        val vm = viewModel().ready()
        vm.onEvent(ListDetailEvent.OpenNew(""))

        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm("Milk", "1.2345")))
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm("  ", "1")))
        delay(100)
        assertEquals(0, itemOutbox().size)
        assertEquals(SheetState.New(""), vm.state.value.sheet)
    }

    @Test
    fun `opening the new sheet before the list has loaded does nothing`() = runBlocking<Unit> {
        registry.open(household) // no list
        val vm = viewModel()
        awaitUntil { vm.state.value.loaded }
        vm.onEvent(ListDetailEvent.OpenNew("Milk"))
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `AC3 clearing the note queues exactly note null and leaves the quantity`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val vm = viewModel().ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        assertEquals(SheetState.Edit("i1"), vm.state.value.sheet)
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm("Milk", "2", "l", "")))
        awaitUntil { itemOutbox().size == 1 }

        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(JsonObject(mapOf("note" to JsonNull)), data)
        assertEquals("upsert", itemOutbox().single().op)
        val item = localItem("i1")
        assertNull(item.note)
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals("l", item.unit)
    }

    @Test
    fun `saving without a change writes nothing and closes the sheet`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks).ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm.of(vm.state.value.editing!!)))
        assertNull(vm.state.value.sheet)
        assertEquals(0, itemOutbox().size)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `a rename queues only the name and updates the local name_key`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val vm = viewModel().ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm("Whole Milk", "2", "l", "lactose free")))
        awaitUntil { itemOutbox().size == 1 }

        val data = Json.parseToJsonElement(itemOutbox().single().data).jsonObject
        assertEquals(setOf("name"), data.keys)
        assertEquals("Whole Milk", data["name"]!!.jsonPrimitive.content)
        awaitUntil { localItem("i1").nameKey == "whole milk" }
        assertEquals("Whole Milk", localItem("i1").name)
    }

    @Test
    fun `Cancel closes the sheet and writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val vm = viewModel().ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        vm.onEvent(ListDetailEvent.CloseSheet)
        assertNull(vm.state.value.sheet)
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `a save that cannot be stored keeps the sheet open with the failure flag`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        val vm = viewModel().ready()
        vm.onEvent(ListDetailEvent.OpenNew("Milk"))
        registry.close() // the database goes away under the open sheet

        vm.onEvent(ListDetailEvent.SaveSheet(ItemForm(name = "Milk")))
        awaitUntil { vm.state.value.saveFailed }
        assertEquals(SheetState.New("Milk"), vm.state.value.sheet)
    }

    // --- delete with undo ---

    @Test
    fun `Delete hides the row at once and writes nothing inside the window, then one delete when it ends`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val delays = mutableListOf<Long>()
        val vm = viewModel(kicks, delayer = { delays += it; gate.await() }).ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        vm.onEvent(ListDetailEvent.Delete("i1"))
        assertEquals(listOf(UNDO_MILLIS), delays)
        assertTrue(vm.state.value.unchecked.isEmpty())
        assertNull(vm.state.value.sheet)
        assertEquals("Deleted Milk", vm.state.value.pending.single().message)
        assertEquals(0, itemOutbox().size)

        gate.complete(Unit)
        awaitUntil { itemOutbox().size == 1 }
        val queued = itemOutbox().single()
        assertEquals("delete", queued.op)
        assertEquals("i1", queued.rowId)
        awaitUntil { vm.state.value.pending.isEmpty() }
        awaitUntil { kicks.get() == 1 }
        assertTrue(vm.state.value.unchecked.isEmpty())
    }

    @Test
    fun `Undo inside the window writes nothing and the row is back`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, delayer = { gate.await() }).ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.Delete("i1"))
        vm.onEvent(ListDetailEvent.Undo("i1"))
        assertEquals(listOf("Milk"), vm.state.value.unchecked.map { it.name })
        assertTrue(vm.state.value.pending.isEmpty())
        gate.complete(Unit)
        delay(100)
        assertEquals(0, itemOutbox().size)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `Flush writes a pending delete at once and the window then writes nothing more`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(delayer = { gate.await() }).ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }

        vm.onEvent(ListDetailEvent.Delete("i1"))
        vm.onEvent(ListDetailEvent.Flush)
        awaitUntil { itemOutbox().size == 1 }
        gate.complete(Unit)
        delay(100)
        assertEquals(1, itemOutbox().size)
        assertEquals("delete", itemOutbox().single().op)
    }

    @Test
    fun `an edited row that disappears closes its sheet`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedMilk()
        val vm = viewModel().ready()
        awaitUntil { vm.state.value.unchecked.size == 1 }
        vm.onEvent(ListDetailEvent.OpenEdit("i1"))
        assertEquals(SheetState.Edit("i1"), vm.state.value.sheet)

        // Deleted on another device: the next pull soft-deletes the local row.
        registry.withSearchDao { it.upsertListItem(ListItemEntity("i1", hid, "l1", "Milk", deletedAt = "2026-11-26T09:00:00.000Z", checked = false)) }
        awaitUntil { vm.state.value.sheet == null }
        assertTrue(vm.state.value.unchecked.isEmpty())
    }
}
