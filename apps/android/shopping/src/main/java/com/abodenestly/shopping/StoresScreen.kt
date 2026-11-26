package com.abodenestly.shopping

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abodenestly.sync.StoreRecord
import com.abodenestly.sync.StoreRow
import com.abodenestly.ui.EmptyState
import com.abodenestly.ui.ListRow
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens
import com.abodenestly.ui.UndoToast

/** The new-store sheet as [StoresScreenHost] mounts it; tests mount the sheet's body inline instead of a popup. */
internal typealias StoreSheetSlot = @Composable (
    stores: List<StoreRecord>,
    onSave: suspend (String) -> Boolean,
    onClose: () -> Unit,
) -> Unit

/**
 * DESIGN C4 Stores. Opened from the Stores button on Lists and from "Manage stores" in the picker; [onBack] returns to
 * where it was opened. Leaving the screen or the app writes the deletes still inside their undo window.
 */
@Composable
fun StoresScreen(viewModel: StoresViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    StoresScreenHost(viewModel, onBack, modifier) { stores, onSave, onClose -> StoreSheet(stores, onSave, onClose) }
}

@Composable
internal fun StoresScreenHost(viewModel: StoresViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, sheet: StoreSheetSlot) {
    val ui by viewModel.state.collectAsState()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    // A configuration change (rotation, font scale) is not leaving: the same view model keeps its timers, so the undo
    // window stays open. A real stop or leaving the screen writes what is waiting.
    val activity = LocalContext.current.findActivity()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP, LocalLifecycleOwner.current) {
        if (activity?.isChangingConfigurations != true) viewModel.onEvent(StoresEvent.Flush)
    }
    DisposableEffect(viewModel) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.onEvent(StoresEvent.Flush) } }
    StoresContent(ui, viewModel::onEvent, onBack, onNew = { showSheet = true }, modifier = modifier)
    if (showSheet) {
        sheet(
            ui.records,
            { name ->
                val saved = viewModel.create(name) != null
                if (saved) showSheet = false
                saved
            },
            { showSheet = false },
        )
    }
}

/** DESIGN C4 states: `empty`, `populated`, `offline`. Pure of the view model, so component tests drive it with plain state. */
@Composable
internal fun StoresContent(ui: StoresUiState, onEvent: (StoresEvent) -> Unit, onBack: () -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val rows = ui.rows
    Box(modifier.fillMaxSize().background(tokens.bg).testTag("stores.screen")) {
        Column(Modifier.fillMaxSize()) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(horizontal = Tokens.ScreenPadding).heightIn(min = Tokens.MinTouchTarget).testTag("stores.back"),
            ) { Text("Back", color = tokens.accent) }
            Row(Modifier.fillMaxWidth().padding(start = Tokens.ScreenPadding, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Stores", style = MaterialTheme.typography.headlineSmall, color = tokens.text,
                    modifier = Modifier.weight(1f).testTag("stores.title").semantics { heading() },
                )
                IconButton(onClick = onNew, modifier = Modifier.testTag("stores.new")) {
                    Icon(Icons.Filled.Add, contentDescription = "New store", tint = tokens.accent)
                }
            }
            if (ui.writeFailed) {
                Text(
                    SAVE_ERROR, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding).testTag("stores.error")
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            if (!ui.online) {
                OfflineBanner("You're offline. Changes will sync when you reconnect.", Modifier.padding(horizontal = Tokens.ScreenPadding))
            }
            if (ui.loaded) {
                if (rows.isEmpty()) {
                    EmptyState(
                        "No stores yet", "New store", onNew, Modifier.testTag("stores.empty"),
                        hint = "Name the shops you go to, then pick one on a list.", actionTag = "stores.empty.new",
                    )
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("stores.list"), contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(rows, key = { it.id }) { row -> SwipeableStoreRow(row, onEvent) }
                    }
                }
            }
        }
        // Newest on top (DESIGN A6).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ui.pending.forEach { action ->
                key(action.id) { UndoToast(action.message, onUndo = { onEvent(StoresEvent.Undo(action.id)) }) }
            }
        }
    }
}

/**
 * One store row. Swipe left deletes; it is also a Delete button and a TalkBack custom action, so it is reachable without
 * swiping. The write waits for the undo window (the view model's), so a swipe only hides the row. A tap on the row does
 * nothing until SHOP-06-android adds the aisle order (C5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableStoreRow(row: StoreRow, onEvent: (StoresEvent) -> Unit) {
    val tokens = LocalTokens.current
    val send by rememberUpdatedState(onEvent)
    val current by rememberUpdatedState(row)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) send(StoresEvent.Delete(current))
            // The row leaves because the view model hides it; until then it springs back.
            target == SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (active) tokens.danger else Color.Transparent).padding(horizontal = Tokens.ScreenPadding),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (active) Text("Delete", color = tokens.surface, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        val learned = Stores.learnedLabel(row.learned)
        Row(Modifier.fillMaxWidth().background(tokens.bg), verticalAlignment = Alignment.CenterVertically) {
            ListRow(
                title = row.name,
                subtitle = learned?.let { text ->
                    @Composable {
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, modifier = Modifier.testTag("stores.row.${row.name}.learned"))
                    }
                },
                modifier = Modifier.weight(1f).testTag("stores.row.${row.name}").semantics {
                    customActions = listOf(CustomAccessibilityAction("Delete") { send(StoresEvent.Delete(row)); true })
                },
            )
            IconButton(
                onClick = { send(StoresEvent.Delete(row)) },
                modifier = Modifier.testTag("stores.row.${row.name}.delete"),
            ) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${row.name}", tint = tokens.danger) }
        }
    }
}
