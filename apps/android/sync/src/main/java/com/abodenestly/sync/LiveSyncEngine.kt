package com.abodenestly.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The push/pull loop (SPEC §6.4); port of iOS `LiveSyncEngine`. One pass pushes the outbox of the open household in
 * insertion order, then pulls until `has_more` is false. Passes never overlap: a kick that arrives mid-pass makes
 * exactly one more pass, which reads whichever household is open by then. Offline it does nothing but report
 * [SyncPhase.Offline], so the queue and the cached rows stay (SPEC §6.5). A Realtime event (SHOP-09) is one more kick.
 */
class LiveSyncEngine(
    private val transport: SyncTransport,
    private val database: DatabaseRegistry,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Instant = { Instant.now() },
) : SyncEngine {
    private val mutablePhase = MutableStateFlow(SyncPhase.Idle)
    override val phase: StateFlow<SyncPhase> = mutablePhase.asStateFlow()

    private val running = Mutex()
    private val pending = AtomicBoolean(false)

    // Waiters queue on the lock; the first one's loop takes every request made so far, so the rest add no pass.
    override fun kick() {
        scope.launch { run() }
    }

    /** Returns when the pass that covers this request, and any queued behind it, is done (a background worker must not return earlier). */
    override suspend fun run() {
        pending.set(true)
        running.withLock {
            while (pending.getAndSet(false)) once()
        }
    }

    override suspend fun retry(seq: Long) {
        val household = database.current.value ?: return
        try { database.retryFailed(household, seq) } catch (_: DatabaseNotOpenException) { return }
        kick()
    }

    override suspend fun discard(seq: Long) {
        val household = database.current.value ?: return
        try { database.discardFailed(household, seq) } catch (_: DatabaseNotOpenException) { return }
        kick()
    }

    private suspend fun once() {
        val household = database.current.value ?: return
        if (!isOnline()) {
            mutablePhase.value = SyncPhase.Offline
            return
        }
        mutablePhase.value = SyncPhase.Syncing
        var next = SyncPhase.Idle
        try {
            next = pass(household)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DatabaseNotOpenException) {
            // The person switched households mid-pass; the follow-up pass reads the new one.
        } catch (_: Exception) {
            // A local database error must not crash the app from a background kick; the next pass tries again.
        } finally {
            mutablePhase.value = next
        }
    }

    private suspend fun pass(household: UUID): SyncPhase {
        var refreshed = false
        while (true) {
            val batch = database.pendingBatch(household, BATCH)
            if (batch.isEmpty()) break
            val sent = batch.map { it.id }.toSet()
            val body = SyncPushBody(household.toString(), batch.map(::toMutation))
            val reply = when (val first = transport.push(body)) {
                is SyncResult.Failure -> if (first.failure == SyncFailure.Retryable) {
                    pause(RETRY_PAUSE_MS)
                    transport.push(body)
                } else first
                is SyncResult.Success -> first
            }
            val result = when (reply) {
                is SyncResult.Failure -> return if (reply.failure == SyncFailure.Network) SyncPhase.Offline else SyncPhase.Idle
                is SyncResult.Success -> reply.value
            }
            val applied = result.applied.filter { it in sent }
            val rejected = result.rejected.filter { it.id in sent }
            // A first `rls_denied` may only be a stale token: refresh once and send those rows again.
            val stale = rejected.filter { SyncRejectionReason.parse(it.reason) == SyncRejectionReason.RlsDenied }
            val retryStale = stale.isNotEmpty() && !refreshed
            database.settlePush(household, applied, if (retryStale) rejected - stale.toSet() else rejected, now())
            if (retryStale) {
                refreshed = true
                transport.refreshSession()
            } else if (applied.isEmpty() && rejected.isEmpty()) {
                break // the server answered for none of the rows: stop rather than spin
            }
        }
        var since = database.cursorOf(household)
        while (true) {
            val page = when (val reply = transport.pull(household.toString(), since, PAGE)) {
                is SyncResult.Failure -> return if (reply.failure == SyncFailure.Network) SyncPhase.Offline else SyncPhase.Idle
                is SyncResult.Success -> reply.value
            }
            if (!database.applyPull(household, page)) return SyncPhase.Idle
            if (!page.hasMore || page.cursor <= since) break
            since = page.cursor
        }
        return SyncPhase.Idle
    }

    private fun toMutation(row: OutboxEntity) = SyncMutation(
        id = row.id, table = row.tableName, op = row.op, rowId = row.rowId,
        data = if (row.op == "delete") null else runCatching { SyncJson.parseToJsonElement(row.data) as? JsonObject }.getOrNull(),
        clientTs = row.clientTs,
    )

    private companion object {
        const val BATCH = 500
        const val PAGE = 500
        const val RETRY_PAUSE_MS = 1_000L
    }
}
