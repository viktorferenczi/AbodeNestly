package com.abodenestly.core.sync

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.FailedChange
import com.abodenestly.sync.SyncEngine
import com.abodenestly.sync.SyncPhase
import com.abodenestly.ui.SyncChipState
import com.abodenestly.ui.syncChipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** DESIGN B12 states: `idle` (nothing waiting), `pending` (writes queued), `failed` (some were turned down). */
data class SyncSheetState(
    val phase: SyncPhase = SyncPhase.Idle,
    val online: Boolean = true,
    val pending: Int = 0,
    val failed: List<FailedChange> = emptyList(),
) {
    private val offline get() = !online || phase == SyncPhase.Offline

    /** What the TopBar chip shows; the same numbers as the sheet. */
    val chip: SyncChipState get() = syncChipState(phase == SyncPhase.Syncing, offline, pending, failed.size)

    val statusText: String
        get() = when {
            phase == SyncPhase.Syncing -> "Syncing…"
            offline -> "Offline — changes will sync when you're back online"
            else -> "Up to date"
        }
}

sealed interface SyncSheetEvent {
    data object SyncNow : SyncSheetEvent
    data class Retry(val seq: Long) : SyncSheetEvent
    data class Discard(val seq: Long) : SyncSheetEvent
}

/** The wording of a rejection reason, as web and iOS word it. Anything else reads "unknown". */
fun rejectionText(reason: String): String = when (reason) {
    "rls_denied" -> "not allowed"
    "validation" -> "invalid"
    "module_disabled" -> "module turned off"
    else -> "unknown"
}

fun actionText(op: String): String = when (op) {
    "upsert" -> "save"
    "delete" -> "delete"
    "delta" -> "adjust"
    else -> op
}

/**
 * Everything the chip and the sheet show about sync for one household (SHOP-08). Local database and engine only, so it
 * works offline and with every module off. [online] is the network monitor's flag.
 */
class SyncSheetViewModel(
    householdId: UUID,
    database: DatabaseRegistry,
    private val sync: SyncEngine,
    online: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<SyncSheetState> = combine(
        sync.phase, online, database.observeSyncCounts(householdId), database.observeFailed(householdId),
    ) { phase, isOnline, counts, failed -> SyncSheetState(phase, isOnline, counts.pending, failed) }
        .stateIn(scope, SharingStarted.Eagerly, SyncSheetState(sync.phase.value, online.value))

    fun onEvent(event: SyncSheetEvent) {
        when (event) {
            SyncSheetEvent.SyncNow -> sync.kick()
            is SyncSheetEvent.Retry -> scope.launch { sync.retry(event.seq) }
            is SyncSheetEvent.Discard -> scope.launch { sync.discard(event.seq) }
        }
    }
}
