package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/** SHOP-15: the assistant shortcut's rules and replies. Twin: iOS `AssistantShortcutTests`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantShortcutTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val user = uuid(7)
    private val now = Instant.parse("2026-11-28T08:00:00Z")

    @After
    fun tearDown() = runBlocking { registry.close() }

    private suspend fun add(text: String, id: java.util.UUID? = household): AssistantResult =
        AssistantShortcut.add(text, id, user, registry, now) { "i1" }

    private suspend fun itemsOn(listId: String) = registry.observeListDetail(household, listId).first().items

    private suspend fun listItemOutbox() = registry.outboxForTesting().filter { it.tableName == "list_item" }

    private fun record(id: String, name: String, createdAt: String? = null, template: Boolean = false, archivedAt: String? = null) =
        ListRecord(id = id, name = name, storeId = null, isTemplate = template, archivedAt = archivedAt, createdAt = createdAt)

    @Test
    fun `SHOP-15 AC1 add milk writes one item on Groceries and one outbox row`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true, pulledList("l1", "Groceries"))

        val result = add("milk")

        assertEquals(AssistantResult.Added("Milk", "Groceries"), result)
        val items = itemsOn("l1")
        assertEquals(listOf("Milk"), items.map { it.name })
        assertNull(items.single().quantity)
        val queued = listItemOutbox()
        assertEquals(1, queued.size)
        assertEquals("i1", queued.single().rowId)
    }

    @Test
    fun `SHOP-15 AC1 quantity and unit are kept`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true, pulledList("l1", "Groceries"))

        val result = add("2 litres of milk")

        assertEquals(AssistantResult.Added("Milk", "Groceries"), result)
        val item = itemsOn("l1").single()
        assertEquals(2.0, item.quantity!!, 0.0)
        assertEquals("l", item.unit)
    }

    @Test
    fun `SHOP-15 AC2 a blank phrase writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true, pulledList("l1", "Groceries"))

        for (text in listOf("", "   ", "\n")) assertEquals(text, AssistantResult.NoItem, add(text))

        assertEquals(emptyList<Any>(), itemsOn("l1"))
        assertEquals(0, listItemOutbox().size)
    }

    @Test
    fun `SHOP-15 AC2 a phrase with only a quantity writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true, pulledList("l1", "Groceries"))

        assertEquals(AssistantResult.NoItem, add("2 litres"))

        assertEquals(emptyList<Any>(), itemsOn("l1"))
        assertEquals(0, listItemOutbox().size)
    }

    @Test
    fun `SHOP-15 AC4 shopping off writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, false, pulledList("l1", "Groceries"))

        assertEquals(AssistantResult.ShoppingOff, add("milk"))

        assertEquals(emptyList<Any>(), itemsOn("l1"))
        assertEquals(0, listItemOutbox().size)
    }

    @Test
    fun `SHOP-15 a household row that is not pulled yet is trusted`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, null, pulledList("l1", "Groceries"))

        assertEquals(AssistantResult.Added("Milk", "Groceries"), add("milk"))
        assertEquals(1, listItemOutbox().size)
    }

    @Test
    fun `SHOP-15 the list named Groceries in any case wins over an older list`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(
            household, true,
            pulledList("l1", "Party", createdAt = "2025-01-01T00:00:00.000Z"),
            pulledList("l2", " groceries ", createdAt = "2026-02-01T00:00:00.000Z"),
        )

        assertEquals(AssistantResult.Added("Milk", "groceries"), add("milk"))
        assertEquals(listOf("Milk"), itemsOn("l2").map { it.name })
        assertEquals(emptyList<Any>(), itemsOn("l1"))
    }

    @Test
    fun `SHOP-15 without Groceries the oldest active non-template list gets the item`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(
            household, true,
            pulledList("l1", "Old template", createdAt = "2024-01-01T00:00:00.000Z", template = true),
            pulledList("l2", "Archived", createdAt = "2024-02-01T00:00:00.000Z", archivedAt = "2025-01-01T00:00:00.000Z"),
            pulledList("l3", "Party", createdAt = "2025-01-01T00:00:00.000Z"),
            pulledList("l4", "Hardware", createdAt = "2026-01-01T00:00:00.000Z"),
        )

        assertEquals(AssistantResult.Added("Milk", "Party"), add("milk"))
        assertEquals(listOf("Milk"), itemsOn("l3").map { it.name })
    }

    @Test
    fun `SHOP-15 pickList never takes an archived list or a template, even one named Groceries`() {
        val lists = listOf(
            record("a", "Groceries", template = true),
            record("b", "Groceries", archivedAt = "2026-01-01T00:00:00.000Z"),
        )
        assertNull(AssistantShortcut.pickList(lists))
        assertEquals("c", AssistantShortcut.pickList(lists + record("c", "Weekly", createdAt = "2026-01-01T00:00:00.000Z"))?.id)
    }

    @Test
    fun `SHOP-15 pickList breaks a creation time tie by id`() {
        val lists = listOf(record("b", "Two", "2026-01-01T00:00:00.000Z"), record("a", "One", "2026-01-01T00:00:00.000Z"))
        assertEquals("a", AssistantShortcut.pickList(lists)?.id)
    }

    @Test
    fun `SHOP-15 no household answers with the sign in reply and writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true, pulledList("l1", "Groceries"))

        assertEquals(AssistantResult.NoHousehold, add("milk", id = null))

        assertEquals(0, listItemOutbox().size)
    }

    @Test
    fun `SHOP-15 a blank phrase is reported before a missing household`() = runBlocking<Unit> {
        assertEquals(AssistantResult.NoItem, add("", id = null))
    }

    @Test
    fun `SHOP-15 no lists answers with the no list reply and writes nothing`() = runBlocking<Unit> {
        registry.open(household)
        registry.pullHousehold(household, true)

        assertEquals(AssistantResult.NoList, add("milk"))

        assertEquals(0, listItemOutbox().size)
    }

    @Test
    fun `SHOP-15 a failing database answers with the failed reply`() = runBlocking<Unit> {
        val failing = object : DatabaseRegistry(context) {
            override suspend fun open(id: java.util.UUID, isCurrent: () -> Boolean): Boolean = throw IllegalStateException("disk full")
        }

        assertEquals(AssistantResult.Failed, AssistantShortcut.add("milk", household, user, failing, now) { "i1" })
    }

    @Test
    fun `SHOP-15 every reply uses the same words as iOS`() {
        assertEquals("Added Milk to Groceries.", AssistantResult.Added("Milk", "Groceries").reply)
        assertEquals("I didn't catch an item. Nothing was added.", AssistantResult.NoItem.reply)
        assertEquals("Shopping is turned off for this household. Nothing was added.", AssistantResult.ShoppingOff.reply)
        assertEquals("Open AbodeNestly and sign in first.", AssistantResult.NoHousehold.reply)
        assertEquals("There's no shopping list to add to.", AssistantResult.NoList.reply)
        assertEquals("Couldn't add that. Try again in the app.", AssistantResult.Failed.reply)
    }
}
