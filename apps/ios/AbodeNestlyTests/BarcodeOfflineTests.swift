import Contract
import Foundation
import Testing
import os
@testable import Core
@testable import Shopping
@testable import Sync

/// Like the other offline suites' stubs, with its own handler so the suites can run side by side.
final class BarcodeStubProtocol: SyncStubProtocol, @unchecked Sendable {
    static let own = OSAllocatedUnfairLock<Handler?>(initialState: nil)
    override class var store: OSAllocatedUnfairLock<Handler?> { own }
}

/// TESTING §6, the same steps as web's `SHOP-10` offline test: scan while the network throws, the item shows with the
/// code as its name and a lookup is queued; then the network returns and a drain renames it and the push carries the
/// patch with `name` and `barcode`.
@MainActor
@Suite("SHOP-10 offline", .serialized)
struct BarcodeOfflineTests {
    private let code = "3017620422003"

    @Test("SHOP-10: AC4 a code entered offline shows as the item name with a queued lookup, then resolves after the network returns")
    func offlineThenOnline() async throws {
        let bodies = OSAllocatedUnfairLock<[Data]>(initialState: [])
        BarcodeStubProtocol.own.withLock { $0 = { _ in throw URLError(.notConnectedToInternet) } }
        let service = makeSyncHttpService(stub: BarcodeStubProtocol.self)
        let h = try await DetailHarness()
        let engine = LiveSyncEngine(transport: service, database: h.db, isOnline: { true })
        let model = ListDetailModel(householdId: h.household, listId: h.list, userId: h.user, database: h.db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        let suite = "abodenestly.tests.\(UUID().uuidString)"
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        let store = BarcodeStore(suite: suite)
        // The device believes it is online, so the lookup is attempted; the URLProtocol makes it fail like a dead network.
        let scanner = model.makeScanner(
            device: FakeScannerDevice(), lookup: { await service.product(barcode: $0) }, isOnline: { true }, store: store
        )

        scanner.code = code
        await scanner.submitCode()
        #expect(scanner.state == .queued(code))
        await h.settle { model.unchecked.count == 1 }
        #expect(model.unchecked.map(\.name) == [code])
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        #expect(store.pending().count == 1)
        for _ in 0..<400 where await engine.currentPhase != .offline { try await Task.sleep(for: .milliseconds(5)) }
        #expect(await engine.currentPhase == .offline)

        BarcodeStubProtocol.own.withLock {
            $0 = { request in
                if request.url?.path.hasPrefix("/products/") == true {
                    return (200, Data(#"{"barcode":"3017620422003","name":"Nutella","brand":"Ferrero","category":null,"image_url":null,"default_unit":null}"#.utf8))
                }
                if request.httpMethod == "POST" {
                    let body = bodyData(of: request)
                    bodies.withLock { $0.append(body) }
                    let ids = ((try? JSONDecoder().decode(SyncPushBody.self, from: body))?.mutations.map(\.id)) ?? []
                    return (200, try JSONEncoder().encode(SyncPushResult(applied: ids)))
                }
                return (200, Data(#"{"cursor":1,"has_more":false,"changes":{}}"#.utf8))
            }
        }
        // A kick that reached the server, then the drain it triggers.
        await engine.kick()
        try await BarcodeLookupDrainer().drain(BarcodeLookupDrainer.Request(
            database: h.db, householdId: h.household, lookup: { await service.product(barcode: $0) }, isOnline: { true },
            store: store, onRenamed: { await engine.kick() }
        ))
        await h.settle { model.unchecked.first?.name == "Nutella" }
        #expect(model.unchecked.map(\.name) == ["Nutella"])
        #expect(store.pending().isEmpty)

        let pushes = try bodies.withLock { $0 }.map { try JSONDecoder().decode(SyncPushBody.self, from: $0) }
        let itemId = try #require(model.unchecked.first?.id).uuidString.lowercased()
        let patch = pushes.flatMap(\.mutations).filter { $0.table == "list_item" && $0.rowId == itemId }.last
        #expect(patch?.data?["name"] == .string("Nutella"))
        #expect(patch?.data?["barcode"] == .string(code))
        #expect(try await h.outbox().isEmpty)
        #expect(await engine.currentPhase == .idle)
    }

    @Test("SHOP-10: the product request maps statuses like web toProductFailure", arguments: [
        (200, nil as String?, "found"), (404, nil, "notFound"), (404, "not_found", "notFound"), (400, "invalid_barcode", "notFound"),
        (400, nil, "notFound"), (502, nil, "offline"), (502, "upstream_unavailable", "offline"), (401, nil, "failed"),
        (500, nil, "failed"), (403, "forbidden", "failed"),
    ])
    func productMapping(status: Int, errorCode: String?, expected: String) {
        let body: Data
        if status == 200 {
            body = Data(#"{"barcode":"3017620422003","name":"Nutella","brand":null,"category":null,"image_url":null,"default_unit":null}"#.utf8)
        } else if let errorCode {
            body = Data(#"{"error":{"code":"\#(errorCode)"}}"#.utf8)
        } else {
            body = Data()
        }
        let result = HttpApiService.productResult(status: status, code: errorCode, data: body)
        let label: String = switch result {
        case .found: "found"
        case .notFound: "notFound"
        case .offline: "offline"
        case .failed: "failed"
        }
        #expect(label == expected)
    }

    @Test("SHOP-10: a 200 with an unreadable body is a failure, not a product")
    func unreadableBody() {
        #expect(HttpApiService.productResult(status: 200, code: nil, data: Data("nope".utf8)) == .failed)
    }

    @Test("SHOP-10: a 502 while online queues the lookup before the add's kick, so that kick's drain renames the item")
    func queuedBeforeKick() async throws {
        let h = try await DetailHarness()
        let suite = "abodenestly.tests.\(UUID().uuidString)"
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        let store = BarcodeStore(suite: suite)
        let seenAtKick = OSAllocatedUnfairLock<[Int]>(initialState: [])
        let model = ListDetailModel(
            householdId: h.household, listId: h.list, userId: h.user, database: h.db,
            kick: { seenAtKick.withLock { $0.append(store.pending().count) } }
        )
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        let scanner = model.makeScanner(
            device: FakeScannerDevice(), lookup: { _ in .offline }, isOnline: { true }, store: store
        )

        scanner.code = code
        await scanner.submitCode()
        #expect(scanner.state == .queued(code))
        // The add's own kick already sees the queued lookup, so the drain that follows that sync run can resolve it.
        #expect(seenAtKick.withLock { $0 } == [1])
    }
}
