import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Sync

private actor Sleeps {
    var values: [Duration] = []
    func add(_ value: Duration) { values.append(value) }
}

private struct EngineSetup {
    let api = FakeApiService()
    let household = UUID()
    let db: HouseholdDatabase
    let engine: LiveSyncEngine
    let sleeps = Sleeps()

    init(online: Bool = true) async throws {
        let db = try await openSyncDatabase(household)
        self.db = db
        let sleeps = sleeps
        engine = LiveSyncEngine(
            transport: api, database: db, isOnline: { online }, sleep: { await sleeps.add($0) },
            now: { Date(timeIntervalSince1970: 1_800_000_000) }
        )
    }

    var pushCount: Int { api.pushes.count }
    var pullSinces: [Int] { api.calls.compactMap { if case .syncPull(_, let since, _) = $0 { since } else { nil } } }
}

@Suite("SHOP-08 sync engine")
struct SyncEngineTests {
    @Test("SHOP-08: AC2 kick pushes both queued rows in seq order, then pulls from 0 and stores what comes back")
    func pushThenPull() async throws {
        let s = try await EngineSetup()
        let first = UUID(), second = UUID(), pulled = UUID()
        try await s.db.upsert(table: "store", row: storeRow(first, name: "A"))
        try await s.db.upsert(table: "store", row: storeRow(second, name: "B"))
        s.api.queuePullResults([.success(SyncPullPage(cursor: 5, hasMore: false, changes: [
            "store": [jsonRow(["id": pulled.uuidString.lowercased(), "household_id": s.household.uuidString.lowercased(), "name": "C"])],
        ]))])
        await s.engine.kick()

        let calls = s.api.calls
        #expect(calls.count == 2)
        guard case .syncPush(let body) = calls[0] else { Issue.record("first call is not the push"); return }
        #expect(body.householdId == s.household.uuidString.lowercased())
        #expect(body.mutations.map(\.rowId) == [first, second].map { $0.uuidString.lowercased() })
        #expect(body.mutations.map(\.op) == ["upsert", "upsert"])
        #expect(calls[1] == .syncPull(householdId: s.household.uuidString.lowercased(), since: 0, limit: 500))
        #expect(try await s.db.outboxForTesting().isEmpty)
        #expect(try await s.db.queryForTesting("SELECT name FROM store ORDER BY name").map { $0["name"] } == ["A", "B", "C"])
        #expect(try await s.db.readCursor() == 5)
    }

    @Test("SHOP-08: AC2 has_more pulls the next page from the new cursor")
    func pagesUntilDone() async throws {
        let s = try await EngineSetup()
        s.api.queuePullResults([
            .success(SyncPullPage(cursor: 500, hasMore: true)), .success(SyncPullPage(cursor: 612, hasMore: false)),
        ])
        await s.engine.kick()
        #expect(s.pullSinces == [0, 500])
        #expect(try await s.db.readCursor() == 612)
    }

    @Test("SHOP-08: a server that says more without moving the cursor does not loop forever")
    func stuckCursor() async throws {
        let s = try await EngineSetup()
        s.api.queuePullResults([.success(SyncPullPage(cursor: 0, hasMore: true))])
        await s.engine.kick()
        #expect(s.pullSinces == [0])
    }

    @Test("SHOP-08: a rejected mutation moves to the failed list with its reason and detail, the rest are applied")
    func rejected() async throws {
        let s = try await EngineSetup()
        let bad = UUID(), good = UUID()
        try await s.db.upsert(table: "store", row: storeRow(bad))
        try await s.db.upsert(table: "store", row: storeRow(good))
        let ids = try await s.db.pendingMutations(householdId: s.household, tables: SyncTables.pushable, limit: 10).map(\.mutation.id)
        s.api.queuePushResults([.success(SyncPushResult(
            applied: [ids[1]], rejected: [SyncRejection(id: ids[0], reason: .validation, detail: "name is required")]
        ))])
        await s.engine.kick()
        #expect(try await s.db.outboxForTesting().isEmpty)
        for await failed in await s.db.observeFailed(householdId: s.household) {
            #expect(failed.count == 1)
            #expect(failed[0].rowId == bad.uuidString.lowercased() && failed[0].reason == .validation && failed[0].detail == "name is required")
            break
        }
        // The pull still runs: the push reached the server.
        #expect(s.pullSinces == [0])
    }

