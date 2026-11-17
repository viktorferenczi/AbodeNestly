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
    private var inflight: Task<Void, Never>?

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
        if let inflight {
            await inflight.value
            return
        }
        let task = Task { await pull() }
        inflight = task
        await task.value
        inflight = nil
    }

    private func pull() async {
        guard let householdId = await database.current else { return }
        let since = HomeFeed.windowStart(now: now())
        guard case .success(let rows) = await api.fetchActivity(householdId: householdId, since: since, limit: HomeFeed.limit),
            !rows.isEmpty
        else { return }
        // The household may have changed while the read was in flight; `upsertActivity` drops foreign rows.
        try? await database.upsertActivity(rows)
    }
}
