import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core
@testable import Sync

/// A clock the test advances: `sleep` returns once the virtual time reaches its deadline.
final class SearchClock: @unchecked Sendable {
    // Guarded by `lock`: sleeps arrive from model tasks, `advance` from the test.
    private let lock = NSLock()
    private var now = Duration.zero
    private var waiters: [(deadline: Duration, continuation: CheckedContinuation<Void, Error>, id: UUID)] = []

    func sleep(_ duration: Duration) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let ready = lock.withLock { () -> Bool in
                    if duration <= .zero { return true }
                    waiters.append((now + duration, continuation, id))
                    return false
                }
                if ready { continuation.resume() }
            }
        } onCancel: {
            let cancelled = lock.withLock { () -> CheckedContinuation<Void, Error>? in
                guard let index = waiters.firstIndex(where: { $0.id == id }) else { return nil }
                return waiters.remove(at: index).continuation
            }
            cancelled?.resume(throwing: CancellationError())
        }
    }

    func advance(milliseconds: Int) async {
        // The model's debounce task registers its sleep asynchronously; wait for it so the time is not lost.
        for _ in 0..<500 where lock.withLock({ waiters.isEmpty }) { await Task.yield() }
        let due = lock.withLock { () -> [CheckedContinuation<Void, Error>] in
            now += .milliseconds(milliseconds)
            let ready = waiters.filter { $0.deadline <= now }
            waiters.removeAll { $0.deadline <= now }
            return ready.map(\.continuation)
        }
        due.forEach { $0.resume() }
        for _ in 0..<50 { await Task.yield() }
    }
}

@MainActor
private final class Opened {
    var targets: [SearchTarget] = []
}

@MainActor
private struct SearchHarness {
    let root = switcherTempRoot()
    let household = UUID()
    let db: HouseholdDatabase
    let clock = SearchClock()
    let opened = Opened()
    let suite = "search-view-\(UUID().uuidString)"
    let model: SearchModel

    init(modules: HomeModules?, recents: [String] = [], rows: [SearchRow] = []) async throws {
        let db = HouseholdDatabase(rootURL: root)
        self.db = db
        try await db.open(householdId: household)
        for row in rows { try await db.insertSearchRowForTesting(row, householdId: household) }
        let store = RecentSearchStore(householdId: household, suiteName: suite)
        for recent in recents.reversed() { store.record(recent) }
        let clock = clock
        model = SearchModel(
            householdId: household, modules: modules, database: db, store: store,
            sleep: { try await clock.sleep($0) }
        )
    }

    func inspect() throws -> InspectableView<ViewType.ClassifiedView> {
        let opened = opened
        return try SearchView(model: model, onOpen: { opened.targets.append($0) }).inspect()
    }

    func has(_ id: String) -> Bool { (try? inspect().find(viewWithAccessibilityIdentifier: id)) != nil }

    /// Types, waits out the debounce and lets the observation deliver.
    func search(_ text: String) async {
        model.query = text
        await clock.advance(milliseconds: 150)
        await settle(text)
    }

    /// Waits until the debounced query is `text` (trimmed) and its first rows arrived.
    func settle(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for _ in 0..<400 where model.debouncedQuery != trimmed || model.isSearching { await Task.yield() }
    }

    var recents: [String] { RecentSearchStore(householdId: household, suiteName: suite).read() }
}

private let allOn = HomeModules(shopping: true, pantry: true, maintenance: true)
private func named(_ table: String, _ name: String) -> SearchRow { SearchRow(table: table, id: UUID(), name: name) }

@MainActor
@Suite("CORE-09 search view")
struct SearchViewTests {
    @Test("CORE-09: AC1 an empty query shows the recent searches, and tapping one fills the field and searches")
    func recents() async throws {
        let h = try await SearchHarness(modules: allOn, recents: ["milk", "boiler"], rows: [named("list_item", "Milk")])
        #expect(h.model.state == .emptyQuery)
        #expect(h.has("search.empty"))
        _ = try h.inspect().find(text: "Recent searches")
        #expect(try h.inspect().findAll(ViewType.Button.self, where: { (try? $0.find(text: "boiler")) != nil }).count == 1)
        let first = try h.inspect().find(viewWithAccessibilityIdentifier: "search.recent").button()
        try first.tap()
        #expect(h.model.query == "milk")
        for _ in 0..<400 where h.model.isSearching { await Task.yield() }
        #expect(h.model.state == .results)
        #expect(h.has("search.section.shopping"))
    }

