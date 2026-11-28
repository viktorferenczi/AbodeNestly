package com.abodenestly.shopping

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import java.util.UUID

/**
 * What the list detail needs to offer sharing (SHOP-13): the web origin the link is built on and the two API calls. Absent
 * (`null`), the List actions menu has no "Share". The calls take the list's id as text; a text that is not a UUID fails.
 */
class ShareSetup(
    val webBase: String,
    val share: suspend (String) -> ApiResult<String>,
    val stop: suspend (String) -> ApiResult<Unit>,
) {
    companion object {
        /** The app's setup: `POST` and `DELETE /lists/{id}/share` through [api]; links open on [webBase]. */
        fun forApi(webBase: String, api: ApiService): ShareSetup = ShareSetup(
            webBase = webBase,
            share = { id ->
                val uuid: UUID? = parseId(id)
                if (uuid == null) ApiResult.Failure(ApiFailure.NotFound) else api.shareList(uuid)
            },
            stop = { id ->
                val uuid: UUID? = parseId(id)
                if (uuid == null) ApiResult.Failure(ApiFailure.NotFound) else api.stopSharing(uuid)
            },
        )

        private fun parseId(id: String): UUID? = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** The C8 sheet as [ListDetailScreenHost] mounts it; tests mount the sheet's body inline instead of a popup. */
internal typealias ShareSlot = @Composable (model: ShareSheetModel, online: Boolean, onClose: () -> Unit) -> Unit

/** The system share chooser for the link (the Android-native "Share…" button). */
fun shareLinkWithChooser(context: Context, url: String) {
    val send: Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
    val chooser: Intent = Intent.createChooser(send, null)
    // An application context has no task to start the chooser in.
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/** DESIGN C8 in a [ModalBottomSheet]. Dismissing it only closes it: a link that is on stays on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(model: ShareSheetModel, online: Boolean, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = LocalTokens.current.bg) {
        ShareSheetContent(model, online)
    }
}

/**
 * The sheet's body. States: `off`, `on` (the link, "Copy link", "Share…", the unchecked-items line, "Stop sharing") and `offline`
 * (the switch and Stop sharing are disabled with "Sharing needs a connection"; a known link stays visible and copyable).
 * Errors are an assertive live region and "Link copied" a polite one. Tested directly so Robolectric never drives the popup.
 */
@Composable
internal fun ShareSheetContent(model: ShareSheetModel, online: Boolean, onSystemShare: ((String) -> Unit)? = null) {
    val tokens = LocalTokens.current
    val context: Context = LocalContext.current
    val ui: ShareSheetUiState by model.state.collectAsState()
    val enabled: Boolean = online && !ui.share.isBusy
    val link: String? = ui.link
    val systemShare: (String) -> Unit = onSystemShare ?: { url: String -> shareLinkWithChooser(context, url) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding).testTag("share.sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Share list", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
            modifier = Modifier.semantics { heading() },
        )
        // The whole row toggles, so the touch target is the row.
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                .toggleable(value = ui.share.isOn, enabled = enabled, role = Role.Switch, onValueChange = { model.setOn(it) })
                .testTag("share.switch"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Share with a link", style = MaterialTheme.typography.bodyLarge, color = tokens.text)
            Switch(checked = ui.share.isOn, onCheckedChange = null, enabled = enabled)
        }
        if (!online) {
            Text(SHARE_OFFLINE_MESSAGE, color = tokens.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share.offline"))
        }
        if (link != null) {
            SelectionContainer {
                Text(link, style = MaterialTheme.typography.bodyLarge, color = tokens.text, modifier = Modifier.fillMaxWidth().testTag("share.link"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.copyLink() }, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("share.copy")) {
                    Text("Copy link")
                }
                OutlinedButton(
                    onClick = { systemShare(link) },
                    modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("share.system"),
                ) { Text("Share…") }
            }
            if (ui.copied) {
                Text(
                    SHARE_COPIED_MESSAGE, color = tokens.text2, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("share.copied").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(SHARE_VIEW_NOTE, color = tokens.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share.note"))
            TextButton(onClick = { model.turnOff() }, enabled = enabled, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("share.stop")) {
                Text("Stop sharing", color = if (enabled) tokens.danger else tokens.text2)
            }
            Text(SHARE_STOP_HINT, color = tokens.text2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("share.stopHint"))
        }
        ui.share.error?.let {
            Text(
                it.message, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("share.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}
