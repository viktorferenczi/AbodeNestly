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
            // Mirrors SPEC §14 `activity_feed_idx`; `created_at` is UTC text, so text order is time order.
            try db.execute(sql: """
                CREATE INDEX activity_feed_idx ON activity (household_id, created_at DESC) WHERE deleted_at IS NULL
                """)
        }

        // CORE-09: the name columns search reads, so it works offline. Minimal on purpose: the module stories (SHOP-01,
        // PANT-01, MAINT-01, ...) widen these tables to the server's full columns. `maint_task` names its text `title`
        // on the server; the search maps it to a name.
        migrator.registerMigration("v3") { db in
            for table in ["list_item", "pantry_item", "recipe", "asset", "maint_task", "store", "location", "contractor"] {
                try db.create(table: table) { t in
                    t.primaryKey("id", .text)
                    t.column("household_id", .text).notNull()
                    t.column(table == "maint_task" ? "title" : "name", .text).notNull()
                    if table == "list_item" { t.column("list_id", .text) }
                    t.column("deleted_at", .text)
                }
                try db.execute(sql: "CREATE INDEX \(table)_household_idx ON \(table) (household_id)")
                try db.execute(sql: "CREATE INDEX \(table)_live_idx ON \(table) (household_id) WHERE deleted_at IS NULL")
            }
        }

        return migrator
    }
}
