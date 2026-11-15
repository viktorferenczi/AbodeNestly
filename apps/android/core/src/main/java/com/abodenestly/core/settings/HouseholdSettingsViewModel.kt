package com.abodenestly.core.settings

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Member
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.HouseholdWithRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class SettingsLoadState {
    Loading,
    Loaded,

    /** The household could not be read and nothing is cached, or the viewer is no longer a member. */
    Unavailable,
}

sealed interface SettingsStatus {
    data object Idle : SettingsStatus
    data object Saving : SettingsStatus
    data class Error(val failure: ApiFailure) : SettingsStatus
    data object Leaving : SettingsStatus
}

data class HouseholdSettingsUiState(
    val household: Household? = null,
    val role: Member.Role? = null,
    val loadState: SettingsLoadState = SettingsLoadState.Loading,
    val status: SettingsStatus = SettingsStatus.Idle,
    /** The household as it will look once the pending save lands; shown while saving. */
    val optimistic: Household? = null,
    /** What the person typed in the name field; `null` shows the saved name. */
    val nameDraft: String? = null,
    val confirmingLeave: Boolean = false,
) {
    val shown: Household? get() = optimistic ?: household
    val name: String get() = nameDraft ?: shown?.name.orEmpty()
    val isSaving: Boolean get() = status == SettingsStatus.Saving
    val isLeaving: Boolean get() = status == SettingsStatus.Leaving
    val isBusy: Boolean get() = isSaving || isLeaving

    /** The inline error under the form; hidden while the leave sheet is open (it shows its own). */
    val saveError: String? get() = (status as? SettingsStatus.Error)?.takeIf { !confirmingLeave }?.let { saveErrorMessage(it.failure) }
    val leaveError: String? get() = (status as? SettingsStatus.Error)?.takeIf { confirmingLeave }?.let { leaveErrorMessage(it.failure) }
}

sealed interface HouseholdSettingsEvent {
    data class Toggle(val key: ModuleKey, val on: Boolean) : HouseholdSettingsEvent
    data class NameChanged(val text: String) : HouseholdSettingsEvent
    /** IME Done: commit now. */
    data object CommitName : HouseholdSettingsEvent

    /** The name field lost focus: commit after the gesture that took the focus has been handled. */
    data object CommitNameOnBlur : HouseholdSettingsEvent
    data class SetCurrency(val currency: String) : HouseholdSettingsEvent
    data object BeginLeave : HouseholdSettingsEvent
    data object CancelLeave : HouseholdSettingsEvent
    data object ConfirmLeave : HouseholdSettingsEvent
}

/**
 * Household settings logic (DESIGN B5); port of iOS `HouseholdSettingsModel` and web `HouseholdSettings.ts(x)`.
 * [isOnline], [onHouseholdChanged] and [onLeft] are rebound by the screen on every composition, because this
 * instance outlives the composition (and its callbacks) across a configuration change.
 */
