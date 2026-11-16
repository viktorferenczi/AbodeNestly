package com.abodenestly.core.auth

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Profile
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.core.profile.InMemoryProfileOutbox
import com.abodenestly.core.profile.ProfileMutation
import com.abodenestly.core.profile.ProfileOutbox
import com.abodenestly.core.switcher.HouseholdsCache
import com.abodenestly.core.switcher.InMemoryHouseholdsCache
import com.abodenestly.core.switcher.Switcher
import com.abodenestly.sync.DatabaseRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
    /** The selected bottom tab; a landing starts on its tab and a household switch keeps it (CORE-07 AC2). */
    val selectedTab: AppTab = AppTab.Home,
    /** Name of the current household, from the cached household list; `null` until it is known. */
    val householdName: String? = null,
    /** Name of the household being switched to, for the whole switch (blocks a second one). */
    val switching: String? = null,
    /** A long-press in the switcher asks Home to show Settings (DESIGN B3 → B5); the shell clears it. */
    val settingsRequested: Boolean = false,
)

/** Why [SessionViewModel.switchTo] did not switch. */
sealed class SwitchError(message: String) : Exception(message) {
    /** The target is not in the cached household list. */
    data object UnknownHousehold : SwitchError("Unknown household")

    /** The target's database could not be opened. */
    data object OpenFailed : SwitchError("Household database did not open")

