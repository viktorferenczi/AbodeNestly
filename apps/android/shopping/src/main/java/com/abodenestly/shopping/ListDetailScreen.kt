package com.abodenestly.shopping

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.text.AnnotatedString
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
import java.util.Locale

/**
 * DESIGN C2 list detail and its C3 item sheet: the items with their checkboxes, the quick-add bar with autocomplete, and
 * Back to the overview. Leaving the screen or the app writes the deletes, check-offs and clears still inside their undo window.
 */
@Composable
fun ListDetailScreen(
    viewModel: ListDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onManageStores: () -> Unit = {},
    /** SHOP-10: the barcode scanner's camera, lookup and storage; `null` hides the bar's scan button. */
    scanner: ScannerSetup? = null,
    /** SHOP-13: the share link's origin and API calls; `null` hides "Share" from the List actions menu. */
    share: ShareSetup? = null,
    /** SHOP-14: the microphone and recognizer; `null` hides the bar's mic button. */
    voice: VoiceSetup? = null,
) {
    ListDetailScreenHost(viewModel, onBack, modifier, onManageStores, scanner, share = share, voice = voice) { isNew, initial, history, failed, onSave, onDelete, onClose ->
        ItemSheet(isNew, initial, history, failed, onSave, onDelete, onClose)
    }
}

