import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)

/// A household database plus a stores model on it, following the local rows.
@MainActor
final class StoresHarness {
    let household = UUID()
    let db: HouseholdDatabase
    let model: StoresModel
    private let kicks = KickCounter()
    private var follower: Task<Void, Never>?
    private var seededOutbox = 0

    init(undoWindow: Duration = .seconds(60), makeId: @escaping @MainActor () -> UUID = { UUID() }, seed: (HouseholdDatabase, UUID) async throws -> Void = { _, _ in }) async throws {
        let db = try await openSyncDatabase(household)
        self.db = db
        try await seed(db, household)
        seededOutbox = try await db.outboxForTesting().count
        let kicks = kicks
        model = StoresModel(
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

    /// Outbox rows queued after seeding, for one table.
    func outbox(_ table: String = "store") async throws -> [(op: String, rowId: String, data: String)] {
        try await db.outboxForTesting().dropFirst(seededOutbox).filter { $0.table == table }.map { ($0.op, $0.rowId, $0.data) }
    }

    deinit { follower?.cancel() }
}

/// Learned positions arrive by pull only; seed them the way a sync page would.
func seedOrder(_ db: HouseholdDatabase, household: UUID, store: UUID, keys: [String]) async throws {
    let rows = keys.map { key in
        jsonRow([
            "household_id": household.uuidString.lowercased(), "store_id": store.uuidString.lowercased(), "name_key": key,
            "mean_position": 1.0, "samples": 2,
        ])
    }
    _ = try await db.applyPull(householdId: household, page: SyncPullPage(cursor: 1, hasMore: false, changes: ["store_item_order": rows]))
}

/// The first snapshot the list detail observation emits.
func firstDetail(_ db: HouseholdDatabase, _ household: UUID, _ list: UUID) async -> ListDetailSnapshot? {
    for await snapshot in await db.observeListDetail(householdId: household, listId: list) { return snapshot }
    return nil
}

@Suite("SHOP-05 stores logic")
struct StoresLogicTests {
    @Test("SHOP-05: learned label for 0, 1 and 42 items")
    func learned() {
        #expect(Stores.learnedLabel(0) == nil)
        #expect(Stores.learnedLabel(1) == "learned 1 item")
        #expect(Stores.learnedLabel(42) == "learned 42 items")
    }

    @Test("SHOP-05: names validate trimmed and case-insensitive, ignoring the excluded store")
    func validation() {
        let market = StoreRecord(id: UUID(), name: "Test Market")
        #expect(Stores.validate(name: "", stores: []) == .empty)
        #expect(Stores.validate(name: "   ", stores: []) == .empty)
        #expect(Stores.validate(name: " test market ", stores: [market]) == .duplicate("Test Market"))
        #expect(Stores.validate(name: "test market", stores: [market], exceptId: market.id) == nil)
        #expect(Stores.validate(name: "Lidl", stores: [market]) == nil)
        #expect(StoreNameProblem.duplicate("Test Market").message == "You already have Test Market")
        #expect(StoreNameProblem.empty.message == "Give the store a name")
    }

    @Test("SHOP-05: stores sort by name, the id breaking ties")
    func sorting() {
        let a = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!, b = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
        let sorted = Stores.sorted([StoreRow(id: b, name: "aldi"), StoreRow(id: UUID(), name: "Zed"), StoreRow(id: a, name: "Aldi")])
        #expect(sorted.map(\.name) == ["Aldi", "aldi", "Zed"])
        #expect(sorted.first?.id == a)
    }
}

@MainActor
@Suite("SHOP-05 stores model", .serialized)
struct StoresModelTests {
    @Test("SHOP-05: AC4 create trims the name, stores one row and queues exactly one upsert")
    func create() async throws {
        let id = UUID()
        let h = try await StoresHarness(makeId: { id })
        #expect(await h.model.create(name: "  Test Market ") == id)
        await h.settle { h.model.rows.count == 1 }
        #expect(h.model.rows.map(\.name) == ["Test Market"])
        let queued = try await h.outbox()
        #expect(queued.count == 1)
        #expect(queued.first?.op == "upsert")
        #expect(queued.first?.rowId == id.uuidString.lowercased())
        #expect(queued.first?.data.contains(#""name":"Test Market""#) == true)
        #expect(await h.kickCount == 1)
    }

    @Test("SHOP-05: empty and duplicate names write nothing")
    func invalid() async throws {
        let h = try await StoresHarness(seed: { db, _ in try await db.upsert(table: "store", row: storeRow(name: "Test Market")) })
        await h.settle { h.model.rows.count == 1 }
        #expect(await h.model.create(name: "  ") == nil)
        #expect(await h.model.create(name: "TEST market") == nil)
        #expect(try await h.outbox().isEmpty)
    }

    @Test("SHOP-05: delete then Undo writes nothing; after the window the store is soft-deleted with one outbox delete")
    func deleteUndoAndCommit() async throws {
        let id = UUID()
        let h = try await StoresHarness(undoWindow: .milliseconds(40), seed: { db, _ in try await db.upsert(table: "store", row: storeRow(id, name: "Lidl")) })
        await h.settle { h.model.rows.count == 1 }
        let row = try #require(h.model.rows.first)
        h.model.delete(row)
        #expect(h.model.rows.isEmpty && h.model.pending.first?.message == "Deleted Lidl")
        h.model.undo(id)
        #expect(h.model.rows.count == 1 && h.model.pending.isEmpty)
        try await Task.sleep(for: .milliseconds(120))
        #expect(try await h.outbox().isEmpty)

        h.model.delete(row)
        await h.settle { h.model.pending.isEmpty }
        await h.settle { h.model.stored.isEmpty }
        #expect(h.model.stored.isEmpty)
        let queued = try await h.outbox()
        #expect(queued.map(\.op) == ["delete"])
    }

    @Test("SHOP-05: a list keeps its store_id when the store is deleted, and flush writes pending deletes")
    func deleteKeepsListLink() async throws {
        let store = UUID(), list = UUID()
        let h = try await StoresHarness(seed: { db, _ in
            try await db.upsert(table: "store", row: storeRow(store, name: "Lidl"))
            try await seedList(db, id: list, name: "Groceries", store: store)
        })
        await h.settle { h.model.rows.count == 1 }
        h.model.delete(try #require(h.model.rows.first))
        await h.model.flush()
        #expect(h.model.pending.isEmpty)
        #expect(try await h.outbox().map(\.op) == ["delete"])
        let snapshot = await firstDetail(h.db, h.household, list)
        #expect(snapshot?.list?.storeId == store)
        #expect(snapshot?.storeName == nil)
    }

    @Test("SHOP-05: stores come sorted with learned counts from their own order rows only")
    func observe() async throws {
        let lidl = UUID(), aldi = UUID()
        let h = try await StoresHarness(seed: { db, household in
            try await db.upsert(table: "store", row: storeRow(lidl, name: "Lidl"))
            try await db.upsert(table: "store", row: storeRow(aldi, name: "Aldi"))
            try await db.upsert(table: "store", row: storeRow(UUID(), name: "Gone"))
            try await seedOrder(db, household: household, store: lidl, keys: ["milk", "eggs", "bread"])
        })
        await h.settle { h.model.rows.count == 3 }
        #expect(h.model.rows.map(\.name) == ["Aldi", "Gone", "Lidl"])
        #expect(h.model.rows.map(\.learned) == [0, 0, 3])
    }

    @Test("SHOP-05: the list detail snapshot carries the live stores")
    func detailStores() async throws {
        let store = UUID()
        let h = try await StoresHarness(seed: { db, _ in
            try await db.upsert(table: "store", row: storeRow(store, name: "Lidl"))
            try await db.upsert(table: "store", row: storeRow(UUID(), name: "Aldi"))
        })
        let list = UUID()
        try await seedList(h.db, id: list, name: "Groceries", store: store)
        let snapshot = await firstDetail(h.db, h.household, list)
        #expect(Set(snapshot?.stores.map(\.name) ?? []) == ["Lidl", "Aldi"])
        #expect(snapshot?.storeName == "Lidl")
    }
}

@MainActor
@Suite("SHOP-05 list store", .serialized)
struct ListStoreTests {
    @Test("SHOP-05: AC3 setStore queues one upsert on shopping_list whose data is only store_id; nil clears; same value writes nothing")
    func setStore() async throws {
        let store = UUID()
        let h = try await DetailHarness(seed: { db, _ in try await db.upsert(table: "store", row: storeRow(store, name: "Test Market")) })
        await h.settle { h.model.snapshot.stores.count == 1 }
        #expect(await h.model.setStore(nil))
        #expect(try await h.db.outboxForTesting().filter { $0.table == "shopping_list" }.count == 1) // the seed only
        #expect(await h.model.setStore(store))
        await h.settle { h.model.snapshot.storeName == "Test Market" }
        let queued = try await h.db.outboxForTesting().filter { $0.table == "shopping_list" }
        #expect(queued.count == 2)
        #expect(queued.last?.data == #"{"store_id":"\#(store.uuidString.lowercased())"}"#)
        #expect(await h.model.setStore(store))
        #expect(try await h.db.outboxForTesting().filter { $0.table == "shopping_list" }.count == 2)
        #expect(await h.model.setStore(nil))
        await h.settle { h.model.snapshot.storeName == nil }
        #expect(try await h.db.outboxForTesting().filter { $0.table == "shopping_list" }.last?.data == #"{"store_id":null}"#)
    }

    @Test("SHOP-05: createStore from the picker validates and queues one store upsert")
    func createStore() async throws {
        let id = UUID()
        let h = try await DetailHarness(makeId: { id })
        #expect(await h.model.createStore(name: " ") == nil)
        #expect(await h.model.createStore(name: "Corner Shop") == id)
        await h.settle { h.model.snapshot.stores.count == 1 }
        #expect(await h.model.createStore(name: "corner shop") == nil)
        #expect(try await h.db.outboxForTesting().filter { $0.table == "store" }.count == 1)
    }
}
