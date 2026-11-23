import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-01-lists.offline.spec.ts`: create a list while the network throws, it
/// shows at once and an outbox row exists; then the network returns and the next kick pushes it.
@MainActor
@Suite("SHOP-01 offline", .serialized)
struct ListsOfflineTests {
    @Test("SHOP-01: AC6 a list created offline appears at once and is pushed by the first kick after the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let h = try await ListsHarness()
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: h.db, isOnline: { true })
        let model = ListsModel(householdId: h.household, database: h.db, kick: { await engine.kick() })

        let row = try #require(await model.create(name: "Groceries", storeId: nil))
        #expect(await engine.currentPhase == .offline)
        await h.settle { h.model.sections.lists.count == 1 }
        #expect(h.model.sections.lists.map(\.name) == ["Groceries"])
        let queued = try await h.outbox()
        #expect(queued.count == 1)
        #expect(queued.first?.rowId == row.id.uuidString.lowercased())

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
        #expect(body.mutations.map(\.table) == ["shopping_list"])
        #expect(body.mutations.first?.rowId == row.id.uuidString.lowercased())
        #expect(body.mutations.first?.data?["name"] == .string("Groceries"))
        #expect(try await h.outbox().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
