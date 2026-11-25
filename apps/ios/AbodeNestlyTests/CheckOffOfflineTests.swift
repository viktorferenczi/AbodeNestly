import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// TESTING §6, the same steps as `web/e2e/SHOP-04-check-off.offline.spec.ts`: check off while the network throws, wait
/// out the window; the outbox holds the check-off and the phase is offline. Then the network returns and the next kick
/// pushes it.
@MainActor
@Suite("SHOP-04 offline", .serialized)
struct CheckOffOfflineTests {
    @Test("SHOP-04: AC6 a check-off made offline is queued after the window and pushed once the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        OfflineStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") })
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: OfflineStubProtocol.self), database: h.db, isOnline: { true })
        let model = ListDetailModel(
            householdId: h.household, listId: h.list, userId: h.user, database: h.db, kick: { await engine.kick() },
            undoWindow: .milliseconds(100)
        )
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await h.settle { model.unchecked.count == 1 }

        let item = try #require(model.unchecked.first)
        model.check(item)
        await h.settle { model.pendingActions.isEmpty && model.checked.count == 1 }
        #expect(model.checked.count == 1)
        #expect(try await h.outbox().count == 1)
        // The kick runs after the pending state clears, so wait for the failed push to flip the phase.
        for _ in 0..<400 where await engine.currentPhase != .offline { try await Task.sleep(for: .milliseconds(5)) }
        #expect(await engine.currentPhase == .offline)

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
        let upsert = body.mutations.filter { $0.table == "list_item" && $0.rowId == item.id.uuidString.lowercased() }.last
        #expect(upsert?.data?["checked"] == .bool(true))
        #expect(try await h.outbox().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
