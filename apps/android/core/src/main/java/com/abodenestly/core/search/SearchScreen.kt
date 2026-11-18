package com.abodenestly.core.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SearchModule
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import java.util.UUID

/**
 * DESIGN B8 Search. States: empty-query (recent searches), no-results, results grouped by module. A row raises
 * [SearchEvent.Open]; the app owns navigation. [modules] are the enabled ones; sections for the others never show.
 */
@Composable
fun SearchScreen(
    householdId: UUID,
    modules: Set<SearchModule>,
    database: DatabaseRegistry,
    recents: RecentSearchStore,
    onNavigate: (SearchEvent.Open) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed by household and flags: a module switched off while Search is open rebuilds the model with the smaller set.
    val vm: SearchViewModel = viewModel(key = "search.$householdId.${modules.sortedBy { it.ordinal }}") {
        SearchViewModel(householdId, modules, { q, m -> database.searchNames(householdId, q, m) }, recents, onNavigate)
    }
    SideEffect { vm.navigate = onNavigate }
    val ui by vm.state.collectAsState()
    SearchContent(ui, vm::onEvent, modifier)
}

@Composable
internal fun SearchContent(ui: SearchUiState, onEvent: (SearchEvent) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxSize().imePadding().padding(Tokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = ui.query,
            onValueChange = { onEvent(SearchEvent.QueryChanged(it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).focusRequester(focus).testTag("search.field"),
            label = { Text("Search") },
            placeholder = { Text("Search your household") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onEvent(SearchEvent.Submit) }),
        )
        when (ui.state) {
            SearchState.EmptyQuery -> Column(Modifier.fillMaxWidth().testTag("search.empty"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (ui.recents.isEmpty()) {
                    Text("Search lists, pantry and maintenance by name.", color = tokens.text2)
                } else {
                    Text("Recent searches", style = MaterialTheme.typography.titleSmall, color = tokens.text, modifier = Modifier.semantics { heading() })
                    ui.recents.forEach { recent ->
                        Text(
                            recent,
                            color = tokens.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.MinTouchTarget)
                                .clickable(role = Role.Button) { onEvent(SearchEvent.PickRecent(recent)) }
                                .padding(vertical = 12.dp)
                                .testTag("search.recent"),
                        )
                    }
                }
            }
            SearchState.NoResults -> Text(
                "No matches for \"${ui.debounced.trim()}\".", color = tokens.text2, modifier = Modifier.testTag("search.noResults"),
            )
            SearchState.Results -> LazyColumn(Modifier.fillMaxSize().testTag("search.results")) {
                ui.sections.forEach { section ->
                    item(key = "header.${section.module.key}") { SectionHeader(section.module) }
                    items(section.rows, key = { "${it.target.table}.${it.target.id}" }) { ResultRow(it, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(module: SearchModule) {
    val tokens = LocalTokens.current
    val color: Color = when (module) {
        SearchModule.Shopping -> tokens.shopping
        SearchModule.Pantry -> tokens.pantry
        SearchModule.Maintenance -> tokens.maintenance
    }
    Text(
        module.key.replaceFirstChar { it.uppercase() },
        color = color,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).testTag("search.section.${module.key}").semantics { heading() },
    )
}

@Composable
private fun ResultRow(result: SearchResult, onEvent: (SearchEvent) -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.MinTouchTarget)
            .clickable(role = Role.Button) { onEvent(SearchEvent.Open(result.target)) }
            .padding(vertical = 8.dp)
            .testTag("search.row")
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(result.name, color = tokens.text, modifier = Modifier.weight(1f))
        Text(result.kind, color = tokens.text2, style = MaterialTheme.typography.bodySmall)
    }
}
