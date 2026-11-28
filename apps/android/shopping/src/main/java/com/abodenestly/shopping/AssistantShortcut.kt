package com.abodenestly.shopping

import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListRecord
import com.abodenestly.sync.shoppingListRecords
import com.abodenestly.sync.shoppingModuleEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** What the assistant shortcut did, and so what it says back. Twin: iOS `AssistantResult`. */
sealed class AssistantResult {
    data class Added(val name: String, val list: String) : AssistantResult()
    data object NoItem : AssistantResult()
    data object ShoppingOff : AssistantResult()
    data object NoHousehold : AssistantResult()
    data object NoList : AssistantResult()
    data object Failed : AssistantResult()

    /** The reply the Toast shows (SHOP-15). Pure, so the wording is table-tested; same texts as iOS. */
    val reply: String
        get() = when (this) {
            is Added -> "Added $name to $list."
            NoItem -> "I didn't catch an item. Nothing was added."
            ShoppingOff -> "Shopping is turned off for this household. Nothing was added."
            NoHousehold -> "Open AbodeNestly and sign in first."
            NoList -> "There's no shopping list to add to."
            Failed -> "Couldn't add that. Try again in the app."
        }
}

/**
 * SHOP-15 logic, kept apart from the activity so it is testable without the system. The write is the one Quick add and
 * the voice sheet use (`QuickAdd.row` and `addItem`): the local row and its outbox entry in one transaction. The caller
 * kicks sync afterwards. Twin: iOS `AssistantShortcut`.
 */
object AssistantShortcut {
    /** The default list's name (CORE-02). */
    const val DEFAULT_LIST_NAME = "Groceries"

    /**
     * Adds what the person said to the default list of [householdId]. [householdId] is null when nobody is signed in or
     * no household was opened yet. Nothing is written for any result but [AssistantResult.Added].
     */
    suspend fun add(
        text: String,
        householdId: UUID?,
        userId: UUID?,
        registry: DatabaseRegistry,
        now: Instant = Instant.now(),
        newId: () -> String = { UUID.randomUUID().toString() },
    ): AssistantResult {
        val spoken = text.trim().takeIf { it.isNotEmpty() }?.let { VoiceAdd.parse(it) } ?: return AssistantResult.NoItem
        if (householdId == null) return AssistantResult.NoHousehold
        // The write finishes even if the activity goes away meanwhile.
        return withContext(NonCancellable) {
            try {
                registry.open(householdId)
                // A household row that has not been pulled yet is trusted: the server rejects a write with `module_disabled`.
                if (registry.shoppingModuleEnabled(householdId) == false) return@withContext AssistantResult.ShoppingOff
                val list = pickList(registry.shoppingListRecords(householdId)) ?: return@withContext AssistantResult.NoList
                addItem(
                    registry, householdId,
                    QuickAdd.row(newId(), list.id, spoken.name, userId, now, quantity = spoken.quantity, unit = spoken.unit),
                    now,
                )
                AssistantResult.Added(spoken.name, list.name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AssistantResult.Failed
            }
        }
    }

    /** "Groceries" (any case) if the household has one, else its oldest list; archived lists and templates never. */
    fun pickList(lists: List<ListRecord>): ListRecord? {
        val active = lists.filter { it.archivedAt == null && !it.isTemplate }
            .sortedWith(compareBy<ListRecord> { it.createdAt ?: "" }.thenBy { it.id })
        val groceries = active.firstOrNull { it.name.trim().lowercase(Locale.ROOT) == DEFAULT_LIST_NAME.lowercase(Locale.ROOT) }
        return groceries ?: active.firstOrNull()
    }
}
