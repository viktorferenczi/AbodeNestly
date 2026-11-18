package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.search.InMemoryRecentSearchStore
import com.abodenestly.core.search.RecentSearches
import com.abodenestly.core.search.Search
import com.abodenestly.core.search.SearchState
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.MaintTaskEntity
import com.abodenestly.sync.PantryItemEntity
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.SearchRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val ALL = SearchModule.entries.toSet()

private fun row(table: String, name: String, id: String = name, deletedAt: String? = null) = SearchRow(table, id, name, deletedAt = deletedAt)

/** CORE-09 pure logic: grouping, ordering, module filtering, state rule, recents. */
class SearchTest {
    private fun names(rows: List<SearchRow>, query: String, modules: Set<SearchModule> = ALL) =
        Search.group(rows, query, modules).map { s -> s.module to s.rows.map { it.name } }

    @Test
    fun `CORE-09 blank and whitespace queries match nothing`() {
        val rows = listOf(row("list_item", "Milk"))
        for (q in listOf("", " ", "\t\n")) assertTrue(q, Search.group(rows, q, ALL).isEmpty())
    }

    @Test
    fun `CORE-09 matching is a trimmed, case-insensitive substring`() {
        val rows = listOf(row("list_item", "Oat MILK"), row("store", "Tesco"))
        assertEquals(listOf(SearchModule.Shopping to listOf("Oat MILK")), names(rows, "  milk "))
        assertEquals(listOf(SearchModule.Shopping to listOf("Oat MILK")), names(rows, "at mi"))
    }

    @Test
    fun `CORE-09 AC3 a list item and a pantry item with the same name group under Shopping then Pantry`() {
        val rows = listOf(row("pantry_item", "Milk", "p"), row("list_item", "Milk", "l"))
        val sections = Search.group(rows, "milk", ALL)
        assertEquals(listOf(SearchModule.Shopping, SearchModule.Pantry), sections.map { it.module })
        assertEquals(listOf("List item"), sections[0].rows.map { it.kind })
        assertEquals(listOf("Pantry item"), sections[1].rows.map { it.kind })
        assertEquals("l", sections[0].rows[0].target.id)
    }

    @Test
    fun `CORE-09 every table maps to its module and kind label`() {
        val rows = listOf(
            row("list_item", "x1"), row("store", "x2"), row("pantry_item", "x3"), row("location", "x4"), row("recipe", "x5"),
            row("asset", "x6"), row("maint_task", "x7"), row("contractor", "x8"), row("unknown", "x9"),
        )
        val kinds = Search.group(rows, "x", ALL).map { s -> s.module to s.rows.map { it.kind } }
        assertEquals(
            listOf(
                SearchModule.Shopping to listOf("List item", "Store"),
                SearchModule.Pantry to listOf("Pantry item", "Location", "Recipe"),
                SearchModule.Maintenance to listOf("Asset", "Task", "Contractor"),
            ),
            kinds,
        )
    }

    @Test
    fun `CORE-09 AC4 rows of a disabled module are dropped even when they are passed in`() {
        val rows = listOf(row("list_item", "Milk"), row("pantry_item", "Milk"), row("asset", "Milk boiler"))
        assertEquals(
            listOf(SearchModule.Shopping to listOf("Milk"), SearchModule.Maintenance to listOf("Milk boiler")),
            names(rows, "milk", setOf(SearchModule.Shopping, SearchModule.Maintenance)),
        )
        assertTrue(names(rows, "milk", emptySet()).isEmpty())
    }

    @Test
    fun `CORE-09 soft-deleted rows are dropped`() {
        val rows = listOf(row("list_item", "Milk", deletedAt = "2026-01-01T00:00:00.000Z"), row("list_item", "Milky"))
        assertEquals(listOf(SearchModule.Shopping to listOf("Milky")), names(rows, "milk"))
    }

    @Test
    fun `CORE-09 prefix matches come first, then alphabetical`() {
        val rows = listOf(row("list_item", "Soy milk"), row("list_item", "milk"), row("list_item", "Almond milk"), row("list_item", "Milkshake"))
        assertEquals(listOf("milk", "Milkshake", "Almond milk", "Soy milk"), names(rows, "milk")[0].second)
    }

    @Test
    fun `CORE-09 matching ignores the device locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf(SearchModule.Shopping to listOf("TITLE")), names(listOf(row("list_item", "TITLE")), "title"))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test
    fun `CORE-09 state follows the debounced query and the sections`() {
        val one = Search.group(listOf(row("list_item", "Milk")), "milk", ALL)
        assertEquals(SearchState.EmptyQuery, Search.state("", emptyList()))
        assertEquals(SearchState.EmptyQuery, Search.state("  ", one))
        assertEquals(SearchState.NoResults, Search.state("zzz", emptyList()))
        assertEquals(SearchState.Results, Search.state("milk", one))
    }

    @Test
    fun `CORE-09 recent searches keep 8, newest first, deduplicated ignoring case`() {
        var recents = emptyList<String>()
        for (q in listOf("a", "b", "c", "d", "e", "f", "g", "h", "i")) recents = RecentSearches.add(recents, q)
        assertEquals(listOf("i", "h", "g", "f", "e", "d", "c", "b"), recents)
        assertEquals(listOf("B", "i", "h", "g", "f", "e", "d", "c"), RecentSearches.add(recents, " B "))
        assertEquals(recents, RecentSearches.add(recents, "   "))
    }

    @Test
    fun `CORE-09 the in-memory store is per household`() = runBlocking {
        val store = InMemoryRecentSearchStore()
        store.record(id(1), "milk")
        assertEquals(listOf("milk"), store.read(id(1)))
        assertTrue(store.read(id(2)).isEmpty())
    }
}

/** CORE-09 local reads: the registry only queries enabled modules' tables and never returns tombstones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchRegistryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun search(query: String, modules: Set<SearchModule> = ALL) =
        runBlocking { withTimeout(5_000) { registry.searchNames(household, query, modules).first() } }

    private fun seed() = runBlocking {
        registry.open(household)
        registry.withSearchDao { dao ->
            dao.upsertListItem(ListItemEntity("l1", household.toString(), "list1", "Milk"))
            dao.upsertListItem(ListItemEntity("l2", household.toString(), "list1", "Milk (gone)", deletedAt = "2026-01-01T00:00:00.000Z"))
            dao.upsertListItem(ListItemEntity("l3", id(2).toString(), "list9", "Milk of another household"))
            dao.upsertPantryItem(PantryItemEntity("p1", household.toString(), "Milk powder"))
            dao.upsertMaintTask(MaintTaskEntity("m1", household.toString(), "Descale kettle (milk frother)"))
        }
    }

    @Test
    fun `CORE-09 reads live rows of this household from the enabled modules only, keeping the list id`() {
        seed()
        val all = search("milk")
        assertEquals(listOf("l1", "m1", "p1"), all.map { it.id }.sorted())
        assertEquals("list1", all.first { it.id == "l1" }.listId)
        assertEquals(listOf("l1"), search("milk", setOf(SearchModule.Shopping)).map { it.id })
        assertTrue(search("milk", emptySet()).isEmpty())
    }

    @Test
    fun `CORE-09 percent and underscore in the query are literal`() {
        seed()
        assertTrue(search("%").isEmpty())
        assertTrue(search("m_lk").isEmpty())
    }

    @Test
    fun `CORE-09 a blank query reads nothing`() {
        seed()
        assertTrue(search("  ").isEmpty())
    }
}
