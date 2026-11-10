package com.abodenestly.core.auth

import java.time.Instant
import java.util.UUID

enum class AppTab(val key: String) {
    Shopping("shopping"), Pantry("pantry"), Maintenance("maintenance"), Home("home"),
}

/** The household columns the landing rule reads. */
data class LandingHousehold(
    val id: UUID,
    val moduleShopping: Boolean,
    val modulePantry: Boolean,
    val moduleMaintenance: Boolean,
)

/** An active (not left, household not deleted) membership with its household flags. */
data class Membership(val householdId: UUID, val joinedAt: Instant, val household: LandingHousehold)

sealed interface Destination {
    data object Welcome : Destination
    data class Household(val id: UUID, val tab: AppTab) : Destination

    /** Signed in, but memberships could not be read while offline (SPEC §6.5): enter the shell at Home. */
    data object Home : Destination
}

/** Port of web `landing.ts` and iOS `Landing` so every client lands the same way. */
object Landing {
    /** First enabled module in the order shopping → pantry → maintenance; Home when all are off. */
    fun startTab(household: LandingHousehold): AppTab = when {
        household.moduleShopping -> AppTab.Shopping
        household.modulePantry -> AppTab.Pantry
        household.moduleMaintenance -> AppTab.Maintenance
        else -> AppTab.Home
    }

    /** The last used household if still active, else the earliest joined. */
    fun pickHousehold(memberships: List<Membership>, lastHouseholdId: UUID?): Membership? =
        memberships.firstOrNull { it.householdId == lastHouseholdId } ?: memberships.minByOrNull { it.joinedAt }

    fun destination(memberships: List<Membership>, lastHouseholdId: UUID?): Destination {
        val chosen = pickHousehold(memberships, lastHouseholdId) ?: return Destination.Welcome
        return Destination.Household(chosen.householdId, startTab(chosen.household))
    }
}
