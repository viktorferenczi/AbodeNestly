package com.abodenestly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/** DESIGN A6 TopBar: title on the left, optional trailing text (the signed-in person's name for now). */
@Composable
fun TopBar(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.surface)
            .heightIn(min = Tokens.MinTouchTarget)
            .padding(horizontal = Tokens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.testTag("topbar.title").semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.text,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing,
                modifier = Modifier.testTag("topbar.trailing"),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
