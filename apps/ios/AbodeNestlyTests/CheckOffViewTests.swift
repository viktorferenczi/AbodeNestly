import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync
@testable import UI

private func has(_ view: InspectableView<ViewType.ClassifiedView>, _ id: String) -> Bool {
    (try? view.find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
@Suite("SHOP-04 check-off view", .serialized)
struct CheckOffViewTests {
    private func inspect(_ h: DetailHarness, online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try ListDetailView(model: h.model, title: "Groceries", isOnline: online, checkedExpanded: true).inspect()
    }

    @Test("SHOP-04: AC1 the checkbox has a label; tapping it shows the toast and the row stays among the unchecked")
    func checkTap() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: .seconds(30))
        await h.settle { h.model.unchecked.count == 1 }
        let box = try inspect(h).find(viewWithAccessibilityIdentifier: "list.row.Milk.check")
        try box.button().tap()
        #expect(h.model.pendingActions.map(\.message) == ["Checked Milk"])
        let view = try inspect(h)
        _ = try view.find(text: "Checked Milk")
        #expect(has(view, "list.row.Milk") && h.model.unchecked.count == 1)
    }

    @Test("SHOP-04: Undo in the toast removes it and nothing is checked")
    func undoToast() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: .seconds(30))
        await h.settle { h.model.unchecked.count == 1 }
        try inspect(h).find(viewWithAccessibilityIdentifier: "list.row.Milk.check").button().tap()
        // The toast lives in the overlay, outside the inspected body, so Undo goes through the model as the toast does.
        h.model.undo(try #require(h.model.pendingActions.first).id)
        #expect(h.model.pendingActions.isEmpty && h.model.checked.isEmpty)
    }

    @Test("SHOP-04: AC4 all checked shows All done and Clear checked, which clears")
    func allDone() async throws {
        let h = try await DetailHarness(seed: { db, list in
            try await seedItem(db, list: list, name: "Eggs", checked: true)
        }, undoWindow: .seconds(30))
        await h.settle { h.model.checked.count == 1 }
        let view = try inspect(h)
        _ = try view.find(viewWithAccessibilityIdentifier: "list.allDone")
        try view.find(viewWithAccessibilityIdentifier: "list.clearChecked").button().tap()
        #expect(h.model.pendingActions.map(\.message) == ["Cleared 1 item"] && h.model.checked.isEmpty)
    }

    @Test("SHOP-04: no All done while something is unchecked")
    func notAllDone() async throws {
        let h = try await DetailHarness(seed: { db, list in
            try await seedItem(db, list: list, name: "Eggs", checked: true)
            try await seedItem(db, list: list, name: "Milk")
        })
        await h.settle { h.model.checked.count == 1 && h.model.unchecked.count == 1 }
        let view = try inspect(h)
        #expect(!has(view, "list.allDone") && !has(view, "list.clearChecked"))
    }

    @Test("SHOP-04: a checked row's checkbox unchecks at once")
    func uncheckTap() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Eggs", checked: true) })
        await h.settle { h.model.checked.count == 1 }
        try inspect(h).find(viewWithAccessibilityIdentifier: "list.checked.row.Eggs.check").button().tap()
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.pendingActions.isEmpty && h.model.unchecked.first?.name == "Eggs")
    }

    @Test("SHOP-04: AC6 the offline banner shows and the checkbox still works")
    func offline() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk") }, undoWindow: .seconds(30))
        await h.settle { h.model.unchecked.count == 1 }
        let view = try inspect(h, online: false)
        _ = try view.find(text: "Offline — changes will sync when you're back online")
        try view.find(viewWithAccessibilityIdentifier: "list.row.Milk.check").button().tap()
        #expect(h.model.pendingActions.count == 1)
    }
}
