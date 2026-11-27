import Foundation
import GRDB

/// A `list_item` row as the list detail, the item sheet and the autocomplete read it (SHOP-02, SHOP-03).
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
    public var quantity: Double?
    public var unit: String?
    public var note: String?
    /// Millisecond UTC text of the check-off, or `nil` while unchecked (or for a row from before it was stamped).
    public var checkedAt: String?

    public init(
        id: UUID, listId: UUID? = nil, name: String, nameKey: String? = nil, checked: Bool = false,
        deletedAt: String? = nil, createdAt: String? = nil, quantity: Double? = nil, unit: String? = nil, note: String? = nil,
        checkedAt: String? = nil
    ) {
        self.checkedAt = checkedAt
        self.quantity = quantity
        self.unit = unit
        self.note = note
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
    /// The household's live stores, for the store picker.
    public var stores: [StoreRecord]
    /// This list's live items.
    public var items: [ListItemRecord]
    /// The household's item history: live rows plus rows that were checked at some point, on every list.
    public var history: [ListItemRecord]

    public init(
        list: ListRecord? = nil, storeName: String? = nil, stores: [StoreRecord] = [], items: [ListItemRecord] = [],
        history: [ListItemRecord] = []
    ) {
        self.list = list
        self.storeName = storeName
        self.stores = stores
        self.items = items
        self.history = history
    }
}

extension HouseholdDatabase {
    /// The stored state of one item, read straight from the database (not the observed snapshot, which can lag a sync
    /// apply). `nil` when there is no such row.
    public func listItemState(id: UUID) throws -> (checked: Bool, deletedAt: String?)? {
        guard let pool else { throw HouseholdDatabaseError.notOpen }
        return try pool.read { db in
            try Row.fetchOne(db, sql: "SELECT checked, deleted_at FROM list_item WHERE id = ?", arguments: [id.uuidString.lowercased()])
                .map { row in
                    let checked: Bool? = row["checked"]
                    return (checked ?? false, row["deleted_at"])
                }
        }
    }

    /// The stored name and `deleted_at` of one item (SHOP-10: a queued barcode lookup renames it only while it still bears
    /// the code and is not deleted). `nil` when there is no such row.
    public func listItemIdentity(id: UUID) throws -> (name: String, deletedAt: String?)? {
        guard let pool else { throw HouseholdDatabaseError.notOpen }
        return try pool.read { db in
            try Row.fetchOne(db, sql: "SELECT name, deleted_at FROM list_item WHERE id = ?", arguments: [id.uuidString.lowercased()])
                .map { row in
                    let name: String? = row["name"]
                    return (name ?? "", row["deleted_at"])
                }
        }
    }

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
            let stores = try Row.fetchAll(
                db, sql: "SELECT id, name FROM store WHERE household_id = ? AND deleted_at IS NULL", arguments: [key]
            ).compactMap { row -> StoreRecord? in
                UUID(uuidString: row["id"]).map { StoreRecord(id: $0, name: row["name"]) }
            }
            let storeName = list?.storeId.flatMap { id in stores.first { $0.id == id }?.name }
            let rows = try Row.fetchAll(
                db,
                sql: """
                SELECT id, list_id, name, name_key, quantity, unit, note, checked, checked_at, deleted_at, created_at FROM list_item
                WHERE household_id = ? AND (deleted_at IS NULL OR checked = 1)
                """,
                arguments: [key]
            ).compactMap { row -> ListItemRecord? in
                guard let id = UUID(uuidString: row["id"]) else { return nil }
                let list: String? = row["list_id"]
                let checked: Bool? = row["checked"]
                return ListItemRecord(
                    id: id, listId: list.flatMap { UUID(uuidString: $0) }, name: row["name"], nameKey: row["name_key"],
                    checked: checked ?? false, deletedAt: row["deleted_at"], createdAt: row["created_at"],
                    quantity: row["quantity"], unit: row["unit"], note: row["note"], checkedAt: row["checked_at"]
                )
            }
            let items = rows.filter { $0.listId == listId && $0.deletedAt == nil }
            return ListDetailSnapshot(list: list, storeName: storeName, stores: stores, items: items, history: rows)
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
