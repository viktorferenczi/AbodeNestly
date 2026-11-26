package com.abodenestly.shopping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * The Shopping tab: the module's one entry point, mounted by the app shell only while the Shopping module is on. It owns
 * its sub-navigation until the NavHost lands with the tab shell (X7): an open list is a saved flag with a back handler,
 * like Members and Settings. Reads and writes go to the local database, so everything works offline.
 */
@Composable
fun ShoppingTab(
    householdId: UUID,
    database: DatabaseRegistry,
    sync: SyncEngine,
    isOnline: StateFlow<Boolean>,
    modifier: Modifier = Modifier,
) {
    // Keyed by household: the overview follows that household's database, and a switch starts a fresh model.
    val lists: ListsViewModel = viewModel(key = "shopping.lists.$householdId") {
        ListsViewModel(householdId, database, kick = { sync.kick() }, isOnline = isOnline)
    }
    var openListId by rememberSaveable(householdId) { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize().testTag("tab.shopping.content")) {
        val id = openListId
        if (id == null) {
            ListsScreen(lists, onOpen = { openListId = it })
        } else {
            BackHandler { openListId = null }
            val ui by lists.state.collectAsState()
            // A list just created may not be in the overview yet; the title fills in when it is.
            ListPlaceholderScreen(ui.overview.lists.firstOrNull { it.id == id }?.name.orEmpty(), onBack = { openListId = null })
        }
    }
}
