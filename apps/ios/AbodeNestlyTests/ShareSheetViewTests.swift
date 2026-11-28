import Foundation
import SwiftUI
import Testing
import UIKit
import ViewInspector
@testable import Core
@testable import Shopping
@testable import Sync
@testable import UI

/// SHOP-13 component tests: DESIGN C8 states (off, on, offline) and interactions (switch, Copy, Stop sharing).
@MainActor
@Suite("SHOP-13 share sheet view", .serialized)
struct ShareSheetViewTests {
    private func inspect(_ h: ShareHarness, online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try ShareSheetView(model: h.sheet, isOnline: online, onClose: {}).inspect()
    }

    private func has(_ h: ShareHarness, _ id: String, online: Bool = true) -> Bool {
        (try? inspect(h, online: online).find(viewWithAccessibilityIdentifier: id)) != nil
    }

    private func text(_ h: ShareHarness, _ id: String, online: Bool = true) throws -> String {
        try inspect(h, online: online).find(viewWithAccessibilityIdentifier: id).text().string()
    }

    @Test("SHOP-13: AC1 sharing off shows the switch off, enabled, and no link")
    func off() async throws {
        let h = try await ShareHarness()
        let toggle = try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle()
        #expect(try !toggle.isOn())
        #expect(try !toggle.isDisabled())
        _ = try inspect(h).find(text: "Share with a link")
        for id in ["share.link", "share.copy", "share.stop", "share.unchecked", "share.offline", "share.error"] {
            #expect(!has(h, id), "\(id)")
        }
    }

    @Test("SHOP-13: AC2 turning the switch on shows the link, Copy, Share, the unchecked-items line and Stop sharing")
    func on() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.success("http://api.test/shared/tok123"))
        try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().tap()
        await h.detail.settle { h.sheet.state.isOn }
        #expect(h.api.calls == [.shareList(listId: h.list)])
        #expect(try text(h, "share.link") == "http://localhost:5173/shared/tok123")
        #expect(try text(h, "share.unchecked") == "Anyone with the link can view unchecked items")
        #expect(try text(h, "share.stopHint") == "The current link will stop working")
        for id in ["share.copy", "share.system", "share.stop"] { #expect(has(h, id), "\(id)") }
        #expect(try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isOn())
    }

    @Test("SHOP-13: a local share_token shows the sheet on at once")
    func startsOn() async throws {
        let h = try await ShareHarness(token: "tok123")
        #expect(try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isOn())
        #expect(try text(h, "share.link") == "http://localhost:5173/shared/tok123")
    }

    @Test("SHOP-13: AC3 Stop sharing DELETEs and the switch returns to off with the link gone")
    func stop() async throws {
        let h = try await ShareHarness(token: "tok123")
        try inspect(h).find(viewWithAccessibilityIdentifier: "share.stop").button().tap()
        await h.detail.settle { !h.sheet.state.isOn }
        #expect(h.api.calls == [.stopSharing(listId: h.list)])
        #expect(try !inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isOn())
        #expect(!has(h, "share.link") && !has(h, "share.stop"))
    }

    @Test("SHOP-13: turning the switch off from on is Stop sharing")
    func switchOff() async throws {
        let h = try await ShareHarness(token: "tok123")
        try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().tap()
        await h.detail.settle { !h.sheet.state.isOn }
        #expect(h.api.calls == [.stopSharing(listId: h.list)])
    }

    @Test("SHOP-13: Copy link hands the link to the clipboard and shows Link copied")
    func copy() async throws {
        let h = try await ShareHarness(token: "tok123")
        #expect(!has(h, "share.copied"))
        try inspect(h).find(viewWithAccessibilityIdentifier: "share.copy").button().tap()
        #expect(h.copied.values == ["http://localhost:5173/shared/tok123"])
        #expect(try text(h, "share.copied") == "Link copied")
    }

    @Test("SHOP-13: Copy link sets the system pasteboard")
    func pasteboard() async throws {
        let model = ShareSheetModel(
            initialToken: "tok123", webBase: shareWebBase, share: { .failure(.failed) }, stop: { .failure(.failed) },
            record: { _ in }, isOnline: { true }
        )
        try ShareSheetView(model: model, isOnline: true, onClose: {}).inspect()
            .find(viewWithAccessibilityIdentifier: "share.copy").button().tap()
        #expect(UIPasteboard.general.string == "http://localhost:5173/shared/tok123")
    }

    @Test("SHOP-13: AC4 offline the switch is disabled and says Sharing needs a connection")
    func offline() async throws {
        let h = try await ShareHarness(online: false)
        #expect(try inspect(h, online: false).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isDisabled())
        #expect(try text(h, "share.offline", online: false) == "Sharing needs a connection")
        #expect(h.api.calls.isEmpty)
    }

    @Test("SHOP-13: AC4 offline with a known link, Stop sharing is disabled while the link stays visible and copyable")
    func offlineWithLink() async throws {
        let h = try await ShareHarness(token: "tok123", online: false)
        #expect(try inspect(h, online: false).find(viewWithAccessibilityIdentifier: "share.stop").button().isDisabled())
        #expect(try inspect(h, online: false).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isDisabled())
        #expect(try text(h, "share.link", online: false) == "http://localhost:5173/shared/tok123")
        #expect(try !inspect(h, online: false).find(viewWithAccessibilityIdentifier: "share.copy").button().isDisabled())
    }

    @Test("SHOP-13: online the offline line is not shown")
    func online() async throws {
        let h = try await ShareHarness()
        #expect(!has(h, "share.offline"))
    }

    @Test("SHOP-13: a failed turn on returns the switch to off and says so")
    func turnOnFails() async throws {
        let h = try await ShareHarness()
        h.api.setShareResult(.failure(.unknown))
        try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().tap()
        await h.detail.settle { h.sheet.state.error != nil }
        #expect(try text(h, "share.error") == "Could not turn on sharing. Try again.")
        #expect(try !inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isOn())
    }

    @Test("SHOP-13: a failed Stop sharing keeps the switch on and says so")
    func stopFails() async throws {
        let h = try await ShareHarness(token: "tok123")
        h.api.setStopResult(.failure(.unknown))
        try inspect(h).find(viewWithAccessibilityIdentifier: "share.stop").button().tap()
        await h.detail.settle { h.sheet.state.error != nil }
        #expect(try text(h, "share.error") == "Could not stop sharing. Try again.")
        #expect(try inspect(h).find(viewWithAccessibilityIdentifier: "share.switch").toggle().isOn())
        #expect(has(h, "share.link"))
    }

    @Test("SHOP-13: Share is the first item of the List actions menu with nothing checked, on a list and on a template")
    func menuEntry() async throws {
        let h = try await ShareHarness()
        #expect(h.detail.model.checked.isEmpty && !h.detail.model.isTemplate)
        #expect(Templates.actions(isTemplate: h.detail.model.isTemplate, hasChecked: !h.detail.model.checked.isEmpty) == [.share])
        #expect(Templates.actions(isTemplate: true, hasChecked: false).first == .share)
    }
}
