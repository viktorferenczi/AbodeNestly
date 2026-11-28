import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)
private let templateCreated = "2026-11-01T09:00:00.000Z"
private let targetCreated = "2026-11-01T10:00:00.000Z"

private func uuidText(_ id: UUID) -> String { id.uuidString.lowercased() }

private func decodeData(_ text: String) throws -> [String: JSONValue] {
    try JSONDecoder().decode([String: JSONValue].self, from: Data(text.utf8))
}

/// A template ("Weekly") and a normal list ("Groceries") with a model on the template, following the local rows.
@MainActor
final class TemplatesHarness {
    let household = UUID()
    let template = UUID()
    let target = UUID()
    let user = UUID()
    let db: HouseholdDatabase
    let model: ListDetailModel
    private let kicks = KickCounter()
    private var follower: Task<Void, Never>?
    private var seededOutbox = 0

    init(
        seed: (HouseholdDatabase, UUID) async throws -> Void = { _, _ in }, statusWindow: Duration = .seconds(60)
    ) async throws {
        let db = try await openSyncDatabase(household)
        self.db = db
        try await seedList(db, id: template, name: "Weekly", template: true, created: templateCreated)
        try await seedList(db, id: target, name: "Groceries", created: targetCreated)
        try await seed(db, template)
        seededOutbox = try await db.outboxForTesting().count
        let kicks = kicks
        model = ListDetailModel(
            householdId: household, listId: template, userId: user, database: db, kick: { await kicks.add() }, now: { fixedNow },
            makeId: { UUID() }, undoWindow: .seconds(60), statusWindow: statusWindow
        )
        let model = model
        follower = Task { await model.start() }
        await settle { model.loaded && model.lists.count == 2 }
    }

    var kickCount: Int { get async { await kicks.count } }

    func settle(until condition: @MainActor () -> Bool) async {
        for _ in 0..<2000 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
    }

    /// Outbox rows queued after the seeding.
    func outbox(_ table: String? = nil) async throws -> [(table: String, op: String, rowId: String, data: String)] {
        let all = try await db.outboxForTesting().dropFirst(seededOutbox)
        var rows: [(table: String, op: String, rowId: String, data: String)] = []
        for row in all where table == nil || row.table == table { rows.append((row.table, row.op, row.rowId, row.data)) }
        return rows
    }

    var groceries: ListRecord { ListRecord(id: target, name: "Groceries", createdAt: targetCreated) }

    deinit { follower?.cancel() }
}

func seedTemplateItem(
    _ db: HouseholdDatabase, list: UUID, name: String, quantity: Double? = nil, unit: String? = nil, note: String? = nil,
    barcode: String? = nil, checked: Bool = false, checkedAt: String? = nil, deleted: Bool = false,
    created: String = "2026-11-01T10:00:00.000Z", id: UUID = UUID()
) async throws {
    var row: [String: JSONValue] = [
        "id": .string(uuidText(id)), "list_id": .string(uuidText(list)), "name": .string(name),
        "checked": .bool(checked), "created_at": .string(created),
    ]
    row["quantity"] = quantity.map { .double($0) } ?? .null
    row["unit"] = unit.map { .string($0) } ?? .null
    row["note"] = note.map { .string($0) } ?? .null
    row["barcode"] = barcode.map { .string($0) } ?? .null
    row["checked_at"] = checkedAt.map { .string($0) } ?? .null
    row["deleted_at"] = deleted ? .string("2026-11-02T10:00:00.000Z") : .null
    try await db.upsert(table: "list_item", row: row)
}

@Suite("SHOP-11 template logic")
struct TemplatesLogicTests {
    private func list(_ name: String, template: Bool = false, archived: String? = nil, created: String) -> ListRecord {
        ListRecord(id: UUID(), name: name, isTemplate: template, archivedAt: archived, createdAt: created)
    }

    @Test("SHOP-11: targets are the active non-template lists, oldest first")
    func targets() {
        let lists: [ListRecord] = [
            list("Newer", created: "2026-11-03T10:00:00.000Z"),
            list("Weekly", template: true, created: "2026-11-01T10:00:00.000Z"),
            list("Old", archived: "2026-11-05T10:00:00.000Z", created: "2026-11-01T10:00:00.000Z"),
            list("Older", created: "2026-11-02T10:00:00.000Z"),
        ]
        #expect(Templates.targets(lists: lists).map(\.name) == ["Older", "Newer"])
        #expect(Templates.targets(lists: []).isEmpty)
    }

