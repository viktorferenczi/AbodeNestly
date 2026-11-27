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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * What the C2 list detail shows (SHOP-02 slice) and the C3 sheet (SHOP-03). [loaded] is false until the first read, so
 * "This list is gone" does not flash. [query] is the quick-add field's text. [sheet] is what the item sheet is open on.
 * [pending] are deletes, check-offs and clears inside their undo window, newest first. A delete or clear hides its rows;
 * a check-off shows its row ticked in place. [settling] are hidden rows whose removal the list has not re-emitted yet, and
 * [settlingChecks] are checked rows the list still shows unchecked, so neither flashes back. [saveFailed] keeps the sheet's message up.
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
    /** SHOP-05: the store picker is open; [pickerFailed] keeps its message up when the choice could not be stored. */
    val picker: Boolean = false,
    val pickerFailed: Boolean = false,
    val pending: List<PendingAction> = emptyList(),
    val settling: Set<String> = emptySet(),
    val settlingChecks: Set<String> = emptySet(),
) {
    val isGone: Boolean get() = loaded && detail.list == null

    /** The list's store when it points to a live store: what the chip shows and the picker starts on. */
    val currentStoreId: String? get() = detail.list?.storeId?.takeIf { id -> detail.stores.any { it.id == id } }

    val suggestions: List<Suggestion> get() = QuickAdd.suggest(detail.history, query, listId)

    private val hidden: Set<String> get() = pending.flatMapTo(HashSet()) { it.hiddenIds } + settling

    /** Rows are hidden by a delete or clear still in its undo window (or not yet re-emitted): they are not gone yet. */
    val hidingRows: Boolean get() = hidden.isNotEmpty()

    /** Unchecked rows shown ticked and struck through until their check-off is written and the list shows it. */
    val pendingCheckIds: Set<String> get() = pending.filterIsInstance<PendingAction.Check>().mapTo(HashSet()) { it.id } + settlingChecks

    /** DESIGN C2 `all-checked` (AC4). */
    val allChecked: Boolean get() = CheckOff.allChecked(unchecked.size, pendingCheckIds.size, checked.size)

    private val visible: List<ListItemRecord>
        get() {
            val gone = hidden
            return detail.items.filter { it.id !in gone }
        }

    /** Oldest first, so a new item lands at the bottom; the id breaks ties so the order never flickers. */
    val unchecked: List<ListItemRecord>
        get() = visible.filter { !it.checked }.sortedWith(compareBy<ListItemRecord>({ it.createdAt ?: "" }, { it.id }))

    /** Newest check-off first. */
    val checked: List<ListItemRecord>
        get() = visible.filter { it.checked }.sortedWith(CheckOff.newestFirst)

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

    /** Undoes the pending action with this id (a delete or check-off: its item's id; a clear: its own). */
    data class Undo(val actionId: String) : ListDetailEvent

    /** The checkbox on an unchecked row: the check-off is written when its undo window ends. */
    data class Check(val itemId: String) : ListDetailEvent

    /** The checkbox on a checked row: written at once, no toast. */
    data class Uncheck(val itemId: String) : ListDetailEvent

    /** Soft-deletes the rows that are checked now, after an undo window. */
    data object ClearChecked : ListDetailEvent

    /** The store chip: opens the C2 store picker (SHOP-05). */
    data object OpenPicker : ListDetailEvent

    /** Cancel or a dismissed picker: nothing is written. */
    data object ClosePicker : ListDetailEvent

    /** The picker's Save: the list's store, or null for no store. Writes only when it differs from what the list has. */
    data class SetStore(val storeId: String?) : ListDetailEvent

    /** Leaving the screen or the app writes what is still waiting rather than dropping it. */
    data object Flush : ListDetailEvent
}

