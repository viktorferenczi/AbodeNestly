import Contract
import Foundation
import Observation
import Sync

/// DESIGN C2 (SHOP-02 slice). Reads only the local database, so it works offline; an add goes through the outbox in
/// one local transaction and then kicks sync. Web twin: `web/src/shopping/ListDetail.tsx`.
@Observable
@MainActor
public final class ListDetailModel {
    public private(set) var snapshot = ListDetailSnapshot()
    /// `false` until the first read, so "This list is gone" does not flash.
    public private(set) var loaded = false
    /// What the person has typed in the quick-add field.
    public var query = ""
    /// Set when a write could not be stored locally.
    public private(set) var writeFailed = false
    /// The item sheet, when open (SHOP-03).
    public var editing: ItemSheetState?
    /// Deletes, check-offs and clears inside their undo window, newest first. Deleted and cleared rows are already
    /// hidden from the list; a checked row shows ticked in place until its write lands.
    public private(set) var pendingActions: [PendingAction] = []

    @ObservationIgnored private let undoWindow: Duration
    @ObservationIgnored private var timers: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let listId: UUID
    @ObservationIgnored private let userId: UUID?
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let kick: @Sendable () async -> Void
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private let makeId: @MainActor () -> UUID

    public init(
        householdId: UUID, listId: UUID, userId: UUID?, database: HouseholdDatabase,
        kick: @escaping @Sendable () async -> Void, now: @escaping @MainActor () -> Date = { Date() },
        makeId: @escaping @MainActor () -> UUID = { UUID() }, undoWindow: Duration = Lists.undoWindow
    ) {
        self.undoWindow = undoWindow
        self.householdId = householdId
        self.listId = listId
        self.userId = userId
        self.database = database
        self.kick = kick
        self.now = now
        self.makeId = makeId
    }

    public var isGone: Bool { loaded && snapshot.list == nil }
    public var suggestions: [Suggestion] { QuickAdd.suggest(history: snapshot.history, query: query, listId: listId) }

    /// Oldest first, so a new item lands at the bottom; the id breaks ties so the order never flickers.
    public var unchecked: [ListItemRecord] {
        snapshot.items.filter { !$0.checked && !isHidden($0) }.sorted { ($0.createdAt ?? "", $0.id.uuidString) < ($1.createdAt ?? "", $1.id.uuidString) }
    }

    /// Newest check-off first.
    public var checked: [ListItemRecord] {
        snapshot.items.filter { $0.checked && !isHidden($0) }.sorted(by: CheckOff.newestFirst)
    }

    /// Unchecked rows waiting out their check-off window: rendered ticked and struck through, still among the unchecked.
    public func isPendingCheck(_ id: UUID) -> Bool {
        pendingActions.contains { if case .check(let pendingId, _) = $0 { pendingId == id } else { false } }
    }

    /// AC4: everything is checked.
    public var allChecked: Bool {
        CheckOff.allChecked(
            unchecked: unchecked.count, pending: pendingActions.filter { if case .check = $0 { true } else { false } }.count,
            checked: checked.count
        )
    }

    /// Follows the local rows until the view goes away or the household's database closes.
    public func start() async {
        for await snapshot in await database.observeListDetail(householdId: householdId, listId: listId) {
            self.snapshot = snapshot
            loaded = true
            // Deleted on another device while open: close the sheet rather than save into a missing row.
            if case .edit(let item)? = editing, !snapshot.items.contains(where: { $0.id == item.id }) { editing = nil }
        }
        // A stream that ended at once (database not open): nothing cached to show.
        loaded = true
    }

    /// Adds `name` as typed. The row exists locally and in the outbox before this returns (AC4).
    public func add(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        await create(ItemFields(name: trimmed))
    }

    /// Adds a row with all four fields, the same shape as a quick add (AC1). Blank quantity, unit and note are null.
    @discardableResult
    public func create(_ fields: ItemFields) async -> Bool {
        let stamp = now().formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
        let row = QuickAdd.row(
            id: makeId(), householdId: householdId, listId: listId, name: fields.name, userId: userId, now: stamp,
            quantity: fields.quantity, unit: fields.unit, note: fields.note
        )
        do {
            try await database.upsert(table: "list_item", row: row, now: now())
            writeFailed = false
        } catch {
            writeFailed = true
            return false
        }
        query = ""
        await kick()
        return true
    }

