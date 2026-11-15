package com.abodenestly.core.settings

import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Member
import com.abodenestly.core.api.ApiFailure

/** The three switchable modules, with the copy web and iOS show. */
enum class ModuleKey(val label: String, val summary: String) {
    Shopping("Shopping", "Shared shopping lists that sync across the household."),
    Pantry("Pantry", "What you have at home, with expiry dates and low-stock alerts."),
    Maintenance("Maintenance", "Recurring home tasks, warranties and service history."),
    ;

    /** The lower-case id used in test tags, like iOS `rawValue`. */
    val key: String get() = name.lowercase()
}

enum class SettingsMode { Owner, Member, Offline }

const val FORBIDDEN_MESSAGE = "Only owners can change household settings."
const val NETWORK_MESSAGE = "Couldn't save. Check your connection and try again."
const val SAVE_ERROR_MESSAGE = "Couldn't save. Try again."
const val LAST_OWNER_LEAVE_MESSAGE = "You're the last owner. Make someone else an owner before leaving."
const val LEAVE_ERROR_MESSAGE = "Couldn't leave the household. Try again."

fun saveErrorMessage(failure: ApiFailure): String = when (failure) {
    ApiFailure.Forbidden -> FORBIDDEN_MESSAGE
    ApiFailure.Network -> NETWORK_MESSAGE
    else -> SAVE_ERROR_MESSAGE
}

fun leaveErrorMessage(failure: ApiFailure): String =
    if (failure == ApiFailure.LastOwner) LAST_OWNER_LEAVE_MESSAGE else LEAVE_ERROR_MESSAGE

/** Offline wins over role: the whole screen is read-only without a connection. */
fun settingsMode(role: Member.Role?, online: Boolean): SettingsMode = when {
    !online -> SettingsMode.Offline
    role == Member.Role.owner -> SettingsMode.Owner
    else -> SettingsMode.Member
}

/** The household as it will look once the module is saved. */
fun withModule(household: Household, key: ModuleKey, on: Boolean): Household = when (key) {
    ModuleKey.Shopping -> household.copy(moduleShopping = on)
    ModuleKey.Pantry -> household.copy(modulePantry = on)
    ModuleKey.Maintenance -> household.copy(moduleMaintenance = on)
}

fun isOn(household: Household, key: ModuleKey): Boolean = when (key) {
    ModuleKey.Shopping -> household.moduleShopping
    ModuleKey.Pantry -> household.modulePantry
    ModuleKey.Maintenance -> household.moduleMaintenance
}

val CURRENCIES = listOf("AUD", "CAD", "CHF", "CZK", "DKK", "EUR", "GBP", "HUF", "JPY", "NOK", "PLN", "SEK", "USD")

/** The short list, plus the household's own value when it is not in it so the picker never lies. */
fun currencyOptions(current: String): List<String> =
    if (current in CURRENCIES) CURRENCIES else (CURRENCIES + current).sorted()
