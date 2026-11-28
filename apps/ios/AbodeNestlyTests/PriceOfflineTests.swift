import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-12-price.offline.spec.ts`: enter a price while the network throws, the
/// caption shows it at once with one queued `item_price`; then the network returns and the next kick pushes it with
/// its `name_key` and empties the outbox.
@MainActor
@Suite("SHOP-12 offline", .serialized)
struct PriceOfflineTests {
    @Test("SHOP-12: AC4 a price saved offline shows at once and is pushed with its name_key after the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let h = try await DetailHarness(seed: { db, list in
            try await seedPriceList(db, list: list)
            try await seedItem(db, list: list, name: "Milk")
        })
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: h.db, isOnline: { true })
        let model = ListDetailModel(householdId: h.household, listId: h.list, userId: h.user, database: h.db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await h.settle { model.unchecked.count == 1 && model.priceStore != nil }

        let item = try #require(model.unchecked.first)
        #expect(await model.save(item, ItemFields(name: "Milk", price: 1.2)))
        #expect(await engine.currentPhase == .offline)
        await h.settle { model.priceCaption(for: item) != nil }
        let caption: String = try #require(model.priceCaption(for: item))
        #expect(caption == Price.caption(LastBought(price: 1.2, seenAt: "", storeName: "Test Market"), currency: "EUR"))
        let queued = try await priceOutbox(h.db)
        #expect(queued.count == 1)

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
        let upserts = body.mutations.filter { $0.table == "item_price" }
        #expect(upserts.count == 1)
        let sent = try #require(upserts.first)
        #expect(sent.op == "upsert")
        #expect(sent.data?["name_key"] == .string("milk"))
        #expect(sent.data?["store_id"] == .string(priceStoreId.uuidString.lowercased()))
        #expect(sent.data?["price"] == .double(1.2))
        #expect(try await h.db.outboxForTesting().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
