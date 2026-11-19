package com.abodenestly.core.home

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Activity
import com.abodenestly.core.auth.AppTab
import com.abodenestly.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What the Home screen shows. `loaded` is false until the first read of the local database, so empty does not flash. */
data class HomeUiState(
    val loaded: Boolean = false,
    val feed: List<Activity> = emptyList(),
    val modules: HomeModules? = null,
) {
    val state: HomeState get() = HomeFeed.state(feed, modules)
}

sealed interface HomeEvent {
    /** Pull to refresh. */
    data object Refresh : HomeEvent
    data class OpenModule(val tab: AppTab) : HomeEvent
    data object OpenSettings : HomeEvent
    data object OpenMembers : HomeEvent
    data object OpenRecentlyDeleted : HomeEvent
    data object OpenProfile : HomeEvent
}

/**
 * DESIGN B4. Reads the household's local `activity` rows (works offline, AC5) and asks the sync engine for fresh ones
 * on pull to refresh (AC4). Port of iOS `HomeModel`; navigation events go to [navigate].
 *
 * @param feed the stored rows, newest first (`DatabaseRegistry.activityFeed`); the window is applied here.
 */
class HomeViewModel(
    feed: Flow<List<Activity>>,
    private val sync: SyncEngine,
    modules: HomeModules?,
    var navigate: (HomeEvent) -> Unit = {},
    private val now: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.UTC) },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(HomeUiState(modules = modules))
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            feed.collect { rows -> mutableState.update { it.copy(loaded = true, feed = HomeFeed.window(rows, now())) } }
        }
    }

    /** Settings can flip a module while Home is up. */
    fun setModules(modules: HomeModules?) = mutableState.update { it.copy(modules = modules) }

    fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.Refresh -> sync.kick()
            else -> navigate(event)
        }
    }
}
