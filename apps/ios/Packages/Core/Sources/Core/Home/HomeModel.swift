import Contract
import Foundation
import Observation
import Sync

/// DESIGN B4. Reads the household's local `activity` rows (so it works offline, AC5) and asks the sync engine for
/// fresh ones on pull to refresh (AC4). Web twin: `web/src/core/HomeFeed.tsx`.
@Observable
@MainActor
public final class HomeModel {
    public private(set) var rows: [Activity] = []
    /// `false` until the first read of the local database, so the empty state does not flash.
    public private(set) var loaded = false
    /// Follows the household's flags; `nil` when they are unknown (offline before any household was read).
    public var modules: HomeModules?

    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let kick: @Sendable () async -> Void
    @ObservationIgnored private let now: @MainActor () -> Date

    /// - Parameters:
    ///   - modules: `nil` shows no Today cards and no all-off prompt.
    ///   - kick: `SyncEngine.kick()`; offline it is a no-op that keeps the cached rows.
    public init(
        householdId: UUID, modules: HomeModules?, database: HouseholdDatabase,
        kick: @escaping @Sendable () async -> Void, now: @escaping @MainActor () -> Date = { Date() }
    ) {
        self.householdId = householdId
        self.modules = modules
        self.database = database
        self.kick = kick
        self.now = now
    }

    public var feed: [Activity] { HomeFeed.window(rows, now: now()) }
    public var state: HomeState { HomeFeed.state(feed: feed, modules: modules) }

    /// Follows the local rows until the view goes away or the household's database closes.
    public func start() async {
        for await rows in await database.observeActivity(householdId: householdId) {
            self.rows = rows
            loaded = true
        }
        // A stream that ended at once (database not open): show what the person can see, i.e. nothing cached.
        loaded = true
    }

    /// Pull to refresh.
    public func refresh() async {
        await kick()
    }
}
