import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync
@testable import UI

private func has(_ view: InspectableView<ViewType.ClassifiedView>, _ id: String) -> Bool {
    (try? view.find(viewWithAccessibilityIdentifier: id)) != nil
}

private actor SaveBox {
    var template: Bool?
    func set(_ value: Bool) { template = value }
}

@MainActor
@Suite("SHOP-11 templates view", .serialized)
struct TemplatesViewTests {
    private func detail(_ h: TemplatesHarness) throws -> InspectableView<ViewType.ClassifiedView> {
        try ListDetailView(model: h.model, title: "Weekly", isOnline: true).inspect()
    }

    private func sheet(_ h: TemplatesHarness) throws -> InspectableView<ViewType.ClassifiedView> {
        let model = h.model
        return try AddAllSheet(lists: model.targets, onPick: { await model.addAll(to: $0) }, onClose: {}).inspect()
    }

    @Test("SHOP-11: the Template switch is off by default")
    func switchOff() throws {
        let draft = NewListSheet.Draft()
        let view = try NewListSheet(stores: [], draft: draft, onSave: { _, _, _ in true }, onClose: {}).inspect()
        let toggle = try view.find(viewWithAccessibilityIdentifier: "newlist.template").toggle()
        #expect(try !toggle.isOn())
        #expect(!draft.isTemplate)
    }

    @Test("SHOP-11: AC1 Template on, then Save, hands the switch to the save and the list shows under Templates")
    func templateOnSave() async throws {
        let saved = SaveBox()
        let draft = NewListSheet.Draft()
        let view = try NewListSheet(stores: [], draft: draft, onSave: { _, _, isTemplate in
            await saved.set(isTemplate)
            return true
        }, onClose: {}).inspect()
        try view.find(viewWithAccessibilityIdentifier: "newlist.name").textField().setInput("Weekly")
        try view.find(viewWithAccessibilityIdentifier: "newlist.template").toggle().tap()
        #expect(draft.isTemplate)
        try view.find(viewWithAccessibilityIdentifier: "newlist.save").button().tap()
        for _ in 0..<400 where await saved.template == nil { await Task.yield() }
        #expect(await saved.template == true)

        let h = try await ListsHarness()
        _ = await h.model.create(name: "Weekly", storeId: nil, isTemplate: true)
        await h.settle { h.model.sections.templates.count == 1 }
        let lists = try ListsView(model: h.model, isOnline: true, onOpen: { _ in }).inspect()
        _ = try lists.find(text: "Templates")
        _ = try lists.find(text: "Weekly")
        #expect(h.model.sections.lists.isEmpty)
    }

    @Test("SHOP-11: a template shows its caption and Add all to list with nothing checked")
    func templateDetail() async throws {
        let h = try await TemplatesHarness()
        await h.settle { h.model.isTemplate }
        let view = try detail(h)
        _ = try view.find(viewWithAccessibilityIdentifier: "list.template")
        _ = try view.find(text: "Template")
        #expect(Templates.actions(isTemplate: h.model.isTemplate, hasChecked: !h.model.checked.isEmpty) == [.share, .addAll])
    }

    @Test("SHOP-11: a normal list has no caption and its menu has no Add all")
    func normalDetail() async throws {
        let d = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Eggs", checked: true) })
        await d.settle { d.model.checked.count == 1 }
        let view = try ListDetailView(model: d.model, title: "Groceries", isOnline: true).inspect()
        #expect(!has(view, "list.template"))
        #expect(!d.model.isTemplate)
        #expect(Templates.actions(isTemplate: d.model.isTemplate, hasChecked: !d.model.checked.isEmpty) == [.share, .clearChecked])
    }

    @Test("SHOP-11: AC2 picking Groceries copies the items, updates its count and shows the status message")
    func pickAddsAll() async throws {
        let h = try await TemplatesHarness(seed: { db, template in
            try await seedTemplateItem(db, list: template, name: "Milk", created: "2026-11-01T10:00:01.000Z")
            try await seedTemplateItem(db, list: template, name: "Eggs", created: "2026-11-01T10:00:02.000Z")
        })
        let overview = ListsModel(householdId: h.household, database: h.db, kick: {})
        let follower = Task { await overview.start() }
        defer { follower.cancel() }
        await h.settle { overview.loaded }

        let picker = try sheet(h)
        #expect(h.model.targets.count == 1)
        _ = try picker.find(text: "Groceries")
        try picker.find(viewWithAccessibilityIdentifier: "addall.row").button().tap()
        await h.settle { h.model.status != nil }
        await h.settle { overview.sections.lists.first?.count == "0 of 2" }
        #expect(overview.sections.lists.first?.count == "0 of 2")
        _ = try detail(h).find(text: "Added 2 items to Groceries")
        #expect(has(try detail(h), "list.status"))
    }

    @Test("SHOP-11: with no other list the sheet says Make a list first")
    func noTargets() async throws {
        let h = try await TemplatesHarness()
        let picker = try AddAllSheet(lists: [], onPick: { _ in .empty }, onClose: {}).inspect()
        _ = try picker.find(text: "Make a list first")
        #expect(!has(picker, "addall.row"))
        // The template itself is never a target.
        #expect(h.model.targets.map(\.name) == ["Groceries"])
    }

    @Test("SHOP-11: AC3 an empty template shows the nothing-to-add message and the target is unchanged")
    func emptyTemplate() async throws {
        let h = try await TemplatesHarness()
        await h.settle { h.model.isTemplate }
        let picker = try sheet(h)
        try picker.find(viewWithAccessibilityIdentifier: "addall.row").button().tap()
        await h.settle { h.model.status != nil }
        _ = try detail(h).find(text: "Nothing to add: this template is empty")
        #expect(try await h.db.liveListItems(listId: h.target).isEmpty)
    }
}
