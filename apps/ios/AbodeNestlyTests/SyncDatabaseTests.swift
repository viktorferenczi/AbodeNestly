import Contract
import Foundation
import Testing
@testable import Core
@testable import Sync

/// A household database in a fresh temp folder, open on `household`.
func openSyncDatabase(_ household: UUID = UUID(), root: URL = switcherTempRoot()) async throws -> HouseholdDatabase {
    let db = HouseholdDatabase(rootURL: root)
    try await db.open(householdId: household)
    return db
}

func storeRow(_ id: UUID = UUID(), name: String = "Lidl") -> [String: JSONValue] {
    ["id": .string(id.uuidString.lowercased()), "name": .string(name)]
}

func jsonRow(_ pairs: [String: Any]) -> [String: JSONValue] {
    (try? JSONDecoder().decode([String: JSONValue].self, from: JSONSerialization.data(withJSONObject: pairs))) ?? [:]
}

@Suite("SHOP-08 outbox writes")
struct SyncOutboxWriteTests {
    let household = UUID()

    @Test("SHOP-08: AC1 upsert stores the row and queues one mutation without server-owned columns")
    func upsertWritesRowAndOutbox() async throws {
        let db = try await openSyncDatabase(household)
        let item = UUID()
        let list = UUID()
        try await db.upsert(table: "list_item", row: jsonRow([
            "id": item.uuidString.lowercased(), "list_id": list.uuidString.lowercased(), "name": "Milk",
            "quantity": 2.5, "checked": false, "name_key": "milk", "created_at": "2026-11-23T10:00:00.000Z",
        ]))
        let local = try await db.queryForTesting("SELECT id, household_id, name, quantity, checked FROM list_item")
        #expect(local.count == 1)
        #expect(local[0]["name"] == "Milk")
        #expect(local[0]["quantity"] == "2.5")
        #expect(local[0]["checked"] == "0")
        #expect(local[0]["household_id"] == household.uuidString.lowercased())
        let outbox = try await db.outboxForTesting()
        #expect(outbox.count == 1)
        #expect(outbox[0].table == "list_item" && outbox[0].op == "upsert" && outbox[0].rowId == item.uuidString.lowercased())
        let data = try JSONDecoder().decode([String: JSONValue].self, from: Data(outbox[0].data.utf8))
        #expect(Set(data.keys) == ["list_id", "name", "quantity", "checked"])
    }

