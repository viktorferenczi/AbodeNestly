import Contract
import Foundation
import Sync

/// The modules search covers, in section order (DESIGN B8).
public enum SearchModule: String, Sendable, Equatable, CaseIterable {
    case shopping, pantry, maintenance

    /// Local tables whose names belong to the module.
    public var tables: [String] {
        switch self {
        case .shopping: ["list_item", "store"]
        case .pantry: ["pantry_item", "location", "recipe"]
        case .maintenance: ["asset", "maint_task", "contractor"]
        }
    }

    public var title: String {
        switch self {
        case .shopping: "Shopping"
        case .pantry: "Pantry"
        case .maintenance: "Maintenance"
        }
    }

    public var tab: AppTab {
        switch self {
        case .shopping: .shopping
        case .pantry: .pantry
        case .maintenance: .maintenance
        }
    }

    public static func of(table: String) -> SearchModule? {
        allCases.first { $0.tables.contains(table) }
    }

    /// The row kind shown beside the name.
    public static func kindLabel(table: String) -> String {
        switch table {
        case "list_item": "List item"
        case "store": "Store"
        case "pantry_item": "Pantry item"
        case "location": "Location"
        case "recipe": "Recipe"
        case "asset": "Asset"
        case "maint_task": "Task"
        case "contractor": "Contractor"
        default: table
        }
    }
}

/// Where a result row leads. The entity screens arrive with the module stories; until then the shell only switches
/// to the module's tab (DESIGN B8 deviation).
public struct SearchTarget: Sendable, Equatable {
    public let module: SearchModule
    public let table: String
    public let id: UUID
    public let listId: UUID?

    public init(module: SearchModule, table: String, id: UUID, listId: UUID? = nil) {
        self.module = module
        self.table = table
        self.id = id
        self.listId = listId
    }
}

public struct SearchResult: Sendable, Equatable, Identifiable {
    public let target: SearchTarget
    public let name: String
    public let kind: String
    public var id: String { "\(target.table):\(target.id.uuidString)" }
}

public struct SearchSection: Sendable, Equatable, Identifiable {
    public let module: SearchModule
    public let results: [SearchResult]
    public var id: SearchModule { module }
}

public enum SearchState: Sendable, Equatable {
    case emptyQuery, noResults, results
}

/// DESIGN B8 / CORE-09. Web twin: `web/src/core/search.ts`.
public enum Search {
    public static let debounce: Duration = .milliseconds(150)

    /// The flags search filters by. The open household's own when known; otherwise (offline `.home`, memberships
    /// unreadable) the ones cached with that household id. `nil` when neither exists, so Search is not offered.
    public static func modules(householdId: UUID?, household: LandingHousehold?, cached: [HouseholdWithRole]) -> HomeModules? {
        if let household { return HomeModules(household) }
        guard let householdId, let entry = cached.first(where: { $0.household.id == householdId })?.household else { return nil }
        return HomeModules(shopping: entry.moduleShopping, pantry: entry.modulePantry, maintenance: entry.moduleMaintenance)
    }

    public static func tables(for modules: Set<SearchModule>) -> [String] {
        SearchModule.allCases.filter(modules.contains).flatMap(\.tables)
    }

    /// Trimmed, case-insensitive substring match on the name. A blank query matches nothing; deleted rows and rows of
    /// a module that is off are dropped (AC4 holds even when the local database still has them). Sections follow
    /// Shopping, Pantry, Maintenance; inside one, names that start with the query come first, then A–Z.
    public static func group(_ rows: [SearchRow], query: String, modules: Set<SearchModule>) -> [SearchSection] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return [] }
        var byModule: [SearchModule: [SearchResult]] = [:]
        for row in rows where row.deletedAt == nil {
            guard let module = SearchModule.of(table: row.table), modules.contains(module),
                row.name.lowercased().contains(needle)
            else { continue }
            let target = SearchTarget(module: module, table: row.table, id: row.id, listId: row.listId)
            byModule[module, default: []].append(
                SearchResult(target: target, name: row.name, kind: SearchModule.kindLabel(table: row.table))
            )
        }
        return SearchModule.allCases.compactMap { module in
            guard let results = byModule[module], !results.isEmpty else { return nil }
            let sorted = results.sorted { a, b in
                let aPrefix = a.name.lowercased().hasPrefix(needle), bPrefix = b.name.lowercased().hasPrefix(needle)
                if aPrefix != bPrefix { return aPrefix }
                let order = a.name.localizedCaseInsensitiveCompare(b.name)
                return order != .orderedSame ? order == .orderedAscending : a.id < b.id
            }
            return SearchSection(module: module, results: sorted)
        }
    }

    /// `query` is the debounced one.
    public static func state(query: String, sections: [SearchSection]) -> SearchState {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .emptyQuery }
        return sections.isEmpty ? .noResults : .results
    }
}

/// The last searches the person opened a result for, per household, newest first. Modelled on `LastHouseholdStore`.
public struct RecentSearchStore: Sendable {
    public static let limit = 8
    private let suiteName: String?
    private let key: String

    public init(householdId: UUID, suiteName: String? = nil) {
        self.suiteName = suiteName
        key = "abodenestly.search.\(householdId.uuidString.lowercased())"
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    public func read() -> [String] {
        defaults.stringArray(forKey: key) ?? []
    }

    /// Moves a repeat (any case) to the front instead of listing it twice.
    @discardableResult
    public func record(_ query: String) -> [String] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return read() }
        var recents = read().filter { $0.caseInsensitiveCompare(trimmed) != .orderedSame }
        recents.insert(trimmed, at: 0)
        recents = Array(recents.prefix(Self.limit))
        defaults.set(recents, forKey: key)
        return recents
    }
}
