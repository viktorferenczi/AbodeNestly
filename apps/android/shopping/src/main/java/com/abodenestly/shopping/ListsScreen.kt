package com.abodenestly.shopping

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abodenestly.sync.StoreRecord
import com.abodenestly.ui.EmptyState
import com.abodenestly.ui.ListRow
import com.abodenestly.ui.ListRowChip
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens
import com.abodenestly.ui.UndoToast

/** The new-list sheet as [ListsScreenHost] mounts it; tests mount the sheet's body inline instead of a popup. */
internal typealias NewListSheetSlot = @Composable (
    stores: List<StoreRecord>,
    failed: Boolean,
    onSave: (name: String, storeId: String?, isTemplate: Boolean) -> Unit,
    onClose: () -> Unit,
) -> Unit

/**
 * DESIGN C1 Lists overview. [onOpen] gets the id of the list to open: a tapped row, or the list the sheet just created
 * (AC5). Leaving the screen or the app writes the swipes still inside their undo window.
 */
@Composable
fun ListsScreen(viewModel: ListsViewModel, onOpen: (listId: String) -> Unit, modifier: Modifier = Modifier, onOpenStores: () -> Unit = {}) {
    ListsScreenHost(viewModel, onOpen, modifier, onOpenStores) { stores, failed, onSave, onClose -> NewListSheet(stores, failed, onSave, onClose) }
}

@Composable
internal fun ListsScreenHost(
    viewModel: ListsViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenStores: () -> Unit = {},
    sheet: NewListSheetSlot,
) {
    val ui by viewModel.state.collectAsState()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val open by rememberUpdatedState(onOpen)
    // A configuration change (rotation, font scale) is not leaving: the same view model keeps its timers, so the undo
    // window stays open. A real stop or leaving the overview writes what is waiting.
    val activity = LocalContext.current.findActivity()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, LocalLifecycleOwner.current) {
        if (activity?.isChangingConfigurations != true) viewModel.onEvent(ListsEvent.Flush)
    }
    DisposableEffect(viewModel) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.onEvent(ListsEvent.Flush) } }
    // AC5: a created list opens once, and the sheet goes.
    LaunchedEffect(ui.opened) {
        val id = ui.opened ?: return@LaunchedEffect
        showSheet = false
        viewModel.onEvent(ListsEvent.OpenedHandled)
        open(id)
    }
    ListsContent(ui, viewModel::onEvent, onOpen = { open(it.id) }, onNewList = { showSheet = true }, onOpenStores = onOpenStores, modifier = modifier)
    if (showSheet) sheet(ui.stores, ui.createFailed, { name, storeId, isTemplate -> viewModel.onEvent(ListsEvent.Create(name, storeId, isTemplate)) }, { showSheet = false })
}

