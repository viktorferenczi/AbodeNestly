package com.abodenestly.core.search

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.SearchRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What Search shows. [query] is the field text; [debounced] the query the results belong to (150 ms behind it).
 * [state] follows [debounced], so typing does not flash the empty or no-results state.
 */
data class SearchUiState(
    val query: String = "",
    val debounced: String = "",
    val sections: List<SearchSection> = emptyList(),
    val recents: List<String> = emptyList(),
) {
    val state: SearchState get() = Search.state(debounced, sections)
}

sealed interface SearchEvent {
    data class QueryChanged(val text: String) : SearchEvent
    data object Submit : SearchEvent
    /** A result row was tapped; also what the app receives to navigate. */
    data class Open(val target: SearchTarget) : SearchEvent
    data class PickRecent(val text: String) : SearchEvent
}

/**
 * DESIGN B8. Local database only, so it works offline (AC5). Port of iOS `SearchModel`.
 *
 * @param rows the matching rows of [modules]' tables (`DatabaseRegistry.searchNames`), already limited to enabled modules.
 * @param modules enabled modules; sections are filtered again here (AC4).
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val householdId: UUID,
    private val modules: Set<SearchModule>,
    rows: (String, Set<SearchModule>) -> Flow<List<SearchRow>>,
    private val recents: RecentSearchStore,
    var navigate: (SearchEvent.Open) -> Unit = {},
    debounceMs: Long = Search.DEBOUNCE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private val queryFlow = MutableStateFlow("")

    init {
        scope.launch { mutableState.update { it.copy(recents = recents.read(householdId)) } }
        scope.launch {
            queryFlow
                .debounce(debounceMs)
                .distinctUntilChanged()
                .flatMapLatest { q ->
                    if (q.isBlank()) flowOf(q to emptyList())
                    else rows(q, modules).map { q to Search.group(it, q, modules) }
                }
                .collect { (q, sections) -> mutableState.update { it.copy(debounced = q, sections = sections) } }
        }
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> setQuery(event.text)
            is SearchEvent.PickRecent -> setQuery(event.text)
            SearchEvent.Submit -> if (mutableState.value.sections.isNotEmpty()) remember()
            is SearchEvent.Open -> {
                remember()
                navigate(event)
            }
        }
    }

    private fun setQuery(text: String) {
        mutableState.update { it.copy(query = text) }
        queryFlow.value = text
    }

    private fun remember() {
        val q = mutableState.value.debounced
        if (q.isBlank()) return
        scope.launch { mutableState.update { it.copy(recents = recents.record(householdId, q)) } }
    }
}
