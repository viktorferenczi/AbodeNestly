import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync

/// Records that the screen asked to close.
@MainActor
private final class Closed {
    var count = 0
}

@MainActor
private func inspect(_ h: BarcodeHarness, closed: Closed = Closed()) throws -> InspectableView<ViewType.ClassifiedView> {
    try BarcodeScannerView(model: h.model, onClose: { closed.count += 1 }).inspect()
}

@MainActor
private func has(_ h: BarcodeHarness, _ id: String) -> Bool {
    (try? inspect(h).find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
private func text(_ h: BarcodeHarness, _ id: String) throws -> String {
    try inspect(h).find(viewWithAccessibilityIdentifier: id).text().string()
}

/// DESIGN C7, one case per state and interaction.
@MainActor
@Suite("SHOP-10 scanner view", .serialized)
struct BarcodeViewTests {
    private let code = "3017620422003"

    @Test("SHOP-10: AC1 scanning shows the guide box, the hint and the manual field")
    func scanning() async throws {
        let h = try await BarcodeHarness()
        await h.model.startCamera()
        #expect(has(h, "barcode.preview") && has(h, "barcode.guide"))
        #expect(try text(h, "barcode.hint") == "Point the camera at a barcode")
        #expect(has(h, "barcode.manual.field") && has(h, "barcode.close"))
        #expect(!has(h, "barcode.nocamera.title"))
    }

    @Test("SHOP-10: the torch toggle appears only when the camera has a torch")
    func torch() async throws {
        let with = try await BarcodeHarness(device: FakeScannerDevice(hasTorch: true))
        await with.model.startCamera()
        #expect(has(with, "barcode.torch"))
        try inspect(with).find(viewWithAccessibilityIdentifier: "barcode.torch").button().tap()
        #expect(with.model.torchOn && with.device.torchOn)

        let without = try await BarcodeHarness(device: FakeScannerDevice(hasTorch: false))
        await without.model.startCamera()
        #expect(!has(without, "barcode.torch"))
    }

    @Test("SHOP-10: AC2 found shows the name, brand and image; Add puts Nutella on the list and closes")
    func found() async throws {
        let h = try await BarcodeHarness(results: [.found(barcodeProduct(code))])
        await h.model.handle(code)
        #expect(try text(h, "barcode.found.name") == "Nutella")
        #expect(try text(h, "barcode.found.brand") == "Ferrero")
        #expect(has(h, "barcode.image"))
        let closed = Closed()
        try inspect(h, closed: closed).find(viewWithAccessibilityIdentifier: "barcode.add").button().tap()
        await h.settle { closed.count == 1 }
        #expect(closed.count == 1)
        await h.settle { h.detail.model.unchecked.count == 1 }
        #expect(h.detail.model.unchecked.map(\.name) == ["Nutella"])
        let queued = try await h.queued()
        #expect(queued.count == 1 && queued[0].data["barcode"] == .string(code))
    }

    @Test("SHOP-10: found without an image, or with a non-https one, shows no image")
    func foundWithoutImage() async throws {
        for image in [nil, "http://img.test/a.jpg"] {
            let h = try await BarcodeHarness(results: [.found(barcodeProduct(code, brand: nil, image: image))])
            await h.model.handle(code)
            #expect(!has(h, "barcode.image"))
            #expect(!has(h, "barcode.found.brand"))
            #expect(try text(h, "barcode.found.name") == "Nutella")
        }
    }

    @Test("SHOP-10: AC3 not-found shows the name field; typing a name and tapping Add adds it")
    func notFound() async throws {
        let h = try await BarcodeHarness(results: [.notFound])
        await h.model.handle(code)
        #expect(try text(h, "barcode.notfound.title") == "We don't know this product")
        #expect(try text(h, "barcode.code") == code)
        #expect(has(h, "barcode.name.field"))
        #expect(try inspect(h).find(viewWithAccessibilityIdentifier: "barcode.add").button().isDisabled())
        h.model.name = "Mystery jam"
        let closed = Closed()
        try inspect(h, closed: closed).find(viewWithAccessibilityIdentifier: "barcode.add").button().tap()
        await h.settle { closed.count == 1 }
        #expect(closed.count == 1)
        await h.settle { h.detail.model.unchecked.count == 1 }
        #expect(h.detail.model.unchecked.map(\.name) == ["Mystery jam"])
    }

    @Test("SHOP-10: AC5 permission-denied shows its message, and a typed code still looks up")
    func permissionDenied() async throws {
        let h = try await BarcodeHarness(device: FakeScannerDevice(authorization: .denied), results: [.notFound])
        await h.model.startCamera()
        #expect(try text(h, "barcode.nocamera.title") == "Camera access is off")
        #expect(has(h, "barcode.manual.field"))
        #expect(!has(h, "barcode.preview"))
        h.model.code = code
        try inspect(h).find(viewWithAccessibilityIdentifier: "barcode.manual.submit").button().tap()
        await h.settle { h.model.state != .permissionDenied }
        #expect(h.model.state == .notFound(code))
        #expect(has(h, "barcode.notfound.title"))
    }

    @Test("SHOP-10: AC4 offline shows that the item was added and the lookup is queued")
    func queued() async throws {
        let h = try await BarcodeHarness(online: false)
        await h.model.handle(code)
        #expect(try text(h, "barcode.queued") == "Added \(code). We'll look it up when you're back online")
        #expect(has(h, "barcode.again") && has(h, "barcode.done"))
        let closed = Closed()
        try inspect(h, closed: closed).find(viewWithAccessibilityIdentifier: "barcode.done").button().tap()
        #expect(closed.count == 1)
    }

    @Test("SHOP-10: unsupported and camera-unavailable each show their copy and the manual field")
    func noCamera() async throws {
        let none = try await BarcodeHarness(device: FakeScannerDevice(startError: .unsupported))
        await none.model.startCamera()
        #expect(try text(none, "barcode.nocamera.title") == "Scanning isn't available on this device")
        #expect(try text(none, "barcode.nocamera.hint") == "You can still enter the code printed under the barcode.")
        #expect(has(none, "barcode.manual.field"))

        let busy = try await BarcodeHarness(device: FakeScannerDevice(startError: .unavailable))
        await busy.model.startCamera()
        #expect(try text(busy, "barcode.nocamera.title") == "Can't use the camera")
        #expect(has(busy, "barcode.manual.field"))
    }

    @Test("SHOP-10: a code that is not 8 to 14 digits shows the error and looks nothing up")
    func manualError() async throws {
        let h = try await BarcodeHarness()
        await h.model.startCamera()
        h.model.code = "123"
        try inspect(h).find(viewWithAccessibilityIdentifier: "barcode.manual.submit").button().tap()
        await h.settle { h.model.codeError }
        #expect(try text(h, "barcode.manual.error") == "Enter 8 to 14 digits")
        #expect(h.script.codes.isEmpty)
    }

    @Test("SHOP-10: a failed lookup shows Try again with Retry and a name field; Retry tapped twice makes one lookup")
    func failedRetryTwice() async throws {
        let gate = BarcodeGate()
        let h = try await BarcodeHarness(results: [.failed, .notFound], gate: gate, gatedFrom: 1)
        await h.model.handle(code)
        #expect(try text(h, "barcode.failed.title") == "Couldn't look that up. Try again.")
        #expect(has(h, "barcode.retry") && has(h, "barcode.name.field"))
        let view = try inspect(h)
        try view.find(viewWithAccessibilityIdentifier: "barcode.retry").button().tap()
        try view.find(viewWithAccessibilityIdentifier: "barcode.retry").button().tap()
        await h.settle { h.script.codes.count >= 2 }
        await gate.open()
        await h.settle { h.model.state == .notFound(code) }
        #expect(h.script.codes == [code, code])
        #expect(try await h.queued().isEmpty)
    }

    @Test("SHOP-10: the item could not be stored shows the error")
    func addFailed() async throws {
        let h = try await BarcodeHarness(results: [.found(barcodeProduct(code))])
        await h.model.handle(code)
        await h.detail.db.close()
        #expect(await h.model.addFound() == false)
        #expect(try text(h, "barcode.error") == "Couldn't add that item. Try again.")
    }

    @Test("SHOP-10: the list shows a Scan barcode button only when a scanner is given")
    func scanButton() async throws {
        let h = try await BarcodeHarness()
        let without = try ListDetailView(model: h.detail.model, title: "Groceries", isOnline: true).inspect()
        #expect((try? without.find(viewWithAccessibilityIdentifier: "quickadd.scan")) == nil)
        let model = h.detail.model
        let device = h.device
        let with = try ListDetailView(
            model: model, title: "Groceries", isOnline: true,
            makeScanner: { model.makeScanner(device: device, lookup: { _ in .notFound }, isOnline: { true }) }
        ).inspect()
        _ = try with.find(viewWithAccessibilityIdentifier: "quickadd.scan").button()
    }
}
