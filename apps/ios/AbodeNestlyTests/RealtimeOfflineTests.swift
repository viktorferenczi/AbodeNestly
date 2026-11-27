import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6 (web twin: the offline case in `web/src/shopping/Realtime.test.tsx`): the network throws, an event
/// arrives and its kick fails offline with the local rows intact; then the network returns and the next event pulls.
@MainActor
@Suite("SHOP-09 offline", .serialized)
struct RealtimeOfflineTests {
    @Test("SHOP-09: AC3 an event while the network throws leaves the rows alone; with the network back the pull applies")
    func offlineThenOnline() async throws {
        let household = UUID(), list = UUID()
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let db = try await openSyncDatabase(household)
        try await seedList(db, id: list, name: "Groceries")
        try await seedItem(db, list: list, name: "Milk")
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: db, isOnline: { true })
        let realtime = FakeRealtime()
        let task = Task {
            await HouseholdRealtime.run(
                realtime: realtime, householdId: household, modules: HomeModules(shopping: true, pantry: false, maintenance: false), sync: engine
            )
        }
        defer { task.cancel() }
        for _ in 0..<1000 where realtime.active != 1 { try await Task.sleep(for: .milliseconds(5)) }

        realtime.emit()
        for _ in 0..<1000 where await engine.currentPhase != .offline { try await Task.sleep(for: .milliseconds(5)) }
        #expect(await engine.currentPhase == .offline)
        #expect(try await db.queryForTesting("SELECT name, checked FROM list_item").map { $0["name"] } == ["Milk"])

        let id = try #require(try await db.queryForTesting("SELECT id FROM list_item").first?["id"])
        let household_ = household.uuidString.lowercased(), list_ = list.uuidString.lowercased()
        OfflineStubProtocol.own.withLock {
            $0 = { request in
                if request.httpMethod == "POST" {
                    let ids = ((try? JSONDecoder().decode(SyncPushBody.self, from: bodyData(of: request)))?.mutations.map(\.id)) ?? []
                    return (200, try JSONEncoder().encode(SyncPushResult(applied: ids)))
                }
                let row = #"{"id":"\#(id)","household_id":"\#(household_)","list_id":"\#(list_)","name":"Milk","checked":true,"deleted_at":null,"created_at":"2026-11-01T10:00:00.000Z","sync_seq":4}"#
                return (200, Data(#"{"cursor":4,"has_more":false,"changes":{"list_item":[\#(row)]}}"#.utf8))
            }
        }
        realtime.emit()
        for _ in 0..<1000 {
            let rows = try await db.queryForTesting("SELECT checked FROM list_item")
            if rows.first?["checked"] == "1" { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        let rows = try await db.queryForTesting("SELECT checked FROM list_item")
        #expect(rows.first?["checked"] == "1")
        #expect(await engine.currentPhase == .idle)
    }
}
