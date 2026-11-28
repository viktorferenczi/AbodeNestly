import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync
@testable import UI

private func has(_ view: InspectableView<ViewType.ClassifiedView>, _ id: String) -> Bool {
    (try? view.find(viewWithAccessibilityIdentifier: id)) != nil
}

/// The sheet wired to the model the way `ListDetailView` wires it, so a save goes through the real write.
@MainActor
private func sheetView(_ state: ItemSheetState, model: ListDetailModel) -> ItemSheetView {
    ItemSheetView(
        state: state, unitSuggestions: [], priceStoreName: model.priceStore?.name,
        lastBoughtLine: { model.lastBoughtLine(for: $0) },
        onSave: { fields in
            switch state {
            case .new: await model.create(fields)
            case .edit(let item): await model.save(item, fields)
            }
        },
        onDelete: { _ in }, onCancel: {}
    )
}

@MainActor
private func inspectList(_ h: DetailHarness) throws -> InspectableView<ViewType.ClassifiedView> {
    try ListDetailView(model: h.model, title: "Groceries", isOnline: true, checkedExpanded: true).inspect()
}

@MainActor
private func seededHarness(withStore: Bool = true) async throws -> DetailHarness {
    let h = try await DetailHarness(seed: { db, list in
        if withStore { try await seedPriceList(db, list: list) }
        try await seedItem(db, list: list, name: "Milk")
        try await seedPrice(db, key: "milk", price: 1.2, seenAt: "2026-11-03T12:00:00.000Z")
    })
    await h.settle { h.model.unchecked.count == 1 && h.model.priceSnapshot.prices.count == 1 && (!withStore || h.model.priceStore != nil) }
    return h
}

@MainActor
@Suite("SHOP-12 price views", .serialized)
struct PriceViewTests {
    @Test("SHOP-12: AC3 a list with no store has no price field, in new and in edit")
    func noField() async throws {
        let h = try await seededHarness(withStore: false)
        let item = try #require(h.model.unchecked.first)
        #expect(!has(try sheetView(.new(prefill: "Eggs"), model: h.model).inspect(), "item.price"))
        #expect(!has(try sheetView(.edit(item), model: h.model).inspect(), "item.price"))
    }

    @Test("SHOP-12: a list with a store offers the field, labelled with the store, empty on open, in new and in edit")
    func field() async throws {
        let h = try await seededHarness()
        let item = try #require(h.model.unchecked.first)
        for state in [ItemSheetState.new(prefill: "Eggs"), ItemSheetState.edit(item)] {
            let view = try sheetView(state, model: h.model).inspect()
            let field = try view.find(viewWithAccessibilityIdentifier: "item.price")
            #expect(try field.accessibilityLabel().string() == "Price at Test Market")
            #expect(try field.textField().input() == "")
            #expect(!has(view, "item.priceError"))
        }
    }

    @Test("SHOP-12: AC2 the row shows the last price and the store name under the name")
    func rowCaption() async throws {
        let h = try await seededHarness()
        let item = try #require(h.model.unchecked.first)
        let expected: String = try #require(h.model.priceCaption(for: item))
        #expect(expected.hasSuffix(" · Test Market"))
        #expect(try inspectList(h).find(viewWithAccessibilityIdentifier: "list.row.Milk.price").text().string() == expected)
    }

    @Test("SHOP-12: a row with no price has no caption")
    func noCaption() async throws {
        let h = try await DetailHarness(seed: { db, list in
            try await seedPriceList(db, list: list)
            try await seedItem(db, list: list, name: "Eggs")
        })
        await h.settle { h.model.unchecked.count == 1 }
        #expect(!has(try inspectList(h), "list.row.Eggs.price"))
    }

    @Test("SHOP-12: the Last bought line shows in edit, and in new once a name is typed")
    func lastBought() async throws {
        let h = try await seededHarness()
        let item = try #require(h.model.unchecked.first)
        let edit = try sheetView(.edit(item), model: h.model).inspect()
        let line = try edit.find(viewWithAccessibilityIdentifier: "item.lastBought").text().string()
        #expect(line.hasPrefix("Last bought: "))
        #expect(line.hasSuffix("1.20 at Test Market"))

        #expect(!has(try sheetView(.new(prefill: ""), model: h.model).inspect(), "item.lastBought"))
        let typed = sheetView(.new(prefill: ""), model: h.model)
        try typed.inspect().find(viewWithAccessibilityIdentifier: "item.name").textField().setInput("milk")
        #expect(has(try typed.inspect(), "item.lastBought"))
        #expect(has(try sheetView(.new(prefill: "Milk"), model: h.model).inspect(), "item.lastBought"))
        #expect(!has(try sheetView(.new(prefill: "Eggs"), model: h.model).inspect(), "item.lastBought"))
    }

    @Test("SHOP-12: entering 1,5 and saving updates the row caption to 1.50")
    func enterAndSave() async throws {
        let h = try await seededHarness()
        let item = try #require(h.model.unchecked.first)
        let view = sheetView(.edit(item), model: h.model)
        try view.inspect().find(viewWithAccessibilityIdentifier: "item.price").textField().setInput("1,5")
        #expect(try !view.inspect().find(viewWithAccessibilityIdentifier: "item.save").button().isDisabled())
        try view.inspect().find(viewWithAccessibilityIdentifier: "item.save").button().tap()
        await h.settle { h.model.priceSnapshot.prices.count == 2 }
        let caption = try inspectList(h).find(viewWithAccessibilityIdentifier: "list.row.Milk.price").text().string()
        #expect(caption.contains("1.50"))
        #expect(caption.hasSuffix(" · Test Market"))
    }

    @Test("SHOP-12: an invalid price shows the message and turns Save off")
    func invalid() async throws {
        let h = try await seededHarness()
        let item = try #require(h.model.unchecked.first)
        let view = sheetView(.edit(item), model: h.model)
        for text in ["1.234", "abc", "-1"] {
            try view.inspect().find(viewWithAccessibilityIdentifier: "item.price").textField().setInput(text)
            let inspected = try view.inspect()
            _ = try inspected.find(text: "Enter a price like 1.20")
            #expect(try inspected.find(viewWithAccessibilityIdentifier: "item.save").button().isDisabled())
        }
        try view.inspect().find(viewWithAccessibilityIdentifier: "item.price").textField().setInput("1.20")
        #expect(!has(try view.inspect(), "item.priceError"))
        #expect(try !view.inspect().find(viewWithAccessibilityIdentifier: "item.save").button().isDisabled())
    }
}
