import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)

private func item(
    _ name: String, list: UUID? = nil, key: String? = nil, checked: Bool = false, deleted: Bool = false,
    created: String = "2026-11-01T10:00:00.000Z"
) -> ListItemRecord {
    ListItemRecord(
        id: UUID(), listId: list, name: name, nameKey: key, checked: checked,
        deletedAt: deleted ? "2026-11-02T10:00:00.000Z" : nil, createdAt: created
    )
}

/// A household with one list, a model on it, and a kick counter.
@MainActor
final class DetailHarness {
    let household = UUID()
    let list = UUID()
    let user = UUID()
    let db: HouseholdDatabase
    let model: ListDetailModel
    private let kicks = KickCounter()
    private var follower: Task<Void, Never>?
    /// Seeding goes through `upsert`, which queues; the tests only look at what the model queued after that.
    private var seededOutbox = 0

    init(
        seed: (HouseholdDatabase, UUID) async throws -> Void = { _, _ in }, makeId: @escaping @MainActor () -> UUID = { UUID() },
        undoWindow: Duration = .seconds(5), afterKick: (@Sendable (HouseholdDatabase, UUID) async -> Void)? = nil
    ) async throws {
        let db = try await openSyncDatabase(household)
        self.db = db
        try await seedList(db, id: list, name: "Groceries")
        try await seed(db, list)
        seededOutbox = try await db.outboxForTesting().filter { $0.table == "list_item" }.count
        let kicks = kicks
        let listId: UUID = list
        model = ListDetailModel(
            householdId: household, listId: list, userId: user, database: db,
            kick: {
                await kicks.add()
                await afterKick?(db, listId)
            },
            now: { fixedNow }, makeId: makeId,
            undoWindow: undoWindow
        )
        let model = model
        follower = Task { await model.start() }
        await settle { model.loaded }
    }

    var kickCount: Int { get async { await kicks.count } }

    func settle(until condition: @MainActor () -> Bool) async {
        for _ in 0..<2000 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
    }

    func outbox() async throws -> [(op: String, rowId: String, data: String)] {
        try await db.outboxForTesting().filter { $0.table == "list_item" }.dropFirst(seededOutbox).map { ($0.op, $0.rowId, $0.data) }
    }

    deinit { follower?.cancel() }
}

func seedItem(
    _ db: HouseholdDatabase, list: UUID, name: String, checked: Bool = false, deleted: Bool = false,
    created: String = "2026-11-01T10:00:00.000Z"
) async throws {
    try await db.upsert(table: "list_item", row: [
        "id": .string(UUID().uuidString.lowercased()), "list_id": .string(list.uuidString.lowercased()), "name": .string(name),
        "checked": .bool(checked), "deleted_at": deleted ? .string("2026-11-02T10:00:00.000Z") : .null, "created_at": .string(created),
    ])
}

@Suite("SHOP-02 name key")
struct NameKeyTests {
    @Test("SHOP-02: NameKey matches the SQL name_key", arguments: [
        ("  Oat   Milk ", "oat milk"), ("MILK", "milk"), ("\tMilk", " milk"), ("Milk\t", "milk "),
        ("Oat\t\nMilk", "oat milk"), ("milk", "milk"), ("   ", ""),
    ])
    func key(input: String, expected: String) {
        #expect(NameKey.make(input) == expected)
    }
}

@Suite("SHOP-02 suggest")
struct SuggestTests {
    let here = UUID()
    let other = UUID()

    private func labels(_ history: [ListItemRecord], _ query: String, limit: Int = 5) -> [String] {
        QuickAdd.suggest(history: history, query: query, listId: here, limit: limit).map(\.label)
    }

    @Test("SHOP-02: AC1 typing mi suggests Milk bought on another list, whatever the case or padding")
    func prefix() {
        let history = [item("Milk", list: other, checked: true)]
        #expect(labels(history, "mi") == ["Milk"])
        #expect(labels(history, "MI") == ["Milk"])
        #expect(labels(history, " mi") == ["Milk"])
    }

