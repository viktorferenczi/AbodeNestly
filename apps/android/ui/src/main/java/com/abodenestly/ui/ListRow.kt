package com.abodenestly.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * DESIGN A6 ListRow: [title], an optional [subtitle] slot under it (a chip, a note) and a [trailing] slot (a count, a
 * chevron). At least 48 dp tall. Tapping calls [onClick]; a long press calls [onLongClick] (a menu, so every swipe has
 * a non-gesture twin). The row is one accessibility node: callers put the spoken label and custom actions on [modifier].
 * [struck] strikes the title through and dims it (a check-off waiting out its undo window). Swipe and the leading
 * control belong to the caller.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    struck: Boolean = false,
) {
    val tokens = LocalTokens.current
    val tap = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick ?: {})
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .then(tap)
            .heightIn(min = Tokens.MinTouchTarget)
            .padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (struck) tokens.text2 else tokens.text,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.invoke()
        }
        trailing?.invoke()
    }
}

/** A small capsule for a [ListRow] subtitle: a store name, "from pantry". */
@Composable
fun ListRowChip(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Surface(modifier, shape = RoundedCornerShape(percent = 50), color = tokens.accentBg) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
