import Foundation
import GRDB

extension HouseholdDatabase {
    /// SHOP-15: the local household row's `module_shopping`. `nil` when the row has not been pulled yet (or the flag is
    /// still empty), so the caller can tell "off" from "not known".
    public func shoppingModuleEnabled(householdId: UUID) throws -> Bool? {
        let pool = try requirePool()
        return try pool.read { db in
            let row = try Row.fetchOne(
                db, sql: "SELECT module_shopping FROM household WHERE id = ?",
                arguments: [householdId.uuidString.lowercased()]
            )
            guard let row else { return nil }
            let enabled: Bool? = row["module_shopping"]
            return enabled
        }
    }

    /// SHOP-15: the household's live lists, read once (the assistant has no screen to observe from).
    public func shoppingListRecords(householdId: UUID) throws -> [ListRecord] {
        let pool = try requirePool()
        return try pool.read { db in
            try Row.fetchAll(
                db,
                sql: """
                SELECT id, name, store_id, is_template, archived_at, created_at FROM shopping_list
                WHERE household_id = ? AND deleted_at IS NULL
                """,
                arguments: [householdId.uuidString.lowercased()]
            ).compactMap { row -> ListRecord? in
                guard let id = UUID(uuidString: row["id"]) else { return nil }
                let storeId: String? = row["store_id"]
                let template: Bool? = row["is_template"]
                return ListRecord(
                    id: id, name: row["name"], storeId: storeId.flatMap { UUID(uuidString: $0) }, isTemplate: template ?? false,
                    archivedAt: row["archived_at"], createdAt: row["created_at"]
                )
            }
        }
    }
}
