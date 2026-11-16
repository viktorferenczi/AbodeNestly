package com.abodenestly.core.switcher

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Member
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.sync.DatabaseRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** One line of the B3 sheet. */
data class SwitcherRow(
    val household: Household,
    val role: Member.Role,
    val isCurrent: Boolean,
    /** Queued writes of that household's local database. */
    val pending: Int,
) {
    val id: UUID get() = household.id

    /** `switcher.row.<id>`, the id lowercase like every other id the API and tests handle. */
    val tag: String get() = "switcher.row.${household.id.toString().lowercase()}"

    val detail: String
        get() {
            val roleText = if (role == Member.Role.owner) "Owner" else "Member"
            return if (pending > 0) "$roleText · $pending to sync" else roleText
        }
}

data class SwitcherUiState(
    val rows: List<SwitcherRow> = emptyList(),
    val switchingName: String? = null,
    val errorText: String? = null,
) {
    val state: SwitcherState get() = Switcher.state(rows.size, switchingName)
}

/**
 * DESIGN B3. Rows start from the cached list (works offline, AC5), refresh from the API when online, and carry
 * the pending-sync count of each household's local database. Port of iOS `SwitcherModel`.
 *
 * @param current the open household, added when the cached list lacks it.
 * @param perform switches households (`SessionViewModel.switchTo`); the `Boolean` asks to land on Settings.
 */
class SwitcherViewModel(
    private val userId: UUID,
    private val currentId: UUID?,
    private val current: HouseholdWithRole?,
    private val api: ApiService,
    private val cache: HouseholdsCache,
    private val database: DatabaseRegistry,
    private val isOnline: () -> Boolean,
    private val perform: suspend (UUID, Boolean) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(SwitcherUiState())
    val state: StateFlow<SwitcherUiState> = mutableState.asStateFlow()

    private var entries: List<HouseholdWithRole>? = null
    private var pending: Map<UUID, Int> = emptyMap()

    /** Cached rows first, then counts, then the online refresh and counts for any new rows. */
    suspend fun load() {
        seed()
        loadPending()
        if (!isOnline()) return
        val result = api.fetchMyHouseholds(userId)
        if (result is ApiResult.Success) {
            cache.write(userId, result.value)
            // A refresh shows what the server says.
            entries = sorted(result.value)
            rebuild()
            loadPending()
        }
    }

    /** Row tap. Returns `true` when the sheet should close (switched, or it was the current household). */
    suspend fun select(id: UUID): Boolean = switchTo(id, openSettings = false)

    /** Row long-press: switch if needed, then land on Home → Settings (B5). */
    suspend fun openSettings(id: UUID): Boolean = switchTo(id, openSettings = true)

    private suspend fun switchTo(id: UUID, openSettings: Boolean): Boolean {
        if (mutableState.value.switchingName != null) return false
        if (id == currentId && !openSettings) return true
        seed()
        val target = entries?.firstOrNull { it.household.id == id } ?: return false
        mutableState.update { it.copy(errorText = null, switchingName = target.household.name) }
        return try {
            perform(id, openSettings)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(errorText = SWITCH_ERROR_TEXT) }
            false
        } finally {
            mutableState.update { it.copy(switchingName = null) }
        }
    }

    private suspend fun seed() {
        if (entries != null) return
        entries = sorted(merge(cache.read(userId), current))
        rebuild()
    }

    private suspend fun loadPending() {
        pending = entries.orEmpty().associate { it.household.id to runCatching { database.pendingCount(it.household.id) }.getOrDefault(0) }
        rebuild()
    }

    private fun rebuild() {
        val rows = entries.orEmpty().map { SwitcherRow(it.household, it.role, it.household.id == currentId, pending[it.household.id] ?: 0) }
        mutableState.update { it.copy(rows = rows) }
    }

    private fun merge(list: List<HouseholdWithRole>, current: HouseholdWithRole?): List<HouseholdWithRole> =
        if (current != null && list.none { it.household.id == current.household.id }) listOf(current) + list else list

    private fun sorted(list: List<HouseholdWithRole>) = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.household.name })

    companion object {
        const val SWITCH_ERROR_TEXT = "Couldn't switch households. Try again."
    }
}
