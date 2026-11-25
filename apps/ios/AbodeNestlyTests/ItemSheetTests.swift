import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync
@testable import UI

private func seedFull(
    _ db: HouseholdDatabase, list: UUID, name: String, quantity: Double? = nil, unit: String? = nil, note: String? = nil
) async throws {
    try await db.upsert(table: "list_item", row: [
        "id": .string(UUID().uuidString.lowercased()), "list_id": .string(list.uuidString.lowercased()), "name": .string(name),
        "name_key": .string(NameKey.make(name)), "checked": .bool(false), "deleted_at": .null,
        "quantity": quantity.map { .double($0) } ?? .null, "unit": unit.map { .string($0) } ?? .null,
        "note": note.map { .string($0) } ?? .null, "created_at": .string("2026-11-01T10:00:00.000Z"),
    ])
}

private func data(_ json: String) throws -> [String: Any] {
    try #require(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
}

@Suite("SHOP-03 item sheet logic")
struct ItemSheetLogicTests {
    @Test("SHOP-03: fields trims, turns blanks into nil and parses a comma", arguments: [
        ("2", 2.0 as Double?, true), ("0,5", 0.5, true), ("1.25", 1.25, true), (" 3 ", 3.0, true), ("", nil, true),
        ("1.2345", nil, false), ("0", nil, false), ("-1", nil, false), ("abc", nil, false), ("1,2,3", nil, false), ("1.", nil, false),
    ])
    func quantity(text: String, expected: Double?, valid: Bool) {
        let fields = ItemSheetLogic.fields(ItemForm(name: "Milk", quantityText: text))
        #expect((fields != nil) == valid)
        #expect(fields?.quantity == expected)
    }

    @Test("SHOP-03: blank name is invalid; unit and note are trimmed, blank is nil")
    func nameUnitNote() {
        #expect(ItemSheetLogic.fields(ItemForm(name: "   ")) == nil)
        let fields = ItemSheetLogic.fields(ItemForm(name: " Milk ", unit: "  l ", note: "   "))
        #expect(fields == ItemFields(name: "Milk", quantity: nil, unit: "l", note: nil))
        #expect(ItemSheetLogic.fields(ItemForm(name: "Milk", note: " lactose free "))?.note == "lactose free")
    }

    @Test("SHOP-03: formatQty", arguments: [
        (2.0 as Double?, "l" as String?, "2 l"), (0.5, "kg", "0.5 kg"), (3.0, nil, "3"), (nil, "l", ""), (nil, nil, ""),
    ])
    func formatQty(q: Double?, unit: String?, expected: String) {
        #expect(ItemSheetLogic.formatQty(q, unit) == expected)
    }

    @Test("SHOP-03: unit suggestions are presets first, then household units, deduplicated, deleted rows ignored")
    func suggestions() {
        func row(_ unit: String?, deleted: Bool = false) -> ListItemRecord {
            ListItemRecord(id: UUID(), name: "x", deletedAt: deleted ? "2026-11-02T10:00:00.000Z" : nil, unit: unit)
        }
        let out = ItemSheetLogic.unitSuggestions(history: [row("bunch"), row("L"), row(" Bunch "), row("tin", deleted: true), row(nil)])
        #expect(out == ItemSheetLogic.unitPresets + ["bunch"])
    }

    @Test("SHOP-03: changes lists only what changed; a name change adds the local name_key")
    func changes() {
        let before = ListItemRecord(id: UUID(), name: "Milk", quantity: 2, unit: "l", note: "lactose free")
        #expect(ItemSheetLogic.changes(before: before, after: ItemFields(name: "Milk", quantity: 2, unit: "l", note: "lactose free")).fields.isEmpty)
        let note = ItemSheetLogic.changes(before: before, after: ItemFields(name: "Milk", quantity: 2, unit: "l", note: nil))
        #expect(note.fields == ["note": .null] && note.localOnly.isEmpty)
        let name = ItemSheetLogic.changes(before: before, after: ItemFields(name: "Oat Milk", quantity: 2, unit: "l", note: "lactose free"))
        #expect(name.fields == ["name": .string("Oat Milk")] && name.localOnly == ["name_key": .string("oat milk")])
    }
}

@Suite("SHOP-03 quantity stepper")
struct QuantityStepperLogicTests {
    @Test("SHOP-03: step", arguments: [
        ("", 1, "1"), ("2", 1, "3"), ("1", -1, ""), ("0.5", -1, ""), ("2.5", -1, "1.5"), ("", -1, ""), ("abc", 1, "abc"), ("0,5", 1, "1.5"),
    ])
    func step(text: String, direction: Int, expected: String) {
        #expect(QuantityText.step(text, by: direction) == expected)
    }
}

