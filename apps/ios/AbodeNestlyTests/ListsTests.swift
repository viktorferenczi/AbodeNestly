import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let stamp = Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt)
private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)

private func record(
    _ name: String, store: UUID? = nil, template: Bool = false, archived: String? = nil, created: String? = nil, id: UUID = UUID()
) -> ListRecord {
    ListRecord(id: id, name: name, storeId: store, isTemplate: template, archivedAt: archived, createdAt: created)
}

/// A household database plus a model on it, following the local rows.
@MainActor
final class ListsHarness {
    let household = UUID()
    let db: HouseholdDatabase
    let model: ListsModel
    private let kicks = KickCounter()
    private var follower: Task<Void, Never>?

    init(undoWindow: Duration = .seconds(60), makeId: @escaping @MainActor () -> UUID = { UUID() }, seed: (HouseholdDatabase) async throws -> Void = { _ in }) async throws {
        let db = try await openSyncDatabase(household)
        self.db = db
        try await seed(db)
        let kicks = kicks
        model = ListsModel(
            householdId: household, database: db, kick: { await kicks.add() }, undoWindow: undoWindow, now: { fixedNow }, makeId: makeId
        )
        let model = model
        follower = Task { await model.start() }
        await settle { model.loaded }
    }

    var kickCount: Int { get async { await kicks.count } }

    func settle(until condition: @MainActor () -> Bool) async {
        for _ in 0..<2000 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
    }

    /// Outbox rows (table, op, row id) queued since the harness was seeded.
    func outbox(_ table: String = "shopping_list") async throws -> [(op: String, rowId: String, data: String)] {
        try await db.outboxForTesting().filter { $0.table == table }.map { ($0.op, $0.rowId, $0.data) }
    }

    deinit { follower?.cancel() }
}

actor KickCounter {
    var count = 0
    func add() { count += 1 }
}

func seedList(
    _ db: HouseholdDatabase, id: UUID = UUID(), name: String, store: UUID? = nil, template: Bool = false,
    archived: String? = nil, created: String = "2026-11-01T10:00:00.000Z"
) async throws {
    try await db.upsert(table: "shopping_list", row: [
        "id": .string(id.uuidString.lowercased()), "name": .string(name),
        "store_id": store.map { .string($0.uuidString.lowercased()) } ?? .null,
        "is_template": .bool(template), "archived_at": archived.map { .string($0) } ?? .null,
        "deleted_at": .null, "created_at": .string(created),
    ])
}

@Suite("SHOP-01 lists logic")
struct ListsLogicTests {
    @Test("SHOP-01: AC2 each row has the name, the store name and checked of all items")
    func rows() {
        let store = UUID(), list = UUID(), bare = UUID()
        let snapshot = ShoppingListsSnapshot(
            lists: [record("Groceries", store: store, created: "a", id: list), record("Hardware", created: "b", id: bare)],
            stores: [StoreRecord(id: store, name: "Lidl")], counts: [list: ListItemCount(checked: 3, all: 12)]
        )
        let sections = Lists.sections(snapshot)
        #expect(sections.lists.map(\.name) == ["Groceries", "Hardware"])
        #expect(sections.lists.map(\.storeName) == ["Lidl", nil])
        #expect(sections.lists.map(\.count) == ["3 of 12", "No items"])
    }

    @Test("SHOP-01: a store that is gone shows no chip")
    func deletedStore() {
        let snapshot = ShoppingListsSnapshot(lists: [record("Groceries", store: UUID(), created: "a")])
        #expect(Lists.sections(snapshot).lists.first?.storeName == nil)
    }

    @Test("SHOP-01: lists and templates are oldest first, archived newest archived first, three sections apart")
    func ordering() {
        let snapshot = ShoppingListsSnapshot(lists: [
            record("Newer", created: "2026-11-02"), record("Older", created: "2026-11-01"),
            record("Template", template: true, created: "2026-11-03"),
            record("Old archive", archived: "2026-11-05", created: "2026-10-01"),
            record("Fresh archive", archived: "2026-11-09", created: "2026-10-02"),
        ])
        let sections = Lists.sections(snapshot)
        #expect(sections.lists.map(\.name) == ["Older", "Newer"])
        #expect(sections.templates.map(\.name) == ["Template"])
        #expect(sections.archived.map(\.name) == ["Fresh archive", "Old archive"])
        #expect(!sections.isEmpty)
        #expect(Lists.sections(ShoppingListsSnapshot()).isEmpty)
    }

    @Test("SHOP-01: a list waiting out its undo window is hidden")
    func hidden() {
        let gone = UUID()
        let snapshot = ShoppingListsSnapshot(lists: [record("Groceries", id: gone), record("Hardware")])
        #expect(Lists.sections(snapshot, hidden: [gone]).lists.map(\.name) == ["Hardware"])
    }
}

