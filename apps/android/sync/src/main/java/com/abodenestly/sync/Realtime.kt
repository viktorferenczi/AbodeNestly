package com.abodenestly.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tables the migrations have added to the `supabase_realtime` publication, by the module that owns them. Binding a
 * table that is not published makes the whole join fail (`CHANNEL_ERROR`), so a table joins this list in the same
 * change that publishes it (`docs/schema.sql` shows the end state). Every table here carries `household_id`.
 * Twins: iOS `RealtimeTables`, web `REALTIME_TABLES`.
 */
object RealtimeTables {
    val core = listOf("location", "invite", "activity", "attachment", "notification_pref")
    val shopping = listOf("store", "shopping_list", "list_item", "store_item_order")

    /** Empty until a migration publishes a pantry table; it joins in that change. */
    val pantry: List<String> = emptyList()

    /** Empty until a migration publishes a maintenance table; it joins in that change. */
    val maintenance: List<String> = emptyList()

    /** Core always, a module's tables only while that module is on (SHOP-09 AC4, X7). `:sync` cannot see `HomeModules`, hence the flags. */
    fun tables(shopping: Boolean, pantry: Boolean, maintenance: Boolean): List<String> =
        core + (if (shopping) this.shopping else emptyList()) +
            (if (pantry) this.pantry else emptyList()) +
            (if (maintenance) this.maintenance else emptyList())
}

/** Ends one subscription. Cancelling twice is harmless. */
fun interface RealtimeSubscription {
    suspend fun cancel()
}

/**
 * What the app needs from Realtime: tell me when a household table changed. The payload is never passed on (SPEC §6.4);
 * `onChange` starts a pull, the one code path that applies data. `SupabaseRealtimeService` (`:core`) is the live one,
 * [FakeRealtime] stands in for tests and [NoRealtime] for builds without a Realtime server.
 */
interface RealtimeSubscribing {
    /**
     * Returns at once; joining the channel happens in the background and a failure is not reported (the foreground,
     * reconnect and 5-minute kicks keep running). [onChange] is also called once whenever the channel has joined, to
     * catch up on what happened while it was not.
     */
    suspend fun subscribe(householdId: String, tables: List<String>, onChange: () -> Unit): RealtimeSubscription
}

/** Previews and builds without a Realtime server. */
object NoRealtime : RealtimeSubscribing {
    override suspend fun subscribe(householdId: String, tables: List<String>, onChange: () -> Unit) = RealtimeSubscription { }
}

/** What a channel reports, reduced to what matters here. */
enum class RealtimeStatus { Subscribed, Error, Closed }

/**
 * Test double: records what was asked for, counts live subscriptions and lets a test fire an event or a status. Like the
 * live service it calls `onChange` for an event and once for `Subscribed`, and never for an error.
 */
class FakeRealtime : RealtimeSubscribing {
    data class Call(val householdId: String, val tables: List<String>)

    private class Live(val call: Call, val onChange: () -> Unit)

    val calls = CopyOnWriteArrayList<Call>()
    private val live = CopyOnWriteArrayList<Live>()

    /** Subscriptions that have not been cancelled. */
    val active: Int get() = live.size

    /** The tables of every live subscription, in the order they were made. */
    val activeCalls: List<Call> get() = live.map { it.call }

    override suspend fun subscribe(householdId: String, tables: List<String>, onChange: () -> Unit): RealtimeSubscription {
        val entry = Live(Call(householdId, tables), onChange)
        calls += entry.call
        live += entry
        return RealtimeSubscription { live.remove(entry) }
    }

    /** A change by another member. [payload] is whatever the server sent; nothing reads it. */
    @Suppress("UNUSED_PARAMETER")
    fun fireEvent(payload: String = "{}") {
        live.forEach { it.onChange() }
    }

    fun fireStatus(status: RealtimeStatus) {
        if (status == RealtimeStatus.Subscribed) live.forEach { it.onChange() }
    }
}

/**
 * SHOP-09: keeps the household subscription for as long as the calling coroutine lives. A change calls [kick]
 * (`SyncEngine.kick`), which already joins a pass in flight and makes it loop exactly once more, so a burst of events
 * costs at most one extra pass. Never returns normally: cancel the coroutine on a household or module change, when the
 * app goes to the background or on sign-out. The unsubscribe is not cut short by that cancellation.
 */
suspend fun runHouseholdRealtime(realtime: RealtimeSubscribing, householdId: String, tables: List<String>, kick: () -> Unit): Nothing {
    val subscription = realtime.subscribe(householdId, tables, kick)
    try {
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { subscription.cancel() }
    }
}
