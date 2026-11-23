import Contract
import Foundation
import SwiftUI
import Testing
import ViewInspector
import os
@testable import Core
@testable import Sync
@testable import UI

private final class RecordingScheduler: BackgroundSyncScheduler, @unchecked Sendable {
    let requests = OSAllocatedUnfairLock<[(String, Date)]>(initialState: [])
    func schedule(identifier: String, earliestBegin: Date) { requests.withLock { $0.append((identifier, earliestBegin)) } }
}

@MainActor
private struct SheetHarness {
    let household = UUID()
    let db: HouseholdDatabase
    let sync = FakeSync()
    let network: NetworkMonitor
    let model: SyncStatusModel
    let running: Task<Void, Never>

    init(online: Bool = true, pending: Int = 0, failed: [(String, SyncRejectionReason)] = []) async throws {
        let db = try await openSyncDatabase(household)
        self.db = db
        for index in 0..<pending { try await db.upsert(table: "store", row: storeRow(name: "Store \(index)")) }
        for (op, reason) in failed {
            try await db.enqueue(table: "list_item", op: op, rowId: UUID(), data: op == "delete" ? nil : ["name": .string("x")])
            let queued = try #require(try await db.pendingMutations(householdId: household, tables: ["list_item"], limit: 1).first)
            try await db.settle(householdId: household, applied: [], rejected: [(queued.seq, reason, "detail")], now: Date())
        }
        network = NetworkMonitor(isOnline: online)
        let model = SyncStatusModel(engine: sync, database: db, network: network)
        self.model = model
        let household = household
        running = Task { await model.run(householdId: household) }
        let expected = pending + failed.count
        for _ in 0..<2000 where model.counts.pending + model.counts.failed < expected { try? await Task.sleep(for: .milliseconds(5)) }
    }

    func sheet() throws -> InspectableView<ViewType.ClassifiedView> { try SyncSheetView(model: model, onClose: {}).inspect() }
    func text(_ id: String) throws -> String { try sheet().find(viewWithAccessibilityIdentifier: id).text().string() }
    func has(_ id: String) -> Bool { (try? sheet().find(viewWithAccessibilityIdentifier: id)) != nil }
}

@MainActor
@Suite("SHOP-08 sync sheet and chip")
struct SyncSheetViewTests {
    @Test("SHOP-08: idle state says up to date, with no pending and no failed list; the chip is hidden")
    func idle() async throws {
        let h = try await SheetHarness()
        #expect(h.model.state == .idle)
        #expect(h.model.chip == .hidden)
        #expect(try h.text("sync.status") == "Up to date")
        #expect(try h.text("sync.pending") == "Pending changes: 0")
        #expect(try h.text("sync.failed") == "Failed changes: 0")
        #expect(!h.has("sync.failed.row.1"))
        h.running.cancel()
    }

    @Test("SHOP-08: AC4 pending state: the chip reads 3 to sync and the sheet lists 3 pending changes")
    func pending() async throws {
        let h = try await SheetHarness(pending: 3)
        #expect(h.model.state == .pending)
        #expect(h.model.chip == .pending(3))
        #expect(try SyncStatusChip(state: h.model.chip, onTap: {}).inspect().find(text: "3 to sync") != nil)
        #expect(try h.text("sync.pending") == "Pending changes: 3")
        #expect(try h.text("sync.status") == "Changes are waiting to sync")
        h.running.cancel()
    }

    @Test("SHOP-08: AC3 failed state lists the change with its reason and offers Retry and Discard that reach the engine")
    func failed() async throws {
        let h = try await SheetHarness(failed: [("upsert", .validation)])
        #expect(h.model.state == .failed)
        let seq = try #require(h.model.failed.first?.seq)
        #expect(try h.text("sync.failed.row.\(seq)") == "list_item · save · Couldn't save: invalid")
        #expect(try h.text("sync.failed") == "Failed changes: 1")
        #expect(h.model.chip == .pending(1))
        try h.sheet().find(viewWithAccessibilityIdentifier: "sync.retry.\(seq)").button().tap()
        try h.sheet().find(viewWithAccessibilityIdentifier: "sync.discard.\(seq)").button().tap()
        await waitUntil { h.sync.retried == [seq] && h.sync.discarded == [seq] }
        #expect(h.sync.retried == [seq] && h.sync.discarded == [seq])
        h.running.cancel()
    }

