package com.abodenestly.core.members

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abodenestly.contract.models.Member
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.MemberRow
import com.abodenestly.core.invite.InviteSheet
import com.abodenestly.core.invite.InviteSheetViewModel
import com.abodenestly.ui.Avatar
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.OfflineBanner
import com.abodenestly.ui.Tokens
import com.abodenestly.ui.UndoToast
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * DESIGN B6: member rows (avatar, name, role, "You"), the owner overflow menu, Leave and the Invite sheet.
 * Everything is read-only offline, with the list taken from the last snapshot. There is no role gating on
 * Invite: owners and members both invite (SPEC §4 CORE-03).
 */
@Composable
fun MembersScreen(
    householdId: UUID,
    meId: UUID,
    api: ApiService,
    isOnline: Boolean,
    webBaseUrl: String,
    cache: MembersCache,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: ((String) -> Unit)? = null,
) {
    val tokens = LocalTokens.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // The view model reads the latest connectivity and callback, so it need not be rebuilt when they change.
    val online by rememberUpdatedState(isOnline)
    val left by rememberUpdatedState(onLeft)
    val vm = remember(householdId, meId) {
        MembersViewModel(householdId, meId, api, cache, isOnline = { online }, onLeft = { left() })
    }
    val state by vm.state.collectAsState()
    // Created on tap and kept for the sheet's life, so a parent recomposition never resets its state.
    var invite by remember { mutableStateOf<InviteSheetViewModel?>(null) }
    val actionsEnabled = isOnline && !state.isBusy

    // Loads on entry and again when connectivity changes.
    LaunchedEffect(isOnline) { vm.load() }
    // A pending removal is sent now rather than dropped: Undo is the only cancel. The view model keeps its own scope.
    DisposableEffect(vm) { onDispose { vm.flushPending() } }

    Box(modifier.fillMaxSize().testTag("members.screen")) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Tokens.ScreenPadding),
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
                    enabled = isOnline,
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("members.invite"),
                ) { Text("Invite", fontWeight = FontWeight.SemiBold) }
            }
            if (!isOnline) OfflineBanner("You're offline. Connect to change household settings.")
            when (state.loadState) {
                MembersLoadState.Loading ->
                    Text("Loading…", color = tokens.text2, modifier = Modifier.testTag("members.loading"))
                MembersLoadState.OfflineNoCache ->
                    Text("Connect to see the members.", color = tokens.text2, modifier = Modifier.testTag("members.connect"))
                MembersLoadState.Error -> {
                    Text("Couldn't load members. Try again.", color = tokens.danger, modifier = Modifier.testTag("members.error"))
                    OutlinedButton(
                        onClick = { scope.launch { vm.load() } },
                        modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("members.retry"),
                    ) { Text("Try again", color = tokens.accent) }
                }
                MembersLoadState.Loaded -> {
                    Column {
                        state.visibleRows.forEach { row ->
                            MemberRowItem(
                                row, meId, canRemove = MembersViewModel.canRemove(state.viewerRole, row, meId),
                                actionsEnabled = actionsEnabled,
                                onMakeOwner = { scope.launch { vm.makeOwner(row) } },
                                onRemove = { vm.remove(row) },
                            )
                            HorizontalDivider(color = tokens.line)
                        }
                    }
                    OutlinedButton(
                        onClick = vm::leave,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("members.leave"),
                    ) { Text("Leave household", color = if (actionsEnabled) tokens.danger else tokens.text2) }
                }
            }
        }
        state.toast?.let { message ->
            UndoToast(message, onUndo = vm::undo, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    state.alert?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissAlert,
            title = { Text("Members") },
            text = { Text(message, modifier = Modifier.testTag("members.alert")) },
            confirmButton = { TextButton(onClick = vm::dismissAlert, modifier = Modifier.testTag("members.alert.ok")) { Text("OK") } },
        )
    }
    invite?.let { model ->
        if (onShare != null) {
            InviteSheet(model, isOnline, webBaseUrl, onClose = { invite = null }, onShare = onShare)
        } else {
            InviteSheet(model, isOnline, webBaseUrl, onClose = { invite = null })
        }
    }
}

@Composable
private fun MemberRowItem(
    row: MemberRow,
    meId: UUID,
    canRemove: Boolean,
    actionsEnabled: Boolean,
    onMakeOwner: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = LocalTokens.current
    val name = MembersViewModel.displayName(row)
    val id = row.id.toString()
    val role = if (row.member.role == Member.Role.owner) "Owner" else "Member"
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).padding(vertical = 8.dp).testTag("members.row.$id"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(MembersViewModel.initials(name))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = tokens.text, modifier = Modifier.testTag("members.row.$id.name"))
            Text(role, style = MaterialTheme.typography.bodySmall, color = tokens.text2, modifier = Modifier.testTag("members.row.$id.role"))
        }
        if (row.id == meId) {
            Text(
                "You", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tokens.accent,
                modifier = Modifier.background(tokens.accentBg, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("members.row.$id.you"),
            )
        }
        if (canRemove) OverflowMenu(row, name, id, actionsEnabled, onMakeOwner, onRemove)
    }
}

@Composable
private fun OverflowMenu(
    row: MemberRow,
    name: String,
    id: String,
    actionsEnabled: Boolean,
    onMakeOwner: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = actionsEnabled,
            modifier = Modifier.size(Tokens.MinTouchTarget).semantics { contentDescription = "More actions for $name" }
                .testTag("members.row.$id.menu"),
        ) { Text("⋮", color = LocalTokens.current.text2, style = MaterialTheme.typography.titleLarge) }
        // Items are disabled unless actions are enabled, so a stale open menu cannot act offline or mid-removal.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (row.member.role != Member.Role.owner) {
                DropdownMenuItem(
                    text = { Text("Make owner") },
                    enabled = actionsEnabled,
                    onClick = { open = false; onMakeOwner() },
                    modifier = Modifier.testTag("members.row.$id.makeOwner"),
                )
            }
            DropdownMenuItem(
                text = { Text("Remove", color = LocalTokens.current.danger) },
                enabled = actionsEnabled,
                onClick = { open = false; onRemove() },
                modifier = Modifier.testTag("members.row.$id.remove"),
            )
        }
    }
}
