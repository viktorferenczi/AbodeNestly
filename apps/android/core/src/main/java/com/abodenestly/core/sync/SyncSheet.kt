package com.abodenestly.core.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens

/**
 * DESIGN B12 sync sheet: the status line, how many changes wait, the ones the server turned down (each with Retry and
 * Discard), and Sync now. It is household-wide, so it lives in `:core` rather than `:shopping`. Port of iOS `SyncSheetView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSheet(viewModel: SyncSheetViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalTokens.current.bg) {
        SyncSheetContent(viewModel)
    }
}

@Composable
internal fun SyncSheetContent(viewModel: SyncSheetViewModel) {
    val tokens = LocalTokens.current
    val ui by viewModel.state.collectAsState()
    Column(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(Tokens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Sync",
            modifier = Modifier.testTag("sync.title").semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = tokens.text,
        )
        Text(ui.statusText, modifier = Modifier.testTag("sync.status"), color = tokens.text2)
        Text("Pending changes: ${ui.pending}", modifier = Modifier.testTag("sync.pending"), color = tokens.text)
        Text("Failed changes: ${ui.failed.size}", modifier = Modifier.testTag("sync.failedCount"), color = tokens.text)
        ui.failed.forEach { change ->
            val what = "${change.table} ${actionText(change.op)}"
            Column(Modifier.fillMaxWidth().testTag("sync.failed.row"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${change.table} · ${actionText(change.op)} · Couldn't save: ${rejectionText(change.reason)}",
                    color = tokens.danger,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { viewModel.onEvent(SyncSheetEvent.Retry(change.seq)) },
                        modifier = Modifier.testTag("sync.retry").semantics { contentDescription = "Retry $what" },
                    ) { Text("Retry") }
                    TextButton(
                        onClick = { viewModel.onEvent(SyncSheetEvent.Discard(change.seq)) },
                        modifier = Modifier.testTag("sync.discard").semantics { contentDescription = "Discard $what" },
                    ) { Text("Discard") }
                }
            }
        }
        Button(onClick = { viewModel.onEvent(SyncSheetEvent.SyncNow) }, modifier = Modifier.testTag("sync.now")) { Text("Sync now") }
    }
}
