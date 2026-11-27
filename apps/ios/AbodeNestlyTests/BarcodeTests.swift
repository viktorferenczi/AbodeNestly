import Contract
import Foundation
import Testing
import os
@testable import Shopping
@testable import Sync

// MARK: shared fixtures for the SHOP-10 suites

func barcodeProduct(
    _ barcode: String = "3017620422003", name: String = "Nutella", brand: String? = "Ferrero", image: String? = "https://img.test/nutella.jpg"
) -> Product {
    Product(barcode: barcode, name: name, brand: brand, category: nil, imageUrl: image, defaultUnit: nil)
}

/// Opens and releases lookups held by a `BarcodeLookupScript`.
actor BarcodeGate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        for waiter in waiters { waiter.resume() }
        waiters = []
    }
}

/// A scripted lookup: answers from `results` in order (the last one repeats), records each code, and holds every lookup
/// from the `gatedFrom`th on until `gate` opens.
final class BarcodeLookupScript: Sendable {
    private let state: OSAllocatedUnfairLock<(results: [ProductLookupResult], codes: [String])>
    private let gate: BarcodeGate?
    private let gatedFrom: Int

    init(_ results: [ProductLookupResult], gate: BarcodeGate? = nil, gatedFrom: Int = .max) {
        state = OSAllocatedUnfairLock(initialState: (results: results, codes: []))
        self.gate = gate
        self.gatedFrom = gatedFrom
    }

    var codes: [String] { state.withLock { $0.codes } }

    func lookup(_ code: String) async -> ProductLookupResult {
        let (result, index) = state.withLock { s -> (ProductLookupResult, Int) in
            s.codes.append(code)
            let result = s.results.count > 1 ? s.results.removeFirst() : (s.results.first ?? .notFound)
            return (result, s.codes.count - 1)
        }
        if index >= gatedFrom { await gate?.wait() }
        return result
    }
}

/// A scanner over a seeded household and list, with a fake camera, a scripted lookup and its own `UserDefaults` suite.
@MainActor
final class BarcodeHarness {
    let detail: DetailHarness
    let device: FakeScannerDevice
    let script: BarcodeLookupScript
    let suite: String
    let model: BarcodeScannerModel

    init(
        device: FakeScannerDevice = FakeScannerDevice(), online: Bool = true, results: [ProductLookupResult] = [.notFound],
        gate: BarcodeGate? = nil, gatedFrom: Int = .max, seed: (HouseholdDatabase, UUID) async throws -> Void = { _, _ in }
    ) async throws {
        let detail = try await DetailHarness(seed: seed)
        let suite = "abodenestly.tests.\(UUID().uuidString)"
        let script = BarcodeLookupScript(results, gate: gate, gatedFrom: gatedFrom)
        self.detail = detail
        self.device = device
        self.script = script
        self.suite = suite
        model = detail.model.makeScanner(
            device: device, lookup: { await script.lookup($0) }, isOnline: { online }, store: BarcodeStore(suite: suite)
        )
    }

    var store: BarcodeStore { BarcodeStore(suite: suite) }

    func settle(until condition: @MainActor () -> Bool) async { await detail.settle(until: condition) }

    /// The queued `list_item` writes, decoded.
    func queued() async throws -> [(op: String, data: [String: JSONValue])] {
        try await detail.outbox().map { ($0.op, try JSONDecoder().decode([String: JSONValue].self, from: Data($0.data.utf8))) }
    }

    deinit { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
}

// MARK: pure logic

@Suite("SHOP-10 barcode logic")
struct BarcodeLogicTests {
    @Test("SHOP-10: isBarcode takes 8 to 14 digits and nothing else", arguments: [
        ("12345678", true), ("3017620422003", true), ("12345678901234", true), ("1234567", false), ("123456789012345", false),
        ("1234567a", false), ("1234 5678", false), ("", false), ("-12345678", false),
    ])
    func isBarcode(code: String, expected: Bool) {
        #expect(Barcode.isBarcode(code) == expected)
    }

