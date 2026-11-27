package com.abodenestly.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.Landing
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.LastHouseholdStore
import com.abodenestly.core.auth.SessionState
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.members.MembersCache
import com.abodenestly.core.search.RecentSearchStore
import com.abodenestly.core.notificationprefs.NotificationPrefsScreen
import com.abodenestly.core.recentlydeleted.RecentlyDeletedScreen
import com.abodenestly.core.search.SearchScreen
import com.abodenestly.core.search.searchModules
import com.abodenestly.core.search.tab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Profile
import com.abodenestly.core.members.MembersScreen
import com.abodenestly.core.profile.ProfileDeps
import com.abodenestly.core.profile.ProfileScreen
import com.abodenestly.core.settings.HouseholdCache
import com.abodenestly.core.switcher.HouseholdsCache
import com.abodenestly.core.switcher.SwitcherSheet
import com.abodenestly.core.switcher.SwitcherViewModel
import com.abodenestly.core.home.HomeEvent
import com.abodenestly.core.home.HomeModules
import com.abodenestly.core.home.HomeScreen
import com.abodenestly.shopping.BarcodeDrainEffect
import com.abodenestly.shopping.ShoppingTab
import com.abodenestly.shopping.rememberScannerSetup
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.RealtimeSubscribing
import com.abodenestly.sync.SyncEngine
import com.abodenestly.core.sync.HouseholdRealtimeEffect
import com.abodenestly.core.sync.SyncSheet
import com.abodenestly.core.sync.SyncSheetState
import com.abodenestly.core.sync.SyncSheetViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.abodenestly.core.settings.HouseholdSettingsScreen
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.core.welcome.WelcomeScreen
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.NetworkMonitor
import com.abodenestly.ui.Tokens
import com.abodenestly.ui.TopBar
import java.util.UUID

/** Loading → sign-in → shell. The landing rule (Welcome or the household's start tab) is resolved in [SessionViewModel]. */
@Composable
fun AppRoot(
    session: SessionViewModel,
    signIn: SignInViewModel,
    welcome: WelcomeViewModel,
    network: NetworkMonitor,
    api: ApiService,
    lastHousehold: LastHouseholdStore,
    membersCache: MembersCache,
    householdCache: HouseholdCache,
    webBaseUrl: String,
    profileDeps: ProfileDeps,
    database: DatabaseRegistry,
    householdsCache: HouseholdsCache,
    sync: SyncEngine,
    recentSearches: RecentSearchStore,
    realtime: RealtimeSubscribing,
    foreground: StateFlow<Boolean>,
) {
    val state by session.state.collectAsState()
    val online by network.isOnline.collectAsState()
    val inForeground by foreground.collectAsState()
    // Edge-to-edge is on (targetSdk 35): the background runs under the system bars, the content stays clear of them.
    Box(Modifier.fillMaxSize().background(LocalTokens.current.bg).safeDrawingPadding()) {
        when (state.status) {
            SessionStatus.Loading -> Box(Modifier.fillMaxSize().testTag("session.loading"))
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = online)
            SessionStatus.SignedIn -> SignedInShell(
                state, welcome, api, lastHousehold, membersCache, householdCache, webBaseUrl, profileDeps, database, householdsCache, sync, recentSearches, network.isOnline, realtime, inForeground,
                onSwitch = session::switchTo, onSelectTab = session::selectTab, onSettingsShown = session::settingsShown,
                meId = session.userId, email = session.email, isOnline = online, onRetry = session::retry, onSignOut = session::signOut,
                onLeft = session::leftHousehold, onHouseholdChanged = session::updateHousehold,
                onProfileChanged = session::updateProfile,
            )
        }
    }
}

