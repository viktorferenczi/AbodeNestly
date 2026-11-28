import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

private let fixedNow = Date(timeIntervalSince1970: 1_800_000_000)

private func setShopping(_ db: HouseholdDatabase, household: UUID, on: Bool) async throws {
    try await db.upsert(table: "household", row: [
        "id": .string(household.uuidString.lowercased()), "module_shopping": .bool(on),
    ])
}

private func itemRows(_ db: HouseholdDatabase) async throws -> [[String: String]] {
    try await db.queryForTesting("SELECT name, quantity, unit, list_id, source FROM list_item")
}

private func itemOutbox(_ db: HouseholdDatabase) async throws -> [(table: String, op: String)] {
    try await db.outboxForTesting().filter { $0.table == "list_item" }.map { ($0.table, $0.op) }
}

@Suite("SHOP-15 assistant shortcut")
struct AssistantShortcutTests {
    @Test("SHOP-15: AC1 \"milk\" adds one Milk row on Groceries with one outbox upsert")
    func addsMilk() async throws {
        let household = UUID(), groceries = UUID(), user = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, id: groceries, name: "Groceries")
        try await setShopping(db, household: household, on: true)

        let result = await AssistantShortcut.add(text: "milk", database: db, householdId: household, userId: user, now: fixedNow)

        #expect(result == .added(name: "Milk", list: "Groceries"))
        let rows = try await itemRows(db)
        #expect(rows.count == 1)
        #expect(rows.first?["name"] == "Milk")
        #expect(rows.first?["list_id"] == groceries.uuidString.lowercased())
        let queued = try await itemOutbox(db)
        #expect(queued.count == 1 && queued[0].op == "upsert")
    }

    @Test("SHOP-15: \"2 litres of milk\" keeps the quantity and unit like the voice sheet")
    func quantityAndUnit() async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, name: "Groceries")

        let result = await AssistantShortcut.add(text: "2 litres of milk", database: db, householdId: household, userId: nil, now: fixedNow)

        #expect(result == .added(name: "Milk", list: "Groceries"))
        let row = try #require(try await itemRows(db).first)
        #expect(row["name"] == "Milk")
        #expect(Double(row["quantity"] ?? "") == 2)
        #expect(row["unit"] == "l")
    }

    @Test("SHOP-15: AC2 an empty phrase, spaces, or a quantity with no name write nothing", arguments: ["", "   ", "2 litres"])
    func noItem(text: String) async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, name: "Groceries")

        let result = await AssistantShortcut.add(text: text, database: db, householdId: household, userId: nil, now: fixedNow)

        #expect(result == .noItem)
        #expect(try await itemRows(db).isEmpty)
        #expect(try await itemOutbox(db).isEmpty)
    }

    @Test("SHOP-15: AC4 shopping off for the household writes nothing")
    func shoppingOff() async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, name: "Groceries")
        try await setShopping(db, household: household, on: false)

        let result = await AssistantShortcut.add(text: "milk", database: db, householdId: household, userId: nil, now: fixedNow)

        #expect(result == .shoppingOff)
        #expect(try await itemRows(db).isEmpty)
        #expect(try await itemOutbox(db).isEmpty)
    }

    @Test("SHOP-15: a household row that was never pulled is trusted, so the add is written")
    func unknownFlagWrites() async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, name: "Groceries")

        let result = await AssistantShortcut.add(text: "milk", database: db, householdId: household, userId: nil, now: fixedNow)

        #expect(result == .added(name: "Milk", list: "Groceries"))
        #expect(try await itemRows(db).count == 1)
    }

    @Test("SHOP-15: no household writes nothing")
    func noHousehold() async throws {
        let db = try await openSyncDatabase()

        let result = await AssistantShortcut.add(text: "milk", database: db, householdId: nil, userId: nil, now: fixedNow)

        #expect(result == .noHousehold)
    }

    @Test("SHOP-15: a household with no list answers noList and writes nothing")
    func noList() async throws {
        let household = UUID()
        let db = try await openSyncDatabase(household)
        try await seedList(db, name: "Old", archived: "2026-11-10T10:00:00.000Z")
        try await seedList(db, name: "Weekly", template: true)

        let result = await AssistantShortcut.add(text: "milk", database: db, householdId: household, userId: nil, now: fixedNow)

        #expect(result == .noList)
        #expect(try await itemRows(db).isEmpty)
    }

    @Test("SHOP-15: Groceries wins over an older list; without it the oldest active non-template list is used")
    func listChoice() {
        func list(_ name: String, created: String, template: Bool = false, archived: String? = nil) -> ListRecord {
            ListRecord(id: UUID(), name: name, isTemplate: template, archivedAt: archived, createdAt: created)
        }
        let older = list("Hardware", created: "2026-11-01T10:00:00.000Z")
        let groceries = list(" groceries ", created: "2026-11-05T10:00:00.000Z")
        #expect(AssistantShortcut.pickList([groceries, older])?.id == groceries.id)

        let newer = list("Party", created: "2026-11-03T10:00:00.000Z")
        let template = list("Weekly", created: "2026-10-01T10:00:00.000Z", template: true)
        let archived = list("Old", created: "2026-09-01T10:00:00.000Z", archived: "2026-11-10T10:00:00.000Z")
        #expect(AssistantShortcut.pickList([newer, template, archived, older])?.id == older.id)

        let archivedGroceries = list("Groceries", created: "2026-08-01T10:00:00.000Z", archived: "2026-11-10T10:00:00.000Z")
        #expect(AssistantShortcut.pickList([archivedGroceries, older])?.id == older.id)
        #expect(AssistantShortcut.pickList([template, archived]) == nil)
        #expect(AssistantShortcut.pickList([]) == nil)
    }
}
