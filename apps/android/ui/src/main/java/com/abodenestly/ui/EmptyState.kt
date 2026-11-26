package com.abodenestly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** DESIGN A6 EmptyState: illustration-free, one sentence of purpose ([message]) and one primary button. */
@Composable
fun EmptyState(message: String, actionTitle: String, onAction: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) {
    val tokens = LocalTokens.current
    Column(
        modifier.fillMaxWidth().padding(Tokens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            message, style = MaterialTheme.typography.titleMedium, color = tokens.text, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = tokens.text2, textAlign = TextAlign.Center)
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
            modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("empty-state.action"),
        ) { Text(actionTitle) }
    }
}
