import Foundation
import GRDB

/// A `shopping_list` row as the lists overview reads it (SHOP-01). Local on purpose: the overview needs six columns,
/// not the whole contract model.
public struct ListRecord: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var name: String
    public var storeId: UUID?
    public var isTemplate: Bool
    /// Millisecond UTC text, or `nil` while the list is active.
    public var archivedAt: String?
    /// Millisecond UTC text, or `nil` for a row from before the server stamped it.
    public var createdAt: String?

    public init(
        id: UUID, name: String, storeId: UUID? = nil, isTemplate: Bool = false, archivedAt: String? = nil, createdAt: String? = nil
    ) {
        self.id = id
        self.name = name
        self.storeId = storeId
        self.isTemplate = isTemplate
        self.archivedAt = archivedAt
        self.createdAt = createdAt
    }
}

/// A live `store` row: the id and name the store chip and the picker need.
public struct StoreRecord: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var name: String

    public init(id: UUID, name: String) {
        self.id = id
        self.name = name
    }
}

/// How many live items a list has, and how many of them are checked.
public struct ListItemCount: Sendable, Equatable {
    public var checked: Int
    public var all: Int

    public init(checked: Int, all: Int) {
        self.checked = checked
        self.all = all
    }
}

/// Everything the lists overview shows, in one read so the three tables never disagree.
public struct ShoppingListsSnapshot: Sendable, Equatable {
    public var lists: [ListRecord]
    public var stores: [StoreRecord]
    public var counts: [UUID: ListItemCount]

    public init(lists: [ListRecord] = [], stores: [StoreRecord] = [], counts: [UUID: ListItemCount] = [:]) {
        self.lists = lists
        self.stores = stores
        self.counts = counts
    }
}

extension HouseholdDatabase {
    /// The household's live lists, live stores and per-list item counts, re-emitted whenever any of them changes.
    /// Finishes at once when the household is not open.
    public func observeShoppingLists(householdId: UUID) -> AsyncStream<ShoppingListsSnapshot> {
        guard let pool, handle?.householdId == householdId else { return AsyncStream { $0.finish() } }
        let key = householdId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db -> ShoppingListsSnapshot in
            let lists = try Row.fetchAll(
                db,
                sql: """
                SELECT id, name, store_id, is_template, archived_at, created_at FROM shopping_list
                WHERE household_id = ? AND deleted_at IS NULL
                """,
                arguments: [key]
            ).compactMap { row -> ListRecord? in
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
            var counts: [UUID: ListItemCount] = [:]
            for row in try Row.fetchAll(
                db,
                sql: """
                SELECT list_id, COALESCE(SUM(checked), 0) AS checked, COUNT(*) AS total FROM list_item
                WHERE household_id = ? AND deleted_at IS NULL AND list_id IS NOT NULL GROUP BY list_id
                """,
                arguments: [key]
            ) {
                if let id = UUID(uuidString: row["list_id"]) { counts[id] = ListItemCount(checked: row["checked"], all: row["total"]) }
            }
            return ShoppingListsSnapshot(lists: lists, stores: stores, counts: counts)
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