class HouseholdSettingsViewModel(
    private val householdId: UUID,
    private val userId: UUID,
    private val api: ApiService,
    private val cache: HouseholdCache,
    var isOnline: () -> Boolean,
    var onHouseholdChanged: (Household) -> Unit,
    var onLeft: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Runs the blur commit; never immediate, so the tap that took the focus is handled first. */
    private val blurDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(HouseholdSettingsUiState())
    val state: StateFlow<HouseholdSettingsUiState> = mutableState.asStateFlow()

    /** Bumped when a save or leave starts; a fetch begun earlier must not overwrite what that save stored. */
    private var generation = 0

    fun onEvent(event: HouseholdSettingsEvent) {
        when (event) {
            is HouseholdSettingsEvent.Toggle -> scope.launch { toggle(event.key, event.on) }
            is HouseholdSettingsEvent.NameChanged -> mutableState.update {
                if (canEdit(it)) it.copy(nameDraft = event.text) else it
            }
            HouseholdSettingsEvent.CommitName -> scope.launch { commitName() }
            HouseholdSettingsEvent.CommitNameOnBlur -> scope.launch(blurDispatcher) { commitName() }
            is HouseholdSettingsEvent.SetCurrency -> scope.launch { setCurrency(event.currency) }
            HouseholdSettingsEvent.BeginLeave -> beginLeave()
            HouseholdSettingsEvent.CancelLeave -> cancelLeave()
            HouseholdSettingsEvent.ConfirmLeave -> scope.launch { leave() }
        }
    }

    /** Cache first so the screen is readable offline, then the live row when online. */
    suspend fun load() {
        if (mutableState.value.household == null) cache.read(userId, householdId)?.let(::show)
        if (!isOnline()) {
            if (mutableState.value.household == null) mutableState.update { it.copy(loadState = SettingsLoadState.Unavailable) }
            return
        }
        val started = generation
        val result = api.fetchHousehold(householdId, userId)
        if (started != generation) return
        when (result) {
            is ApiResult.Success -> {
                val value = result.value
                if (value != null) {
                    show(value)
                    cache.write(userId, value)
                } else {
                    // No longer an active member (left elsewhere, or the household was deleted).
                    cache.clear(userId, householdId)
                    mutableState.update { it.copy(household = null, role = null, loadState = SettingsLoadState.Unavailable) }
                }
            }
            is ApiResult.Failure ->
                if (mutableState.value.household == null) mutableState.update { it.copy(loadState = SettingsLoadState.Unavailable) }
        }
    }

    private fun show(value: HouseholdWithRole) {
        mutableState.update { it.copy(household = value.household, role = value.role, loadState = SettingsLoadState.Loaded) }
    }

    private fun canEdit(s: HouseholdSettingsUiState) = isOnline() && s.role == Member.Role.owner

    suspend fun toggle(key: ModuleKey, on: Boolean) {
        val s = mutableState.value
        val current = s.household ?: return
        if (!canEdit(s) || s.isBusy) return
        // A name typed but not committed goes first, so the tap that blurred the field is not lost.
        if (!saveDirtyName()) return
        val fresh = mutableState.value.household ?: return
        save(display = withModule(fresh, key, on)) {
            api.updateModules(
                householdId,
                shopping = if (key == ModuleKey.Shopping) on else null,
                pantry = if (key == ModuleKey.Pantry) on else null,
                maintenance = if (key == ModuleKey.Maintenance) on else null,
            )
        }
    }

    /** Saves the draft when it is trimmed, non-empty and changed. */
    suspend fun commitName() {
        val s = mutableState.value
        val current = s.household ?: return
        if (!canEdit(s) || s.isBusy) return
        val trimmed = s.name.trim()
        if (trimmed.isEmpty() || trimmed == current.name) {
            // Nothing to save: show the saved name again rather than a blank or unchanged draft.
            mutableState.update { it.copy(nameDraft = null) }
            return
        }
        save(display = null) { api.updateHousehold(householdId, name = trimmed) }
    }

    suspend fun setCurrency(currency: String) {
        val s = mutableState.value
        val current = s.household ?: return
        if (!canEdit(s) || current.currency == currency || s.isBusy) return
        if (!saveDirtyName()) return
        val fresh = mutableState.value.household ?: return
        save(display = fresh.copy(currency = currency)) { api.updateHousehold(householdId, currency = currency) }
    }

    /** Saves a pending name edit. `false` when that save failed, so the caller must not go on. */
    private suspend fun saveDirtyName(): Boolean {
        val s = mutableState.value
        val trimmed = s.nameDraft?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == s.household?.name) return true
        save(display = null) { api.updateHousehold(householdId, name = trimmed) }
        return mutableState.value.status !is SettingsStatus.Error
    }

    private suspend fun save(display: Household?, run: suspend () -> ApiResult<Household>) {
        generation += 1
        mutableState.update { it.copy(status = SettingsStatus.Saving, optimistic = display) }
        val result = run()
        mutableState.update { it.copy(optimistic = null) }
        // A fetch that started during the request still holds the pre-patch row; it must not overwrite the result.
        generation += 1
        when (result) {
            is ApiResult.Success -> {
                val saved = result.value
                val role = mutableState.value.role
                mutableState.update { it.copy(nameDraft = null, household = saved, status = SettingsStatus.Idle) }
                // Without a known role the snapshot would be unreadable offline, so write only when it is known.
                if (role != null) cache.write(userId, HouseholdWithRole(saved, role))
                onHouseholdChanged(saved)
            }
            is ApiResult.Failure -> {
                mutableState.update { it.copy(status = SettingsStatus.Error(result.failure)) }
                // The role may have changed under us; reload so the screen shows the truth.
                if (result.failure == ApiFailure.Forbidden) load()
            }
        }
    }

    private fun beginLeave() {
        if (mutableState.value.isBusy) return
        mutableState.update { it.copy(status = SettingsStatus.Idle, confirmingLeave = true) }
    }

    private fun cancelLeave() {
        if (mutableState.value.isLeaving) return
        mutableState.update { it.copy(status = SettingsStatus.Idle, confirmingLeave = false) }
    }

    suspend fun leave() {
        if (!isOnline() || mutableState.value.isLeaving) return
        generation += 1
        mutableState.update { it.copy(status = SettingsStatus.Leaving) }
        val result = api.removeMember(householdId, userId)
        // Already gone counts as done.
        if (result is ApiResult.Success || (result is ApiResult.Failure && result.failure == ApiFailure.NotFound)) {
            cache.clear(userId, householdId)
            mutableState.update { it.copy(status = SettingsStatus.Idle, confirmingLeave = false) }
            onLeft()
        } else if (result is ApiResult.Failure) {
            mutableState.update { it.copy(status = SettingsStatus.Error(result.failure)) }
        }
    }
}
