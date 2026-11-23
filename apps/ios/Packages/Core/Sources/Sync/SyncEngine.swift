/// Where the loop is, for the status chip. `offline` is only what the last run saw; the chip also reads the network monitor.
public enum SyncPhase: Sendable, Equatable {
    case idle, syncing, offline
}

/// What screens call to ask for fresh data, and what the sync sheet calls to recover failed changes.
/// `LiveSyncEngine` implements it; `FakeSync` (Core) stands in for tests and previews. Web twin: `web/src/sync/SyncEngine.ts`.
public protocol SyncEngine: Sendable {
    func kick() async
    /// Puts a failed change back at its old place in the outbox and syncs.
    func retry(seq: Int) async
    /// Drops a failed change and, unless another change targets the row, the local copy; the server's version is pulled back.
    func discard(seq: Int) async
    var currentPhase: SyncPhase { get async }
    /// The current phase first, then every change. Ends when the consumer stops iterating.
    func phases() async -> AsyncStream<SyncPhase>
}