    @Test("SHOP-08: AC1 a write that fails leaves neither the row nor an outbox entry")
    func failedWriteRollsBack() async throws {
        let db = try await openSyncDatabase(household)
        // `name` is NOT NULL locally, so the row insert throws inside the transaction.
        await #expect(throws: (any Error).self) {
            try await db.upsert(table: "list_item", row: jsonRow(["id": UUID().uuidString.lowercased(), "quantity": 1]))
        }
        #expect(try await db.outboxForTesting().isEmpty)
        #expect(try await db.queryForTesting("SELECT id FROM list_item").isEmpty)
    }

    @Test("SHOP-08: a table the spec does not name is refused before anything is written")
    func unknownTable() async throws {
        let db = try await openSyncDatabase(household)
        await #expect(throws: SyncWriteError.unknownTable("outbox")) {
            try await db.upsert(table: "outbox", row: storeRow())
        }
        #expect(try await db.outboxForTesting().isEmpty)
    }

    @Test("SHOP-08: patch writes and queues only the given fields, local-only columns stay local")
    func patch() async throws {
        let db = try await openSyncDatabase(household)
        let id = UUID()
        try await db.upsert(table: "store", row: storeRow(id))
        try await db.patch(table: "store", id: id, fields: ["name": .string("Aldi")], localOnly: ["updated_at": .string("x")])
        let local = try await db.queryForTesting("SELECT name, updated_at FROM store")
        #expect(local[0]["name"] == "Aldi" && local[0]["updated_at"] == "x")
        let outbox = try await db.outboxForTesting()
        #expect(outbox.count == 2)
        #expect(outbox[1].data == #"{"name":"Aldi"}"#)
    }

    @Test("SHOP-08: patching or removing a row that is not here throws and queues nothing")
    func missingRow() async throws {
        let db = try await openSyncDatabase(household)
        await #expect(throws: SyncWriteError.rowNotFound) {
            try await db.patch(table: "store", id: UUID(), fields: ["name": .string("x")])
        }
        await #expect(throws: SyncWriteError.rowNotFound) { try await db.remove(table: "store", id: UUID()) }
        #expect(try await db.outboxForTesting().isEmpty)
    }

    @Test("SHOP-08: remove soft-deletes locally and queues a delete without data")
    func remove() async throws {
        let db = try await openSyncDatabase(household)
        let id = UUID()
        try await db.upsert(table: "store", row: storeRow(id))
        try await db.remove(table: "store", id: id, now: Date(timeIntervalSince1970: 1_800_000_000))
        let local = try await db.queryForTesting("SELECT deleted_at FROM store")
        #expect(local[0]["deleted_at"] == "2027-01-15T08:00:00.000Z")
        let last = try #require(try await db.outboxForTesting().last)
        #expect(last.op == "delete" && last.data == "{}")
    }

    @Test("SHOP-08: a pantry delta is queued as a delta mutation")
    func delta() async throws {
        let db = try await openSyncDatabase(household)
        let id = UUID()
        try await db.delta(pantryItemId: id, delta: -1, reason: "used")
        let row = try #require(try await db.outboxForTesting().first)
        #expect(row.table == "pantry_item" && row.op == "delta" && row.rowId == id.uuidString.lowercased())
        #expect(row.data == #"{"delta":-1,"field":"quantity","reason":"used"}"#)
    }

    @Test("SHOP-08: counts include only tables the loop sends, plus failed changes")
    func counts() async throws {
        let db = try await openSyncDatabase(household)
        try await db.upsert(table: "store", row: storeRow())
        try await db.enqueue(table: "notification_pref", op: "upsert", rowId: UUID(), data: [:])
        for await counts in await db.observeSyncCounts(householdId: household) {
            #expect(counts == SyncCounts(pending: 1, failed: 0))
            break
        }
        let queued = try #require(try await db.pendingMutations(householdId: household, tables: SyncTables.pushable, limit: 10).first)
        try await db.settle(householdId: household, applied: [], rejected: [(queued.seq, .validation, "bad")], now: Date())
        for await counts in await db.observeSyncCounts(householdId: household) {
            #expect(counts == SyncCounts(pending: 0, failed: 1))
            break
        }
        for await failed in await db.observeFailed(householdId: household) {
            #expect(failed.map(\.table) == ["store"] && failed[0].reason == .validation && failed[0].detail == "bad")
            break
        }
    }

    @Test("SHOP-08: counts finish at once for a household that is not open")
    func countsNotOpen() async throws {
        let db = try await openSyncDatabase(household)
        var emitted = 0
        for await _ in await db.observeSyncCounts(householdId: UUID()) { emitted += 1 }
        #expect(emitted == 0)
    }
}

@Suite("SHOP-08 applying a pulled page")
struct SyncApplyPullTests {
    let household = UUID()

    @Test("SHOP-08: AC2 rows are stored with the columns this app knows and the cursor moves; unknown tables and columns are ignored")
    func appliesPage() async throws {
        let db = try await openSyncDatabase(household)
        let id = UUID()
        let page = SyncPullPage(cursor: 7, hasMore: false, changes: [
            "store": [jsonRow(["id": id.uuidString.lowercased(), "household_id": household.uuidString.lowercased(), "name": "Lidl",
                               "future_column": "x", "sync_seq": 6, "created_at": "2026-11-23T10:00:00.5+00:00"])],
            "from_the_future": [jsonRow(["id": UUID().uuidString.lowercased()])],
            "outbox": [jsonRow(["id": "x"])],
        ])
        #expect(try await db.applyPull(householdId: household, page: page))
        let rows = try await db.queryForTesting("SELECT name, sync_seq, created_at FROM store")
        #expect(rows == [["name": "Lidl", "sync_seq": "6", "created_at": "2026-11-23T10:00:00.500Z"]])
        #expect(try await db.readCursor() == 7)
        #expect(try await db.outboxForTesting().isEmpty)
    }

    @Test("SHOP-08: a row with a change still queued keeps its local value")
    func queuedRowWins() async throws {
        let db = try await openSyncDatabase(household)
        let id = UUID()
        try await db.upsert(table: "store", row: storeRow(id, name: "Mine"))
        let page = SyncPullPage(cursor: 3, hasMore: false, changes: [
            "store": [jsonRow(["id": id.uuidString.lowercased(), "household_id": household.uuidString.lowercased(), "name": "Theirs"])],
        ])
        _ = try await db.applyPull(householdId: household, page: page)
        #expect(try await db.queryForTesting("SELECT name FROM store")[0]["name"] == "Mine")
    }

