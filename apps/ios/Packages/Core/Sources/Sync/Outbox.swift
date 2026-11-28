import Contract
import Foundation
import GRDB

/// Which tables the sync loop sends and which columns it never sends.
public enum SyncTables {
    /// Tables the server accepts mutations for (api/_lib/sync/tables.ts). Rows queued for other tables wait, unsent and
    /// uncounted, until their `-api` story adds them to both lists. `item_price` joined with SHOP-12.
    public static let pushable = ["store", "shopping_list", "list_item", "location", "item_price"]

    /// Every table SPEC §6.2 names; the closed set a write may target (table names go into SQL text).
    static let known: Set<String> = [
        "household", "member", "invite", "activity", "attachment", "store", "shopping_list", "list_item",
        "store_item_order", "item_price", "location", "category", "pantry_item", "pantry_movement", "unit_conversion",
        "recipe", "recipe_ingredient", "house", "contractor", "asset", "maint_task", "task_consumable", "maint_log",
    ]

    /// Columns the server writes itself (SPEC §6.2); they never travel in an upsert's `data`. Mirrors api/_lib/sync/tables.ts.
    static let serverOwned: Set<String> = [
        "id", "household_id", "created_at", "updated_at", "sync_seq", "name_key", "share_token",
    ]

    /// Tables whose `name_key` is a real, client-sent column (the item_price key); elsewhere the server derives it.
    static let clientNameKeyTables: Set<String> = ["item_price"]

    /// The fields of a write for `table` that may go to the server: `fields` minus the columns the server owns.
    static func sendable(table: String, fields: [String: JSONValue]) -> [String: JSONValue] {
        var dropped: Set<String> = serverOwned
        if clientNameKeyTables.contains(table) { dropped.remove("name_key") }
        return fields.filter { !dropped.contains($0.key) }
    }

    /// Tables a pull must never write to.
    static let localOnly: Set<String> = ["outbox", "outbox_failed", "meta", "attachment_outbox"]
}

public enum SyncWriteError: Error, Equatable, Sendable {
    /// A table SPEC §6.2 does not name, or one this app has no local table for yet.
    case unknownTable(String)
    /// The row has no string `id`, or a patch has nothing to write.
    case invalidRow
    /// A patch or delete of a row that is not in the local database.
    case rowNotFound
}

/// How many changes wait for the server (`pending`, only tables the loop sends) and how many it turned down (`failed`).
public struct SyncCounts: Sendable, Equatable {
    public var pending: Int
    public var failed: Int

    public init(pending: Int = 0, failed: Int = 0) {
        self.pending = pending
        self.failed = failed
    }
}

/// A mutation the server rejected, waiting for Retry or Discard in the sync sheet.
public struct FailedChange: Sendable, Equatable, Identifiable {
    public var seq: Int
    public var table: String
    public var op: String
    public var rowId: String
    public var reason: SyncRejectionReason
    public var detail: String
    public var failedAt: String
    public var id: Int { seq }

    public init(seq: Int, table: String, op: String, rowId: String, reason: SyncRejectionReason, detail: String, failedAt: String = "") {
        self.seq = seq
        self.table = table
        self.op = op
        self.rowId = rowId
        self.reason = reason
        self.detail = detail
        self.failedAt = failedAt
    }
}

/// An outbox row with the envelope the push sends.
struct QueuedMutation: Equatable {
    var seq: Int
    var mutation: SyncMutation
}

/// The list item half of `HouseholdDatabase.writeItem`: a new row, or only the changed columns of an existing one.
public enum ItemChange: Sendable {
    case upsert(row: [String: JSONValue])
    case patch(id: UUID, fields: [String: JSONValue], localOnly: [String: JSONValue])
}

/// An upsert ready to store: see `HouseholdDatabase.prepareUpsert`.
struct PreparedUpsert: Sendable {
    var rowId: String
    var local: [String: JSONValue]
    var data: [String: JSONValue]
}

extension HouseholdDatabase {
    // MARK: Writes (the web `mutate` twin: every shopping and pantry write goes through here)

    /// Stores the full row locally and queues its fields minus the server-owned columns, in one transaction so the two
    /// cannot diverge (AGENTS.md: offline is a requirement). The caller kicks sync afterwards. `row` needs a string `id`;
    /// a missing `household_id` is filled with the open household.
    public func upsert(table: String, row: [String: JSONValue], now: Date = Date()) throws {
        let pool = try requirePool()
        guard let household = handle?.householdId else { throw HouseholdDatabaseError.notOpen }
        let prepared = try Self.prepareUpsert(table: table, row: row, household: household)
        try pool.write { db in
            try Self.requireTable(db, table)
            try Self.writeAndQueue(db, table: table, prepared: prepared, now: now)
        }
    }

