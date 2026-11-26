package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.ListItemRecord
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private fun row(id: String, checkedAt: String? = null, createdAt: String? = null) =
    ListItemRecord(id, "l1", id, id, checked = true, deletedAt = null, createdAt = createdAt, checkedAt = checkedAt)

/** SHOP-04 logic: the pure rules (same tables as web and iOS) and the check-off, uncheck and clear writes, on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckOffTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val user = uuid(7)
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    // --- pure logic ---

    @Test
    fun `checkFields sets exactly the three columns, checked_by null without a user`() {
        assertEquals(
            JsonObject(
                mapOf(
                    "checked" to JsonPrimitive(true),
                    "checked_at" to JsonPrimitive("2026-11-26T08:00:00.000Z"),
                    "checked_by" to JsonPrimitive(user.toString()),
                ),
            ),
            CheckOff.checkFields(user, now),
        )
        assertEquals(JsonNull, CheckOff.checkFields(null, now)["checked_by"])
    }

    @Test
    fun `uncheckFields puts all three back`() {
        assertEquals(
            JsonObject(mapOf("checked" to JsonPrimitive(false), "checked_at" to JsonNull, "checked_by" to JsonNull)),
            CheckOff.uncheckFields(),
        )
    }

    @Test
    fun `allChecked needs no unchecked, no pending check and something checked`() {
        // (unchecked, pendingChecks, checked) -> expected
        val table = listOf(
            Triple(0, 0, 0) to false,
            Triple(0, 0, 1) to true,
            Triple(0, 0, 4) to true,
            Triple(1, 0, 3) to false,
            Triple(0, 1, 3) to false,
            Triple(2, 1, 0) to false,
        )
        for ((input, expected) in table) {
            assertEquals("$input", expected, CheckOff.allChecked(input.first, input.second, input.third))
        }
    }

    @Test
    fun `newestFirst orders by checkedAt, then createdAt, then id`() {
        val rows = listOf(
            row("a", checkedAt = "2026-11-26T08:00:00.000Z"),
            row("b", checkedAt = "2026-11-26T09:00:00.000Z"),
            row("c", createdAt = "2026-11-01T00:00:00.000Z"),
            row("e", createdAt = "2026-11-02T00:00:00.000Z"),
            row("d", createdAt = "2026-11-02T00:00:00.000Z"),
        )
        assertEquals(listOf("b", "a", "d", "e", "c"), rows.shuffled().sortedWith(CheckOff.newestFirst).map { it.id })
    }

    @Test
    fun `PendingAction messages`() {
        assertEquals("Deleted Milk", PendingAction.Delete("i1", "Milk").message)
        assertEquals("Checked Milk", PendingAction.Check("i1", "Milk").message)
        assertEquals("Cleared 1 item", PendingAction.Clear("c", listOf("a")).message)
        assertEquals("Cleared 2 items", PendingAction.Clear("c", listOf("a", "b")).message)
    }

    // --- writes ---

    private fun viewModel(kicks: AtomicInteger = AtomicInteger(), gate: CompletableDeferred<Unit> = CompletableDeferred()) = ListDetailViewModel(
        household, "l1", user, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
        delayer = { gate.await() }, now = { now }, newId = { "n1" }, scope = scope,
    )

    private fun seedList() = runBlocking { createList(registry, household, "Groceries", null, now, "l1") }

    private fun seedItem(id: String, name: String, checked: Boolean = false, checkedAt: String? = null, created: String = "2026-11-01T00:00:00.000Z") = runBlocking {
        registry.withSearchDao {
            it.upsertListItem(ListItemEntity(id, hid, "l1", name, nameKey = name.lowercase(), checked = checked, checkedAt = checkedAt, createdAt = created))
        }
    }

    private suspend fun itemOutbox() = registry.outboxForTesting().filter { it.tableName == "list_item" }

    private suspend fun stored(id: String): ListItemRecord? = registry.observeListDetail(household, "l1").first().items.firstOrNull { it.id == id }

    private suspend fun ListDetailViewModel.ready(items: Int): ListDetailViewModel {
        awaitUntil { state.value.loaded && state.value.detail.items.size == items }
        return this
    }

    @Test
    fun `AC2 Check then Undo leaves the row and the outbox untouched, even after the window`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, gate).ready(1)

        vm.onEvent(ListDetailEvent.Check("i1"))
        assertEquals("Checked Milk", vm.state.value.pending.single().message)
        // Ticked in place among the unchecked rows.
        assertEquals(setOf("i1"), vm.state.value.pendingCheckIds)
        assertEquals(listOf("i1"), vm.state.value.unchecked.map { it.id })
        vm.onEvent(ListDetailEvent.Undo("i1"))
        assertTrue(vm.state.value.pending.isEmpty())
        assertTrue(vm.state.value.pendingCheckIds.isEmpty())
        gate.complete(Unit)
        delay(100)

        assertEquals(0, itemOutbox().size)
        assertFalse(stored("i1")!!.checked)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `AC3 the window ending writes one check-off patch, stamps and the user, then one kick`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, gate).ready(1)

        vm.onEvent(ListDetailEvent.Check("i1"))
        assertEquals(0, itemOutbox().size)
        assertFalse(stored("i1")!!.checked)
        gate.complete(Unit)
        awaitUntil { itemOutbox().size == 1 }

        val queued = itemOutbox().single()
        assertEquals("upsert", queued.op)
        assertEquals("i1", queued.rowId)
        assertEquals(CheckOff.checkFields(user, now), Json.parseToJsonElement(queued.data).jsonObject)
        awaitUntil { vm.state.value.checked.map { it.id } == listOf("i1") }
        val item = stored("i1")!!
        assertTrue(item.checked)
        assertEquals("2026-11-26T08:00:00.000Z", item.checkedAt)
        awaitUntil { vm.state.value.pending.isEmpty() && vm.state.value.settlingChecks.isEmpty() }
        awaitUntil { kicks.get() == 1 }
        assertEquals(1, kicks.get())
        assertEquals(1, itemOutbox().size)
    }

    @Test
    fun `expiry skips a row deleted meanwhile and a row already checked elsewhere`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk")
        seedItem("i2", "Bread")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(gate = gate).ready(2)
        vm.onEvent(ListDetailEvent.Check("i1"))
        vm.onEvent(ListDetailEvent.Check("i2"))

        // Another device: Milk deleted, Bread checked (the next pull).
        runBlocking {
            registry.withSearchDao { it.upsertListItem(ListItemEntity("i1", hid, "l1", "Milk", deletedAt = "2026-11-26T09:00:00.000Z", checked = false)) }
        }
        seedItem("i2", "Bread", checked = true, checkedAt = "2026-11-26T09:00:00.000Z")
        gate.complete(Unit)

        awaitUntil { vm.state.value.pending.isEmpty() }
        delay(100)
        assertEquals(0, itemOutbox().size)
        assertEquals("2026-11-26T09:00:00.000Z", stored("i2")!!.checkedAt)
    }

    @Test
    fun `Flush writes every pending check, clear and delete at once`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk")
        seedItem("i2", "Bread", checked = true, checkedAt = "2026-11-25T09:00:00.000Z")
        seedItem("i3", "Eggs")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(gate = gate).ready(3)
        vm.onEvent(ListDetailEvent.Check("i1"))
        vm.onEvent(ListDetailEvent.ClearChecked)
        vm.onEvent(ListDetailEvent.Delete("i3"))

        vm.onEvent(ListDetailEvent.Flush)
        awaitUntil { itemOutbox().size == 3 }
        gate.complete(Unit)
        delay(100)

        val byRow = itemOutbox().associateBy { it.rowId }
        assertEquals("upsert", byRow.getValue("i1").op)
        assertEquals("delete", byRow.getValue("i2").op)
        assertEquals("delete", byRow.getValue("i3").op)
        assertEquals(3, itemOutbox().size)
        awaitUntil { vm.state.value.pending.isEmpty() }
    }

    @Test
    fun `Uncheck writes one upsert with all three columns reset and shows no toast`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk", checked = true, checkedAt = "2026-11-25T09:00:00.000Z")
        val kicks = AtomicInteger()
        val vm = viewModel(kicks).ready(1)
        awaitUntil { vm.state.value.checked.size == 1 }

        vm.onEvent(ListDetailEvent.Uncheck("i1"))
        assertTrue(vm.state.value.pending.isEmpty())
        awaitUntil { itemOutbox().size == 1 }

        assertEquals(CheckOff.uncheckFields(), Json.parseToJsonElement(itemOutbox().single().data).jsonObject)
        awaitUntil { vm.state.value.unchecked.map { it.id } == listOf("i1") }
        assertNull(stored("i1")!!.checkedAt)
        awaitUntil { kicks.get() == 1 }
        assertTrue(vm.state.value.pending.isEmpty())
    }

    @Test
    fun `AC5 ClearChecked soft-deletes the three checked rows after the window and leaves the unchecked one`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        for (n in 1..3) seedItem("c$n", "Done $n", checked = true, checkedAt = "2026-11-25T0$n:00:00.000Z")
        seedItem("u1", "Milk")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, gate).ready(4)
        awaitUntil { vm.state.value.checked.size == 3 }

        vm.onEvent(ListDetailEvent.ClearChecked)
        assertEquals("Cleared 3 items", vm.state.value.pending.single().message)
        assertTrue(vm.state.value.checked.isEmpty())
        assertEquals(0, itemOutbox().size)
        gate.complete(Unit)
        awaitUntil { itemOutbox().size == 3 }

        assertEquals(setOf("c1", "c2", "c3"), itemOutbox().map { it.rowId }.toSet())
        assertTrue(itemOutbox().all { it.op == "delete" })
        awaitUntil { vm.state.value.pending.isEmpty() }
        awaitUntil { kicks.get() == 1 }
        assertEquals(listOf("u1"), registry.observeListDetail(household, "l1").first().items.map { it.id })
        assertEquals(listOf("u1"), vm.state.value.unchecked.map { it.id })
        assertTrue(vm.state.value.checked.isEmpty())
    }

    @Test
    fun `ClearChecked then Undo writes nothing and the rows are back`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("c1", "Done", checked = true, checkedAt = "2026-11-25T09:00:00.000Z")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(gate = gate).ready(1)
        awaitUntil { vm.state.value.checked.size == 1 }

        vm.onEvent(ListDetailEvent.ClearChecked)
        val actionId = vm.state.value.pending.single().id
        vm.onEvent(ListDetailEvent.Undo(actionId))
        assertEquals(listOf("c1"), vm.state.value.checked.map { it.id })
        gate.complete(Unit)
        delay(100)
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `a row checked during the clear window is not swept in`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("c1", "Done", checked = true, checkedAt = "2026-11-25T09:00:00.000Z")
        seedItem("u1", "Milk")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(gate = gate).ready(2)
        awaitUntil { vm.state.value.checked.size == 1 }
        vm.onEvent(ListDetailEvent.ClearChecked)

        // Milk is checked elsewhere while the toast is up.
        seedItem("u1", "Milk", checked = true, checkedAt = "2026-11-26T07:00:00.000Z")
        awaitUntil { vm.state.value.detail.items.any { it.id == "u1" && it.checked } }
        gate.complete(Unit)
        awaitUntil { itemOutbox().size == 1 }
        delay(100)

        assertEquals("c1", itemOutbox().single().rowId)
        assertEquals(listOf("u1"), vm.state.value.checked.map { it.id })
    }

    @Test
    fun `deleting a pending-check item cancels the check, leaving one delete and no upsert`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(gate = gate).ready(1)

        vm.onEvent(ListDetailEvent.Check("i1"))
        vm.onEvent(ListDetailEvent.Delete("i1"))
        assertEquals("Deleted Milk", vm.state.value.pending.single().message)
        assertTrue(vm.state.value.pendingCheckIds.isEmpty())
        gate.complete(Unit)
        awaitUntil { itemOutbox().size == 1 }
        delay(100)

        assertEquals(1, itemOutbox().size)
        assertEquals("delete", itemOutbox().single().op)
    }

    @Test
    fun `allChecked follows the rows, a pending check keeps it false`() = runBlocking<Unit> {
        registry.open(household)
        seedList()
        seedItem("i1", "Milk")
        seedItem("i2", "Bread", checked = true, checkedAt = "2026-11-25T09:00:00.000Z")
        val vm = viewModel().ready(2)
        awaitUntil { vm.state.value.checked.size == 1 }
        assertFalse(vm.state.value.allChecked)

        vm.onEvent(ListDetailEvent.Check("i1"))
        assertFalse(vm.state.value.allChecked)
        vm.onEvent(ListDetailEvent.Delete("i1"))
        // Milk is hidden by its pending delete; only Bread is left, and it is checked.
        assertTrue(vm.state.value.allChecked)
    }
}
