import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core

@MainActor
private struct Harness {
    let api = FakeApiService()
    let model: WelcomeModel
    let landed: LandedBox

    /// Collects `onLanded` outcomes.
    final class LandedBox { var outcomes: [WelcomeOutcome] = [] }

    init() {
        let box = LandedBox()
        landed = box
        model = WelcomeModel(api: api) { box.outcomes.append($0) }
    }

    func view(online: Bool = true, name: String? = "Ada") throws -> InspectableView<ViewType.ClassifiedView> {
        try WelcomeView(model: model, displayName: name, isOnline: online, onSignOut: {}).inspect()
    }

    func isDisabled(_ id: String, online: Bool = true) throws -> Bool {
        try view(online: online).find(viewWithAccessibilityIdentifier: id).isDisabled()
    }

    func has(_ id: String, online: Bool = true) -> Bool {
        (try? view(online: online).find(viewWithAccessibilityIdentifier: id)) != nil
    }
}

@MainActor
@Suite("CORE-02 welcome screen")
struct WelcomeViewTests {
    @Test("CORE-02: AC1 idle shows the greeting, both cards and sign-out; enabled, no banner")
    func idle() throws {
        let h = Harness()
        let view = try h.view()
        _ = try view.find(text: "Welcome, Ada")
        _ = try view.find(text: "Create a household")
        _ = try view.find(text: "Join with a code")
        _ = try view.find(viewWithAccessibilityIdentifier: "welcome.name")
        _ = try view.find(viewWithAccessibilityIdentifier: "welcome.code")
        _ = try view.find(button: "Create household")
        _ = try view.find(button: "Join household")
        _ = try view.find(button: "Sign out")
        #expect(try h.isDisabled("welcome.create") == false)
        #expect(try h.isDisabled("welcome.join") == false)
        #expect(!h.has("offline-banner"))
        #expect(!h.has("welcome.creating") && !h.has("welcome.joining"))
        #expect(!h.has("welcome.createError") && !h.has("welcome.joinError"))
    }

    @Test("CORE-02: without a profile name the greeting is plain")
    func noName() throws {
        _ = try Harness().view(name: nil).find(text: "Welcome")
    }

    @Test("CORE-02: AC2 creating shows a spinner and disables both buttons")
    func creating() async throws {
        let h = Harness()
        h.api.hold()
        h.model.name = "Test Home"
        let task = Task { await h.model.create() }
        await waitFor { h.model.state == .creating }
        #expect(h.has("welcome.creating"))
        #expect(!h.has("welcome.joining"))
        #expect(try h.isDisabled("welcome.create"))
        #expect(try h.isDisabled("welcome.join"))
        h.api.setCreateResult(.success(makeHousehold()))
        h.api.release()
        await task.value
    }

    @Test("CORE-02: joining shows a spinner on Join and disables both buttons")
    func joining() async throws {
        let h = Harness()
        h.api.hold()
        h.model.code = "ABC123"
        let task = Task { await h.model.join() }
        await waitFor { h.model.state == .joining }
        #expect(h.has("welcome.joining"))
        #expect(!h.has("welcome.creating"))
        #expect(try h.isDisabled("welcome.create"))
        #expect(try h.isDisabled("welcome.join"))
        h.api.release()
        await task.value
    }

    @Test("CORE-02: AC3 a create error is shown and the typed name is kept")
    func createError() async throws {
        let h = Harness()
        h.api.setCreateResult(.failure(.network))
        h.model.name = "Test Home"
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.create").button().tap()
        await waitFor { h.model.state == .createError(.network) }
        _ = try h.view().find(text: "Couldn't reach the server. Check your connection and try again.")
        #expect(h.has("welcome.createError"))
        #expect(h.model.name == "Test Home")
        #expect(try h.isDisabled("welcome.create") == false)
        #expect(h.landed.outcomes.isEmpty)
    }

