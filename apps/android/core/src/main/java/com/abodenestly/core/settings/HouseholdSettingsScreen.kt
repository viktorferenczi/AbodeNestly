package com.abodenestly.core.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.contract.models.Household
import com.abodenestly.core.api.ApiService
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens
import java.util.UUID

/**
 * DESIGN B5: name, module switches, currency and Leave. Read-only for members and offline.
 * Delete household is deferred (no endpoint yet).
 */
@Composable
fun HouseholdSettingsScreen(
    householdId: UUID,
    userId: UUID,
    api: ApiService,
    cache: HouseholdCache,
    isOnline: Boolean,
    onHouseholdChanged: (Household) -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held by the activity's ViewModelStore, so a pending save survives rotation, font scale and theme changes.
    val vm: HouseholdSettingsViewModel = viewModel(key = "settings.$householdId.$userId") {
        HouseholdSettingsViewModel(householdId, userId, api, cache, { isOnline }, onHouseholdChanged, onLeft)
    }
    // The instance outlives this composition, so give it the current connectivity and callbacks each time.
    SideEffect {
        vm.isOnline = { isOnline }
        vm.onHouseholdChanged = onHouseholdChanged
        vm.onLeft = onLeft
    }
    val state by vm.state.collectAsState()
    // Loads on entry and again when connectivity changes.
    LaunchedEffect(isOnline) { vm.load() }
    HouseholdSettingsContent(state, isOnline, vm::onEvent, modifier)
    if (state.confirmingLeave) LeaveSheet(state, isOnline, vm::onEvent)
}

/** The form, driven only by state and events so component tests need no view model. The Leave sheet is the screen's job. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseholdSettingsContent(
    state: HouseholdSettingsUiState,
    isOnline: Boolean,
    onEvent: (HouseholdSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTokens.current
    val household = state.shown
    val mode = settingsMode(state.role, isOnline)
    val locked = mode != SettingsMode.Owner || state.isSaving

    Column(
        modifier.fillMaxSize().testTag("settings.screen").verticalScroll(rememberScrollState()).padding(Tokens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = tokens.text, modifier = Modifier.semantics { heading() },
        )
        if (state.loadState == SettingsLoadState.Unavailable && household == null) {
            Text("Couldn't load household settings.", color = tokens.text2, modifier = Modifier.testTag("settings.unavailable"))
            return@Column
        }
        if (household == null) {
            Text("Loading…", color = tokens.text2, modifier = Modifier.testTag("settings.loading"))
            return@Column
        }
        if (mode == SettingsMode.Offline) OfflineBanner("You're offline. Connect to change household settings.")

        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(HouseholdSettingsEvent.NameChanged(it)) },
            label = { Text("Household name") },
            singleLine = true,
            enabled = !locked,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(HouseholdSettingsEvent.CommitName) }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("settings.name")
                .onFocusChanged {
                    // Losing focus commits like Done does; the first callback is the initial unfocused state.
                    if (hadFocus && !it.isFocused) onEvent(HouseholdSettingsEvent.CommitNameOnBlur)
                    hadFocus = it.isFocused
                },
        )

        Text(
            "Modules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = tokens.text, modifier = Modifier.semantics { heading() },
        )
        Column {
            ModuleKey.entries.forEach { key ->
                ModuleRow(key, on = isOn(household, key), enabled = !locked) { onEvent(HouseholdSettingsEvent.Toggle(key, it)) }
            }
        }

        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open && !locked, onExpandedChange = { open = it && !locked }) {
            OutlinedTextField(
                value = household.currency,
                onValueChange = {},
                readOnly = true,
                enabled = !locked,
                label = { Text("Currency") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && !locked) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    .heightIn(min = Tokens.MinTouchTarget).testTag("settings.currency"),
            )
            ExposedDropdownMenu(expanded = open && !locked, onDismissRequest = { open = false }) {
                currencyOptions(household.currency).forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        onClick = { open = false; onEvent(HouseholdSettingsEvent.SetCurrency(code)) },
                        modifier = Modifier.testTag("settings.currency.$code"),
                    )
                }
            }
        }

        // Visual-only status and errors are silent in TalkBack, so they are live regions (web uses aria-live).
        if (state.isSaving) {
            Text(
                "Saving…", style = MaterialTheme.typography.bodyMedium, color = tokens.text2,
                modifier = Modifier.testTag("settings.saving").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.saveError?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = tokens.danger,
                modifier = Modifier.testTag("settings.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        if (state.role != null) {
            val canLeave = mode != SettingsMode.Offline && !state.isSaving
            OutlinedButton(
                onClick = { onEvent(HouseholdSettingsEvent.BeginLeave) },
                enabled = canLeave,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("settings.leave"),
            ) { Text("Leave household", color = if (canLeave) tokens.danger else tokens.text2) }
        }
    }
}

@Composable
private fun ModuleRow(key: ModuleKey, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = LocalTokens.current
    // The whole row toggles; the Switch is decoration so TalkBack sees one control with a label and a description.
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("settings.module.${key.key}")
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(key.label, color = tokens.text)
            Text(key.summary, style = MaterialTheme.typography.bodySmall, color = tokens.text2)
        }
        Switch(
            checked = on, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
        )
    }
}

/** The confirm sheet for Leave (DESIGN B5). It cannot be dismissed while leaving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveSheet(state: HouseholdSettingsUiState, isOnline: Boolean, onEvent: (HouseholdSettingsEvent) -> Unit) {
    val leaving = state.isLeaving
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !leaving })
    ModalBottomSheet(
        onDismissRequest = { if (!leaving) onEvent(HouseholdSettingsEvent.CancelLeave) },
        sheetState = sheetState,
        containerColor = LocalTokens.current.bg,
    ) { LeaveSheetContent(state, isOnline, onEvent) }
}

/** The sheet's body: Cancel / Leave, with the last-owner refusal shown inline. Tested directly so Robolectric never drives the popup. */
@Composable
internal fun LeaveSheetContent(state: HouseholdSettingsUiState, isOnline: Boolean, onEvent: (HouseholdSettingsEvent) -> Unit) {
    val tokens = LocalTokens.current
    val leaving = state.isLeaving
    Column(
        Modifier.fillMaxWidth().testTag("settings.leave.sheet").padding(horizontal = Tokens.ScreenPadding)
            .padding(bottom = Tokens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Leave ${state.household?.name ?: "household"}?", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, color = tokens.text, modifier = Modifier.semantics { heading() },
        )
        Text("You'll lose access to this household's lists until someone invites you back.", color = tokens.text2)
        state.leaveError?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = tokens.danger,
                modifier = Modifier.testTag("settings.leave.error").semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onEvent(HouseholdSettingsEvent.CancelLeave) },
                enabled = !leaving,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("settings.leave.cancel"),
            ) { Text("Cancel", color = tokens.accent) }
            Button(
                onClick = { onEvent(HouseholdSettingsEvent.ConfirmLeave) },
                enabled = !leaving && isOnline,
                colors = ButtonDefaults.buttonColors(containerColor = tokens.danger, contentColor = Color.White),
                modifier = Modifier.weight(1f).heightIn(min = Tokens.MinTouchTarget).testTag("settings.leave.confirm"),
            ) { Text("Leave", fontWeight = FontWeight.SemiBold) }
        }
    }
}