@Composable
private fun SignedInShell(
    state: SessionState,
    welcome: WelcomeViewModel,
    api: ApiService,
    lastHousehold: LastHouseholdStore,
    membersCache: MembersCache,
    householdCache: HouseholdCache,
    webBaseUrl: String,
    profileDeps: ProfileDeps,
    database: DatabaseRegistry,
    householdsCache: HouseholdsCache,
    sync: SyncEngine,
    recentSearches: RecentSearchStore,
    onlineFlow: StateFlow<Boolean>,
    realtime: RealtimeSubscribing,
    foreground: Boolean,
    onSwitch: suspend (UUID, Boolean) -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onSettingsShown: () -> Unit,
    meId: UUID?,
    email: String?,
    isOnline: Boolean,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onLeft: () -> Unit,
    onHouseholdChanged: (Household) -> Unit,
    onProfileChanged: (Profile) -> Unit,
) {
    var showSwitcher by remember { mutableStateOf(false) }
    var showWelcome by remember { mutableStateOf(false) }
    val currentHouseholdId = (state.destination as? Destination.Household)?.id
    // "Create or join another" ends when a household was entered (created or joined), which changes the id.
    LaunchedEffect(currentHouseholdId) { showWelcome = false }
    val canSwitch = (state.destination is Destination.Household || state.destination == Destination.Home) && !state.loadFailed
    // Search (CORE-09) is a flag over the shell like Members/Settings; the magnifier sits on Home's TopBar (DESIGN B8).
    var showSearch by rememberSaveable(currentHouseholdId) { mutableStateOf(false) }
    var searchHouseholdId by remember { mutableStateOf<UUID?>(state.household?.id) }
    LaunchedEffect(state.household) { searchHouseholdId = state.household?.id ?: lastHousehold.read() }
    // Offline at launch the landing read can fail; the cached households list still knows the module flags.
    var cachedModules by remember { mutableStateOf<HomeModules?>(null) }
    LaunchedEffect(state.household, searchHouseholdId, meId) {
        cachedModules = null
        val id = searchHouseholdId
        if (state.household == null && id != null && meId != null) {
            cachedModules = householdsCache.read(meId).firstOrNull { it.household.id == id }?.household?.let(HomeModules::of)
        }
    }
    val searchModules = (state.household?.let(HomeModules::of) ?: cachedModules)?.searchModules()
    val canSearch = canSwitch && searchHouseholdId != null && searchModules != null && !showWelcome
    // SHOP-09: the household's Realtime subscription, for as long as the shell and the foreground last. Its flags come from the
    // landing read or the cached households list, like search's; a change kicks the same sync engine as every other trigger.
    val shellModules = state.household?.let(HomeModules::of) ?: cachedModules
    HouseholdRealtimeEffect(
        realtime, if (canSwitch) searchHouseholdId else null, shellModules, foreground,
        kick = { sync.kick() },
    )
    // SHOP-10: barcode lookups queued offline resolve on household open, on reconnect and after each sync run, only while Shopping is on.
    BarcodeDrainEffect(
        householdId = if (canSwitch && shellModules?.shopping == true) searchHouseholdId else null,
        database = database, sync = sync, isOnline = onlineFlow, lookup = api::product,
    )
    // SHOP-08: the sync chip sits in the TopBar on every signed-in screen and opens the sync sheet (DESIGN B12).
    var showSync by remember { mutableStateOf(false) }
    val syncSheet = rememberSyncSheet(searchHouseholdId, database, sync, onlineFlow)
    val syncUi by remember(syncSheet) { syncSheet?.state ?: MutableStateFlow(SyncSheetState()) }.collectAsState()
    // AC8: the TopBar shows the person's display name once the profile row is in. The title opens the switcher (B3).
    Scaffold(
        topBar = {
            TopBar(
                state.householdName ?: "AbodeNestly", state.profile?.displayName,
                onTitleClick = if (canSwitch && meId != null) ({ showSwitcher = true }) else null,
                status = syncUi.chip, onStatusClick = { showSync = true },
                actions = if (canSearch && !showSearch && state.selectedTab == AppTab.Home) {
                    {
                        IconButton(onClick = { showSearch = true }, modifier = Modifier.testTag("topbar.search")) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                } else null,
            )
        },
        containerColor = LocalTokens.current.bg,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loadFailed -> Centered {
                    Text("Couldn't load your households.", modifier = Modifier.testTag("session.error"))
                    Button(onClick = onRetry, modifier = Modifier.testTag("session.retry")) { Text("Try again") }
                }
                state.destination == null -> Centered { CircularProgressIndicator(Modifier.testTag("session.loading")) }
                state.destination == Destination.Welcome ->
                    WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = isOnline, onSignOut = onSignOut)
                showSearch && canSearch -> {
                    BackHandler { showSearch = false }
                    Column(Modifier.fillMaxSize()) {
                        TextButton(onClick = { showSearch = false }, modifier = Modifier.testTag("search.back")) { Text("Back") }
                        SearchScreen(
                            searchHouseholdId!!, searchModules!!, database, recentSearches,
                            // No entity screens yet: a result opens its module's tab (DESIGN B8 deviation).
                            onNavigate = { showSearch = false; onSelectTab(it.target.module.tab) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                showWelcome -> {
                    BackHandler { showWelcome = false }
                    Column(Modifier.fillMaxSize()) {
                        TextButton(onClick = { showWelcome = false }, modifier = Modifier.testTag("switcher.welcome.cancel")) { Text("Cancel") }
                        WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = isOnline, onSignOut = onSignOut, modifier = Modifier.weight(1f))
                    }
                }
                else -> Tabs(
                    state.destination, state.household, state.profile, state.selectedTab, state.settingsRequested, onSelectTab, onSettingsShown,
                    api, lastHousehold, membersCache, householdCache, householdsCache, webBaseUrl, profileDeps, database, sync, onlineFlow, meId, email, isOnline, onSignOut, onLeft, onHouseholdChanged, onProfileChanged,
                )
            }
        }
        if (showSync && syncSheet != null) SyncSheet(syncSheet, onDismiss = { showSync = false })
        if (showSwitcher && meId != null) {
            SwitcherHost(
                meId, state.destination, api, lastHousehold, householdCache, householdsCache, database, isOnline,
                onSwitch = onSwitch, onDismiss = { showSwitcher = false }, onCreateOrJoin = { showSwitcher = false; showWelcome = true },
            )
        }
    }
}

/** One [SyncSheetViewModel] per household, with a scope that ends when the household changes or the shell leaves. */
@Composable
private fun rememberSyncSheet(householdId: UUID?, database: DatabaseRegistry, sync: SyncEngine, online: StateFlow<Boolean>): SyncSheetViewModel? {
    val scope = remember(householdId) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    return remember(householdId) { householdId?.let { SyncSheetViewModel(it, database, sync, online, scope) } }
}

/** One [SwitcherViewModel] per presentation, built once the current household is known (it may come from storage offline). */
@Composable
private fun SwitcherHost(
    userId: UUID,
    destination: Destination?,
    api: ApiService,
    lastHousehold: LastHouseholdStore,
    householdCache: HouseholdCache,
    householdsCache: HouseholdsCache,
    database: DatabaseRegistry,
    isOnline: Boolean,
    onSwitch: suspend (UUID, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onCreateOrJoin: () -> Unit,
) {
    val online by rememberUpdatedState(isOnline)
    val perform by rememberUpdatedState(onSwitch)
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    var viewModel by remember { mutableStateOf<SwitcherViewModel?>(null) }
    LaunchedEffect(Unit) {
        val currentId = (destination as? Destination.Household)?.id ?: lastHousehold.read()
        viewModel = SwitcherViewModel(
            userId, currentId, currentId?.let { householdCache.read(userId, it) }, api, householdsCache, database,
            isOnline = { online }, perform = { id, settings -> perform(id, settings) }, scope = scope,
        )
    }
    viewModel?.let { SwitcherSheet(it, onDismiss, onCreateOrJoin) }
}

/** Bottom tabs, hidden for disabled modules (DESIGN A6). Content is a placeholder until the module stories land. */
@Composable
private fun Tabs(
    destination: Destination?,
    household: LandingHousehold?,
    profile: Profile?,
    selectedTab: AppTab,
    settingsRequested: Boolean,
    onSelectTab: (AppTab) -> Unit,
    onSettingsShown: () -> Unit,
    api: ApiService,
    lastHousehold: LastHouseholdStore,
    membersCache: MembersCache,
    householdCache: HouseholdCache,
    householdsCache: HouseholdsCache,
    webBaseUrl: String,
    profileDeps: ProfileDeps,
    database: DatabaseRegistry,
    sync: SyncEngine,
    onlineFlow: StateFlow<Boolean>,
    meId: UUID?,
    email: String?,
    isOnline: Boolean,
    onSignOut: () -> Unit,
    onLeft: () -> Unit,
    onHouseholdChanged: (Household) -> Unit,
    onProfileChanged: (Profile) -> Unit,
) {
    val tabs = Landing.visibleTabs(household)
    // Settings can switch the selected module off; the tab shell proper is X7, this only keeps a hidden tab from staying selected.
    val selected = if (selectedTab in tabs) selectedTab else AppTab.Home
    // Store the fallback, so a household switch keeps the tab that is on screen rather than the hidden one.
    LaunchedEffect(selected, selectedTab) { if (selected != selectedTab) onSelectTab(selected) }
    // No NavHost yet, so Members is a flag over the Home tab. The household may be unknown offline at launch; fall back to the last one used.
    var showMembers by rememberSaveable(destination) { mutableStateOf(false) }
    var showSettings by rememberSaveable(destination) { mutableStateOf(false) }
    var showDeleted by rememberSaveable(destination) { mutableStateOf(false) }
    var showNotifications by rememberSaveable(destination) { mutableStateOf(false) }
    var showProfile by rememberSaveable(destination) { mutableStateOf(false) }
    var householdId by remember { mutableStateOf<UUID?>(household?.id) }
    LaunchedEffect(household) { householdId = household?.id ?: lastHousehold.read() }
    // A long-press in the switcher asks for this household's Settings (B3 → B5).
    LaunchedEffect(settingsRequested) {
        if (settingsRequested) {
            showSettings = true
            onSettingsShown()
        }
    }
    val members = householdId
    // Offline at launch the landing read fails and `household` stays null; the flags then come from the cached households list, so the Today cards and the all-off prompt still show.
    var cachedModules by remember { mutableStateOf<HomeModules?>(null) }
    LaunchedEffect(household, members, meId) {
        cachedModules = null
        if (household == null && members != null && meId != null) {
            cachedModules = householdsCache.read(meId).firstOrNull { it.household.id == members }?.household?.let(HomeModules::of)
        }
    }
    val deletedModules = (household?.let(HomeModules::of) ?: cachedModules)?.searchModules()
    // Over Profile, so Back from it lands on Profile again.
    if (showProfile && showNotifications && members != null && meId != null) {
        BackHandler { showNotifications = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showNotifications = false }, modifier = Modifier.testTag("notif.back")) { Text("Back") }
            // Unknown flags show only Activity, like iOS.
            NotificationPrefsScreen(members, meId, deletedModules ?: emptySet(), database, sync, modifier = Modifier.weight(1f))
        }
        return
    }
    if (showProfile && meId != null) {
        BackHandler { showProfile = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showProfile = false }, modifier = Modifier.testTag("profile.back")) { Text("Back") }
            ProfileScreen(
                meId, email, profile, profileDeps, isOnline,
                onProfileChanged = onProfileChanged, onSignOut = onSignOut,
                onOpenNotifications = { showNotifications = true }, modifier = Modifier.weight(1f),
            )
        }
        return
    }
    if (showDeleted && members != null && deletedModules != null) {
        BackHandler { showDeleted = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showDeleted = false }, modifier = Modifier.testTag("deleted.back")) { Text("Back") }
            RecentlyDeletedScreen(members, deletedModules, database, sync, modifier = Modifier.weight(1f))
        }
        return
    }
    if (showMembers && members != null && meId != null) {
        BackHandler { showMembers = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showMembers = false }, modifier = Modifier.testTag("members.back")) { Text("Back") }
            MembersScreen(
                members, meId, api, isOnline, webBaseUrl, membersCache,
                onLeft = { showMembers = false; onLeft() },
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    if (showSettings && members != null && meId != null) {
        BackHandler { showSettings = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showSettings = false }, modifier = Modifier.testTag("settings.back")) { Text("Back") }
            HouseholdSettingsScreen(
                members, meId, api, householdCache, isOnline,
                onHouseholdChanged = onHouseholdChanged,
                onLeft = { showSettings = false; onLeft() },
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (selected == AppTab.Home && members != null) {
            Column(Modifier.weight(1f).fillMaxSize().testTag("tab.home.content")) {
                HomeScreen(
                    members, household?.let(HomeModules::of) ?: cachedModules, database, sync,
                    onNavigate = { event ->
                        when (event) {
                            is HomeEvent.OpenModule -> onSelectTab(event.tab)
                            HomeEvent.OpenSettings -> if (meId != null) showSettings = true
                            HomeEvent.OpenMembers -> if (meId != null) showMembers = true
                            HomeEvent.OpenRecentlyDeleted -> if (deletedModules != null) showDeleted = true
                            HomeEvent.OpenProfile -> if (meId != null) showProfile = true
                            HomeEvent.Refresh -> Unit
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSignOut, modifier = Modifier.testTag("home.signout")) { Text("Sign out") }
            }
        } else if (selected == AppTab.Shopping && members != null) {
            // SHOP-01/02: the module owns its screens and its tag (`tab.shopping.content`). Module off: the tab is absent, so this never composes.
            ShoppingTab(members, database, sync, onlineFlow, meId, Modifier.weight(1f), scanner = rememberScannerSetup(api::product))
        } else {
            Box(Modifier.weight(1f).fillMaxSize().testTag("tab.${selected.key}.content"), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.key.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium)
                    if (selected == AppTab.Home) {
                        // No household known yet (offline before any was read): nothing to show a feed for.
                        if (meId != null) TextButton(onClick = { showProfile = true }, modifier = Modifier.testTag("home.profile")) { Text("Profile") }
                        TextButton(onClick = onSignOut, modifier = Modifier.testTag("home.signout")) { Text("Sign out") }
                    }
                }
            }
        }
        NavigationBar(containerColor = LocalTokens.current.surface) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelectTab(tab) },
                    icon = {},
                    label = { Text(tab.key.replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.testTag("tab.${tab.key}"),
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) { content() }
}
