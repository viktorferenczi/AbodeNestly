package com.abodenestly.core.switcher

import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Landing
import com.abodenestly.core.auth.LandingHousehold

/** DESIGN B3 states. Port of web `switcherState`. */
sealed interface SwitcherState {
    data object OneHousehold : SwitcherState
    data object Many : SwitcherState

    /** The target household's name, for "Switching to X…". */
    data class Switching(val name: String) : SwitcherState
}

/** Pure switch logic, shared by the view model and tests (iOS twin: `Switcher`). */
object Switcher {
    fun state(count: Int, switchingTo: String?): SwitcherState = when {
        switchingTo != null -> SwitcherState.Switching(switchingTo)
        count > 1 -> SwitcherState.Many
        else -> SwitcherState.OneHousehold
    }

    /** Same tab in the target household; a module tab whose module is off there falls back to its start tab. */
    fun targetTab(current: AppTab, target: LandingHousehold): AppTab {
        val enabled = when (current) {
            AppTab.Shopping -> target.moduleShopping
            AppTab.Pantry -> target.modulePantry
            AppTab.Maintenance -> target.moduleMaintenance
            AppTab.Home -> true
        }
        return if (enabled) current else Landing.startTab(target)
    }
}
