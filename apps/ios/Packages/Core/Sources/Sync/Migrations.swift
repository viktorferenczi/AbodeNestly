import GRDB

/// Append-only (swift-ios skill): never edit a registered migration, add a new one. Mirrors the server schema
/// and the web `HouseholdDb` versions.
enum Migrations {
    static func migrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v1") { db in
            try db.create(table: "meta") { t in
                t.primaryKey("key", .text)
                t.column("value", .text)
            }
            // SPEC §6.2 envelope. `table_name` because `table` is an SQL keyword.
            try db.create(table: "outbox") { t in
                t.autoIncrementedPrimaryKey("seq")
                t.column("id", .text).notNull().unique()
                t.column("table_name", .text).notNull()
                t.column("op", .text).notNull()
                t.column("row_id", .text).notNull()
                t.column("data", .text).notNull()
                t.column("client_ts", .text).notNull()
            }
            // The contract has no shopping list model yet; SHOP-01/08 replace this placeholder (web does the same).
            try db.create(table: "shopping_list") { t in
                t.primaryKey("id", .text)
                t.column("household_id", .text).notNull().indexed()
                t.column("name", .text).notNull()
                t.column("deleted_at", .text)
            }
        }

        return migrator
    }
}
