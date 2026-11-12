package com.abodenestly.core.auth

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
    private val profileCache: ProfileCache = InMemoryProfileCache(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var userId: UUID? = null
    private var startJob: Job? = null
    private var loadJob: Job? = null
    private var writeJob: Job? = null

    /** A household the person just joined: it opens on Home whatever its modules (DESIGN B4). */
    private var landOnHome: UUID? = null

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

    /** A household was just created: open it on Home without another round trip (CORE-02 AC2). */
    fun enter(household: LandingHousehold) {
        loadJob?.cancel()
        // Publish before the DataStore write suspends: the shell must leave Welcome on this turn, or the idle form
        // is tappable again and a second tap sends another POST /households.
        mutableState.update {
            it.copy(
                household = household,
                destination = Destination.Household(household.id, AppTab.Home),
                loadFailed = false,
            )
        }
        writeJob?.cancel()
        writeJob = scope.launch { lastHousehold.write(household.id) }
    }

    /** An invite was accepted: reload the memberships and open that household on Home. */
    fun enterJoined(householdId: UUID) {
        val user = userId ?: return
        landOnHome = householdId
        // Load only after the write lands, so the landing rule picks the joined household.
        loadJob?.cancel()
        mutableState.update { it.copy(destination = null, household = null, loadFailed = false) }
        loadJob = scope.launch {
            lastHousehold.write(householdId)
            runLoad(user)
        }
    }

    /** Resolves once the profile and landing destination for the current session are in. */
    suspend fun awaitLoaded() {
        loadJob?.join()
    }

    private fun apply(session: AuthSession?) {
        if (session == null) {
            userId = null
            loadJob?.cancel()
            writeJob?.cancel()
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
        loadJob = scope.launch { runLoad(userId) }
    }

    private suspend fun runLoad(userId: UUID) {
        val (profileResult, membershipsResult) = coroutineScope {
            val profile = async { auth.fetchProfile(userId) }
            val memberships = async { auth.fetchMemberships(userId) }
            profile.await() to memberships.await()
        }
        if (!currentCoroutineContext().isActive || this.userId != userId) return
        // A missing row (trigger not run yet) leaves the name empty; a failed read (offline) falls back to the
        // last profile seen on this device so the name still renders.
        val profile = when (profileResult) {
            is Fetch.Success -> profileResult.value?.also { profileCache.write(userId, it) }
            is Fetch.Failure -> profileCache.read(userId)
        }
        when (membershipsResult) {
            is Fetch.Success -> {
                val memberships = membershipsResult.value
                val lastId = lastHousehold.read()
                val chosen = Landing.pickHousehold(memberships, lastId)
                if (chosen != null) lastHousehold.write(chosen.householdId)
                var destination = Landing.destination(memberships, lastId)
                if (destination is Destination.Household && destination.id == landOnHome) {
                    destination = destination.copy(tab = AppTab.Home)
                }
                landOnHome = null
                mutableState.update {
                    it.copy(profile = profile, household = chosen?.household, destination = destination)
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