    @Test("SHOP-11/SHOP-13: the List actions menu always offers Share first, then Add all on a template; a normal list never offers Add all", arguments: [
        (true, false, [ListAction.share, ListAction.addAll]), (true, true, [ListAction.share, ListAction.addAll, ListAction.clearChecked]),
        (false, true, [ListAction.share, ListAction.clearChecked]), (false, false, [ListAction.share]),
    ])
    func actions(isTemplate: Bool, hasChecked: Bool, expected: [ListAction]) {
        #expect(Templates.actions(isTemplate: isTemplate, hasChecked: hasChecked) == expected)
    }

    @Test("SHOP-11: result messages")
    func messages() {
        #expect(AddAllResult.added(count: 3, listName: "Groceries").message == "Added 3 items to Groceries")
        #expect(AddAllResult.added(count: 1, listName: "Groceries").message == "Added 1 item to Groceries")
        #expect(AddAllResult.empty.message == "Nothing to add: this template is empty")
        #expect(AddAllResult.failed.message == nil)
    }

    @Test("SHOP-11: AC2 copies are fresh unchecked rows in the template's display order, a millisecond apart")
    func copies() {
        let template = UUID(), target = UUID(), household = UUID(), user = UUID()
        let sourceIds: [UUID] = (0..<5).map { _ in UUID() }
        let items: [ListItemRecord] = [
            ListItemRecord(
                id: sourceIds[0], listId: template, name: "Milk", checked: false, createdAt: "2026-11-01T10:00:02.000Z",
                quantity: 2, unit: "l", note: "lactose free", barcode: "5901234123457"
            ),
            ListItemRecord(id: sourceIds[1], listId: template, name: "Eggs", checked: false, createdAt: "2026-11-01T10:00:01.000Z"),
            ListItemRecord(
                id: sourceIds[2], listId: template, name: "Bread", checked: true, createdAt: "2026-11-01T10:00:00.000Z",
                checkedAt: "2026-11-02T10:00:00.000Z"
            ),
            ListItemRecord(
                id: sourceIds[3], listId: template, name: "Rice", checked: true, createdAt: "2026-11-01T10:00:00.000Z",
                checkedAt: "2026-11-03T10:00:00.000Z"
            ),
            ListItemRecord(
                id: sourceIds[4], listId: template, name: "Gone", checked: false, deletedAt: "2026-11-04T10:00:00.000Z",
                createdAt: "2026-11-01T10:00:00.000Z"
            ),
            ListItemRecord(id: UUID(), listId: UUID(), name: "Elsewhere", createdAt: "2026-11-01T10:00:00.000Z"),
        ]
        var counter = 0
        let rows = Templates.copies(
            of: items, templateId: template, targetListId: target, householdId: household, userId: user, now: fixedNow,
            uuid: {
                counter += 1
                return UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", counter)) ?? UUID()
            }
        )
        let names: [JSONValue?] = rows.map { $0["name"] }
        // Unchecked oldest first, then checked with the newest check first; deleted and other lists' items are skipped.
        #expect(names == [.string("Eggs"), .string("Milk"), .string("Rice"), .string("Bread")])
        let created: [JSONValue?] = rows.map { $0["created_at"] }
        #expect(created == [
            .string("2027-01-15T08:00:00.000Z"), .string("2027-01-15T08:00:00.001Z"),
            .string("2027-01-15T08:00:00.002Z"), .string("2027-01-15T08:00:00.003Z"),
        ])
        let ids: Set<JSONValue?> = Set(rows.map { $0["id"] })
        #expect(ids.count == 4)
        for source in sourceIds { #expect(!ids.contains(.string(uuidText(source)))) }
        let milk = rows[1]
        #expect(milk["quantity"] == .double(2) && milk["unit"] == .string("l") && milk["note"] == .string("lactose free"))
        #expect(milk["barcode"] == .string("5901234123457") && milk["name_key"] == .string("milk"))
        for row in rows {
            #expect(row["list_id"] == .string(uuidText(target)) && row["household_id"] == .string(uuidText(household)))
            #expect(row["checked"] == .bool(false) && row["checked_at"] == .null && row["checked_by"] == .null)
            #expect(row["manual_position"] == .null && row["source"] == .string("manual"))
            #expect(row["pantry_item_id"] == .null && row["maint_task_id"] == .null && row["deleted_at"] == .null)
            #expect(row["created_by"] == .string(uuidText(user)))
        }
    }

    @Test("SHOP-11: the copies keep the template's order even when the ids sort the other way")
    func orderIgnoresIds() {
        let template = UUID()
        let ids: [UUID] = [
            UUID(uuidString: "ccccccc0-0000-4000-8000-000000000000") ?? UUID(),
            UUID(uuidString: "bbbbbbb0-0000-4000-8000-000000000000") ?? UUID(),
            UUID(uuidString: "aaaaaaa0-0000-4000-8000-000000000000") ?? UUID(),
        ]
        let names = ["first", "second", "third"]
        var items: [ListItemRecord] = []
        for index in 0..<3 {
            items.append(ListItemRecord(
                id: ids[index], listId: template, name: names[index], createdAt: "2026-11-01T10:00:0\(index).000Z"
            ))
        }
        let rows = Templates.copies(
            of: items, templateId: template, targetListId: UUID(), householdId: UUID(), userId: nil, now: fixedNow, uuid: { UUID() }
        )
        var stamped: [(created: String, name: String)] = []
        for row in rows {
            var created = "", name = ""
            if case .string(let text)? = row["created_at"] { created = text }
            if case .string(let text)? = row["name"] { name = text }
            stamped.append((created, name))
        }
        stamped.sort { $0.created < $1.created }
        #expect(stamped.map(\.name) == ["first", "second", "third"])
        #expect(rows.map { $0["created_by"] } == [.null, .null, .null])
    }
}

