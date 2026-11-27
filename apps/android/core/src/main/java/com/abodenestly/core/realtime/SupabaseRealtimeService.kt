package com.abodenestly.core.realtime

import com.abodenestly.sync.RealtimeSubscribing
import com.abodenestly.sync.RealtimeSubscription
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * supabase-kt Realtime (SPEC §3: the SDK is limited to Auth and Realtime). One channel `household:<id>` with a
 * `postgres_changes` flow per published table, filtered to the household. Each event calls `onChange` and the payload is
 * never read. Twin of iOS `SupabaseRealtimeService`; web twin: `subscribeHouseholdRealtime` in `web/src/sync/realtime.ts`.
 *
 * The SDK rejoins the same channel after a socket reconnect, so a status that goes back to `SUBSCRIBING` is waited out
 * (and every return to `SUBSCRIBED` catches up with one `onChange`). A channel is replaced only when it has been
 * `SUBSCRIBING` for [joinTimeoutMs] (the join failed: offline, server down, an unpublished table) or was removed
 * (`phx_close`). Everything is swallowed: a failure is not an error here (AC3), the foreground, reconnect and
 * 5-minute pulls keep the list current.
 */
class SupabaseRealtimeService(
    private val client: SupabaseClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val joinTimeoutMs: Long = JOIN_TIMEOUT_MS,
    private val rejoinDelayMs: Long = REJOIN_DELAY_MS,
) : RealtimeSubscribing {
    /**
     * One subscription at a time owns the household topic on the shared client. `channel(topic)` hands back the old
     * instance until `removeChannel` has run, so a new subscription (household switch, background then foreground)
     * waits for the previous one to finish tearing down.
     */
    private val turn = Mutex()

    override suspend fun subscribe(householdId: String, tables: List<String>, onChange: () -> Unit): RealtimeSubscription {
        val supervisor = scope.launch { turn.withLock { supervise(householdId, tables, onChange) } }
        return RealtimeSubscription { supervisor.cancelAndJoin() }
    }

    private suspend fun supervise(id: String, tables: List<String>, onChange: () -> Unit) = coroutineScope {
        while (isActive) {
            var channel: RealtimeChannel? = null
            val listeners = mutableListOf<Job>()
            try {
                val joined = client.realtime.channel("household:$id")
                channel = joined
                // Every binding must exist before the join, or the server never learns about it.
                for (name in tables) {
                    val changes = joined.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = name
                        filter("household_id", FilterOperator.EQ, id)
                    }
                    // Undispatched, so the collector is registered before the join is sent.
                    listeners += launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            changes.collect { onChange() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A listener must not take the supervisor down.
                        }
                    }
                }
                joined.subscribe()
                stayJoined(joined, onChange)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Could not join (offline, server down): replaced below after a pause.
            } finally {
                // Unstructured, so a cancelled supervisor does not cut the removal short, and the turn is released only after it.
                withContext(NonCancellable) {
                    listeners.forEach { it.cancelAndJoin() }
                    channel?.let { runCatching { client.realtime.removeChannel(it) } }
                }
            }
            delay(rejoinDelayMs)
        }
    }

    private enum class Health { Joined, Stuck, Gone }

    /** Returns when [channel] has to be replaced: the join did not complete in time, or the channel was removed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun stayJoined(channel: RealtimeChannel, onChange: () -> Unit) {
        channel.status.mapLatest { status ->
            when (status) {
                // The first join and every SDK rejoin: catch up on what happened while the channel was not joined.
                RealtimeChannel.Status.SUBSCRIBED -> { onChange(); Health.Joined }
                // Waiting for the join or the SDK's rejoin; the next status cancels this wait.
                RealtimeChannel.Status.SUBSCRIBING -> { delay(joinTimeoutMs); Health.Stuck }
                RealtimeChannel.Status.UNSUBSCRIBING, RealtimeChannel.Status.UNSUBSCRIBED -> Health.Gone
            }
        }.first { it != Health.Joined }
    }

    private companion object {
        /** How long a join (or the SDK's rejoin) may take before the channel counts as failed. */
        const val JOIN_TIMEOUT_MS = 10_000L

        /** How long to wait before trying again after a failed join (offline, server down). */
        const val REJOIN_DELAY_MS = 15_000L
    }
}