    @Test("CORE-02: AC4 offline shows the banner and disables both buttons")
    func offline() throws {
        let h = Harness()
        let view = try h.view(online: false)
        _ = try view.find(text: "You're offline. Connect to create or join a household.")
        #expect(h.has("offline-banner", online: false))
        #expect(try h.isDisabled("welcome.create", online: false))
        #expect(try h.isDisabled("welcome.join", online: false))
    }

    @Test("CORE-02: AC5 an unknown code shows error-invalid-code and keeps the typed code")
    func invalidCode() async throws {
        let h = Harness()
        h.api.setAcceptResult(.failure(.invalidCode))
        h.model.code = "nope"
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.join").button().tap()
        await waitFor { h.model.state == .errorInvalidCode }
        _ = try h.view().find(text: "That code isn't valid or has expired. Check it and try again.")
        #expect(h.model.code == "nope")
        #expect(try h.isDisabled("welcome.join") == false)
    }

    @Test("CORE-02: other join failures show the generic message")
    func joinGenericError() async throws {
        let h = Harness()
        h.api.setAcceptResult(.failure(.unknown))
        h.model.code = "abc"
        await h.model.join()
        #expect(h.model.state == .joinError(.unknown))
        _ = try h.view().find(text: "Something went wrong. Try again.")
    }

    @Test("CORE-02: tapping Create calls createHousehold with the trimmed name and reports the household")
    func createTap() async throws {
        let h = Harness()
        let household = makeHousehold()
        h.api.setCreateResult(.success(household))
        h.model.name = " Test Home "
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.create").button().tap()
        await waitFor { !h.landed.outcomes.isEmpty }
        #expect(h.api.calls == [.createHousehold(name: "Test Home")])
        #expect(h.landed.outcomes == [.created(household)])
    }

    @Test("CORE-02: tapping Join calls acceptInvite with the trimmed, lowercased code and reports the household id")
    func joinTap() async throws {
        let h = Harness()
        let id = UUID()
        h.api.setAcceptResult(.success(id))
        h.model.code = " ABC123 "
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.join").button().tap()
        await waitFor { !h.landed.outcomes.isEmpty }
        #expect(h.api.calls == [.acceptInvite(code: "abc123")])
        #expect(h.landed.outcomes == [.joined(householdId: id)])
    }

    @Test("CORE-02: an empty name is a validation error without calling the API")
    func emptyName() async throws {
        let h = Harness()
        h.model.name = "   "
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.create").button().tap()
        await waitFor { h.model.state == .createError(.validation) }
        _ = try h.view().find(text: "Enter a name for your household.")
        #expect(h.api.calls.isEmpty)
    }

    @Test("CORE-02: an empty code is a validation error without calling the API")
    func emptyCode() async throws {
        let h = Harness()
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.join").button().tap()
        await waitFor { h.model.state == .joinError(.validation) }
        _ = try h.view().find(text: "Enter your invite code.")
        #expect(h.api.calls.isEmpty)
    }

    @Test("CORE-02: pressing Go on the keyboard submits each card")
    func keyboardSubmit() async throws {
        let h = Harness()
        h.api.setCreateResult(.success(makeHousehold()))
        h.model.name = "Test Home"
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.name").callOnSubmit()
        await waitFor { h.api.calls.count == 1 }
        #expect(h.api.calls == [.createHousehold(name: "Test Home")])

        h.model.code = "ABC123"
        try h.view().find(viewWithAccessibilityIdentifier: "welcome.code").callOnSubmit()
        await waitFor { h.api.calls.count == 2 }
        #expect(h.api.calls.last == .acceptInvite(code: "abc123"))
    }

    @Test("CORE-02: the field's hint carries its error for VoiceOver")
    func errorHint() async throws {
        let h = Harness()
        h.model.name = ""
        await h.model.create()
        let hint = try h.view().find(viewWithAccessibilityIdentifier: "welcome.name").accessibilityHint().string()
        #expect(hint == "Enter a name for your household.")
    }
}
