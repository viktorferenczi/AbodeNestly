import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// Its own handler, so this suite can run beside the other offline suites.
final class AssistantStubProtocol: SyncStubProtocol, @unchecked Sendable {
    static let own = OSAllocatedUnfairLock<Handler?>(initialState: nil)
    override class var store: OSAllocatedUnfairLock<Handler?> { own }
}

/// TESTING §6, the same steps as the Android twin: "milk" is added while the network throws, the local row and one
/// outbox row exist and the failed kick loses neither; then the network returns and the push carries the item.
@Suite("SHOP-15 offline", .serialized)
struct AssistantShortcutOfflineTests {
    @Test("SHOP-15: AC3 an item added by the assistant offline is stored locally and queued, then pushed when the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        AssistantStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let household = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, name: "Groceries")
        let engine = LiveSyncEngine(transport: makeSyncHttpService(stub: AssistantStubProtocol.self), database: db, isOnline: { true })
        let queuedBefore = try await db.outboxForTesting().count

        let result = await AssistantShortcut.add(text: "milk", database: db, householdId: household, userId: UUID())
        #expect(result == .added(name: "Milk", list: "Groceries"))
        await engine.kick()

        #expect(await engine.currentPhase == .offline)
        let rows = try await db.queryForTesting("SELECT name FROM list_item")
        #expect(rows.count == 1 && rows[0]["name"] == "Milk")
        let queued = try await db.outboxForTesting().dropFirst(queuedBefore)
        #expect(queued.count == 1 && queued.first?.table == "list_item" && queued.first?.op == "upsert")

        AssistantStubProtocol.own.withLock {
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

        let pushes = try bodies.withLock { $0 }.map { try JSONDecoder().decode(SyncPushBody.self, from: $0) }
        let push = pushes.flatMap(\.mutations).first { $0.table == "list_item" }
        #expect(push?.data?["name"] == .string("Milk"))
        #expect(try await db.outboxForTesting().filter { $0.table == "list_item" }.isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
