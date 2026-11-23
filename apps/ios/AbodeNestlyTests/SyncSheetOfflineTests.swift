import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-08-sync.offline.spec.ts`: add while the network throws, nothing is lost,
/// then the network returns and the next kick pushes it.
@Suite("SHOP-08 offline", .serialized)
struct SyncSheetOfflineTests {
    @Test("SHOP-08: AC1 AC2 a list item added offline is queued, and pushed by the first kick after the network returns")
    func offlineThenOnline() async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: db, isOnline: { true })

        let item = UUID()
        try await db.upsert(table: "list_item", row: jsonRow([
            "id": item.uuidString.lowercased(), "list_id": UUID().uuidString.lowercased(), "name": "Milk",
        ]))
        await engine.kick()
        #expect(try await db.outboxForTesting().count == 1)
        #expect(await engine.currentPhase == .offline)
        #expect(try await db.queryForTesting("SELECT name FROM list_item") == [["name": "Milk"]])

        OfflineStubProtocol.own.withLock {
            $0 = { request in
                if request.httpMethod == "POST" {
                    let body = bodyData(of: request)
                    bodies.withLock { $0.append(body) }
                    let ids = ((try? JSONDecoder().decode(SyncPushBody.self, from: body))?.mutations.map(\.id)) ?? []
                    return (200, try JSONEncoder().encode(SyncPushResult(applied: ids)))
                }
                return (200, Data(#"{"cursor":1,"has_more":false,"changes":{}}"#.utf8))
            }
        }
        await engine.kick()
        let pushed = try #require(bodies.withLock { $0.first })
        let body = try JSONDecoder().decode(SyncPushBody.self, from: pushed)
        #expect(body.mutations.map(\.rowId) == [item.uuidString.lowercased()])
        #expect(body.mutations.first?.data?["name"] == .string("Milk"))
        #expect(try await db.outboxForTesting().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
