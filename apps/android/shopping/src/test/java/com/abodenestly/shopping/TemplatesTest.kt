package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.ListRecord
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.sync.liveListItems
import com.abodenestly.sync.patch
import com.abodenestly.sync.remove
import com.abodenestly.sync.upsertAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** SHOP-11 logic and writes on a real Room file: the copy, its order, the batch transaction and the list filters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplatesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val user = uuid(7)
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun item(
        id: String, name: String, listId: String = "t1", checked: Boolean = false, createdAt: String = "2026-11-01T00:00:00.000Z",
        checkedAt: String? = null, deletedAt: String? = null,
    ) = ListItemRecord(id, listId, name, name.lowercase(), checked, deletedAt, createdAt, checkedAt = checkedAt)

    private fun list(id: String, createdAt: String?, template: Boolean = false, archivedAt: String? = null) =
        ListRecord(id, id, null, template, archivedAt, createdAt)

    private fun seed() = runBlocking {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        createList(registry, household, "Weekly", null, now.plusSeconds(1), "t1", isTemplate = true)
        registry.withSearchDao { dao ->
            dao.upsertListItem(
                ListItemEntity(
                    "a", hid, "t1", "Milk", nameKey = "milk", quantity = 2.0, unit = "l", note = "lactose free", barcode = "5000112637922",
                    checked = false, createdAt = "2026-11-01T00:00:01.000Z", createdBy = uuid(9).toString(),
                ),
            )
            dao.upsertListItem(ListItemEntity("b", hid, "t1", "Eggs", nameKey = "eggs", checked = false, createdAt = "2026-11-01T00:00:02.000Z"))
        }
    }

    private fun outbox() = runBlocking { registry.outboxForTesting() }

    @Test
    fun `AC2 N items give N upserts with fresh ids and copied fields, none for the template, and the template is unchanged`() = runBlocking<Unit> {
        seed()
        val before = outbox().count { it.tableName == "shopping_list" }
        val counter = AtomicInteger()

        val count = Templates.addAllToList(registry, household, "t1", "l1", user, now, uuid = { "n${counter.incrementAndGet()}" })

        assertEquals(2, count)
        val upserts = outbox().filter { it.tableName == "list_item" }
        assertEquals(listOf("n1", "n2"), upserts.map { it.rowId })
        assertTrue(upserts.all { it.op == "upsert" })
        assertEquals(before, outbox().count { it.tableName == "shopping_list" })
        val copied = registry.liveListItems(household, "l1").sortedBy { it.createdAt }
        assertEquals(listOf("Milk", "Eggs"), copied.map { it.name })
        val milk = copied.first()
        assertEquals(2.0, milk.quantity)
        assertEquals("l", milk.unit)
        assertEquals("lactose free", milk.note)
        assertEquals("5000112637922", milk.barcode)
        assertTrue(copied.none { it.checked || it.checkedAt != null })
        val data = Json.parseToJsonElement(upserts.first().data).jsonObject
        assertEquals("l1", data["list_id"]!!.jsonPrimitive.content)
        assertEquals(false, data["checked"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(data["checked_at"] is JsonNull)
        assertTrue(data["checked_by"] is JsonNull)
        assertTrue(data["manual_position"] is JsonNull)
        assertEquals("manual", data["source"]!!.jsonPrimitive.content)
        assertTrue(data["pantry_item_id"] is JsonNull)
        assertTrue(data["maint_task_id"] is JsonNull)
        assertEquals(user.toString(), data["created_by"]!!.jsonPrimitive.content)
        assertNull(data["id"])
        assertEquals(listOf("a", "b"), registry.liveListItems(household, "t1").map { it.id }.sorted())
    }

    @Test
    fun `deleted items are skipped and checked items are copied after the others, unchecked`() {
        val items = listOf(
            item("u1", "Late", createdAt = "2026-11-01T00:00:03.000Z"),
            item("u2", "Early", createdAt = "2026-11-01T00:00:01.000Z"),
            item("c1", "Old check", checked = true, checkedAt = "2026-11-02T09:00:00.000Z"),
            item("c2", "New check", checked = true, checkedAt = "2026-11-02T10:00:00.000Z"),
            item("d1", "Deleted", deletedAt = "2026-11-03T00:00:00.000Z"),
            item("o1", "Other list", listId = "other"),
        )
        val ids = AtomicInteger()
        val rows = Templates.copies(items, "t1", "l1", household, user, now, uuid = { "n${ids.incrementAndGet()}" })

        assertEquals(listOf("Early", "Late", "New check", "Old check"), rows.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(rows.all { it["checked"]!!.jsonPrimitive.booleanOrNull == false && it["checked_at"] is JsonNull })
        assertEquals(listOf("n1", "n2", "n3", "n4"), rows.map { it["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `created_at follows the template's order, one millisecond apart, even when the ids sort the other way`() {
        val items = listOf(
            item("z", "First", createdAt = "2026-11-01T00:00:01.000Z"),
            item("m", "Second", createdAt = "2026-11-01T00:00:02.000Z"),
            item("a", "Third", createdAt = "2026-11-01T00:00:03.000Z"),
        )
        val ids = listOf("y", "b", "a").iterator()
        val rows = Templates.copies(items, "t1", "l1", household, user, now, uuid = { ids.next() })

        assertEquals(
            listOf("2026-11-28T08:00:00.000Z", "2026-11-28T08:00:00.001Z", "2026-11-28T08:00:00.002Z"),
            rows.map { it["created_at"]!!.jsonPrimitive.content },
        )
        // C2 sorts oldest first: the copies read back as First, Second, Third although their ids sort the other way.
        val shown = rows.sortedWith(compareBy({ it["created_at"]!!.jsonPrimitive.content }, { it["id"]!!.jsonPrimitive.content }))
        assertEquals(listOf("First", "Second", "Third"), shown.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `AC3 an empty template writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        createList(registry, household, "Empty", null, now, "t1", isTemplate = true)
        val before = outbox().size

        val count = Templates.addAllToList(registry, household, "t1", "l1", user, now)

        assertEquals(0, count)
        assertEquals(before, outbox().size)
        assertEquals(0, registry.liveListItems(household, "l1").size)
    }

    @Test
    fun `upsertAll rolls the whole batch back when one row cannot be stored`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        val before = outbox().size
        val good = QuickAdd.row("x1", "l1", "Milk", user, now)
        // No list_id: the NOT NULL column makes the local insert fail after the first row was written.
        val bad = buildJsonObject { put("id", JsonPrimitive("x2")); put("name", JsonPrimitive("Eggs")) }

        val failed = runCatching { registry.upsertAll(household, "list_item", listOf(good, bad), now) }.isFailure

        assertTrue(failed)
        assertEquals(before, outbox().size)
        assertEquals(0, registry.liveListItems(household, "l1").size)
    }

    @Test
    fun `upsertAll with no rows does nothing`() = runBlocking<Unit> {
        registry.open(household)
        registry.upsertAll(household, "list_item", emptyList(), now)
        assertEquals(0, outbox().size)
    }

    @Test
    fun `create with isTemplate true writes is_template locally and in the outbox data`() = runBlocking<Unit> {
        registry.open(household)
        val vm = ListsViewModel(
            household, registry, kick = { }, isOnline = MutableStateFlow(true), delayer = { kotlinx.coroutines.awaitCancellation() },
            now = { now }, newId = { "created" }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded }

        vm.onEvent(ListsEvent.Create("Meal prep", null, isTemplate = true))
        awaitUntil { vm.state.value.opened == "created" }

        assertTrue(registry.observeListOverview(household).first().lists.single { it.id == "created" }.isTemplate)
        val data = Json.parseToJsonElement(outbox().single { it.rowId == "created" }.data).jsonObject
        assertEquals(true, data["is_template"]!!.jsonPrimitive.booleanOrNull)
        // The default stays a normal list.
        createList(registry, household, "Normal", null, now, "plain")
        assertEquals(false, registry.observeListOverview(household).first().lists.single { it.id == "plain" }.isTemplate)
    }

    @Test
    fun `targets exclude templates, archived lists and deleted lists, oldest first`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Newer", null, now.plusSeconds(5), "n")
        createList(registry, household, "Older", null, now, "o")
        createList(registry, household, "Template", null, now, "t", isTemplate = true)
        createList(registry, household, "Archived", null, now, "a")
        createList(registry, household, "Deleted", null, now, "d")
        registry.patch(household, "shopping_list", "a", buildJsonObject { put("archived_at", JsonPrimitive("2026-11-01T00:00:00.000Z")) }, now)
        registry.remove(household, "shopping_list", "d", now)

        val targets = Templates.targets(registry.observeListOverview(household).first().lists)

        assertEquals(listOf("o", "n"), targets.map { it.id })
    }

    @Test
    fun `targets table`() {
        val lists = listOf(
            list("b", "2026-11-02T00:00:00.000Z"), list("a", "2026-11-01T00:00:00.000Z"), list("none", null),
            list("t", "2026-11-01T00:00:00.000Z", template = true), list("x", "2026-11-01T00:00:00.000Z", archivedAt = "2026-11-05T00:00:00.000Z"),
        )
        assertEquals(listOf("none", "a", "b"), Templates.targets(lists).map { it.id })
        assertEquals(emptyList<ListRecord>(), Templates.targets(emptyList()))
    }

    @Test
    fun `actions table`() {
        assertEquals(listOf(ListAction.AddAll), Templates.actions(isTemplate = true, hasChecked = false))
        assertEquals(listOf(ListAction.AddAll, ListAction.ClearChecked), Templates.actions(isTemplate = true, hasChecked = true))
        assertEquals(emptyList<ListAction>(), Templates.actions(isTemplate = false, hasChecked = false))
        assertEquals(listOf(ListAction.ClearChecked), Templates.actions(isTemplate = false, hasChecked = true))
    }

    @Test
    fun `result messages`() {
        assertEquals("Added 1 item to Groceries", AddAllResult.Added(1, "Groceries").message)
        assertEquals("Added 3 items to Groceries", AddAllResult.Added(3, "Groceries").message)
        assertEquals("Nothing to add: this template is empty", AddAllResult.Empty.message)
        assertNull(AddAllResult.Failed.message)
    }

    @Test
    fun `the view model kicks once after a copy, never for an empty template or a failed copy`() = runBlocking<Unit> {
        seed()
        val kicks = AtomicInteger()
        val ids = AtomicInteger()
        fun model(listId: String) = ListDetailViewModel(
            household, listId, user, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
            delayer = { kotlinx.coroutines.awaitCancellation() }, now = { now }, newId = { "m${ids.incrementAndGet()}" }, scope = scope,
        )
        createList(registry, household, "Empty", null, now, "t2", isTemplate = true)
        val full = model("t1")
        awaitUntil { full.state.value.loaded && full.state.value.targets.isNotEmpty() }

        full.onEvent(ListDetailEvent.AddAll("l1"))
        awaitUntil { full.state.value.status != null }
        assertEquals("Added 2 items to Groceries", full.state.value.status)
        assertEquals(1, kicks.get())

        val empty = model("t2")
        awaitUntil { empty.state.value.loaded && empty.state.value.targets.isNotEmpty() }
        empty.onEvent(ListDetailEvent.AddAll("l1"))
        awaitUntil { empty.state.value.status != null }
        assertEquals(EMPTY_TEMPLATE_MESSAGE, empty.state.value.status)
        assertEquals(1, kicks.get())
        assertEquals(2, registry.liveListItems(household, "l1").size)

        registry.close()
        full.onEvent(ListDetailEvent.AddAll("l1"))
        awaitUntil { full.state.value.addAllFailed }
        assertEquals(1, kicks.get())
    }

    @Test
    fun `a normal list ignores Add all`() = runBlocking<Unit> {
        seed()
        val kicks = AtomicInteger()
        val vm = ListDetailViewModel(
            household, "l1", user, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
            delayer = { kotlinx.coroutines.awaitCancellation() }, now = { now }, newId = { UUID.randomUUID().toString() }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded }

        vm.onEvent(ListDetailEvent.OpenAddAll)
        vm.onEvent(ListDetailEvent.AddAll("l1"))

        assertEquals(false, vm.state.value.addAllOpen)
        assertEquals(0, kicks.get())
        assertEquals(0, registry.liveListItems(household, "l1").size)
    }
}
