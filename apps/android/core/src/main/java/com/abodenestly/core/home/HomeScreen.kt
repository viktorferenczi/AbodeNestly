package com.abodenestly.core.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.contract.models.Activity
import com.abodenestly.core.auth.AppTab
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * DESIGN B4 Home tab. States: empty-activity, populated, all-modules-off. Today cards and footer links raise
 * [HomeEvent]s; the app owns navigation. Port of iOS `HomeView`, web `HomeFeed.tsx`.
 */
@Composable
fun HomeScreen(
    householdId: UUID,
    modules: HomeModules?,
    database: DatabaseRegistry,
    sync: SyncEngine,
    onNavigate: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held by the activity's ViewModelStore, so a rotation keeps the feed and does not re-collect it.
    val vm: HomeViewModel = viewModel(key = "home.$householdId") {
        HomeViewModel(database.activityFeed(householdId), sync, modules, onNavigate)
    }
    SideEffect { vm.navigate = onNavigate }
    LaunchedEffect(modules) { vm.setModules(modules) }
    val ui by vm.state.collectAsState()
    HomeContent(ui, vm::onEvent, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeContent(ui: HomeUiState, onEvent: (HomeEvent) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    // The kick is fire-and-forget; the indicator stays long enough to read as "asked".
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_INDICATOR_MS)
            refreshing = false
        }
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onEvent(HomeEvent.Refresh)
        },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("home.list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Tokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.loaded) {
                item("top") { TopSection(ui, onEvent) }
                item("activity-heading") {
                    Text(
                        "Activity", style = MaterialTheme.typography.titleMedium, color = tokens.text,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (ui.feed.isEmpty()) {
                    if (ui.state == HomeState.Empty) item("empty") { EmptyActivity(onEvent) }
                } else {
                    items(ui.feed, key = { it.id }) { FeedRow(it) }
                }
            }
            item("footer") {
                Column {
                    HorizontalDivider(color = tokens.line)
                    FooterLink("Members", "home.members") { onEvent(HomeEvent.OpenMembers) }
                    FooterLink("Settings", "home.settings") { onEvent(HomeEvent.OpenSettings) }
                    FooterLink("Profile", "home.profile") { onEvent(HomeEvent.OpenProfile) }
                }
            }
        }
    }
}

private const val REFRESH_INDICATOR_MS = 600L

@Composable
private fun TopSection(ui: HomeUiState, onEvent: (HomeEvent) -> Unit) {
    val tokens = LocalTokens.current
    val modules = ui.modules
    when {
        ui.state == HomeState.AllModulesOff -> Column(
            Modifier.fillMaxWidth().testTag("home.allOff"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Every module is off. Turn one on in Settings.", color = tokens.text)
            Button(onClick = { onEvent(HomeEvent.OpenSettings) }, modifier = Modifier.testTag("home.allOff.settings")) { Text("Settings") }
        }
        modules != null -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium, color = tokens.text, modifier = Modifier.semantics { heading() })
            // Counts stay 0 until the module screens exist; the cards carry the tap-through.
            if (modules.shopping) TodayCard(AppTab.Shopping, "Shopping", "On the list: 0", onEvent)
            if (modules.pantry) TodayCard(AppTab.Pantry, "Pantry", "Eat soon: 0 · Low: 0", onEvent)
            if (modules.maintenance) TodayCard(AppTab.Maintenance, "Maintenance", "Due tasks: 0", onEvent)
        }
    }
}

@Composable
private fun TodayCard(tab: AppTab, title: String, counts: String, onEvent: (HomeEvent) -> Unit) {
    val tokens = LocalTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.MinTouchTarget)
            .clickable(role = Role.Button) { onEvent(HomeEvent.OpenModule(tab)) }
            .padding(vertical = 8.dp)
            .testTag("home.today.${tab.key}")
            .semantics(mergeDescendants = true) {},
    ) {
        Text(title, color = tokens.text, style = MaterialTheme.typography.titleSmall)
        Text(counts, color = tokens.text2, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyActivity(onEvent: (HomeEvent) -> Unit) {
    val tokens = LocalTokens.current
    Column(Modifier.fillMaxWidth().testTag("home.empty"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Things your household does show up here.", color = tokens.text)
        Button(onClick = { onEvent(HomeEvent.OpenMembers) }, modifier = Modifier.testTag("home.empty.invite")) { Text("Invite a member") }
    }
}

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun FeedRow(activity: Activity) {
    val tokens = LocalTokens.current
    Column(Modifier.fillMaxWidth().testTag("home.feed.row").semantics(mergeDescendants = true) {}) {
        Text(activity.summary, color = tokens.text)
        Text(
            WHEN.format(activity.createdAt.atZoneSameInstant(java.time.ZoneId.systemDefault())),
            color = tokens.text2, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FooterLink(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag(tag)) {
        Text(label, color = LocalTokens.current.accent)
    }
}
