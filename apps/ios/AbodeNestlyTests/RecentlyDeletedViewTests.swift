import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core
@testable import Sync

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)
private let stamp = Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt)
private let allOn = HomeModules(shopping: true, pantry: true, maintenance: true)

private actor Kicks {
    var count = 0
    func add() { count += 1 }
}

@MainActor
private struct DeletedHarness {
    let root = switcherTempRoot()
    let household = UUID()
    let db: HouseholdDatabase
    let kicks = Kicks()
    let model: RecentlyDeletedModel

    /// `rows`: (table, name, days ago).
    init(modules: HomeModules?, rows: [(String, String, Double)] = []) async throws {
        let db = HouseholdDatabase(rootURL: root)
        self.db = db
        try await db.open(householdId: household)
        for (table, name, days) in rows {
            let at = fixedNow.addingTimeInterval(-days * 86_400 - 3_600).formatted(stamp)
            try await db.insertSearchRowForTesting(SearchRow(table: table, id: UUID(), name: name, deletedAt: at), householdId: household)
        }
        let kicks = kicks
        model = RecentlyDeletedModel(
            householdId: household, modules: modules, database: db, kick: { await kicks.add() }, now: { fixedNow }
        )
        await settle()
    }

    func settle() async {
        for _ in 0..<400 where !model.loaded { await Task.yield() }
        for _ in 0..<50 { await Task.yield() }
    }

    func inspect() throws -> InspectableView<ViewType.ClassifiedView> { try RecentlyDeletedView(model: model).inspect() }
    func has(_ id: String) -> Bool { (try? inspect().find(viewWithAccessibilityIdentifier: id)) != nil }
}

@MainActor
@Suite("CORE-10 recently deleted view")
struct RecentlyDeletedViewTests {
    @Test("CORE-10: AC1 nothing deleted shows the empty state")
    func empty() async throws {
        let h = try await DeletedHarness(modules: allOn)
        #expect(h.has("deleted.empty"))
        _ = try h.inspect().find(text: "Nothing deleted in the last 30 days.")
        #expect(!h.has("deleted.row"))
    }

    @Test("CORE-10: AC2 a list item deleted 2 days ago shows Restore and 28 days left")
    func populated() async throws {
        let h = try await DeletedHarness(modules: allOn, rows: [("list_item", "Milk", 2)])
        #expect(h.has("deleted.segment.shopping"))
        #expect(h.has("deleted.row"))
        _ = try h.inspect().find(text: "Milk")
        _ = try h.inspect().find(text: "28 days left")
        #expect(h.has("deleted.restore"))
    }

    @Test("CORE-10: AC3 Restore removes the row, queues the upsert and kicks sync")
    func restoreTap() async throws {
        let h = try await DeletedHarness(modules: allOn, rows: [("list_item", "Milk", 2)])
        try h.inspect().find(viewWithAccessibilityIdentifier: "deleted.restore").button().tap()
        for _ in 0..<400 where !h.model.selectedItems.isEmpty { await Task.yield() }
        for _ in 0..<50 { await Task.yield() }
        #expect(h.model.state == .empty)
        #expect(h.has("deleted.empty"))
        #expect(try await h.db.outboxForTesting().count == 1)
        #expect(await h.kicks.count == 1)
    }

    @Test("CORE-10: AC4 a row deleted 31 days ago is not listed")
    func purged() async throws {
        let h = try await DeletedHarness(modules: allOn, rows: [("store", "Old", 31)])
        #expect(h.has("deleted.empty"))
        #expect(!h.has("deleted.row"))
    }

    @Test("CORE-10: AC5 with pantry off the Pantry segment is hidden and Shopping still works")
    func pantryOff() async throws {
        let h = try await DeletedHarness(
            modules: HomeModules(shopping: true, pantry: false, maintenance: true),
            rows: [("pantry_item", "Rice", 1), ("store", "Lidl", 1)]
        )
        #expect(h.has("deleted.segment.shopping"))
        #expect(h.has("deleted.segment.maintenance"))
        #expect(!h.has("deleted.segment.pantry"))
        #expect(h.model.selectedItems.map(\.name) == ["Lidl"])
    }

    @Test("CORE-10: a selected segment with no rows shows a note; the first enabled module is selected")
    func emptySegment() async throws {
        let h = try await DeletedHarness(modules: allOn, rows: [("recipe", "Soup", 3)])
        #expect(h.model.selectedModule == .shopping)
        #expect(h.has("deleted.none"))
        h.model.selected = .pantry
        #expect(h.model.selectedItems.map(\.name) == ["Soup"])
        #expect(!h.has("deleted.none"))
    }
}
