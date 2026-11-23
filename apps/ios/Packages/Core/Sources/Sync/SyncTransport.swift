/// Why a /sync call did not deliver. `network`: could not reach the server (keep everything, go offline).
/// `retryable`: 503 busy (deadlock), try once more shortly. `other`: stop this run.
public enum SyncFailure: Error, Sendable, Equatable {
    case network, retryable, other
}

/// The two /sync calls plus the token refresh the loop needs. `Sync` cannot depend on Core, so Core's `HttpApiService`
/// (and `FakeApiService`) conform. Web twin: the `syncPush` / `syncPull` / `refreshSession` slice of `ApiClient`.
public protocol SyncTransport: Sendable {
    func push(_ body: SyncPushBody) async -> Result<SyncPushResult, SyncFailure>
    func pull(householdId: String, since: Int, limit: Int) async -> Result<SyncPullPage, SyncFailure>
    /// Forces a token refresh; the loop calls it once before it gives up on an `rls_denied` rejection.
    func refreshSession() async
}
