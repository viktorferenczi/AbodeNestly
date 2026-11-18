package com.abodenestly.core.search

import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.home.HomeModules
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.SearchRow
import java.util.Locale

/** What a tapped result points at. Android has no entity screens yet, so the app only uses [module] (switches tab). */
data class SearchTarget(val module: SearchModule, val table: String, val id: String, val listId: String? = null)

/** One result row: the name plus what kind of record it is ("List item", "Store", …). */
data class SearchResult(val target: SearchTarget, val name: String, val kind: String)

data class SearchSection(val module: SearchModule, val rows: List<SearchResult>)

enum class SearchState { EmptyQuery, NoResults, Results }

val SearchModule.tab: AppTab
    get() = when (this) {
        SearchModule.Shopping -> AppTab.Shopping
        SearchModule.Pantry -> AppTab.Pantry
        SearchModule.Maintenance -> AppTab.Maintenance
    }

/** The enabled modules as search modules. */
fun HomeModules.searchModules(): Set<SearchModule> = buildSet {
    if (shopping) add(SearchModule.Shopping)
    if (pantry) add(SearchModule.Pantry)
    if (maintenance) add(SearchModule.Maintenance)
}

/** DESIGN B8 / CORE-09. Twins: iOS `Search`, web `Search.ts`. */
object Search {
    const val DEBOUNCE_MS = 150L

    private val KINDS = mapOf(
        "list_item" to ("List item" to SearchModule.Shopping),
        "store" to ("Store" to SearchModule.Shopping),
        "pantry_item" to ("Pantry item" to SearchModule.Pantry),
        "location" to ("Location" to SearchModule.Pantry),
        "recipe" to ("Recipe" to SearchModule.Pantry),
        "asset" to ("Asset" to SearchModule.Maintenance),
        "maint_task" to ("Task" to SearchModule.Maintenance),
        "contractor" to ("Contractor" to SearchModule.Maintenance),
    )

    /**
     * Rows whose name contains the trimmed [query] (case-insensitive, locale-insensitive), grouped by module in the
     * order Shopping, Pantry, Maintenance. Rows of a module not in [modules] are dropped here too, so a query that
     * saw them (stale emission, module just switched off) can never show them (AC4). A blank query matches nothing.
     */
    fun group(rows: List<SearchRow>, query: String, modules: Set<SearchModule>): List<SearchSection> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return SearchModule.entries.filter { it in modules }.mapNotNull { module ->
            val matches = rows.mapNotNull { row ->
                val (kind, owner) = KINDS[row.table] ?: return@mapNotNull null
                if (owner != module || row.deletedAt != null) return@mapNotNull null
                val name = fold(row.name)
                if (!name.contains(q)) return@mapNotNull null
                Triple(name.startsWith(q), name, SearchResult(SearchTarget(module, row.table, row.id, row.listId), row.name, kind))
            }
            if (matches.isEmpty()) null
            else SearchSection(
                module,
                matches
                    .sortedWith(compareByDescending<Triple<Boolean, String, SearchResult>> { it.first }.thenBy { it.second }.thenBy { it.third.target.id })
                    .map { it.third },
            )
        }
    }

    /** [debouncedQuery] is the query after the debounce, not the field text. */
    fun state(debouncedQuery: String, sections: List<SearchSection>): SearchState = when {
        debouncedQuery.isBlank() -> SearchState.EmptyQuery
        sections.isEmpty() -> SearchState.NoResults
        else -> SearchState.Results
    }

    private fun fold(s: String) = s.lowercase(Locale.ROOT)
}
