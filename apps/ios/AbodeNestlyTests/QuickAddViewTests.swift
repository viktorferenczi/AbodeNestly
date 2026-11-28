import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync

@MainActor
private func inspect(_ h: DetailHarness, online: Bool = true, checkedExpanded: Bool = false) throws -> InspectableView<ViewType.ClassifiedView> {
    try ListDetailView(model: h.model, title: "Groceries", isOnline: online, checkedExpanded: checkedExpanded).inspect()
}

@MainActor
private func has(_ h: DetailHarness, _ id: String, online: Bool = true) -> Bool {
    (try? inspect(h, online: online).find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
@Suite("SHOP-02 list detail view", .serialized)
struct QuickAddViewTests {
    @Test("SHOP-02: empty list shows the hint and the quick-add bar")
    func empty() async throws {
        let h = try await DetailHarness()
        #expect(has(h, "list.empty"))
        _ = try inspect(h).find(text: "Try: 2 l milk")
        #expect(has(h, "quickadd.field"))
    }

    @Test("SHOP-02: populated shows item names, the store chip and a collapsed Checked (n) group")
    func populated() async throws {
        let store = UUID()
        let h = try await DetailHarness(seed: { db, list in
            try await db.upsert(table: "store", row: storeRow(store, name: "Lidl"))
            try await db.patch(table: "shopping_list", id: list, fields: ["store_id": .string(store.uuidString.lowercased())])
            try await seedItem(db, list: list, name: "Eggs")
            try await seedItem(db, list: list, name: "Milk", checked: true)
        })
        await h.settle { h.model.snapshot.items.count == 2 && h.model.snapshot.storeName != nil }
        _ = try inspect(h).find(text: "Eggs")
        _ = try inspect(h).find(text: "Lidl")
        _ = try inspect(h).find(text: "Checked (1)")
        #expect(!has(h, "list.empty"))
        _ = try inspect(h, checkedExpanded: true).find(viewWithAccessibilityIdentifier: "list.checked.row.Milk")
    }

    @Test("SHOP-02: offline shows the banner and Add still works")
    func offline() async throws {
        let h = try await DetailHarness(seed: { db, _ in try await seedItem(db, list: UUID(), name: "Milk", checked: true) })
        #expect(!has(h, "offline-banner"))
        #expect(has(h, "offline-banner", online: false))
        h.model.query = "mi"
        #expect(try !inspect(h, online: false).find(viewWithAccessibilityIdentifier: "quickadd.add").button().isDisabled())
        await h.settle { !h.model.suggestions.isEmpty }
        await h.model.pick(try #require(h.model.suggestions.first))
        await h.settle { h.model.unchecked.count == 1 }
        _ = try inspect(h, online: false).find(text: "Milk")
    }

    @Test("SHOP-02: AC1 typing mi shows the Milk suggestion; tapping it adds Milk and clears the field")
    func suggestAndTap() async throws {
        let h = try await DetailHarness(seed: { db, _ in try await seedItem(db, list: UUID(), name: "Milk", checked: true) })
        await h.settle { !h.model.snapshot.history.isEmpty }
        h.model.query = "mi"
        try inspect(h).find(viewWithAccessibilityIdentifier: "quickadd.suggestion.Milk").button().tap()
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.unchecked.map(\.name) == ["Milk"])
        #expect(h.model.query.isEmpty)
    }

    @Test("SHOP-02: AC3 Add with a name that has no history adds it as typed")
    func addAsTyped() async throws {
        let h = try await DetailHarness()
        h.model.query = "Lightbulbs"
        try inspect(h).find(viewWithAccessibilityIdentifier: "quickadd.add").button().tap()
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.unchecked.map(\.name) == ["Lightbulbs"])
    }

    @Test("SHOP-02: Add is disabled on a blank field")
    func addDisabled() async throws {
        let h = try await DetailHarness()
        #expect(try inspect(h).find(viewWithAccessibilityIdentifier: "quickadd.add").button().isDisabled())
    }

    @Test("SHOP-02: a deleted list shows This list is gone")
    func gone() async throws {
        let h = try await DetailHarness()
        try await h.db.remove(table: "shopping_list", id: h.list)
        await h.settle { h.model.isGone }
        #expect(has(h, "list.gone"))
        _ = try inspect(h).find(text: "This list is gone")
        #expect(!has(h, "quickadd.field"))
    }
}