    @Test("SHOP-10: normalize turns EAN-13 with a leading 0 into UPC-A and leaves other codes alone", arguments: [
        ("0012345678905", "012345678905"), ("3017620422003", "3017620422003"), ("01234567", "01234567"),
        ("012345678905", "012345678905"), ("00123456789012", "00123456789012"),
    ])
    func normalize(raw: String, expected: String) {
        #expect(Barcode.normalize(raw) == expected)
    }

    @Test("SHOP-10: the product image shows for an https URL only")
    func imageURL() {
        #expect(Barcode.imageURL("https://img.test/a.jpg") != nil)
        #expect(Barcode.imageURL("http://img.test/a.jpg") == nil)
        #expect(Barcode.imageURL("javascript:alert(1)") == nil)
        #expect(Barcode.imageURL("") == nil)
        #expect(Barcode.imageURL(nil) == nil)
    }
}

@Suite("SHOP-10 name cache and queue")
struct BarcodeStoreTests {
    private let suite = "abodenestly.tests.\(UUID().uuidString)"
    private var store: BarcodeStore { BarcodeStore(suite: suite) }

    @Test("SHOP-10: the cache evicts in insertion order, with 8-digit and 13-digit codes mixed")
    func eviction() {
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        // 8-digit codes are integer-like; an object would order them numerically, not by insertion.
        let codes = (0..<BarcodeStore.namesCap + 5).map { $0 % 2 == 0 ? String(format: "%08ld", 90_000_000 - $0) : String(format: "%013ld", 3_000_000_000_000 + $0) }
        for (index, code) in codes.enumerated() { store.cacheName(code: code, name: "Item \(index)") }
        let kept = store.names().map(\.code)
        #expect(kept == Array(codes.suffix(BarcodeStore.namesCap)))
        #expect(store.cachedName(codes[0]) == nil)
        #expect(store.cachedName(codes[4]) == nil)
        #expect(store.cachedName(codes[5]) == "Item 5")
    }

    @Test("SHOP-10: naming a code again moves it to the newest end")
    func renamed() {
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        store.cacheName(code: "11111111", name: "A")
        store.cacheName(code: "22222222", name: "B")
        store.cacheName(code: "11111111", name: "C")
        #expect(store.names() == [NameEntry(code: "22222222", name: "B"), NameEntry(code: "11111111", name: "C")])
    }

