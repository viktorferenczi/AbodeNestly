import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-03-item-sheet.offline.spec.ts`: set a quantity while the network throws,
/// it shows at once with one queued patch; then the network returns and the next kick pushes only that column.
@MainActor
@Suite("SHOP-03 offline", .serialized)
struct ItemSheetOfflineTests {
    @Test("SHOP-03: AC4 a quantity saved offline shows at once and is pushed as { quantity } after the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") })
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: h.db, isOnline: { true })
        let model = ListDetailModel(householdId: h.household, listId: h.list, userId: h.user, database: h.db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await h.settle { model.unchecked.count == 1 }

        let item = try #require(model.unchecked.first)
        #expect(await model.save(item, ItemFields(name: "Milk", quantity: 3)))
        #expect(await engine.currentPhase == .offline)
        await h.settle { model.unchecked.first?.quantity == 3 }
        #expect(model.unchecked.first?.quantity == 3)
        #expect(try await h.outbox().count == 1)

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
        let patch = body.mutations.filter { $0.table == "list_item" && $0.rowId == item.id.uuidString.lowercased() }.last
        #expect(patch?.data == ["quantity": .double(3)])
        #expect(try await h.outbox().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
