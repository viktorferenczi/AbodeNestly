import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync

@MainActor
private func inspect(_ h: StoresHarness, online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
    try StoresView(model: h.model, isOnline: online).inspect()
}

@MainActor
private func has(_ h: StoresHarness, _ id: String, online: Bool = true) -> Bool {
    (try? inspect(h, online: online).find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
private func inspect(_ h: DetailHarness) throws -> InspectableView<ViewType.ClassifiedView> {
    try ListDetailView(model: h.model, title: "Groceries", isOnline: true).inspect()
}

@MainActor
@Suite("SHOP-05 stores view", .serialized)
struct StoresViewTests {
    @Test("SHOP-05: AC1 no stores shows the empty state with New store")
    func empty() async throws {
        let h = try await StoresHarness()
        #expect(has(h, "stores.empty"))
        _ = try inspect(h).find(text: "No stores yet")
        #expect(has(h, "stores.empty.new"))
        #expect(!has(h, "stores.row"))
    }

    @Test("SHOP-05: AC2 a populated screen shows the name and, when learned, the item count")
    func populated() async throws {
        let market = UUID(), plain = UUID()
        let h = try await StoresHarness(seed: { db, household in
            try await db.upsert(table: "store", row: storeRow(market, name: "Test Market"))
            try await db.upsert(table: "store", row: storeRow(plain, name: "Aldi"))
            try await seedOrder(db, household: household, store: market, keys: ["a", "b", "c"])
        })
        await h.settle { h.model.rows.map(\.learned) == [0, 3] }
        _ = try inspect(h).find(text: "Test Market")
        _ = try inspect(h).find(text: "learned 3 items")
        #expect(!has(h, "stores.empty"))
    }

    @Test("SHOP-05: offline the screen still works and says changes will sync")
    func offline() async throws {
        let h = try await StoresHarness()
        #expect(!has(h, "offline-banner"))
        #expect(has(h, "offline-banner", online: false))
    }

    // ViewInspector cannot reach `swipeActions`; the swipe's effect is driven through the model call its button makes.
    @Test("SHOP-05: a delete shows the undo toast and Undo brings the store back")
    func deleteUndo() async throws {
        let h = try await StoresHarness(seed: { db, _ in try await db.upsert(table: "store", row: storeRow(name: "Test Market")) })
        await h.settle { h.model.rows.count == 1 }
        h.model.delete(try #require(h.model.rows.first))
        _ = try inspect(h).find(text: "Deleted Test Market")
        try inspect(h).find(viewWithAccessibilityIdentifier: "undo-toast.undo").button().tap()
        #expect(has(h, "stores.row"))
        #expect(!has(h, "undo-toast"))
    }

    @Test("SHOP-05: the store sheet blocks empty and duplicate names with an inline message and saves a good one")
    func sheet() async throws {
        let saved = SavedName()
        let draft = StoreSheet.Draft()
        let sheet = StoreSheet(stores: [StoreRecord(id: UUID(), name: "Test Market")], draft: draft) { name in
            await saved.set(name)
            return true
        } onClose: {}
        let view = try sheet.inspect()
        #expect(try view.find(viewWithAccessibilityIdentifier: "storesheet.save").button().isDisabled())
        try view.find(viewWithAccessibilityIdentifier: "storesheet.name").textField().setInput("test market")
        _ = try sheet.inspect().find(text: "You already have Test Market")
        #expect(try sheet.inspect().find(viewWithAccessibilityIdentifier: "storesheet.save").button().isDisabled())
        try sheet.inspect().find(viewWithAccessibilityIdentifier: "storesheet.name").textField().setInput("  Corner Shop ")
        try sheet.inspect().find(viewWithAccessibilityIdentifier: "storesheet.save").button().tap()
        for _ in 0..<400 where await saved.name == nil { await Task.yield() }
        #expect(await saved.name == "  Corner Shop ")
    }

    @Test("SHOP-05: AC3 the chip reads Add store, then the picked store after Save")
    func assignStore() async throws {
        let store = UUID()
        let h = try await DetailHarness(seed: { db, _ in try await db.upsert(table: "store", row: storeRow(store, name: "Test Market")) })
        await h.settle { h.model.snapshot.stores.count == 1 }
        _ = try inspect(h).find(text: "Add store")
        let chip = try inspect(h).find(viewWithAccessibilityIdentifier: "list.store")
        #expect(try chip.accessibilityLabel().string() == "Add store")

        let draft = StorePickerSheet.Draft(selection: nil)
        let picker = StorePickerSheet(
            stores: h.model.snapshot.stores, current: nil, draft: draft, onCreate: { await h.model.createStore(name: $0) },
            onSave: { await h.model.setStore($0) }, onManage: {}, onClose: {}
        )
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.row").button().tap()
        #expect(draft.selection == store)
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.save").button().tap()
        await h.settle { h.model.snapshot.storeName == "Test Market" }
        _ = try inspect(h).find(text: "Test Market")
        #expect(try inspect(h).find(viewWithAccessibilityIdentifier: "list.store").accessibilityLabel().string() == "Store: Test Market")
        #expect(try await h.db.outboxForTesting().filter { $0.table == "shopping_list" }.last?.data.contains("store_id") == true)
    }

    @Test("SHOP-05: choosing No store clears the chip back to Add store")
    func clearStore() async throws {
        let store = UUID()
        let h = try await DetailHarness(seed: { db, list in
            try await db.upsert(table: "store", row: storeRow(store, name: "Test Market"))
            try await db.patch(table: "shopping_list", id: list, fields: ["store_id": .string(store.uuidString.lowercased())])
        })
        await h.settle { h.model.snapshot.storeName == "Test Market" }
        let draft = StorePickerSheet.Draft(selection: store)
        let picker = StorePickerSheet(
            stores: h.model.snapshot.stores, current: store, draft: draft, onCreate: { _ in nil },
            onSave: { await h.model.setStore($0) }, onManage: {}, onClose: {}
        )
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.none").button().tap()
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.save").button().tap()
        await h.settle { h.model.snapshot.storeName == nil }
        _ = try inspect(h).find(text: "Add store")
    }

    @Test("SHOP-05: the picker's inline New store creates and selects in one step, and rejects a duplicate")
    func inlineCreate() async throws {
        let id = UUID()
        let h = try await DetailHarness(makeId: { id })
        let draft = StorePickerSheet.Draft(selection: nil)
        let picker = StorePickerSheet(
            stores: [], current: nil, draft: draft, onCreate: { await h.model.createStore(name: $0) },
            onSave: { await h.model.setStore($0) }, onManage: {}, onClose: {}
        )
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.newname").textField().setInput("Corner Shop")
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.add").button().tap()
        for _ in 0..<400 where draft.created.isEmpty { await Task.yield() }
        #expect(draft.selection == id)
        #expect(draft.created.map(\.name) == ["Corner Shop"])
        _ = try picker.inspect().find(text: "Corner Shop")
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.newname").textField().setInput("corner shop")
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.add").button().tap()
        #expect(draft.problem == .duplicate("Corner Shop"))
    }

    @Test("SHOP-05: the Shopping header has a Stores button that opens the screen")
    func listsHeaderButton() async throws {
        let h = try await ListsHarness()
        var opened = 0
        let view = try ListsView(model: h.model, isOnline: true, onOpenStores: { opened += 1 }, onOpen: { _ in }).inspect()
        try view.find(viewWithAccessibilityIdentifier: "lists.stores").button().tap()
        #expect(opened == 1)
    }
}

private actor SavedName {
    var name: String?
    func set(_ name: String) { self.name = name }
}

@MainActor
@Suite("SHOP-05 picker fallbacks", .serialized)
struct StorePickerFallbackTests {
    @Test("SHOP-05: a store deleted elsewhere keeps the list's value, so Save writes nothing")
    func deletedCurrentKeepsLink() async throws {
        let gone = UUID()
        let wrote = SavedName()
        let draft = StorePickerSheet.Draft(selection: gone)
        let picker = StorePickerSheet(
            stores: [], current: gone, draft: draft, onCreate: { _ in nil },
            onSave: { _ in
                await wrote.set("saved")
                return true
            }, onManage: {}, onClose: { Task { await wrote.set("closed") } }
        )
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.save").button().tap()
        for _ in 0..<400 where await wrote.name == nil { await Task.yield() }
        #expect(await wrote.name == "closed")
    }
}

@MainActor
@Suite("SHOP-05 picker manage and seen", .serialized)
struct StorePickerManageTests {
    @Test("SHOP-05: Manage stores calls onManage")
    func manage() async throws {
        let called = SavedName()
        let picker = StorePickerSheet(
            stores: [], current: nil, onCreate: { _ in nil }, onSave: { _ in true },
            onManage: { Task { await called.set("manage") } }, onClose: {}
        )
        try picker.inspect().find(viewWithAccessibilityIdentifier: "storepicker.manage").button().tap()
        for _ in 0..<400 where await called.name == nil { await Task.yield() }
        #expect(await called.name == "manage")
    }

    @Test("SHOP-05: a created store seen in the live list and then deleted elsewhere is not offered again")
    func seenThenGone() async throws {
        let created = StoreRecord(id: UUID(), name: "Corner Shop")
        let draft = StorePickerSheet.Draft(selection: created.id)
        draft.created = [created]
        draft.seen = [created.id] // what the stores observation records once the row appears
        let picker = StorePickerSheet(
            stores: [], current: nil, draft: draft, onCreate: { _ in nil }, onSave: { _ in true }, onManage: {}, onClose: {}
        )
        #expect((try? picker.inspect().find(text: "Corner Shop")) == nil)
    }

    @Test("SHOP-05: a store still inside its undo window keeps its name taken")
    func pendingNameTaken() async throws {
        let h = try await StoresHarness(seed: { db, _ in try await db.upsert(table: "store", row: storeRow(name: "Lidl")) })
        await h.settle { h.model.rows.count == 1 }
        h.model.delete(try #require(h.model.rows.first))
        #expect(await h.model.create(name: "lidl") == nil)
        #expect(try await h.outbox().isEmpty)
    }
}
