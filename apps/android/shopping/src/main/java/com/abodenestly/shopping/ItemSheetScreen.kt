package com.abodenestly.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.QuantityStepper
import com.abodenestly.ui.Tokens

/**
 * SHOP-12: what the sheet needs to offer a price, provided by [ListDetailScreenHost] around the sheet slot so the slot's
 * signature stays as it was. [storeName] is the list's live store: null means no store, so no price field (AC3).
 * [lastBoughtLine] answers for the name as typed now (the same lookup as the C2 caption) and is null when there is no price.
 */
internal data class PriceSheetInfo(val storeName: String?, val lastBoughtLine: (String) -> String?)

internal val LocalPriceSheet = staticCompositionLocalOf<PriceSheetInfo?> { null }

/** The item sheet as [ListDetailScreenHost] mounts it; tests mount the sheet's body inline instead of a popup. */
internal typealias ItemSheetSlot = @Composable (
    isNew: Boolean,
    initial: ItemForm,
    history: List<ListItemRecord>,
    failed: Boolean,
    onSave: (ItemForm) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) -> Unit

/** DESIGN C3 sheet in a [ModalBottomSheet]. Dismissing it (swipe, scrim, Back) is Cancel: nothing is written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(
    isNew: Boolean,
    initial: ItemForm,
    history: List<ListItemRecord>,
    failed: Boolean,
    onSave: (ItemForm) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = LocalTokens.current.bg) {
        ItemSheetContent(isNew, initial, history, failed, onSave, onDelete, onClose)
    }
}

/**
 * The sheet's body (DESIGN C3): name, quantity stepper, unit with suggestion chips, note, the price and its Last bought line (SHOP-12); Cancel and Save, and in edit a
 * Delete button. Save is off while the name is blank or the quantity is invalid (the view model ignores a second tap while a save is in flight);
 * [failed] shows the message. [history] feeds the unit chips. Tested directly so Robolectric never drives the popup.
 */
@Composable
internal fun ItemSheetContent(
    isNew: Boolean,
    initial: ItemForm,
    history: List<ListItemRecord>,
    failed: Boolean,
    onSave: (ItemForm) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val tokens = LocalTokens.current
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var quantity by rememberSaveable { mutableStateOf(initial.quantityText) }
    var unit by rememberSaveable { mutableStateOf(initial.unit) }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    var price by rememberSaveable { mutableStateOf(initial.priceText) }
    val pricing = LocalPriceSheet.current
    val priceStore = pricing?.storeName
    // Without a store nothing was typed, so the form's price is blank and never blocks Save.
    val form = ItemForm(name, quantity, unit, note, if (priceStore != null) price else "")
    val valid = ItemSheetLogic.fields(form) != null
    val quantityError = ItemSheetLogic.quantityError(quantity)
    val priceError = priceStore != null && ItemSheetLogic.priceError(price)
    val units = ItemSheetLogic.unitSuggestions(history, unit)
    val focus = remember { FocusRequester() }
    // A new item starts on its name; an edit opens without the keyboard.
    LaunchedEffect(Unit) { if (isNew) runCatching { focus.requestFocus() } }
    fun save() {
        if (valid) onSave(form)
    }

    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding).testTag("item.sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (isNew) "New item" else "Edit item", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = tokens.text, modifier = Modifier.testTag("item.title").semantics { heading() },
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).focusRequester(focus).testTag("item.name"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            QuantityStepper(quantity, { quantity = it }, isError = quantityError)
            if (quantityError) {
                Text(
                    "Enter a number above 0", color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("item.qtyError").semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text("Unit") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("item.unit"),
            )
            if (units.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEach { u -> UnitChip(u) { unit = u } }
                }
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("item.note"),
        )
        if (priceStore != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price at $priceStore") },
                    singleLine = true,
                    isError = priceError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("itemsheet.price"),
                )
                if (priceError) {
                    Text(
                        PRICE_ERROR, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("itemsheet.priceError").semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        }
        pricing?.lastBoughtLine?.invoke(name)?.let { line ->
            Text(
                line, color = tokens.text2, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("itemsheet.lastBought"),
            )
        }
        if (failed) {
            Text(
                SAVE_ERROR, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("item.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("item.cancel"),
            ) { Text("Cancel", color = tokens.accent) }
            Button(
                onClick = { save() },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("item.save"),
            ) { Text("Save") }
        }
        if (!isNew && onDelete != null) {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("item.delete"),
            ) { Text("Delete item", color = tokens.danger) }
        }
    }
}

/** SHOP-12: the inline message under a price that is not a price. */
internal const val PRICE_ERROR = "Enter a price like 1.20"

/** A tappable unit suggestion; the row is 48 dp tall for the touch target, the capsule inside it is smaller. */
@Composable
private fun UnitChip(unit: String, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.heightIn(min = Tokens.MinTouchTarget).clickable(role = Role.Button, onClick = onClick).testTag("item.unit.suggestion.$unit"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(percent = 50), color = tokens.accentBg) {
            Text(unit, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = tokens.accent)
        }
    }
}
