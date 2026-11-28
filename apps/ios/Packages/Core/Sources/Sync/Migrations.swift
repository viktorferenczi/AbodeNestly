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

        // CORE-11: attachments of pantry items, assets and log entries (the server's columns), plus the offline upload
        // queue. The queue is separate from `outbox` on purpose: /sync carries rows, not bytes, and nothing drains
        // `outbox` yet (SHOP-08). `file_path` is relative to the app's root folder and holds the picked bytes.
        migrator.registerMigration("v4") { db in
            try db.create(table: "attachment") { t in
                t.primaryKey("id", .text)
                t.column("household_id", .text).notNull()
                t.column("entity", .text).notNull()
                t.column("entity_id", .text).notNull()
                t.column("storage_path", .text).notNull()
                t.column("mime", .text).notNull()
                t.column("size_bytes", .integer).notNull()
                t.column("created_by", .text)
                t.column("created_at", .text).notNull()
                t.column("updated_at", .text).notNull()
                t.column("deleted_at", .text)
                t.column("sync_seq", .integer).notNull()
            }
            try db.execute(sql: "CREATE INDEX attachment_entity_idx ON attachment (entity, entity_id)")
            try db.create(table: "attachment_outbox") { t in
                t.primaryKey("id", .text)
                t.column("household_id", .text).notNull()
                t.column("entity", .text).notNull()
                t.column("entity_id", .text).notNull()
                t.column("filename", .text).notNull()
                t.column("mime", .text).notNull()
                t.column("size", .integer).notNull()
                t.column("file_path", .text).notNull()
                t.column("created_at", .text).notNull()
            }
        }

        // CORE-12: my switches for this household. The server's PK is (user_id, household_id, type); there is no `id`, no
        // soft delete and no row until the first change (a missing row means enabled). `sync_seq` stays for the pull.
        migrator.registerMigration("v5") { db in
            try db.create(table: "notification_pref") { t in
                t.column("user_id", .text).notNull()
                t.column("household_id", .text).notNull()
                t.column("type", .text).notNull()
                t.column("enabled", .boolean).notNull().defaults(to: true)
                t.column("updated_at", .text)
                t.column("sync_seq", .integer)
                t.primaryKey(["user_id", "household_id", "type"])
            }
            try db.execute(sql: "CREATE INDEX notification_pref_household_idx ON notification_pref (household_id)")
        }

        // SHOP-08: the server's full columns for the tables /sync pushes (all nullable locally: a row may be written
        // before the server stamps `created_at` / `updated_at` / `sync_seq`), the pull-only tables web also stores,
        // and the queue of rejected changes. RULE (same as web `db.ts`): any later migration that adds a table the
        // pull returns must also reset `meta.cursor` to 0, or rows below the old cursor never arrive.
        migrator.registerMigration("v6") { db in
            func add(_ table: String, _ columns: [(String, String)]) throws {
                for (name, type) in columns {
                    try db.execute(sql: "ALTER TABLE \(table) ADD COLUMN \(name) \(type)")
                }
            }
            let stamps: [(String, String)] = [("created_at", "TEXT"), ("updated_at", "TEXT"), ("sync_seq", "INTEGER")]
            try add("shopping_list", [
                ("store_id", "TEXT"), ("is_template", "BOOLEAN"), ("share_token", "TEXT"), ("archived_at", "TEXT"),
            ] + stamps)
            try add("list_item", [
                ("name_key", "TEXT"), ("quantity", "REAL"), ("unit", "TEXT"), ("note", "TEXT"), ("barcode", "TEXT"),
                ("checked", "BOOLEAN"), ("checked_at", "TEXT"), ("checked_by", "TEXT"), ("manual_position", "INTEGER"),
                ("source", "TEXT"), ("pantry_item_id", "TEXT"), ("maint_task_id", "TEXT"), ("created_by", "TEXT"),
            ] + stamps)
            try add("store", stamps)
            try add("location", [("kind", "TEXT")] + stamps)

            try db.create(table: "household") { t in
                t.primaryKey("id", .text)
                t.column("name", .text)
                t.column("created_by", .text)
                t.column("module_shopping", .boolean)
                t.column("module_pantry", .boolean)
                t.column("module_maintenance", .boolean)
                t.column("currency", .text)
                t.column("created_at", .text)
                t.column("updated_at", .text)
                t.column("deleted_at", .text)
                t.column("sync_seq", .integer)
            }
            try db.create(table: "member") { t in
                t.column("household_id", .text).notNull()
                t.column("user_id", .text).notNull()
                t.column("role", .text)
                t.column("joined_at", .text)
                t.column("left_at", .text)
                t.column("updated_at", .text)
                t.column("sync_seq", .integer)
                t.primaryKey(["household_id", "user_id"])
            }
            try db.create(table: "invite") { t in
                t.primaryKey("id", .text)
                t.column("household_id", .text).notNull().indexed()
                t.column("code", .text)
                t.column("role", .text)
                t.column("created_by", .text)
                t.column("expires_at", .text)
                t.column("max_uses", .integer)
                t.column("uses", .integer)
                t.column("created_at", .text)
                t.column("updated_at", .text)
                t.column("deleted_at", .text)
                t.column("sync_seq", .integer)
            }
            try db.create(table: "store_item_order") { t in
                t.column("household_id", .text).notNull()
                t.column("store_id", .text).notNull()
                t.column("name_key", .text).notNull()
                t.column("mean_position", .double)
                t.column("samples", .integer)
                t.column("updated_at", .text)
                t.column("sync_seq", .integer)
                t.primaryKey(["store_id", "name_key"])
            }
            // A rejected change leaves `outbox` and waits here for Retry or Discard; `seq` is the outbox seq it had,
            // so Retry puts it back at its old place.
            try db.create(table: "outbox_failed") { t in
                t.primaryKey("seq", .integer)
                t.column("id", .text).notNull()
                t.column("table_name", .text).notNull()
                t.column("op", .text).notNull()
                t.column("row_id", .text).notNull()
                t.column("data", .text).notNull()
                t.column("client_ts", .text).notNull()
                t.column("reason", .text).notNull()
                t.column("detail", .text).notNull()
                t.column("failed_at", .text).notNull()
            }
            try db.execute(sql: "UPDATE meta SET value = '0' WHERE key = 'cursor'")
        }

        // SHOP-12: the server's `item_price` columns, so a price is stored and queued offline and the caption reads it
        // locally. Nullable stamps like v6 (a row is written before the server stamps it). The (store, name key) index
        // serves the last-price lookup; the cursor reset follows the v6 rule, or prices below the old cursor never arrive.
        migrator.registerMigration("v7") { db in
            try db.create(table: "item_price") { t in
                t.primaryKey("id", .text)
                t.column("household_id", .text).notNull()
                t.column("store_id", .text).notNull()
                t.column("name_key", .text).notNull()
                t.column("price", .double).notNull()
                t.column("seen_at", .text).notNull()
                t.column("created_at", .text)
                t.column("updated_at", .text)
                t.column("deleted_at", .text)
                t.column("sync_seq", .integer)
            }
            try db.execute(sql: "CREATE INDEX item_price_store_key_idx ON item_price (store_id, name_key)")
            try db.execute(sql: "CREATE INDEX item_price_household_idx ON item_price (household_id)")
            try db.execute(sql: "UPDATE meta SET value = '0' WHERE key = 'cursor'")
        }

        return migrator
    }
}