    @Test("CORE-09: AC2 a name that matches nothing shows no-results at 150 ms, not at 149")
    func noResults() async throws {
        let h = try await SearchHarness(modules: allOn, rows: [named("list_item", "Milk")])
        h.model.query = "zzz"
        await h.clock.advance(milliseconds: 149)
        #expect(h.model.state == .emptyQuery)
        #expect(!h.has("search.noResults"))
        await h.clock.advance(milliseconds: 1)
        await h.settle("zzz")
        #expect(h.model.state == .noResults)
        #expect(h.has("search.noResults"))
    }

    @Test("CORE-09: typing again restarts the 150 ms wait")
    func debounceRestarts() async throws {
        let h = try await SearchHarness(modules: allOn, rows: [named("list_item", "Milk")])
        h.model.query = "m"
        await h.clock.advance(milliseconds: 100)
        h.model.query = "mi"
        await h.clock.advance(milliseconds: 100)
        #expect(h.model.debouncedQuery == "")
        await h.clock.advance(milliseconds: 50)
        await h.settle("mi")
        #expect(h.model.debouncedQuery == "mi")
    }

    @Test("CORE-09: AC3 a list item and a pantry item appear under Shopping and Pantry")
    func grouped() async throws {
        let h = try await SearchHarness(modules: allOn, rows: [named("pantry_item", "Oat milk"), named("list_item", "Milk")])
        await h.search("milk")
        #expect(h.model.sections.map(\.module) == [.shopping, .pantry])
        #expect(h.has("search.section.shopping"))
        #expect(h.has("search.section.pantry"))
        #expect(!h.has("search.section.maintenance"))
        #expect(try h.inspect().findAll(ViewType.Button.self).count >= 2)
        _ = try h.inspect().find(text: "List item")
        _ = try h.inspect().find(text: "Pantry item")
    }

    @Test("CORE-09: AC4 with pantry off no Pantry section shows even though the database still has the row")
    func pantryOff() async throws {
        let h = try await SearchHarness(
            modules: HomeModules(shopping: true, pantry: false, maintenance: true),
            rows: [named("pantry_item", "Oat milk"), named("list_item", "Milk")]
        )
        await h.search("milk")
        #expect(h.model.sections.map(\.module) == [.shopping])
        #expect(!h.has("search.section.pantry"))
        // Turning pantry back on while the screen is open brings the section back.
        h.model.modules = allOn
        for _ in 0..<400 where h.model.isSearching { await Task.yield() }
        #expect(h.model.sections.map(\.module) == [.shopping, .pantry])
    }

    @Test("CORE-09: AC5 the local database answers with the network gone (the model never touches the network)")
    func offline() async throws {
        let h = try await SearchHarness(modules: allOn, rows: [named("asset", "Boiler")])
        await h.search("boil")
        #expect(h.model.state == .results)
        _ = try h.inspect().find(text: "Boiler")
    }

    @Test("CORE-09: tapping a result records the recent search and opens its target")
    func rowTap() async throws {
        let item = named("list_item", "Milk")
        let h = try await SearchHarness(modules: allOn, rows: [item])
        await h.search("  Milk ")
        try h.inspect().find(viewWithAccessibilityIdentifier: "search.row").button().tap()
        #expect(h.opened.targets == [SearchTarget(module: .shopping, table: "list_item", id: item.id)])
        #expect(h.recents == ["Milk"])
        #expect(h.model.recents == ["Milk"])
    }

    @Test("CORE-09: submit records the search only when it has results")
    func submit() async throws {
        let h = try await SearchHarness(modules: allOn, rows: [named("list_item", "Milk")])
        await h.search("zzz")
        h.model.submit()
        #expect(h.recents.isEmpty)
        await h.search("milk")
        h.model.submit()
        #expect(h.recents == ["milk"])
    }
}
