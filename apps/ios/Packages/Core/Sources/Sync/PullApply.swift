import Contract
import Foundation
import GRDB

extension HouseholdDatabase {
    /// Stores one pulled page and moves the cursor, in one transaction (web `applyPage`). Returns `false`, writing
    /// nothing, when `householdId` is no longer the open household (a switch while the page was in flight).
    ///
    /// - A table this app has no local table for is skipped; its rows arrive after the app learns it (cursor reset rule in `Migrations`).
    /// - A column this app does not know is ignored, so a newer server is safe.
    /// - A row with a change still queued or failed keeps its local value; the server's copy arrives after the push lands.
    func applyPull(householdId: UUID, page: SyncPullPage) throws -> Bool {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { return false }
        try pool.write { db in
            var queued = Set<String>()
            for table in ["outbox", "outbox_failed"] {
                for row in try Row.fetchAll(db, sql: "SELECT table_name, row_id FROM \(table)") {
                    queued.insert("\(row["table_name"] as String):\(row["row_id"] as String)")
                }
            }
            for (table, rows) in page.changes.sorted(by: { $0.key < $1.key }) {
                // Table names come from the server and go into SQL text: only plain names of tables that exist here.
                guard !SyncTables.localOnly.contains(table), Self.isPlainName(table), try db.tableExists(table) else { continue }
                for row in rows {
                    if case .string(let id)? = row["id"], queued.contains("\(table):\(id)") { continue }
                    try Self.writeRow(db, table: table, row: row)
                }
            }
            try db.execute(sql: "INSERT OR REPLACE INTO meta (key, value) VALUES ('cursor', ?)", arguments: [String(page.cursor)])
        }
        return true
    }

    private static func isPlainName(_ name: String) -> Bool {
        !name.isEmpty && name.allSatisfy { $0 == "_" || ($0.isASCII && ($0.isLowercase || $0.isNumber)) }
    }

    /// Postgres writes `2026-11-23T17:47:27.123456+00:00`; the app stores `2026-11-23T17:47:27.123Z` (millisecond UTC), so
    /// text order stays time order (`activity_feed_idx`) and `date(_:)` reads both. Other shapes are returned unchanged.
    static func normalized(timestamp text: String) -> String {
        var body = text
        if body.hasSuffix("+00:00") { body = String(body.dropLast(6)) + "Z" }
        guard body.hasSuffix("Z") else {
            // Another offset: let Foundation convert it.
            if let date = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) ?? Date(text, strategy: Date.ISO8601FormatStyle()) {
                return Self.text(date)
            }
            return text
        }
        body.removeLast()
        var whole = body
        var fraction = ""
        if let dot = body.firstIndex(of: ".") {
            whole = String(body[..<dot])
            fraction = String(body[body.index(after: dot)...])
        }
        guard whole.count == 19, fraction.allSatisfy(\.isNumber) else { return text }
        return "\(whole).\(String((fraction + "000").prefix(3)))Z"
    }
}