    /** The person signed out (or changed) while the switch ran. */
    data object SessionEnded : SwitchError("Session ended during the switch")
}

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
    private val profileOutbox: ProfileOutbox = InMemoryProfileOutbox(),
    private val database: DatabaseRegistry? = null,
    private val householdsCache: HouseholdsCache = InMemoryHouseholdsCache(),
    private val api: ApiService? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** The signed-in user, or `null` when signed out. */
    var userId: UUID? = null
        private set

    /** The signed-in user's email from the auth session; the Profile screen shows it read-only. */
    var email: String? = null
        private set
    private var startJob: Job? = null
    private var loadJob: Job? = null
    private var writeJob: Job? = null

    /** The close a sign-out started; an open for the next account waits for it, so it cannot close the new file. */
    private var closeJob: Job? = null

    /** Set before the first suspension of a switch, so a second tap cannot start another one. */
    private var switchClaimed = false

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

    fun selectTab(tab: AppTab) {
        mutableState.update { it.copy(selectedTab = tab) }
    }

    /** The shell showed Settings for a long-press in the switcher. */
    fun settingsShown() {
        mutableState.update { it.copy(settingsRequested = false) }
    }

    fun signOut() {
        scope.launch { auth.signOut() }
    }

    fun retry() {
        userId?.let(::load)
    }

    /** A household was just created: open its database, then open it on Home without another round trip (CORE-02 AC2). */
    fun enter(household: LandingHousehold) {
        loadJob?.cancel()
        val user = userId
        // The shell must leave Welcome on this turn, before the file open suspends, or the idle form is tappable again
        // and a second tap sends another POST /households. The loading state is what it shows meanwhile.
        mutableState.update { it.copy(destination = null, household = null, householdName = null, loadFailed = false) }
        writeJob?.cancel()
        writeJob = scope.launch {
            // The file opens before the household is published; a failed open never shows a household without its file.
            val opened = openDatabase(household.id)
            if (userId != user) return@launch
            if (!opened) {
                mutableState.update { it.copy(loadFailed = true) }
                return@launch
            }
            mutableState.update {
                it.copy(household = household, householdName = cachedName(user, household.id), loadFailed = false)
                    .landed(Destination.Household(household.id, AppTab.Home))
            }
            lastHousehold.write(household.id)
        }
    }

    /**
     * Settings saved new module flags for the current household. Only the flags change, never the destination,
     * so the tab bar follows in place without bouncing the person back to the start tab.
     */
    fun updateHousehold(updated: com.abodenestly.contract.models.Household) {
        mutableState.update { s ->
            val current = s.household
            if (current == null || current.id != updated.id) s
            else s.copy(
                household = current.copy(
                    moduleShopping = updated.moduleShopping,
                    modulePantry = updated.modulePantry,
                    moduleMaintenance = updated.moduleMaintenance,
                ),
                householdName = updated.name,
            )
        }
    }

    /** The profile was edited on the Profile screen: the TopBar name follows without a reload. */
    fun updateProfile(profile: Profile) {
        mutableState.update { if (it.profile?.id == profile.id || it.profile == null) it.copy(profile = profile) else it }
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

    /** The person left their household: forget it as the last one used and land again (Welcome or another household). */
    fun leftHousehold() {
        val user = userId ?: return
        loadJob?.cancel()
        mutableState.update { it.copy(destination = null, household = null, loadFailed = false) }
        loadJob = scope.launch {
            lastHousehold.clear()
            runLoad(user)
        }
    }

    /** Resolves once the profile and landing destination for the current session are in. */
    suspend fun awaitLoaded() {
        loadJob?.join()
    }

    private fun apply(session: AuthSession?) {
        if (session == null) {
            val previous = userId
            if (previous != null) scope.launch { householdsCache.clear(previous) }
            closeJob = scope.launch { database?.close() }
            userId = null
            email = null
            loadJob?.cancel()
            writeJob?.cancel()
            mutableState.value = SessionState(status = SessionStatus.SignedOut)
            return
        }
        email = session.email
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
            // Edits still queued in the outbox win over the server row, or a stale read would hide them.
            is Fetch.Success -> profileResult.value
                ?.let { ProfileMutation.overlay(it, profileOutbox.pending(userId)) }
                ?.also { profileCache.write(userId, it) }
            is Fetch.Failure -> profileCache.read(userId)
        }
        when (membershipsResult) {
            is Fetch.Success -> {
                val memberships = membershipsResult.value
                val lastId = lastHousehold.read()
                val chosen = Landing.pickHousehold(memberships, lastId)
                if (chosen != null) {
                    // Never publish a household whose file is not open.
                    if (!openDatabase(chosen.householdId)) {
                        if (!currentCoroutineContext().isActive || this.userId != userId) return
                        mutableState.update { it.copy(profile = profile, loadFailed = true) }
                        return
                    }
                    lastHousehold.write(chosen.householdId)
                } else {
                    runCatching { database?.close() }
                }
                if (!currentCoroutineContext().isActive || this.userId != userId) return
                var destination = Landing.destination(memberships, lastId)
                if (destination is Destination.Household && destination.id == landOnHome) {
                    destination = destination.copy(tab = AppTab.Home)
                }
                landOnHome = null
                val name = chosen?.householdId?.let { cachedName(userId, it) }
                mutableState.update {
                    it.copy(profile = profile, household = chosen?.household, householdName = name).landed(destination)
                }
                primeHouseholds(userId)
            }
            // Offline, an existing session stays usable (SPEC §6.5): enter the shell at Home.
            // Online, a failure is a real problem and stays a blocking error.
            is Fetch.Failure -> {
                if (isOnline()) {
                    mutableState.update { it.copy(profile = profile, loadFailed = true) }
                    return
                }
                // The last household's database still opens; its name comes from the cached list.
                val last = lastHousehold.read()
                if (last != null && !openDatabase(last)) {
                    if (!currentCoroutineContext().isActive || this.userId != userId) return
                    mutableState.update { it.copy(profile = profile, loadFailed = true) }
                    return
                }
                if (!currentCoroutineContext().isActive || this.userId != userId) return
                val name = last?.let { cachedName(userId, it) }
                mutableState.update { it.copy(profile = profile, householdName = name).landed(Destination.Home) }
            }
        }
    }

    /** Opens the household's database; `true` when there is none to open (tests, signed-out previews) or it opened. */
    private suspend fun openDatabase(id: UUID): Boolean {
        val registry = database ?: return true
        closeJob?.join()
        val user = userId
        return try {
            // Asked inside the registry lock: an open that outlived its session never replaces the next one's file.
            registry.open(id) { userId == user }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun cachedName(userId: UUID?, householdId: UUID): String? =
        userId?.let { householdsCache.read(it).firstOrNull { entry -> entry.household.id == householdId }?.household?.name }

    /** Online: reads the household list once so the TopBar name and the switcher have it (the memberships read carries only module flags). */
    private suspend fun primeHouseholds(userId: UUID) {
        val api = api ?: return
        if (!isOnline()) return
        val result = api.fetchMyHouseholds(userId)
        if (result !is ApiResult.Success || this.userId != userId) return
        householdsCache.write(userId, result.value)
        val current = (state.value.destination as? Destination.Household)?.id
        val name = current?.let { id -> result.value.firstOrNull { it.household.id == id }?.household?.name }
        if (name != null) mutableState.update { it.copy(householdName = name) }
    }

    /**
     * Switches households (CORE-07): closes the current database, opens the target's, keeps the selected tab (or the
     * target's start tab when that module is off there) and lands there. Reads only the cached household list, so it
     * works offline (AC5). If the target cannot be opened the previous database is reopened and [SwitchError.OpenFailed]
     * is thrown. [openSettings] then routes to Home → Settings (DESIGN B3 long-press).
     */
    suspend fun switchTo(id: UUID, openSettings: Boolean = false) {
        val user = userId ?: return
        if (switchClaimed) return
        switchClaimed = true
        try {
            val previous = (state.value.destination as? Destination.Household)?.id ?: lastHousehold.read()
            if (id == previous) {
                if (openSettings) requestSettings()
                return
            }
            val target = householdsCache.read(user).firstOrNull { it.household.id == id } ?: throw SwitchError.UnknownHousehold
            mutableState.update { it.copy(switching = target.household.name) }
            // A switch that was started is finished, even if the sheet that asked is dismissed meanwhile.
            withContext(NonCancellable) {
                val opened = openDatabase(id)
                // Signed out meanwhile: write nothing for the next account, and fail so the sheet stays open.
                if (userId != user) throw SwitchError.SessionEnded
                if (!opened) {
                    // The household on screen must keep its file; if even that will not reopen, the shell shows the error.
                    if (previous != null && !openDatabase(previous) && userId == user) {
                        mutableState.update { it.copy(loadFailed = true) }
                    }
                    throw SwitchError.OpenFailed
                }
                val landing = target.household.toLanding()
                lastHousehold.write(id)
                mutableState.update {
                    it.copy(household = landing, householdName = target.household.name, loadFailed = false)
                        .landed(Destination.Household(id, Switcher.targetTab(it.selectedTab, landing)))
                }
                if (openSettings) requestSettings()
            }
        } finally {
            switchClaimed = false
            mutableState.update { it.copy(switching = null) }
        }
    }

    private fun requestSettings() {
        mutableState.update { it.copy(selectedTab = AppTab.Home, settingsRequested = true) }
    }

    /** A landing (load, create, join) starts on its tab, and a switch passes the kept tab through here. */
    private fun SessionState.landed(destination: Destination?): SessionState = copy(
        destination = destination,
        selectedTab = when (destination) {
            is Destination.Household -> destination.tab
            Destination.Home -> AppTab.Home
            Destination.Welcome, null -> selectedTab
        },
    )

    private fun Household.toLanding() = LandingHousehold(id, moduleShopping, modulePantry, moduleMaintenance)
}
