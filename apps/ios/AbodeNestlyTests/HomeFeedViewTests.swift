import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core
@testable import Sync

/// Records what the screen asked for (TESTING §7): the fake engine counts `kick()` calls.
final class FakeSyncEngine: SyncEngine, @unchecked Sendable {
    // Guarded by `lock`: `kick()` may arrive from any task.
    private let lock = NSLock()
    private var count = 0
    var kicks: Int { lock.withLock { count } }
    func kick() async { lock.withLock { count += 1 } }
}

@MainActor
private final class Taps {
    var modules: [AppTab] = []
    var invite = 0, members = 0, settings = 0, profile = 0
}

@MainActor
private struct HomeHarness {
    let root = switcherTempRoot()
    let household = UUID()
    let db: HouseholdDatabase
    let sync = FakeSyncEngine()
    let taps = Taps()
    let model: HomeModel
    private let reader: Task<Void, Never>

    init(modules: HomeModules?, rows: [Activity] = []) async throws {
        let db = HouseholdDatabase(rootURL: root)
        self.db = db
        try await db.open(householdId: household)
        let household = household
        try await db.upsertActivity(rows.map { var row = $0; row.householdId = household; return row })
        let sync = sync
        let home = HomeModel(
            householdId: household, modules: modules, database: db, kick: { await sync.kick() }, now: { homeNow }
        )
        model = home
        reader = Task { await home.start() }
        for _ in 0..<200 where !home.loaded { await Task.yield() }
    }

    func inspect() throws -> InspectableView<ViewType.ClassifiedView> {
        let taps = taps
        return try HomeView(
            model: model, onOpenModule: { taps.modules.append($0) }, onInvite: { taps.invite += 1 },
            onOpenMembers: { taps.members += 1 }, onOpenSettings: { taps.settings += 1 }, onOpenProfile: { taps.profile += 1 }
        ).inspect()
    }

    func has(_ id: String) -> Bool { (try? inspect().find(viewWithAccessibilityIdentifier: id)) != nil }
    func stop() { reader.cancel() }
}

private let modulesOn = HomeModules(shopping: true, pantry: true, maintenance: true)
private let modulesOff = HomeModules(shopping: false, pantry: false, maintenance: false)

@MainActor
@Suite("CORE-08 home feed view")
struct HomeFeedViewTests {
    @Test("CORE-08: AC1 no activity shows the empty-activity state with an invite button")
    func empty() async throws {
        let h = try await HomeHarness(modules: modulesOn)
        defer { h.stop() }
        #expect(h.model.state == .empty)
        #expect(h.has("home.empty"))
        _ = try h.inspect().find(text: "Things your household does show up here.")
        try h.inspect().find(viewWithAccessibilityIdentifier: "home.empty.invite").button().tap()
        #expect(h.taps.invite == 1)
        #expect(!h.has("home.feed.row"))
    }

    @Test("CORE-08: AC2 shows sentences newest first, at most 20, from the last 30 days only")
    func populated() async throws {
        var rows = [makeActivity("Anna checked off Milk", ago: 5), makeActivity("Ben added Eggs", ago: 60)]
        rows.append(makeActivity("Too old", ago: 31 * homeDay))
        rows += (0..<22).map { makeActivity("Filler \($0)", ago: 120 + Double($0)) }
        let h = try await HomeHarness(modules: modulesOn, rows: rows)
        defer { h.stop() }
        #expect(h.model.state == .populated)
        #expect(h.model.feed.count == 20)
        #expect(h.model.feed.prefix(2).map(\.summary) == ["Anna checked off Milk", "Ben added Eggs"])
        _ = try h.inspect().find(text: "Anna checked off Milk")
        #expect((try? h.inspect().find(text: "Too old")) == nil)
        #expect(!h.has("home.empty"))
    }

    @Test("CORE-08: AC3 every module off shows the settings prompt, no Today cards, and the feed still lists core events")
    func allOff() async throws {
        let row = makeActivity("Ben joined the household", ago: 5, module: .core)
        let h = try await HomeHarness(modules: modulesOff, rows: [row])
        defer { h.stop() }
        #expect(h.model.state == .allModulesOff)
        #expect(h.has("home.allOff"))
        _ = try h.inspect().find(text: "Every module is off. Turn one on in Settings.")
        _ = try h.inspect().find(text: "Ben joined the household")
        #expect(!h.has("home.today.shopping"))
        try h.inspect().find(viewWithAccessibilityIdentifier: "home.allOff.settings").button().tap()
        #expect(h.taps.settings == 1)
    }

    @Test("CORE-08: a module that is off hides its Today card; the others open their module")
    func todayCards() async throws {
        let h = try await HomeHarness(modules: HomeModules(shopping: false, pantry: true, maintenance: true))
        defer { h.stop() }
        #expect(!h.has("home.today.shopping"))
        #expect(h.has("home.today.maintenance"))
        try h.inspect().find(viewWithAccessibilityIdentifier: "home.today.pantry").button().tap()
        #expect(h.taps.modules == [.pantry])
    }

    @Test("CORE-08: footer links open Members, Settings and Profile")
    func footer() async throws {
        let h = try await HomeHarness(modules: modulesOn)
        defer { h.stop() }
        try h.inspect().find(viewWithAccessibilityIdentifier: "home.members").button().tap()
        try h.inspect().find(viewWithAccessibilityIdentifier: "home.settings").button().tap()
        try h.inspect().find(viewWithAccessibilityIdentifier: "home.profile").button().tap()
        #expect((h.taps.members, h.taps.settings, h.taps.profile) == (1, 1, 1))
    }

    @Test("CORE-08: AC4 pull to refresh calls kick()")
    func pullToRefresh() async throws {
        let h = try await HomeHarness(modules: modulesOn)
        defer { h.stop() }
        // ViewInspector's views are not Sendable; the call stays on the main actor.
        nonisolated(unsafe) let list = try h.inspect().find(ViewType.List.self)
        try await list.callRefreshable()
        #expect(h.sync.kicks == 1)
    }

    @Test("CORE-08: AC5 the cached feed is shown with the network gone, and a pull stays harmless")
    func offline() async throws {
        let h = try await HomeHarness(modules: modulesOn, rows: [makeActivity("Anna checked off Milk", ago: 5)])
        defer { h.stop() }
        // Offline is the pull engine's concern (ActivityPull is a no-op); the screen only reads the local database.
        _ = try h.inspect().find(text: "Anna checked off Milk")
        nonisolated(unsafe) let list = try h.inspect().find(ViewType.List.self)
        try await list.callRefreshable()
        _ = try h.inspect().find(text: "Anna checked off Milk")
    }

    @Test("CORE-08: a row pulled while the screen is open appears")
    func liveUpdate() async throws {
        let h = try await HomeHarness(modules: modulesOn)
        defer { h.stop() }
        #expect(h.model.state == .empty)
        try await h.db.upsertActivity([makeActivity("Cleo joined the household", ago: 5, household: h.household, module: .core)])
        for _ in 0..<400 where h.model.rows.isEmpty { await Task.yield() }
        #expect(h.model.feed.map(\.summary) == ["Cleo joined the household"])
    }
}
