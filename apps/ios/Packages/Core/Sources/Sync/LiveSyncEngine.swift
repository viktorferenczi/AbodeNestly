import Foundation

/// The push/pull loop (SPEC §6.4). Pushes the outbox of the open household, then pulls everything after the cursor.
/// Web twin: `createSyncEngine` in `web/src/sync/SyncEngine.ts`; keep the two in step.
public actor LiveSyncEngine: SyncEngine {
    private enum Step { case ok, network, stop }

    private static let pageSize = 500
    private static let retryableDelay: Duration = .seconds(1)

    private let transport: any SyncTransport
    private let database: HouseholdDatabase
    private let isOnline: @MainActor @Sendable () -> Bool
    private let sleep: @Sendable (Duration) async -> Void
    private let now: @Sendable () -> Date

    private var phase: SyncPhase = .idle
    private var listeners: [UUID: AsyncStream<SyncPhase>.Continuation] = [:]
    private var inflight: Task<Void, Never>?
    private var again = false
    /// Mutation ids that already had their one retry after a token refresh.
    private var refreshed = Set<String>()
    /// Called when a run ends, e.g. to schedule the next background refresh.
    private let onRunFinished: @Sendable () -> Void

    public init(
        transport: any SyncTransport, database: HouseholdDatabase,
        isOnline: @escaping @MainActor @Sendable () -> Bool,
        sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) },
        now: @escaping @Sendable () -> Date = { Date() },
        onRunFinished: @escaping @Sendable () -> Void = {}
    ) {
        self.transport = transport
        self.database = database
        self.isOnline = isOnline
        self.sleep = sleep
        self.now = now
        self.onRunFinished = onRunFinished
    }

    public var currentPhase: SyncPhase { phase }

    public func phases() -> AsyncStream<SyncPhase> {
        let (stream, continuation) = AsyncStream.makeStream(of: SyncPhase.self)
        let key = UUID()
        listeners[key] = continuation
        continuation.yield(phase)
        continuation.onTermination = { [weak self] _ in
            Task { await self?.removeListener(key) }
        }
        return stream
    }

    private func removeListener(_ key: UUID) { listeners[key] = nil }

    private func setPhase(_ next: SyncPhase) {
        guard phase != next else { return }
        phase = next
        for continuation in listeners.values { continuation.yield(next) }
    }

    /// Concurrent kicks share one run; a kick during a run makes it loop exactly once more.
    public func kick() async {
        if let inflight {
            again = true
            await inflight.value
            return
        }
        let task = Task { await self.loop() }
        inflight = task
        await task.value
    }

    private func loop() async {
        repeat {
            again = false
            await run()
        } while again
        // Cleared with no suspension after the last `again` check, so a waiter never joins a finished run.
        inflight = nil
        onRunFinished()
    }

    private func run() async {
        guard let household = await database.current else { return }
        guard await isOnline() else { return setPhase(.offline) }
        setPhase(.syncing)
        do {
            var step = try await push(household)
            // A pull after a push that never reached the server would only hide what is still queued.
            if step == .ok { step = try await pull(household) }
            setPhase(step == .network ? .offline : .idle)
        } catch {
            // A closed database (household switch) or a failed write ends this run; the next trigger starts a new one.
            setPhase(.idle)
        }
        // A switch or landing during the run leaves the household open now unsynced: go again for it.
        if let current = await database.current, current != household { again = true }
    }

    private func push(_ household: UUID) async throws -> Step {
        var retriedBusy = false
        while true {
            let rows = try await database.pendingMutations(householdId: household, tables: SyncTables.pushable, limit: Self.pageSize)
            if rows.isEmpty { return .ok }
            let body = SyncPushBody(householdId: household.uuidString.lowercased(), mutations: rows.map(\.mutation))
            switch await transport.push(body) {
            case .failure(.network):
                return .network
            case .failure(.retryable):
                if retriedBusy { return .stop }
                retriedBusy = true
                await sleep(Self.retryableDelay)
                continue
            case .failure(.other):
                return .stop
            case .success(let result):
                let applied = Set(result.applied)
                let rejections = Dictionary(result.rejected.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
                var needsRefresh = false
                var done: [Int] = []
                var failed: [(seq: Int, reason: SyncRejectionReason, detail: String)] = []
                for row in rows {
                    let id = row.mutation.id
                    if applied.contains(id) {
                        done.append(row.seq)
                    } else if let rejection = rejections[id] {
                        if rejection.reason == .rlsDenied, !refreshed.contains(id) {
                            // The token may be stale: one retry after a refresh before this counts as a real rejection.
                            refreshed.insert(id)
                            needsRefresh = true
                        } else {
                            failed.append((row.seq, rejection.reason, rejection.detail))
                        }
                    }
                }
                // The server answered for none of them: do not spin.
                if done.isEmpty, failed.isEmpty, !needsRefresh { return .stop }
                try await database.settle(householdId: household, applied: done, rejected: failed, now: now())
                if needsRefresh { await transport.refreshSession() }
            }
        }
    }

    private func pull(_ household: UUID) async throws -> Step {
        while true {
            let since = try await database.readCursor()
            switch await transport.pull(householdId: household.uuidString.lowercased(), since: since, limit: Self.pageSize) {
            case .failure(.network): return .network
            case .failure: return .stop
            case .success(let page):
                // The household was switched while the page was in flight: nothing was written.
                guard try await database.applyPull(householdId: household, page: page) else { return .stop }
                if !page.hasMore { return .ok }
                // A server that says "more" without moving the cursor would loop forever.
                if page.cursor <= since { return .stop }
            }
        }
    }

    public func retry(seq: Int) async {
        guard await database.current != nil else { return }
        if let id = try? await database.requeueFailed(seq: seq) { refreshed.remove(id) }
        await kick()
    }

    public func discard(seq: Int) async {
        guard await database.current != nil else { return }
        _ = try? await database.dropFailed(seq: seq)
        await kick()
    }
}
