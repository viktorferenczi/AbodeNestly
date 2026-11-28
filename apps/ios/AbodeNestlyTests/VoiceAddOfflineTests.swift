import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// Like the other offline suites' stubs, with its own handler so the suites can run side by side.
final class VoiceStubProtocol: SyncStubProtocol, @unchecked Sendable {
    static let own = OSAllocatedUnfairLock<Handler?>(initialState: nil)
    override class var store: OSAllocatedUnfairLock<Handler?> { own }
}

/// TESTING §6, the same steps as web's `SHOP-14` offline test: say an item while the network throws, the row shows with
/// its quantity and unit and one outbox row is queued; then the network returns and the push carries it.
@MainActor
@Suite("SHOP-14 offline", .serialized)
struct VoiceAddOfflineTests {
    @Test("SHOP-14: AC2 an item said offline shows as Milk · 2 l with one queued row, then is pushed when the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        VoiceStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let service = makeSyncHttpService(stub: VoiceStubProtocol.self)
        let h = try await DetailHarness()
        let engine = LiveSyncEngine(transport: service, database: h.db, isOnline: { true })
        let model = ListDetailModel(householdId: h.household, listId: h.list, userId: h.user, database: h.db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        // A fake on-device recognizer: no audio engine, no network.
        let recognizer = FakeSpeechRecognizer(onDevice: true)
        let voice = model.makeVoice(recognizer: recognizer, isOnline: { false }, locale: enUS)

        await voice.start()
        #expect(recognizer.offlineStarts == [true])
        recognizer.emit(.final("2 litres milk"))
        #expect(voice.state == .parsed("2 litres milk", SpokenItem(name: "Milk", quantity: 2, unit: "l")))
        #expect(await voice.addParsed())

        await h.settle { model.unchecked.count == 1 }
        let item = try #require(model.unchecked.first)
        #expect(item.name == "Milk" && ItemSheetLogic.formatQty(item.quantity, item.unit) == "2 l")
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        // The kick after the add reached no server and did not lose the row.
        for _ in 0..<400 where await engine.currentPhase != .offline { try await Task.sleep(for: .milliseconds(5)) }
        #expect(await engine.currentPhase == .offline)
        #expect(model.unchecked.map(\.name) == ["Milk"])

        VoiceStubProtocol.own.withLock {
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
        let push = pushes.flatMap(\.mutations).first { $0.table == "list_item" && $0.rowId == item.id.uuidString.lowercased() }
        #expect(push?.data?["name"] == .string("Milk"))
        #expect(push?.data?["unit"] == .string("l"))
        let quantity = push?.data?["quantity"]
        #expect(quantity == .double(2) || quantity == .int(2))
        #expect(try await h.outbox().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }
}
