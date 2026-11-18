package com.abodenestly.core.home

import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interim [SyncEngine] (CORE-08, until SHOP-08): pulls only `activity` of the open household over Supabase REST into
 * the local database, the way `ProfileSync` drains its queue. Kicks never overlap: one that arrives mid-read marks
 * a follow-up pass, which reads the household that is open when the first read ends (the first one sampled it
 * earlier, and `upsertActivity` drops its rows after a switch). Offline it does nothing, so the cached rows stay
 * (SPEC §6.5).
 */
class ActivityPull(
    private val api: ApiService,
    private val database: DatabaseRegistry,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.UTC) },
) : SyncEngine {
    private val running = Mutex()
    private val pending = AtomicBoolean(false)

    override fun kick() {
        scope.launch { pull() }
    }

    /**
     * One pull; returns when it is done, or at once when another pull is running and will take this request as its
     * follow-up pass. Exposed for tests, which await it instead of racing [kick].
     */
    suspend fun pull() {
        if (!isOnline()) return
        pending.set(true)
        // The outer check catches a request that landed between the holder's last look and its unlock.
        while (pending.get()) {
            if (!running.tryLock()) return
            try {
                while (pending.getAndSet(false)) once()
            } finally {
                running.unlock()
            }
        }
    }

    private suspend fun once() {
        val household = database.current.value ?: return
        // Tombstones come too (SPEC §6.3), so a sentence soft-deleted on the server stops showing here, and an
        // empty page is still a successful read. `upsertActivity` drops the rows when the person switched
        // households while the read was in flight.
        val result = api.fetchActivity(household, HomeFeed.windowStart(now()), HomeFeed.PULL_LIMIT)
        if (result is ApiResult.Success) database.upsertActivity(household, result.value)
    }
}
