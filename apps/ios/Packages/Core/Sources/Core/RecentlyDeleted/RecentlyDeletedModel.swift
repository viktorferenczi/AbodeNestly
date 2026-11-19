import Foundation
import Observation
import Sync

/// DESIGN B11. Reads the household's local tables only, so it works offline. Restore writes locally, then kicks
/// sync. `now` is injected so tests fix the clock.
@Observable
@MainActor
public final class RecentlyDeletedModel {
    /// The flags of the household; `nil` when unknown, which lists nothing.
    public var modules: HomeModules? {
        didSet { if modules != oldValue { startObserving() } }
    }
    /// The person's pick; falls back to the first enabled module when that one is off.
    public var selected: SearchModule?
    /// `false` until the first read, so the empty state does not flash.
    public private(set) var loaded = false
    /// Set when a restore could not be written.
    public private(set) var restoreFailed = false

    private var rows: [DeletedRow] = []
    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let kick: @Sendable () async -> Void
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private var observeTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0

    public init(
        householdId: UUID, modules: HomeModules?, database: HouseholdDatabase,
        kick: @escaping @Sendable () async -> Void, now: @escaping @MainActor () -> Date = { Date() }
    ) {
        self.householdId = householdId
        self.modules = modules
        self.database = database
        self.kick = kick
        self.now = now
        startObserving()
    }

    private var enabled: Set<SearchModule> {
        guard let modules else { return [] }
        var set: Set<SearchModule> = []
        if modules.shopping { set.insert(.shopping) }
        if modules.pantry { set.insert(.pantry) }
        if modules.maintenance { set.insert(.maintenance) }
        return set
    }

    /// Filtered by the flags again here, whatever the database returned (AC5).
    public var sections: [RecentlyDeletedSection] {
        RecentlyDeleted.sections(rows: rows, modules: enabled, now: now())
    }

    public var state: RecentlyDeletedState { RecentlyDeleted.state(sections: sections) }

    public var selectedModule: SearchModule? {
        let modules = sections.map(\.module)
        if let selected, modules.contains(selected) { return selected }
        return modules.first
    }

    public var selectedItems: [RecentlyDeletedItem] {
        sections.first { $0.module == selectedModule }?.items ?? []
    }

    private func startObserving() {
        observeTask?.cancel()
        generation += 1
        let current = generation
        let tables = Search.tables(for: enabled)
        guard !tables.isEmpty else {
            rows = []
            loaded = true
            return
        }
        let (householdId, database) = (householdId, database)
        observeTask = Task { [weak self] in
            for await found in await database.observeDeleted(householdId: householdId, tables: tables) {
                guard let self, current == self.generation else { return }
                self.rows = found
                self.loaded = true
            }
            // A stream that ended at once (database not open): nothing to show, and nothing more is coming.
            if let self, current == self.generation { self.loaded = true }
        }
    }

    /// Restore tapped: one local write (row + outbox), then ask sync to push it.
    public func restore(_ item: RecentlyDeletedItem) async {
        do {
            try await database.restore(householdId: householdId, table: item.table, id: item.rowId, now: now())
            restoreFailed = false
        } catch {
            restoreFailed = true
            return
        }
        await kick()
    }
}
