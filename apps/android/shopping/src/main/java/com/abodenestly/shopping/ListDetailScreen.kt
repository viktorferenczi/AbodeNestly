package com.abodenestly.shopping

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.ListRow
import com.abodenestly.ui.ListRowChip
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.QuickAddBar
import com.abodenestly.ui.Tokens

/** DESIGN C2 list detail, SHOP-02 slice: the items, the quick-add bar with autocomplete, and Back to the overview. */
@Composable
fun ListDetailScreen(viewModel: ListDetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by viewModel.state.collectAsState()
    ListDetailContent(ui, viewModel::onEvent, onBack, modifier)
}

/** DESIGN C2 states `empty`, `populated`, `offline`, plus "This list is gone". Pure of the view model, so tests drive it with plain state. */
@Composable
internal fun ListDetailContent(ui: ListDetailUiState, onEvent: (ListDetailEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val focus = remember { FocusRequester() }
    var checkedOpen by rememberSaveable { mutableStateOf(false) }
    val unchecked = ui.unchecked
    val checked = ui.checked
    val isEmpty = ui.loaded && !ui.isGone && unchecked.isEmpty() && checked.isEmpty()
    // `empty` opens with the bar focused (DESIGN C2).
    LaunchedEffect(isEmpty) {
        if (isEmpty) focus.tryFocus()
    }
    Column(modifier.fillMaxSize().background(tokens.bg).testTag("list.screen")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("list.back")) {
                Text("Back", color = tokens.accent)
            }
            ui.detail.list?.let { list ->
                Text(
                    list.name, style = MaterialTheme.typography.headlineSmall, color = tokens.text,
                    modifier = Modifier.testTag("list.title").semantics { heading() },
                )
                // Display only until SHOP-05-android adds the picker.
                ui.detail.storeName?.let { ListRowChip(it, Modifier.padding(top = 4.dp).testTag("list.store")) }
            }
            ui.error?.let {
                Text(
                    it, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("list.error")
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            if (!ui.online) {
                OfflineBanner("You're offline. Changes will sync when you reconnect.", Modifier.padding(top = 8.dp))
            }
        }
        when {
            !ui.loaded -> Box(Modifier.weight(1f))
            ui.isGone -> Box(Modifier.weight(1f).fillMaxWidth().padding(Tokens.ScreenPadding)) {
                Text(
                    "This list is gone", style = MaterialTheme.typography.titleMedium, color = tokens.text2,
                    modifier = Modifier.testTag("list.gone").semantics { heading() },
                )
            }
            isEmpty -> Box(Modifier.weight(1f).fillMaxWidth().padding(Tokens.ScreenPadding), contentAlignment = Alignment.Center) {
                Text("Add your first item", style = MaterialTheme.typography.bodyLarge, color = tokens.text2, modifier = Modifier.testTag("list.empty"))
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("list.items")) {
                items(unchecked, key = { it.id }) { item ->
                    ListRow(title = item.name, modifier = Modifier.testTag("list.row.${item.name}"))
                }
                if (checked.isNotEmpty()) {
                    item(key = "h.checked") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                                .clickable(role = Role.Button) { checkedOpen = !checkedOpen }
                                .padding(horizontal = Tokens.ScreenPadding)
                                .testTag("list.checked")
                                .semantics { stateDescription = if (checkedOpen) "Expanded" else "Collapsed" },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Checked (${checked.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = tokens.text2)
                            Text(if (checkedOpen) "▾" else "▸", color = tokens.text2)
                        }
                    }
                    if (checkedOpen) {
                        items(checked, key = { it.id }) { item ->
                            // Read only until SHOP-04-android adds the checkbox.
                            ListRow(title = item.name, modifier = Modifier.alpha(0.6f).testTag("list.checked.row.${item.name}"))
                        }
                    }
                }
            }
        }
        if (!ui.isGone) {
            val suggestions = ui.suggestions
            val submit: () -> Unit = {
                onEvent(ListDetailEvent.Submit)
                focus.tryFocus()
            }
            QuickAddBar(
                text = ui.query,
                onTextChange = { onEvent(ListDetailEvent.QueryChanged(it)) },
                suggestions = suggestions.map { it.key to it.label },
                onPick = { key ->
                    suggestions.firstOrNull { it.key == key }?.let { onEvent(ListDetailEvent.Pick(it)) }
                    focus.tryFocus()
                },
                onSubmit = submit,
                focusRequester = focus,
                modifier = Modifier.imePadding(),
            )
        }
    }
}

/** The field keeps focus after each add. A bar that is not attached yet (first frame) has nothing to focus. */
private fun FocusRequester.tryFocus() {
    try {
        requestFocus()
    } catch (_: Exception) {
    }
}
