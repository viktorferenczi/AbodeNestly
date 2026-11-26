package com.abodenestly.shopping

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.StoreRecord
import com.abodenestly.sync.StoreRow
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

/**
 * What the C4 Stores screen shows. [loaded] is false until the first read, so the empty state does not flash. [pending]
 * are deletes inside their undo window, newest first. [settling] are deletes already written whose change the read has
 * not re-emitted yet: they stay hidden, so the row does not flash back for a frame. [writeFailed] is set when a write
 * could not be stored locally.
 */
data class StoresUiState(
    val stored: List<StoreRow> = emptyList(),
    val pending: List<PendingStoreDelete> = emptyList(),
    val settling: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val online: Boolean = true,
    val writeFailed: Boolean = false,
) {
    private val hidden: Set<String> get() = pending.mapTo(HashSet()) { it.id } + settling

    /** Live stores by name, minus the ones whose delete is waiting. */
    val rows: List<StoreRow> get() = Stores.sorted(stored.filter { it.id !in hidden })

    /**
     * For name validation: every live store, including one whose delete is still inside its undo window (its row is not
     * written yet, so its name is taken until the delete lands).
     */
    val records: List<StoreRecord> get() = stored.map { StoreRecord(it.id, it.name) }
}

sealed interface StoresEvent {
    data class Delete(val row: StoreRow) : StoresEvent
    data class Undo(val id: String) : StoresEvent

    /** Leaving the screen or the app writes what is still waiting rather than dropping it. */
    data object Flush : StoresEvent
}

/**
 * DESIGN C4. Reads only the local database, so it works offline; every write goes through the outbox in one local
 * transaction and then [kick]s sync. A delete waits [UNDO_MILLIS] (through [delayer], so tests do not wait in real time)
 * before it is written; Undo inside the window writes nothing. Twins: iOS `StoresModel`, web `Stores.tsx`.
 */
class StoresViewModel(
    private val householdId: UUID,
    private val database: DatabaseRegistry,
    private val kick: suspend () -> Unit,
    isOnline: StateFlow<Boolean>,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(StoresUiState(online = isOnline.value))
    val state: StateFlow<StoresUiState> = mutableState.asStateFlow()

    /** Deletes still inside their window, by store id. A delete leaves this map when it is undone, flushed or written. */
    private val waiting = HashMap<String, Job>()

    /** Ids whose write has started, so the timer and a flush can never both queue it. */
    private val committing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            database.observeStores(householdId).collect { rows ->
                mutableState.update { s ->
                    val present = rows.mapTo(HashSet()) { it.id }
                    // A settling delete is done once the read no longer has the store.
                    s.copy(stored = rows, loaded = true, settling = s.settling.filterTo(HashSet()) { it in present })
                }
            }
        }
        scope.launch { isOnline.collect { online -> mutableState.update { it.copy(online = online) } } }
    }

    fun onEvent(event: StoresEvent) {
        when (event) {
            is StoresEvent.Delete -> defer(PendingStoreDelete(event.row.id, event.row.name))
            is StoresEvent.Undo -> undo(event.id)
            StoresEvent.Flush -> flush()
        }
    }

    /**
     * Saves a new store and returns its id, or null when the name is not valid or could not be stored. The store exists
     * locally and in the outbox before this returns (AC4); a write that has started finishes even if the caller is cancelled.
     */
    suspend fun create(name: String): String? {
        if (Stores.validate(name, mutableState.value.records) != null) return null
        val id = newId()
        val saved = withContext(NonCancellable) {
            try {
                createStore(database, householdId, name, now(), id)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        mutableState.update { it.copy(writeFailed = !saved) }
        if (!saved) return null
        kick()
        return id
    }

    private fun defer(action: PendingStoreDelete) {
        // A second delete of the same store replaces the first, so only one write is ever queued.
        waiting.remove(action.id)?.cancel()
        mutableState.update { s -> s.copy(pending = listOf(action) + s.pending.filter { it.id != action.id }) }
        // Lazy, so the job is registered before it can run (a dispatcher that runs it at once would otherwise finish first).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delayer(UNDO_MILLIS)
            // A cancelled wait (Undo, flush) must not write, whatever the injected delayer did.
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

    private suspend fun commit(action: PendingStoreDelete) {
        if (!committing.add(action.id)) return
        // A write that has started finishes even when the view model is cleared right after a flush (back out of the app).
        withContext(NonCancellable) { commitOnce(action) }
        committing.remove(action.id)
    }

    private suspend fun commitOnce(action: PendingStoreDelete) {
        val written = try {
            deleteStore(database, householdId, action.id, now())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        mutableState.update { s ->
            s.copy(
                pending = s.pending.filter { it != action },
                writeFailed = !written,
                // The read may already carry the write (Room re-emits while sync is kicked): then there is nothing to
                // wait for, and a settling entry added now would hide nothing but linger until the next emission.
                settling = if (written && s.stored.any { it.id == action.id }) s.settling + action.id else s.settling,
            )
        }
        if (written) kick()
    }
}