    /// `upsert` for many rows of one table in a single transaction (SHOP-11 "Add all"): every row and every outbox entry
    /// is stored, or, when one fails, none is. An empty list is a no-op. The caller kicks sync once afterwards.
    public func upsertAll(table: String, rows: [[String: JSONValue]], now: Date = Date()) throws {
        guard !rows.isEmpty else { return }
        let pool = try requirePool()
        guard let household = handle?.householdId else { throw HouseholdDatabaseError.notOpen }
        var prepared: [PreparedUpsert] = []
        for row in rows { prepared.append(try Self.prepareUpsert(table: table, row: row, household: household)) }
        try pool.write { db in
            try Self.requireTable(db, table)
            for item in prepared { try Self.writeAndQueue(db, table: table, prepared: item, now: now) }
        }
    }

    /// The row to store locally (household filled in) and the fields to queue (server-owned columns removed).
    private static func prepareUpsert(table: String, row: [String: JSONValue], household: UUID) throws -> PreparedUpsert {
        guard case .string(let rowId)? = row["id"] else { throw SyncWriteError.invalidRow }
        var local = row
        if local["household_id"] == nil { local["household_id"] = .string(household.uuidString.lowercased()) }
        let data: [String: JSONValue] = SyncTables.sendable(table: table, fields: row)
        return PreparedUpsert(rowId: rowId, local: local, data: data)
    }

    private static func writeAndQueue(_ db: Database, table: String, prepared: PreparedUpsert, now: Date) throws {
        try writeRow(db, table: table, row: prepared.local)
        try queue(db, table: table, op: "upsert", rowId: prepared.rowId, data: prepared.data, now: now)
    }

    /// SHOP-12: an item change and the price saved with it, in one transaction so the price never exists without the
    /// item write (or the other way round). Either part may be absent; a patch with nothing to write is invalid. The
    /// caller kicks sync once afterwards.
    public func writeItem(change: ItemChange?, price: [String: JSONValue]?, now: Date = Date()) throws {
        let pool = try requirePool()
        guard let household = handle?.householdId else { throw HouseholdDatabaseError.notOpen }
        var itemUpsert: PreparedUpsert?
        var patchId: String?
        var patchData: [String: JSONValue] = [:]
        var patchLocal: [String: JSONValue] = [:]
        switch change {
        case .upsert(let row)?:
            itemUpsert = try Self.prepareUpsert(table: "list_item", row: row, household: household)
        case .patch(let id, let fields, let localOnly)?:
            patchData = SyncTables.sendable(table: "list_item", fields: fields)
            guard !patchData.isEmpty else { throw SyncWriteError.invalidRow }
            patchId = id.uuidString.lowercased()
            patchLocal = patchData.merging(localOnly) { _, new in new }
        case nil:
            break
        }
        var priceUpsert: PreparedUpsert?
        if let price { priceUpsert = try Self.prepareUpsert(table: "item_price", row: price, household: household) }
        // Immutable copies: the write closure is sendable and must not capture the variables above.
        let newItem: PreparedUpsert? = itemUpsert
        let newPrice: PreparedUpsert? = priceUpsert
        let patchRowId: String? = patchId
        let patchFields: [String: JSONValue] = patchData
        let patchValues: [String: JSONValue] = patchLocal
        try pool.write { db in
            if let newItem {
                try Self.requireTable(db, "list_item")
                try Self.writeAndQueue(db, table: "list_item", prepared: newItem, now: now)
            }
            if let patchRowId {
                try Self.requireTable(db, "list_item")
                try Self.update(db, table: "list_item", rowId: patchRowId, values: patchValues)
                try Self.queue(db, table: "list_item", op: "upsert", rowId: patchRowId, data: patchFields, now: now)
            }
            if let newPrice {
                try Self.requireTable(db, "item_price")
                try Self.writeAndQueue(db, table: "item_price", prepared: newPrice, now: now)
            }
        }
    }