@MainActor
@Suite("SHOP-01 lists model", .serialized)
struct ListsModelTests {
    @Test("SHOP-01: AC3 a swipe not undone within the window sets archived_at and moves the list to Archived")
    func archiveCommits() async throws {
        let id = UUID()
        let h = try await ListsHarness(undoWindow: .milliseconds(20), seed: { try await seedList($0, id: id, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        let before = try await h.outbox().count
        h.model.archive(try #require(h.model.sections.lists.first))
        #expect(h.model.sections.lists.isEmpty)
        await h.settle { h.model.sections.archived.count == 1 && h.model.pending.isEmpty }
        #expect(h.model.sections.archived.map(\.name) == ["Groceries"])
        let queued = try await h.outbox()
        #expect(queued.count == before + 1)
        let last = try #require(queued.last)
        #expect(last.op == "upsert" && last.rowId == id.uuidString.lowercased())
        let data = try #require(try JSONSerialization.jsonObject(with: Data(last.data.utf8)) as? [String: Any])
        #expect(data.keys.sorted() == ["archived_at"])
        #expect(await h.kickCount >= 1)
    }

    @Test("SHOP-01: AC4 undo inside the window writes nothing and the list stays in Lists")
    func undoDoesNotEnqueue() async throws {
        let h = try await ListsHarness(seed: { try await seedList($0, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        let before = try await h.outbox().count
        let row = try #require(h.model.sections.lists.first)
        h.model.archive(row)
        #expect(h.model.pending.map(\.message) == ["Archived Groceries"])
        h.model.undo(row.id)
        #expect(h.model.pending.isEmpty)
        #expect(h.model.sections.lists.map(\.name) == ["Groceries"])
        await h.model.flush()
        #expect(try await h.outbox().count == before)
        #expect(try await h.db.queryForTesting("SELECT archived_at FROM shopping_list") == [["archived_at": "NULL"]])
    }

    @Test("SHOP-01: AC5 AC6 creating a list stores it, queues exactly one upsert and returns it to open")
    func createEnqueues() async throws {
        let store = UUID()
        let new = UUID()
        let h = try await ListsHarness(makeId: { new }, seed: { try await $0.upsert(table: "store", row: storeRow(store, name: "Lidl")) })
        await h.settle { h.model.stores.count == 1 }
        let row = try #require(await h.model.create(name: "  Hardware ", storeId: store))
        #expect(row.id == new && row.name == "Hardware" && row.storeName == "Lidl")
        let queued = try await h.outbox()
        #expect(queued.count == 1)
        let entry = try #require(queued.first)
        #expect(entry.op == "upsert" && entry.rowId == new.uuidString.lowercased())
        let data = try #require(try JSONSerialization.jsonObject(with: Data(entry.data.utf8)) as? [String: Any])
        #expect(data["name"] as? String == "Hardware" && data["store_id"] as? String == store.uuidString.lowercased())
        #expect(data["is_template"] as? Bool == false)
        // Server-owned columns never travel.
        #expect(data["id"] == nil && data["household_id"] == nil && data["created_at"] == nil && data["sync_seq"] == nil)
        await h.settle { h.model.sections.lists.count == 1 }
        #expect(h.model.sections.lists.map(\.name) == ["Hardware"])
    }

    @Test("SHOP-01: a blank name creates nothing")
    func blankName() async throws {
        let h = try await ListsHarness()
        #expect(await h.model.create(name: "   ", storeId: nil) == nil)
        #expect(try await h.outbox().isEmpty)
    }

    @Test("SHOP-01: restore clears archived_at with one patch; delete after the window soft-deletes")
    func restoreAndDelete() async throws {
        let h = try await ListsHarness(undoWindow: .milliseconds(20), seed: {
            try await seedList($0, name: "Old", archived: "2026-11-05T10:00:00.000Z")
            try await seedList($0, name: "Older", archived: "2026-11-04T10:00:00.000Z")
        })
        await h.settle { h.model.sections.archived.count == 2 }
        let before = try await h.outbox().count
        await h.model.restore(try #require(h.model.sections.archived.first))
        await h.settle { h.model.sections.lists.count == 1 }
        #expect(h.model.sections.lists.map(\.name) == ["Old"])
        let restore = try #require(try await h.outbox().last)
        #expect(restore.data == #"{"archived_at":null}"#)

        h.model.delete(try #require(h.model.sections.archived.first))
        await h.settle { h.model.sections.archived.isEmpty && h.model.pending.isEmpty }
        let queued = try await h.outbox()
        #expect(queued.count == before + 2)
        #expect(queued.last?.op == "delete")
    }

    @Test("SHOP-01: leaving the screen writes a swipe that is still waiting")
    func flushWrites() async throws {
        let h = try await ListsHarness(seed: { try await seedList($0, name: "Groceries") })
        await h.settle { h.model.sections.lists.count == 1 }
        let before = try await h.outbox().count
        h.model.archive(try #require(h.model.sections.lists.first))
        await h.model.flush()
        #expect(try await h.outbox().count == before + 1)
        #expect(h.model.pending.isEmpty)
    }
}
