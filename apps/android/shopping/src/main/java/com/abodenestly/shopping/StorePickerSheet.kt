package com.abodenestly.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abodenestly.sync.StoreRecord
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.launch

/** The store picker as [ListDetailScreenHost] mounts it; tests mount the picker's body inline instead of a popup. */
internal typealias StorePickerSlot = @Composable (
    stores: List<StoreRecord>,
    current: String?,
    failed: Boolean,
    onCreate: suspend (String) -> String?,
    onSave: (String?) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
) -> Unit

/** DESIGN C2 store picker in a [ModalBottomSheet]. Dismissing it (swipe, scrim, Back) is Cancel: nothing is written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorePickerSheet(
    stores: List<StoreRecord>,
    current: String?,
    failed: Boolean,
    onCreate: suspend (String) -> String?,
    onSave: (String?) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = LocalTokens.current.bg) {
        StorePickerContent(stores, current, failed, onCreate, onSave, onManage, onClose)
    }
}

/**
 * The picker's body (DESIGN C2): radio rows "No store" and the live stores by name, an inline "New store" field with Add
 * (creates and selects in one step, same validation as the C4 sheet), "Manage stores", Cancel and Save. [current] is the
 * list's store when it is live. Save writes only when the choice differs from [current]. A store just created stays
 * selected until the observation catches up; a store deleted elsewhere while open drops out, and a selection that points
 * to it falls back to [current]. Tested directly so Robolectric never drives the popup.
 */
@Composable
internal fun StorePickerContent(
    stores: List<StoreRecord>,
    current: String?,
    failed: Boolean,
    onCreate: suspend (String) -> String?,
    onSave: (String?) -> Unit,
    onManage: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalTokens.current
    var choice by rememberSaveable { mutableStateOf(current) }
    var newName by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addFailed by remember { mutableStateOf(false) }
    // Stores created here, shown until the observation has them. Ids are recorded as seen during composition (not in an
    // effect a batched emission could skip), so a created store that was seen and then deleted elsewhere is never offered again.
    val created = remember { mutableStateListOf<StoreRecord>() }
    val seen = remember { HashSet<String>() }
    stores.forEach { seen.add(it.id) }
    val shown = (stores + created.filter { c -> c.id !in seen }).sortedWith(compareBy<StoreRecord>({ it.name.lowercase() }, { it.id }))
    val chosen = choice?.takeIf { id -> shown.any { it.id == id } } ?: if (choice == null) null else current
    val problem = Stores.validate(newName, shown)
    val shownProblem = problem.takeUnless { it == StoreNameProblem.Empty && newName.isEmpty() }
    val scope = rememberCoroutineScope()
    val create by rememberUpdatedState(onCreate)
    fun add() {
        if (problem != null || adding) return
        val typed = newName.trim()
        adding = true
        addFailed = false
        scope.launch {
            val id = create(typed)
            adding = false
            if (id == null) {
                addFailed = true
            } else {
                created.add(StoreRecord(id, typed))
                choice = id
                newName = ""
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding).testTag("storepicker.sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Store", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
            modifier = Modifier.semantics { heading() },
        )
        StoreChoice("No store", chosen == null, Modifier.testTag("storepicker.none")) { choice = null }
        shown.forEach { store ->
            StoreChoice(store.name, chosen == store.id, Modifier.testTag("storepicker.row.${store.name}")) { choice = store.id }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New store") },
                singleLine = true,
                isError = shownProblem != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("storepicker.new"),
            )
            OutlinedButton(
                onClick = { add() },
                enabled = problem == null && !adding,
                modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("storepicker.add"),
            ) { Text("Add", color = tokens.accent) }
        }
        shownProblem?.let {
            Text(
                it.message, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("storepicker.problem").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (addFailed) {
            Text(
                "Could not save the store. Try again.", color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("storepicker.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        TextButton(onClick = onManage, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("storepicker.manage")) {
            Text("Manage stores", color = tokens.accent)
        }
        if (failed) {
            Text(
                SAVE_ERROR, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("storepicker.saveError").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("storepicker.cancel"),
            ) { Text("Cancel", color = tokens.accent) }
            Button(
                onClick = { if (chosen == current) onClose() else onSave(chosen) },
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("storepicker.save"),
            ) { Text("Save") }
        }
    }
}

/** One radio row: the whole row selects, so the 48 dp target is the row, not the small radio. */
@Composable
private fun StoreChoice(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = tokens.accent))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tokens.text)
    }
}
