import Foundation
import SwiftUI
import Testing
import UserNotifications
import ViewInspector
@testable import Core
@testable import Sync

private let user = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!

private actor Kicks {
    var count = 0
    func add() { count += 1 }
}

@MainActor
private final class Opened {
    var urls: [URL] = []
}

@MainActor
private struct PrefsHarness {
    let household = UUID()
    let db: HouseholdDatabase
    let kicks = Kicks()
    let opened = Opened()
    let model: NotificationPrefsModel

    init(modules: HomeModules?, status: UNAuthorizationStatus = .authorized) async throws {
        let db = HouseholdDatabase(rootURL: switcherTempRoot())
        self.db = db
        try await db.open(householdId: household)
        let kicks = kicks
        model = NotificationPrefsModel(
            householdId: household, userId: user, modules: modules, database: db,
            kick: { await kicks.add() }, authorization: { status }
        )
        await model.refreshAuthorization()
        for _ in 0..<400 where !model.loaded { await Task.yield() }
        for _ in 0..<50 { await Task.yield() }
    }

    func inspect() throws -> InspectableView<ViewType.ClassifiedView> {
        let opened = opened
        return try NotificationPrefsView(model: model, openURL: { opened.urls.append($0) }).inspect()
    }
    func has(_ id: String) -> Bool { (try? inspect().find(viewWithAccessibilityIdentifier: id)) != nil }
}

private let allOn = HomeModules(shopping: true, pantry: true, maintenance: true)

@MainActor
@Suite("CORE-12 notification preferences view")
struct NotificationPrefsViewTests {
    @Test("CORE-12: AC1 idle shows five switches, all on, and no banner")
    func idle() async throws {
        let h = try await PrefsHarness(modules: allOn)
        for type in NotificationType.allCases {
            #expect(h.has("notif.switch.\(type.rawValue)"))
            #expect(h.model.isOn(type))
        }
        #expect(!h.has("notif.denied"))
    }

    @Test("CORE-12: AC2 turning Eat soon off queues one upsert and kicks sync")
    func toggle() async throws {
        let h = try await PrefsHarness(modules: allOn)
        await h.model.set(.eatSoon, enabled: false)
        for _ in 0..<400 where h.model.isOn(.eatSoon) { await Task.yield() }
        #expect(!h.model.isOn(.eatSoon))
        let rows = try await h.db.outboxForTesting()
        #expect(rows.count == 1 && rows[0].table == "notification_pref")
        #expect(await h.kicks.count == 1)
    }

    @Test("CORE-12: AC3 permission denied shows the banner and keeps the switches")
    func denied() async throws {
        let h = try await PrefsHarness(modules: allOn, status: .denied)
        #expect(h.has("notif.denied"))
        #expect(h.has("notif.switch.eat_soon") && h.has("notif.switch.activity"))
    }

    @Test("CORE-12: AC4 pantry off hides Eat soon and Low stock")
    func pantryOff() async throws {
        let h = try await PrefsHarness(modules: HomeModules(shopping: true, pantry: false, maintenance: true))
        #expect(!h.has("notif.switch.eat_soon") && !h.has("notif.switch.low_stock"))
        #expect(h.has("notif.switch.task_due") && h.has("notif.switch.activity"))
    }

    @Test("CORE-12: AC5 maintenance off hides Task due and Warranty; Activity stays")
    func maintenanceOff() async throws {
        let h = try await PrefsHarness(modules: HomeModules(shopping: true, pantry: true, maintenance: false))
        #expect(!h.has("notif.switch.task_due") && !h.has("notif.switch.warranty"))
        #expect(h.has("notif.switch.eat_soon") && h.has("notif.switch.activity"))
    }

    @Test("CORE-12: the settings link opens the system notification settings")
    func settingsLink() async throws {
        let h = try await PrefsHarness(modules: allOn)
        try h.inspect().find(viewWithAccessibilityIdentifier: "notif.settings").button().tap()
        #expect(h.opened.urls.map(\.absoluteString) == [UIApplication.openNotificationSettingsURLString])
    }
}