@MainActor
@Suite("SHOP-11 templates", .serialized)
struct TemplatesTests {
    private func seedThree(_ db: HouseholdDatabase, _ template: UUID) async throws {
        try await seedTemplateItem(db, list: template, name: "Milk", quantity: 2, unit: "l", created: "2026-11-01T10:00:01.000Z")
        try await seedTemplateItem(db, list: template, name: "Eggs", note: "free range", created: "2026-11-01T10:00:02.000Z")
        try await seedTemplateItem(
            db, list: template, name: "Bread", checked: true, checkedAt: "2026-11-02T10:00:00.000Z", created: "2026-11-01T10:00:03.000Z"
        )
        try await seedTemplateItem(db, list: template, name: "Gone", deleted: true)
    }

    @Test("SHOP-11: AC2 Add all queues one list_item upsert per live item and none for the template, and kicks once")
    func addAll() async throws {
        let h = try await TemplatesHarness(seed: { db, template in try await self.seedThree(db, template) })
        let result = await h.model.addAll(to: h.groceries)
        #expect(result == .added(count: 3, listName: "Groceries"))

        let queued = try await h.outbox()
        #expect(queued.count == 3)
        #expect(queued.allSatisfy { $0.table == "list_item" && $0.op == "upsert" })
        var rowIds: Set<String> = []
        for row in queued {
            rowIds.insert(row.rowId)
            let data = try decodeData(row.data)
            #expect(data["list_id"] == .string(uuidText(h.target)) && data["checked"] == .bool(false))
            #expect(data["id"] == nil && data["created_at"] == nil)
        }
        #expect(rowIds.count == 3)

        let copied = try await h.db.liveListItems(listId: h.target)
        #expect(copied.count == 3 && copied.allSatisfy { !$0.checked && $0.checkedAt == nil })
        #expect(copied.map(\.name).sorted() == ["Bread", "Eggs", "Milk"])
        let milk = try #require(copied.first { $0.name == "Milk" })
        #expect(milk.quantity == 2 && milk.unit == "l")
        #expect(copied.first { $0.name == "Eggs" }?.note == "free range")
        // The template is unchanged.
        #expect(try await h.db.liveListItems(listId: h.template).count == 3)
        #expect(await h.kickCount == 1)
        #expect(h.model.status == "Added 3 items to Groceries")
    }

