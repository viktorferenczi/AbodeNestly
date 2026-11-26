package com.abodenestly.shopping

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens

/** What opening a list shows until SHOP-02-android replaces it with the list detail (DESIGN C2). */
@Composable
internal fun ListPlaceholderScreen(name: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Column(modifier.fillMaxSize().padding(Tokens.ScreenPadding)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("list.back")) { Text("Back", color = tokens.accent) }
        Text(name, style = MaterialTheme.typography.headlineSmall, color = tokens.text, modifier = Modifier.testTag("list.title").semantics { heading() })
        Text("Items arrive with the next update", color = tokens.text2, modifier = Modifier.padding(top = 8.dp).testTag("list.placeholder"))
    }
}

