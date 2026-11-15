import Contract
import Foundation
import SwiftUI
@testable import Sync
import Testing
import ViewInspector
@testable import Core

@MainActor
private final class Recorder {
    var performed: [(UUID, Bool)] = []
    var dismissed = 0
    var createTaps = 0
    var failure: Error?
    /// When set, `perform` waits here so the switching state can be inspected.
    var gate: CheckedContinuation<Void, Never>?
    var hold = false
}

@MainActor
private struct ViewHarness {
    let root = switcherTempRoot()
    let db: HouseholdDatabase
    let user = UUID()
    let suite = "SwitcherViewTests-\(UUID())"
    let cache: HouseholdsCache
    let api = FakeApiService()
    let recorder = Recorder()
    let alpha = HouseholdWithRole(household: makeHousehold(name: "Alpha"), role: .owner)
    let beta = HouseholdWithRole(household: makeHousehold(name: "Beta"), role: .member)
    let model: SwitcherModel

    init(entries: [HouseholdWithRole]? = nil, online: Bool = true) {
        db = HouseholdDatabase(rootURL: root)
        cache = HouseholdsCache(suiteName: suite)
        let list = entries ?? [alpha, beta]
        cache.write(list, userId: user)
        // The online refresh returns what the server has.
        api.setMyHouseholdsResult(.success(list))
        let recorder = recorder
        model = SwitcherModel(
            userId: user, currentId: alpha.household.id, current: nil, api: api, cache: cache, database: db,
            isOnline: { online },
            perform: { id, settings in
                recorder.performed.append((id, settings))
                if recorder.hold { await withCheckedContinuation { recorder.gate = $0 } }
                if let failure = recorder.failure { throw failure }
            }
        )
    }

    func inspect() throws -> InspectableView<ViewType.ClassifiedView> {
        let recorder = recorder
        return try SwitcherView(
            model: model, onDismiss: { recorder.dismissed += 1 }, onCreateOrJoin: { recorder.createTaps += 1 }
        ).inspect()
    }

    func find(_ id: String) throws -> InspectableView<ViewType.ClassifiedView> {
        try inspect().find(viewWithAccessibilityIdentifier: id)
    }

    func has(_ id: String) -> Bool { (try? find(id)) != nil }

    func row(_ household: HouseholdWithRole) throws -> InspectableView<ViewType.ClassifiedView> {
        try find("switcher.row.\(household.household.id.uuidString.lowercased())")
    }

    func settle() async {
        for _ in 0..<50 { await Task.yield() }
    }
}

@MainActor
@Suite("CORE-07 switcher sheet")
struct SwitcherViewTests {
    @Test("CORE-07: AC1 one household still shows the create-or-join row")
    func oneHousehold() async throws {
        let h = ViewHarness(entries: [ViewHarness().alpha])
        await h.model.load()
        #expect(h.model.state == .oneHousehold)
        #expect(h.has("switcher.create"))
        _ = try h.inspect().find(text: "Create or join another")
        try h.find("switcher.create").button().tap()
        #expect(h.recorder.createTaps == 1)
    }

    @Test("CORE-07: AC2 many households show names, roles, the check and the pending count")
    func many() async throws {
        let h = ViewHarness()
        try await h.db.open(householdId: h.beta.household.id)
        try await h.db.enqueueForTesting()
        try await h.db.enqueueForTesting()
        await h.model.load()

        #expect(h.model.state == .many)
        let alpha = try h.row(h.alpha)
        _ = try alpha.find(text: "Alpha")
        _ = try alpha.find(text: "Owner")
        #expect(try alpha.find(ViewType.Image.self).actualImage().name() == "checkmark")
        let beta = try h.row(h.beta)
        _ = try beta.find(text: "Beta")
        _ = try beta.find(text: "Member · 2 to sync")
        #expect(try beta.find(ViewType.Image.self).actualImage().name() != "checkmark")
    }

