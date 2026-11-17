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

        // CORE-08: the server's `activity` columns, so the Home feed reads offline. The index serves the
        // household-and-time window the feed asks for (mirrors `activity_feed_idx`).
        migrator.registerMigration("v2") { db in
            try db.create(table: "activity") { t in
                t.primaryKey("id", .text)
                t.column("household_id", .text).notNull()
                t.column("actor_id", .text)
                t.column("module", .text).notNull()
                t.column("entity", .text).notNull()
                t.column("entity_id", .text).notNull()
                t.column("action", .text).notNull()
                t.column("summary", .text).notNull()
                t.column("created_at", .text).notNull()
                t.column("updated_at", .text).notNull()
                t.column("deleted_at", .text)
                t.column("sync_seq", .integer).notNull()
            }
            try db.create(index: "activity_household_created_idx", on: "activity", columns: ["household_id", "created_at"])
        }

        return migrator
    }
}
