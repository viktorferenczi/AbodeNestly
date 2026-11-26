package com.abodenestly.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.abodenestly.sync.StoreRecord
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens

/** DESIGN C1 sheet: name and store. The Template switch waits for SHOP-11. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewListSheet(stores: List<StoreRecord>, failed: Boolean, onSave: (name: String, storeId: String?) -> Unit, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = LocalTokens.current.bg) {
        NewListSheetContent(stores, failed, onSave, onClose)
    }
}

/**
 * The sheet's body: name (focused on open), store picker (only when the household has stores), Cancel and Save. Save is
 * off while the trimmed name is blank and while a save is in flight; [failed] re-enables it and shows the message. Tested
 * directly so Robolectric never drives the popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewListSheetContent(
    stores: List<StoreRecord>,
    failed: Boolean,
    onSave: (name: String, storeId: String?) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalTokens.current
    var name by rememberSaveable { mutableStateOf("") }
    var storeId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // A store deleted while the sheet is open drops out of the picker, and the choice falls back to "No store".
    val chosen = stores.firstOrNull { it.id == storeId }
    val valid = name.trim().isNotEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(failed) { if (failed) saving = false }
    fun save() {
        if (!valid || saving) return
        saving = true
        onSave(name, chosen?.id)
    }

    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding).testTag("newlist.sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "New list", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).focusRequester(focus).testTag("newlist.name"),
        )
        if (failed) {
            Text(
                "Could not save the list. Try again.", color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("newlist.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (stores.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = chosen?.name ?: NO_STORE,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Store") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        .heightIn(min = Tokens.MinTouchTarget).testTag("newlist.store"),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(NO_STORE) },
                        onClick = { storeId = null; open = false },
                        modifier = Modifier.testTag("newlist.store.none"),
                    )
                    stores.forEach { store ->
                        DropdownMenuItem(
                            text = { Text(store.name) },
                            onClick = { storeId = store.id; open = false },
                            modifier = Modifier.testTag("newlist.store.${store.id}"),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("newlist.cancel"),
            ) { Text("Cancel", color = tokens.accent) }
            Button(
                onClick = { save() },
                enabled = valid && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("newlist.save"),
            ) { Text("Save") }
        }
    }
}

private const val NO_STORE = "No store"
