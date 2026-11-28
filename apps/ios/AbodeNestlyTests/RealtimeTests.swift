import Contract
import Foundation
import Testing
@testable import Core
@testable import Sync

private func settle(until condition: () -> Bool) async {
    for _ in 0..<1000 where !condition() { try? await Task.sleep(for: .milliseconds(5)) }
}

private let allOn = HomeModules(shopping: true, pantry: true, maintenance: true)
private let shoppingOff = HomeModules(shopping: false, pantry: true, maintenance: true)

/// SHOP-09 (SPEC §6.4 step 3). Web twin: `web/src/shopping/Realtime.test.ts`.
@Suite("SHOP-09 realtime", .serialized)
struct RealtimeTests {
    private let household = UUID()

    @Test("SHOP-09: AC1 an event kicks the sync engine once; the fake has no payload to read")
    func eventKicks() async {
        let realtime = FakeRealtime()
        let sync = FakeSync()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: allOn, sync: sync) }
        await settle { realtime.active == 1 }
        realtime.emit(table: "list_item")
        await settle { sync.kicks == 1 }
        #expect(sync.kicks == 1)
        task.cancel()
        await task.value
    }

    @Test("SHOP-09: the subscription covers exactly the ten published tables, for the open household")
    func allTables() async {
        let realtime = FakeRealtime()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: allOn, sync: FakeSync()) }
        await settle { realtime.active == 1 }
        let subscription = realtime.subscriptions.first
        #expect(subscription?.householdId == household)
        #expect(Set(subscription?.tables ?? []) == [
            "location", "invite", "activity", "attachment", "notification_pref",
            "store", "shopping_list", "list_item", "store_item_order", "item_price",
        ])
        #expect(subscription?.tables.count == 10)
        task.cancel()
        await task.value
    }

    @Test("SHOP-09: AC4 with Shopping off only the five core tables are subscribed")
    func shoppingOffSubscribesCoreOnly() async {
        let realtime = FakeRealtime()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: shoppingOff, sync: FakeSync()) }
        await settle { realtime.active == 1 }
        #expect(realtime.subscriptions.first?.tables == ["location", "invite", "activity", "attachment", "notification_pref"])
        #expect(RealtimeTables.tables(for: shoppingOff).allSatisfy({ !RealtimeTables.shopping.contains($0) }))
        task.cancel()
        await task.value
    }

    @Test("SHOP-09: the joined status catches up with one kick; an error status neither kicks nor throws")
    func statuses() async {
        let realtime = FakeRealtime()
        let sync = FakeSync()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: allOn, sync: sync) }
        await settle { realtime.active == 1 }
        realtime.emit(status: .error)
        realtime.emit(status: .timedOut)
        realtime.emit(status: .closed)
        try? await Task.sleep(for: .milliseconds(50))
        #expect(sync.kicks == 0)
        realtime.emit(status: .subscribed)
        await settle { sync.kicks == 1 }
        #expect(sync.kicks == 1)
        task.cancel()
        await task.value
    }

    @Test("SHOP-09: cancelling the task unsubscribes, and events after that do nothing")
    func cancelUnsubscribes() async {
        let realtime = FakeRealtime()
        let sync = FakeSync()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: allOn, sync: sync) }
        await settle { realtime.active == 1 }
        task.cancel()
        await task.value
        #expect(realtime.cancelled == 1 && realtime.active == 0)
        realtime.emit()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(sync.kicks == 0)
    }

    @Test("SHOP-09: five events during a run cost at most one more run")
    func burstCoalesces() async throws {
        let api = FakeApiService()
        let db = try await openSyncDatabase(household)
        let engine = LiveSyncEngine(transport: api, database: db, isOnline: { true })
        let realtime = FakeRealtime()
        let task = Task { await HouseholdRealtime.run(realtime: realtime, householdId: household, modules: allOn, sync: engine) }
        await settle { realtime.active == 1 }
        // The first pull waits, so the run is in flight while the events arrive.
        api.hold()
        realtime.emit()
        await settle { api.calls.contains { if case .syncPull = $0 { true } else { false } } }
        for _ in 0..<4 { realtime.emit() }
        try await Task.sleep(for: .milliseconds(100))
        api.release()
        await settle { api.calls.filter { if case .syncPull = $0 { true } else { false } }.count >= 2 }
        try await Task.sleep(for: .milliseconds(200))
        let pulls = api.calls.filter { if case .syncPull = $0 { true } else { false } }.count
        #expect(pulls == 2)
        task.cancel()
        await task.value
    }
}