    /// Updates only `fields` locally and queues just those columns, so a concurrent edit of another column on another
    /// device is not overwritten (the server merges the columns present in `data`). `localOnly` columns (a derived
    /// `name_key`, say) are written locally and never queued.
    public func patch(
        table: String, id: UUID, fields: [String: JSONValue], localOnly: [String: JSONValue] = [:], now: Date = Date()
    ) throws {
        let pool = try requirePool()
        guard handle?.householdId != nil else { throw HouseholdDatabaseError.notOpen }
        let data: [String: JSONValue] = SyncTables.sendable(table: table, fields: fields)
        guard !data.isEmpty else { throw SyncWriteError.invalidRow }
        let rowId = id.uuidString.lowercased()
        try pool.write { db in
            try Self.requireTable(db, table)
            try Self.update(db, table: table, rowId: rowId, values: data.merging(localOnly) { _, new in new })
            try Self.queue(db, table: table, op: "upsert", rowId: rowId, data: data, now: now)
        }
    }

    /// Soft delete (SPEC §6.3): the row stays locally with `deleted_at` set.
    public func remove(table: String, id: UUID, now: Date = Date()) throws {
        let pool = try requirePool()
        guard handle?.householdId != nil else { throw HouseholdDatabaseError.notOpen }
        let rowId = id.uuidString.lowercased()
        try pool.write { db in
            try Self.requireTable(db, table)
            try Self.update(db, table: table, rowId: rowId, values: ["deleted_at": .string(Self.text(now))])
            try Self.queue(db, table: table, op: "delete", rowId: rowId, data: nil, now: now)
        }
    }

    /// Pantry quantity changes are deltas, never absolute writes (AGENTS.md). The local `quantity` is adjusted once the
    /// pantry story adds that column; the server accepts the mutation once PANT-03-api does, until then it waits.
    public func delta(pantryItemId: UUID, delta: Double, reason: String, now: Date = Date()) throws {
        let pool = try requirePool()
        guard handle?.householdId != nil else { throw HouseholdDatabaseError.notOpen }
        let rowId = pantryItemId.uuidString.lowercased()
        try pool.write { db in
            if try db.columns(in: "pantry_item").contains(where: { $0.name == "quantity" }) {
                try db.execute(
                    sql: "UPDATE pantry_item SET quantity = COALESCE(quantity, 0) + ? WHERE id = ?", arguments: [delta, rowId]
                )
            }
            let data: [String: JSONValue] = ["field": .string("quantity"), "delta": .double(delta), "reason": .string(reason)]
            try Self.queue(db, table: "pantry_item", op: "delta", rowId: rowId, data: data, now: now)
        }
    }

    /// Queues a mutation without touching a local row. For E2E seeding (Debug builds) and tests.
    public func enqueue(table: String, op: String, rowId: UUID, data: [String: JSONValue]?, now: Date = Date()) throws {
        let pool = try requirePool()
        try pool.write { db in
            try Self.queue(db, table: table, op: op, rowId: rowId.uuidString.lowercased(), data: data, now: now)
        }
    }

    // MARK: Sync counts for the chip and the sheet

