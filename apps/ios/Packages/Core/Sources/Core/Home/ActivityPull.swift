import Contract
import Foundation
import Sync

/// Interim `SyncEngine` (CORE-08, until SHOP-08): pulls only `activity` of the open household over Supabase REST
/// into the local database, the way ProfileSync drains its queue. Concurrent kicks share one pull; offline it does
/// nothing, so the cached rows stay (SPEC §6.5).
public actor ActivityPull: SyncEngine {
    private let api: any ApiService
    private let database: HouseholdDatabase
    private let isOnline: @MainActor @Sendable () -> Bool
    private let now: @Sendable () -> Date
    /// The pull in flight; it resolves to the household it read, or `nil` when none was open.
    private var inflight: Task<UUID?, Never>?

    public init(
        api: any ApiService, database: HouseholdDatabase,
        isOnline: @escaping @MainActor @Sendable () -> Bool, now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.api = api
        self.database = database
        self.isOnline = isOnline
        self.now = now
    }

    public func kick() async {
        // Ask first: the check below must not suspend, or two kicks could both see no pull in flight.
        guard await isOnline() else { return }
        let fetched: UUID?
        if let inflight {
            fetched = await inflight.value
        } else {
            let task = Task { () -> UUID? in
                let fetched = await pull()
                // Cleared before any waiter resumes, so a waiter never joins a finished pull.
                inflight = nil
                return fetched
            }
            inflight = task
            fetched = await task.value
        }
        // A switch or landing during the read leaves the household that is open now unread (its rows were
        // dropped as foreign): pull again for it. No household open (nil == nil) is nothing to pull.
        if await database.current != fetched { await kick() }
    }

    /// Returns the household it read.
    private func pull() async -> UUID? {
        guard let householdId = await database.current else { return nil }
        let since = HomeFeed.windowStart(now: now())
        // Tombstones come too (SPEC §6.3), so a sentence soft-deleted on the server stops showing here, and an
        // empty page is still a successful read. The household may change while the read is in flight;
        // `upsertActivity` drops foreign rows.
        if case .success(let rows) = await api.fetchActivity(householdId: householdId, since: since, limit: HomeFeed.pullLimit) {
            try? await database.upsertActivity(rows)
        }
        return householdId
    }
}
