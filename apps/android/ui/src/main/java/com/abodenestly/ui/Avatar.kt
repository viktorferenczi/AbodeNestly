package com.abodenestly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Initials in a circle. Loading `avatar_path` images is out of scope for CORE-04 (Coil is not allowed), so this
 * is also the fallback once that lands. The name is read next to the avatar, so TalkBack skips it.
 */
@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tokens = LocalTokens.current
    Box(
        modifier.size(size).background(tokens.accentBg, CircleShape).clearAndSetSemantics {}.testTag("avatar"),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tokens.accent)
    }
}
