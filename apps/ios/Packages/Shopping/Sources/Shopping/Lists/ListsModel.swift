import Contract
import Foundation
import Observation
import Sync

/// A swipe waiting out its undo window.
public struct PendingListAction: Equatable, Identifiable, Sendable {
    public enum Kind: Sendable { case archive, delete }
    public var id: UUID
    public var kind: Kind
    public var name: String

    public var message: String { "\(kind == .archive ? "Archived" : "Deleted") \(name)" }
}

/// DESIGN C1. Reads only the local database, so it works offline; every write goes through the outbox in one local
/// transaction and then kicks sync. Web twin: `web/src/shopping/Lists.tsx`.
@Observable
@MainActor
public final class ListsModel {
    public private(set) var snapshot = ShoppingListsSnapshot()
    /// `false` until the first read, so the empty state does not flash.
    public private(set) var loaded = false
    /// Swipes inside their undo window, newest first. The list is already hidden from the sections.
    public private(set) var pending: [PendingListAction] = []
    /// Set when a write could not be stored locally.
    public private(set) var writeFailed = false

    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let kick: @Sendable () async -> Void
    @ObservationIgnored private let undoWindow: Duration
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private let makeId: @MainActor () -> UUID
    @ObservationIgnored private var timers: [UUID: Task<Void, Never>] = [:]

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

    public var sections: ListSections { Lists.sections(snapshot, hidden: Set(pending.map(\.id))) }
    /// Stores for the picker, by name.
    public var stores: [StoreRecord] { snapshot.stores.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending } }

    /// Follows the local rows until the view goes away or the household's database closes.
    public func start() async {
        for await snapshot in await database.observeShoppingLists(householdId: householdId) {
            self.snapshot = snapshot
            loaded = true
        }
        // A stream that ended at once (database not open): nothing cached to show.
        loaded = true
    }

    /// Saves a new list and returns it. The list exists locally and in the outbox before this returns (AC5, AC6).
    @discardableResult
    public func create(name: String, storeId: UUID?) async -> ListRow? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let id = makeId()
        let stamp = now().formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
        // The server owns updated_at and sync_seq; the local row carries placeholders until the next pull replaces it.
        let row: [String: JSONValue] = [
            "id": .string(id.uuidString.lowercased()), "household_id": .string(householdId.uuidString.lowercased()),
            "name": .string(trimmed), "store_id": storeId.map { .string($0.uuidString.lowercased()) } ?? .null,
            "is_template": .bool(false), "archived_at": .null, "deleted_at": .null,
            "created_at": .string(stamp), "updated_at": .string(stamp), "sync_seq": .int(0),
        ]
        do {
            try await database.upsert(table: "shopping_list", row: row, now: now())
            writeFailed = false
        } catch {
            writeFailed = true
            return nil
        }
        await kick()
        return ListRow(
            id: id, name: trimmed, storeName: storeId.flatMap { id in stores.first { $0.id == id }?.name }, count: "No items"
        )
    }

    /// Swipe: the list leaves the sections now and is written after the undo window, unless Undo comes first.
    public func archive(_ row: ListRow) { defer_(PendingListAction(id: row.id, kind: .archive, name: row.name)) }
    public func delete(_ row: ListRow) { defer_(PendingListAction(id: row.id, kind: .delete, name: row.name)) }

    /// Undo inside the window: nothing was written.
    public func undo(_ id: UUID) {
        timers.removeValue(forKey: id)?.cancel()
        pending.removeAll { $0.id == id }
    }

    /// Restore of an archived list: written at once (no toast), only the changed column queued.
    public func restore(_ row: ListRow) async {
        await write { try await $0.patch(table: "shopping_list", id: row.id, fields: ["archived_at": .null], now: self.now()) }
    }

    /// Leaving the screen (or the app) writes what is still waiting rather than dropping it.
    public func flush() async {
        let waiting = pending
        for action in waiting { timers.removeValue(forKey: action.id)?.cancel() }
        pending = []
        for action in waiting { await commit(action) }
    }

    private func defer_(_ action: PendingListAction) {
        // A second action on the same list replaces the first, so only one write is ever queued.
        timers.removeValue(forKey: action.id)?.cancel()
        pending.removeAll { $0.id == action.id }
        pending.insert(action, at: 0)
        let window = undoWindow
        timers[action.id] = Task { [weak self] in
            try? await Task.sleep(for: window)
            guard !Task.isCancelled, let self else { return }
            self.timers[action.id] = nil
            guard self.pending.contains(where: { $0.id == action.id }) else { return }
            await self.commit(action)
            self.pending.removeAll { $0.id == action.id }
        }
    }

    private func commit(_ action: PendingListAction) async {
        switch action.kind {
        case .archive:
            let stamp = now().formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
            await write { try await $0.patch(table: "shopping_list", id: action.id, fields: ["archived_at": .string(stamp)], now: self.now()) }
        case .delete:
            await write { try await $0.remove(table: "shopping_list", id: action.id, now: self.now()) }
        }
    }

    private func write(_ body: (HouseholdDatabase) async throws -> Void) async {
        do {
            try await body(database)
            writeFailed = false
        } catch {
            writeFailed = true
            return
        }
        await kick()
    }
}
