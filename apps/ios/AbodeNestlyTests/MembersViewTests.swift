import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core

private let webBase = URL(string: "http://localhost:5173")!

@MainActor
private struct Harness {
    let base: MembersHarness
    var model: MembersModel { base.model }
    var api: FakeApiService { base.api }

    init(role: Member.Role = .owner, others: [MemberRow] = []) {
        base = MembersHarness(role: role, others: others)
    }

    func inspect(online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try MembersView(model: model, api: api, isOnline: online, webBaseURL: webBase).inspect()
    }

    func has(_ id: String, online: Bool = true) -> Bool {
        (try? inspect(online: online).find(viewWithAccessibilityIdentifier: id)) != nil
    }

    func isDisabled(_ id: String, online: Bool = true) throws -> Bool {
        try inspect(online: online).find(viewWithAccessibilityIdentifier: id).isDisabled()
    }

    func text(_ id: String) throws -> String {
        try inspect().find(viewWithAccessibilityIdentifier: id).text().string()
    }

    func tapMenuItem(_ item: String, of row: MemberRow) throws {
        let id = row.id.uuidString.lowercased()
        let menu = try inspect().find(viewWithAccessibilityIdentifier: "members.row.\(id).menu").menu()
        try menu.find(viewWithAccessibilityIdentifier: "members.row.\(id).\(item)").button().tap()
    }
}

@MainActor
@Suite("CORE-04 members screen")
struct MembersViewTests {
    @Test("CORE-04: AC1 owner list is populated with avatar, name, role and the you marker on me")
    func ownerList() async throws {
        let bea = makeRow(name: "Bea Kovacs")
        let h = Harness(others: [bea])
        await h.model.load()
        let me = h.base.me.uuidString.lowercased(), beaId = bea.id.uuidString.lowercased()
        #expect(try h.text("members.row.\(me).name") == "Me Myself")
        #expect(try h.text("members.row.\(me).role") == "Owner")
        #expect(h.has("members.row.\(me).you"))
        #expect(try h.text("members.row.\(beaId).name") == "Bea Kovacs")
        #expect(try h.text("members.row.\(beaId).role") == "Member")
        #expect(!h.has("members.row.\(beaId).you"))
        let view = try h.inspect()
        _ = try view.find(text: "MM")
        _ = try view.find(text: "BK")
        #expect(!h.has("offline-banner"))
        #expect(try h.isDisabled("members.invite") == false)
    }

    @Test("CORE-04: rows show owners first and an owner sees a menu only on other people")
    func ownerMenus() async throws {
        let bea = makeRow(name: "Bea")
        let h = Harness(others: [bea])
        await h.model.load()
        let me = h.base.me.uuidString.lowercased(), beaId = bea.id.uuidString.lowercased()
        #expect(h.has("members.row.\(beaId).menu"))
        #expect(!h.has("members.row.\(me).menu"))
        #expect(try h.isDisabled("members.row.\(beaId).menu") == false)
        #expect(try h.isDisabled("members.leave") == false)
    }

    @Test("CORE-04: loading, error and offline-no-cache states")
    func nonListStates() async throws {
        let h = Harness()
        #expect(h.has("members.loading"))
        #expect(!h.has("members.leave"))

        h.api.setMembersResult(.failure(.unknown))
        await h.model.load()
        _ = try h.inspect().find(text: "Couldn't load members. Try again.")
        h.api.setMembersResult(.success([makeRow(id: h.base.me, name: "Me", role: .owner)]))
        try h.inspect().find(viewWithAccessibilityIdentifier: "members.retry").button().tap()
        await waitFor { h.model.loadState == .loaded }
        #expect(h.model.loadState == .loaded)

        let offline = Harness()
        offline.base.online.value = false
        await offline.model.load()
        #expect(offline.has("offline-banner", online: false))
        _ = try offline.inspect(online: false).find(text: "Connect to see the members.")
    }

    @Test("CORE-04: AC2 Remove in the overflow menu starts the toast and sends no DELETE yet")
    func removeStartsToast() async throws {
        let bea = makeRow(name: "Bea")
        let h = Harness(others: [bea])
        await h.model.load()
        try h.tapMenuItem("remove", of: bea)
        #expect(h.model.toast == "Removed Bea")
        #expect(try h.text("undo-toast.message") == "Removed Bea")
        #expect(!h.has("members.row.\(bea.id.uuidString.lowercased())"))
        #expect(h.base.removeCalls.isEmpty)
        // Actions are blocked while the removal waits.
        #expect(try h.isDisabled("members.leave"))
        h.model.undo()
    }

    @Test("CORE-04: AC3 tapping Undo brings the row back and never calls DELETE")
    func undoTap() async throws {
        let bea = makeRow(name: "Bea")
        let h = Harness(others: [bea])
        await h.model.load()
        try h.tapMenuItem("remove", of: bea)
        try h.inspect().find(viewWithAccessibilityIdentifier: "undo-toast.undo").button().tap()
        #expect(!h.has("undo-toast"))
        #expect(h.has("members.row.\(bea.id.uuidString.lowercased())"))
        try? await Task.sleep(for: .milliseconds(30))
        #expect(h.base.removeCalls.isEmpty)
    }

