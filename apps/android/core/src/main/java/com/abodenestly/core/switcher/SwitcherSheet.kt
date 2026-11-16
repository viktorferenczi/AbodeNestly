package com.abodenestly.core.switcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.launch

/**
 * DESIGN B3 household switcher. States: one-household, many, switching. Tap switches and keeps the tab; long-press
 * opens B5 Settings. Port of iOS `SwitcherView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitcherSheet(viewModel: SwitcherViewModel, onDismiss: () -> Unit, onCreateOrJoin: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalTokens.current.bg) {
        SwitcherContent(viewModel, onDismiss, onCreateOrJoin)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SwitcherContent(viewModel: SwitcherViewModel, onDismiss: () -> Unit, onCreateOrJoin: () -> Unit) {
    val tokens = LocalTokens.current
    val ui by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.load() }
    val switching = ui.state is SwitcherState.Switching

    Column(Modifier.fillMaxWidth().heightIn(min = 160.dp)) {
        Text(
            "Households",
            modifier = Modifier.padding(Tokens.ScreenPadding).testTag("switcher.title").semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = tokens.text,
        )
        (ui.state as? SwitcherState.Switching)?.let { state ->
            Row(
                Modifier.padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp).testTag("switcher.switching").semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Switching to ${state.name}…", color = tokens.text2)
            }
        }
        ui.errorText?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp).testTag("switcher.error"),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.danger,
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ui.rows.forEach { row ->
                // Tap and long-press on one element (no Button): a long press must not also fire the tap (double switch).
                // While a switch runs nothing in the list takes touches.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Tokens.MinTouchTarget)
                        .combinedClickable(
                            enabled = !switching,
                            role = Role.Button,
                            onClick = { scope.launch { if (viewModel.select(row.id)) onDismiss() } },
                            onLongClick = { scope.launch { if (viewModel.openSettings(row.id)) onDismiss() } },
                        )
                        .padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp)
                        .testTag(row.tag)
                        .semantics(mergeDescendants = true) {
                            selected = row.isCurrent
                            customActions = listOf(
                                CustomAccessibilityAction("Household settings") {
                                    scope.launch { if (viewModel.openSettings(row.id)) onDismiss() }
                                    true
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The check keeps its width on other rows so names line up.
                    Text(if (row.isCurrent) "✓" else "", modifier = Modifier.width(24.dp).testTag("${row.tag}.check"), color = tokens.accent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(row.household.name, color = tokens.text)
                        Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = tokens.text2)
                    }
                }
                HorizontalDivider(color = tokens.line)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.MinTouchTarget)
                    .combinedClickable(enabled = !switching, role = Role.Button, onClick = onCreateOrJoin)
                    .padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp)
                    .testTag("switcher.create"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", modifier = Modifier.width(24.dp), color = tokens.accent)
                Spacer(Modifier.width(12.dp))
                Text("Create or join another", color = tokens.accent)
            }
        }
    }
}
