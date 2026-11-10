package com.abodenestly.app

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.SessionState
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.NetworkMonitor
import com.abodenestly.ui.Tokens
import com.abodenestly.ui.TopBar

/** Loading → sign-in → shell. The landing rule (Welcome or the household's start tab) is resolved in [SessionViewModel]. */
@Composable
fun AppRoot(session: SessionViewModel, signIn: SignInViewModel, network: NetworkMonitor) {
    val state by session.state.collectAsState()
    val online by network.isOnline.collectAsState()
    // Edge-to-edge is on (targetSdk 35): the background runs under the system bars, the content stays clear of them.
    Box(Modifier.fillMaxSize().background(LocalTokens.current.bg).safeDrawingPadding()) {
        when (state.status) {
            SessionStatus.Loading -> Box(Modifier.fillMaxSize().testTag("session.loading"))
            SessionStatus.SignedOut -> SignInScreen(signIn, isOnline = online)
            SessionStatus.SignedIn -> SignedInShell(state, onRetry = session::retry, onSignOut = session::signOut)
        }
    }
}

@Composable
private fun SignedInShell(state: SessionState, onRetry: () -> Unit, onSignOut: () -> Unit) {
    // AC8: the TopBar shows the person's display name once the profile row is in.
    Scaffold(topBar = { TopBar("AbodeNestly", state.profile?.displayName) }, containerColor = LocalTokens.current.bg) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loadFailed -> Centered {
                    Text("Couldn't load your households.", modifier = Modifier.testTag("session.error"))
                    Button(onClick = onRetry, modifier = Modifier.testTag("session.retry")) { Text("Try again") }
                }
                state.destination == null -> Centered { CircularProgressIndicator(Modifier.testTag("session.loading")) }
                state.destination == Destination.Welcome -> Centered {
                    // Create/join arrives with CORE-02; this placeholder proves the landing rule.
                    Text("Welcome", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("welcome.title"))
                    state.profile?.let { Text(it.displayName, modifier = Modifier.testTag("welcome.name")) }
                    TextButton(onClick = onSignOut, modifier = Modifier.testTag("welcome.signout")) { Text("Sign out") }
                }
                else -> Tabs(state.destination, state.household, onSignOut)
            }
        }
    }
}

/** Bottom tabs, hidden for disabled modules (DESIGN A6). Content is a placeholder until the module stories land. */
@Composable
private fun Tabs(destination: Destination?, household: LandingHousehold?, onSignOut: () -> Unit) {
    val start = (destination as? Destination.Household)?.tab ?: AppTab.Home
    val tabs = buildList {
        if (household?.moduleShopping == true) add(AppTab.Shopping)
        if (household?.modulePantry == true) add(AppTab.Pantry)
        if (household?.moduleMaintenance == true) add(AppTab.Maintenance)
        add(AppTab.Home)
    }
    var selected by rememberSaveable(destination) { mutableStateOf(start) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxSize().testTag("tab.${selected.key}.content"), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.key.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.headlineMedium)
                if (selected == AppTab.Home) TextButton(onClick = onSignOut, modifier = Modifier.testTag("home.signout")) { Text("Sign out") }
            }
        }
        NavigationBar(containerColor = LocalTokens.current.surface) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { selected = tab },
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
