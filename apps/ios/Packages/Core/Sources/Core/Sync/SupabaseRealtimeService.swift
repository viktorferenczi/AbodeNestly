import Foundation
import Realtime
import Shared

/// supabase-swift Realtime V2 (SPEC §3: the SDK is limited to Auth and Realtime). One channel `household:<id>` with a
/// `postgres_changes` stream per published table, filtered to the household. Each event calls `onChange` and the payload
/// is never read. A dropped or failed channel is removed and replaced. Web twin: `subscribeHouseholdRealtime` in `web/src/sync/realtime.ts`.
public final class SupabaseRealtimeService: RealtimeSubscribing {
    private let client: RealtimeClientV2
    /// How long to wait before trying to join again after a failed join (offline, server down).
    private static let rejoinDelay: Duration = .seconds(15)
    private let turn = ChannelTurn()

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
        let client = client
        let turn = turn
        // One supervisor per subscription: join, stay while joined, and when the join fails or the channel leaves
        // `.subscribed` (socket drop, `phx_close`), remove that channel and open a new one after a pause. A removed
        // channel is never joined again. A failure is not an error here (AC3).
        let supervisor = Task {
            // The client hands back the channel for a topic until `removeChannel` has finished, so wait until the
            // previous subscription (household switch, background then foreground) has removed its channel.
            await turn.acquire()
            while !Task.isCancelled {
                let channel = client.channel("household:\(id)")
                var listeners: [Task<Void, Never>] = []
                // Bindings must exist before the join, or the server never learns about them.
                for table in tables {
                    let changes = channel.postgresChange(AnyAction.self, schema: "public", table: table, filter: "household_id=eq.\(id)")
                    listeners.append(Task { for await _ in changes { onChange() } })
                }
                do {
                    try await channel.subscribeWithError()
                    // Catch up on whatever happened while the channel was not joined.
                    onChange()
                    for await status in channel.statusChange where status == .unsubscribing || status == .unsubscribed { break }
                } catch {
                    // Offline, server down, or cancelled: fall through to teardown.
                }
                listeners.forEach { $0.cancel() }
                // Unstructured, so a cancelled supervisor does not cut the removal short.
                await Task { await client.removeChannel(channel) }.value
                if Task.isCancelled { break }
                try? await Task.sleep(for: Self.rejoinDelay)
            }
            await turn.release()
        }
        return RealtimeSubscription {
            supervisor.cancel()
            await supervisor.value
        }
    }
}

/// Lets one subscription at a time own a channel on the shared client. The app keeps a single household subscription,
/// so a waiter only waits for the previous one to finish tearing down.
private actor ChannelTurn {
    private var held = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func acquire() async {
        if !held {
            held = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if waiters.isEmpty { held = false } else { waiters.removeFirst().resume() }
    }
}
