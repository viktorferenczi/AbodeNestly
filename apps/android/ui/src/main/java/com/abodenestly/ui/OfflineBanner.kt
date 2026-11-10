package com.abodenestly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** DESIGN A6: shown on screens that need the network. */
@Composable
fun OfflineBanner(message: String, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Box(
        modifier
            .fillMaxWidth()
            .testTag("offline-banner")
            .background(tokens.surface2, RoundedCornerShape(Tokens.CardRadius))
            .padding(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = tokens.warn)
    }
}

