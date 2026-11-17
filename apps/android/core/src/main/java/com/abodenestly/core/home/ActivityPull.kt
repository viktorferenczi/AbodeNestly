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

/**
 * Interim [SyncEngine] (CORE-08, until SHOP-08): pulls only `activity` of the open household over Supabase REST into
 * the local database, the way `ProfileSync` drains its queue. A kick while a pull runs is dropped (the running pull
 * is the fresh one); offline it does nothing, so the cached rows stay (SPEC §6.5).
 */
class ActivityPull(
    private val api: ApiService,
    private val database: DatabaseRegistry,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.UTC) },
) : SyncEngine {
    private val running = Mutex()

    override fun kick() {
        scope.launch { pull() }
    }

    /** One pull; returns when it is done. Exposed for tests, which await it instead of racing [kick]. */
    suspend fun pull() {
        if (!isOnline()) return
        if (!running.tryLock()) return
        try {
            val household = database.current.value ?: return
            val result = api.fetchActivity(household, HomeFeed.windowStart(now()), HomeFeed.LIMIT)
            // `upsertActivity` drops the rows when the person switched households while the read was in flight.
            if (result is ApiResult.Success && result.value.isNotEmpty()) database.upsertActivity(household, result.value)
        } finally {
            running.unlock()
        }
    }
}
