package com.abodenestly.core.notificationprefs

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.DatabaseNotOpenException
import com.abodenestly.sync.NotificationPrefRow
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * What Notification preferences shows. [types] are the visible switches; [enabled] holds one value per visible type.
 * [loaded] is false until the first read, so the switches do not flash their defaults.
 */
data class NotificationPrefsUiState(
    val loaded: Boolean = false,
    val types: List<NotificationType> = emptyList(),
    val enabled: Map<NotificationType, Boolean> = emptyMap(),
    val permissionDenied: Boolean = false,
)

sealed interface NotificationPrefsEvent {
    data class Toggle(val type: NotificationType, val enabled: Boolean) : NotificationPrefsEvent
    data object OpenSettings : NotificationPrefsEvent

    /** The screen came back to the foreground: the person may have changed the system setting. */
    data object Resumed : NotificationPrefsEvent
}

/**
 * DESIGN B9. Local database only, so it works offline. A toggle is one local transaction (row + outbox upsert, [save])
 * followed by [SyncEngine.kick]; the switch follows because the database flow re-emits.
 *
 * @param prefs my rows (`DatabaseRegistry.observeNotificationPrefs`).
 * @param notificationsAllowed `NotificationManagerCompat.areNotificationsEnabled()`, injected so tests need no system.
 * @param openSystemSettings fires `Settings.ACTION_APP_NOTIFICATION_SETTINGS`.
 */
class NotificationPrefsViewModel(
    prefs: Flow<List<NotificationPrefRow>>,
    private val modules: Set<SearchModule>,
    private val save: suspend (type: NotificationType, enabled: Boolean, now: Instant) -> Unit,
    private val sync: SyncEngine,
    private val notificationsAllowed: () -> Boolean,
    private val openSystemSettings: () -> Unit,
    private val now: () -> Instant = Instant::now,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val types = NotificationPrefs.visibleTypes(modules)
    private val mutableState = MutableStateFlow(
        NotificationPrefsUiState(types = types, enabled = types.associateWith { true }, permissionDenied = !notificationsAllowed()),
    )
    val state: StateFlow<NotificationPrefsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            prefs.collect { rows ->
                mutableState.update { it.copy(loaded = true, enabled = types.associateWith { type -> NotificationPrefs.enabled(rows, type) }) }
            }
        }
    }

    fun onEvent(event: NotificationPrefsEvent) {
        when (event) {
            is NotificationPrefsEvent.Toggle -> if (event.type in types) scope.launch {
                // A household switch can close the database under an in-flight write; the switch stays as last emitted.
                try {
                    save(event.type, event.enabled, now())
                } catch (_: DatabaseNotOpenException) {
                    return@launch
                }
                sync.kick()
            }
            NotificationPrefsEvent.OpenSettings -> openSystemSettings()
            NotificationPrefsEvent.Resumed -> mutableState.update { it.copy(permissionDenied = !notificationsAllowed()) }
        }
    }
}
