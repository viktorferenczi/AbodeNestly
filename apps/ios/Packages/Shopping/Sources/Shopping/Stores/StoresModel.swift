import Contract
import Foundation
import Observation
import Sync

/// DESIGN C4. Reads only the local database, so it works offline; every write goes through the outbox in one local
/// transaction and then kicks sync. Web twin: `web/src/shopping/Stores.tsx`.
@Observable
@MainActor
public final class StoresModel {
    public private(set) var stored: [StoreRow] = []
    /// `false` until the first read, so the empty state does not flash.
    public private(set) var loaded = false
    /// Deletes inside their undo window, newest first. The store is already hidden from `rows`.
    public private(set) var pending: [PendingStoreDelete] = []
    /// Set when a write could not be stored locally.
    public private(set) var writeFailed = false

    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let kick: @Sendable () async -> Void
    @ObservationIgnored private let undoWindow: Duration
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private let makeId: @MainActor () -> UUID
    @ObservationIgnored private var timers: [UUID: Task<Void, Never>] = [:]
    /// Deletes whose window ended and whose local write is running: no toast, no Undo, no second write.
    @ObservationIgnored private var writing: Set<UUID> = []

    public init(
        householdId: UUID, database: HouseholdDatabase, kick: @escaping @Sendable () async -> Void,
        undoWindow: Duration = Lists.undoWindow, now: @escaping @MainActor () -> Date = { Date() },
        makeId: @escaping @MainActor () -> UUID = { UUID() }
    ) {
        self.householdId = householdId
        self.database = database
        self.kick = kick
        self.undoWindow = undoWindow
        self.now = now
        self.makeId = makeId
    }

    /// Live stores by name, minus the ones whose delete is waiting.
    public var rows: [StoreRow] {
        let hidden = Set(pending.map(\.id))
        return Stores.sorted(stored.filter { !hidden.contains($0.id) })
    }

    /// For name validation: every live store, including one whose delete is still inside its undo window (its row is
    /// not written yet, so its name is taken until the delete lands).
    public var records: [StoreRecord] { stored.map { StoreRecord(id: $0.id, name: $0.name) } }

    /// Follows the local rows until the view goes away or the household's database closes.
    public func start() async {
        for await rows in await database.observeStores(householdId: householdId) {
            stored = rows
            loaded = true
        }
        // A stream that ended at once (database not open): nothing cached to show.
        loaded = true
    }

    /// Saves a new store and returns its id, or `nil` when the name is not valid or could not be stored. The store
    /// exists locally and in the outbox before this returns (AC4).
    @discardableResult
    public func create(name: String) async -> UUID? {
        guard Stores.validate(name: name, stores: records) == nil else { return nil }
        let id = makeId()
        do {
            try await database.upsert(
                table: "store", row: Stores.row(id: id, householdId: householdId, name: name, now: now()), now: now()
            )
            writeFailed = false
        } catch {
            writeFailed = true
            return nil
        }
        await kick()
        return id
    }

    /// Swipe: the store leaves the list now and is written after the undo window, unless Undo comes first. Lists keep
    /// their `store_id`; their chip falls back to "Add store" and Recently deleted restores the link.
    public func delete(_ row: StoreRow) {
        let action = PendingStoreDelete(id: row.id, name: row.name)
        timers.removeValue(forKey: action.id)?.cancel()
        pending.removeAll { $0.id == action.id }
        pending.insert(action, at: 0)
        let window = undoWindow
        timers[action.id] = Task { [weak self] in
            try? await Task.sleep(for: window)
            guard !Task.isCancelled, let self else { return }
            self.timers[action.id] = nil
            guard self.pending.contains(where: { $0.id == action.id }), !self.writing.contains(action.id) else { return }
            self.writing.insert(action.id)
            let wrote = await self.write(action)
            self.pending.removeAll { $0.id == action.id }
            self.writing.remove(action.id)
            // The kick waits on the network, so it runs after the toast and the pending state are gone.
            if wrote { await self.kick() }
        }
    }

    /// Undo inside the window: nothing was written.
    public func undo(_ id: UUID) {
        guard !writing.contains(id) else { return }
        timers.removeValue(forKey: id)?.cancel()
        pending.removeAll { $0.id == id }
    }

    /// Leaving the screen (or the app) writes what is still waiting rather than dropping it.
    public func flush() async {
        let waiting = pending.filter { !writing.contains($0.id) }
        for action in waiting {
            timers.removeValue(forKey: action.id)?.cancel()
            writing.insert(action.id)
        }
        var wrote = false
        for action in waiting where await write(action) { wrote = true }
        let done = Set(waiting.map(\.id))
        pending.removeAll { done.contains($0.id) }
        writing.subtract(done)
        if wrote { await kick() }
    }

    /// The local write only; the caller kicks sync. `true` when something was stored.
    private func write(_ action: PendingStoreDelete) async -> Bool {
        do {
            try await database.remove(table: "store", id: action.id, now: now())
            writeFailed = false
            return true
        } catch {
            writeFailed = true
            return false
        }
    }
}
