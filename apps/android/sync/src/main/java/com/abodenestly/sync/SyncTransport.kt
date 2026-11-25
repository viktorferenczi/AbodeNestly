package com.abodenestly.sync

/** Why a /sync call did not produce a result. */
sealed interface SyncFailure {
    /** The server could not be reached; the outbox is kept and nothing is pulled. */
    data object Network : SyncFailure

    /** 503 `retryable` (a deadlock on the server): the same push may be sent again. */
    data object Retryable : SyncFailure

    /** Anything else (a 4xx/5xx that retrying will not fix, an undecodable body). */
    data object Other : SyncFailure
}

sealed interface SyncResult<out T> {
    data class Success<T>(val value: T) : SyncResult<T>
    data class Failure(val failure: SyncFailure) : SyncResult<Nothing>
}

/**
 * The two /sync calls, as the engine needs them. `:sync` cannot depend on `:core`, so the HTTP implementation lives
 * there (`HttpApiService`) and tests use a recording fake. Twin: iOS `SyncTransport`.
 */
interface SyncTransport {
    suspend fun push(body: SyncPushBody): SyncResult<SyncPushResult>
    suspend fun pull(householdId: String, since: Long, limit: Int): SyncResult<SyncPullPage>

    /** Refreshes the access token; used once after an `rls_denied` in case it was a stale session. */
    suspend fun refreshSession()
}
