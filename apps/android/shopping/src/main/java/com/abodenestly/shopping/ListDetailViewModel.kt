package com.abodenestly.shopping

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListDetail
import com.abodenestly.sync.ListItemRecord
import com.abodenestly.sync.patch
import com.abodenestly.sync.remove
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/** An item delete waiting out its undo window. The row is already hidden. */
data class PendingItemDelete(val id: String, val name: String) {
    val message: String get() = "Deleted $name"
}

/**
 * What the C2 list detail shows (SHOP-02 slice) and the C3 sheet (SHOP-03). [loaded] is false until the first read, so
 * "This list is gone" does not flash. [query] is the quick-add field's text. [sheet] is what the item sheet is open on.
 * [pending] are deletes inside their undo window, newest first; [settling] are deletes already written whose removal
 * the list has not re-emitted yet. Both stay hidden, so a row never flashes back. [saveFailed] keeps the sheet's message up.
 */
data class ListDetailUiState(
    val detail: ListDetail = ListDetail(),
    val loaded: Boolean = false,
    val query: String = "",
    val online: Boolean = true,
    val error: String? = null,
    val listId: String = "",
    val sheet: SheetState? = null,
    val saveFailed: Boolean = false,
    val pending: List<PendingItemDelete> = emptyList(),
    val settling: Set<String> = emptySet(),
) {
    val isGone: Boolean get() = loaded && detail.list == null

    val suggestions: List<Suggestion> get() = QuickAdd.suggest(detail.history, query, listId)

    private val hidden: Set<String> get() = pending.mapTo(HashSet()) { it.id } + settling

    private val visible: List<ListItemRecord>
        get() {
            val gone = hidden
            return detail.items.filter { it.id !in gone }
        }

    /** Oldest first, so a new item lands at the bottom; the id breaks ties so the order never flickers. */
    val unchecked: List<ListItemRecord>
        get() = visible.filter { !it.checked }.sortedWith(compareBy<ListItemRecord>({ it.createdAt ?: "" }, { it.id }))

    /** Newest first. */
    val checked: List<ListItemRecord>
        get() = visible.filter { it.checked }.sortedWith(compareByDescending<ListItemRecord> { it.createdAt ?: "" }.thenBy { it.id })

    /** The row the sheet edits; null for `new`, and when that row is gone. */
    val editing: ListItemRecord?
        get() = (sheet as? SheetState.Edit)?.let { edit -> detail.items.firstOrNull { it.id == edit.itemId } }
}

sealed interface ListDetailEvent {
    data class QueryChanged(val text: String) : ListDetailEvent

    /** Return or the IME action: adds exactly the typed text, never an auto-picked suggestion. */
    data object Submit : ListDetailEvent

    /** A tapped suggestion: adds its label. */
    data class Pick(val suggestion: Suggestion) : ListDetailEvent

    data object ErrorShown : ListDetailEvent

    /** The bar's Details button: the C3 sheet for a new item, with the typed text as its name. */
    data class OpenNew(val prefill: String) : ListDetailEvent

    /** A tapped row: the C3 sheet for that item. */
    data class OpenEdit(val itemId: String) : ListDetailEvent

    /** Cancel or a dismissed sheet: nothing is written. */
    data object CloseSheet : ListDetailEvent

    data class SaveSheet(val form: ItemForm) : ListDetailEvent

    data class Delete(val itemId: String) : ListDetailEvent

    data class Undo(val itemId: String) : ListDetailEvent

    /** Leaving the screen or the app writes what is still waiting rather than dropping it. */
    data object Flush : ListDetailEvent
}

/**
 * DESIGN C2 and C3. Reads only the local database, so it works offline; an add or an edit goes through the outbox in one
 * local transaction and then [kick]s sync. A delete waits [UNDO_MILLIS] (through [delayer], so tests do not wait in real
 * time) before it is written; Undo inside the window writes nothing. Twins: iOS `ListDetailModel`, web `ListDetail.tsx`.
 */
