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
) {
    val state by session.state.collectAsState()
    val online by network.isOnline.collectAsState()
    // Edge-to-edge is on (targetSdk 35): the background runs under the system bars, the content stays clear of them.
    Box(Modifier.fillMaxSize().background(LocalTokens.current.bg).safeDrawingPadding()) {
        when (state.status) {
            SessionStatus.Loading -> Box(Modifier.fillMaxSize().testTag("session.loading"))
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = online)
            SessionStatus.SignedIn -> SignedInShell(
                state, welcome, api, lastHousehold, membersCache, householdCache, webBaseUrl, profileDeps,
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
    meId: UUID?,
    email: String?,
    isOnline: Boolean,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onLeft: () -> Unit,
    onHouseholdChanged: (Household) -> Unit,
    onProfileChanged: (Profile) -> Unit,
) {
    // AC8: the TopBar shows the person's display name once the profile row is in.
    Scaffold(topBar = { TopBar("AbodeNestly", state.profile?.displayName) }, containerColor = LocalTokens.current.bg) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loadFailed -> Centered {
                    Text("Couldn't load your households.", modifier = Modifier.testTag("session.error"))
                    Button(onClick = onRetry, modifier = Modifier.testTag("session.retry")) { Text("Try again") }
                }
                state.destination == null -> Centered { CircularProgressIndicator(Modifier.testTag("session.loading")) }
                state.destination == Destination.Welcome ->
                    WelcomeScreen(welcome, displayName = state.profile?.displayName, isOnline = isOnline, onSignOut = onSignOut)
                else -> Tabs(state.destination, state.household, state.profile, api, lastHousehold, membersCache, householdCache, webBaseUrl, profileDeps, meId, email, isOnline, onSignOut, onLeft, onHouseholdChanged, onProfileChanged)
            }
        }
    }
}

/** Bottom tabs, hidden for disabled modules (DESIGN A6). Content is a placeholder until the module stories land. */
@Composable
private fun Tabs(
    destination: Destination?,
    household: LandingHousehold?,
    profile: Profile?,
    api: ApiService,
    lastHousehold: LastHouseholdStore,
    membersCache: MembersCache,
    householdCache: HouseholdCache,
    webBaseUrl: String,
    profileDeps: ProfileDeps,
    meId: UUID?,
    email: String?,
    isOnline: Boolean,
    onSignOut: () -> Unit,
    onLeft: () -> Unit,
    onHouseholdChanged: (Household) -> Unit,
    onProfileChanged: (Profile) -> Unit,
) {
    val start = (destination as? Destination.Household)?.tab ?: AppTab.Home
    val tabs = Landing.visibleTabs(household)
    var picked by rememberSaveable(destination) { mutableStateOf(start) }
    // Settings can switch the selected module off; the tab shell proper is X7, this only keeps a hidden tab from staying selected.
    val selected = if (picked in tabs) picked else AppTab.Home
    // No NavHost yet, so Members is a flag over the Home tab. The household may be unknown offline at launch; fall back to the last one used.
    var showMembers by rememberSaveable(destination) { mutableStateOf(false) }
    var showSettings by rememberSaveable(destination) { mutableStateOf(false) }
    var showProfile by rememberSaveable(destination) { mutableStateOf(false) }
    var householdId by remember { mutableStateOf<UUID?>(household?.id) }
    LaunchedEffect(household) { householdId = household?.id ?: lastHousehold.read() }
    val members = householdId
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
        Box(Modifier.weight(1f).fillMaxSize().testTag("tab.${selected.key}.content"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.key.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium)
                if (selected == AppTab.Home) {
                    if (members != null && meId != null) TextButton(onClick = { showMembers = true }, modifier = Modifier.testTag("home.members")) { Text("Members") }
                    if (members != null && meId != null) TextButton(onClick = { showSettings = true }, modifier = Modifier.testTag("home.settings")) { Text("Settings") }
                    if (meId != null) TextButton(onClick = { showProfile = true }, modifier = Modifier.testTag("home.profile")) { Text("Profile") }
                    TextButton(onClick = onSignOut, modifier = Modifier.testTag("home.signout")) { Text("Sign out") }
                }
            }
        }
        NavigationBar(containerColor = LocalTokens.current.surface) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { picked = tab },
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
