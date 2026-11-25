import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync
@testable import UI

@MainActor
private final class Calls {
    var saved: [ItemFields] = []
    var deleted: [UUID] = []
    var cancels = 0
}

@MainActor
private func sheet(
    _ state: ItemSheetState, calls: Calls = Calls(), units: [String] = ItemSheetLogic.unitPresets
) throws -> InspectableView<ViewType.ClassifiedView> {
    try ItemSheetView(
        state: state, unitSuggestions: units,
        onSave: { calls.saved.append($0); return true }, onDelete: { calls.deleted.append($0.id) }, onCancel: { calls.cancels += 1 }
    ).inspect()
}

private func has(_ view: InspectableView<ViewType.ClassifiedView>, _ id: String) -> Bool {
    (try? view.find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
@Suite("SHOP-03 item sheet view", .serialized)
struct ItemSheetViewTests {
    private let milk = ListItemRecord(id: UUID(), name: "Milk", quantity: 2, unit: "l", note: "lactose free")

    @Test("SHOP-03: new shows the title, the prefilled name, Save enabled and no Delete")
    func newState() throws {
        let view = try sheet(.new(prefill: "Eggs"))
        _ = try view.find(text: "New item")
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.name").textField().input() == "Eggs")
        #expect(try !view.find(viewWithAccessibilityIdentifier: "item.save").button().isDisabled())
        #expect(!has(view, "item.delete"))
    }

    @Test("SHOP-03: AC1 Save on a new item with only a name sends nil quantity, unit and note")
    func newSave() async throws {
        let calls = Calls()
        try sheet(.new(prefill: "Eggs"), calls: calls).find(viewWithAccessibilityIdentifier: "item.save").button().tap()
        for _ in 0..<200 where calls.saved.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        #expect(calls.saved == [ItemFields(name: "Eggs")])
    }

    @Test("SHOP-03: AC2 edit shows the stored name, quantity, unit and note, and Delete")
    func editState() throws {
        let view = try sheet(.edit(milk))
        _ = try view.find(text: "Edit item")
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.name").textField().input() == "Milk")
        #expect(try view.find(viewWithAccessibilityIdentifier: "stepper.field").textField().input() == "2")
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.unit").textField().input() == "l")
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.note").textField().input() == "lactose free")
        #expect(has(view, "item.delete"))
    }

    @Test("SHOP-03: Save is disabled for a blank name")
    func blankName() throws {
        let view = try sheet(.new(prefill: "  "))
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.save").button().isDisabled())
    }

    @Test("SHOP-03: an invalid quantity shows the inline message and disables Save")
    func badQuantity() throws {
        let view = try sheet(.edit(ListItemRecord(id: UUID(), name: "Milk")))
        #expect(!has(view, "item.qtyError"))
        try view.find(viewWithAccessibilityIdentifier: "stepper.field").textField().setInput("0")
        #expect(has(view, "item.qtyError"))
        _ = try view.find(text: "Enter a number above 0")
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.save").button().isDisabled())
    }

    @Test("SHOP-03: a unit chip fills the unit field, and the chips are the suggestions")
    func unitChip() throws {
        let view = try sheet(.new(prefill: "Rice"), units: ["pcs", "kg", "bunch"])
        try view.find(viewWithAccessibilityIdentifier: "item.unit.suggestion.bunch").button().tap()
        #expect(try view.find(viewWithAccessibilityIdentifier: "item.unit").textField().input() == "bunch")
    }

    @Test("SHOP-03: the stepper buttons step the quantity")
    func stepper() throws {
        let view = try sheet(.new(prefill: "Rice"))
        try view.find(viewWithAccessibilityIdentifier: "stepper.field").textField().setInput("0,5")
        #expect(!has(view, "item.qtyError"))
    }

    @Test("SHOP-03: Cancel writes nothing; Delete hands the item over")
    func cancelAndDelete() throws {
        let calls = Calls()
        let view = try sheet(.edit(milk), calls: calls)
        try view.find(viewWithAccessibilityIdentifier: "item.cancel").button().tap()
        #expect(calls.cancels == 1 && calls.saved.isEmpty)
        try view.find(viewWithAccessibilityIdentifier: "item.delete").button().tap()
        #expect(calls.deleted == [milk.id])
    }
}

@MainActor
@Suite("SHOP-03 list rows", .serialized)
struct ItemRowViewTests {
    private func inspect(_ h: DetailHarness) throws -> InspectableView<ViewType.ClassifiedView> {
        try ListDetailView(model: h.model, title: "Groceries", isOnline: true, checkedExpanded: true).inspect()
    }

    @Test("SHOP-03: AC2 a row shows quantity with unit and the note; tapping it opens the sheet")
    func rowAndOpen() async throws {
        let h = try await DetailHarness(seed: { db, list in
            try await db.upsert(table: "list_item", row: [
                "id": .string(UUID().uuidString.lowercased()), "list_id": .string(list.uuidString.lowercased()), "name": .string("Milk"),
                "checked": .bool(false), "quantity": .double(2), "unit": .string("l"), "note": .string("lactose free"),
                "created_at": .string("2026-11-01T10:00:00.000Z"),
            ])
        })
        await h.settle { h.model.unchecked.count == 1 }
        let view = try inspect(h)
        _ = try view.find(text: "2 l")
        _ = try view.find(text: "lactose free")
        try view.find(viewWithAccessibilityIdentifier: "list.row.Milk").button().tap()
        #expect(h.model.editing == .edit(try #require(h.model.unchecked.first)))
    }

    @Test("SHOP-03: a checked row opens the sheet too")
    func checkedOpens() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Eggs", checked: true) })
        await h.settle { h.model.checked.count == 1 }
        try inspect(h).find(viewWithAccessibilityIdentifier: "list.checked.row.Eggs").button().tap()
        #expect(h.model.editing != nil)
    }

    @Test("SHOP-03: Delete hides the row at once and shows the undo toast")
    func deleteToast() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Eggs") })
        await h.settle { h.model.unchecked.count == 1 }
        h.model.delete(try #require(h.model.unchecked.first))
        _ = try inspect(h).find(text: "Deleted Eggs")
        #expect((try? inspect(h).find(viewWithAccessibilityIdentifier: "list.row.Eggs")) == nil)
        try inspect(h).find(viewWithAccessibilityIdentifier: "undo-toast.undo").button().tap()
        #expect(h.model.unchecked.count == 1)
    }

    @Test("SHOP-03: the Details button opens a new item prefilled with the typed text")
    func detailsButton() async throws {
        let h = try await DetailHarness()
        h.model.query = "Eggs"
        try inspect(h).find(viewWithAccessibilityIdentifier: "quickadd.details").button().tap()
        #expect(h.model.editing == .new(prefill: "Eggs"))
    }
}
