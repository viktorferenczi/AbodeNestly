import Contract
import Foundation
import Sync

/// DESIGN C1/C2 template logic (SHOP-11), kept apart from the views so it is table-testable. Web twin:
/// `web/src/shopping/Templates.ts`.
public enum Templates {
    /// Lists a template can be added to: active (not archived; deleted rows are never read) and not a template, oldest
    /// first like C1.
    public static func targets(lists: [ListRecord]) -> [ListRecord] {
        var active: [ListRecord] = []
        for list in lists where list.archivedAt == nil && !list.isTemplate { active.append(list) }
        active.sort { left, right in
            let a: String = left.createdAt ?? ""
            let b: String = right.createdAt ?? ""
            if a != b { return a < b }
            return left.id.uuidString < right.id.uuidString
        }
        return active
    }

    /// The template's live items in the order C2 shows them: unchecked oldest first, then checked, newest check first.
    static func displayOrder(_ items: [ListItemRecord], templateId: UUID) -> [ListItemRecord] {
        var unchecked: [ListItemRecord] = []
        var checked: [ListItemRecord] = []
        for item in items where item.listId == templateId && item.deletedAt == nil {
            if item.checked { checked.append(item) } else { unchecked.append(item) }
        }
        unchecked.sort { left, right in
            let a: String = left.createdAt ?? ""
            let b: String = right.createdAt ?? ""
            if a != b { return a < b }
            return left.id.uuidString < right.id.uuidString
        }
        checked.sort(by: CheckOff.newestFirst)
        return unchecked + checked
    }

    /// One new `list_item` row per live template item: fresh id, name, quantity, unit, note and barcode copied, and
    /// everything else a manual add's defaults (unchecked, no position, no pantry or task link, `created_by` the current
    /// user). Each row's `created_at` is one millisecond after the previous one, so C2's oldest-first sort keeps the
    /// template's order locally; `created_at` is server-owned, so the order collapses after the next pull.
    public static func copies(
        of items: [ListItemRecord], templateId: UUID, targetListId: UUID, householdId: UUID, userId: UUID?, now: Date,
        uuid: () -> UUID
    ) -> [[String: JSONValue]] {
        let ordered = displayOrder(items, templateId: templateId)
        let startMs = Int((now.timeIntervalSince1970 * 1000).rounded(.down))
        var rows: [[String: JSONValue]] = []
        for (index, source) in ordered.enumerated() {
            // Half a millisecond in, so the formatter cannot land on the millisecond before.
            let moment = Date(timeIntervalSince1970: (Double(startMs + index) + 0.5) / 1000)
            let stamp: String = moment.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
            var row: [String: JSONValue] = QuickAdd.row(
                id: uuid(), householdId: householdId, listId: targetListId, name: source.name, userId: userId, now: stamp,
                quantity: source.quantity, unit: source.unit, note: source.note, barcode: source.barcode
            )
            // Copy the name as stored (QuickAdd.row trims it).
            row["name"] = .string(source.name)
            rows.append(row)
        }
        return rows
    }

    /// Copies every live item of `templateId` onto `targetListId` in one transaction and returns how many. Nothing is
    /// written for an empty template. The caller kicks sync once afterwards, and only when the count is above 0.
    @MainActor
    public static func addAllToList(
        database: HouseholdDatabase, householdId: UUID, templateId: UUID, targetListId: UUID, userId: UUID?, now: Date,
        uuid: () -> UUID
    ) async throws -> Int {
        let items = try await database.liveListItems(listId: templateId)
        let rows = copies(
            of: items, templateId: templateId, targetListId: targetListId, householdId: householdId, userId: userId,
            now: now, uuid: uuid
        )
        guard !rows.isEmpty else { return 0 }
        try await database.upsertAll(table: "list_item", rows: rows, now: now)
        return rows.count
    }
}

/// What "Add all to list" did.
public enum AddAllResult: Equatable, Sendable {
    case added(count: Int, listName: String)
    case empty
    case failed

    /// The status line for a result; a failure is shown in the sheet instead.
    public var message: String? {
        switch self {
        case .added(let count, let listName): "Added \(count) \(count == 1 ? "item" : "items") to \(listName)"
        case .empty: "Nothing to add: this template is empty"
        case .failed: nil
        }
    }
}

/// An entry of the C2 "List actions" menu.
public enum ListAction: Hashable, Sendable {
    /// SHOP-13: opens the share sheet (C8).
    case share
    case addAll
    case clearChecked
}

extension Templates {
    /// The List actions menu is always there: "Share" first (SHOP-13, templates too), then on a template "Add all to list"
    /// (SHOP-11); "Clear checked" only when something is checked; a normal list never offers Add all.
    public static func actions(isTemplate: Bool, hasChecked: Bool) -> [ListAction] {
        var actions: [ListAction] = [.share]
        if isTemplate { actions.append(.addAll) }
        if hasChecked { actions.append(.clearChecked) }
        return actions
    }
}
