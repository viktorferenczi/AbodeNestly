package com.abodenestly.core.members

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Member
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.MemberRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** DESIGN B6 list states. */
enum class MembersLoadState {
    Loading,
    Loaded,

    /** Offline and nothing cached: "Connect to see the members." */
    OfflineNoCache,
    Error,
}

/** A removal or leave waiting out the undo window; its row is hidden meanwhile. */
data class PendingRemoval(val row: MemberRow, val leaving: Boolean)

data class MembersUiState(
    val rows: List<MemberRow> = emptyList(),
    val loadState: MembersLoadState = MembersLoadState.Loading,
    val pending: PendingRemoval? = null,
    /** "Removed <name>" or "Leaving household" while the undo window is open. */
    val toast: String? = null,
    /** Last-owner block or a failure message; the screen shows it in a dialog and clears it. */
    val alert: String? = null,
    val meId: UUID? = null,
) {
    /** Rows to render: sorted, with the pending removal hidden. */
    val visibleRows: List<MemberRow> get() = MembersViewModel.sortMembers(rows.filter { it.id != pending?.row?.id })
    val viewerRole: Member.Role? get() = meId?.let { MembersViewModel.viewerRole(rows, it) }
    val isBusy: Boolean get() = pending != null
}

/**
 * Members screen logic; port of iOS `MembersModel`. The undo window is a `delay` in [scope], so tests drive it
 * with virtual time, and the view model keeps its own scope so a pending DELETE outlives the screen.
 * [isOnline] and [onLeft] are rebound by the screen on every composition, because this instance outlives the
 * composition (and its callbacks) across a configuration change.
 */
