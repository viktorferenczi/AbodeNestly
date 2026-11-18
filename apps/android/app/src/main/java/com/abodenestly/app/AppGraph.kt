package com.abodenestly.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.AuthService
import com.abodenestly.core.auth.DataStoreLastHouseholdStore
import com.abodenestly.core.auth.DataStoreProfileCache
import com.abodenestly.core.auth.LastHouseholdStore
import com.abodenestly.core.members.DataStoreMembersCache
import com.abodenestly.core.profile.DataStoreProfileOutbox
import com.abodenestly.core.profile.HttpAvatarLoader
import com.abodenestly.core.profile.HttpProfileRemote
import com.abodenestly.core.profile.ProfileDeps
import com.abodenestly.core.profile.ProfileOutbox
import com.abodenestly.core.profile.ProfileSync
import com.abodenestly.core.members.MembersCache
import com.abodenestly.core.auth.ProfileCache
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.settings.DataStoreHouseholdCache
import com.abodenestly.core.settings.HouseholdCache
import com.abodenestly.core.switcher.DataStoreHouseholdsCache
import com.abodenestly.core.switcher.HouseholdsCache
import com.abodenestly.core.home.ActivityPull
import com.abodenestly.core.search.DataStoreRecentSearchStore
import com.abodenestly.core.search.RecentSearchStore
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.core.welcome.land
import com.abodenestly.shared.AppConfig
import com.abodenestly.ui.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File

/** Hand-written DI container (no Hilt, SKILL "Architecture"): created once in [AbodeNestlyApp]. */
class AppGraph(context: Context) {
    val config = AppConfig(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        webBaseUrl = BuildConfig.WEB_BASE_URL,
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        authScheme = BuildConfig.AUTH_URL_SCHEME,
    )
    val auth: AuthService = SupabaseAuthService(config)
    val api: ApiService = HttpApiService(config, auth)
    val network: NetworkMonitor = NetworkMonitor.observing(context)
    val lastHousehold: LastHouseholdStore =
        DataStoreLastHouseholdStore.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val membersCache: MembersCache =
        DataStoreMembersCache.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    /** Every household of the signed-in person, for the switcher (CORE-07). Settings writes follow into it. */
    val householdsCache: HouseholdsCache =
        DataStoreHouseholdsCache.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val householdCache: HouseholdCache =
        DataStoreHouseholdCache.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO), householdsCache)
    /** One Room database per household (SPEC §6.1); the session opens the current one. */
    val database = DatabaseRegistry(context)
    /** Recent searches per household (CORE-09). */
    val recentSearches: RecentSearchStore =
        DataStoreRecentSearchStore.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val profileCache: ProfileCache =
        DataStoreProfileCache.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val profileOutbox: ProfileOutbox =
        DataStoreProfileOutbox.create(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** Lives as long as the process; the drain triggers below are collected on it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profileSync = ProfileSync(
        profileOutbox, HttpProfileRemote(config, auth), profileCache,
        currentUser = { auth.currentSession()?.userId }, scope = appScope,
    )
    /** Interim `ActivityPull` until SHOP-08 brings the full push/pull loop behind the same seam. */
    val sync: SyncEngine = ActivityPull(api, database, isOnline = { network.isOnline.value }, scope = appScope)
    val profileDeps = ProfileDeps(
        profileCache, profileOutbox, profileSync, HttpAvatarLoader(config, auth), avatarDir = File(context.filesDir, "avatars"),
    )

    init {
        // Triggers (CORE-06): connectivity returning, and a session appearing (a queue left by an earlier run).
        appScope.launch { network.isOnline.filter { it }.collect { profileSync.kick() } }
        appScope.launch { auth.sessionChanges.filter { it != null }.collect { profileSync.kick() } }
        // CORE-08: the same connectivity trigger, plus entering or switching a household (its database just opened).
        appScope.launch { network.isOnline.filter { it }.collect { sync.kick() } }
        appScope.launch { database.current.filter { it != null }.collect { sync.kick() } }
    }

    val viewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            SessionViewModel::class.java -> SessionViewModel(auth, lastHousehold, isOnline = { network.isOnline.value }, profileCache = profileCache, profileOutbox = profileOutbox, database = database, householdsCache = householdsCache, api = api)
            SignInViewModel::class.java -> SignInViewModel(auth, redirectTo = config.authCallbackUrl)
            else -> error("Unknown view model ${modelClass.name}")
        } as T
    }

    /** Welcome hands its outcome to the session, so it is built with the session view model. */
    fun welcomeFactory(session: SessionViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WelcomeViewModel(api, onLanded = session::land) as T
    }
}
