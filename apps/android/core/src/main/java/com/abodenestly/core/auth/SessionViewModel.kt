package com.abodenestly.core.auth

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class SessionStatus { Loading, SignedIn, SignedOut }

data class SessionState(
    val status: SessionStatus = SessionStatus.Loading,
    val profile: Profile? = null,
    /** `null` while memberships are loading. */
    val destination: Destination? = null,
    /** The module flags of the household in [destination], for tab filtering. */
    val household: LandingHousehold? = null,
    /** Online, memberships could not be read: a blocking error with retry. */
    val loadFailed: Boolean = false,
)

/**
 * App-wide auth state: bootstraps from the stored session (AC6), follows session changes, loads the
 * profile (AC8) and resolves where to land (AC4/AC5). Port of iOS `SessionModel`.
 * [scope] is injectable so tests control the dispatcher; it is cancelled with the ViewModel.
 */
class SessionViewModel(
    private val auth: AuthService,
    private val lastHousehold: LastHouseholdStore,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var userId: UUID? = null
    private var startJob: Job? = null
    private var loadJob: Job? = null

    /** Reads the stored session, then follows changes for the life of the app. Idempotent. */
    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            apply(auth.currentSession())
            auth.sessionChanges.collect { apply(it) }
        }
    }

    fun signOut() {
        scope.launch { auth.signOut() }
    }

    fun retry() {
        userId?.let(::load)
    }

    /** Resolves once the profile and landing destination for the current session are in. */
    suspend fun awaitLoaded() {
        loadJob?.join()
    }

    private fun apply(session: AuthSession?) {
        if (session == null) {
            userId = null
            loadJob?.cancel()
            mutableState.value = SessionState(status = SessionStatus.SignedOut)
            return
        }
        mutableState.update { it.copy(status = SessionStatus.SignedIn) }
        // Token refreshes re-emit the same user; do not reload or bounce the landing.
        if (userId == session.userId) return
        userId = session.userId
        load(session.userId)
    }

    private fun load(userId: UUID) {
        loadJob?.cancel()
        mutableState.update { it.copy(destination = null, household = null, loadFailed = false) }
        loadJob = scope.launch {
            val (profileResult, membershipsResult) = coroutineScope {
                val profile = async { auth.fetchProfile(userId) }
                val memberships = async { auth.fetchMemberships(userId) }
                profile.await() to memberships.await()
            }
            if (!isActive || this@SessionViewModel.userId != userId) return@launch
            // A missing row (trigger not run yet) or a failed read leaves the name empty.
            val profile = (profileResult as? Fetch.Success)?.value
            when (membershipsResult) {
                is Fetch.Success -> {
                    val memberships = membershipsResult.value
                    val lastId = lastHousehold.read()
                    val chosen = Landing.pickHousehold(memberships, lastId)
                    if (chosen != null) lastHousehold.write(chosen.householdId)
                    mutableState.update {
                        it.copy(
                            profile = profile,
                            household = chosen?.household,
                            destination = Landing.destination(memberships, lastId),
                        )
                    }
                }
                // Offline, an existing session stays usable (SPEC §6.5): enter the shell at Home.
                // Online, a failure is a real problem and stays a blocking error.
                is Fetch.Failure -> mutableState.update {
                    if (isOnline()) it.copy(profile = profile, loadFailed = true)
                    else it.copy(profile = profile, destination = Destination.Home)
                }
            }
        }
    }
}
