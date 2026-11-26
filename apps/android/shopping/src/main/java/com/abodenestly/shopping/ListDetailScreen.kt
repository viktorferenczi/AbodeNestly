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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.ui.ListRow
import com.abodenestly.ui.ListRowChip
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.QuickAddBar
import com.abodenestly.ui.Tokens
import com.abodenestly.ui.UndoToast

/**
 * DESIGN C2 list detail and its C3 item sheet: the items with their checkboxes, the quick-add bar with autocomplete, and
 * Back to the overview. Leaving the screen or the app writes the deletes, check-offs and clears still inside their undo window.
 */
@Composable
fun ListDetailScreen(viewModel: ListDetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ListDetailScreenHost(viewModel, onBack, modifier) { isNew, initial, history, failed, onSave, onDelete, onClose ->
        ItemSheet(isNew, initial, history, failed, onSave, onDelete, onClose)
    }
}

@Composable
internal fun ListDetailScreenHost(viewModel: ListDetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, sheet: ItemSheetSlot) {
    val ui by viewModel.state.collectAsState()
    // A configuration change (rotation, font scale) is not leaving: the same view model keeps its timers, so the undo
    // window stays open. A real stop or leaving the list writes what is waiting.
    val activity = LocalContext.current.findActivity()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, LocalLifecycleOwner.current) {
        if (activity?.isChangingConfigurations != true) viewModel.onEvent(ListDetailEvent.Flush)
    }
    DisposableEffect(viewModel) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.onEvent(ListDetailEvent.Flush) } }
    ListDetailContent(ui, viewModel::onEvent, onBack, modifier)
    val open = ui.sheet
    if (open != null) {
        val item = ui.editing
        if (open is SheetState.New || item != null) {
            key(open) {
                sheet(
                    open is SheetState.New,
                    when (open) {
                        is SheetState.New -> ItemForm(name = open.prefill)
                        is SheetState.Edit -> ItemForm.of(item!!)
                    },
                    ui.detail.history,
                    ui.saveFailed,
                    { form -> viewModel.onEvent(ListDetailEvent.SaveSheet(form)) },
                    if (open is SheetState.Edit) ({ viewModel.onEvent(ListDetailEvent.Delete(open.itemId)) }) else null,
                    { viewModel.onEvent(ListDetailEvent.CloseSheet) },
                )
            }
        }
    }
}

