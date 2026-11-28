import Foundation
import Sync

/// The tables the migrations have added to the `supabase_realtime` publication, by the module that owns them. Binding a
/// table that is not published makes the whole join fail (`CHANNEL_ERROR`), so a table joins this list in the same change
/// that publishes it (`docs/schema.sql` shows the end state). Every table here carries `household_id`.
/// Web twin: `REALTIME_TABLES` in `web/src/sync/realtime.ts`.
public enum RealtimeTables {
    public static let core = ["location", "invite", "activity", "attachment", "notification_pref"]
    public static let shopping = ["store", "shopping_list", "list_item", "store_item_order", "item_price"]
    /// Empty until a migration publishes a pantry table; it joins in that change.
    public static let pantry: [String] = []
    /// Empty until a migration publishes a maintenance table; it joins in that change.
    public static let maintenance: [String] = []

    /// Core always, a module's tables only while that module is on (SHOP-09 AC4, X7).
    public static func tables(for modules: HomeModules) -> [String] {
        core + (modules.shopping ? shopping : []) + (modules.pantry ? pantry : []) + (modules.maintenance ? maintenance : [])
    }
}

/// Ends one subscription. Cancelling twice is harmless.
public struct RealtimeSubscription: Sendable {
    private let onCancel: @Sendable () async -> Void

    public init(onCancel: @escaping @Sendable () async -> Void) { self.onCancel = onCancel }

    public func cancel() async { await onCancel() }
}

/// What the app needs from Realtime: tell me when a household table changed. The payload is never passed on (SPEC §6.4);
/// `onChange` starts a pull, the one code path that applies data. `SupabaseRealtimeService` is the live one,
/// `FakeRealtime` stands in for tests.
public protocol RealtimeSubscribing: Sendable {
    /// Returns at once; joining the channel happens in the background and a failure is not reported (the foreground,
    /// reconnect and 5-minute kicks keep running). `onChange` is also called once when the channel joins, to catch up.
    func subscribe(householdId: UUID, tables: [String], onChange: @escaping @Sendable () -> Void) async -> RealtimeSubscription
}

/// Previews and builds without a Realtime server.
public struct NoRealtime: RealtimeSubscribing {
    public init() {}

    public func subscribe(householdId: UUID, tables: [String], onChange: @escaping @Sendable () -> Void) async -> RealtimeSubscription {
        RealtimeSubscription {}
    }
}

/// SHOP-09: keeps the household subscription for as long as the calling task lives. A change calls `SyncEngine.kick()`,
/// which already joins a run in flight and makes it loop exactly once more, so a burst of events costs at most one extra run.
public enum HouseholdRealtime {
    public static func run(realtime: any RealtimeSubscribing, householdId: UUID, modules: HomeModules, sync: any SyncEngine) async {
        let subscription = await realtime.subscribe(householdId: householdId, tables: RealtimeTables.tables(for: modules)) {
            Task { await sync.kick() }
        }
        // Sleeps in long steps until the task is cancelled (household or module change, background, sign-out).
        while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)) }
        // Unstructured, so the cancelled task does not cut the unsubscribe short.
        await Task { await subscription.cancel() }.value
    }
}
