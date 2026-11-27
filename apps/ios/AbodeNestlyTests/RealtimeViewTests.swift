import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
@testable import Core
@testable import Shopping
@testable import Sync
@testable import UI

private func has(_ view: InspectableView<ViewType.ClassifiedView>, _ id: String) -> Bool {
    (try? view.find(viewWithAccessibilityIdentifier: id)) != nil
}

/// SHOP-09 AC2 and AC3 on the list screen (DESIGN C2): the model is built once and never re-created; only a
/// Realtime-triggered pull changes what it shows. Web twin: `web/src/shopping/Realtime.test.tsx`.
@MainActor
@Suite("SHOP-09 realtime view", .serialized)
struct RealtimeViewTests {
    private let household = UUID()
    private let list = UUID()

    private func inspect(_ model: ListDetailModel, online: Bool = true) throws -> InspectableView<ViewType.ClassifiedView> {
        try ListDetailView(model: model, title: "Groceries", isOnline: online, checkedExpanded: true).inspect()
    }

    private func milkId(_ db: HouseholdDatabase) async throws -> String {
        let rows = try await db.queryForTesting("SELECT id FROM list_item WHERE name = 'Milk'")
        return try #require(rows.first?["id"])
    }

    private func settle(until condition: @MainActor () -> Bool) async {
        for _ in 0..<1000 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
    }

    @Test("SHOP-09: AC2 another member checked off Milk; after the Realtime-triggered pull it shows under Checked")
    func remoteCheckOff() async throws {
        let api = FakeApiService()
        let db = try await openSyncDatabase(household)
        try await seedList(db, id: list, name: "Groceries")
        try await seedItem(db, list: list, name: "Milk")
        let engine = LiveSyncEngine(transport: api, database: db, isOnline: { true })
        let model = ListDetailModel(householdId: household, listId: list, userId: UUID(), database: db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await settle { model.unchecked.count == 1 }
        #expect(model.checked.isEmpty)

        let id = try await milkId(db)
        api.queuePullResults([.success(SyncPullPage(cursor: 5, hasMore: false, changes: ["list_item": [jsonRow([
            "id": id, "household_id": household.uuidString.lowercased(), "list_id": list.uuidString.lowercased(), "name": "Milk",
            "checked": true, "checked_at": "2026-11-27T10:00:00.000Z", "deleted_at": NSNull(), "created_at": "2026-11-01T10:00:00.000Z",
            "updated_at": "2026-11-27T10:00:00.000Z", "sync_seq": 5,
        ])]]))])
        let realtime = FakeRealtime()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: HomeModules(shopping: true, pantry: false, maintenance: false), sync: engine) }
        defer { task.cancel() }
        await settle { realtime.active == 1 }

        realtime.emit(table: "list_item")
        await settle { model.checked.count == 1 }
        #expect(model.checked.first?.name == "Milk" && model.unchecked.isEmpty)
        let view = try inspect(model)
        #expect(has(view, "list.checked.row.Milk"))
    }

    @Test("SHOP-09: AC3 offline with Realtime down shows the local rows and no error")
    func offlineNoError() async throws {
        let api = FakeApiService()
        api.queuePushResults([.failure(.network)])
        api.queuePullResults([.failure(.network)])
        let db = try await openSyncDatabase(household)
        try await seedList(db, id: list, name: "Groceries")
        try await seedItem(db, list: list, name: "Milk")
        let engine = LiveSyncEngine(transport: api, database: db, isOnline: { true })
        let model = ListDetailModel(householdId: household, listId: list, userId: UUID(), database: db, kick: { await engine.kick() })
        let follower = Task { await model.start() }
        defer { follower.cancel() }
        await settle { model.unchecked.count == 1 }

        let realtime = FakeRealtime()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: HomeModules(shopping: true, pantry: false, maintenance: false), sync: engine) }
        defer { task.cancel() }
        await settle { realtime.active == 1 }
        realtime.emit(status: .error)
        realtime.emit()
        for _ in 0..<400 where await engine.currentPhase != .offline { try await Task.sleep(for: .milliseconds(5)) }

        #expect(model.unchecked.map(\.name) == ["Milk"] && !model.writeFailed && !model.isGone)
        let view = try inspect(model, online: false)
        #expect(has(view, "list.row.Milk"))
    }
}