    /// Writes only the changed columns; nothing at all when nothing changed (AC3, AC4).
    @discardableResult
    public func save(_ item: ListItemRecord, _ fields: ItemFields) async -> Bool {
        let changes = ItemSheetLogic.changes(before: item, after: fields)
        guard !changes.fields.isEmpty else { return true }
        do {
            try await database.patch(table: "list_item", id: item.id, fields: changes.fields, localOnly: changes.localOnly, now: now())
            writeFailed = false
        } catch {
            writeFailed = true
            return false
        }
        await kick()
        return true
    }

    public func openNew(prefill: String) { editing = .new(prefill: prefill) }
    public func open(_ item: ListItemRecord) { editing = .edit(item) }
    public func closeSheet() { editing = nil }

    /// The row leaves the list now and is written after the undo window, unless Undo comes first.
    public func delete(_ item: ListItemRecord) {
        editing = nil
        // A delete supersedes a check-off still waiting on the same row.
        dropPending { if case .check(let id, _) = $0 { id == item.id } else { false } }
        begin(.delete(id: item.id, name: item.name))
    }

    /// Ticks the row in place and writes the check-off after the undo window, unless Undo comes first (AC1-AC3).
    public func check(_ item: ListItemRecord) {
        guard !item.checked, !isPendingCheck(item.id) else { return }
        begin(.check(id: item.id, name: item.name))
    }

    /// Unchecking is its own undo: written at once, no toast.
    public func uncheck(_ item: ListItemRecord) async {
        await write { try await database.patch(table: "list_item", id: item.id, fields: CheckOff.uncheckFields(), now: now()) }
    }

    /// Hides the rows that are checked now and soft-deletes them after the window (AC5). A row checked during the
    /// window is not swept in.
    public func clearChecked() {
        let ids = checked.map(\.id)
        guard !ids.isEmpty else { return }
        begin(.clear(id: makeId(), itemIds: ids))
    }

    /// Undo inside the window: nothing was written.
    public func undo(_ id: UUID) {
        timers.removeValue(forKey: id)?.cancel()
        pendingActions.removeAll { $0.id == id }
    }

    /// Leaving the screen (or the app) writes what is still waiting rather than dropping it.
    public func flush() async {
        let waiting = pendingActions
        for action in waiting { timers.removeValue(forKey: action.id)?.cancel() }
        for action in waiting { await commit(action) }
        let done = Set(waiting.map(\.id))
        pendingActions.removeAll { done.contains($0.id) }
    }

    private func begin(_ action: PendingAction) {
        undo(action.id)
        pendingActions.insert(action, at: 0)
        let window = undoWindow
        timers[action.id] = Task { [weak self] in
            try? await Task.sleep(for: window)
            guard !Task.isCancelled, let self else { return }
            self.timers[action.id] = nil
            guard self.pendingActions.contains(where: { $0.id == action.id }) else { return }
            await self.commit(action)
            // A check stays pending until its write has landed, so the row never flickers back to unchecked.
            self.pendingActions.removeAll { $0.id == action.id }
        }
    }

    private func dropPending(where match: (PendingAction) -> Bool) {
        for action in pendingActions where match(action) { timers.removeValue(forKey: action.id)?.cancel() }
        pendingActions.removeAll(where: match)
    }

    private func isHidden(_ item: ListItemRecord) -> Bool { pendingActions.contains { $0.hiddenIds.contains(item.id) } }

    private func commit(_ action: PendingAction) async {
        switch action {
        case .delete(let id, _):
            await write { try await database.remove(table: "list_item", id: id, now: now()) }
        case .check(let id, _):
            // Re-read: a row deleted here or checked on another device meanwhile must not be overwritten.
            guard let latest = snapshot.items.first(where: { $0.id == id }), !latest.checked else { return }
            let stamp = now().formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
            await write { try await database.patch(table: "list_item", id: id, fields: CheckOff.checkFields(userId: userId, now: stamp), now: now()) }
        case .clear(_, let ids):
            var wrote = false
            for id in ids {
                if await write({ try await database.remove(table: "list_item", id: id, now: now()) }, kick: false) { wrote = true }
            }
            if wrote { await kick() }
        }
    }

    /// One local write, then a sync kick. `false` when it could not be stored.
    @discardableResult
    private func write(_ body: () async throws -> Void, kick shouldKick: Bool = true) async -> Bool {
        do {
            try await body()
            writeFailed = false
        } catch {
            writeFailed = true
            return false
        }
        if shouldKick { await kick() }
        return true
    }

    /// Tapping a suggestion adds its label, so the row shows the household's own casing (AC2).
    public func pick(_ suggestion: Suggestion) async { await add(suggestion.label) }

    /// Return adds exactly what was typed; a suggestion is only ever added by tapping it (AC3).
    public func submit() async { await add(query) }
}
