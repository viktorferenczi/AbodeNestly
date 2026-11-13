package com.abodenestly.core.welcome

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Household
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** DESIGN B2 states. Typed text lives in [WelcomeViewModel.name] / [WelcomeViewModel.code], outside the state, so it survives every error (AC3). */
sealed interface WelcomeState {
    data object Idle : WelcomeState
    data object Creating : WelcomeState
    data object Joining : WelcomeState
    data class CreateError(val failure: ApiFailure) : WelcomeState
    data object ErrorInvalidCode : WelcomeState
    data class JoinError(val failure: ApiFailure) : WelcomeState
}

/** What the person did on the Welcome screen; the app turns it into a [SessionViewModel] call. */
sealed interface WelcomeOutcome {
    data class Created(val household: Household) : WelcomeOutcome
    data class Joined(val householdId: UUID) : WelcomeOutcome
}

/** Hands a Welcome outcome to the session: a new household opens on Home, a joined one reloads then opens on Home. */
fun SessionViewModel.land(outcome: WelcomeOutcome) = when (outcome) {
    is WelcomeOutcome.Created -> with(outcome.household) {
        enter(LandingHousehold(id, moduleShopping, modulePantry, moduleMaintenance))
    }
    is WelcomeOutcome.Joined -> enterJoined(outcome.householdId)
}

/** Port of iOS `WelcomeModel`. */
class WelcomeViewModel(
    private val api: ApiService,
    private val onLanded: (WelcomeOutcome) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableName = MutableStateFlow("")
    private val mutableCode = MutableStateFlow("")
    private val mutableState = MutableStateFlow<WelcomeState>(WelcomeState.Idle)

    val name: StateFlow<String> = mutableName.asStateFlow()
    val code: StateFlow<String> = mutableCode.asStateFlow()
    val state: StateFlow<WelcomeState> = mutableState.asStateFlow()

    private val isBusy get() = mutableState.value.let { it == WelcomeState.Creating || it == WelcomeState.Joining }

    fun onNameChange(value: String) {
        mutableName.value = value
    }

    fun onCodeChange(value: String) {
        mutableCode.value = value
    }

    fun create() {
        if (isBusy) return
        val trimmed = mutableName.value.trim()
        if (trimmed.isEmpty()) {
            mutableState.value = WelcomeState.CreateError(ApiFailure.Validation)
            return
        }
        mutableState.value = WelcomeState.Creating
        scope.launch {
            when (val result = api.createHousehold(trimmed)) {
                is ApiResult.Success -> {
                    mutableState.value = WelcomeState.Idle
                    onLanded(WelcomeOutcome.Created(result.value))
                }
                is ApiResult.Failure -> mutableState.value = WelcomeState.CreateError(result.failure)
            }
        }
    }

    fun join() {
        if (isBusy) return
        // Codes are lowercase hex, so a pasted or auto-capitalised "ABC123" still works (matches iOS and web).
        val trimmed = mutableCode.value.trim().lowercase()
        if (trimmed.isEmpty()) {
            mutableState.value = WelcomeState.JoinError(ApiFailure.Validation)
            return
        }
        mutableState.value = WelcomeState.Joining
        scope.launch {
            when (val result = api.acceptInvite(trimmed)) {
                is ApiResult.Success -> {
                    mutableState.value = WelcomeState.Idle
                    onLanded(WelcomeOutcome.Joined(result.value))
                }
                is ApiResult.Failure -> mutableState.value = when (result.failure) {
                    ApiFailure.InvalidCode -> WelcomeState.ErrorInvalidCode
                    else -> WelcomeState.JoinError(result.failure)
                }
            }
        }
    }

    companion object {
        /** DESIGN B4: every household opens on its Home tab. */
        fun landing(household: Household): Destination = Destination.Household(household.id, AppTab.Home)
    }
}
