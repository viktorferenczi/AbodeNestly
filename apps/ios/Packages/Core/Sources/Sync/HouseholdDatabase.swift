import Foundation
import GRDB

public enum HouseholdDatabaseError: Error, Equatable, Sendable {
    /// A read or write that needs the open household database, with none open.
    case notOpen
}

/// A row of the placeholder `shopping_list` table.
public struct ShoppingListRow: Sendable, Equatable {
    public var id: UUID
    public var householdId: UUID
    public var name: String

    public init(id: UUID = UUID(), householdId: UUID, name: String) {
        self.id = id
        self.householdId = householdId
        self.name = name
    }
}

/// The handle `open` returns: which household and file, and whether its pool still works.
public struct OpenHousehold: Sendable {
    public let householdId: UUID
    public let fileName: String
    private let pool: DatabasePool

    fileprivate init(householdId: UUID, fileName: String, pool: DatabasePool) {
        self.householdId = householdId
        self.fileName = fileName
        self.pool = pool
    }

    /// `false` once `close()` released the pool (a closed pool throws on every access).
    public var isUsable: Bool {
        (try? pool.read { _ in true }) == true
    }
}

/// One SQLite file per household (SPEC §6.1): own rows, own outbox, own pull cursor. Switching closes the pool
/// of the household being left before the next opens. Web twin: `web/src/sync/db.ts`.
public actor HouseholdDatabase {
    private let directory: URL
    private let seedLists: [UUID: String]
    private var pool: DatabasePool?
    private var handle: OpenHousehold?

    /// `rootURL` is a temporary directory in tests and Application Support in the app. `seedLists` (E2E only) names
    /// one list to insert when a household is opened without any.
    public init(rootURL: URL, seedLists: [UUID: String] = [:]) {
        self.seedLists = seedLists
        directory = rootURL.appendingPathComponent("households", isDirectory: true)
    }

    /// The household whose pool is open, if any.
    public var current: UUID? { handle?.householdId }

    public nonisolated func fileName(for householdId: UUID) -> String {
        "\(householdId.uuidString.lowercased()).sqlite"
    }

    private func fileURL(for householdId: UUID) -> URL {
        directory.appendingPathComponent(fileName(for: householdId))
    }

    /// Opens (creating it on first use) the household's file, migrates it and seeds `meta.cursor = 0`.
    /// Opening a different household closes the current one first; opening the current one is a no-op.
    @discardableResult
    public func open(householdId: UUID) throws -> OpenHousehold {
        if let handle, handle.householdId == householdId { return handle }
        close()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let pool = try DatabasePool(path: fileURL(for: householdId).path)
        try Migrations.migrator().migrate(pool)
        try pool.write { db in
            try db.execute(sql: "INSERT OR IGNORE INTO meta (key, value) VALUES ('cursor', '0')")
            if let name = seedLists[householdId],
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM shopping_list") == 0 {
                try db.execute(
                    sql: "INSERT INTO shopping_list (id, household_id, name) VALUES (?, ?, ?)",
                    arguments: [UUID().uuidString.lowercased(), householdId.uuidString.lowercased(), name]
                )
            }
        }
        let opened = OpenHousehold(householdId: householdId, fileName: fileName(for: householdId), pool: pool)
        self.pool = pool
        handle = opened
        return opened
    }

    /// Releases the pool. Safe to call when nothing is open.
    public func close() {
        try? pool?.close()
        pool = nil
        handle = nil
    }

    // MARK: Cursor (SHOP-08's pull loop moves it; this story only stores it)

    public func readCursor() throws -> Int {
        let pool = try requirePool()
        return try pool.read { db in
            try String.fetchOne(db, sql: "SELECT value FROM meta WHERE key = 'cursor'").flatMap(Int.init) ?? 0
        }
    }

    public func writeCursor(_ value: Int) throws {
        let pool = try requirePool()
        try pool.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO meta (key, value) VALUES ('cursor', ?)", arguments: [String(value)])
        }
    }

    // MARK: Outbox

    /// Queued writes of a household. Counts on the open pool when it is that household's own; otherwise opens a
    /// short-lived connection and closes it again (a household that was never opened has nothing queued).
    public func pendingCount(householdId: UUID) throws -> Int {
        if let pool, handle?.householdId == householdId {
            return try pool.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM outbox") ?? 0 }
        }
        let url = fileURL(for: householdId)
        guard FileManager.default.fileExists(atPath: url.path) else { return 0 }
        let queue = try DatabaseQueue(path: url.path)
        defer { try? queue.close() }
        return try queue.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM outbox") ?? 0 }
    }

    // MARK: Placeholder lists (replaced by SHOP-01/08)

    public func lists() throws -> [ShoppingListRow] {
        let pool = try requirePool()
        return try pool.read { db in
            try Row.fetchAll(db, sql: "SELECT id, household_id, name FROM shopping_list WHERE deleted_at IS NULL ORDER BY name")
                .compactMap { row in
                    guard let id = UUID(uuidString: row["id"]), let household = UUID(uuidString: row["household_id"]) else { return nil }
                    return ShoppingListRow(id: id, householdId: household, name: row["name"])
                }
        }
    }

    public func insertList(_ list: ShoppingListRow) throws {
        let pool = try requirePool()
        try pool.write { db in
            try db.execute(
                sql: "INSERT OR REPLACE INTO shopping_list (id, household_id, name) VALUES (?, ?, ?)",
                arguments: [list.id.uuidString.lowercased(), list.householdId.uuidString.lowercased(), list.name]
            )
        }
    }

    /// Test seam: queues one mutation so pending counts have something to count.
    func enqueueForTesting(table: String = "shopping_list", rowId: UUID = UUID()) throws {
        let pool = try requirePool()
        try pool.write { db in
            try db.execute(
                sql: "INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES (?, ?, 'upsert', ?, '{}', ?)",
                arguments: [UUID().uuidString.lowercased(), table, rowId.uuidString.lowercased(), ISO8601DateFormatter().string(from: Date())]
            )
        }
    }

    private func requirePool() throws -> DatabasePool {
        guard let pool else { throw HouseholdDatabaseError.notOpen }
        return pool
    }
}
