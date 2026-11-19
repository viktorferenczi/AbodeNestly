package com.abodenestly.core.recentlydeleted

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.DeletedRow
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
 * What Recently deleted shows. [sections] has one entry per enabled module; [selected] is the segment on screen (null when
 * no module is on). [loaded] is false until the first read, so the empty state does not flash.
 */
data class RecentlyDeletedUiState(
    val loaded: Boolean = false,
    val sections: List<DeletedSection> = emptyList(),
    val selected: SearchModule? = null,
) {
    /** DESIGN B11: `empty` when nothing is restorable in any enabled module, otherwise `populated`. */
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
    val items: List<DeletedItem> get() = sections.firstOrNull { it.module == selected }?.items.orEmpty()
}

sealed interface RecentlyDeletedEvent {
    data class Select(val module: SearchModule) : RecentlyDeletedEvent
    data class Restore(val item: DeletedItem) : RecentlyDeletedEvent
}

/**
 * DESIGN B11. Local database only, so it works offline. Restore is one local transaction (clear `deleted_at` + queue the
 * upsert, [restore]) followed by [SyncEngine.kick]; the row leaves because the database flow re-emits.
 *
 * @param rows soft-deleted rows of the enabled modules (`DatabaseRegistry.deletedRows`).
 * @param now injected so the 30-day window is exact in tests.
 */
class RecentlyDeletedViewModel(
    rows: Flow<List<DeletedRow>>,
    private val modules: Set<SearchModule>,
    private val restore: suspend (table: String, rowId: String, now: Instant) -> Unit,
    private val sync: SyncEngine,
    private val now: () -> Instant = Instant::now,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(RecentlyDeletedUiState(selected = SearchModule.entries.firstOrNull { it in modules }))
    val state: StateFlow<RecentlyDeletedUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            rows.collect { list -> mutableState.update { it.copy(loaded = true, sections = RecentlyDeleted.sections(list, modules, now())) } }
        }
    }

    fun onEvent(event: RecentlyDeletedEvent) {
        when (event) {
            is RecentlyDeletedEvent.Select -> if (event.module in modules) mutableState.update { it.copy(selected = event.module) }
            is RecentlyDeletedEvent.Restore -> scope.launch {
                restore(event.item.table, event.item.id, now())
                sync.kick()
            }
        }
    }
}