@MainActor
@Suite("SHOP-03 item sheet model", .serialized)
struct ItemSheetModelTests {
    @Test("SHOP-03: AC1 create with blank quantity, unit and note stores three nulls and queues one upsert with nulls")
    func createBlank() async throws {
        let h = try await DetailHarness()
        #expect(await h.model.create(ItemFields(name: "Milk")))
        await h.settle { h.model.unchecked.count == 1 }
        let local = try await h.db.queryForTesting("SELECT quantity, unit, note FROM list_item")
        #expect(local.count == 1 && local[0]["quantity"] == "NULL" && local[0]["unit"] == "NULL" && local[0]["note"] == "NULL")
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        let body = try data(queued[0].data)
        #expect(body["quantity"] is NSNull && body["unit"] is NSNull && body["note"] is NSNull)
    }

    @Test("SHOP-03: AC2 create with all three shows them on the model's row")
    func createFull() async throws {
        let h = try await DetailHarness()
        await h.model.create(ItemFields(name: "Milk", quantity: 2, unit: "l", note: "lactose free"))
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        #expect(item.quantity == 2 && item.unit == "l" && item.note == "lactose free")
        #expect(ItemSheetLogic.formatQty(item.quantity, item.unit) == "2 l")
    }

    @Test("SHOP-03: AC3 clearing the note queues only note: null and leaves the quantity")
    func clearNote() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedFull(db, list: list, name: "Milk", quantity: 2, unit: "l", note: "lactose free") })
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        #expect(await h.model.save(item, ItemFields(name: "Milk", quantity: 2, unit: "l", note: nil)))
        await h.settle { h.model.unchecked.first?.note == nil }
        let queued = try await h.outbox()
        #expect(queued.count == 1)
        let body = try data(queued[0].data)
        #expect(body.count == 1 && body["note"] is NSNull)
        #expect(h.model.unchecked.first?.quantity == 2)
    }

    @Test("SHOP-03: no change writes nothing; a name change queues name only and updates name_key locally")
    func noChangeAndRename() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedFull(db, list: list, name: "Milk", quantity: 2, unit: "l") })
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        #expect(await h.model.save(item, ItemFields(name: "Milk", quantity: 2, unit: "l", note: nil)))
        #expect(try await h.outbox().isEmpty)
        #expect(await h.model.save(item, ItemFields(name: "Oat Milk", quantity: 2, unit: "l", note: nil)))
        let body = try data(try await h.outbox()[0].data)
        #expect(body.count == 1 && body["name"] as? String == "Oat Milk")
        let local = try await h.db.queryForTesting("SELECT name_key FROM list_item")
        #expect(local[0]["name_key"] == "oat milk")
    }

    @Test("SHOP-03: delete hides the row at once, writes nothing in the window, then one delete")
    func deleteAfterWindow() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedFull(db, list: list, name: "Milk") }, undoWindow: .milliseconds(150))
        await h.settle { h.model.unchecked.count == 1 }
        h.model.delete(try #require(h.model.unchecked.first))
        #expect(h.model.unchecked.isEmpty && h.model.pendingActions.count == 1)
        #expect(try await h.outbox().isEmpty)
        await h.settle { h.model.pendingActions.isEmpty }
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "delete")
    }

    @Test("SHOP-03: Undo inside the window writes nothing and the row returns")
    func undo() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedFull(db, list: list, name: "Milk") }, undoWindow: .milliseconds(150))
        await h.settle { h.model.unchecked.count == 1 }
        let item = try #require(h.model.unchecked.first)
        h.model.delete(item)
        h.model.undo(item.id)
        try await Task.sleep(for: .milliseconds(300))
        #expect(h.model.unchecked.count == 1)
        #expect(try await h.outbox().isEmpty)
    }

    @Test("SHOP-03: flush writes a pending delete at once")
    func flush() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedFull(db, list: list, name: "Milk") })
        await h.settle { h.model.unchecked.count == 1 }
        h.model.delete(try #require(h.model.unchecked.first))
        await h.model.flush()
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "delete")
        #expect(h.model.pendingActions.isEmpty)
    }

    @Test("SHOP-03: the sheet closes when the edited row disappears")
    func rowGone() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedFull(db, list: list, name: "Milk") })
        await h.settle { h.model.unchecked.count == 1 }
        h.model.open(try #require(h.model.unchecked.first))
        #expect(h.model.editing != nil)
        try await h.db.remove(table: "list_item", id: try #require(h.model.unchecked.first).id)
        await h.settle { h.model.editing == nil }
        #expect(h.model.editing == nil)
    }
}
