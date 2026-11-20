package com.abodenestly.core.notificationprefs

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.SyncEngine
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import java.util.UUID

/**
 * DESIGN B9 Notification preferences. States: idle, permission-denied (banner; the switches stay). [modules] are the
 * enabled ones; rows of the others never show.
 */
@Composable
fun NotificationPrefsScreen(
    householdId: UUID,
    userId: UUID,
    modules: Set<SearchModule>,
    database: DatabaseRegistry,
    sync: SyncEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Keyed by household, person and flags: a module switched off while this screen is open rebuilds the model.
    val vm: NotificationPrefsViewModel = viewModel(key = "notif.$householdId.$userId.${modules.sortedBy { it.ordinal }}") {
        NotificationPrefsViewModel(
            database.observeNotificationPrefs(householdId, userId), modules,
            save = { type, enabled, now -> database.setNotificationPref(userId, householdId, type.key, enabled, now) }, sync,
            notificationsAllowed = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
            openSystemSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
    // Back from the system settings the permission may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, LocalLifecycleOwner.current) { vm.onEvent(NotificationPrefsEvent.Resumed) }
    val ui by vm.state.collectAsState()
    NotificationPrefsContent(ui, vm::onEvent, modifier)
}

@Composable
internal fun NotificationPrefsContent(ui: NotificationPrefsUiState, onEvent: (NotificationPrefsEvent) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Column(modifier.fillMaxSize().padding(Tokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notification preferences", style = MaterialTheme.typography.titleLarge, color = tokens.text, modifier = Modifier.semantics { heading() })
        if (!ui.loaded) return@Column
        if (ui.permissionDenied) {
            Text(
                "Notifications are turned off for AbodeNestly in Android settings. Open the system notification settings to receive these notices.",
                color = tokens.text,
                modifier = Modifier.testTag("notif.denied").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ui.types.forEach { type ->
            val on = ui.enabled[type] ?: true
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                    .toggleable(value = on, role = Role.Switch, onValueChange = { onEvent(NotificationPrefsEvent.Toggle(type, it)) })
                    .testTag("notif.switch.${type.key}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(type.label, color = tokens.text)
                Switch(checked = on, onCheckedChange = null)
            }
        }
        OutlinedButton(
            onClick = { onEvent(NotificationPrefsEvent.OpenSettings) },
            modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("notif.settings"),
        ) { Text("System notification settings") }
    }
}
