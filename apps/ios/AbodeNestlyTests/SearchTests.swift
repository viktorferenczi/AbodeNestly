import Contract
import Foundation
import Testing
@testable import Core
@testable import Sync

// Lives here, not in Packages/Core/Tests: Core has no test target, and adding one broke Xcode package resolution on CI (81a7a46).

private func row(_ table: String, _ name: String, deleted: Bool = false) -> SearchRow {
    SearchRow(table: table, id: UUID(), name: name, deletedAt: deleted ? "2026-01-01T00:00:00Z" : nil)
}

private let all: Set<SearchModule> = [.shopping, .pantry, .maintenance]

@Suite("CORE-09 search grouping")
struct SearchGroupingTests {
    @Test("CORE-09: AC3 a list item and a pantry item group under Shopping then Pantry, with kind labels",
          arguments: [("Milk", "Oat milk"), ("milk", "MILK")])
    func groupsByModule(listName: String, pantryName: String) {
        let sections = Search.group(
            [row("pantry_item", pantryName), row("list_item", listName)], query: "milk", modules: all
        )
        #expect(sections.map(\.module) == [.shopping, .pantry])
        #expect(sections[0].results.map(\.kind) == ["List item"])
        #expect(sections[1].results.map(\.kind) == ["Pantry item"])
    }

    @Test("CORE-09: AC4 a module that is off has no section even when its rows are given")
    func moduleOff() {
        let rows = [row("list_item", "Milk"), row("pantry_item", "Milk"), row("asset", "Milk frother")]
        #expect(Search.group(rows, query: "milk", modules: [.shopping, .maintenance]).map(\.module) == [.shopping, .maintenance])
        #expect(Search.group(rows, query: "milk", modules: []).isEmpty)
    }

    @Test("CORE-09: blank queries match nothing and deleted rows are dropped", arguments: ["", "   ", "\n"])
    func blankAndDeleted(query: String) {
        #expect(Search.group([row("list_item", "Milk")], query: query, modules: all).isEmpty)
        #expect(Search.group([row("list_item", "Milk", deleted: true)], query: "milk", modules: all).isEmpty)
    }

    @Test("CORE-09: the query is trimmed; prefix matches come first, then alphabetical")
    func ordering() {
        let rows = [row("pantry_item", "Soy milk"), row("pantry_item", "Milk"), row("pantry_item", "Almond milk"), row("recipe", "Milkshake")]
        let names = Search.group(rows, query: "  MILK ", modules: all)[0].results.map(\.name)
        #expect(names == ["Milk", "Milkshake", "Almond milk", "Soy milk"])
    }

    @Test("CORE-09: every table maps to its module and kind label")
    func mapping() {
        let expected: [(String, SearchModule, String)] = [
            ("list_item", .shopping, "List item"), ("store", .shopping, "Store"),
            ("pantry_item", .pantry, "Pantry item"), ("location", .pantry, "Location"), ("recipe", .pantry, "Recipe"),
            ("asset", .maintenance, "Asset"), ("maint_task", .maintenance, "Task"), ("contractor", .maintenance, "Contractor"),
        ]
        for (table, module, label) in expected {
            let result = Search.group([row(table, "x")], query: "x", modules: all).first?.results.first
            #expect(result?.target.module == module)
            #expect(result?.target.table == table)
            #expect(result?.kind == label)
        }
    }

    @Test("CORE-09: state is empty-query, no-results or results from the debounced query")
    func states() {
        let section = Search.group([row("list_item", "Milk")], query: "milk", modules: all)
        #expect(Search.state(query: "  ", sections: []) == .emptyQuery)
        #expect(Search.state(query: "zzz", sections: []) == .noResults)
        #expect(Search.state(query: "milk", sections: section) == .results)
    }
}

@Suite("CORE-09 recent searches")
struct RecentSearchStoreTests {
    private func store() -> RecentSearchStore {
        RecentSearchStore(householdId: UUID(), suiteName: "search-tests-\(UUID().uuidString)")
    }

    @Test("CORE-09: newest first, case-insensitive dedupe, capped at 8")
    func recording() {
        let store = store()
        for name in ["a", "b", "c", "d", "e", "f", "g", "h", "i"] { store.record(name) }
        #expect(store.read() == ["i", "h", "g", "f", "e", "d", "c", "b"])
        store.record("  C ")
        #expect(store.read() == ["C", "i", "h", "g", "f", "e", "d", "b"])
        store.record("   ")
        #expect(store.read().count == 8)
    }