    @Test("SHOP-10: a corrupt entry is skipped, the rest of the queue survives")
    func lossy() throws {
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        let good = PendingLookup(householdId: UUID(), itemId: UUID(), code: "12345678")
        let goodJSON = String(decoding: try JSONEncoder().encode(good), as: UTF8.self)
        UserDefaults(suiteName: suite)?.set(Data(#"[{"nope":1},\#(goodJSON)]"#.utf8), forKey: BarcodeStore.lookupsKey)
        #expect(store.pending() == [good])
    }
}

// MARK: the scanner model

@MainActor
@Suite("SHOP-10 scanner model", .serialized)
struct BarcodeScannerModelTests {
    private let code = "5000159484695"

    @Test("SHOP-10: AC4 an offline add uses the code as the name, sets no barcode and queues one lookup")
    func offlineAdd() async throws {
        let h = try await BarcodeHarness(online: false)
        await h.model.handle(code)
        #expect(h.model.state == .queued(code))
        await h.settle { h.detail.model.unchecked.count == 1 }
        #expect(h.detail.model.unchecked.map(\.name) == [code])
        let queued = try await h.queued()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        #expect(queued[0].data["name"] == .string(code))
        // No product row exists yet, so the foreign key on list_item.barcode must stay empty.
        #expect(queued[0].data["barcode"] == .null || queued[0].data["barcode"] == nil)
        #expect(h.script.codes.isEmpty)
        let pending = h.store.pending()
        #expect(pending.count == 1 && pending[0].code == code && pending[0].householdId == h.detail.household)
        #expect(pending[0].itemId == h.detail.model.unchecked.first?.id)
    }

    @Test("SHOP-10: a lookup that fails like a network error or a 502 also takes the offline path")
    func transientIsOffline() async throws {
        let h = try await BarcodeHarness(results: [.offline])
        await h.model.handle(code)
        #expect(h.model.state == .queued(code))
        #expect(h.store.pending().count == 1)
        #expect(h.script.codes == [code])
    }

    @Test("SHOP-10: AC2 a found add puts the product name and its barcode on the list")
    func foundAdd() async throws {
        let h = try await BarcodeHarness(results: [.found(barcodeProduct())])
        await h.model.handle("3017620422003")
        #expect(h.model.state == .found(barcodeProduct()))
        #expect(await h.model.addFound())
        let queued = try await h.queued()
        #expect(queued.count == 1)
        #expect(queued[0].data["name"] == .string("Nutella"))
        #expect(queued[0].data["barcode"] == .string("3017620422003"))
        #expect(h.store.pending().isEmpty)
    }

    @Test("SHOP-10: AC3 a not-found add keeps the barcode empty and remembers the name")
    func notFoundAdd() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        await h.model.handle(code)
        #expect(h.model.state == .notFound(code))
        h.model.name = "  Mystery jam "
        #expect(await h.model.addNamed())
        let queued = try await h.queued()
        #expect(queued.count == 1)
        #expect(queued[0].data["name"] == .string("Mystery jam"))
        #expect(queued[0].data["barcode"] == .null || queued[0].data["barcode"] == nil)
        #expect(h.store.cachedName(code) == "Mystery jam")
        #expect(h.store.pending().isEmpty)
    }

    @Test("SHOP-10: a not-found name is prefilled from the cache when this device named the code before")
    func prefilled() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        h.store.cacheName(code: code, name: "Mystery jam")
        await h.model.handle(code)
        #expect(h.model.name == "Mystery jam")
    }

    @Test("SHOP-10: a blank name adds nothing")
    func blankName() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        await h.model.handle(code)
        h.model.name = "   "
        #expect(await h.model.addNamed() == false)
        #expect(try await h.queued().isEmpty)
    }

    @Test("SHOP-10: a failure that cannot pass by itself (401, 500) writes nothing and queues nothing")
    func failedWritesNothing() async throws {
        let h = try await BarcodeHarness(results: [.failed])
        await h.model.handle(code)
        #expect(h.model.state == .failed(code))
        #expect(try await h.queued().isEmpty)
        #expect(h.store.pending().isEmpty)
        #expect(await h.detail.kickCount == 0)
    }

    @Test("SHOP-10: a name typed after a failed lookup adds it with no barcode")
    func failedThenNamed() async throws {
        let h = try await BarcodeHarness(results: [.failed])
        await h.model.handle(code)
        h.model.name = "Jam"
        #expect(await h.model.addNamed())
        let queued = try await h.queued()
        #expect(queued.count == 1 && queued[0].data["name"] == .string("Jam"))
        #expect(queued[0].data["barcode"] == .null || queued[0].data["barcode"] == nil)
    }

    @Test("SHOP-10: a typed name survives a retry")
    func typedNameSurvivesRetry() async throws {
        let h = try await BarcodeHarness(results: [.failed, .failed, .notFound])
        h.store.cacheName(code: code, name: "Cached name")
        await h.model.handle(code)
        #expect(h.model.name == "Cached name")
        h.model.name = "Typed by hand"
        await h.model.retry()
        #expect(h.model.state == .failed(code))
        #expect(h.model.name == "Typed by hand")
        await h.model.retry()
        #expect(h.model.state == .notFound(code))
        #expect(h.model.name == "Typed by hand")
    }

    @Test("SHOP-10: Retry tapped twice during a lookup makes one lookup and one item")
    func retryTwice() async throws {
        let gate = BarcodeGate()
        let h = try await BarcodeHarness(results: [.failed, .found(barcodeProduct())], gate: gate, gatedFrom: 1)
        await h.model.handle("3017620422003")
        let first = Task { await h.model.retry() }
        let second = Task { await h.model.retry() }
        await h.settle { h.script.codes.count >= 2 }
        // The manual field cannot start a second lookup for the same screen either.
        h.model.code = "3017620422010"
        await h.model.submitCode()
        await gate.open()
        await first.value
        await second.value
        #expect(h.script.codes == ["3017620422003", "3017620422003"])
        #expect(h.model.state == .found(barcodeProduct()))
        #expect(await h.model.addFound())
        #expect(try await h.queued().count == 1)
    }

