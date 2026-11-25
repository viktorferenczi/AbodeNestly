import Foundation
import GRDB

/// A `list_item` row as the list detail and the autocomplete read it (SHOP-02). Name, key and state only; quantity,
/// unit and note arrive with SHOP-03.
public struct ListItemRecord: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var listId: UUID?
    public var name: String
    /// The server's `name_key`, or the client's own until the next pull; `nil` for a row that never had one.
    public var nameKey: String?
    public var checked: Bool
    /// Millisecond UTC text, or `nil` while the item is live.
    public var deletedAt: String?
    /// Millisecond UTC text, or `nil` for a row from before the server stamped it.
    public var createdAt: String?

    public init(
        id: UUID, listId: UUID? = nil, name: String, nameKey: String? = nil, checked: Bool = false,
        deletedAt: String? = nil, createdAt: String? = nil
    ) {
        self.id = id
        self.listId = listId
        self.name = name
        self.nameKey = nameKey
        self.checked = checked
        self.deletedAt = deletedAt
        self.createdAt = createdAt
    }
}

/// Everything the list detail shows, in one read so the tables never disagree.
public struct ListDetailSnapshot: Sendable, Equatable {
    /// `nil` when the list row is missing or deleted.
    public var list: ListRecord?
    /// The list's store, when it points to a live store.
    public var storeName: String?
    /// This list's live items.
    public var items: [ListItemRecord]
    /// The household's item history: live rows plus rows that were checked at some point, on every list.
    public var history: [ListItemRecord]

    public init(list: ListRecord? = nil, storeName: String? = nil, items: [ListItemRecord] = [], history: [ListItemRecord] = []) {
        self.list = list
        self.storeName = storeName
        self.items = items
        self.history = history
    }
}

extension HouseholdDatabase {
    /// One list with its store name, its live items and the household's item history, re-emitted whenever any of them
    /// changes. Finishes at once when the household is not open.
    public func observeListDetail(householdId: UUID, listId: UUID) -> AsyncStream<ListDetailSnapshot> {
        guard let pool, handle?.householdId == householdId else { return AsyncStream { $0.finish() } }
        let key = householdId.uuidString.lowercased()
        let listKey = listId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db -> ListDetailSnapshot in
            let listRow = try Row.fetchOne(
                db,
                sql: """
                SELECT id, name, store_id, is_template, archived_at, created_at FROM shopping_list
                WHERE household_id = ? AND id = ? AND deleted_at IS NULL
                """,
                arguments: [key, listKey]
            )
            let list = listRow.flatMap { row -> ListRecord? in
                guard let id = UUID(uuidString: row["id"]) else { return nil }
                let storeId: String? = row["store_id"]
                let template: Bool? = row["is_template"]
                return ListRecord(
                    id: id, name: row["name"], storeId: storeId.flatMap { UUID(uuidString: $0) }, isTemplate: template ?? false,
                    archivedAt: row["archived_at"], createdAt: row["created_at"]
                )
            }
            var storeName: String?
            if let storeId = list?.storeId {
                storeName = try String.fetchOne(
                    db, sql: "SELECT name FROM store WHERE household_id = ? AND id = ? AND deleted_at IS NULL",
                    arguments: [key, storeId.uuidString.lowercased()]
                )
            }
            let rows = try Row.fetchAll(
                db,
                sql: """
                SELECT id, list_id, name, name_key, checked, deleted_at, created_at FROM list_item
                WHERE household_id = ? AND (deleted_at IS NULL OR checked = 1)
                """,
                arguments: [key]
            ).compactMap { row -> ListItemRecord? in
                guard let id = UUID(uuidString: row["id"]) else { return nil }
                let list: String? = row["list_id"]
                let checked: Bool? = row["checked"]
                return ListItemRecord(
                    id: id, listId: list.flatMap { UUID(uuidString: $0) }, name: row["name"], nameKey: row["name_key"],
                    checked: checked ?? false, deletedAt: row["deleted_at"], createdAt: row["created_at"]
                )
            }
            let items = rows.filter { $0.listId == listId && $0.deletedAt == nil }
            return ListDetailSnapshot(list: list, storeName: storeName, items: items, history: rows)
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await value in observation.values(in: pool) { continuation.yield(value) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last value.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