    @Test("SHOP-08: rls_denied gets one retry after a session refresh, then counts as failed")
    func rlsDeniedRetriedOnce() async throws {
        let s = try await EngineSetup()
        try await s.db.upsert(table: "store", row: storeRow())
        let id = try #require(try await s.db.pendingMutations(householdId: s.household, tables: SyncTables.pushable, limit: 1).first).mutation.id
        let denied = Result<SyncPushResult, SyncFailure>.success(SyncPushResult(applied: [], rejected: [SyncRejection(id: id, reason: .rlsDenied, detail: "no")]))
        s.api.queuePushResults([denied, denied])
        await s.engine.kick()
        let kinds = s.api.calls.map { call -> String in
            switch call {
            case .syncPush: "push"
            case .refreshSession: "refresh"
            case .syncPull: "pull"
            default: "other"
            }
        }
        #expect(kinds == ["push", "refresh", "push", "pull"])
        for await failed in await s.db.observeFailed(householdId: s.household) {
            #expect(failed.map(\.reason) == [.rlsDenied])
            break
        }
    }

    @Test("SHOP-08: a busy server (retryable) is retried once after a second, then the rows stay queued and nothing is pulled")
    func retryable() async throws {
        let s = try await EngineSetup()
        try await s.db.upsert(table: "store", row: storeRow())
        s.api.queuePushResults([.failure(.retryable), .failure(.retryable)])
        await s.engine.kick()
        #expect(s.pushCount == 2)
        #expect(await s.sleeps.values == [.seconds(1)])
        #expect(try await s.db.outboxForTesting().count == 1)
        #expect(s.pullSinces.isEmpty)
    }

    @Test("SHOP-08: a retryable answer that clears on the second try completes the push")
    func retryableThenOk() async throws {
        let s = try await EngineSetup()
        try await s.db.upsert(table: "store", row: storeRow())
        s.api.queuePushResults([.failure(.retryable)])
        await s.engine.kick()
        #expect(s.pushCount == 2)
        #expect(try await s.db.outboxForTesting().isEmpty)
        #expect(s.pullSinces == [0])
    }

    @Test("SHOP-08: AC2 no network keeps the rows, skips the pull and reports offline")
    func network() async throws {
        let s = try await EngineSetup()
        try await s.db.upsert(table: "store", row: storeRow())
        s.api.queuePushResults([.failure(.network)])
        await s.engine.kick()
        #expect(try await s.db.outboxForTesting().count == 1)
        #expect(s.pullSinces.isEmpty)
        #expect(await s.engine.currentPhase == .offline)
    }

    @Test("SHOP-08: an unanswered batch stops the run instead of spinning")
    func unanswered() async throws {
        let s = try await EngineSetup()
        try await s.db.upsert(table: "store", row: storeRow())
        s.api.queuePushResults([.success(SyncPushResult(applied: []))])
        await s.engine.kick()
        #expect(s.pushCount == 1)
        #expect(try await s.db.outboxForTesting().count == 1)
    }

    @Test("SHOP-08: when the device is offline nothing is called and the phase is offline")
    func offline() async throws {
        let s = try await EngineSetup(online: false)
        try await s.db.upsert(table: "store", row: storeRow())
        await s.engine.kick()
        #expect(s.api.calls.isEmpty)
        #expect(await s.engine.currentPhase == .offline)
        #expect(try await s.db.outboxForTesting().count == 1)
    }

    @Test("SHOP-08: rows for tables the server does not accept yet stay queued and are never sent")
    func heldBack() async throws {
        let s = try await EngineSetup()
        try await s.db.enqueue(table: "notification_pref", op: "upsert", rowId: UUID(), data: ["enabled": .bool(false)])
        await s.engine.kick()
        #expect(s.pushCount == 0)
        #expect(try await s.db.outboxForTesting().count == 1)
        #expect(s.pullSinces == [0])
    }

    @Test("SHOP-08: concurrent kicks share one run and a kick during a run causes exactly one more")
    func coalesces() async throws {
        let s = try await EngineSetup()
        s.api.hold()
        async let first: Void = s.engine.kick()
        for _ in 0..<10_000 where s.api.calls.isEmpty { await Task.yield() }
        async let second: Void = s.engine.kick()
        async let third: Void = s.engine.kick()
        for _ in 0..<200 { await Task.yield() }
        s.api.release()
        _ = await (first, second, third)
        #expect(s.pullSinces.count == 2)
    }

