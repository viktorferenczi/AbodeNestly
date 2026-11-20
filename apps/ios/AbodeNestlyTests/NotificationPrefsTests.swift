import Foundation
import Testing
@testable import Core
@testable import Sync

// Golden values: identical in the web and Android suites.
private let user = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
private let household = UUID(uuidString: "22222222-2222-4222-8222-222222222222")!
private let goldenEatSoon = "38c53767-3538-5d08-9384-d9843b1761ad"
private let goldenActivity = "4aa3e84e-d57f-53f9-8472-e9fcf82e8393"

@Suite("CORE-12 notification preference logic")
struct NotificationPrefsTests {
    @Test("CORE-12: row_id is the cross-platform golden UUID v5")
    func goldenRowId() {
        #expect(NotificationPrefs.rowId(userId: user, householdId: household, type: .eatSoon).uuidString.lowercased() == goldenEatSoon)
        #expect(NotificationPrefs.rowId(userId: user, householdId: household, type: .activity).uuidString.lowercased() == goldenActivity)
    }

    @Test("CORE-12: types keep the DESIGN order and titles")
    func order() {
        #expect(NotificationType.allCases.map(\.title) == ["Eat soon", "Low stock", "Task due", "Warranty", "Activity"])
        #expect(NotificationType.allCases.map(\.rawValue) == ["eat_soon", "low_stock", "task_due", "warranty", "activity"])
    }

    @Test(
        "CORE-12: AC1/AC4/AC5 visible rows match the module flags",
        arguments: [
            (true, true, ["eat_soon", "low_stock", "task_due", "warranty", "activity"]),
            (false, true, ["task_due", "warranty", "activity"]),
            (true, false, ["eat_soon", "low_stock", "activity"]),
            (false, false, ["activity"]),
        ] as [(Bool, Bool, [String])]
    )
    func visible(pantry: Bool, maintenance: Bool, expected: [String]) {
        let modules = HomeModules(shopping: true, pantry: pantry, maintenance: maintenance)
        #expect(NotificationPrefs.visibleTypes(modules).map(\.rawValue) == expected)
    }

    @Test("CORE-12: unknown flags show only Activity")
    func unknownFlags() {
        #expect(NotificationPrefs.visibleTypes(nil) == [.activity])
    }

    @Test("CORE-12: a missing row means enabled")
    func defaults() {
        let off = NotificationPrefRow(userId: user, householdId: household, type: "eat_soon", enabled: false)
        #expect(NotificationPrefs.enabled([], .eatSoon))
        #expect(!NotificationPrefs.enabled([off], .eatSoon))
        #expect(NotificationPrefs.enabled([off], .lowStock))
    }

    @Test("CORE-12: AC2 turning Eat soon off writes the row and queues one upsert with the v5 row_id")
    func setPrefWritesOutbox() async throws {
        let db = HouseholdDatabase(rootURL: switcherTempRoot())
        try await db.open(householdId: household)
        try await db.setNotificationPref(userId: user, householdId: household, type: "eat_soon", enabled: false)
        let rows = try await db.outboxForTesting()
        #expect(rows.count == 1)
        let entry = try #require(rows.first)
        #expect(entry.table == "notification_pref" && entry.op == "upsert" && entry.rowId == goldenEatSoon)
        let data = try #require(try JSONSerialization.jsonObject(with: Data(entry.data.utf8)) as? [String: Any])
        #expect(data["user_id"] as? String == user.uuidString.lowercased())
        #expect(data["household_id"] as? String == household.uuidString.lowercased())
        #expect(data["type"] as? String == "eat_soon")
        #expect(data["enabled"] as? Bool == false)
        #expect(data.count == 4)
    }

    @Test("CORE-12: toggling twice keeps one local row and queues two upserts")
    func upsertsOneRow() async throws {
        let db = HouseholdDatabase(rootURL: switcherTempRoot())
        try await db.open(householdId: household)
        try await db.setNotificationPref(userId: user, householdId: household, type: "eat_soon", enabled: false)
        try await db.setNotificationPref(userId: user, householdId: household, type: "eat_soon", enabled: true)
        var seen: [NotificationPrefRow] = []
        for await rows in await db.observeNotificationPrefs(householdId: household, userId: user) {
            seen = rows
            break
        }
        #expect(seen.count == 1 && seen[0].enabled)
        #expect(try await db.outboxForTesting().count == 2)
    }
}
