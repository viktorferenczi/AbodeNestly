package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.ListItemRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

private fun item(
    id: String, name: String, nameKey: String? = null, listId: String = "other", checked: Boolean = false,
    deletedAt: String? = null, createdAt: String? = null,
) = ListItemRecord(id, listId, name, nameKey, checked, deletedAt, createdAt)

private fun labels(history: List<ListItemRecord>, query: String, listId: String = "l1", limit: Int = SUGGESTION_LIMIT) =
    QuickAdd.suggest(history, query, listId, limit).map { it.label }

/** SHOP-02 logic (table-driven) and the quick-add writes of the detail view model, on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickAddTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val kicks = AtomicInteger()

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    // --- NameKey: the cases of web/src/lib/nameKey.test.ts ---

    @Test
    fun `name_key parity with the SQL and the web twin`() {
        listOf(
            "  Oat   Milk " to "oat milk",
            "MILK" to "milk",
            "\tMilk" to " milk",
            "Milk\t" to "milk ",
            "Oat\t\nMilk" to "oat milk",
            "milk" to "milk",
            "   " to "",
        ).forEach { (input, expected) -> assertEquals("name_key of [$input]", expected, NameKey.make(input)) }
    }

    // --- QuickAdd.suggest ---

    @Test
    fun `AC1 typing mi suggests Milk from another list, whatever the case or the padding`() {
        val history = listOf(item("1", "Milk", "milk", listId = "l2", checked = true, createdAt = "2026-11-01T00:00:00.000Z"))
        assertEquals(listOf("Milk"), labels(history, "mi"))
        assertEquals(listOf("Milk"), labels(history, "MI"))
        assertEquals(listOf("Milk"), labels(history, " mi"))
    }

    @Test
    fun `AC2 one suggestion per key, labelled with the newest casing`() {
        val history = listOf(
            item("1", "milk", "milk", createdAt = "2026-01-01T00:00:00.000Z"),
            item("2", "Milk", "milk", createdAt = "2026-02-01T00:00:00.000Z"),
        )
        assertEquals(listOf(Suggestion("milk", "Milk")), QuickAdd.suggest(history, "mi", "l1"))
    }

    @Test
    fun `a word start matches but the middle of a word does not`() {
        val history = listOf(item("1", "Oat milk", "oat milk"), item("2", "Salami", "salami"))
        assertEquals(listOf("Oat milk"), labels(history, "mi"))
        assertEquals(listOf("Oat milk"), labels(history, "oat m"))
        assertEquals(emptyList<String>(), labels(history, "lami"))
    }

    @Test
    fun `a name unchecked on this list is left out, checked or elsewhere it is not`() {
        val history = listOf(
            item("1", "Milk", "milk", listId = "l1"),
            item("2", "Mint", "mint", listId = "l1", checked = true),
            item("3", "Mango", "mango", listId = "l2"),
        )
        assertEquals(listOf("Mango", "Mint"), labels(history, "m").sorted())
    }

    @Test
    fun `a deleted unchecked row is not history`() {
        val history = listOf(
            item("1", "Typo", "typo", deletedAt = "2026-01-01T00:00:00.000Z"),
            item("2", "Tea", "tea", checked = true, deletedAt = "2026-01-01T00:00:00.000Z"),
        )
        assertEquals(listOf("Tea"), labels(history, "t"))
    }

    @Test
    fun `order is prefix first, then count, then recency, then key`() {
        val history = listOf(
            item("1", "Oat milk", "oat milk", createdAt = "2026-03-01T00:00:00.000Z"),
            item("2", "Oat milk", "oat milk", createdAt = "2026-03-02T00:00:00.000Z"),
            item("3", "Milk", "milk", createdAt = "2026-01-01T00:00:00.000Z"),
            item("4", "Millet", "millet", createdAt = "2026-02-01T00:00:00.000Z"),
            item("5", "Mild salsa", "mild salsa", createdAt = "2026-02-01T00:00:00.000Z"),
        )
        // Prefix matches lead ("oat milk" matches on a word only, so it is last despite its count); equal count, then the
        // newer first (millet and mild salsa before milk); equal recency, then the key.
        assertEquals(listOf("Mild salsa", "Millet", "Milk", "Oat milk"), labels(history, "mil"))
    }

    @Test
    fun `among prefix matches the more frequent name comes before the more recent one`() {
        val history = listOf(
            item("1", "Tea", "tea", createdAt = "2026-01-01T00:00:00.000Z"),
            item("2", "Tea", "tea", createdAt = "2026-01-02T00:00:00.000Z"),
            item("3", "Toast", "toast", createdAt = "2026-06-01T00:00:00.000Z"),
        )
        assertEquals(listOf("Tea", "Toast"), labels(history, "t"))
    }

    @Test
    fun `the list is capped at five`() {
        val history = (1..8).map { item("$it", "Tea $it", "tea $it") }
        assertEquals(5, labels(history, "te").size)
        assertEquals(2, labels(history, "te", limit = 2).size)
    }

    @Test
    fun `a blank query suggests nothing`() {
        val history = listOf(item("1", "Milk", "milk"))
        assertTrue(labels(history, "").isEmpty())
        assertTrue(labels(history, "   ").isEmpty())
    }

    @Test
    fun `a row without a name_key is keyed from its name`() {
        assertEquals(listOf("Oat  Milk"), labels(listOf(item("1", "Oat  Milk", null)), "oat m"))
    }

    // --- QuickAdd.row ---

    @Test
    fun `the quick-add row carries the full manual item`() {
        val row = QuickAdd.row("i1", "l1", "  Oat   Milk ", uuid(7), now)
        assertEquals("Oat   Milk", row["name"]!!.jsonPrimitive.content)
        assertEquals("oat milk", row["name_key"]!!.jsonPrimitive.content)
        assertEquals("manual", row["source"]!!.jsonPrimitive.content)
        assertEquals(uuid(7).toString(), row["created_by"]!!.jsonPrimitive.content)
        assertFalse(row["checked"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, row["deleted_at"])
        assertEquals("2026-11-26T08:00:00.000Z", row["created_at"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, QuickAdd.row("i2", "l1", "x", null, now)["created_by"])
    }

    // --- The view model's writes ---

    private fun model(household: java.util.UUID = this.household, newId: () -> String = { "new" }) = ListDetailViewModel(
        household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
        now = { now }, newId = newId, scope = scope,
    )

    private suspend fun seedHistory() {
        createList(registry, household, "Groceries", null, now, "l1")
        createList(registry, household, "Hardware", null, now, "l2")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("old1", hid, "l2", "milk", nameKey = "milk", checked = true, createdAt = "2026-01-01T00:00:00.000Z"))
            dao.upsertListItem(ListItemEntity("old2", hid, "l2", "Milk", nameKey = "milk", checked = true, createdAt = "2026-02-01T00:00:00.000Z"))
        }
    }

    @Test
    fun `AC2 picking Milk writes a Milk row matched on name_key and queues one upsert without name_key`() = runBlocking<Unit> {
        registry.open(household)
        seedHistory()
        val vm = model()
        awaitUntil { vm.state.value.loaded && vm.state.value.detail.history.size == 2 }
        vm.onEvent(ListDetailEvent.QueryChanged("mi"))
        assertEquals(listOf("Milk"), vm.state.value.suggestions.map { it.label })

        vm.onEvent(ListDetailEvent.Pick(vm.state.value.suggestions.single()))
        awaitUntil { vm.state.value.detail.items.any { it.id == "new" } }

        val local = registry.withSearchDao { it.listItems(hid).first() }.single { it.id == "new" }
        assertEquals("Milk", local.name)
        assertEquals("milk", local.nameKey)
        assertEquals("l1", local.listId)
        awaitUntil { vm.state.value.query == "" && kicks.get() == 1 }
        val queued = registry.outboxForTesting().filter { it.tableName == "list_item" }.single()
        assertEquals("upsert", queued.op)
        assertEquals("new", queued.rowId)
        val data = Json.parseToJsonElement(queued.data).jsonObject
        assertEquals("Milk", data["name"]!!.jsonPrimitive.content)
        assertFalse("name_key" in data)
    }

    @Test
    fun `AC3 a name with no history is added as typed`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        val vm = model()
        awaitUntil { vm.state.value.loaded }
        vm.onEvent(ListDetailEvent.QueryChanged("  Lightbulbs "))
        assertTrue(vm.state.value.suggestions.isEmpty())

        vm.onEvent(ListDetailEvent.Submit)
        awaitUntil { vm.state.value.detail.items.any { it.id == "new" } }

        val local = registry.withSearchDao { it.listItems(hid).first() }.single { it.id == "new" }
        assertEquals("Lightbulbs", local.name)
        assertEquals("manual", local.source)
        assertEquals(uuid(7).toString(), local.createdBy)
        assertEquals(false, local.checked)
        awaitUntil { vm.state.value.query == "" && kicks.get() == 1 }
        assertEquals(listOf("Lightbulbs"), vm.state.value.unchecked.map { it.name })
    }

    @Test
    fun `Submit adds the typed text even when a suggestion matches it`() = runBlocking<Unit> {
        registry.open(household)
        seedHistory()
        val vm = model()
        awaitUntil { vm.state.value.detail.history.size == 2 }
        vm.onEvent(ListDetailEvent.QueryChanged("mi"))
        vm.onEvent(ListDetailEvent.Submit)
        awaitUntil { vm.state.value.detail.items.any { it.id == "new" } }
        awaitUntil { vm.state.value.query == "" && kicks.get() == 1 }
        assertEquals(listOf("mi"), vm.state.value.detail.items.map { it.name })
    }

    @Test
    fun `a blank submit writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        val before = registry.outboxForTesting().size
        val vm = model()
        awaitUntil { vm.state.value.loaded }
        vm.onEvent(ListDetailEvent.QueryChanged("   "))
        vm.onEvent(ListDetailEvent.Submit)
        assertEquals(before, registry.outboxForTesting().size)
        assertEquals(0, kicks.get())
        assertTrue(vm.state.value.detail.items.isEmpty())
    }

    @Test
    fun `a write that cannot be stored shows the error and keeps the text`() = runBlocking<Unit> {
        registry.open(household)
        // Another household's model: its database is not open, so the write throws.
        val vm = model(household = uuid(2))
        vm.onEvent(ListDetailEvent.QueryChanged("Lightbulbs"))
        vm.onEvent(ListDetailEvent.Submit)
        awaitUntil { vm.state.value.error != null }
        assertEquals(SAVE_ERROR, vm.state.value.error)
        assertEquals("Lightbulbs", vm.state.value.query)
        assertEquals(0, kicks.get())
        vm.onEvent(ListDetailEvent.ErrorShown)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `unchecked rows are oldest first and checked rows newest first`() = runBlocking<Unit> {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("b", hid, "l1", "Second", checked = false, createdAt = "2026-02-01T00:00:00.000Z"))
            dao.upsertListItem(ListItemEntity("a", hid, "l1", "First", checked = false, createdAt = "2026-01-01T00:00:00.000Z"))
            dao.upsertListItem(ListItemEntity("c", hid, "l1", "Bought early", checked = true, createdAt = "2026-01-01T00:00:00.000Z"))
            dao.upsertListItem(ListItemEntity("d", hid, "l1", "Bought late", checked = true, createdAt = "2026-03-01T00:00:00.000Z"))
        }
        val vm = model()
        awaitUntil { vm.state.value.detail.items.size == 4 }
        assertEquals(listOf("First", "Second"), vm.state.value.unchecked.map { it.name })
        assertEquals(listOf("Bought late", "Bought early"), vm.state.value.checked.map { it.name })
    }
}
