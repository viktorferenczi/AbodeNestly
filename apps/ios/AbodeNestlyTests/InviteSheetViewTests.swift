import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core

private let webBase = URL(string: "http://localhost:5173")!

@MainActor
private struct Harness {
    let householdId = UUID()
    let api = FakeApiService()
    let model: InviteSheetModel
    let copied: CopiedBox

    final class CopiedBox { var values: [String] = [] }

    init() {
        let box = CopiedBox()
        copied = box
        model = InviteSheetModel(householdId: householdId, api: api, copy: { box.values.append($0) })
    }

    func view(online: Bool = true) -> InviteSheetView {
        InviteSheetView(model: model, isOnline: online, webBaseURL: webBase)
    }

    func inspect(online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try view(online: online).inspect()
    }

    func has(_ id: String, online: Bool = true) -> Bool {
        (try? inspect(online: online).find(viewWithAccessibilityIdentifier: id)) != nil
    }

    func isDisabled(_ id: String, online: Bool = true) throws -> Bool {
        try inspect(online: online).find(viewWithAccessibilityIdentifier: id).isDisabled()
    }

    func text(_ id: String, online: Bool = true) throws -> String {
        try inspect(online: online).find(viewWithAccessibilityIdentifier: id).text().string()
    }
}

@MainActor
@Suite("CORE-03 invite sheet")
struct InviteSheetViewTests {
    @Test("CORE-03: AC1 shows the code, uses left and expiry, enabled buttons, no banner")
    func ready() async throws {
        let h = Harness()
        let invite = makeInvite(code: "abc123def456")
        h.api.setLiveInviteResult(.success(invite))
        await h.model.loadIfNeeded()
        #expect(try h.text("invite.code") == "abc123def456")
        let meta = try h.text("invite.meta")
        #expect(meta.hasPrefix("10 uses left · Expires "))
        for id in ["invite.copy", "invite.newCode", "invite.share"] { #expect(try h.isDisabled(id) == false, "\(id)") }
        #expect(!h.has("offline-banner"))
        #expect(!h.has("invite.error"))
    }

    @Test("CORE-03: loading shows a placeholder and Share is disabled")
    func loading() throws {
        let h = Harness()
        #expect(h.has("invite.loading"))
        #expect(try h.isDisabled("invite.share"))
    }

    @Test("CORE-03: AC2 New code shows the new code, drops the old one and retires it")
    func newCodeTap() async throws {
        let h = Harness()
        let old = makeInvite(code: "aaaaaaaaaaaa"), new = makeInvite(code: "bbbbbbbbbbbb")
        h.api.setLiveInviteResult(.success(old))
        h.api.setCreateInviteResult(.success(new))
        await h.model.loadIfNeeded()
        try h.inspect().find(viewWithAccessibilityIdentifier: "invite.newCode").button().tap()
        await waitFor { h.model.state == .ready(new) }
        #expect(try h.text("invite.code") == "bbbbbbbbbbbb")
        #expect(h.api.calls.contains(.retireInvite(id: old.id)))
    }

    @Test("CORE-03: AC2 while rotating, Share, Copy and New code are disabled and a spinner shows")
    func rotating() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.success(makeInvite()))
        await h.model.loadIfNeeded()
        h.api.hold()
        h.api.setCreateInviteResult(.success(makeInvite(code: "bbbbbbbbbbbb")))
        let task = Task { await h.model.newCode() }
        await waitFor { h.model.isRotating }
        #expect(h.has("invite.rotating"))
        for id in ["invite.copy", "invite.newCode", "invite.share"] { #expect(try h.isDisabled(id), "\(id)") }
        h.api.release()
        await task.value
    }

    @Test("CORE-03: AC3 Share is a ShareLink for <web>/join/<code>")
    func share() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.success(makeInvite(code: "abc123def456")))
        await h.model.loadIfNeeded()
        let view = h.view()
        #expect(view.shareURL?.absoluteString == "http://localhost:5173/join/abc123def456")
        #expect(h.has("invite.share"))
        #expect(try h.isDisabled("invite.share") == false)
    }

    @Test("CORE-03: AC5 offline shows the banner, disables New code and Share, makes no API calls")
    func offline() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.success(makeInvite()))
        await h.model.loadIfNeeded() // an invite loaded earlier stays on screen
        let calls = h.api.calls
        #expect(h.has("offline-banner", online: false))
        #expect(try h.isDisabled("invite.newCode", online: false))
        #expect(try h.isDisabled("invite.share", online: false))
        #expect(h.api.calls == calls)
    }

    @Test("CORE-03: AC5 offline without an invite says to connect and calls nothing")
    func offlineNoInvite() throws {
        let h = Harness()
        #expect(h.has("offline-banner", online: false))
        let view = try h.inspect(online: false)
        _ = try view.find(text: "Connect to see the invite code.")
        #expect(try h.isDisabled("invite.share", online: false))
        #expect(h.api.calls.isEmpty)
    }

    @Test("CORE-03: a load error shows the message and Try again, which reloads")
    func loadError() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.failure(.network))
        await h.model.loadIfNeeded()
        _ = try h.inspect().find(text: "Couldn't load the invite code. Try again.")
        let live = makeInvite()
        h.api.setLiveInviteResult(.success(live))
        try h.inspect().find(viewWithAccessibilityIdentifier: "invite.retry").button().tap()
        await waitFor { h.model.state == .ready(live) }
        #expect(h.model.state == .ready(live))
    }

    @Test("CORE-03: a failed New code keeps the old code visible with the reassurance")
    func newCodeError() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.success(makeInvite(code: "aaaaaaaaaaaa")))
        h.api.setCreateInviteResult(.failure(.network))
        await h.model.loadIfNeeded()
        await h.model.newCode()
        #expect(try h.text("invite.code") == "aaaaaaaaaaaa")
        _ = try h.inspect().find(text: "Couldn't create a new code. The current code still works.")
    }

    @Test("CORE-03: a failed retire shows the old-code warning")
    func oldCodeWarning() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.success(makeInvite()))
        h.api.setCreateInviteResult(.success(makeInvite(code: "bbbbbbbbbbbb")))
        h.api.setRetireResult(.failure(.unknown))
        await h.model.loadIfNeeded()
        await h.model.newCode()
        _ = try h.inspect().find(text: "The new code is ready, but the old code may still work.")
    }

    @Test("CORE-03: tapping Copy copies the code and shows Code copied")
    func copyTap() async throws {
        let h = Harness()
        h.api.setLiveInviteResult(.success(makeInvite(code: "abc123def456")))
        await h.model.loadIfNeeded()
        try h.inspect().find(viewWithAccessibilityIdentifier: "invite.copy").button().tap()
        #expect(h.copied.values == ["abc123def456"])
        #expect(try h.text("invite.notice") == "Code copied")
    }

    @Test("CORE-03: owners and members get the same Invite button (MembersView has no role input)")
    func membersInvite() throws {
        let view = MembersView(householdId: UUID(), api: FakeApiService(), isOnline: true, webBaseURL: webBase)
        let inspected = try view.inspect()
        _ = try inspected.find(text: "Members")
        let button = try inspected.find(viewWithAccessibilityIdentifier: "members.invite").button()
        #expect(try button.isDisabled() == false)
        // ViewInspector cannot observe `@State` without hosting the view, so the sheet that the tap presents
        // is covered by the InviteSheetView cases above and by the E2E journey.
        #expect(try inspected.find(text: "Invite") != nil)
    }
}
