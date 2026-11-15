package com.abodenestly.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * DESIGN A6 undo toast, styled like a Snackbar. Timing lives with the caller (a `SnackbarHostState` timer would
 * make tests wait in real time); this only shows the message and the Undo button, and announces the message.
 */
@Composable
fun UndoToast(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Surface(
        modifier.fillMaxWidth().padding(Tokens.ScreenPadding).testTag("undo-toast")
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Tokens.CardRadius),
        color = tokens.surface2,
        border = BorderStroke(1.dp, tokens.line),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tokens.text)
            TextButton(onClick = onUndo, modifier = Modifier.defaultMinSize(Tokens.MinTouchTarget, Tokens.MinTouchTarget).testTag("undo-toast.undo")) {
                Text("Undo", color = tokens.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