    @Test("SHOP-08: every reason has its own words")
    func reasons() {
        func line(_ op: String, _ reason: SyncRejectionReason) -> String {
            SyncStatusModel.description(of: FailedChange(seq: 1, table: "store", op: op, rowId: "r", reason: reason, detail: ""))
        }
        #expect(line("upsert", .rlsDenied) == "store · save · Couldn't save: not allowed")
        #expect(line("delete", .moduleDisabled) == "store · delete · Couldn't save: module turned off")
        #expect(line("delta", .unknownTable) == "store · adjust · Couldn't save: unknown")
        #expect(line("upsert", .unknown("new")) == "store · save · Couldn't save: unknown")
    }

    @Test("SHOP-08: AC5 Sync now calls kick on the engine")
    func syncNow() async throws {
        let h = try await SheetHarness()
        try h.sheet().find(viewWithAccessibilityIdentifier: "sync.now").button().tap()
        await waitUntil { h.sync.kicks == 1 }
        #expect(h.sync.kicks == 1)
        h.running.cancel()
    }

    @Test("SHOP-08: the chip shows Syncing, Offline, or nothing, with syncing first and offline before pending")
    func chipPrecedence() async throws {
        let h = try await SheetHarness(pending: 2)
        h.sync.setPhase(.syncing)
        await waitUntil { h.model.phase == .syncing }
        #expect(h.model.chip == .syncing)
        #expect(try SyncStatusChip(state: .syncing, onTap: {}).inspect().find(text: "Syncing") != nil)
        h.sync.setPhase(.idle)
        await waitUntil { h.model.phase == .idle }
        #expect(h.model.chip == .pending(2))
        h.network.setOnline(false)
        #expect(h.model.chip == .offline)
        #expect(try SyncStatusChip(state: .offline, onTap: {}).inspect().find(text: "Offline") != nil)
        h.running.cancel()
    }

    @Test("SHOP-08: a hidden chip draws nothing")
    func hiddenChip() throws {
        #expect(throws: (any Error).self) { try SyncStatusChip(state: .hidden, onTap: {}).inspect().find(text: "Syncing") }
        #expect(SyncChipState.hidden.text == nil)
    }

    @Test("SHOP-08: offline, the sheet still opens, says so, and shows the counts without an error")
    func offlineSheet() async throws {
        let h = try await SheetHarness(online: false, pending: 1)
        #expect(try h.text("sync.status") == "Offline — changes will sync when you're back online")
        #expect(try h.text("sync.pending") == "Pending changes: 1")
        h.running.cancel()
    }

    @Test("SHOP-08: tapping the chip in the TopBar reports the tap")
    func topBarChipTap() throws {
        let taps = OSAllocatedUnfairLock(initialState: 0)
        let bar = TopBar(title: "Home", status: .pending(2), onStatusTap: { taps.withLock { $0 += 1 } })
        try bar.inspect().find(viewWithAccessibilityIdentifier: "sync.chip").button().tap()
        #expect(taps.withLock { $0 } == 1)
    }
}

@Suite("SHOP-08 background refresh")
struct BackgroundSyncTests {
    @Test("SHOP-08: scheduling asks for com.abodenestly.sync at least 15 minutes from now")
    func schedules() {
        let scheduler = RecordingScheduler()
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        scheduler.scheduleNext(now: now)
        let request = scheduler.requests.withLock { $0 }
        #expect(request.count == 1)
        #expect(request[0].0 == "com.abodenestly.sync")
        #expect(request[0].1 == now.addingTimeInterval(15 * 60))
    }

    @Test("SHOP-08: the engine asks for the next refresh when a run ends")
    func engineSchedulesAfterRun() async throws {
        let scheduler = RecordingScheduler()
        let db = try await openSyncDatabase()
        let engine = LiveSyncEngine(transport: FakeApiService(), database: db, isOnline: { true }, onRunFinished: { scheduler.scheduleNext() })
        await engine.kick()
        #expect(scheduler.requests.withLock { $0.count } == 1)
    }

    @Test("SHOP-08: the identifier matches Info.plist")
    func plist() throws {
        let info = try #require(Bundle.main.infoDictionary)
        #expect((info["BGTaskSchedulerPermittedIdentifiers"] as? [String]) == [BackgroundSync.taskIdentifier])
        #expect((info["UIBackgroundModes"] as? [String])?.contains("fetch") == true)
    }
}
