package com.abodenestly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * DESIGN A6 QuickAddBar: a text field with an Add button, pinned at the bottom of a list by the caller, and the
 * autocomplete [suggestions] stacked above it (each a pair of `name_key` and the label it shows). Presentational: the
 * caller owns the text and the matching. Tapping a suggestion calls [onPick]; Done on the keyboard and the Add button
 * both call [onSubmit], so they add exactly the typed text. [onDetails], when given, adds a Details button that hands the typed
 * text to the caller (the C3 sheet for a new item). No mic or barcode button until SHOP-14 and SHOP-10.
 */
@Composable
fun QuickAddBar(
    text: String,
    onTextChange: (String) -> Unit,
    suggestions: List<Pair<String, String>>,
    onPick: (key: String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Add item",
    focusRequester: FocusRequester? = null,
    onDetails: ((String) -> Unit)? = null,
) {
    val tokens = LocalTokens.current
    Column(modifier.fillMaxWidth().background(tokens.surface)) {
        suggestions.forEach { (key, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("quickadd.suggestion.$label")
                    .semantics { contentDescription = "Suggestion, $label" }
                    .clickable(role = Role.Button) { onPick(key) }
                    .heightIn(min = Tokens.MinTouchTarget)
                    .padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = tokens.text)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Tokens.MinTouchTarget)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .testTag("quickadd.field"),
                placeholder = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
            if (onDetails != null) {
                TextButton(
                    onClick = { onDetails(text) },
                    modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("quickadd.details"),
                ) { Text("Details", color = tokens.accent) }
            }
            Button(
                onClick = onSubmit,
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
                modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("quickadd.add"),
            ) { Text("Add") }
        }
    }
}
