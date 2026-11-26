package com.abodenestly.shopping

import androidx.lifecycle.ViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListDetail
import com.abodenestly.sync.ListItemRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * What the C2 list detail shows (SHOP-02 slice). [loaded] is false until the first read, so "This list is gone" does not
 * flash. [query] is the quick-add field's text.
 */
data class ListDetailUiState(
    val detail: ListDetail = ListDetail(),
    val loaded: Boolean = false,
    val query: String = "",
    val online: Boolean = true,
    val error: String? = null,
    val listId: String = "",
) {
    val isGone: Boolean get() = loaded && detail.list == null

    val suggestions: List<Suggestion> get() = QuickAdd.suggest(detail.history, query, listId)

    /** Oldest first, so a new item lands at the bottom; the id breaks ties so the order never flickers. */
    val unchecked: List<ListItemRecord>
        get() = detail.items.filter { !it.checked }.sortedWith(compareBy<ListItemRecord>({ it.createdAt ?: "" }, { it.id }))

    /** Newest first. */
    val checked: List<ListItemRecord>
        get() = detail.items.filter { it.checked }.sortedWith(compareByDescending<ListItemRecord> { it.createdAt ?: "" }.thenBy { it.id })
}

sealed interface ListDetailEvent {
    data class QueryChanged(val text: String) : ListDetailEvent

    /** Return or the IME action: adds exactly the typed text, never an auto-picked suggestion. */
    data object Submit : ListDetailEvent

    /** A tapped suggestion: adds its label. */
    data class Pick(val suggestion: Suggestion) : ListDetailEvent

    data object ErrorShown : ListDetailEvent
}

/**
 * DESIGN C2, SHOP-02 slice. Reads only the local database, so it works offline; an add goes through the outbox in one
 * local transaction and then [kick]s sync. Twins: iOS `ListDetailModel`, web `ListDetail.tsx`.
 */
class ListDetailViewModel(
    private val householdId: UUID,
    private val listId: String,
    private val userId: UUID?,
    private val database: DatabaseRegistry,
    private val kick: suspend () -> Unit,
    isOnline: StateFlow<Boolean>,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(ListDetailUiState(online = isOnline.value, listId = listId))
    val state: StateFlow<ListDetailUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            database.observeListDetail(householdId, listId).collect { detail ->
                mutableState.update { it.copy(detail = detail, loaded = true) }
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
        }
    }

    /**
     * Adds [name] as typed. The row exists locally and in the outbox before the field clears (AC4); a write that cannot
     * be stored keeps the text and shows [SAVE_ERROR]. Nothing waits on a later emission: the list updates when Room re-emits.
     */
    private fun add(name: String) {
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
}
