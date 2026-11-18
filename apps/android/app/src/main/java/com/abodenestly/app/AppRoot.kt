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
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
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
) {
    val state by session.state.collectAsState()
    val online by network.isOnline.collectAsState()
    // Edge-to-edge is on (targetSdk 35): the background runs under the system bars, the content stays clear of them.
    Box(Modifier.fillMaxSize().background(LocalTokens.current.bg).safeDrawingPadding()) {
        when (state.status) {
            SessionStatus.Loading -> Box(Modifier.fillMaxSize().testTag("session.loading"))
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = online)
            SessionStatus.SignedIn -> SignedInShell(
                state, welcome, api, lastHousehold, membersCache, householdCache, webBaseUrl, profileDeps, database, householdsCache, sync,
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
    // AC8: the TopBar shows the person's display name once the profile row is in. The title opens the switcher (B3).
    Scaffold(
        topBar = {
            TopBar(
                state.householdName ?: "AbodeNestly", state.profile?.displayName,
                onTitleClick = if (canSwitch && meId != null) ({ showSwitcher = true }) else null,
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
                showWelcome -> {
                    BackHandler { showWelcome = false }
                    Column(Modifier.fillMaxSize()) {
                        TextButton(onClick = { showWelcome = false }, modifier = Modifier.testTag("switcher.welcome.cancel")) { Text("Cancel") }
                        WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = isOnline, onSignOut = onSignOut, modifier = Modifier.weight(1f))
                    }
                }
                else -> Tabs(
                    state.destination, state.household, state.profile, state.selectedTab, state.settingsRequested, onSelectTab, onSettingsShown,
                    api, lastHousehold, membersCache, householdCache, householdsCache, webBaseUrl, profileDeps, database, sync, meId, email, isOnline, onSignOut, onLeft, onHouseholdChanged, onProfileChanged,
                )
            }
        }
        if (showSwitcher && meId != null) {
            SwitcherHost(
                meId, state.destination, api, lastHousehold, householdCache, householdsCache, database, isOnline,
                onSwitch = onSwitch, onDismiss = { showSwitcher = false }, onCreateOrJoin = { showSwitcher = false; showWelcome = true },
            )
        }
    }
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
    if (showProfile && meId != null) {
        BackHandler { showProfile = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showProfile = false }, modifier = Modifier.testTag("profile.back")) { Text("Back") }
            ProfileScreen(
                meId, email, profile, profileDeps, isOnline,
                onProfileChanged = onProfileChanged, onSignOut = onSignOut, modifier = Modifier.weight(1f),
            )
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
                            HomeEvent.OpenProfile -> if (meId != null) showProfile = true
                            HomeEvent.Refresh -> Unit
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSignOut, modifier = Modifier.testTag("home.signout")) { Text("Sign out") }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxSize().testTag("tab.${selected.key}.content"), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.key.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium)
                    if (BuildConfig.DEBUG && selected == AppTab.Shopping) LocalLists(database)
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

/** Debug only: the household database's placeholder lists, so E2E can see that each household has its own (CORE-07 AC4). */
@Composable
private fun LocalLists(database: DatabaseRegistry) {
    val current by database.current.collectAsState()
    var names by remember { mutableStateOf("") }
    LaunchedEffect(current) {
        names = if (current == null) "" else runCatching { database.lists().joinToString(", ") { it.name } }.getOrDefault("")
    }
    Text(names, modifier = Modifier.testTag("shopping.local-lists"))
}
