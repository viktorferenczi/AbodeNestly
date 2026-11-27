import Foundation
import Realtime
import Shared

/// supabase-swift Realtime V2 (SPEC §3: the SDK is limited to Auth and Realtime). One channel `household:<id>` with a
/// `postgres_changes` stream per published table, filtered to the household. Each event calls `onChange` and the payload
/// is never read. Web twin: `subscribeHouseholdRealtime` in `web/src/sync/realtime.ts`.
public final class SupabaseRealtimeService: RealtimeSubscribing {
    private let client: RealtimeClientV2
    /// How long to wait before trying to join again after a failed join (offline, server down).
    private static let rejoinDelay: Duration = .seconds(15)

    public init(config: AppConfig, auth: any AuthService) {
        client = RealtimeClientV2(
            url: config.supabaseURL.appendingPathComponent("realtime/v1"),
            options: RealtimeClientOptions(
                headers: ["apikey": config.supabaseAnonKey],
                // Read on every (re)connect, so a refreshed token is picked up; `nil` while signed out.
                accessToken: { await auth.accessToken(refresh: false) }
            )
        )
    }

    public func subscribe(householdId: UUID, tables: [String], onChange: @escaping @Sendable () -> Void) async -> RealtimeSubscription {
        let id = householdId.uuidString.lowercased()
        let channel = client.channel("household:\(id)")
        var tasks: [Task<Void, Never>] = []
        // Bindings must exist before the join, or the server never learns about them.
        for table in tables {
            let changes = channel.postgresChange(AnyAction.self, schema: "public", table: table, filter: "household_id=eq.\(id)")
            tasks.append(Task { for await _ in changes { onChange() } })
        }
        // Catch up on whatever happened while the channel was not joined. Other statuses are ignored on purpose.
        let statuses = channel.statusChange
        tasks.append(Task { for await status in statuses where status == .subscribed { onChange() } })
        // A failed join is not an error here (AC3): keep trying quietly until cancelled.
        tasks.append(Task {
            while !Task.isCancelled {
                do {
                    try await channel.subscribeWithError()
                    return
                } catch {
                    try? await Task.sleep(for: Self.rejoinDelay)
                }
            }
        })
        let started = tasks
        let client = client
        return RealtimeSubscription {
            started.forEach { $0.cancel() }
            await client.removeChannel(channel)
        }
    }
}
