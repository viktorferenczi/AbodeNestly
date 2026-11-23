import Foundation
import Observation
import Sync
import UI

/// DESIGN B12 states.
public enum SyncSheetState: Equatable, Sendable {
    case idle, pending, failed
}

/// What the chip and the sync sheet show, from the engine's phase, the network and the outbox counts of the open
/// household. Web twin: `useSyncStatus.ts`. One model serves both, so they never disagree.
@Observable
@MainActor
public final class SyncStatusModel {
    public private(set) var phase: SyncPhase = .idle
    public private(set) var counts = SyncCounts()
    public private(set) var failed: [FailedChange] = []

    @ObservationIgnored private let engine: any SyncEngine
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let network: NetworkMonitor

    public init(engine: any SyncEngine, database: HouseholdDatabase, network: NetworkMonitor) {
        self.engine = engine
        self.database = database
        self.network = network
    }

    public var online: Bool { network.isOnline }

    /// DESIGN A6 precedence: syncing, then offline, then "N to sync" (queued plus failed), else hidden.
    public var chip: SyncChipState {
        if phase == .syncing { return .syncing }
        if !online || phase == .offline { return .offline }
        let total = counts.pending + counts.failed
        return total > 0 ? .pending(total) : .hidden
    }

    public var state: SyncSheetState {
        if counts.failed > 0 { return .failed }
        return counts.pending > 0 ? .pending : .idle
    }

    public var statusLine: String {
        if phase == .syncing { return "Syncing…" }
        if !online || phase == .offline { return "Offline — changes will sync when you're back online" }
        return state == .idle ? "Up to date" : "Changes are waiting to sync"
    }

    /// Follows the engine and the household's outbox until the calling task is cancelled (`.task(id: householdId)`).
    /// `nil`: no household, only the phase is followed.
    public func run(householdId: UUID?) async {
        counts = SyncCounts()
        failed = []
        async let phases: Void = followPhases()
        async let counts: Void = followCounts(householdId)
        async let failed: Void = followFailed(householdId)
        _ = await (phases, counts, failed)
    }

    private func followPhases() async {
        for await next in await engine.phases() { phase = next }
    }

    // The household file may still be opening when the screen appears, and its stream ends when it closes: observe
    // whenever it is the open one, until cancelled.
    private func followCounts(_ householdId: UUID?) async {
        guard let householdId else { return }
        while !Task.isCancelled {
            if await database.current == householdId {
                for await next in await database.observeSyncCounts(householdId: householdId) { counts = next }
            }
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    private func followFailed(_ householdId: UUID?) async {
        guard let householdId else { return }
        while !Task.isCancelled {
            if await database.current == householdId {
                for await next in await database.observeFailed(householdId: householdId) { failed = next }
            }
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    public func syncNow() async { await engine.kick() }
    public func retry(_ change: FailedChange) async { await engine.retry(seq: change.seq) }
    public func discard(_ change: FailedChange) async { await engine.discard(seq: change.seq) }

    /// The copy for one failed change, e.g. "list_item · save · Couldn't save: invalid" (same words as web).
    public static func description(of change: FailedChange) -> String {
        let action = ["upsert": "save", "delete": "delete", "delta": "adjust"][change.op] ?? change.op
        let reason: String
        switch change.reason {
        case .rlsDenied: reason = "not allowed"
        case .validation: reason = "invalid"
        case .moduleDisabled: reason = "module turned off"
        case .unknownTable, .unknown: reason = "unknown"
        }
        return "\(change.table) · \(action) · Couldn't save: \(reason)"
    }
}
