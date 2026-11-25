import Foundation
import GRDB

/// A live `store` row with how many items the household has learned an aisle position for in it (SHOP-05).
public struct StoreRow: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var name: String
    /// Count of this store's `store_item_order` rows.
    public var learned: Int

    public init(id: UUID, name: String, learned: Int = 0) {
        self.id = id
        self.name = name
        self.learned = learned
    }
}

extension HouseholdDatabase {
    /// The household's live stores with their learned-item counts, re-emitted whenever stores or their order rows
    /// change. Finishes at once when the household is not open.
    public func observeStores(householdId: UUID) -> AsyncStream<[StoreRow]> {
        guard let pool, handle?.householdId == householdId else { return AsyncStream { $0.finish() } }
        let key = householdId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db -> [StoreRow] in
            try Row.fetchAll(
                db,
                sql: """
                SELECT s.id AS id, s.name AS name, COUNT(o.name_key) AS learned FROM store s
                LEFT JOIN store_item_order o ON o.store_id = s.id AND o.household_id = s.household_id
                WHERE s.household_id = ? AND s.deleted_at IS NULL GROUP BY s.id, s.name
                """,
                arguments: [key]
            ).compactMap { row -> StoreRow? in
                UUID(uuidString: row["id"]).map { StoreRow(id: $0, name: row["name"], learned: row["learned"]) }
            }
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
