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
        makeId: @escaping @MainActor () -> UUID = { UUID() }
    ) {
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
        snapshot.items.filter { !$0.checked }.sorted { ($0.createdAt ?? "", $0.id.uuidString) < ($1.createdAt ?? "", $1.id.uuidString) }
    }

    /// Newest first.
    public var checked: [ListItemRecord] {
        snapshot.items.filter(\.checked).sorted { ($0.createdAt ?? "", $0.id.uuidString) > ($1.createdAt ?? "", $1.id.uuidString) }
    }

    /// Follows the local rows until the view goes away or the household's database closes.
    public func start() async {
        for await snapshot in await database.observeListDetail(householdId: householdId, listId: listId) {
            self.snapshot = snapshot
            loaded = true
        }
        // A stream that ended at once (database not open): nothing cached to show.
        loaded = true
    }

    /// Adds `name` as typed. The row exists locally and in the outbox before this returns (AC4).
    public func add(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let stamp = now().formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
        let row = QuickAdd.row(id: makeId(), householdId: householdId, listId: listId, name: trimmed, userId: userId, now: stamp)
        do {
            try await database.upsert(table: "list_item", row: row, now: now())
            writeFailed = false
        } catch {
            writeFailed = true
            return
        }
        query = ""
        await kick()
    }

    /// Tapping a suggestion adds its label, so the row shows the household's own casing (AC2).
    public func pick(_ suggestion: Suggestion) async { await add(suggestion.label) }

    /// Return adds exactly what was typed; a suggestion is only ever added by tapping it (AC3).
    public func submit() async { await add(query) }
}