    @Test("SHOP-02: a blank query suggests nothing")
    func blank() {
        #expect(labels([item("Milk", list: other, checked: true)], "  ").isEmpty)
        #expect(labels([item("Milk", list: other, checked: true)], "").isEmpty)
    }

    @Test("SHOP-02: a deleted unchecked row is not history, a deleted checked row is")
    func deletedRows() {
        #expect(labels([item("Milk", list: other, deleted: true)], "mi").isEmpty)
        #expect(labels([item("Milk", list: other, checked: true, deleted: true)], "mi") == ["Milk"])
    }

    @Test("SHOP-02: a word start matches, an infix does not")
    func wordStart() {
        let history = [item("Oat milk", list: other, checked: true), item("Salami", list: other, checked: true)]
        #expect(labels(history, "mi") == ["Oat milk"])
    }

    @Test("SHOP-02: a name already unchecked on this list is left out, a checked one is not")
    func alreadyOnList() {
        #expect(labels([item("Milk", list: here)], "mi").isEmpty)
        #expect(labels([item("Milk", list: here, checked: true)], "mi") == ["Milk"])
    }

    @Test("SHOP-02: one suggestion per name key, labelled with the newest casing")
    func newestLabel() {
        let history = [
            item("milk", list: other, checked: true, created: "2026-11-01T10:00:00.000Z"),
            item("Milk", list: other, checked: true, created: "2026-11-03T10:00:00.000Z"),
        ]
        #expect(labels(history, "mi") == ["Milk"])
    }

    @Test("SHOP-02: order is prefix first, then count, then recency")
    func order() {
        let history = [
            item("Oat milk", list: other, checked: true, created: "2026-11-09T10:00:00.000Z"),
            item("Oat milk", list: other, checked: true, created: "2026-11-08T10:00:00.000Z"),
            item("Milkshake", list: other, checked: true, created: "2026-11-01T10:00:00.000Z"),
            item("Milk", list: other, checked: true, created: "2026-11-05T10:00:00.000Z"),
        ]
        // Prefix matches (Milk, Milkshake) lead; Milk is newer. Oat milk matches a word, so it comes last.
        #expect(labels(history, "mi") == ["Milk", "Milkshake", "Oat milk"])
    }

    @Test("SHOP-02: at most five suggestions")
    func limit() {
        let history = (1...8).map { item("Milk \($0)", list: other, checked: true) }
        #expect(labels(history, "mi").count == 5)
        #expect(labels(history, "mi", limit: 2).count == 2)
    }

    @Test("SHOP-02: a row without a name_key falls back to the key of its name")
    func nilKey() {
        let history = [item("  Oat   Milk ", list: other, key: nil, checked: true)]
        #expect(QuickAdd.suggest(history: history, query: "oat m", listId: here).map(\.key) == ["oat milk"])
    }
}

