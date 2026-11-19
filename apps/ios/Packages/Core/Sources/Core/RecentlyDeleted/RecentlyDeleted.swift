import Foundation
import Sync

/// One restorable row on the screen.
public struct RecentlyDeletedItem: Sendable, Equatable, Identifiable {
    public let table: String
    public let rowId: UUID
    public let name: String
    public let daysLeft: Int
    public var id: String { "\(table):\(rowId.uuidString)" }
}

/// One segment: a module and its rows (possibly none).
public struct RecentlyDeletedSection: Sendable, Equatable, Identifiable {
    public let module: SearchModule
    public let items: [RecentlyDeletedItem]
    public var id: SearchModule { module }
}

public enum RecentlyDeletedState: Sendable, Equatable {
    case empty, populated
}

/// DESIGN B11 / CORE-10. Web twin: `web/src/core/RecentlyDeleted.ts`.
public enum RecentlyDeleted {
    /// The purge job (CORE-10-db) deletes rows 30 days after `deleted_at`.
    public static let retentionDays = 30

    /// 30 minus whole days since deletion. At 0 or less the server has purged the row (and sent no tombstone), so
    /// the screen must not list it (AC4).
    public static func daysLeft(deletedAt: Date, now: Date) -> Int {
        retentionDays - Int((now.timeIntervalSince(deletedAt) / 86_400).rounded(.down))
    }

    /// One section per enabled module in Shopping, Pantry, Maintenance order; rows of a module that is off, or
    /// with no days left, are dropped; newest deleted first.
    public static func sections(rows: [DeletedRow], modules: Set<SearchModule>, now: Date) -> [RecentlyDeletedSection] {
        SearchModule.allCases.filter(modules.contains).map { module in
            let items = rows
                .filter { SearchModule.of(table: $0.table) == module && daysLeft(deletedAt: $0.deletedAt, now: now) > 0 }
                .sorted { a, b in a.deletedAt != b.deletedAt ? a.deletedAt > b.deletedAt : a.id < b.id }
                .map {
                    RecentlyDeletedItem(
                        table: $0.table, rowId: $0.id, name: $0.name, daysLeft: daysLeft(deletedAt: $0.deletedAt, now: now)
                    )
                }
            return RecentlyDeletedSection(module: module, items: items)
        }
    }

    public static func state(sections: [RecentlyDeletedSection]) -> RecentlyDeletedState {
        sections.allSatisfy(\.items.isEmpty) ? .empty : .populated
    }

    public static func daysLeftText(_ days: Int) -> String {
        days == 1 ? "1 day left" : "\(days) days left"
    }
}