@Composable
internal fun ListDetailScreenHost(
    viewModel: ListDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onManageStores: () -> Unit = {},
    scanner: ScannerSetup? = null,
    share: ShareSetup? = null,
    voice: VoiceSetup? = null,
    voiceSheet: VoiceSlot = { model, onClose, onTypeInstead -> VoiceAddSheet(model, onClose, onTypeInstead) },
    shareSheet: ShareSlot = { model, online, onClose -> ShareSheet(model, online, onClose) },
    picker: StorePickerSlot = { stores, current, failed, onCreate, onSave, onManage, onClose ->
        StorePickerSheet(stores, current, failed, onCreate, onSave, onManage, onClose)
    },
    addAllSheet: AddAllSlot = { targets, failed, onPick, onClose -> AddAllSheet(targets, failed, onPick, onClose) },
    sheet: ItemSheetSlot,
) {
    val ui by viewModel.state.collectAsState()
    // A configuration change (rotation, font scale) is not leaving: the same view model keeps its timers, so the undo
    // window stays open. A real stop or leaving the list writes what is waiting.
    val activity = LocalContext.current.findActivity()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, LocalLifecycleOwner.current) {
        if (activity?.isChangingConfigurations != true) viewModel.onEvent(ListDetailEvent.Flush)
    }
    DisposableEffect(viewModel) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.onEvent(ListDetailEvent.Flush) } }
    // SHOP-10: the scanner is a full-screen modal over the list; what is typed in the bar is left alone.
    var scanning by rememberSaveable { mutableStateOf(false) }
    // SHOP-14: the voice sheet. "Type instead" closes it and, once it has gone, focuses the Quick add field.
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    var typeInstead by remember { mutableStateOf(false) }
    val barFocus = remember { FocusRequester() }
    LaunchedEffect(voiceOpen, typeInstead) {
        if (!voiceOpen && typeInstead) {
            typeInstead = false
            barFocus.tryFocus()
        }
    }
    ListDetailContent(
        ui, viewModel::onEvent, onBack, modifier,
        onScan = if (scanner != null) ({ scanning = true }) else null,
        canShare = share != null,
        onVoice = if (voice != null) ({ voiceOpen = true }) else null,
        focus = barFocus,
    )
    if (voiceOpen && voice != null) {
        val device = voice.device()
        val model = remember { viewModel.makeVoice(device) }
        voiceSheet(model, { voiceOpen = false }, { typeInstead = true; voiceOpen = false })
    }
    if (scanning && scanner != null) {
        val device = scanner.device()
        val scannerScope = rememberCoroutineScope()
        val model = remember { viewModel.makeScanner(device, scanner.lookup, scanner.store, scannerScope) }
        BarcodeScannerDialog(model, device, scanner.images, onClose = { scanning = false })
    }
    if (ui.picker && ui.detail.list != null) {
        picker(
            ui.detail.stores,
            ui.currentStoreId,
            ui.pickerFailed,
            viewModel::addStore,
            { storeId -> viewModel.onEvent(ListDetailEvent.SetStore(storeId)) },
            {
                viewModel.onEvent(ListDetailEvent.ClosePicker)
                onManageStores()
            },
            { viewModel.onEvent(ListDetailEvent.ClosePicker) },
        )
    }
    // SHOP-13: the C8 share sheet, only for a list that has loaded and is not gone.
    if (ui.shareOpen && share != null && ui.canShare) {
        val clipboard = LocalClipboardManager.current
        val model = remember(viewModel, share) { viewModel.makeShare(share.webBase, share.share, share.stop, copy = { clipboard.setText(AnnotatedString(it)) }) }
        DisposableEffect(model) { onDispose { model.close() } }
        shareSheet(model, ui.online) { viewModel.onEvent(ListDetailEvent.CloseShare) }
    }
    // SHOP-11: the template's "Add all to…" sheet.
    if (ui.addAllOpen && ui.isTemplate) {
        addAllSheet(
            ui.targets,
            ui.addAllFailed,
            { target -> viewModel.onEvent(ListDetailEvent.AddAll(target.id)) },
            { viewModel.onEvent(ListDetailEvent.CloseAddAll) },
        )
    }
    val open = ui.sheet
    if (open != null) {
        val item = ui.editing
        if (open is SheetState.New || item != null) {
            // SHOP-12: the price field (only with a live list store) and the Last bought line read this.
            val pricing = PriceSheetInfo(ui.detail.storeName) { name ->
                ui.lastBought(name)?.let { Price.lastBoughtLine(it, ui.detail.currency, Locale.getDefault()) }
            }
            CompositionLocalProvider(LocalPriceSheet provides pricing) {
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
}

/** DESIGN C2 states `empty`, `populated`, `offline`, plus "This list is gone". Pure of the view model, so tests drive it with plain state. */
@Composable
internal fun ListDetailContent(
    ui: ListDetailUiState,
    onEvent: (ListDetailEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onScan: (() -> Unit)? = null,
    /** SHOP-13: the app can share; "Share" is then the menu's first entry once the list has loaded. */
    canShare: Boolean = false,
    /** SHOP-14: opens the voice sheet; `null` hides the mic button. */
    onVoice: (() -> Unit)? = null,
    /** The Quick add field's focus; the host passes its own so "Type instead" can reach it. */
    focus: FocusRequester = remember { FocusRequester() },
) {
    val tokens = LocalTokens.current
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
                    // Share comes first once sharing is available; then Add all on a template and Clear checked when something is checked.
                    val actions = Templates.actions(ui.isTemplate, checked.isNotEmpty(), canShare = canShare && ui.canShare)
                    if (actions.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { actionsOpen = true }, modifier = Modifier.testTag("list.actions")) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "List actions", tint = tokens.accent)
                            }
                            DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                                actions.forEach { action ->
                                    when (action) {
                                        ListAction.Share -> DropdownMenuItem(
                                            text = { Text("Share") },
                                            onClick = { actionsOpen = false; onEvent(ListDetailEvent.OpenShare) },
                                            modifier = Modifier.testTag("list.actions.share"),
                                        )
                                        ListAction.AddAll -> DropdownMenuItem(
                                            text = { Text("Add all to list") },
                                            onClick = { actionsOpen = false; onEvent(ListDetailEvent.OpenAddAll) },
                                            modifier = Modifier.testTag("list.actions.addAll"),
                                        )
                                        ListAction.ClearChecked -> DropdownMenuItem(
                                            text = { Text("Clear checked") },
                                            onClick = { actionsOpen = false; onEvent(ListDetailEvent.ClearChecked) },
                                            modifier = Modifier.testTag("list.actions.clearChecked"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (ui.isTemplate) {
                    Text(
                        "Template", style = MaterialTheme.typography.labelLarge, color = tokens.text2,
                        modifier = Modifier.testTag("list.templateCaption"),
                    )
                }
                StoreChip(ui.detail.storeName) { onEvent(ListDetailEvent.OpenPicker) }
            }
            // SHOP-11: a plain status line, announced to TalkBack; it clears itself and has no Undo.
            ui.status?.let {
                Text(
                    it, color = tokens.text2, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("list.status")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
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
                Text("Try: 2 l milk", style = MaterialTheme.typography.bodyLarge, color = tokens.text2, modifier = Modifier.testTag("list.empty"))
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
                        item, "list.row.${item.name}", ui.caption(item),
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
                                item, "list.checked.row.${item.name}", ui.caption(item),
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
                onScan = onScan,
                onVoice = onVoice,
                modifier = Modifier.imePadding(),
            )
        }
    }
}

/**
 * One item row (DESIGN C2): a checkbox, then the name, its note and the last price [caption] ("€1.20 · Test Market",
 * SHOP-12) under it and "2 l" at the end, one TalkBack node ("Milk, 2 l, lactose free, €1.20 · Test Market") with a "Check off" custom action. The box toggles through [onToggle] ([ticked] is how it
 * looks); a tap on the rest of the row opens the C3 sheet. [tag] is the row's test tag; the box, the note and the
 * quantity and the caption add `.check`, `.note`, `.qty` and `.price`.
 */
@Composable
private fun ItemRow(
    item: ListItemRecord, tag: String, caption: String?, modifier: Modifier,
    ticked: Boolean, struck: Boolean, onToggle: () -> Unit, onOpen: () -> Unit,
) {
    val tokens = LocalTokens.current
    val qty = ItemSheetLogic.formatQty(item.quantity, item.unit)
    val note = item.note?.takeIf { it.isNotBlank() }
    val spoken = listOfNotNull(item.name, qty.ifEmpty { null }, note, caption).joinToString(", ")
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
            subtitle = if (note != null || caption != null) {
                @Composable {
                    Column {
                        if (note != null) {
                            Text(note, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, maxLines = 2, modifier = Modifier.testTag("$tag.note"))
                        }
                        if (caption != null) {
                            Text(caption, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, maxLines = 1, modifier = Modifier.testTag("$tag.price"))
                        }
                    }
                }
            } else {
                null
            },
            trailing = qty.ifEmpty { null }?.let { text ->
                @Composable { Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, modifier = Modifier.testTag("$tag.qty")) }
            },
            onClick = onOpen,
            struck = struck,
            modifier = Modifier.weight(1f).then(modifier).testTag(tag).semantics {
                if (qty.isNotEmpty() || note != null || caption != null) contentDescription = spoken
                customActions = listOf(CustomAccessibilityAction(if (ticked) "Uncheck" else "Check off") { onToggle(); true })
            },
        )
    }
}

/**
 * The list's store chip (DESIGN C2): the store name, or a muted "Add store" when the list has none or its store was
 * deleted. Always a button that opens the picker; the 48 dp target is the row around the capsule.
 */
@Composable
private fun StoreChip(storeName: String?, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.defaultMinSize(minWidth = Tokens.MinTouchTarget, minHeight = Tokens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("list.store")
            .semantics { contentDescription = "Store: ${storeName ?: "none"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (storeName != null) {
            ListRowChip(storeName)
        } else {
            Surface(shape = RoundedCornerShape(percent = 50), color = tokens.surface2, border = BorderStroke(1.dp, tokens.line)) {
                Text(
                    "Add store", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = tokens.text2, maxLines = 1,
                )
            }
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
