import Foundation
import Testing
@testable import Core
@testable import Sync

private let now = Date(timeIntervalSince1970: 1_800_000_000)
private func ago(_ days: Double, extra: TimeInterval = 3_600) -> Date { now.addingTimeInterval(-days * 86_400 - extra) }
private let allOn: Set<SearchModule> = [.shopping, .pantry, .maintenance]

private func row(_ table: String, _ name: String, _ deleted: Date) -> DeletedRow {
    DeletedRow(table: table, id: UUID(), name: name, deletedAt: deleted)
}

@Suite("CORE-10 recently deleted logic")
struct RecentlyDeletedTests {
    @Test("CORE-10: AC2 days left is 30 minus the age in whole days")
    func daysLeft() {
        #expect(RecentlyDeleted.daysLeft(deletedAt: ago(2), now: now) == 28)
        #expect(RecentlyDeleted.daysLeft(deletedAt: now, now: now) == 30)
        #expect(RecentlyDeleted.daysLeft(deletedAt: ago(29, extra: 0), now: now) == 1)
        #expect(RecentlyDeleted.daysLeft(deletedAt: ago(30, extra: 0), now: now) == 0)
        #expect(RecentlyDeleted.daysLeftText(1) == "1 day left")
        #expect(RecentlyDeleted.daysLeftText(28) == "28 days left")
    }

    @Test("CORE-10: AC4 purged rows are hidden, newest deleted first")
    func sectionsHideAndOrder() {
        let rows = [row("store", "Old", ago(31)), row("store", "A", ago(5)), row("list_item", "B", ago(1)), row("store", "Gone", ago(30, extra: 0))]
        let sections = RecentlyDeleted.sections(rows: rows, modules: allOn, now: now)
        #expect(sections.map(\.module) == [.shopping, .pantry, .maintenance])
        #expect(sections[0].items.map(\.name) == ["B", "A"])
        #expect(RecentlyDeleted.state(sections: sections) == .populated)
        #expect(RecentlyDeleted.state(sections: RecentlyDeleted.sections(rows: [], modules: allOn, now: now)) == .empty)
    }

    @Test("CORE-10: AC5 a module that is off has no section")
    func moduleOff() {
        let rows = [row("pantry_item", "Rice", ago(1)), row("store", "Lidl", ago(1))]
        let sections = RecentlyDeleted.sections(rows: rows, modules: [.shopping, .maintenance], now: now)
        #expect(sections.map(\.module) == [.shopping, .maintenance])
        #expect(sections.flatMap(\.items).map(\.name) == ["Lidl"])
    }

    @Test("CORE-10: AC3 restore clears deleted_at and queues one upsert whose data has deleted_at null")
    func restoreWritesOutbox() async throws {
        let db = HouseholdDatabase(rootURL: switcherTempRoot())
        let household = UUID(), id = UUID()
        try await db.open(householdId: household)
        let stamp = Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt)
        try await db.insertSearchRowForTesting(
            SearchRow(table: "maint_task", id: id, name: "Service boiler", deletedAt: ago(2).formatted(stamp)), householdId: household
        )
        try await db.restore(householdId: household, table: "maint_task", id: id, now: now)
        let rows = try await db.outboxForTesting()
        #expect(rows.count == 1)
        let entry = try #require(rows.first)
        #expect(entry.table == "maint_task" && entry.op == "upsert" && entry.rowId == id.uuidString.lowercased())
        let data = try #require(try JSONSerialization.jsonObject(with: Data(entry.data.utf8)) as? [String: Any])
        #expect(data.count == 1)
        #expect(data["deleted_at"] is NSNull)
        #expect(UUID(uuidString: entry.id) != nil)
        // Restoring again changes nothing: the row is live.
        try await db.restore(householdId: household, table: "maint_task", id: id, now: now)
        #expect(try await db.outboxForTesting().count == 1)
    }
}