class MembersViewModel(
    private val householdId: UUID,
    private val meId: UUID,
    private val api: ApiService,
    private val cache: MembersCache,
    var isOnline: () -> Boolean,
    var onLeft: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val undoMillis: Long = UNDO_MILLIS,
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(MembersUiState(meId = meId))
    val state: StateFlow<MembersUiState> = mutableState.asStateFlow()

    /** Bumped when a removal lands; a fetch that started earlier must not bring the member back. */
    private var generation = 0

    /** The undo-window timer; non-null exactly while the DELETE has not been sent yet. */
    private var waiting: Job? = null

    /** Live list when online; the cached list when offline or the read fails. */
    suspend fun load() {
        val started = generation
        if (!isOnline()) return fromCache()
        val result = api.fetchMembers(householdId)
        if (started != generation) return
        when (result) {
            is ApiResult.Success -> {
                mutableState.update { it.copy(rows = result.value, loadState = MembersLoadState.Loaded) }
                cache.write(result.value, householdId)
            }
            is ApiResult.Failure -> fromCache()
        }
    }

    private suspend fun fromCache() {
        val cached = cache.read(householdId)
        if (cached != null) {
            mutableState.update { it.copy(rows = cached, loadState = MembersLoadState.Loaded) }
        } else if (mutableState.value.loadState != MembersLoadState.Loaded) {
            // A list already on screen stays; only a first load can end without one.
            mutableState.update {
                it.copy(loadState = if (isOnline()) MembersLoadState.Error else MembersLoadState.OfflineNoCache)
            }
        }
    }

    // Actions are no-ops offline or while a removal is pending.

    fun remove(row: MemberRow) {
        val s = mutableState.value
        if (!isOnline() || s.pending != null || !canRemove(s.viewerRole, row, meId)) return
        start(row, leaving = false)
    }

    /** AC4: the only owner is blocked before any call is made. */
    fun leave() {
        val s = mutableState.value
        if (!isOnline() || s.pending != null || s.loadState != MembersLoadState.Loaded) return
        val me = s.rows.firstOrNull { it.id == meId } ?: return
        if (isLastOwner(s.rows, meId)) {
            mutableState.update { it.copy(alert = LAST_OWNER_MESSAGE) }
            return
        }
        start(me, leaving = true)
    }

    /** AC3: Undo inside the window means DELETE is never sent. */
    fun undo() {
        val timer = waiting ?: return
        timer.cancel()
        waiting = null
        mutableState.update { it.copy(pending = null, toast = null) }
    }

    /** The toast said "Removed", so leaving the screen does not cancel it: Undo is the only cancel. */
    fun flushPending() {
        val timer = waiting ?: return
        timer.cancel()
        scope.launch { commit() }
    }

    fun dismissAlert() {
        mutableState.update { it.copy(alert = null) }
    }

    suspend fun makeOwner(row: MemberRow) {
        val s = mutableState.value
        if (!isOnline() || s.pending != null || s.viewerRole != Member.Role.owner || row.member.role == Member.Role.owner) return
        when (api.makeOwner(householdId, row.id)) {
            is ApiResult.Success -> {
                mutableState.update { it.copy(alert = null) }
                load()
            }
            is ApiResult.Failure ->
                mutableState.update { it.copy(alert = "Couldn't make ${displayName(row)} an owner. Try again.") }
        }
    }

    private fun start(row: MemberRow, leaving: Boolean) {
        mutableState.update {
            it.copy(
                alert = null,
                pending = PendingRemoval(row, leaving),
                toast = if (leaving) "Leaving household" else "Removed ${displayName(row)}",
            )
        }
        waiting = scope.launch {
            delay(undoMillis)
            commit()
        }
    }

    /** Sends the DELETE. Runs once per removal: the timer and [flushPending] can both reach it. */
    private suspend fun commit() {
        val removal = mutableState.value.pending
        if (waiting == null || removal == null) return
        waiting = null
        mutableState.update { it.copy(toast = null) }
        val name = if (removal.leaving) "yourself" else displayName(removal.row)
        when (val result = api.removeMember(householdId, removal.row.id)) {
            is ApiResult.Success -> landed(removal)
            // Already gone counts as done: the row should disappear either way.
            is ApiResult.Failure -> if (result.failure == ApiFailure.NotFound) landed(removal) else {
                val message = when (result.failure) {
                    ApiFailure.LastOwner -> LAST_OWNER_MESSAGE
                    ApiFailure.Forbidden -> "You don't have permission to remove $name."
                    else -> if (removal.leaving) "Couldn't leave the household. Try again." else "Couldn't remove $name. Try again."
                }
                mutableState.update { it.copy(pending = null, alert = message) }
                // The list the person acted on may be stale (roles changed elsewhere); show what the server has now.
                load()
            }
        }
    }

    private suspend fun landed(removal: PendingRemoval) {
        generation += 1
        mutableState.update { it.copy(pending = null) }
        if (removal.leaving) {
            onLeft()
        } else {
            val rows = mutableState.value.rows.filterNot { it.id == removal.row.id }
            mutableState.update { it.copy(rows = rows) }
            cache.write(rows, householdId)
        }
    }

    companion object {
        const val UNDO_MILLIS = 5_000L
        const val LAST_OWNER_MESSAGE = "You're the last owner. Make someone else an owner before leaving."

        /** The profile name, or "Member" when the profile is hidden or empty. */
        fun displayName(row: MemberRow): String = row.profile?.displayName?.trim().orEmpty().ifEmpty { "Member" }

        /** Owners first, then by name (case-insensitive); ties keep input order. */
        fun sortMembers(rows: List<MemberRow>): List<MemberRow> =
            rows.sortedWith(
                compareBy<MemberRow> { if (it.member.role == Member.Role.owner) 0 else 1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { displayName(it) },
            )

        /** "Anna Kovacs" → "AK"; a single name gives one letter; nothing gives "?". */
        fun initials(displayName: String): String {
            val words = displayName.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val first = words.firstOrNull()?.first() ?: return "?"
            val last = if (words.size > 1) words.last().first().toString() else ""
            return (first.toString() + last).uppercase()
        }

        fun viewerRole(rows: List<MemberRow>, meId: UUID): Member.Role? = rows.firstOrNull { it.id == meId }?.member?.role

        /** True when the viewer is an owner and no one else is. */
        fun isLastOwner(rows: List<MemberRow>, meId: UUID): Boolean =
            viewerRole(rows, meId) == Member.Role.owner &&
                rows.none { it.member.role == Member.Role.owner && it.id != meId }

        /** Only owners remove others; leaving yourself is the separate Leave action. */
        fun canRemove(role: Member.Role?, target: MemberRow, meId: UUID): Boolean =
            role == Member.Role.owner && target.id != meId
    }
}