@MainActor
@Suite("SHOP-02 quick add model", .serialized)
struct QuickAddModelTests {
    @Test("SHOP-02: AC2 picking a suggestion adds a row named Milk and queues one upsert without name_key")
    func pick() async throws {
        let h = try await DetailHarness(seed: { db, _ in
            try await seedItem(db, list: UUID(), name: "milk", checked: true, created: "2026-11-01T10:00:00.000Z")
            try await seedItem(db, list: UUID(), name: "Milk", checked: true, created: "2026-11-03T10:00:00.000Z")
        })
        await h.settle { h.model.snapshot.history.count == 2 }
        h.model.query = "mi"
        let suggestion = try #require(h.model.suggestions.first)
        #expect(h.model.suggestions.count == 1 && suggestion.label == "Milk")
        await h.model.pick(suggestion)
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.unchecked.map(\.name) == ["Milk"])
        let local = try await h.db.queryForTesting("SELECT name, name_key, source, checked FROM list_item WHERE list_id = '\(h.list.uuidString.lowercased())'")
        #expect(local.count == 1 && local[0]["name"] == "Milk" && local[0]["name_key"] == "milk")
        let queued = try await h.outbox()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        let data = try #require(JSONSerialization.jsonObject(with: Data(queued[0].data.utf8)) as? [String: Any])
        #expect(data["name"] as? String == "Milk")
        #expect(data["name_key"] == nil)
    }

    @Test("SHOP-02: AC3 Return adds the typed name as typed, clears the field and kicks once")
    func submit() async throws {
        let h = try await DetailHarness()
        h.model.query = "  Lightbulbs "
        await h.model.submit()
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.unchecked.map(\.name) == ["Lightbulbs"])
        #expect(h.model.query.isEmpty)
        #expect(await h.kickCount == 1)
        let local = try await h.db.queryForTesting("SELECT source, checked, created_by FROM list_item")
        #expect(local[0]["source"] == "manual" && local[0]["checked"] == "0" && local[0]["created_by"] == h.user.uuidString.lowercased())
    }

    @Test("SHOP-02: Return with a prefix of a suggestion does not pick it")
    func returnNeverPicks() async throws {
        let h = try await DetailHarness(seed: { db, _ in try await seedItem(db, list: UUID(), name: "Milk", checked: true) })
        await h.settle { !h.model.snapshot.history.isEmpty }
        h.model.query = "Mi"
        await h.model.submit()
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.unchecked.map(\.name) == ["Mi"])
    }

    @Test("SHOP-02: a blank add writes nothing")
    func blank() async throws {
        let h = try await DetailHarness()
        h.model.query = "   "
        await h.model.submit()
        #expect(try await h.outbox().isEmpty)
        #expect(await h.kickCount == 0)
    }

    @Test("SHOP-02: a checked name can be added again as a new unchecked row")
    func readdChecked() async throws {
        let h = try await DetailHarness(seed: { db, list in try await seedItem(db, list: list, name: "Milk", checked: true) })
        await h.settle { h.model.checked.count == 1 }
        h.model.query = "mi"
        #expect(h.model.suggestions.map(\.label) == ["Milk"])
        await h.model.pick(try #require(h.model.suggestions.first))
        await h.settle { h.model.unchecked.count == 1 }
        #expect(h.model.checked.count == 1 && h.model.unchecked.map(\.name) == ["Milk"])
    }
}

@MainActor
@Suite("SHOP-02 observe list detail", .serialized)
struct ListDetailObserveTests {
    @Test("SHOP-02: the snapshot has the list, a live store name, this list's items and the household history")
    func snapshot() async throws {
        let store = UUID()
        let h = try await DetailHarness(seed: { db, list in
            try await db.upsert(table: "store", row: storeRow(store, name: "Lidl"))
            try await db.patch(table: "shopping_list", id: list, fields: ["store_id": .string(store.uuidString.lowercased())])
            try await seedItem(db, list: list, name: "Eggs")
            try await seedItem(db, list: UUID(), name: "Milk", checked: true)
            try await seedItem(db, list: UUID(), name: "Typo", deleted: true)
        })
        await h.settle { h.model.snapshot.storeName != nil }
        #expect(h.model.snapshot.list?.name == "Groceries")
        #expect(h.model.snapshot.storeName == "Lidl")
        #expect(h.model.snapshot.items.map(\.name) == ["Eggs"])
        #expect(Set(h.model.snapshot.history.map(\.name)) == ["Eggs", "Milk"])
    }

    @Test("SHOP-02: it re-emits after a write")
    func reEmits() async throws {
        let h = try await DetailHarness()
        #expect(h.model.snapshot.items.isEmpty)
        try await seedItem(h.db, list: h.list, name: "Eggs")
        await h.settle { h.model.snapshot.items.count == 1 }
        #expect(h.model.snapshot.items.map(\.name) == ["Eggs"])
    }

    @Test("SHOP-02: a deleted list has no list and reads as gone")
    func gone() async throws {
        let h = try await DetailHarness()
        #expect(!h.model.isGone)
        try await h.db.remove(table: "shopping_list", id: h.list)
        await h.settle { h.model.isGone }
        #expect(h.model.isGone)
    }

    @Test("SHOP-02: the stream finishes at once when the household is not open")
    func notOpen() async throws {
        let db = HouseholdDatabase(rootURL: switcherTempRoot())
        var count = 0
        for await _ in await db.observeListDetail(householdId: UUID(), listId: UUID()) { count += 1 }
        #expect(count == 0)
    }
}