/**
 * DESIGN C2 and C3. Reads only the local database, so it works offline; an add or an edit goes through the outbox in one
 * local transaction and then [kick]s sync. A delete, a check-off and a clear wait [UNDO_MILLIS] (through [delayer], so tests
 * do not wait in real time) before they are written; Undo inside the window writes nothing. Twins: iOS `ListDetailModel`, web `ListDetail.tsx`.
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

    /** Actions still inside their window, by action id. An action leaves this map when it is undone, flushed or written. */
    private val waiting = HashMap<String, Job>()

    /** Unchecks whose write is running, so a double tap queues one mutation. */
    private val unchecking = HashSet<String>()

    private var clearSeq = 0

    /** A sheet save is running: a second tap on Save must not queue a second row. */
    private var savingSheet = false

    /** A store choice is being written: a second tap on the picker's Save must not queue a second patch. */
    private var savingStore = false

    /** Actions whose write has started, so the timer and a flush can never both queue it. */
    private val committing = java.util.concurrent.ConcurrentHashMap.newKeySet<PendingAction>()

    init {
        scope.launch {
            database.observeListDetail(householdId, listId).collect { detail ->
                mutableState.update { s ->
                    val present = detail.items.mapTo(HashSet()) { it.id }
                    // An edited row that is gone (deleted on another device) closes its sheet.
                    val sheet = s.sheet.takeUnless { it is SheetState.Edit && it.itemId !in present }
                    // A settling check is done once the list shows it checked (or the row is gone).
                    val stillUnchecked = detail.items.filter { !it.checked }.mapTo(HashSet()) { it.id }
                    s.copy(
                        detail = detail, loaded = true, sheet = sheet,
                        settling = s.settling.filterTo(HashSet()) { it in present },
                        settlingChecks = s.settlingChecks.filterTo(HashSet()) { it in stillUnchecked },
                    )
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
            is ListDetailEvent.Undo -> undo(event.actionId)
            is ListDetailEvent.Check -> check(event.itemId)
            is ListDetailEvent.Uncheck -> uncheck(event.itemId)
            ListDetailEvent.ClearChecked -> clearChecked()
            ListDetailEvent.OpenPicker -> openPicker()
            ListDetailEvent.ClosePicker -> mutableState.update { it.copy(picker = false, pickerFailed = false) }
            is ListDetailEvent.SetStore -> setStore(event.storeId)
            ListDetailEvent.Flush -> flush()
        }
    }

    // --- Store picker (SHOP-05) ---

    private fun openPicker() {
        // Same guard as the item sheet: a picker for a list that is not shown (or is gone) could only queue a patch for nothing.
        val current = mutableState.value
        if (!current.loaded || current.detail.list == null) return
        mutableState.update { it.copy(picker = true, pickerFailed = false) }
    }

    /**
     * Saves the picker's choice: one `patch` of `store_id` only (a rename made on another device is not overwritten). The
     * picker closes once the write is stored; the same store as the list already has writes nothing. A write that cannot be
     * stored keeps the picker open with [ListDetailUiState.pickerFailed].
     */
    private fun setStore(storeId: String?) {
        val list = mutableState.value.detail.list
        // A second Save tap while the first write runs must not queue a second patch (same rule as the item sheet).
        if (savingStore) return
        if (list == null || list.storeId == storeId) {
            mutableState.update { it.copy(picker = false, pickerFailed = false) }
            return
        }
        savingStore = true
        scope.launch {
            val written = try {
                setListStore(database, householdId, listId, storeId, now())
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            savingStore = false
            mutableState.update { if (written) it.copy(picker = false, pickerFailed = false) else it.copy(pickerFailed = true) }
            if (written) kick()
        }
    }

    /**
     * The picker's inline "New store": validates against the live stores, writes the store and returns its id, or null
     * when the name is not valid or could not be stored. The picker selects the id it gets back.
     */
    suspend fun addStore(name: String): String? {
        if (Stores.validate(name, mutableState.value.detail.stores) != null) return null
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
        if (!saved) return null
        kick()
        return id
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

    // --- Barcode scan (SHOP-10) ---

    /**
     * Adds a scanned or looked-up item and returns its id (null when it could not be stored or the list is not shown).
     * Unlike [add] it leaves what is typed in the quick-add field alone. [barcode] only for a product a lookup found.
     * [beforeKick] runs after the row is stored and before sync is kicked, so a lookup queued there is seen by the drain
     * that follows that sync run.
     */
    suspend fun addScanned(name: String, barcode: String?, beforeKick: (String) -> Unit = {}): String? {
        val current = mutableState.value
        if (!current.loaded || current.detail.list == null) return null
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val id = newId()
        val at = now()
        // Closing the scanner mid-add must not leave a stored row without its queued lookup, so the whole write finishes.
        return withContext(NonCancellable) {
            try {
                addItem(database, householdId, QuickAdd.row(id, listId, trimmed, userId, at, barcode = barcode), at)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext null
            }
            beforeKick(id)
            kick()
            id
        }
    }

    /** The scanner for this list (DESIGN C7): its adds go through [addScanned]; offline is read from this screen's connectivity. */
    fun makeScanner(
        device: ScannerDevice,
        lookup: suspend (String) -> com.abodenestly.core.api.ProductLookupResult,
        store: BarcodeStore,
        scannerScope: CoroutineScope,
    ): BarcodeScannerModel = BarcodeScannerModel(
        householdId = householdId.toString(),
        device = device,
        lookup = lookup,
        isOnline = { mutableState.value.online },
        store = store,
        add = { name, barcode, beforeKick -> addScanned(name, barcode, beforeKick) },
        scope = scannerScope,
    )

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
        if (pending.any { itemId in it.hiddenIds } || itemId in settling) null else detail.items.firstOrNull { it.id == itemId }

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
                        // One transaction: `patch` writes every key locally and queues all but the server-owned ones, so
                        // the local name_key (keeps suggestions matching until the next pull) goes with the rename.
                        val fields = changes.nameKey?.let { key -> JsonObject(changes.fields + ("name_key" to JsonPrimitive(key))) } ?: changes.fields
                        database.patch(householdId, "list_item", before.id, fields, at)
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

    // --- Delete, check off and clear, each with undo (SHOP-03, SHOP-04) ---

    private fun delete(itemId: String) {
        val item = mutableState.value.liveItem(itemId) ?: return
        // Same id as a check-off still waiting on this row, so it replaces (cancels) that check.
        begin(PendingAction.Delete(item.id, item.name))
        // Deleting from the sheet closes it.
        mutableState.update { s -> s.copy(sheet = if ((s.sheet as? SheetState.Edit)?.itemId == itemId) null else s.sheet) }
    }

    /** Ticks the row in place; the write happens when the window ends (AC1, AC3). */
    private fun check(itemId: String) {
        val s = mutableState.value
        val item = s.liveItem(itemId) ?: return
        if (item.checked || itemId in s.pendingCheckIds) return
        begin(PendingAction.Check(item.id, item.name))
    }

    /** Unchecking is its own undo: written at once, no toast. */
    private fun uncheck(itemId: String) {
        val s = mutableState.value
        val item = s.liveItem(itemId) ?: return
        if (!item.checked || !unchecking.add(itemId)) return
        scope.launch {
            val written = try {
                database.patch(householdId, "list_item", itemId, CheckOff.uncheckFields(), now())
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            unchecking.remove(itemId)
            if (written) kick() else mutableState.update { it.copy(error = SAVE_ERROR) }
        }
    }

    /** Hides the rows that are checked now and soft-deletes them after the window (AC5). A row checked during it is not swept in. */
    private fun clearChecked() {
        val ids = mutableState.value.checked.map { it.id }
        if (ids.isEmpty()) return
        begin(PendingAction.Clear("clear-${++clearSeq}", ids))
    }

    private fun begin(action: PendingAction) {
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

    private fun undo(actionId: String) {
        // Past the window (or flushed) the write has started: Undo has nothing left to cancel.
        val job = waiting.remove(actionId) ?: return
        job.cancel()
        mutableState.update { s -> s.copy(pending = s.pending.filter { it.id != actionId }) }
    }

    private fun flush() {
        val toWrite = mutableState.value.pending.filter { it.id in waiting }
        if (toWrite.isEmpty()) return
        toWrite.forEach { waiting.remove(it.id)?.cancel() }
        scope.launch { toWrite.forEach { commit(it) } }
    }

    private suspend fun commit(action: PendingAction) {
        if (!committing.add(action)) return
        // A write that has started finishes even when the view model is cleared right after a flush (back out of the app).
        withContext(NonCancellable) { commitOnce(action) }
        committing.remove(action)
    }

    private suspend fun commitOnce(action: PendingAction) {
        val written = try {
            when (action) {
                is PendingAction.Delete -> {
                    database.remove(householdId, "list_item", action.id, now())
                    true
                }
                is PendingAction.Check -> writeCheck(action)
                is PendingAction.Clear -> writeClear(action)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        mutableState.update { s ->
            val shown = s.detail.items.associateBy { it.id }
            s.copy(
                // Removed by equality: a delete that replaced this action under the same id keeps its toast.
                pending = s.pending.filter { it != action },
                error = if (written != null) s.error else SAVE_ERROR,
                // The list may already carry the write (Room re-emits while sync is kicked): then there is nothing to
                // wait for, and an entry added now would only linger until the next emission.
                settling = if (written == true && action.hiddenIds.isNotEmpty()) s.settling + action.hiddenIds.filter { it in shown } else s.settling,
                settlingChecks = if (written == true && action is PendingAction.Check && shown[action.id]?.checked == false) s.settlingChecks + action.id else s.settlingChecks,
            )
        }
        if (written == true) kick()
    }

    /** One `patch` (local row and outbox). Re-reads the stored row: a row deleted meanwhile or checked on another device is left alone. */
    private suspend fun writeCheck(action: PendingAction.Check): Boolean {
        val row = database.observeListDetail(householdId, listId).first().items.firstOrNull { it.id == action.id }
        if (row == null || row.checked) return false
        val at = now()
        database.patch(householdId, "list_item", action.id, CheckOff.checkFields(userId, at), at)
        return true
    }

    /** One `remove` per row (the sync contract is one mutation per row). */
    private suspend fun writeClear(action: PendingAction.Clear): Boolean {
        for (id in action.itemIds) database.remove(householdId, "list_item", id, now())
        return true
    }
}
