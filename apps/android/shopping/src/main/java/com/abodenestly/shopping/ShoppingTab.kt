package com.abodenestly.shopping

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
 * its sub-navigation until the NavHost lands with the tab shell (X7): an open list and the Stores screen are saved flags with back handlers,
 * like Members and Settings. Reads and writes go to the local database, so everything works offline.
 */
@Composable
fun ShoppingTab(
    householdId: UUID,
    database: DatabaseRegistry,
    sync: SyncEngine,
    isOnline: StateFlow<Boolean>,
    /** The signed-in user, recorded as `created_by` on the items they add. */
    userId: UUID?,
    modifier: Modifier = Modifier,
    /** SHOP-10: the barcode scanner (lookup, camera, storage); `null` hides the quick-add bar's scan button. */
    scanner: ScannerSetup? = null,
) {
    // Keyed by household: the overview follows that household's database, and a switch starts a fresh model.
    val lists: ListsViewModel = viewModel(key = "shopping.lists.$householdId") {
        ListsViewModel(householdId, database, kick = { sync.kick() }, isOnline = isOnline)
    }
    var openListId by rememberSaveable(householdId) { mutableStateOf<String?>(null) }
    // Stores sits above Lists and the open list (SHOP-05): back returns to where it was opened.
    var storesOpen by rememberSaveable(householdId) { mutableStateOf(false) }
    Box(modifier.fillMaxSize().testTag("tab.shopping.content")) {
        val id = openListId
        if (storesOpen) {
            BackHandler { storesOpen = false }
            val stores: StoresViewModel = viewModel(key = "shopping.stores.$householdId") {
                StoresViewModel(householdId, database, kick = { sync.kick() }, isOnline = isOnline)
            }
            StoresScreen(stores, onBack = { storesOpen = false })
        } else if (id == null) {
            ListsScreen(lists, onOpen = { openListId = it }, onOpenStores = { storesOpen = true })
        } else {
            BackHandler { openListId = null }
            // Keyed by household and list: each open list reads its own rows and keeps its own typed text.
            val detail: ListDetailViewModel = viewModel(key = "shopping.list.$householdId.$id") {
                ListDetailViewModel(householdId, id, userId, database, kick = { sync.kick() }, isOnline = isOnline)
            }
            ListDetailScreen(detail, onBack = { openListId = null }, onManageStores = { storesOpen = true }, scanner = scanner)
        }
    }
}