    @Test("CORE-07: AC2 tapping another household switches and closes the sheet; tapping the current one only closes")
    func tap() async throws {
        let h = ViewHarness()
        await h.model.load()
        try h.row(h.beta).callOnTapGesture()
        await h.settle()
        #expect(h.recorder.performed.count == 1)
        #expect(h.recorder.performed.first?.0 == h.beta.household.id)
        #expect(h.recorder.performed.first?.1 == false)
        #expect(h.recorder.dismissed == 1)

        try h.row(h.alpha).callOnTapGesture()
        await h.settle()
        #expect(h.recorder.performed.count == 1)
        #expect(h.recorder.dismissed == 2)
    }

    @Test("CORE-07: AC3 while switching the rows are disabled and the progress shows the target name")
    func switching() async throws {
        let h = ViewHarness()
        await h.model.load()
        h.recorder.hold = true
        let task = Task { await h.model.select(h.beta.household.id) }
        for _ in 0..<1000 where h.model.switchingName == nil { await Task.yield() }

        #expect(h.model.state == .switching("Beta"))
        _ = try h.find("switcher.switching").find(text: "Switching to Beta…")
        #expect(try h.inspect().find(ViewType.ScrollView.self).isDisabled())
        // A second tap while switching does nothing.
        #expect(await h.model.select(h.alpha.household.id) == false)
        #expect(h.recorder.performed.count == 1)

        h.recorder.gate?.resume()
        #expect(await task.value)
        #expect(h.model.switchingName == nil)
        #expect(!h.has("switcher.switching"))
    }

    @Test("CORE-07: a failed switch shows the error text and keeps the sheet open")
    func failure() async throws {
        let h = ViewHarness()
        await h.model.load()
        h.recorder.failure = SessionModel.SwitchError.openFailed
        try h.row(h.beta).callOnTapGesture()
        await h.settle()
        #expect(h.recorder.dismissed == 0)
        #expect(try h.find("switcher.error").text().string() == "Couldn't switch households. Try again.")
    }

    @Test("CORE-07: long-press switches and asks for Settings (B5)")
    func longPress() async throws {
        let h = ViewHarness()
        await h.model.load()
        try h.row(h.beta).callOnLongPressGesture()
        await h.settle()
        #expect(h.recorder.performed.count == 1)
        #expect(h.recorder.performed.first?.0 == h.beta.household.id)
        #expect(h.recorder.performed.first?.1 == true)
        #expect(h.recorder.dismissed == 1)
    }

    @Test("CORE-07: AC5 offline the rows come from the cache and the API is not asked")
    func offline() async throws {
        let h = ViewHarness(online: false)
        h.api.setMyHouseholdsResult(.failure(.network))
        await h.model.load()
        #expect(h.model.rows.map(\.household.name) == ["Alpha", "Beta"])
        #expect(h.api.calls.isEmpty)
        #expect(h.has("switcher.row.\(h.beta.household.id.uuidString.lowercased())"))
    }

    @Test("CORE-07: online the rows refresh from the API and the cache follows; a failed refresh keeps the cached rows")
    func refresh() async throws {
        let h = ViewHarness()
        let gamma = HouseholdWithRole(household: makeHousehold(name: "Gamma"), role: .member)
        h.api.setMyHouseholdsResult(.success([h.alpha, h.beta, gamma]))
        await h.model.load()
        #expect(h.model.rows.map(\.household.name) == ["Alpha", "Beta", "Gamma"])
        #expect(h.cache.read(userId: h.user).count == 3)

        let failing = ViewHarness()
        failing.api.setMyHouseholdsResult(.failure(.unknown))
        await failing.model.load()
        #expect(failing.model.rows.count == 2)
    }

    @Test("CORE-07: the current household is added when the cached list lacks it")
    func prependsCurrent() {
        let h = ViewHarness(entries: [])
        let model = SwitcherModel(
            userId: h.user, currentId: h.alpha.household.id, current: h.alpha, api: h.api, cache: h.cache, database: h.db,
            isOnline: { false }, perform: { _, _ in }
        )
        #expect(model.rows.map(\.household.name) == ["Alpha"])
        #expect(model.rows.first?.isCurrent == true)
    }
}
