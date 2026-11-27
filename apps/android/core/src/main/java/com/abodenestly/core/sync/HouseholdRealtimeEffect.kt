package com.abodenestly.core.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.abodenestly.core.home.HomeModules
import com.abodenestly.sync.RealtimeSubscribing
import com.abodenestly.sync.RealtimeTables
import com.abodenestly.sync.runHouseholdRealtime
import java.util.UUID

/**
 * SHOP-09: while the signed-in shell is on screen, the app is in the foreground and the household and its flags are
 * known, keep the household's Realtime subscription and call [kick] for every change (the payload is never read).
 * A household switch, a module toggle, the app going to the background or sign-out (the shell leaves) ends the
 * subscription; coming back subscribes again, with one catch-up pull. Twin of the Realtime task in iOS `RootView`.
 *
 * [modules] comes from where the shell already reads the flags (the landing read, else the cached households list),
 * so this adds no network call.
 */
@Composable
fun HouseholdRealtimeEffect(
    realtime: RealtimeSubscribing,
    householdId: UUID?,
    modules: HomeModules?,
    foreground: Boolean,
    kick: () -> Unit,
) {
    val latestKick by rememberUpdatedState(kick)
    LaunchedEffect(realtime, householdId, modules, foreground) {
        if (!foreground || householdId == null || modules == null) return@LaunchedEffect
        val tables = RealtimeTables.tables(modules.shopping, modules.pantry, modules.maintenance)
        runHouseholdRealtime(realtime, householdId.toString(), tables) { latestKick() }
    }
}
