package com.abodenestly.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abodenestly.sync.ListRecord
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens

/** The "Add all to…" sheet as [ListDetailScreenHost] mounts it; tests mount the sheet's body inline instead of a popup. */
internal typealias AddAllSlot = @Composable (
    targets: List<ListRecord>,
    failed: Boolean,
    onPick: (ListRecord) -> Unit,
    onClose: () -> Unit,
) -> Unit

/** DESIGN C2 sheet "Add all to…" (SHOP-11) in a [ModalBottomSheet]. Dismissing it is Cancel: nothing is written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAllSheet(targets: List<ListRecord>, failed: Boolean, onPick: (ListRecord) -> Unit, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = LocalTokens.current.bg) {
        AddAllSheetContent(targets, failed, onPick, onClose)
    }
}

/**
 * The sheet's body: the active non-template lists, oldest first ([targets] comes from [Templates.targets]), or "Make a
 * list first" when there is none. A tap on a list picks it; the view model closes the sheet unless the copy failed, which
 * [failed] says in place (an assertive live region, so TalkBack reads it while focus stays on the tapped row). Tested
 * directly so Robolectric never drives the popup.
 */
@Composable
internal fun AddAllSheetContent(targets: List<ListRecord>, failed: Boolean, onPick: (ListRecord) -> Unit, onClose: () -> Unit) {
    val tokens = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding).testTag("addall.sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Add all to…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
            modifier = Modifier.semantics { heading() },
        )
        if (targets.isEmpty()) {
            Text(
                "Make a list first", style = MaterialTheme.typography.bodyLarge, color = tokens.text2,
                modifier = Modifier.testTag("addall.none"),
            )
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                targets.forEach { list ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                            .clickable(role = Role.Button) { onPick(list) }
                            .testTag("addall.row.${list.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(list.name, style = MaterialTheme.typography.bodyLarge, color = tokens.text)
                    }
                }
            }
        }
        if (failed) {
            Text(
                ADD_ALL_ERROR, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("addall.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("addall.cancel")) {
            Text("Cancel", color = tokens.accent)
        }
    }
}