class ListDetailViewModel(
    private val householdId: UUID,
    private val listId: String,
    private val userId: UUID?,
    private val database: DatabaseRegistry,
    private val kick: suspend () -> Unit,
    isOnline: StateFlow<Boolean>,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(ListDetailUiState(online = isOnline.value, listId = listId))
    val state: StateFlow<ListDetailUiState> = mutableState.asStateFlow()

    /** Deletes still inside their window, by item id. A delete leaves this map when it is undone, flushed or written. */
    private val waiting = HashMap<String, Job>()

    /** A sheet save is running: a second tap on Save must not queue a second row. */
    private var savingSheet = false

    /** Ids whose write has started, so the timer and a flush can never both queue it. */
    private val committing = HashSet<String>()

    init {
        scope.launch {
            database.observeListDetail(householdId, listId).collect { detail ->
                mutableState.update { s ->
                    val present = detail.items.mapTo(HashSet()) { it.id }
                    // An edited row that is gone (deleted on another device) closes its sheet.
                    val sheet = s.sheet.takeUnless { it is SheetState.Edit && it.itemId !in present }
                    s.copy(detail = detail, loaded = true, sheet = sheet, settling = s.settling.filterTo(HashSet()) { it in present })
                }
            }
        }
        scope.launch { isOnline.collect { online -> mutableState.update { it.copy(online = online) } } }
    }

    fun onEvent(event: ListDetailEvent) {
        when (event) {
            is ListDetailEvent.QueryChanged -> mutableState.update { it.copy(query = event.text) }
            ListDetailEvent.Submit -> add(mutableState.value.query)
            is ListDetailEvent.Pick -> add(event.suggestion.label)
            ListDetailEvent.ErrorShown -> mutableState.update { it.copy(error = null) }
            is ListDetailEvent.OpenNew -> openNew(event.prefill)
            is ListDetailEvent.OpenEdit -> openEdit(event.itemId)
            ListDetailEvent.CloseSheet -> mutableState.update { it.copy(sheet = null, saveFailed = false) }
            is ListDetailEvent.SaveSheet -> save(event.form)
            is ListDetailEvent.Delete -> delete(event.itemId)
            is ListDetailEvent.Undo -> undo(event.itemId)
            ListDetailEvent.Flush -> flush()
        }
    }

    // --- Quick add (SHOP-02) ---

    /**
     * Adds [name] as typed. The row exists locally and in the outbox before the field clears (AC4); a write that cannot
     * be stored keeps the text and shows [SAVE_ERROR]. A no-op until the first read has shown the list, and when it is
     * gone, so a submit never queues a `list_item` for a missing list. Nothing waits on a later emission: the list updates when Room re-emits.
     */
    private fun add(name: String) {
        val current = mutableState.value
        if (!current.loaded || current.detail.list == null) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val typed = mutableState.value.query
        scope.launch {
            val at = now()
            val row = QuickAdd.row(newId(), listId, trimmed, userId, at)
            try {
                addItem(database, householdId, row, at)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(error = SAVE_ERROR) }
                return@launch
            }
            // Text typed while the write ran stays.
            mutableState.update { it.copy(error = null, query = if (it.query == typed) "" else it.query) }
            kick()
        }
    }

    // --- Item sheet (SHOP-03) ---

    private fun openNew(prefill: String) {
        // Same guard as an add: a sheet for a list that is not shown (or is gone) could only queue a row for nothing.
        val current = mutableState.value
        if (!current.loaded || current.detail.list == null) return
        mutableState.update { it.copy(sheet = SheetState.New(prefill), saveFailed = false) }
    }

    private fun openEdit(itemId: String) {
        val current = mutableState.value
        if (current.liveItem(itemId) == null) return
        mutableState.update { it.copy(sheet = SheetState.Edit(itemId), saveFailed = false) }
    }

    private fun ListDetailUiState.liveItem(itemId: String): ListItemRecord? =
        if (pending.any { it.id == itemId } || itemId in settling) null else detail.items.firstOrNull { it.id == itemId }

    /**
     * Saves the open sheet. `new` adds the row with the sheet's quantity, unit and note; `edit` writes only the changed
     * columns (and nothing when there are none). The sheet closes once the write is stored; a write that cannot be
     * stored keeps it open with the input and shows [SAVE_ERROR] (the sheet reads [ListDetailUiState.saveFailed]).
     */
    private fun save(form: ItemForm) {
        val current = mutableState.value
        val sheet = current.sheet ?: return
        val fields = ItemSheetLogic.fields(form) ?: return
        if (savingSheet) return
        when (sheet) {
            is SheetState.New -> {
                if (!current.loaded || current.detail.list == null) return
                savingSheet = true
                mutableState.update { it.copy(saveFailed = false) }
                scope.launch {
                    val at = now()
                    val row = QuickAdd.row(newId(), listId, fields.name, userId, at, fields.quantity, fields.unit, fields.note)
                    val ok = stored { addItem(database, householdId, row, at) }
                    savingSheet = false
                    if (!ok) return@launch
                    // The bar's text goes with the item it became; text typed meanwhile stays.
                    mutableState.update { it.copy(sheet = null, saveFailed = false, query = if (it.query == sheet.prefill) "" else it.query) }
                    kick()
                }
            }
            is SheetState.Edit -> {
                val before = current.detail.items.firstOrNull { it.id == sheet.itemId } ?: return
                val changes = ItemSheetLogic.changes(before, fields)
                if (changes.isEmpty) {
                    mutableState.update { it.copy(sheet = null, saveFailed = false) }
                    return
                }
                savingSheet = true
                mutableState.update { it.copy(saveFailed = false) }
                scope.launch {
                    val at = now()
                    val ok = stored {
                        database.patch(householdId, "list_item", before.id, changes.fields, at)
                        // name_key is server-owned: locally it keeps suggestions matching until the next pull.
                        changes.nameKey?.let { key ->
                            database.patch(householdId, "list_item", before.id, JsonObject(mapOf("name_key" to JsonPrimitive(key))), at, localOnly = true)
                        }
                    }
                    savingSheet = false
                    if (!ok) return@launch
                    mutableState.update { it.copy(sheet = null, saveFailed = false) }
                    kick()
                }
            }
        }
    }

    /** Runs a local write for the sheet. A write that cannot be stored sets [ListDetailUiState.saveFailed] and returns false. */
    private suspend fun stored(block: suspend () -> Unit): Boolean {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(saveFailed = true) }
            return false
        }
        return true
    }

    // --- Delete with undo (SHOP-03) ---

    private fun delete(itemId: String) {
        val item = mutableState.value.liveItem(itemId) ?: return
        val action = PendingItemDelete(item.id, item.name)
        waiting.remove(itemId)?.cancel()
        mutableState.update { s ->
            s.copy(
                pending = listOf(action) + s.pending.filter { it.id != itemId },
                // Deleting from the sheet closes it.
                sheet = if ((s.sheet as? SheetState.Edit)?.itemId == itemId) null else s.sheet,
            )
        }
        // Lazy, so the job is registered before it can run (a dispatcher that runs it at once would otherwise finish first).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delayer(UNDO_MILLIS)
            // A cancelled wait (Undo, flush) must not write, whatever the injected delayer did.
            ensureActive()
            waiting.remove(itemId)
            commit(action)
        }
        waiting[itemId] = job
        job.start()
    }

    private fun undo(itemId: String) {
        // Past the window (or flushed) the write has started: Undo has nothing left to cancel.
        val job = waiting.remove(itemId) ?: return
        job.cancel()
        mutableState.update { s -> s.copy(pending = s.pending.filter { it.id != itemId }) }
    }

    private fun flush() {
        val toWrite = mutableState.value.pending.filter { it.id in waiting }
        if (toWrite.isEmpty()) return
        toWrite.forEach { waiting.remove(it.id)?.cancel() }
        scope.launch { toWrite.forEach { commit(it) } }
    }

    private suspend fun commit(action: PendingItemDelete) {
        if (!committing.add(action.id)) return
        // A write that has started finishes even when the view model is cleared right after a flush (back out of the app).
        withContext(NonCancellable) { commitOnce(action) }
        committing.remove(action.id)
    }

    private suspend fun commitOnce(action: PendingItemDelete) {
        val written = try {
            database.remove(householdId, "list_item", action.id, now())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        mutableState.update { s ->
            s.copy(
                pending = s.pending.filter { it.id != action.id },
                error = if (written) s.error else SAVE_ERROR,
                // The list may already carry the delete (Room re-emits while sync is kicked): then there is nothing to
                // wait for, and a settling entry added now would only linger until the next emission.
                settling = if (written && s.detail.items.any { it.id == action.id }) s.settling + action.id else s.settling,
            )
        }
        if (written) kick()
    }
}
