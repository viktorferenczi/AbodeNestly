package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.StoreEntity
import com.abodenestly.sync.StoreRecord
import com.abodenestly.sync.StoreRow
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

/** SHOP-05 logic: the pure rules (same tables as web and iOS) and the store and picker writes, on a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoresTest {
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
    fun `sorted orders by name ignoring case, the id breaks ties`() {
        val rows = listOf(StoreRow("b", "test market", 0), StoreRow("a", "Test Market", 0), StoreRow("c", "Aldi", 0), StoreRow("d", "corner Shop", 0))
        assertEquals(listOf("c", "d", "a", "b"), Stores.sorted(rows.shuffled()).map { it.id })
    }

    @Test
    fun `learnedLabel is nothing for 0 and singular for 1`() {
        val table = listOf(-1 to null, 0 to null, 1 to "learned 1 item", 2 to "learned 2 items", 42 to "learned 42 items")
        for ((count, expected) in table) assertEquals("$count", expected, Stores.learnedLabel(count))
    }

    @Test
    fun `validate trims, ignores case, and only looks at the stores it is given`() {
        val stores = listOf(StoreRecord("s1", "Test Market"), StoreRecord("s2", " Corner Shop "))
        // (name, exceptId) -> expected
        val table = listOf(
            ("" to null) to StoreNameProblem.Empty,
            ("   " to null) to StoreNameProblem.Empty,
            ("Test Market" to null) to StoreNameProblem.Duplicate("Test Market"),
            ("  test market  " to null) to StoreNameProblem.Duplicate("Test Market"),
            ("corner shop" to null) to StoreNameProblem.Duplicate("Corner Shop"),
            ("Test Market" to "s1") to null,
            ("Aldi" to null) to null,
            ("Test Marke" to null) to null,
        )
        for ((input, expected) in table) assertEquals("$input", expected, Stores.validate(input.first, stores, input.second))
        assertEquals("Give the store a name", StoreNameProblem.Empty.message)
        assertEquals("You already have Test Market", StoreNameProblem.Duplicate("Test Market").message)
    }

    @Test
    fun `row is a trimmed, live store with placeholder stamps`() {
        val row = Stores.row("s1", household, "  Test Market ", now)
        assertEquals(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("s1"),
                    "household_id" to JsonPrimitive(hid),
                    "name" to JsonPrimitive("Test Market"),
                    "deleted_at" to JsonNull,
                    "created_at" to JsonPrimitive("2026-11-26T08:00:00.000Z"),
                    "updated_at" to JsonPrimitive("2026-11-26T08:00:00.000Z"),
                    "sync_seq" to JsonPrimitive(0),
                ),
            ),
            row,
        )
    }

    // --- writes ---

    private fun viewModel(kicks: AtomicInteger = AtomicInteger(), gate: CompletableDeferred<Unit> = CompletableDeferred()): StoresViewModel {
        val ids = AtomicInteger()
        return StoresViewModel(
            household, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
            delayer = { gate.await() }, now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
    }

    private fun seedStore(id: String, name: String, deletedAt: String? = null) = runBlocking {
        registry.withSearchDao { it.upsertStore(StoreEntity(id, hid, name, deletedAt)) }
    }

    private suspend fun storeOutbox() = registry.outboxForTesting().filter { it.tableName == "store" }

    private suspend fun listOutbox() = registry.outboxForTesting().filter { it.tableName == "shopping_list" }

    private suspend fun liveStores() = registry.observeStores(household).first()

    private suspend fun StoresViewModel.ready(stores: Int): StoresViewModel {
        awaitUntil { state.value.loaded && state.value.stored.size == stores }
        return this
    }

    @Test
    fun `AC4 create trims the name and writes one local store row and one outbox upsert, then kicks`() = runBlocking<Unit> {
        registry.open(household)
        val kicks = AtomicInteger()
        val vm = viewModel(kicks).ready(0)

        val id = vm.create("  Test Market ")

        assertEquals("n1", id)
        assertEquals(listOf("Test Market"), liveStores().map { it.name })
        val queued = storeOutbox().single()
        assertEquals("upsert", queued.op)
        assertEquals("n1", queued.rowId)
        assertEquals("Test Market", Json.parseToJsonElement(queued.data).jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, kicks.get())
        awaitUntil { vm.state.value.rows.map { it.name } == listOf("Test Market") }
        assertEquals(1, storeOutbox().size)
    }

    @Test
    fun `create writes nothing for an empty name or a case-insensitive duplicate, and ignores a deleted store`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        seedStore("s2", "Old Shop", deletedAt = "2026-01-01T00:00:00.000Z")
        val kicks = AtomicInteger()
        val vm = viewModel(kicks).ready(1)

        assertNull(vm.create(""))
        assertNull(vm.create("   "))
        assertNull(vm.create("test market"))
        assertEquals(0, storeOutbox().size)
        assertEquals(0, kicks.get())

        // A deleted store's name is free again.
        assertEquals("n1", vm.create("Old Shop"))
        assertEquals(1, storeOutbox().size)
    }

    @Test
    fun `Delete then Undo writes nothing, even after the window`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, gate).ready(1)
        val row = vm.state.value.rows.single()

        vm.onEvent(StoresEvent.Delete(row))
        assertEquals("Deleted Test Market", vm.state.value.pending.single().message)
        assertTrue(vm.state.value.rows.isEmpty())
        vm.onEvent(StoresEvent.Undo("s1"))
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(listOf("s1"), vm.state.value.rows.map { it.id })
        gate.complete(Unit)
        delay(100)

        assertEquals(0, storeOutbox().size)
        assertEquals(listOf("Test Market"), liveStores().map { it.name })
        assertEquals(0, kicks.get())
    }

    @Test
    fun `the window ending soft-deletes the store with one outbox delete, and a list keeps its store_id`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", "s1", now, "l1")
        val gate = CompletableDeferred<Unit>()
        val kicks = AtomicInteger()
        val vm = viewModel(kicks, gate).ready(1)

        vm.onEvent(StoresEvent.Delete(vm.state.value.rows.single()))
        assertEquals(0, storeOutbox().size)
        gate.complete(Unit)
        awaitUntil { storeOutbox().size == 1 }

        val queued = storeOutbox().single()
        assertEquals("delete", queued.op)
        assertEquals("s1", queued.rowId)
        awaitUntil { liveStores().isEmpty() && vm.state.value.pending.isEmpty() }
        awaitUntil { kicks.get() == 1 }
        assertTrue(vm.state.value.rows.isEmpty())
        val detail = registry.observeListDetail(household, "l1").first()
        assertEquals("s1", detail.list!!.storeId)
        assertNull(detail.storeName)
        assertEquals(1, storeOutbox().size)
    }

    @Test
    fun `Flush writes the pending deletes at once`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        seedStore("s2", "Corner Shop")
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(gate = gate).ready(2)
        vm.state.value.rows.forEach { vm.onEvent(StoresEvent.Delete(it)) }

        vm.onEvent(StoresEvent.Flush)
        awaitUntil { storeOutbox().size == 2 }
        gate.complete(Unit)
        delay(100)

        assertEquals(setOf("s1", "s2"), storeOutbox().map { it.rowId }.toSet())
        assertTrue(storeOutbox().all { it.op == "delete" })
        awaitUntil { vm.state.value.pending.isEmpty() && vm.state.value.rows.isEmpty() }
        assertEquals(2, storeOutbox().size)
    }

    // --- the list's store (the picker's write) ---

    private fun detailViewModel(kicks: AtomicInteger = AtomicInteger()): ListDetailViewModel {
        val ids = AtomicInteger()
        return ListDetailViewModel(
            household, "l1", user, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
            now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
    }

    private suspend fun ListDetailViewModel.ready(stores: Int): ListDetailViewModel {
        awaitUntil { state.value.loaded && state.value.detail.stores.size == stores }
        return this
    }

    @Test
    fun `SetStore writes one shopping_list upsert whose data is only store_id, then kicks`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", null, now, "l1")
        val before = listOutbox().size
        val kicks = AtomicInteger()
        val vm = detailViewModel(kicks).ready(1)
        vm.onEvent(ListDetailEvent.OpenPicker)
        assertTrue(vm.state.value.picker)

        vm.onEvent(ListDetailEvent.SetStore("s1"))
        awaitUntil { listOutbox().size == before + 1 }

        val queued = listOutbox().last()
        assertEquals("upsert", queued.op)
        assertEquals("l1", queued.rowId)
        assertEquals(JsonObject(mapOf("store_id" to JsonPrimitive("s1"))), Json.parseToJsonElement(queued.data).jsonObject)
        awaitUntil { vm.state.value.detail.storeName == "Test Market" && !vm.state.value.picker }
        awaitUntil { kicks.get() == 1 }
        assertEquals("s1", vm.state.value.currentStoreId)
        assertEquals(before + 1, listOutbox().size)
    }

    @Test
    fun `a second Save tap while the store write runs queues no second patch`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", null, now, "l1")
        val before = listOutbox().size
        val vm = detailViewModel().ready(1)
        vm.onEvent(ListDetailEvent.OpenPicker)

        vm.onEvent(ListDetailEvent.SetStore("s1"))
        vm.onEvent(ListDetailEvent.SetStore("s1"))
        awaitUntil { vm.state.value.detail.storeName == "Test Market" && !vm.state.value.picker }
        delay(100)

        assertEquals(before + 1, listOutbox().size)
    }

    @Test
    fun `SetStore null clears the store with a null store_id`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", "s1", now, "l1")
        val before = listOutbox().size
        val vm = detailViewModel().ready(1)
        awaitUntil { vm.state.value.detail.storeName == "Test Market" }

        vm.onEvent(ListDetailEvent.SetStore(null))
        awaitUntil { listOutbox().size == before + 1 }

        assertEquals(JsonObject(mapOf("store_id" to JsonNull)), Json.parseToJsonElement(listOutbox().last().data).jsonObject)
        awaitUntil { vm.state.value.detail.list?.storeId == null }
        assertNull(vm.state.value.detail.storeName)
        assertEquals(before + 1, listOutbox().size)
    }

    @Test
    fun `SetStore with the store the list already has writes nothing and closes the picker`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", "s1", now, "l1")
        val before = listOutbox().size
        val kicks = AtomicInteger()
        val vm = detailViewModel(kicks).ready(1)
        awaitUntil { vm.state.value.detail.storeName == "Test Market" }
        vm.onEvent(ListDetailEvent.OpenPicker)

        vm.onEvent(ListDetailEvent.SetStore("s1"))
        delay(100)

        assertEquals(false, vm.state.value.picker)
        assertEquals(before, listOutbox().size)
        assertEquals(0, kicks.get())
    }

    @Test
    fun `a store deleted elsewhere drops out of currentStoreId, the list keeps its link`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", "s1", now, "l1")
        val vm = detailViewModel().ready(1)
        awaitUntil { vm.state.value.currentStoreId == "s1" }

        seedStore("s1", "Test Market", deletedAt = "2026-11-26T09:00:00.000Z")
        awaitUntil { vm.state.value.detail.stores.isEmpty() }

        assertNull(vm.state.value.currentStoreId)
        assertNull(vm.state.value.detail.storeName)
        assertEquals("s1", vm.state.value.detail.list!!.storeId)
    }

    @Test
    fun `addStore validates against the live stores and queues one store upsert`() = runBlocking<Unit> {
        registry.open(household)
        seedStore("s1", "Test Market")
        createList(registry, household, "Groceries", null, now, "l1")
        val kicks = AtomicInteger()
        val vm = detailViewModel(kicks).ready(1)

        assertNull(vm.addStore("test market"))
        assertNull(vm.addStore(" "))
        assertEquals(0, storeOutbox().size)
        val id = vm.addStore(" Corner Shop ")

        assertEquals("n1", id)
        assertEquals("Corner Shop", Json.parseToJsonElement(storeOutbox().single().data).jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, kicks.get())
        awaitUntil { vm.state.value.detail.stores.map { it.name } == listOf("Corner Shop", "Test Market") }
        assertEquals(1, storeOutbox().size)
    }
}