    @Test("CORE-09: each household has its own list under abodenestly.search.<id>")
    func perHousehold() {
        let suite = "search-tests-\(UUID().uuidString)"
        let a = UUID(), b = UUID()
        RecentSearchStore(householdId: a, suiteName: suite).record("milk")
        #expect(RecentSearchStore(householdId: b, suiteName: suite).read().isEmpty)
        #expect(UserDefaults(suiteName: suite)?.stringArray(forKey: "abodenestly.search.\(a.uuidString.lowercased())") == ["milk"])
    }
}

@Suite("CORE-09 search table")
struct SearchDatabaseTests {
    let root = switcherTempRoot()
    let household = UUID()

    private func first(_ stream: AsyncStream<[SearchRow]>) async -> [SearchRow]? {
        for await rows in stream { return rows }
        return nil
    }

    @Test("CORE-09: matches names case-insensitively across tables of this household, skipping deleted rows")
    func queries() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        let list = UUID()
        let item = SearchRow(table: "list_item", id: UUID(), name: "Whole MILK", listId: list)
        try await db.insertSearchRowForTesting(item, householdId: household)
        try await db.insertSearchRowForTesting(row("maint_task", "Milk frother descale"), householdId: household)
        try await db.insertSearchRowForTesting(row("store", "Milk bar", deleted: true), householdId: household)
        try await db.insertSearchRowForTesting(row("list_item", "Milk elsewhere"), householdId: UUID())
        try await db.insertSearchRowForTesting(row("pantry_item", "Milk (pantry)"), householdId: household)

        let rows = await first(await db.observeSearch(householdId: household, query: " milk ", tables: ["list_item", "store", "maint_task"]))
        #expect(rows?.map(\.name).sorted() == ["Milk frother descale", "Whole MILK"])
        #expect(rows?.first { $0.table == "list_item" }?.listId == list)
    }

    @Test("CORE-09: LIKE wildcards in the query are literal, unknown tables are ignored, blank gives nothing")
    func literalAndBlank() async throws {
        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        try await db.insertSearchRowForTesting(row("list_item", "50% off"), householdId: household)
        try await db.insertSearchRowForTesting(row("list_item", "500 g"), householdId: household)
        #expect(await first(await db.observeSearch(householdId: household, query: "50%", tables: ["list_item"]))?.map(\.name) == ["50% off"])
        #expect(await first(await db.observeSearch(householdId: household, query: "50", tables: ["nope; DROP"])) == [])
        #expect(await first(await db.observeSearch(householdId: household, query: " ", tables: ["list_item"])) == [])
    }
}

@Suite("CORE-09 search modules offline")
struct SearchModulesTests {
    private func entry(_ id: UUID, shopping: Bool, pantry: Bool, maintenance: Bool) -> HouseholdWithRole {
        HouseholdWithRole(
            household: Household(
                id: id, name: "Home", createdBy: UUID(), moduleShopping: shopping, modulePantry: pantry, moduleMaintenance: maintenance,
                currency: "EUR", createdAt: Date(), updatedAt: Date(), deletedAt: nil, syncSeq: 1
            ),
            role: .member
        )
    }

    @Test("CORE-09: AC5 offline with no open household, the cached flags of that household id are used")
    func cachedFlags() {
        let id = UUID()
        let cached = [entry(UUID(), shopping: true, pantry: true, maintenance: true), entry(id, shopping: true, pantry: false, maintenance: true)]
        #expect(Search.modules(householdId: id, household: nil, cached: cached) == HomeModules(shopping: true, pantry: false, maintenance: true))
    }

    @Test("CORE-09: AC5 no cached row (or no household id) offers no search")
    func noCache() {
        let id = UUID()
        #expect(Search.modules(householdId: id, household: nil, cached: []) == nil)
        #expect(Search.modules(householdId: nil, household: nil, cached: [entry(id, shopping: true, pantry: true, maintenance: true)]) == nil)
    }

    @Test("CORE-09: the open household's own flags win over the cache")
    func openWins() {
        let id = UUID()
        let open = LandingHousehold(id: id, moduleShopping: false, modulePantry: true, moduleMaintenance: false)
        let cached = [entry(id, shopping: true, pantry: true, maintenance: true)]
        #expect(Search.modules(householdId: id, household: open, cached: cached) == HomeModules(shopping: false, pantry: true, maintenance: false))
    }
}