    @Test("SHOP-11: the copies are created in the template's order, unchecked first")
    func localOrder() async throws {
        let h = try await TemplatesHarness(seed: { db, template in try await self.seedThree(db, template) })
        await h.model.addAll(to: h.groceries)
        var copied = try await h.db.liveListItems(listId: h.target)
        copied.sort { ($0.createdAt ?? "") < ($1.createdAt ?? "") }
        #expect(copied.map(\.name) == ["Milk", "Eggs", "Bread"])
    }

    @Test("SHOP-11: the status line clears by itself")
    func statusClears() async throws {
        let h = try await TemplatesHarness(seed: { db, template in try await self.seedThree(db, template) }, statusWindow: .milliseconds(50))
        await h.model.addAll(to: h.groceries)
        #expect(h.model.status != nil)
        await h.settle { h.model.status == nil }
        #expect(h.model.status == nil)
    }

    @Test("SHOP-11: AC3 an empty template writes nothing, kicks nothing and says so")
    func empty() async throws {
        let h = try await TemplatesHarness()
        let result = await h.model.addAll(to: h.groceries)
        #expect(result == .empty)
        #expect(try await h.outbox().isEmpty)
        #expect(try await h.db.liveListItems(listId: h.target).isEmpty)
        #expect(await h.kickCount == 0)
        #expect(h.model.status == "Nothing to add: this template is empty")
    }

    @Test("SHOP-11: a copy that cannot be read fails without a status and never kicks")
    func failed() async throws {
        let h = try await TemplatesHarness(seed: { db, template in try await self.seedThree(db, template) })
        await h.db.close()
        let result = await h.model.addAll(to: h.groceries)
        #expect(result == .failed)
        #expect(h.model.status == nil)
        #expect(await h.kickCount == 0)
    }

    @Test("SHOP-11: upsertAll rolls the whole batch back when one row is bad")
    func upsertAllRollsBack() async throws {
        let h = try await TemplatesHarness()
        let good: [String: JSONValue] = [
            "id": .string(uuidText(UUID())), "list_id": .string(uuidText(h.target)), "name": .string("Milk"),
        ]
        // `name` is NOT NULL, so the second row fails after the first was written inside the transaction.
        let bad: [String: JSONValue] = [
            "id": .string(uuidText(UUID())), "list_id": .string(uuidText(h.target)), "name": .null,
        ]
        let db = h.db
        await #expect(throws: (any Error).self) { try await db.upsertAll(table: "list_item", rows: [good, bad]) }
        #expect(try await h.db.liveListItems(listId: h.target).isEmpty)
        #expect(try await h.outbox().isEmpty)
        #expect(await h.kickCount == 0)
        // A row without an id is refused before anything is written.
        await #expect(throws: SyncWriteError.invalidRow) { try await db.upsertAll(table: "list_item", rows: [good, ["name": .string("x")]]) }
        #expect(try await h.db.liveListItems(listId: h.target).isEmpty)
        // An empty batch is a no-op.
        try await h.db.upsertAll(table: "list_item", rows: [])
        #expect(try await h.outbox().isEmpty)
    }

    @Test("SHOP-11: AC1 createList with isTemplate stores is_template locally and in the outbox")
    func createTemplate() async throws {
        let h = try await ListsHarness()
        let row = try #require(await h.model.create(name: "Weekly", storeId: nil, isTemplate: true))
        await h.settle { h.model.sections.templates.count == 1 }
        #expect(h.model.sections.templates.map(\.name) == ["Weekly"])
        #expect(h.model.sections.lists.isEmpty)
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued.first?.rowId == uuidText(row.id))
        let data = try decodeData(try #require(queued.first).data)
        #expect(data["is_template"] == .bool(true))
        let plain = try #require(await h.model.create(name: "Groceries", storeId: nil))
        await h.settle { h.model.sections.lists.count == 1 }
        #expect(h.model.sections.lists.map(\.id) == [plain.id])
        let second = try decodeData(try #require(try await h.outbox().last).data)
        #expect(second["is_template"] == .bool(false))
    }
}
