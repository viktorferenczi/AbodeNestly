package com.abodenestly.shopping

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListOverview
import com.abodenestly.sync.StoreRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** A swipe waiting out its undo window. The list is already hidden from the sections. */
data class PendingListAction(val id: String, val kind: Kind, val name: String) {
    enum class Kind { Archive, Delete }

    val message: String get() = "${if (kind == Kind.Archive) "Archived" else "Deleted"} $name"
}

/**
 * What the C1 overview shows. [loaded] is false until the first read, so the empty state does not flash. [pending] are
 * swipes inside their undo window, newest first. [settling] are swipes already written whose change the overview has not
 * re-emitted yet: they stay hidden, so the row does not flash back for a frame. [opened] is the id of a list just
 * created, for the screen to open once (it answers with [ListsEvent.OpenedHandled]). [createFailed] keeps the new-list sheet's message up.
 */
data class ListsUiState(
    val overview: ListOverview = ListOverview(),
    val pending: List<PendingListAction> = emptyList(),
    val settling: Map<String, PendingListAction.Kind> = emptyMap(),
    val loaded: Boolean = false,
    val online: Boolean = true,
    val error: String? = null,
    val createFailed: Boolean = false,
    val opened: String? = null,
) {
    val sections: ListsSections get() = ListsSections.make(overview, pending.mapTo(HashSet()) { it.id } + settling.keys)

    /** Live stores for the picker, by name. */
    val stores: List<StoreRecord> get() = overview.stores.sortedBy { it.name.lowercase() }
}

sealed interface ListsEvent {
    data class Archive(val row: ListRowModel) : ListsEvent
    data class Delete(val row: ListRowModel) : ListsEvent
    data class Restore(val row: ListRowModel) : ListsEvent
    data class Undo(val id: String) : ListsEvent
    data class Create(val name: String, val storeId: String?) : ListsEvent
    data object OpenedHandled : ListsEvent

    /** Leaving the screen or the app writes what is still waiting rather than dropping it. */
    data object Flush : ListsEvent
}

const val SAVE_ERROR = "Could not save the change. Try again."

/**
 * DESIGN C1. Reads only the local database, so it works offline; every write goes through the outbox in one local
 * transaction and then [kick]s sync. Archive and Delete wait [UNDO_MILLIS] (through [delayer], so tests do not wait in
 * real time) before they are written; Undo inside the window writes nothing (AC4). Twins: iOS `ListsModel`, web `Lists.tsx`.
 */
class ListsViewModel(
    private val householdId: UUID,
    private val database: DatabaseRegistry,
    private val kick: suspend () -> Unit,
    isOnline: StateFlow<Boolean>,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(ListsUiState(online = isOnline.value))
    val state: StateFlow<ListsUiState> = mutableState.asStateFlow()

    /** Swipes still inside their window, by list id. A swipe leaves this map when it is undone, flushed or written. */
    private val waiting = HashMap<String, Job>()

    init {
        scope.launch {
            database.observeListOverview(householdId).collect { overview ->
                mutableState.update { s ->
                    s.copy(
                        overview = overview, loaded = true,
                        settling = s.settling.filter { (id, kind) -> !settled(overview, id, kind) },
                    )
                }
            }
        }
        scope.launch { isOnline.collect { online -> mutableState.update { it.copy(online = online) } } }
    }

    fun onEvent(event: ListsEvent) {
        when (event) {
            is ListsEvent.Archive -> defer(PendingListAction(event.row.id, PendingListAction.Kind.Archive, event.row.name))
            is ListsEvent.Delete -> defer(PendingListAction(event.row.id, PendingListAction.Kind.Delete, event.row.name))
            is ListsEvent.Undo -> undo(event.id)
            // Restore of an archived list: written at once (no toast), only the changed column queued.
            is ListsEvent.Restore -> scope.launch { write { restoreList(database, householdId, event.row.id, now()) } }
            is ListsEvent.Create -> create(event.name, event.storeId)
            ListsEvent.OpenedHandled -> mutableState.update { it.copy(opened = null) }
            ListsEvent.Flush -> flush()
        }
    }

    private fun create(name: String, storeId: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            mutableState.update { it.copy(error = null, createFailed = false) }
            val id = newId()
            // AC5, AC6: the list exists locally and in the outbox before the screen is told to open it.
            val saved = write { createList(database, householdId, trimmed, storeId, now(), id) }
            mutableState.update { if (saved) it.copy(opened = id) else it.copy(createFailed = true) }
        }
    }

    private fun defer(action: PendingListAction) {
        // A second swipe on the same list replaces the first, so only one write is ever queued.
        waiting.remove(action.id)?.cancel()
        mutableState.update { s -> s.copy(pending = listOf(action) + s.pending.filter { it.id != action.id }) }
        // Lazy, so the job is registered before it can run (a dispatcher that runs it at once would otherwise finish first).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delayer(UNDO_MILLIS)
            // A cancelled wait (Undo, flush, a second swipe) must not write, whatever the injected delayer did.
            ensureActive()
            waiting.remove(action.id)
            commit(action)
        }
        waiting[action.id] = job
        job.start()
    }

    private fun undo(id: String) {
        // Past the window (or flushed) the write has started: Undo has nothing left to cancel.
        val job = waiting.remove(id) ?: return
        job.cancel()
        mutableState.update { s -> s.copy(pending = s.pending.filter { it.id != id }) }
    }

    private fun flush() {
        val toWrite = mutableState.value.pending.filter { it.id in waiting }
        if (toWrite.isEmpty()) return
        toWrite.forEach { waiting.remove(it.id)?.cancel() }
        scope.launch { toWrite.forEach { commit(it) } }
    }

    /** Ids whose write has started, so the timer and a flush can never both queue it. */
    private val committing = HashSet<String>()

    private suspend fun commit(action: PendingListAction) {
        if (!committing.add(action.id)) return
        // A write that has started finishes even when the view model is cleared right after a flush (back out of the app).
        withContext(NonCancellable) { commitOnce(action) }
        committing.remove(action.id)
    }

    private suspend fun commitOnce(action: PendingListAction) {
        val written = write {
            when (action.kind) {
                PendingListAction.Kind.Archive -> archiveList(database, householdId, action.id, now())
                PendingListAction.Kind.Delete -> deleteList(database, householdId, action.id, now())
            }
        }
        mutableState.update { s ->
            s.copy(
                pending = s.pending.filter { it.id != action.id },
                settling = if (written) s.settling + (action.id to action.kind) else s.settling,
            )
        }
    }

    /** Runs a local write, then kicks sync. A write that cannot be stored shows [SAVE_ERROR] and returns false. */
    private suspend fun write(block: suspend () -> Unit): Boolean {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(error = SAVE_ERROR) }
            return false
        }
        mutableState.update { it.copy(error = null) }
        kick()
        return true
    }

    private fun settled(overview: ListOverview, id: String, kind: PendingListAction.Kind): Boolean {
        val record = overview.lists.firstOrNull { it.id == id }
        return when (kind) {
            PendingListAction.Kind.Archive -> record == null || record.archivedAt != null
            PendingListAction.Kind.Delete -> record == null
        }
    }
}
