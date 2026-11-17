package com.abodenestly.core.home

import com.abodenestly.contract.models.Activity
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.LandingHousehold
import java.time.OffsetDateTime

/** The module flags the Home screen reads. */
data class HomeModules(val shopping: Boolean, val pantry: Boolean, val maintenance: Boolean) {
    val allOff: Boolean get() = !shopping && !pantry && !maintenance

    fun isOn(tab: AppTab): Boolean = when (tab) {
        AppTab.Shopping -> shopping
        AppTab.Pantry -> pantry
        AppTab.Maintenance -> maintenance
        AppTab.Home -> false
    }

    companion object {
        fun of(household: LandingHousehold) = HomeModules(household.moduleShopping, household.modulePantry, household.moduleMaintenance)
    }
}

enum class HomeState {
    /** Every module is off: the settings prompt shows and the feed still renders under it. */
    AllModulesOff,
    Empty,
    Populated,
}

/** DESIGN B4 / CORE-08. Twins: iOS `HomeFeed`, web `HomeFeed.ts`. */
object HomeFeed {
    const val DAYS = 30L
    const val LIMIT = 20

    fun windowStart(now: OffsetDateTime): OffsetDateTime = now.minusDays(DAYS)

    /** The newest 20 live rows of the last 30 days. Same-instant rows keep a stable order (id, descending). */
    fun window(rows: List<Activity>, now: OffsetDateTime): List<Activity> {
        val from = windowStart(now)
        return rows
            .filter { it.deletedAt == null && !it.createdAt.isBefore(from) }
            .sortedWith(compareByDescending<Activity> { it.createdAt.toInstant() }.thenByDescending { it.id.toString() })
            .take(LIMIT)
    }

    /** [feed] is the windowed list. `modules == null` (flags unknown) never shows the all-off prompt. */
    fun state(feed: List<Activity>, modules: HomeModules?): HomeState = when {
        modules?.allOff == true -> HomeState.AllModulesOff
        feed.isEmpty() -> HomeState.Empty
        else -> HomeState.Populated
    }
}
