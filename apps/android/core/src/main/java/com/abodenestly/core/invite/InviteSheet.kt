package com.abodenestly.core.invite

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens

// Copied from iOS `InviteSheetView`.
internal const val LOAD_ERROR_MESSAGE = "Couldn't load the invite code. Try again."
internal const val NEW_CODE_ERROR_MESSAGE = "Couldn't create a new code. The current code still works."
internal const val OLD_CODE_MESSAGE = "The new code is ready, but the old code may still work."

/** The system share chooser for the link (AC3). */
fun shareWithChooser(context: android.content.Context, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
        .putExtra(Intent.EXTRA_SUBJECT, "Join my household")
    val chooser = Intent.createChooser(send, null)
    // An application context has no task to start the chooser in.
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/**
 * DESIGN B6 invite sheet. Role-independent: owners and members see the same thing (SPEC §4 CORE-03).
 * [onShare] receives `<webBaseUrl>/join/<code>`; it is a parameter so tests can record the URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(
    viewModel: InviteSheetViewModel,
    isOnline: Boolean,
    webBaseUrl: String,
    onClose: () -> Unit,
    onShare: (String) -> Unit = LocalContext.current.let { context -> { url -> shareWithChooser(context, url) } },
) {
    val state by viewModel.state.collectAsState()
    val oldCodeMayWork by viewModel.oldCodeMayWork.collectAsState()
    val notice by viewModel.notice.collectAsState()
    // Loads on open and again on reconnect.
    LaunchedEffect(isOnline) { if (isOnline) viewModel.loadIfNeeded() }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = LocalTokens.current.bg) {
        InviteSheetContent(
            state = state,
            oldCodeMayWork = oldCodeMayWork,
            notice = notice,
            isOnline = isOnline,
            onNewCode = viewModel::newCode,
            onCopy = viewModel::copyCode,
            onShare = onShare,
            webBaseUrl = webBaseUrl,
            onRetry = viewModel::loadIfNeeded,
            onClose = onClose,
        )
    }
}

/** The sheet's body, tested directly so Robolectric never drives the bottom-sheet popup. */
@Composable
internal fun InviteSheetContent(
    state: InviteSheetState,
    oldCodeMayWork: Boolean,
    notice: String?,
    isOnline: Boolean,
    webBaseUrl: String,
    onNewCode: () -> Unit,
    onCopy: () -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTokens.current
    val invite = state.shownInvite
    val rotating = state is InviteSheetState.Rotating
    val failed = state is InviteSheetState.Error
    val actionsEnabled = isOnline && !rotating && invite != null
    Column(
        modifier.testTag("invite.sheet").widthIn(max = 480.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Invite to household", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = tokens.text, modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("invite.close")) {
                Text("Close", color = tokens.accent)
            }
        }
        if (!isOnline) OfflineBanner("You're offline. Connect to change household settings.")
        if (invite != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    invite.code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("invite.code").semantics { contentDescription = "Invite code ${invite.code}" },
                )
                OutlinedButton(
                    onClick = onCopy, enabled = !rotating,
                    modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("invite.copy"),
                ) { Text("Copy", color = if (rotating) Color.Unspecified else tokens.accent) }
            }
            Text(
                InviteSheetViewModel.metaText(invite), style = MaterialTheme.typography.bodyMedium, color = tokens.text2,
                modifier = Modifier.testTag("invite.meta"),
            )
            OutlinedButton(
                onClick = onNewCode, enabled = isOnline && !rotating,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("invite.newCode")
                    .semantics { if (rotating) stateDescription = "In progress" },
            ) {
                if (rotating) {
                    CircularProgressIndicator(Modifier.size(18.dp).testTag("invite.rotating"), strokeWidth = 2.dp)
                    Box(Modifier.size(8.dp))
                }
                Text("New code", color = if (isOnline && !rotating) tokens.accent else Color.Unspecified)
            }
        } else if (!isOnline) {
            Text("Connect to see the invite code.", style = MaterialTheme.typography.bodyMedium, color = tokens.text2, modifier = Modifier.testTag("invite.connect"))
        } else if (failed) {
            LiveMessage(LOAD_ERROR_MESSAGE, "invite.loadError")
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("invite.retry")) {
                Text("Try again", color = tokens.accent)
            }
        } else {
            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = tokens.text2, modifier = Modifier.testTag("invite.loading"))
        }
        if (invite != null && failed) LiveMessage(NEW_CODE_ERROR_MESSAGE, "invite.error")
        if (oldCodeMayWork) LiveMessage(OLD_CODE_MESSAGE, "invite.oldCode")
        if (notice != null) {
            Text(notice, style = MaterialTheme.typography.bodySmall, color = tokens.text2, modifier = Modifier.testTag("invite.notice").semantics { liveRegion = LiveRegionMode.Polite })
        }
        Button(
            onClick = { invite?.let { onShare(InviteSheetViewModel.link(webBaseUrl, it.code)) } }, enabled = actionsEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = Color.White),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("invite.share"),
        ) { Text("Share link", fontWeight = FontWeight.SemiBold) }
    }
}

/** An error or advisory text in a polite live region, so TalkBack announces it without moving focus. */
@Composable
private fun LiveMessage(message: String, tag: String) {
    Text(
        message, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.danger,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
