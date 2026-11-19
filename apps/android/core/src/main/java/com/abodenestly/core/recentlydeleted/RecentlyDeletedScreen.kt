package com.abodenestly.core.recentlydeleted

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.SyncEngine
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import java.util.UUID

/**
 * DESIGN B11 Recently deleted. States: empty, populated (a segment per enabled module, rows with Restore and days left).
 * [modules] are the enabled ones; segments for the others never show.
 */
@Composable
fun RecentlyDeletedScreen(
    householdId: UUID,
    modules: Set<SearchModule>,
    database: DatabaseRegistry,
    sync: SyncEngine,
    modifier: Modifier = Modifier,
) {
    // Keyed by household and flags: a module switched off while this screen is open rebuilds the model with the smaller set.
    val vm: RecentlyDeletedViewModel = viewModel(key = "deleted.$householdId.${modules.sortedBy { it.ordinal }}") {
        RecentlyDeletedViewModel(
            database.deletedRows(householdId, modules), modules,
            restore = { table, rowId, now -> database.restore(householdId, table, rowId, now) }, sync,
        )
    }
    val ui by vm.state.collectAsState()
    RecentlyDeletedContent(ui, vm::onEvent, modifier)
}

private fun SearchModule.label() = key.replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentlyDeletedContent(ui: RecentlyDeletedUiState, onEvent: (RecentlyDeletedEvent) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Column(modifier.fillMaxSize().padding(Tokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recently deleted", style = MaterialTheme.typography.titleLarge, color = tokens.text, modifier = Modifier.semantics { heading() })
        if (!ui.loaded) return@Column
        if (ui.isEmpty) {
            Text("Nothing deleted in the last 30 days.", color = tokens.text2, modifier = Modifier.testTag("deleted.empty"))
            return@Column
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ui.sections.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = section.module == ui.selected,
                    onClick = { onEvent(RecentlyDeletedEvent.Select(section.module)) },
                    shape = SegmentedButtonDefaults.itemShape(index, ui.sections.size),
                    modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("deleted.segment.${section.module.key}"),
                ) { Text(section.module.label()) }
            }
        }
        val selected = ui.selected
        if (ui.items.isEmpty() && selected != null) {
            Text("Nothing deleted in ${selected.label()}.", color = tokens.text2, modifier = Modifier.testTag("deleted.segment.empty"))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.items, key = { it.key }) { DeletedRowView(it, onEvent) }
            }
        }
    }
}

@Composable
private fun DeletedRowView(item: DeletedItem, onEvent: (RecentlyDeletedEvent) -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).padding(vertical = 4.dp).testTag("deleted.row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, color = tokens.text)
            Text(if (item.daysLeft == 1) "1 day left" else "${item.daysLeft} days left", color = tokens.text2, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { onEvent(RecentlyDeletedEvent.Restore(item)) },
            modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("deleted.restore").semantics { contentDescription = "Restore ${item.name}" },
        ) { Text("Restore") }
    }
}