    @Test("SHOP-10: a failed write at the offline path returns to scanning with the error")
    func offlineWriteFails() async throws {
        let h = try await BarcodeHarness(online: false)
        await h.detail.db.close()
        await h.model.handle(code)
        #expect(h.model.addFailed)
        #expect(h.model.state == .scanning)
        #expect(h.store.pending().isEmpty)
    }

    @Test("SHOP-10: the camera reading a code looks it up, normalising UPC-A, and ignores unreadable reads")
    func cameraRead() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        await h.model.startCamera()
        #expect(h.model.state == .scanning && h.device.startCount == 1)
        h.device.scan("12ab")
        h.device.scan("0012345678905")
        await h.settle { h.model.state != .scanning }
        #expect(h.model.state == .notFound("012345678905"))
        #expect(h.script.codes == ["012345678905"])
        // The camera stops once a code is taken.
        #expect(h.device.stopCount >= 1)
    }

    @Test("SHOP-10: a code typed manually needs 8 to 14 digits")
    func manualValidation() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        h.model.code = "12345"
        await h.model.submitCode()
        #expect(h.model.codeError)
        #expect(h.script.codes.isEmpty)
        h.model.code = " 12345678 "
        await h.model.submitCode()
        #expect(!h.model.codeError)
        #expect(h.script.codes == ["12345678"])
    }

    @Test("SHOP-10: camera errors map to permission-denied, unsupported and camera-unavailable")
    func cameraStates() async throws {
        let denied = try await BarcodeHarness(device: FakeScannerDevice(authorization: .denied))
        await denied.model.startCamera()
        #expect(denied.model.state == .permissionDenied && denied.device.startCount == 0)

        let restricted = try await BarcodeHarness(device: FakeScannerDevice(authorization: .restricted))
        await restricted.model.startCamera()
        #expect(restricted.model.state == .permissionDenied)

        let asked = FakeScannerDevice(authorization: .notDetermined)
        asked.grantsAccess = false
        let refused = try await BarcodeHarness(device: asked)
        await refused.model.startCamera()
        #expect(refused.model.state == .permissionDenied)

        let none = try await BarcodeHarness(device: FakeScannerDevice(startError: .unsupported))
        await none.model.startCamera()
        #expect(none.model.state == .unsupported)

        let busy = try await BarcodeHarness(device: FakeScannerDevice(startError: .unavailable))
        await busy.model.startCamera()
        #expect(busy.model.state == .cameraUnavailable)

        // Manual entry works in every state.
        for h in [denied, none, busy] {
            h.model.code = "12345678"
            await h.model.submitCode()
            #expect(h.model.state == .notFound("12345678"))
        }
    }

    @Test("SHOP-10: the torch shows only when the camera has one, and toggles")
    func torch() async throws {
        let with = try await BarcodeHarness(device: FakeScannerDevice(hasTorch: true))
        await with.model.startCamera()
        #expect(with.model.hasTorch)
        with.model.toggleTorch()
        #expect(with.model.torchOn && with.device.torchOn)
        with.model.toggleTorch()
        #expect(!with.model.torchOn)

        let without = try await BarcodeHarness(device: FakeScannerDevice(hasTorch: false))
        await without.model.startCamera()
        #expect(!without.model.hasTorch)
    }

    @Test("SHOP-10: Scan again returns to the camera and forgets the typed name")
    func scanAgain() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        await h.model.startCamera()
        await h.model.handle("12345678")
        h.model.name = "Typed"
        await h.model.scanAgain()
        #expect(h.model.state == .scanning && h.device.startCount == 2)
        await h.model.handle("12345678")
        #expect(h.model.name.isEmpty)
    }
}

