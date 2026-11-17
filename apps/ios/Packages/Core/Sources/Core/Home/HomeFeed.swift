import Contract
import Foundation

/// The module flags the Home screen reads.
public struct HomeModules: Sendable, Equatable {
    public var shopping: Bool
    public var pantry: Bool
    public var maintenance: Bool

    public init(shopping: Bool, pantry: Bool, maintenance: Bool) {
        self.shopping = shopping
        self.pantry = pantry
        self.maintenance = maintenance
    }

    public init(_ household: LandingHousehold) {
        self.init(shopping: household.moduleShopping, pantry: household.modulePantry, maintenance: household.moduleMaintenance)
    }

    public var allOff: Bool { !shopping && !pantry && !maintenance }
}

public enum HomeState: Equatable, Sendable {
    /// Every module is off: the settings prompt shows and the feed still renders under it.
    case allModulesOff
    case empty
    case populated
}

/// DESIGN B4 / CORE-08. Web twin: `web/src/core/HomeFeed.ts`.
public enum HomeFeed {
    public static let days = 30
    public static let limit = 20

    /// The newest 20 live rows of the last 30 days. Same-instant rows keep a stable order (id, descending).
    public static func window(_ rows: [Activity], now: Date) -> [Activity] {
        let from = windowStart(now: now)
        let kept = rows
            .filter { $0.deletedAt == nil && $0.createdAt >= from }
            .sorted { $0.createdAt != $1.createdAt ? $0.createdAt > $1.createdAt : $0.id.uuidString > $1.id.uuidString }
        return Array(kept.prefix(limit))
    }

    public static func windowStart(now: Date) -> Date {
        now.addingTimeInterval(-Double(days) * 86_400)
    }

    /// `feed` is the windowed list. `modules == nil` (flags unknown, e.g. offline before any household was read)
    /// never shows the all-off prompt.
    public static func state(feed: [Activity], modules: HomeModules?) -> HomeState {
        if modules?.allOff == true { return .allModulesOff }
        return feed.isEmpty ? .empty : .populated
    }
}
