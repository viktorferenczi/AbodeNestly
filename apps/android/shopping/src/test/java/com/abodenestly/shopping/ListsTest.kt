package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ItemCount
import com.abodenestly.sync.ListOverview
import com.abodenestly.sync.ListRecord
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.StoreEntity
import com.abodenestly.sync.StoreRecord
import com.abodenestly.sync.patch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private fun rec(
    id: String, name: String = id, storeId: String? = null, template: Boolean = false, archivedAt: String? = null, createdAt: String? = null,
) = ListRecord(id, name, storeId, template, archivedAt, createdAt)

private fun names(rows: List<ListRowModel>) = rows.map { it.name }

/** SHOP-01 logic: the section split (table-driven) and the outbox writes of the overview, on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    // --- ListsSections.make ---

    @Test
    fun `a plain list lands in Lists only`() {
        val sections = ListsSections.make(ListOverview(lists = listOf(rec("a", "Alpha", createdAt = "2026-01-01T00:00:00.000Z"))))
        assertEquals(listOf("Alpha"), names(sections.lists))
        assertTrue(sections.templates.isEmpty() && sections.archived.isEmpty())
    }

    @Test
    fun `lists, templates and archived are split`() {
        val sections = ListsSections.make(
            ListOverview(
                lists = listOf(
                    rec("l", "Groceries", createdAt = "2026-01-01T00:00:00.000Z"),
                    rec("t", "Weekly", template = true, createdAt = "2026-01-02T00:00:00.000Z"),
                    rec("a", "Old", archivedAt = "2026-02-01T00:00:00.000Z", createdAt = "2026-01-03T00:00:00.000Z"),
                    // An archived template is archived, not a template row.
                    rec("at", "Old template", template = true, archivedAt = "2026-03-01T00:00:00.000Z"),
                ),
            ),
        )
        assertEquals(listOf("Groceries"), names(sections.lists))
        assertEquals(listOf("Weekly"), names(sections.templates))
        assertEquals(listOf("Old template", "Old"), names(sections.archived))
    }

    @Test
    fun `lists and templates are oldest first, a missing created_at first and then by name`() {
        val sections = ListsSections.make(
            ListOverview(
                lists = listOf(
                    rec("1", "New", createdAt = "2026-05-01T00:00:00.000Z"),
                    rec("2", "Zed"),
                    rec("3", "Old", createdAt = "2026-01-01T00:00:00.000Z"),
                    rec("4", "alpha"),
                ),
            ),
        )
        assertEquals(listOf("alpha", "Zed", "Old", "New"), names(sections.lists))
    }

    @Test
    fun `hidden lists leave every section`() {
        val overview = ListOverview(lists = listOf(rec("a", "Alpha"), rec("b", "Beta", archivedAt = "2026-02-01T00:00:00.000Z")))
        val sections = ListsSections.make(overview, hidden = setOf("a", "b"))
        assertTrue(sections.lists.isEmpty() && sections.archived.isEmpty())
        assertTrue(sections.noActive)
    }

    @Test
    fun `the count reads 3 of 12 or No items, and the store chip shows only for a live store`() {
        val sections = ListsSections.make(
            ListOverview(
                lists = listOf(
                    rec("a", "Groceries", storeId = "s1", createdAt = "1"),
                    rec("b", "Hardware", storeId = "gone", createdAt = "2"),
                    rec("c", "Empty", createdAt = "3"),
                ),
                counts = mapOf("a" to ItemCount(checked = 3, all = 12)),
                stores = listOf(StoreRecord("s1", "Test Market")),
            ),
        )
        assertEquals(listOf("3 of 12", "No items", "No items"), sections.lists.map { it.count })
        assertEquals(listOf("Test Market", null, null), sections.lists.map { it.storeName })
    }

    // --- Writes ---

    private fun listRow(id: String) = ListRowModel(id, id, null, "No items")

    private suspend fun seed(id: String, name: String, archivedAt: String? = null) {
        createList(registry, household, name, null, now, id)
        if (archivedAt != null) registry.patch(household, "shopping_list", id, buildJsonObject { put("archived_at", JsonPrimitive(archivedAt)) }, now)
    }

    private fun viewModel(kicks: AtomicInteger, delayer: suspend (Long) -> Unit, online: Boolean = true, household: java.util.UUID = this.household) = ListsViewModel(
        household, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(online),
        delayer = delayer, now = { now }, newId = { "created" }, scope = scope,
    )

    private suspend fun lists() = registry.observeListOverview(household).first().lists

    @Test
    fun `AC6 createList offline writes the local row and exactly one outbox row`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "  Hardware  ", "s1", now, "l1")
        val row = lists().single()
        assertEquals("Hardware", row.name)
        assertEquals("s1", row.storeId)
        assertFalse(row.isTemplate)
        val outbox = registry.outboxForTesting()
        assertEquals(1, outbox.size)
        assertEquals("shopping_list", outbox[0].tableName)
        assertEquals("upsert", outbox[0].op)
        assertEquals("l1", outbox[0].rowId)
        val data = Json.parseToJsonElement(outbox[0].data).jsonObject
        assertEquals(setOf("name", "store_id", "is_template", "archived_at", "deleted_at"), data.keys)
        assertEquals("Hardware", data["name"]!!.jsonPrimitive.content)
        assertFalse(data["is_template"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, data["archived_at"])
    }

    @Test
    fun `AC4 Undo inside the window writes nothing and the list is back`() = runBlocking<Unit> {
        registry.open(household)
        seed("l1", "Groceries")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, delayer = { gate.await() })
        awaitUntil { vm.state.value.sections.lists.size == 1 }

        vm.onEvent(ListsEvent.Archive(listRow("l1")))
        assertTrue(vm.state.value.sections.lists.isEmpty())
        assertEquals("Archived l1", vm.state.value.pending.single().message)

        vm.onEvent(ListsEvent.Undo("l1"))
        gate.complete(Unit)
        delay(100)
        assertEquals(1, vm.state.value.sections.lists.size)
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(1, registry.outboxForTesting().size) // the create only
        assertNull(lists().single().archivedAt)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `AC3 the swipe is written when the window ends, as one archived_at patch and one kick`() = runBlocking<Unit> {
        registry.open(household)
        seed("l1", "Groceries")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val delays = mutableListOf<Long>()
        val vm = viewModel(kicks, delayer = { delays += it; gate.await() })
        awaitUntil { vm.state.value.sections.lists.size == 1 }

        vm.onEvent(ListsEvent.Archive(listRow("l1")))
        assertEquals(listOf(UNDO_MILLIS), delays)
        assertEquals(1, registry.outboxForTesting().size) // nothing yet
        gate.complete(Unit)
        awaitUntil { registry.outboxForTesting().size == 2 }

        val queued = registry.outboxForTesting().last()
        assertEquals("upsert", queued.op)
        assertEquals("l1", queued.rowId)
        val data = Json.parseToJsonElement(queued.data).jsonObject
        assertEquals(setOf("archived_at"), data.keys)
        assertEquals("2026-11-26T08:00:00.000Z", data["archived_at"]!!.jsonPrimitive.content)
        assertNotNull(lists().single().archivedAt)
        awaitUntil { vm.state.value.pending.isEmpty() }
        assertEquals(1, kicks.get())
        // The row stays out of Lists while the overview catches up, and then it is in Archived.
        awaitUntil { vm.state.value.sections.archived.size == 1 }
        assertTrue(vm.state.value.sections.lists.isEmpty())
    }

    @Test
    fun `flush writes a waiting swipe at once and the window then writes nothing more`() = runBlocking<Unit> {
        registry.open(household)
        seed("l1", "Groceries")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, delayer = { gate.await() })
        awaitUntil { vm.state.value.sections.lists.size == 1 }

        vm.onEvent(ListsEvent.Archive(listRow("l1")))
        vm.onEvent(ListsEvent.Flush)
        awaitUntil { registry.outboxForTesting().size == 2 }
        gate.complete(Unit)
        delay(100)
        assertEquals(2, registry.outboxForTesting().size)
        assertEquals(1, kicks.get())
    }

    @Test
    fun `Delete on an archived list soft-deletes it after the window`() = runBlocking<Unit> {
        registry.open(household)
        seed("l1", "Old", archivedAt = "2026-02-01T00:00:00.000Z")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(AtomicInteger(), delayer = { gate.await() })
        awaitUntil { vm.state.value.sections.archived.size == 1 }

        vm.onEvent(ListsEvent.Delete(listRow("l1")))
        assertEquals("Deleted l1", vm.state.value.pending.single().message)
        gate.complete(Unit)
        awaitUntil { registry.outboxForTesting().last().op == "delete" }
        assertEquals("l1", registry.outboxForTesting().last().rowId)
        awaitUntil { lists().isEmpty() }
    }

    @Test
    fun `Restore clears archived_at at once and queues only that column`() = runBlocking<Unit> {
        registry.open(household)
        seed("l1", "Old", archivedAt = "2026-02-01T00:00:00.000Z")
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, delayer = { error("Restore has no window") })
        awaitUntil { vm.state.value.sections.archived.size == 1 }

        vm.onEvent(ListsEvent.Restore(listRow("l1")))
        awaitUntil { registry.outboxForTesting().size == 3 } // create, archive, restore
        val data = Json.parseToJsonElement(registry.outboxForTesting().last().data).jsonObject
        assertEquals(setOf("archived_at"), data.keys)
        assertEquals(JsonNull, data["archived_at"])
        awaitUntil { vm.state.value.sections.lists.size == 1 }
        awaitUntil { kicks.get() == 1 }
    }

    @Test
    fun `AC5 Create writes the list, kicks and reports it to open`() = runBlocking<Unit> {
        registry.open(household)
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", household.toString(), "Test Market")) }
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, delayer = { })
        awaitUntil { vm.state.value.loaded }
        assertEquals(listOf(StoreRecord("s1", "Test Market")), vm.state.value.stores)

        vm.onEvent(ListsEvent.Create("  Hardware ", "s1"))
        awaitUntil { vm.state.value.opened == "created" }
        assertEquals(1, registry.outboxForTesting().size)
        assertEquals(1, kicks.get())
        vm.onEvent(ListsEvent.OpenedHandled)
        assertNull(vm.state.value.opened)
        awaitUntil { vm.state.value.sections.lists.map { it.name } == listOf("Hardware") }
        assertEquals("Test Market", vm.state.value.sections.lists.single().storeName)
    }

    @Test
    fun `a blank name creates nothing`() = runBlocking<Unit> {
        registry.open(household)
        val vm = viewModel(AtomicInteger(), delayer = { })
        vm.onEvent(ListsEvent.Create("   ", null))
        delay(100)
        assertEquals(0, registry.outboxForTesting().size)
        assertNull(vm.state.value.opened)
    }

    @Test
    fun `a write that cannot be stored shows the message and opens nothing`() = runBlocking<Unit> {
        registry.open(household)
        // The household of this model is not the open one: the write throws, like a switch mid-write.
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, delayer = { }, household = uuid(2))
        vm.onEvent(ListsEvent.Create("Hardware", null))
        awaitUntil { vm.state.value.createFailed }
        assertEquals(SAVE_ERROR, vm.state.value.error)
        assertNull(vm.state.value.opened)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `the overview counts come through the model`() = runBlocking<Unit> {
        registry.open(household)
        seed("l1", "Groceries")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("i1", household.toString(), "l1", "Milk", checked = true))
            dao.upsertListItem(ListItemEntity("i2", household.toString(), "l1", "Eggs", checked = false))
        }
        val vm = viewModel(AtomicInteger(), delayer = { })
        awaitUntil { vm.state.value.sections.lists.singleOrNull()?.count == "1 of 2" }
    }
}
