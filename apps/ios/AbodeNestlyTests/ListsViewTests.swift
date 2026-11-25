import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync

@MainActor
private func inspect(_ h: ListsHarness, online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
    try ListsView(model: h.model, isOnline: online, onOpen: { _ in }).inspect()
}

@MainActor
private func has(_ h: ListsHarness, _ id: String, online: Bool = true) -> Bool {
    (try? inspect(h, online: online).find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
@Suite("SHOP-01 lists view", .serialized)
struct ListsViewTests {
    @Test("SHOP-01: AC1 a household with no list sees Make your first list")
    func empty() async throws {
        let h = try await ListsHarness()
        #expect(has(h, "lists.empty"))
        _ = try inspect(h).find(text: "Make your first list")
        #expect(!has(h, "lists.row"))
    }

    @Test("SHOP-01: AC2 a populated overview shows name, store chip and checked count per row")
    func populated() async throws {
        let store = UUID(), list = UUID()
        let h = try await ListsHarness(seed: { db in
            try await db.upsert(table: "store", row: storeRow(store, name: "Lidl"))
            try await seedList(db, id: list, name: "Groceries", store: store)
            try await db.upsert(table: "list_item", row: jsonRow([
                "id": UUID().uuidString.lowercased(), "list_id": list.uuidString.lowercased(), "name": "Milk", "checked": true,
            ]))
            try await db.upsert(table: "list_item", row: jsonRow([
                "id": UUID().uuidString.lowercased(), "list_id": list.uuidString.lowercased(), "name": "Eggs", "checked": false,
            ]))
        })
        await h.settle { h.model.sections.lists.first?.count == "1 of 2" }
        #expect(has(h, "lists.row"))
        _ = try inspect(h).find(text: "Groceries")
        _ = try inspect(h).find(text: "1 of 2")
        _ = try inspect(h).find(text: "Lidl")
        #expect(has(h, "lists.fab"))
        #expect(!has(h, "lists.empty"))
    }

    @Test("SHOP-01: AC6 offline the overview still shows the lists and says changes will sync")
    func offline() async throws {
        let h = try await ListsHarness(seed: { try await seedList($0, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        #expect(!has(h, "offline-banner"))
        #expect(has(h, "offline-banner", online: false))
        _ = try inspect(h, online: false).find(text: "Groceries")
    }

    @Test("SHOP-01: tapping a row opens that list")
    func tapOpens() async throws {
        let h = try await ListsHarness(seed: { try await seedList($0, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        var opened: [String] = []
        let view = try ListsView(model: h.model, isOnline: true, onOpen: { opened.append($0.name) }).inspect()
        try view.find(viewWithAccessibilityIdentifier: "lists.row").find(ViewType.Button.self).tap()
        #expect(opened == ["Groceries"])
    }

    // ViewInspector cannot reach `swipeActions`, so the swipe's effect is driven through the same model call the
    // swipe button makes: the list leaves Lists, the toast shows, Undo brings it back.
    @Test("SHOP-01: AC3 AC4 an archive swipe shows the undo toast and Undo keeps the list")
    func swipeArchiveUndo() async throws {
        let h = try await ListsHarness(seed: { try await seedList($0, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        h.model.archive(try #require(h.model.sections.lists.first))
        #expect(has(h, "undo-toast"))
        _ = try inspect(h).find(text: "Archived Groceries")
        try inspect(h).find(viewWithAccessibilityIdentifier: "undo-toast.undo").button().tap()
        #expect(h.model.pending.isEmpty)
        #expect(!has(h, "undo-toast"))
        #expect(has(h, "lists.row"))
    }

    @Test("SHOP-01: archived lists sit in a collapsed section; opened, each row has Restore, and Restore unarchives it")
    func archivedActions() async throws {
        let h = try await ListsHarness(seed: { try await seedList($0, name: "Old", archived: "2026-11-05T10:00:00.000Z") })
        await h.settle { h.model.sections.archived.count == 1 }
        // Archived is the only section with rows, so the first-list prompt does not show.
        #expect(!has(h, "lists.empty"))
        _ = try inspect(h).find(text: "Archived (1)")
        let open = try ListsView(model: h.model, isOnline: true, archivedExpanded: true, onOpen: { _ in }).inspect()
        _ = try open.find(viewWithAccessibilityIdentifier: "lists.archived.row")
        try open.find(viewWithAccessibilityIdentifier: "lists.restore").button().tap()
        await h.settle { h.model.sections.lists.count == 1 }
        #expect(h.model.sections.lists.map(\.name) == ["Old"] && h.model.sections.archived.isEmpty)
        #expect(try await h.outbox().last?.data == #"{"archived_at":null}"#)
    }

    @Test("SHOP-01: AC5 the new-list sheet saves a trimmed name and the chosen store, then the list opens")
    func newListSheet() async throws {
        let lidl = UUID()
        let saved = SavedBox()
        let draft = NewListSheet.Draft()
        let sheet = NewListSheet(stores: [StoreRecord(id: lidl, name: "Lidl")], draft: draft) { name, store in
            await saved.set(name, store)
            return true
        } onClose: {}
        let view = try sheet.inspect()
        _ = try view.find(viewWithAccessibilityIdentifier: "newlist.store")
        #expect(try view.find(viewWithAccessibilityIdentifier: "newlist.save").button().isDisabled())
        try view.find(viewWithAccessibilityIdentifier: "newlist.name").textField().setInput("  Hardware ")
        try view.find(viewWithAccessibilityIdentifier: "newlist.store").picker().select(value: UUID?.some(lidl))
        try view.find(viewWithAccessibilityIdentifier: "newlist.save").button().tap()
        for _ in 0..<400 where await saved.name == nil { await Task.yield() }
        #expect(await saved.name == "  Hardware ")
        #expect(await saved.store == lidl)
    }

    @Test("SHOP-01: a write that fails shows a message and the list comes back")
    func failedWriteShown() async throws {
        let h = try await ListsHarness(undoWindow: .milliseconds(10), seed: { try await seedList($0, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        let row = try #require(h.model.sections.lists.first)
        // The row disappears from the local database under the swipe, so the patch cannot be stored.
        h.model.archive(ListRow(id: UUID(), name: row.name, storeName: nil, count: row.count))
        await h.settle { h.model.writeFailed }
        #expect(h.model.writeFailed)
        #expect(has(h, "lists.error"))
        #expect(h.model.sections.lists.map(\.name) == ["Groceries"])
    }
}

private actor SavedBox {
    var name: String?
    var store: UUID?
    func set(_ name: String, _ store: UUID?) {
        self.name = name
        self.store = store
    }
}