    /// The queued and failed counts, re-emitted whenever they change. Finishes at once when the household is not open.
    public func observeSyncCounts(householdId: UUID, tables: [String] = SyncTables.pushable) -> AsyncStream<SyncCounts> {
        guard let pool, handle?.householdId == householdId else { return AsyncStream { $0.finish() } }
        let observation = ValueObservation.tracking { db -> SyncCounts in
            let pending = try Int.fetchOne(
                db, sql: "SELECT COUNT(*) FROM outbox WHERE table_name IN (\(databaseQuestionMarks(count: tables.count)))",
                arguments: StatementArguments(tables)
            ) ?? 0
            return SyncCounts(pending: pending, failed: try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM outbox_failed") ?? 0)
        }
        return Self.stream(observation, in: pool)
    }

    /// The failed changes, oldest first, re-emitted whenever they change. Finishes at once when the household is not open.
    public func observeFailed(householdId: UUID) -> AsyncStream<[FailedChange]> {
        guard let pool, handle?.householdId == householdId else { return AsyncStream { $0.finish() } }
        let observation = ValueObservation.tracking { db in
            try Row.fetchAll(db, sql: "SELECT * FROM outbox_failed ORDER BY seq").map { row in
                FailedChange(
                    seq: row["seq"], table: row["table_name"], op: row["op"], rowId: row["row_id"],
                    reason: SyncRejectionReason(rawValue: row["reason"]), detail: row["detail"], failedAt: row["failed_at"]
                )
            }
        }
        return Self.stream(observation, in: pool)
    }

    private static func stream<T: Sendable>(_ observation: ValueObservation<ValueReducers.Fetch<T>>, in pool: DatabasePool) -> AsyncStream<T> {
        AsyncStream { continuation in
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

    // MARK: The loop's view of the queue

    /// Up to `limit` queued mutations of `tables` in `seq` order.
    func pendingMutations(householdId: UUID, tables: [String], limit: Int) throws -> [QueuedMutation] {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { throw HouseholdDatabaseError.notOpen }
        return try pool.read { db in
            try Row.fetchAll(
                db,
                sql: """
                SELECT seq, id, table_name, op, row_id, data, client_ts FROM outbox
                WHERE table_name IN (\(databaseQuestionMarks(count: tables.count))) ORDER BY seq LIMIT ?
                """,
                arguments: StatementArguments(tables) + [limit]
            ).map { row in
                let op: String = row["op"]
                let text: String = row["data"]
                // `delete` carries no data on the wire.
                let data = op == "delete" ? nil : (try? JSONDecoder().decode([String: JSONValue].self, from: Data(text.utf8)))
                return QueuedMutation(
                    seq: row["seq"],
                    mutation: SyncMutation(
                        id: row["id"], table: row["table_name"], op: op, rowId: row["row_id"], data: data ?? (op == "delete" ? nil : [:]),
                        clientTs: row["client_ts"]
                    )
                )
            }
        }
    }

    /// One transaction: rows the server applied leave the outbox; rows it rejected move to `outbox_failed`.
    func settle(householdId: UUID, applied: [Int], rejected: [(seq: Int, reason: SyncRejectionReason, detail: String)], now: Date) throws {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { throw HouseholdDatabaseError.notOpen }
        let stamp = Self.text(now)
        try pool.write { db in
            for failure in rejected {
                try db.execute(
                    sql: """
                    INSERT OR REPLACE INTO outbox_failed (seq, id, table_name, op, row_id, data, client_ts, reason, detail, failed_at)
                    SELECT seq, id, table_name, op, row_id, data, client_ts, ?, ?, ? FROM outbox WHERE seq = ?
                    """,
                    arguments: [failure.reason.rawValue, failure.detail, stamp, failure.seq]
                )
            }
            for seq in applied + rejected.map(\.seq) {
                try db.execute(sql: "DELETE FROM outbox WHERE seq = ?", arguments: [seq])
            }
        }
    }

    /// Puts a failed change back at its old `seq`. Returns its mutation id (the loop forgets that it already refreshed for it).
    func requeueFailed(seq: Int) throws -> String? {
        let pool = try requirePool()
        return try pool.write { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT * FROM outbox_failed WHERE seq = ?", arguments: [seq]) else { return nil }
            // Same mutation id: the server does not remember rejected ids, so the retry is a fresh attempt.
            try db.execute(
                sql: "INSERT OR REPLACE INTO outbox (seq, id, table_name, op, row_id, data, client_ts) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arguments: [seq, row["id"], row["table_name"], row["op"], row["row_id"], row["data"], row["client_ts"]]
            )
            try db.execute(sql: "DELETE FROM outbox_failed WHERE seq = ?", arguments: [seq])
            return row["id"]
        }
    }

    /// Drops a failed change and, unless another change still targets the row, the local copy it created; then the pull
    /// restarts from 0 so the server's version, if there is one, comes back. Returns whether the failed change existed.
    func dropFailed(seq: Int) throws -> Bool {
        let pool = try requirePool()
        return try pool.write { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT table_name, row_id FROM outbox_failed WHERE seq = ?", arguments: [seq]) else {
                return false
            }
            let table: String = row["table_name"]
            let rowId: String = row["row_id"]
            try db.execute(sql: "DELETE FROM outbox_failed WHERE seq = ?", arguments: [seq])
            let others = try Int.fetchOne(
                db,
                sql: """
                SELECT (SELECT COUNT(*) FROM outbox WHERE table_name = ? AND row_id = ?)
                     + (SELECT COUNT(*) FROM outbox_failed WHERE table_name = ? AND row_id = ?)
                """,
                arguments: [table, rowId, table, rowId]
            ) ?? 0
            guard others == 0 else { return true }
            if SyncTables.pushable.contains(table) {
                try db.execute(sql: "DELETE FROM \(table) WHERE id = ?", arguments: [rowId])
            }
            try db.execute(sql: "INSERT OR REPLACE INTO meta (key, value) VALUES ('cursor', '0')")
            return true
        }
    }

    /// Test seam: raw rows as text (`NULL` for null), so tests can read any local table.
    func queryForTesting(_ sql: String) throws -> [[String: String]] {
        let pool = try requirePool()
        return try pool.read { db in
            try Row.fetchAll(db, sql: sql).map { row in
                Dictionary(uniqueKeysWithValues: row.columnNames.map { name -> (String, String) in
                    let text: String
                    switch (row[name] as DatabaseValue).storage {
                    case .null: text = "NULL"
                    case .int64(let value): text = String(value)
                    case .double(let value): text = String(value)
                    case .string(let value): text = value
                    case .blob: text = "BLOB"
                    }
                    return (name, text)
                })
            }
        }
    }

    /// Test seam: a household file as an older release left it (migrated up to `version`, a list item, cursor 42).
    func openLegacyForTesting(householdId: UUID, upTo version: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let pool = try DatabasePool(path: directory.appendingPathComponent(fileName(for: householdId)).path)
        try Migrations.migrator().migrate(pool, upTo: version)
        try pool.write { db in
            try db.execute(sql: "INSERT INTO meta (key, value) VALUES ('cursor', '42')")
            try db.execute(
                sql: "INSERT INTO list_item (id, household_id, name) VALUES (?, ?, 'Milk')",
                arguments: [UUID().uuidString.lowercased(), householdId.uuidString.lowercased()]
            )
        }
        try pool.close()
    }

    // MARK: Row writing

    private static func requireTable(_ db: Database, _ table: String) throws {
        guard SyncTables.known.contains(table), try db.tableExists(table) else { throw SyncWriteError.unknownTable(table) }
    }

    static func queue(
        _ db: Database, table: String, op: String, rowId: String, data: [String: JSONValue]?, now: Date
    ) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let json = String(decoding: try encoder.encode(data ?? [:]), as: UTF8.self)
        try db.execute(
            sql: "INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES (?, ?, ?, ?, ?, ?)",
            arguments: [UUID().uuidString.lowercased(), table, op, rowId, json, text(now)]
        )
    }

    /// `INSERT OR REPLACE` of the columns this app's table has; keys it does not know are ignored.
    static func writeRow(_ db: Database, table: String, row: [String: JSONValue]) throws {
        let known = Set(try db.columns(in: table).map(\.name))
        let keys = row.keys.filter { known.contains($0) }.sorted()
        guard !keys.isEmpty else { return }
        try db.execute(
            sql: "INSERT OR REPLACE INTO \(table) (\(keys.joined(separator: ", "))) VALUES (\(databaseQuestionMarks(count: keys.count)))",
            arguments: StatementArguments(keys.map { row[$0]?.databaseValue(key: $0) ?? .null })
        )
    }

    private static func update(_ db: Database, table: String, rowId: String, values: [String: JSONValue]) throws {
        let known = Set(try db.columns(in: table).map(\.name))
        let keys = values.keys.filter { known.contains($0) }.sorted()
        guard !keys.isEmpty else { throw SyncWriteError.invalidRow }
        try db.execute(
            sql: "UPDATE \(table) SET \(keys.map { "\($0) = ?" }.joined(separator: ", ")) WHERE id = ?",
            arguments: StatementArguments(keys.map { values[$0]?.databaseValue(key: $0) ?? .null } + [rowId.databaseValue])
        )
        guard db.changesCount > 0 else { throw SyncWriteError.rowNotFound }
    }
}

extension JSONValue {
    /// The SQLite value for a column. Timestamps (`*_at`) are normalized to the app's millisecond UTC text so text
    /// order is time order (see `HouseholdDatabase.normalized(timestamp:)`); arrays and objects are stored as JSON text.
    func databaseValue(key: String) -> DatabaseValue {
        switch self {
        case .string(let text): (key.hasSuffix("_at") ? HouseholdDatabase.normalized(timestamp: text) : text).databaseValue
        case .int(let value): value.databaseValue
        case .double(let value): value.databaseValue
        // Stored as the canonical decimal text, so the amount survives exactly (no REAL rounding).
        case .decimal(let value): ItemPriceText.canonical(value).databaseValue
        case .bool(let value): (value ? 1 : 0).databaseValue
        case .null: .null
        case .array, .dictionary:
            ((try? JSONEncoder().encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? "null").databaseValue
        }
    }
}