    @Test("SHOP-08: with no household open there is nothing to sync")
    func noHousehold() async throws {
        let api = FakeApiService()
        let engine = LiveSyncEngine(transport: api, database: HouseholdDatabase(rootURL: switcherTempRoot()), isOnline: { true })
        await engine.kick()
        #expect(api.calls.isEmpty)
    }

    @Test("SHOP-08: a switch during the pull writes nothing into the new file and the new household is synced next")
    func switchDuringPull() async throws {
        let s = try await EngineSetup()
        let other = UUID()
        s.api.hold()
        async let kicked: Void = s.engine.kick()
        for _ in 0..<10_000 where s.api.calls.isEmpty { await Task.yield() }
        try await s.db.open(householdId: other)
        s.api.queuePullResults([.success(SyncPullPage(cursor: 9, hasMore: false, changes: ["store": [storeRow(UUID(), name: "Old")]]))])
        s.api.release()
        await kicked
        let pulled = s.api.calls.compactMap { call -> String? in
            if case .syncPull(let id, _, _) = call { id } else { nil }
        }
        #expect(pulled == [s.household.uuidString.lowercased(), other.uuidString.lowercased()])
        #expect(try await s.db.queryForTesting("SELECT name FROM store").isEmpty)
    }

    @Test("SHOP-08: phases are published, the current one first")
    func phases() async throws {
        let s = try await EngineSetup()
        var seen: [SyncPhase] = []
        for await phase in await s.engine.phases() {
            seen.append(phase)
            if seen.count == 1 { Task { await s.engine.kick() } }
            if seen.count == 3 { break }
        }
        #expect(seen == [.idle, .syncing, .idle])
    }

    @Test("SHOP-08: Retry puts the failed change back at its old place with the same id and pushes it")
    func retry() async throws {
        let s = try await EngineSetup()
        try await s.db.upsert(table: "store", row: storeRow())
        let queued = try #require(try await s.db.pendingMutations(householdId: s.household, tables: SyncTables.pushable, limit: 1).first)
        s.api.queuePushResults([.success(SyncPushResult(applied: [], rejected: [SyncRejection(id: queued.mutation.id, reason: .validation, detail: "bad")]))])
        await s.engine.kick()
        #expect(try await s.db.outboxForTesting().isEmpty)
        await s.engine.retry(seq: queued.seq)
        let pushes = s.api.pushes
        #expect(pushes.count == 2)
        #expect(pushes[1].mutations.map(\.id) == [queued.mutation.id])
        #expect(try await s.db.outboxForTesting().isEmpty)
    }

    @Test("SHOP-08: Discard drops the failed change and its local row, and pulls everything again")
    func discard() async throws {
        let s = try await EngineSetup()
        let id = UUID()
        try await s.db.upsert(table: "store", row: storeRow(id))
        let queued = try #require(try await s.db.pendingMutations(householdId: s.household, tables: SyncTables.pushable, limit: 1).first)
        s.api.queuePushResults([.success(SyncPushResult(applied: [], rejected: [SyncRejection(id: queued.mutation.id, reason: .validation, detail: "bad")]))])
        s.api.queuePullResults([.success(SyncPullPage(cursor: 8, hasMore: false))])
        await s.engine.kick()
        #expect(try await s.db.readCursor() == 8)
        await s.engine.discard(seq: queued.seq)
        #expect(try await s.db.queryForTesting("SELECT id FROM store").isEmpty)
        // The cursor was reset to 0, so the next pull asks from the start.
        #expect(s.pullSinces.last == 0)
        for await failed in await s.db.observeFailed(householdId: s.household) {
            #expect(failed.isEmpty)
            break
        }
    }

    @Test("SHOP-08: Discard keeps the local row while another change still targets it")
    func discardKeepsTargetedRow() async throws {
        let s = try await EngineSetup()
        let id = UUID()
        try await s.db.upsert(table: "store", row: storeRow(id))
        let first = try #require(try await s.db.pendingMutations(householdId: s.household, tables: SyncTables.pushable, limit: 1).first)
        s.api.queuePushResults([.success(SyncPushResult(applied: [], rejected: [SyncRejection(id: first.mutation.id, reason: .validation, detail: "bad")]))])
        // Fail the first change on its own, then queue a second change for the same row before discarding.
        await s.engine.kick()
        try await s.db.patch(table: "store", id: id, fields: ["name": .string("B")])
        await s.engine.discard(seq: first.seq)
        #expect(try await s.db.queryForTesting("SELECT name FROM store") == [["name": "B"]])
    }
}