/** DESIGN C1 states: `empty`, `populated`, `offline`. Pure of the view model, so component tests drive it with plain state. */
@Composable
internal fun ListsContent(
    ui: ListsUiState,
    onEvent: (ListsEvent) -> Unit,
    onOpen: (ListRowModel) -> Unit,
    onNewList: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStores: () -> Unit = {},
) {
    val tokens = LocalTokens.current
    val sections = ui.sections
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(tokens.bg)) {
        Column(Modifier.fillMaxSize()) {
            // The global TopBar keeps the household and the sync chip, so the screen has its own heading with the "+" beside it.
            Row(Modifier.fillMaxWidth().padding(start = Tokens.ScreenPadding, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Shopping", style = MaterialTheme.typography.headlineSmall, color = tokens.text,
                    modifier = Modifier.weight(1f).testTag("lists.heading").semantics { heading() },
                )
                // A text button: the store icon is in material-icons-extended, which the app does not depend on.
                TextButton(
                    onClick = onOpenStores,
                    modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("lists.stores").semantics { contentDescription = "Stores" },
                ) { Text("Stores", color = tokens.accent) }
                IconButton(onClick = onNewList, modifier = Modifier.testTag("lists.new")) {
                    Icon(Icons.Filled.Add, contentDescription = "New list", tint = tokens.accent)
                }
            }
            ui.error?.let {
                Text(
                    it, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding).testTag("lists.error")
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            if (!ui.online) {
                OfflineBanner("You're offline. Changes will sync when you reconnect.", Modifier.padding(horizontal = Tokens.ScreenPadding))
            }
            if (ui.loaded) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("lists.list"), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (sections.isEmpty) {
                        item(key = "empty") {
                            EmptyState(
                                "Make your first list", "New list", onNewList, Modifier.testTag("lists.empty"),
                                hint = "Lists keep what you need to buy, shared with everyone in your household.",
                            )
                        }
                    } else {
                        item(key = "h.lists") { SectionHeader("Lists") }
                        items(sections.lists, key = { it.id }) { row -> SwipeableListRow(row, archived = false, onOpen, onEvent) }
                        if (sections.templates.isNotEmpty()) {
                            item(key = "h.templates") { SectionHeader("Templates") }
                            items(sections.templates, key = { it.id }) { row -> SwipeableListRow(row, archived = false, onOpen, onEvent) }
                        }
                    }
                    if (sections.archived.isNotEmpty()) {
                        item(key = "h.archived") {
                            ArchivedHeader(sections.archived.size, archivedOpen) { archivedOpen = !archivedOpen }
                        }
                        if (archivedOpen) {
                            items(sections.archived, key = { it.id }) { row -> SwipeableListRow(row, archived = true, onOpen, onEvent) }
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (ui.loaded && !sections.isEmpty) {
                Box(Modifier.fillMaxWidth().padding(end = Tokens.ScreenPadding, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    ExtendedFloatingActionButton(
                        onClick = onNewList, containerColor = tokens.accent, contentColor = tokens.surface,
                        modifier = Modifier.testTag("lists.fab"),
                    ) { Text("New list") }
                }
            }
            // Newest on top (DESIGN A6).
            ui.pending.forEach { action ->
                key(action.id) { UndoToast(action.message, onUndo = { onEvent(ListsEvent.Undo(action.id)) }) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = LocalTokens.current.text2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp).semantics { heading() },
    )
}

/** The collapsed Archived disclosure (DESIGN C1). */
@Composable
private fun ArchivedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = Tokens.ScreenPadding)
            .testTag("lists.archived.toggle")
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Archived ($count)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = tokens.text2)
        Text(if (expanded) "▾" else "▸", color = tokens.text2)
    }
}

/**
 * One list row. Swipe left archives (Lists, Templates) or deletes (Archived); swiping an Archived row right restores it.
 * Every swipe is also a TalkBack custom action and an entry in the long-press menu, so it is reachable without swiping.
 * The write waits for the undo window (the view model's), so a swipe only hides the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableListRow(row: ListRowModel, archived: Boolean, onOpen: (ListRowModel) -> Unit, onEvent: (ListsEvent) -> Unit) {
    val tokens = LocalTokens.current
    val send by rememberUpdatedState(onEvent)
    val current by rememberUpdatedState(row)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.EndToStart -> {
                    send(if (archived) ListsEvent.Delete(current) else ListsEvent.Archive(current))
                    false // the row leaves because the view model hides it; until then it springs back
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (archived) send(ListsEvent.Restore(current))
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = archived,
        backgroundContent = {
            val (color, label, align) = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.EndToStart ->
                    Triple(if (archived) tokens.danger else tokens.accent, if (archived) "Delete" else "Archive", Alignment.CenterEnd)
                SwipeToDismissBoxValue.StartToEnd -> Triple(tokens.accent, "Restore", Alignment.CenterStart)
                SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, "", Alignment.CenterStart)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = Tokens.ScreenPadding), contentAlignment = align) {
                Text(label, color = tokens.surface, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        var menu by remember { mutableStateOf(false) }
        val spoken = listOfNotNull(row.name, row.storeName, row.count).joinToString(", ")
        val actions = if (archived) {
            listOf(
                CustomAccessibilityAction("Restore") { send(ListsEvent.Restore(row)); true },
                CustomAccessibilityAction("Delete") { send(ListsEvent.Delete(row)); true },
            )
        } else {
            listOf(CustomAccessibilityAction("Archive") { send(ListsEvent.Archive(row)); true })
        }
        val subtitle: (@Composable () -> Unit)? = row.storeName?.let { store ->
            @Composable { ListRowChip(store, Modifier.testTag("lists.row.store")) }
        }
        Row(Modifier.fillMaxWidth().background(tokens.bg), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ListRow(
                    title = row.name,
                    subtitle = subtitle,
                    trailing = { Text(row.count, style = MaterialTheme.typography.bodyMedium, color = tokens.text2) },
                    onClick = { onOpen(row) },
                    onLongClick = { menu = true },
                    modifier = Modifier
                        .testTag(if (archived) "lists.archived.row.${row.name}" else "lists.row.${row.name}")
                        .semantics { contentDescription = spoken; customActions = actions },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (archived) {
                        DropdownMenuItem(
                            text = { Text("Restore") },
                            onClick = { menu = false; send(ListsEvent.Restore(row)) },
                            modifier = Modifier.testTag("lists.menu.restore"),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = tokens.danger) },
                            onClick = { menu = false; send(ListsEvent.Delete(row)) },
                            modifier = Modifier.testTag("lists.menu.delete"),
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            onClick = { menu = false; send(ListsEvent.Archive(row)) },
                            modifier = Modifier.testTag("lists.menu.archive"),
                        )
                    }
                }
            }
            if (archived) {
                TextButton(
                    onClick = { send(ListsEvent.Restore(row)) },
                    modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("lists.restore.${row.name}")
                        .semantics { contentDescription = "Restore ${row.name}" },
                ) { Text("Restore", color = tokens.accent) }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