    @Test("SHOP-08: Postgres timestamps are stored in the app's millisecond format and the Home feed reads them")
    func timestampsReadable() async throws {
        let db = try await openSyncDatabase(household)
        let activity = UUID()
        let page = SyncPullPage(cursor: 1, hasMore: false, changes: [
            "activity": [jsonRow([
                "id": activity.uuidString.lowercased(), "household_id": household.uuidString.lowercased(),
                "actor_id": NSNull(), "module": "shopping", "entity": "list_item", "entity_id": UUID().uuidString.lowercased(),
                "action": "checked", "summary": "Ada checked Milk", "created_at": "2026-11-23T17:47:27.123456+00:00",
                "updated_at": "2026-11-23T17:47:27.123456+00:00", "deleted_at": NSNull(), "sync_seq": 4,
            ])],
        ])
        _ = try await db.applyPull(householdId: household, page: page)
        #expect(try await db.queryForTesting("SELECT created_at FROM activity")[0]["created_at"] == "2026-11-23T17:47:27.123Z")
        for await rows in await db.observeActivity(householdId: household) {
            #expect(rows.map(\.summary) == ["Ada checked Milk"])
            break
        }
    }

    @Test("SHOP-08: a tombstone replaces the stored row")
    func tombstone() async throws {
        let db = try await openSyncDatabase(household)
        let id = UUID()
        func page(deleted: Bool, cursor: Int) -> SyncPullPage {
            SyncPullPage(cursor: cursor, hasMore: false, changes: ["store": [jsonRow([
                "id": id.uuidString.lowercased(), "household_id": household.uuidString.lowercased(), "name": "Lidl",
                "deleted_at": deleted ? "2026-11-23T18:00:00+00:00" : NSNull(),
            ])]])
        }
        _ = try await db.applyPull(householdId: household, page: page(deleted: false, cursor: 1))
        _ = try await db.applyPull(householdId: household, page: page(deleted: true, cursor: 2))
        #expect(try await db.queryForTesting("SELECT deleted_at FROM store")[0]["deleted_at"] == "2026-11-23T18:00:00.000Z")
    }

    @Test("SHOP-08: a page for a household that is no longer open writes nothing")
    func switchedHousehold() async throws {
        let root = switcherTempRoot()
        let db = try await openSyncDatabase(household, root: root)
        let other = UUID()
        try await db.open(householdId: other)
        let page = SyncPullPage(cursor: 9, hasMore: false, changes: ["store": [jsonRow(["id": UUID().uuidString.lowercased(), "name": "x"])]])
        #expect(try await db.applyPull(householdId: household, page: page) == false)
        #expect(try await db.queryForTesting("SELECT id FROM store").isEmpty)
        #expect(try await db.readCursor() == 0)
    }

    @Test("SHOP-08: timestamp normalization")
    func normalization() {
        #expect(HouseholdDatabase.normalized(timestamp: "2026-11-23T17:47:27.123456+00:00") == "2026-11-23T17:47:27.123Z")
        #expect(HouseholdDatabase.normalized(timestamp: "2026-11-23T17:47:27Z") == "2026-11-23T17:47:27.000Z")
        #expect(HouseholdDatabase.normalized(timestamp: "2026-11-23T17:47:27.1Z") == "2026-11-23T17:47:27.100Z")
        #expect(HouseholdDatabase.normalized(timestamp: "2026-11-23T18:47:27.500+01:00") == "2026-11-23T17:47:27.500Z")
        #expect(HouseholdDatabase.normalized(timestamp: "not a date") == "not a date")
    }
}

@Suite("SHOP-08 migration v6")
struct SyncMigrationTests {
    @Test("SHOP-08: v6 resets the cursor and keeps the rows of an older file")
    func keepsRowsResetsCursor() async throws {
        let root = switcherTempRoot()
        let household = UUID()
        // A file as the previous release left it: migrated up to v5, with a list item and an advanced cursor.
        let seeded = HouseholdDatabase(rootURL: root)
        try await seeded.openLegacyForTesting(householdId: household, upTo: "v5")

        let db = HouseholdDatabase(rootURL: root)
        try await db.open(householdId: household)
        #expect(try await db.readCursor() == 0)
        let items = try await db.queryForTesting("SELECT name, checked, sync_seq FROM list_item")
        #expect(items == [["name": "Milk", "checked": "NULL", "sync_seq": "NULL"]])
    }
}