/** DESIGN C2 states `empty`, `populated`, `offline`, plus "This list is gone". Pure of the view model, so tests drive it with plain state. */
@Composable
internal fun ListDetailContent(ui: ListDetailUiState, onEvent: (ListDetailEvent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val focus = remember { FocusRequester() }
    var checkedOpen by rememberSaveable { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val unchecked = ui.unchecked
    val checked = ui.checked
    // A pending delete or clear only hides its rows, so the list is not empty until the window ends (as on web and iOS).
    val isEmpty = ui.loaded && !ui.isGone && !ui.hidingRows && unchecked.isEmpty() && checked.isEmpty()
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        list.name, style = MaterialTheme.typography.headlineSmall, color = tokens.text,
                        modifier = Modifier.weight(1f).testTag("list.title").semantics { heading() },
                    )
                    // Only Clear checked lives here until Share, Template and Archive land, so only when something is checked.
                    if (checked.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { actionsOpen = true }, modifier = Modifier.testTag("list.actions")) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "List actions", tint = tokens.accent)
                            }
                            DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Clear checked") },
                                    onClick = { actionsOpen = false; onEvent(ListDetailEvent.ClearChecked) },
                                    modifier = Modifier.testTag("list.actions.clearChecked"),
                                )
                            }
                        }
                    }
                }
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
                if (ui.allChecked) {
                    item(key = "h.allDone") {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "All done", style = MaterialTheme.typography.titleMedium, color = tokens.text2,
                                modifier = Modifier.testTag("list.allDone").semantics { heading() },
                            )
                            Button(
                                onClick = { onEvent(ListDetailEvent.ClearChecked) },
                                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
                                modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("list.clearChecked"),
                            ) { Text("Clear checked") }
                        }
                    }
                }
                items(unchecked, key = { it.id }) { item ->
                    // A pending check shows ticked and struck through in place; its box takes the check back.
                    val pendingCheck = item.id in ui.pendingCheckIds
                    ItemRow(
                        item, "list.row.${item.name}",
                        if (pendingCheck) Modifier.semantics { stateDescription = "Checked" } else Modifier,
                        ticked = pendingCheck, struck = pendingCheck,
                        onToggle = { onEvent(if (pendingCheck) ListDetailEvent.Undo(item.id) else ListDetailEvent.Check(item.id)) },
                        onOpen = { onEvent(ListDetailEvent.OpenEdit(item.id)) },
                    )
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
                            ItemRow(
                                item, "list.checked.row.${item.name}",
                                Modifier.alpha(0.6f).semantics { stateDescription = "Checked" },
                                ticked = true, struck = false,
                                onToggle = { onEvent(ListDetailEvent.Uncheck(item.id)) },
                                onOpen = { onEvent(ListDetailEvent.OpenEdit(item.id)) },
                            )
                        }
                    }
                }
            }
        }
        // Newest on top (DESIGN A6).
        ui.pending.forEach { action ->
            key(action.id) { UndoToast(action.message, onUndo = { onEvent(ListDetailEvent.Undo(action.id)) }) }
        }
        // Not before the first read (the list may be missing) and not for a gone list: a submit would queue a row for it.
        if (ui.loaded && !ui.isGone) {
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
                onDetails = { onEvent(ListDetailEvent.OpenNew(it)) },
                modifier = Modifier.imePadding(),
            )
        }
    }
}

/**
 * One item row (DESIGN C2): a checkbox, then the name, its note under it and "2 l" at the end, one TalkBack node
 * ("Milk, 2 l, lactose free") with a "Check off" custom action. The box toggles through [onToggle] ([ticked] is how it
 * looks); a tap on the rest of the row opens the C3 sheet. [tag] is the row's test tag; the box, the note and the
 * quantity add `.check`, `.note` and `.qty`.
 */
@Composable
private fun ItemRow(
    item: ListItemRecord, tag: String, modifier: Modifier,
    ticked: Boolean, struck: Boolean, onToggle: () -> Unit, onOpen: () -> Unit,
) {
    val tokens = LocalTokens.current
    val qty = ItemSheetLogic.formatQty(item.quantity, item.unit)
    val note = item.note?.takeIf { it.isNotBlank() }
    val spoken = listOfNotNull(item.name, qty.ifEmpty { null }, note).joinToString(", ")
    val toggleLabel = if (ticked) "Uncheck ${item.name}" else "Check off ${item.name}"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Beside the row body, not inside it: the body keeps opening C3. The box is at least 48 dp (Material's touch target).
        Checkbox(
            checked = ticked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = tokens.accent),
            modifier = Modifier.padding(start = 4.dp).testTag("$tag.check").semantics { contentDescription = toggleLabel },
        )
        ListRow(
            title = item.name,
            subtitle = note?.let { text ->
                @Composable {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, maxLines = 2, modifier = Modifier.testTag("$tag.note"))
                }
            },
            trailing = qty.ifEmpty { null }?.let { text ->
                @Composable { Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, modifier = Modifier.testTag("$tag.qty")) }
            },
            onClick = onOpen,
            struck = struck,
            modifier = Modifier.weight(1f).then(modifier).testTag(tag).semantics {
                if (qty.isNotEmpty() || note != null) contentDescription = spoken
                customActions = listOf(CustomAccessibilityAction(if (ticked) "Uncheck" else "Check off") { onToggle(); true })
            },
        )
    }
}

/** The field keeps focus after each add. A bar that is not attached yet (first frame) has nothing to focus. */
private fun FocusRequester.tryFocus() {
    try {
        requestFocus()
    } catch (_: Exception) {
    }
}