// MARK: the queued-lookup drain

@MainActor
@Suite("SHOP-10 queued lookups", .serialized)
struct BarcodeDrainTests {
    private let code = "5000159484695"

    private func request(
        _ h: BarcodeHarness, lookup: @escaping @Sendable (String) async -> ProductLookupResult, online: Bool = true
    ) -> BarcodeLookupDrainer.Request {
        BarcodeLookupDrainer.Request(
            database: h.detail.db, householdId: h.detail.household, lookup: lookup, isOnline: { online }, store: h.store
        )
    }

    /// An offline add, so the item is named after the code and one lookup is queued.
    private func queueOne(_ h: BarcodeHarness) async throws -> UUID {
        await h.model.handle(code)
        await h.settle { h.detail.model.unchecked.count == 1 }
        return try #require(h.detail.model.unchecked.first?.id)
    }

    @Test("SHOP-10: a found result renames the item, sets its barcode and drops the entry")
    func foundRenames() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        let found = barcodeProduct(code, name: "Heinz Beans", brand: nil, image: nil)
        try await BarcodeLookupDrainer().drain(request(h, lookup: { _ in .found(found) }))
        await h.settle { h.detail.model.unchecked.first?.name == "Heinz Beans" }
        #expect(h.detail.model.unchecked.map(\.name) == ["Heinz Beans"])
        #expect(h.store.pending().isEmpty)
        let patch = try #require(try await h.queued().last)
        #expect(patch.data["name"] == .string("Heinz Beans") && patch.data["barcode"] == .string(code))
    }

    @Test("SHOP-10: an item the person renamed in the meantime is left untouched")
    func renamedItemWins() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        let item = try #require(h.detail.model.unchecked.first)
        #expect(await h.detail.model.save(item, ItemFields(name: "Baked beans")))
        await h.settle { h.detail.model.unchecked.first?.name == "Baked beans" }
        let before = try await h.queued().count
        try await BarcodeLookupDrainer().drain(request(h, lookup: { _ in .found(barcodeProduct(code, name: "Heinz Beans")) }))
        #expect(h.detail.model.unchecked.map(\.name) == ["Baked beans"])
        #expect(try await h.queued().count == before)
        #expect(h.store.pending().isEmpty)
    }

    @Test("SHOP-10: a deleted item is left alone")
    func deletedItem() async throws {
        let h = try await BarcodeHarness(online: false)
        let id = try await queueOne(h)
        try await h.detail.db.remove(table: "list_item", id: id)
        try await BarcodeLookupDrainer().drain(request(h, lookup: { _ in .found(barcodeProduct(code)) }))
        #expect(h.store.pending().isEmpty)
        let last = try #require(try await h.queued().last)
        #expect(last.op == "delete")
    }

    @Test("SHOP-10: a 404 drops the entry and leaves the name; any other non-transient result drops it too")
    func notFoundDrops() async throws {
        for result in [ProductLookupResult.notFound, .failed] {
            let h = try await BarcodeHarness(online: false)
            _ = try await queueOne(h)
            try await BarcodeLookupDrainer().drain(request(h, lookup: { _ in result }))
            #expect(h.store.pending().isEmpty)
            #expect(h.detail.model.unchecked.map(\.name) == [code])
        }
    }

    @Test("SHOP-10: a network failure or a 502 keeps the entry; a device that is offline asks nothing")
    func offlineKeeps() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        try await BarcodeLookupDrainer().drain(request(h, lookup: { _ in .offline }))
        #expect(h.store.pending().count == 1)
        let asked = OSAllocatedUnfairLock(initialState: 0)
        try await BarcodeLookupDrainer().drain(request(h, lookup: { _ in
            asked.withLock { $0 += 1 }
            return .notFound
        }, online: false))
        #expect(asked.withLock { $0 } == 0 && h.store.pending().count == 1)
    }

    @Test("SHOP-10: entries of another household wait until that household is open")
    func otherHousehold() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        let other = UUID()
        h.store.appendPending(PendingLookup(householdId: other, itemId: UUID(), code: "12345678"))
        let asked = OSAllocatedUnfairLock(initialState: [String]())
        try await BarcodeLookupDrainer().drain(request(h, lookup: { code in
            asked.withLock { $0.append(code) }
            return .notFound
        }))
        #expect(asked.withLock { $0 } == [code])
        #expect(h.store.pending().map(\.householdId) == [other])
    }

    @Test("SHOP-10: a drain requested during a pass runs after it and picks up what was queued meanwhile")
    func requestDuringPass() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        let drainer = BarcodeLookupDrainer()
        let gate = BarcodeGate()
        let asked = OSAllocatedUnfairLock(initialState: [String]())
        let first = Task {
            try await drainer.drain(request(h, lookup: { code in
                asked.withLock { $0.append("first:\(code)") }
                await gate.wait()
                return .notFound
            }))
        }
        await h.settle { asked.withLock { $0.count } == 1 }
        // Queued while the first pass is held: the pass already took its snapshot of the queue.
        let second = "12345678"
        let secondId = try #require(await h.detail.model.addScanned(name: second, barcode: nil))
        h.store.appendPending(PendingLookup(householdId: h.detail.household, itemId: secondId, code: second))
        try await drainer.drain(request(h, lookup: { code in
            asked.withLock { $0.append("second:\(code)") }
            return .found(barcodeProduct(code, name: "Chickpeas"))
        }))
        // The call returned at once; nothing of the second request has run yet.
        #expect(asked.withLock { $0 } == ["first:\(code)"])
        await gate.open()
        try await first.value
        #expect(asked.withLock { $0 } == ["first:\(code)", "second:\(second)"])
        await h.settle { h.detail.model.unchecked.contains { $0.name == "Chickpeas" } }
        #expect(h.detail.model.unchecked.contains { $0.name == "Chickpeas" })
        #expect(h.store.pending().isEmpty)
    }

    @Test("SHOP-10: the follow-up pass for the same household skips what just failed transiently")
    func followUpSkipsFailed() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        let drainer = BarcodeLookupDrainer()
        let gate = BarcodeGate()
        let asked = OSAllocatedUnfairLock(initialState: 0)
        let first = Task {
            try await drainer.drain(request(h, lookup: { _ in
                asked.withLock { $0 += 1 }
                await gate.wait()
                return .offline
            }))
        }
        await h.settle { asked.withLock { $0 } == 1 }
        try await drainer.drain(request(h, lookup: { _ in
            asked.withLock { $0 += 1 }
            return .offline
        }))
        await gate.open()
        try await first.value
        #expect(asked.withLock { $0 } == 1)
        #expect(h.store.pending().count == 1)
    }

    @Test("SHOP-10: a request made during a pass still runs when that pass throws")
    func followUpAfterThrow() async throws {
        let h = try await BarcodeHarness(online: false)
        _ = try await queueOne(h)
        let drainer = BarcodeLookupDrainer()
        let gate = BarcodeGate()
        let entered = OSAllocatedUnfairLock(initialState: false)
        let first = Task {
            try await drainer.drain(request(h, lookup: { _ in
                entered.withLock { $0 = true }
                await gate.wait()
                return .found(barcodeProduct(code, name: "Heinz Beans"))
            }))
        }
        await h.settle { entered.withLock { $0 } }
        // A pass starts by asking whether the device is online, so that call shows the follow-up ran.
        let probed = OSAllocatedUnfairLock(initialState: false)
        try await drainer.drain(BarcodeLookupDrainer.Request(
            database: h.detail.db, householdId: h.detail.household, lookup: { _ in .notFound },
            isOnline: {
                probed.withLock { $0 = true }
                return true
            }, store: h.store
        ))
        // The household's database closes while the first lookup is in flight: the first pass ends in an error.
        await h.detail.db.close()
        await gate.open()
        await #expect(throws: BarcodeDrainError.self) { try await first.value }
        #expect(probed.withLock { $0 })
        // The drainer is free again.
        try await drainer.drain(request(h, lookup: { _ in .notFound }, online: false))
    }
}
