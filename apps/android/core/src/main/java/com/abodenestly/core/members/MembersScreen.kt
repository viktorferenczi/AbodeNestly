package com.abodenestly.core.members

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.invite.InviteSheet
import com.abodenestly.core.invite.InviteSheetViewModel
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import java.util.UUID

/**
 * DESIGN B6. Member rows (avatar, role, overflow menu) arrive with CORE-04 and render above the button.
 * There is no role gating: owners and members both invite (SPEC §4 CORE-03).
 */
@Composable
fun MembersScreen(
    householdId: UUID,
    api: ApiService,
    isOnline: Boolean,
    webBaseUrl: String,
    modifier: Modifier = Modifier,
    onShare: ((String) -> Unit)? = null,
) {
    val tokens = LocalTokens.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // Created on tap and kept for the sheet's life, so a parent recomposition never resets its state.
    var invite by remember { mutableStateOf<InviteSheetViewModel?>(null) }
    Column(
        modifier.fillMaxSize().padding(Tokens.ScreenPadding).testTag("members.screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Members", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = tokens.text, modifier = Modifier.semantics { heading() },
            )
            Button(
                onClick = {
                    invite = InviteSheetViewModel(householdId, api, copy = { clipboard.setText(AnnotatedString(it)) }, scope = scope)
                },
                colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("members.invite"),
            ) { Text("Invite", fontWeight = FontWeight.SemiBold) }
        }
    }
    invite?.let { model ->
        if (onShare != null) {
            InviteSheet(model, isOnline, webBaseUrl, onClose = { invite = null }, onShare = onShare)
        } else {
            InviteSheet(model, isOnline, webBaseUrl, onClose = { invite = null })
        }
    }
}
