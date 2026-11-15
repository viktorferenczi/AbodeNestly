import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core

@MainActor
private struct ViewHarness {
    let base: SettingsHarness
    var model: HouseholdSettingsModel { base.model }
    var api: FakeApiService { base.api }

    init(role: Member.Role = .owner) {
        base = SettingsHarness(role: role)
    }

    func inspect(online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try HouseholdSettingsView(model: model, isOnline: online).inspect()
    }

    func has(_ id: String, online: Bool = true) -> Bool {
        (try? inspect(online: online).find(viewWithAccessibilityIdentifier: id)) != nil
    }

    func isDisabled(_ id: String, online: Bool = true) throws -> Bool {
        try inspect(online: online).find(viewWithAccessibilityIdentifier: id).isDisabled()
    }

    func toggle(_ module: ModuleKey) throws -> InspectableView<ViewType.Toggle> {
        try inspect().find(viewWithAccessibilityIdentifier: "settings.module.\(module.rawValue)").toggle()
    }

    func sheet() throws -> InspectableView<ViewType.ClassifiedView> {
        try LeaveHouseholdSheet(model: model, isOnline: true).inspect()
    }

    func sheetHas(_ id: String) -> Bool {
        (try? sheet().find(viewWithAccessibilityIdentifier: id)) != nil
    }
}

@MainActor
@Suite("CORE-05 household settings screen")
struct HouseholdSettingsViewTests {
    @Test("CORE-05: AC1 owner sees the name, three enabled switches and the currency picker")
    func ownerState() async throws {
        let h = ViewHarness()
        #expect(h.has("settings.loading"))
        await h.model.load()
        let view = try h.inspect()
        #expect(try view.find(viewWithAccessibilityIdentifier: "settings.name").textField().input() == "Test Home")
        for module in ModuleKey.allCases {
            #expect(try h.isDisabled("settings.module.\(module.rawValue)") == false)
            #expect(try h.toggle(module).isOn())
            _ = try view.find(text: module.summary)
        }
        #expect(try h.isDisabled("settings.currency") == false)
        #expect(try h.isDisabled("settings.name") == false)
        #expect(try h.isDisabled("settings.leave") == false)
        #expect(!h.has("offline-banner"))
        #expect(!h.has("settings.saving"))
    }

    @Test("CORE-05: AC2 a member sees read-only fields but can still leave")
    func memberState() async throws {
        let h = ViewHarness(role: .member)
        await h.model.load()
        #expect(try h.isDisabled("settings.name"))
        for module in ModuleKey.allCases { #expect(try h.isDisabled("settings.module.\(module.rawValue)")) }
        #expect(try h.isDisabled("settings.currency"))
        #expect(try h.isDisabled("settings.leave") == false)
    }

    @Test("CORE-05: AC3 while saving the status shows and every field is disabled")
    func savingState() async throws {
        let h = ViewHarness()
        await h.model.load()
        h.api.setUpdateModulesResult(.success(makeHousehold(id: h.base.householdId)))
        h.api.hold()
        let task = Task { await h.model.toggle(.pantry, on: false) }
        await waitFor { h.model.isSaving }
        _ = try h.inspect().find(text: "Saving…")
        #expect(try h.toggle(.pantry).isOn() == false) // optimistic
        for module in ModuleKey.allCases { #expect(try h.isDisabled("settings.module.\(module.rawValue)")) }
        #expect(try h.isDisabled("settings.currency"))
        #expect(try h.isDisabled("settings.leave"))
        h.api.release()
        await task.value
        #expect(!h.has("settings.saving"))
    }

    @Test("CORE-05: AC4 offline shows the banner and disables switches and Leave")
    func offlineState() async throws {
        let h = ViewHarness()
        await h.model.load()
        _ = try h.inspect(online: false).find(text: "You're offline. Connect to change household settings.")
        #expect(h.has("offline-banner", online: false))
        for module in ModuleKey.allCases {
            #expect(try h.isDisabled("settings.module.\(module.rawValue)", online: false))
        }
        #expect(try h.isDisabled("settings.leave", online: false))
        #expect(try h.isDisabled("settings.currency", online: false))
    }

    @Test("CORE-05: flipping a switch sends updateModules for that module")
    func toggleInteraction() async throws {
        let h = ViewHarness()
        await h.model.load()
        var saved = makeHousehold(id: h.base.householdId)
        saved.modulePantry = false
        h.api.setUpdateModulesResult(.success(saved))
        try h.toggle(.pantry).tap()
        await waitFor { h.base.updateModulesCalls.count == 1 && !h.model.isSaving }
        #expect(h.base.updateModulesCalls == [
            .updateModules(householdId: h.base.householdId, shopping: nil, pantry: false, maintenance: nil)
        ])
        #expect(try h.toggle(.pantry).isOn() == false)
    }

    @Test("CORE-05: a save failure shows the error text")
    func saveError() async throws {
        let h = ViewHarness()
        await h.model.load()
        h.api.setUpdateModulesResult(.failure(.unknown))
        await h.model.toggle(.pantry, on: false)
        #expect(try h.inspect().find(viewWithAccessibilityIdentifier: "settings.error").text().string() == "Couldn't save. Try again.")
        #expect(try h.toggle(.pantry).isOn())
    }

    @Test("CORE-05: AC5 Leave opens the confirm sheet; confirming removes me and reports left")
    func leaveFlow() async throws {
        let h = ViewHarness()
        await h.model.load()
        try h.inspect().find(viewWithAccessibilityIdentifier: "settings.leave").button().tap()
        #expect(h.model.isConfirmingLeave)
        #expect(h.api.calls.allSatisfy { if case .removeMember = $0 { false } else { true } })
        #expect(h.sheetHas("settings.leave.cancel"))
        try h.sheet().find(viewWithAccessibilityIdentifier: "settings.leave.confirm").button().tap()
        await waitFor { h.base.recorder.leftCount == 1 }
        #expect(h.api.calls.last == .removeMember(householdId: h.base.householdId, userId: h.base.me))
        #expect(h.base.recorder.leftCount == 1)
    }

    @Test("CORE-05: Cancel closes the sheet without calling the API")
    func leaveCancel() async throws {
        let h = ViewHarness()
        await h.model.load()
        h.model.beginLeave()
        try h.sheet().find(viewWithAccessibilityIdentifier: "settings.leave.cancel").button().tap()
        #expect(!h.model.isConfirmingLeave)
        #expect(h.api.calls.allSatisfy { if case .removeMember = $0 { false } else { true } })
    }

    @Test("CORE-05: the last owner sees the refusal inside the sheet")
    func lastOwner() async throws {
        let h = ViewHarness()
        await h.model.load()
        h.api.setRemoveResult(.failure(.lastOwner))
        h.model.beginLeave()
        try h.sheet().find(viewWithAccessibilityIdentifier: "settings.leave.confirm").button().tap()
        await waitFor { h.model.leaveErrorMessage != nil }
        let text = try h.sheet().find(viewWithAccessibilityIdentifier: "settings.leave.error").text().string()
        #expect(text == HouseholdSettingsModel.lastOwnerMessage)
        #expect(!h.has("settings.error"))
        #expect(h.base.recorder.leftCount == 0)
    }
}
