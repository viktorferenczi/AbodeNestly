import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core

@MainActor
private struct Harness {
    let base: ProfileHarness
    var model: ProfileModel { base.model }

    init(online: Bool = true) async { base = await ProfileHarness(online: online) }

    func view(online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try ProfileView(model: model, isOnline: online).inspect()
    }

    func has(_ id: String, online: Bool = true) -> Bool {
        (try? view(online: online).find(viewWithAccessibilityIdentifier: id)) != nil
    }

    func text(_ id: String) throws -> String {
        try view().find(viewWithAccessibilityIdentifier: id).text().string()
    }
}

@MainActor
@Suite("CORE-06 profile screen")
struct ProfileViewTests {
    @Test("CORE-06: AC1 idle shows avatar, name, read-only email and the notifications link, with no banner")
    func idle() async throws {
        let h = await Harness()
        let view = try h.view()
        _ = try view.find(viewWithAccessibilityIdentifier: "profile.avatar")
        #expect(try view.find(viewWithAccessibilityIdentifier: "profile.name").textField().input() == "Ada")
        #expect(try h.text("profile.email") == "ada@test.local")
        _ = try view.find(viewWithAccessibilityIdentifier: "profile.notifications")
        _ = try view.find(text: "Notification preferences")
        #expect(!h.has("offline-banner"))
        #expect(!h.has("profile.avatar.status"))
        #expect(!h.has("profile.problem"))
    }

    @Test("CORE-06: the email is plain text, not an input")
    func emailReadOnly() async throws {
        let h = await Harness()
        #expect((try? h.view().find(viewWithAccessibilityIdentifier: "profile.email").textField()) == nil)
    }

    @Test("CORE-06: AC3 a held upload shows the uploading-avatar status")
    func uploading() async throws {
        let h = await Harness()
        h.base.auth.holdUpload()
        h.model.pickAvatar(makeImageData())
        #expect(try h.text("profile.avatar.status") == "Uploading photo…")
        while h.base.uploadCalls.isEmpty { await Task.yield() }
        #expect(try h.text("profile.avatar.status") == "Uploading photo…")
        h.base.auth.releaseUpload()
        await h.model.settled()
        #expect(!h.has("profile.avatar.status"))
    }

    @Test("CORE-06: AC4 offline shows the banner, queues a name edit and a photo, and never shows a failure")
    func offline() async throws {
        let h = await Harness(online: false)
        _ = try h.view(online: false).find(text: "You're offline. Changes will sync when you reconnect.")
        let field = try h.view(online: false).find(viewWithAccessibilityIdentifier: "profile.name")
        try field.textField().setInput("Ada Lovelace")
        try field.callOnSubmit()
        h.model.pickAvatar(makeImageData())
        await h.model.settled()
        #expect(h.model.profile?.displayName == "Ada Lovelace")
        #expect(h.base.outbox.read(userId: h.base.user).count == 1)
        #expect(try h.view(online: false).find(viewWithAccessibilityIdentifier: "profile.avatar.status").text().string()
            == "Photo will upload when you're online")
        #expect(!h.has("profile.problem", online: false))
        #expect(h.base.updateCalls.isEmpty && h.base.uploadCalls.isEmpty)
    }

    @Test("CORE-06: submitting the name commits it and drains when online")
    func nameCommit() async throws {
        let h = await Harness()
        let field = try h.view().find(viewWithAccessibilityIdentifier: "profile.name")
        try field.textField().setInput("Ada L")
        try field.callOnSubmit()
        await h.model.settled()
        #expect(h.base.session.profile?.displayName == "Ada L")
        #expect(h.base.updateCalls.count == 1)
    }

    @Test("CORE-06: the avatar control is labelled for VoiceOver and a failed save shows the problem text")
    func avatarAndProblem() async throws {
        let h = await Harness()
        let avatar = try h.view().find(viewWithAccessibilityIdentifier: "profile.avatar")
        #expect(try avatar.accessibilityLabel().string() == "Change photo")
        h.base.auth.setUpdateProfileResult(.failure(.unknown))
        h.model.commitName("Bad name")
        await h.model.settled()
        #expect(try h.text("profile.problem") == ProfileModel.rejectedMessage)
    }

    @Test("CORE-06: AC5 sign out ends the session")
    func signOut() async throws {
        let h = await Harness()
        try h.view().find(viewWithAccessibilityIdentifier: "profile.signOut").button().tap()
        while h.base.session.status != .signedOut { await Task.yield() }
        #expect(h.base.auth.calls.contains(.signOut))
        #expect(h.base.session.status == .signedOut)
    }
}
