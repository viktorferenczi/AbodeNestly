import Contract
import Foundation
import Sync

/// One autocomplete line: `label` is what the row shows, `key` the `name_key` it matched on.
public struct Suggestion: Equatable, Identifiable, Sendable {
    public var key: String
    public var label: String
    public var id: String { key }

    public init(key: String, label: String) {
        self.key = key
        self.label = label
    }
}

/// SHOP-02 logic, kept apart from the view so it is table-testable. Web twin: `web/src/shopping/QuickAdd.ts`.
public enum QuickAdd {
    public static let suggestionLimit = 5

    private static func key(_ item: ListItemRecord) -> String {
        if let key = item.nameKey, !key.isEmpty { return key }
        return NameKey.make(item.name)
    }

    /// Suggestions for `query`, matched on `name_key`. A name matches when its key, or any word in it, starts with the
    /// query ("mi" finds Milk and Oat milk, not Salami). Rows show the casing of the household's most recent entry.
    /// Names already unchecked on `listId` are left out so a suggestion never makes a duplicate line.
    public static func suggest(
        history: [ListItemRecord], query: String, listId: UUID, limit: Int = suggestionLimit
    ) -> [Suggestion] {
        let q = NameKey.make(query)
        guard !q.isEmpty else { return [] }
        let onList = Set(
            history.filter { $0.listId == listId && $0.deletedAt == nil && !$0.checked }.map(key)
        )
        struct Group {
            var label: String
            var latest: String
            var count: Int
        }
        var groups: [String: Group] = [:]
        for item in history where item.deletedAt == nil || item.checked {
            let k = key(item)
            let created = item.createdAt ?? ""
            if var group = groups[k] {
                group.count += 1
                if created > group.latest {
                    group.label = item.name
                    group.latest = created
                }
                groups[k] = group
            } else {
                groups[k] = Group(label: item.name, latest: created, count: 1)
            }
        }
        struct Ranked {
            var key: String
            var label: String
            var prefix: Bool
            var count: Int
            var latest: String
        }
        var ranked: [Ranked] = []
        for (k, group) in groups where !onList.contains(k) {
            let prefix = k.hasPrefix(q)
            guard prefix || k.split(separator: " ").contains(where: { $0.hasPrefix(q) }) else { continue }
            ranked.append(Ranked(key: k, label: group.label, prefix: prefix, count: group.count, latest: group.latest))
        }
        ranked.sort { a, b in
            if a.prefix != b.prefix { return a.prefix }
            if a.count != b.count { return a.count > b.count }
            if a.latest != b.latest { return a.latest > b.latest }
            return a.key < b.key
        }
        return ranked.prefix(limit).map { Suggestion(key: $0.key, label: $0.label) }
    }

    /// The full `list_item` row for a manual add. The server derives `name_key` itself; the local value only serves
    /// matching until the next pull replaces the row, and `upsert` never queues it.
    public static func row(
        id: UUID, householdId: UUID, listId: UUID, name: String, userId: UUID?, now: String
    ) -> [String: JSONValue] {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return [
            "id": .string(id.uuidString.lowercased()), "household_id": .string(householdId.uuidString.lowercased()),
            "list_id": .string(listId.uuidString.lowercased()), "name": .string(trimmed),
            "name_key": .string(NameKey.make(trimmed)),
            "quantity": .null, "unit": .null, "note": .null, "barcode": .null,
            "checked": .bool(false), "checked_at": .null, "checked_by": .null, "manual_position": .null,
            "source": .string("manual"), "pantry_item_id": .null, "maint_task_id": .null,
            "created_by": userId.map { .string($0.uuidString.lowercased()) } ?? .null,
            "deleted_at": .null, "created_at": .string(now), "updated_at": .string(now), "sync_seq": .int(0),
        ]
    }
}
