package com.abodenestly.sync

import kotlinx.coroutines.flow.StateFlow

enum class SyncPhase { Idle, Syncing, Offline }

/**
 * What screens call to ask for fresh data and to act on failed changes. [LiveSyncEngine] is the implementation;
 * tests use a fake. Twins: iOS `SyncEngine`, web `SyncEngine.ts`.
 */
interface SyncEngine {
    val phase: StateFlow<SyncPhase>

    /** Starts a push-then-pull in the background; kicks during a run coalesce into one more pass. */
    fun kick()

    /** One awaitable pass (tests, the background worker). Returns when the pass, and any pass queued behind it, is done. */
    suspend fun run()

    /** Puts a failed change back in the queue at its old place and syncs. */
    suspend fun retry(seq: Long)

    /** Drops a failed change and the local row it created, then re-pulls the server's version. */
    suspend fun discard(seq: Long)
}

/** The application implements this so [SyncWorker] can reach the engine without a dependency on the app module. */
interface SyncEngineProvider {
    val syncEngine: SyncEngine

    /** Opens the household a cold background start should sync (the last one used); a no-op when one is open. */
    suspend fun prepareBackgroundSync() {}
}