    @Test("CORE-04: Make owner in the menu promotes the member and reloads; owners get no Make owner")
    func makeOwnerTap() async throws {
        let bea = makeRow(name: "Bea"), cal = makeRow(name: "Cal", role: .owner)
        let h = Harness(others: [bea, cal])
        await h.model.load()
        let calId = cal.id.uuidString.lowercased()
        let menu = try h.inspect().find(viewWithAccessibilityIdentifier: "members.row.\(calId).menu").menu()
        #expect((try? menu.find(viewWithAccessibilityIdentifier: "members.row.\(calId).makeOwner")) == nil)
        _ = try menu.find(viewWithAccessibilityIdentifier: "members.row.\(calId).remove")

        try h.tapMenuItem("makeOwner", of: bea)
        await waitFor { h.api.calls.contains(.makeOwner(householdId: h.base.householdId, userId: bea.id)) }
        await waitFor { h.api.calls.filter { $0 == .fetchMembers(householdId: h.base.householdId) }.count == 2 }
        #expect(h.api.calls.contains(.makeOwner(householdId: h.base.householdId, userId: bea.id)))
        #expect(h.api.calls.filter { $0 == .fetchMembers(householdId: h.base.householdId) }.count == 2)
    }

    @Test("CORE-04: AC4 Leave as the only owner shows the last-owner message and starts nothing")
    func leaveBlocked() async throws {
        let h = Harness(others: [makeRow(name: "Bea")])
        await h.model.load()
        try h.inspect().find(viewWithAccessibilityIdentifier: "members.leave").button().tap()
        #expect(h.model.alert == MembersModel.lastOwnerMessage)
        #expect(!h.has("undo-toast"))
        #expect(h.base.removeCalls.isEmpty)
    }

    @Test("CORE-04: Leave with another owner shows the Leaving toast, then DELETE for me")
    func leaveTap() async throws {
        let h = Harness(others: [makeRow(name: "Bea", role: .owner)])
        await h.model.load()
        try h.inspect().find(viewWithAccessibilityIdentifier: "members.leave").button().tap()
        #expect(try h.text("undo-toast.message") == "Leaving household")
        #expect(h.base.removeCalls.isEmpty)
        await h.base.sleeper.advance()
        await waitFor { h.base.left.count == 1 }
        #expect(h.base.removeCalls == [.removeMember(householdId: h.base.householdId, userId: h.base.me)])
    }

    @Test("CORE-04: AC5 a member sees no overflow menu and has Leave enabled")
    func memberView() async throws {
        let bea = makeRow(name: "Bea", role: .owner)
        let h = Harness(role: .member, others: [bea])
        await h.model.load()
        let me = h.base.me.uuidString.lowercased()
        #expect(h.has("members.row.\(me).you"))
        #expect(try h.text("members.row.\(me).role") == "Member")
        for row in h.model.rows { #expect(!h.has("members.row.\(row.id.uuidString.lowercased()).menu")) }
        #expect(try h.isDisabled("members.leave") == false)
    }

    @Test("CORE-04: AC6 offline shows the cached rows and the banner, with Invite, the menu and Leave disabled")
    func offline() async throws {
        let bea = makeRow(name: "Bea")
        let h = Harness(others: [bea])
        await h.model.load() // writes the cache
        // Same household and cache as the first load, now offline.
        let model = MembersModel(
            householdId: h.base.householdId, meId: h.base.me, api: h.api, cache: h.base.cache,
            isOnline: { false }, sleep: h.base.sleeper.sleep, onLeft: {}
        )
        await model.load()
        let calls = h.api.calls
        let view = try MembersView(model: model, api: h.api, isOnline: false, webBaseURL: webBase).inspect()
        let beaId = bea.id.uuidString.lowercased()
        _ = try view.find(viewWithAccessibilityIdentifier: "offline-banner")
        _ = try view.find(text: "Bea")
        #expect(try view.find(viewWithAccessibilityIdentifier: "members.invite").isDisabled())
        #expect(try view.find(viewWithAccessibilityIdentifier: "members.row.\(beaId).menu").isDisabled())
        #expect(try view.find(viewWithAccessibilityIdentifier: "members.leave").isDisabled())
        model.leave()
        model.remove(bea)
        #expect(model.pending == nil)
        #expect(h.api.calls == calls)
    }

    @Test("CORE-04: a member row without a profile reads Member, with an M avatar")
    func hiddenProfile() async throws {
        let h = Harness(others: [makeRow(name: nil)])
        await h.model.load()
        let view = try h.inspect()
        _ = try view.find(text: "M")
        #expect(try h.text("members.row.\(h.model.rows.last!.id.uuidString.lowercased()).name") == "Member")
        #expect(h.model.visibleRows.contains { MembersModel.displayName($0) == "Member" })
    }
}
