import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-02-quick-add.offline.spec.ts`: add Milk from a suggestion while the
/// network throws, it shows at once with one outbox upsert; then the network returns and the next kick pushes it.
@MainActor
@Suite("SHOP-02 offline", .serialized)
struct QuickAddOfflineTests {
    @Test("SHOP-02: AC4 an item added from a suggestion offline shows at once and is pushed after the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let h = try await DetailHarness(seed: { db, _ in try await seedItem(db, list: UUID(), name: "Milk", checked: true) })
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: h.db, isOnline: { true })
        let model = ListDetailModel(householdId: h.household, listId: h.list, userId: h.user, database: h.db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await h.settle { model.loaded && !model.snapshot.history.isEmpty }

        model.query = "mi"
        await model.pick(try #require(model.suggestions.first))
        #expect(await engine.currentPhase == .offline)
        await h.settle { model.unchecked.count == 1 }
        #expect(model.unchecked.map(\.name) == ["Milk"])
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")

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
        // The seeded history row rides along in the same push; the new row is the one on this list.
        let added = body.mutations.filter { $0.table == "list_item" && $0.data?["list_id"] == .string(h.list.uuidString.lowercased()) }
        #expect(added.count == 1)
        #expect(added.first?.data?["name"] == .string("Milk"))
        #expect(try await h.outbox().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
