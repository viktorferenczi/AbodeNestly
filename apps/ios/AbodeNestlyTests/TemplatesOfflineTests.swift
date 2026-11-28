import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-11-templates.offline.spec.ts`: Add all while the network throws, the
/// target shows the items at once and the outbox holds one upsert each; then the network returns and the next kick
/// pushes them.
@MainActor
@Suite("SHOP-11 offline", .serialized)
struct TemplatesOfflineTests {
    @Test("SHOP-11: AC4 items added offline are queued and pushed by the first kick after the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let h = try await TemplatesHarness(seed: { db, template in
            try await seedTemplateItem(db, list: template, name: "Milk", created: "2026-11-01T10:00:01.000Z")
            try await seedTemplateItem(db, list: template, name: "Eggs", created: "2026-11-01T10:00:02.000Z")
        })
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: h.db, isOnline: { true })
        let model = ListDetailModel(
            householdId: h.household, listId: h.template, userId: h.user, database: h.db, kick: { await engine.kick() }
        )
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await h.settle { model.isTemplate && model.lists.count == 2 }

        let groceries = ListDetailModel(householdId: h.household, listId: h.target, userId: h.user, database: h.db, kick: {})
        let groceriesFollower = Task { await groceries.start() }
        defer { groceriesFollower.cancel() }

        let result = await model.addAll(to: h.groceries)
        #expect(result == .added(count: 2, listName: "Groceries"))
        // The kick can still be settling when addAll returns, so wait for the failed push to flip the phase.
        for _ in 0..<400 where await engine.currentPhase != .offline { try await Task.sleep(for: .milliseconds(5)) }
        #expect(await engine.currentPhase == .offline)
        await h.settle { groceries.unchecked.count == 2 }
        #expect(groceries.unchecked.map(\.name) == ["Milk", "Eggs"])
        let queued = try await h.outbox("list_item")
        #expect(queued.count == 2)

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
        let target = JSONValue.string(h.target.uuidString.lowercased())
        var copies: [SyncMutation] = []
        for mutation in body.mutations where mutation.table == "list_item" && mutation.data?["list_id"] == target {
            copies.append(mutation)
        }
        #expect(copies.count == 2)
        #expect(copies.allSatisfy { $0.op == "upsert" })
        #expect(try await h.db.outboxForTesting().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
