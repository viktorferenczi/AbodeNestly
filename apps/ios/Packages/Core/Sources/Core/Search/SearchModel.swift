import Foundation
import Observation
import Sync

/// DESIGN B8. Reads the household's local tables only, so it works offline (AC5). Typing restarts a 150 ms timer;
/// when it fires the trimmed query is searched. `sleep` is injected so tests drive the clock.
@Observable
@MainActor
public final class SearchModel {
    /// What the field shows. Every edit restarts the debounce.
    public var query: String = "" {
        didSet { if query != oldValue { restartDebounce() } }
    }
    /// The flags of the household; `nil` when unknown, which searches nothing.
    public var modules: HomeModules? {
        didSet { if modules != oldValue { startObserving() } }
    }
    public private(set) var recents: [String]
    /// The trimmed query the results belong to (changes only after the debounce).
    public private(set) var debouncedQuery = ""
    /// `true` from a non-blank debounced query until its first rows arrive, so "no results" does not flash.
    public private(set) var isSearching = false

    private var rows: [SearchRow] = []
    @ObservationIgnored private let householdId: UUID
    @ObservationIgnored private let database: HouseholdDatabase
    @ObservationIgnored private let store: RecentSearchStore
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    @ObservationIgnored private var observeTask: Task<Void, Never>?
    @ObservationIgnored private var generation = 0

    public init(
        householdId: UUID, modules: HomeModules?, database: HouseholdDatabase, store: RecentSearchStore,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.householdId = householdId
        self.modules = modules
        self.database = database
        self.store = store
        self.sleep = sleep
        recents = store.read()
    }

    private var enabled: Set<SearchModule> {
        guard let modules else { return [] }
        var set: Set<SearchModule> = []
        if modules.shopping { set.insert(.shopping) }
        if modules.pantry { set.insert(.pantry) }
        if modules.maintenance { set.insert(.maintenance) }
        return set
    }

    /// Filtered by the flags again here, whatever the database returned (AC4).
    public var sections: [SearchSection] {
        Search.group(rows, query: debouncedQuery, modules: enabled)
    }

    public var state: SearchState { Search.state(query: debouncedQuery, sections: sections) }

    private func restartDebounce() {
        debounceTask?.cancel()
        let sleep = sleep
        debounceTask = Task { [weak self] in
            do { try await sleep(Search.debounce) } catch { return }
            guard !Task.isCancelled else { return }
            self?.applyQuery()
        }
    }

    private func applyQuery() {
        debouncedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        startObserving()
    }

    private func startObserving() {
        observeTask?.cancel()
        generation += 1
        let current = generation
        guard !debouncedQuery.isEmpty else {
            rows = []
            isSearching = false
            return
        }
        let tables = Search.tables(for: enabled)
        guard !tables.isEmpty else {
            rows = []
            isSearching = false
            return
        }
        isSearching = true
        let (householdId, database, text) = (householdId, database, debouncedQuery)
        observeTask = Task { [weak self] in
            for await found in await database.observeSearch(householdId: householdId, query: text, tables: tables) {
                guard let self, current == self.generation else { return }
                self.rows = found
                self.isSearching = false
            }
            // A stream that ended at once (database not open): nothing to show, and nothing more is coming.
            if let self, current == self.generation { self.isSearching = false }
        }
    }

    /// A recent search was tapped: fill the field and search at once, without the debounce.
    public func choose(recent: String) {
        query = recent
        debounceTask?.cancel()
        applyQuery()
    }

    /// A result row was tapped.
    public func open(_ result: SearchResult) {
        recents = store.record(debouncedQuery)
    }

    /// Return on the keyboard: remember the search when it found something.
    public func submit() {
        guard !sections.isEmpty else { return }
        recents = store.record(debouncedQuery)
    }
}
