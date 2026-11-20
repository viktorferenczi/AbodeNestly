import Contract
import CryptoKit
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

/// One name that matched a search (CORE-09). `table` is the local table it came from.
public struct SearchRow: Sendable, Equatable {
    public var table: String
    public var id: UUID
    public var name: String
    /// `list_item` only: the list the item sits on.
    public var listId: UUID?
    public var deletedAt: String?

    public init(table: String, id: UUID, name: String, listId: UUID? = nil, deletedAt: String? = nil) {
        self.table = table
        self.id = id
        self.name = name
        self.listId = listId
        self.deletedAt = deletedAt
    }
}

/// One soft-deleted row (CORE-10). `table` is the local table it came from.
public struct DeletedRow: Sendable, Equatable {
    public var table: String
    public var id: UUID
    public var name: String
    public var deletedAt: Date

    public init(table: String, id: UUID, name: String, deletedAt: Date) {
        self.table = table
        self.id = id
        self.name = name
        self.deletedAt = deletedAt
    }
}

/// One notification switch of one person in one household (CORE-12). `type` is the contract's NotificationType raw value.
public struct NotificationPrefRow: Sendable, Equatable {
    public var userId: UUID
    public var householdId: UUID
    public var type: String
    public var enabled: Bool

    public init(userId: UUID, householdId: UUID, type: String, enabled: Bool) {
        self.userId = userId
        self.householdId = householdId
        self.type = type
        self.enabled = enabled
    }
}

/// UUID v5 (RFC 4122 §4.3) of a switch: the outbox `row_id`, identical on web, iOS and Android because the table has no
/// `id`. Lives here because `setNotificationPref` derives it; Core's `NotificationPrefs` forwards to it.
public enum NotificationPrefId {
    private static let urlNamespace: [UInt8] = [
        0x6b, 0xa7, 0xb8, 0x11, 0x9d, 0xad, 0x11, 0xd1, 0x80, 0xb4, 0x00, 0xc0, 0x4f, 0xd4, 0x30, 0xc8,
    ]

    public static func rowId(userId: UUID, householdId: UUID, type: String) -> UUID {
        let name = "abodenestly:notification_pref:\(userId.uuidString):\(householdId.uuidString):\(type)".lowercased()
        var bytes = Array(Insecure.SHA1.hash(data: Data(urlNamespace) + Data(name.utf8))).prefix(16).map { $0 }
        bytes[6] = (bytes[6] & 0x0f) | 0x50
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }
}

/// A pick waiting to upload (CORE-11). The bytes live in a file under the app's root folder; `filePath` is relative to it.
public struct AttachmentUpload: Sendable, Equatable, Identifiable {
    public var id: UUID
    public var householdId: UUID
    public var entity: String
    public var entityId: UUID
    public var filename: String
    public var mime: String
    public var size: Int
    public var filePath: String
    public var createdAt: Date

    public init(
        id: UUID, householdId: UUID, entity: String, entityId: UUID, filename: String, mime: String, size: Int,
        filePath: String = "", createdAt: Date = Date()
    ) {
        self.id = id
        self.householdId = householdId
        self.entity = entity
        self.entityId = entityId
        self.filename = filename
        self.mime = mime
        self.size = size
        self.filePath = filePath
        self.createdAt = createdAt
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
    private let root: URL
    private let directory: URL
    private let seedLists: [UUID: String]
    private var pool: DatabasePool?
    private var handle: OpenHousehold?

    /// `rootURL` is a temporary directory in tests and Application Support in the app. `seedLists` (E2E only) names
    /// one list to insert when a household is opened without any.
    public init(rootURL: URL, seedLists: [UUID: String] = [:]) {
        self.seedLists = seedLists
        root = rootURL
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

    // MARK: Activity (CORE-08)

    /// Stores pulled rows of the open household. A row of another household is dropped: a pull that was in flight
    /// when the person switched must not write into the new household's file.
    public func upsertActivity(_ rows: [Activity]) throws {
        let pool = try requirePool()
        guard let householdId = handle?.householdId else { throw HouseholdDatabaseError.notOpen }
        let own = rows.filter { $0.householdId == householdId }
        guard !own.isEmpty else { return }
        try pool.write { db in
            for row in own {
                try db.execute(
                    sql: """
                    INSERT OR REPLACE INTO activity
                      (id, household_id, actor_id, module, entity, entity_id, action, summary,
                       created_at, updated_at, deleted_at, sync_seq)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    arguments: [
                        row.id.uuidString.lowercased(), row.householdId.uuidString.lowercased(),
                        row.actorId?.uuidString.lowercased(), row.module.rawValue, row.entity,
                        row.entityId.uuidString.lowercased(), row.action, row.summary,
                        Self.text(row.createdAt), Self.text(row.updatedAt), row.deletedAt.map(Self.text), row.syncSeq,
                    ]
                )
            }
        }
    }

    /// The household's newest stored rows, re-emitted whenever they change. The screen applies the 30-day / 20-row
    /// window; the cap here only keeps the observation small. Finishes at once when that household is not open,
    /// and when its pool closes (a switch).
    public func observeActivity(householdId: UUID) -> AsyncStream<[Activity]> {
        guard let pool, handle?.householdId == householdId else {
            return AsyncStream { $0.finish() }
        }
        let key = householdId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db in
            try Row.fetchAll(
                db,
                sql: """
                SELECT * FROM activity WHERE household_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC, id DESC LIMIT 100
                """,
                arguments: [key]
            ).compactMap(Self.activity)
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await rows in observation.values(in: pool) { continuation.yield(rows) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last rows.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Search (CORE-09)

    /// Local tables search may read, with the column that holds the name. A closed set: table names go into SQL text.
    public static let searchableTables: [String: String] = [
        "list_item": "name", "store": "name", "pantry_item": "name", "location": "name", "recipe": "name",
        "asset": "name", "maint_task": "title", "contractor": "name",
    ]

    /// Rows of `tables` whose name contains `query` (case-insensitive), re-emitted whenever they change. Soft-deleted
    /// rows are not read. A blank query, no tables, or a household that is not open yields one empty emission or an
    /// ended stream respectively, like `observeActivity`.
    public func observeSearch(householdId: UUID, query: String, tables: [String]) -> AsyncStream<[SearchRow]> {
        guard let pool, handle?.householdId == householdId else {
            return AsyncStream { $0.finish() }
        }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let known = tables.filter { Self.searchableTables[$0] != nil }
        guard !needle.isEmpty, !known.isEmpty else {
            return AsyncStream { continuation in
                continuation.yield([])
                continuation.finish()
            }
        }
        let key = householdId.uuidString.lowercased()
        // `\` escapes LIKE wildcards so "50%" is searched literally.
        let escaped = needle
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "%", with: "\\%")
            .replacingOccurrences(of: "_", with: "\\_")
        let observation = ValueObservation.tracking { db in
            try known.flatMap { table -> [SearchRow] in
                let column = Self.searchableTables[table] ?? "name"
                let listColumn = table == "list_item" ? "list_id" : "NULL"
                return try Row.fetchAll(
                    db,
                    sql: """
                    SELECT id, \(column) AS name, \(listColumn) AS list_id, deleted_at FROM \(table)
                    WHERE household_id = ? AND deleted_at IS NULL
                      AND \(column) LIKE '%' || ? || '%' ESCAPE '\\' COLLATE NOCASE
                    """,
                    arguments: [key, escaped]
                ).compactMap { row -> SearchRow? in
                    guard let id = UUID(uuidString: row["id"]) else { return nil }
                    let list: String? = row["list_id"]
                    return SearchRow(
                        table: table, id: id, name: row["name"], listId: list.flatMap { UUID(uuidString: $0) },
                        deletedAt: row["deleted_at"]
                    )
                }
            }
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await rows in observation.values(in: pool) { continuation.yield(rows) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last rows.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Recently deleted (CORE-10)

    /// Soft-deleted rows of `tables` (same closed set as search), re-emitted whenever they change. Newest deleted
    /// first. The screen applies the 30-day window. Finishes at once when the household is not open.
    public func observeDeleted(householdId: UUID, tables: [String]) -> AsyncStream<[DeletedRow]> {
        guard let pool, handle?.householdId == householdId else {
            return AsyncStream { $0.finish() }
        }
        let known = tables.filter { Self.searchableTables[$0] != nil }
        guard !known.isEmpty else {
            return AsyncStream { continuation in
                continuation.yield([])
                continuation.finish()
            }
        }
        let key = householdId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db in
            try known.flatMap { table -> [DeletedRow] in
                let column = Self.searchableTables[table] ?? "name"
                return try Row.fetchAll(
                    db,
                    sql: "SELECT id, \(column) AS name, deleted_at FROM \(table) WHERE household_id = ? AND deleted_at IS NOT NULL",
                    arguments: [key]
                ).compactMap { row -> DeletedRow? in
                    guard let id = UUID(uuidString: row["id"]), let deleted = Self.date(row["deleted_at"]) else { return nil }
                    return DeletedRow(table: table, id: id, name: row["name"], deletedAt: deleted)
                }
            }
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await rows in observation.values(in: pool) { continuation.yield(rows) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last rows.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Puts a soft-deleted row back: clears `deleted_at` and queues the SPEC §6.2 upsert in one transaction, so the
    /// outbox never misses a restore the screen already shows. `data` is partial on purpose and carries an explicit
    /// null (a missing key would mean "unchanged").
    public func restore(householdId: UUID, table: String, id: UUID, now: Date = Date()) throws {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { throw HouseholdDatabaseError.notOpen }
        guard Self.searchableTables[table] != nil else { return }
        let key = householdId.uuidString.lowercased()
        let rowId = id.uuidString.lowercased()
        try pool.write { db in
            try db.execute(
                sql: "UPDATE \(table) SET deleted_at = NULL WHERE id = ? AND household_id = ? AND deleted_at IS NOT NULL",
                arguments: [rowId, key]
            )
            // Nothing was deleted (already restored, or purged): queue nothing.
            guard db.changesCount > 0 else { return }
            let data = String(decoding: try JSONEncoder().encode(RestoreData()), as: UTF8.self)
            try db.execute(
                sql: "INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES (?, ?, 'upsert', ?, ?, ?)",
                arguments: [UUID().uuidString.lowercased(), table, rowId, data, Self.text(now)]
            )
        }
    }

    private struct RestoreData: Encodable {
        enum CodingKeys: String, CodingKey { case deletedAt = "deleted_at" }
        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encodeNil(forKey: .deletedAt)
        }
    }

    // MARK: Notification preferences (CORE-12)

    /// My switches in this household, re-emitted whenever they change. A type with no row is enabled (the caller
    /// applies that default). Finishes at once when the household is not open.
    public func observeNotificationPrefs(householdId: UUID, userId: UUID) -> AsyncStream<[NotificationPrefRow]> {
        guard let pool, handle?.householdId == householdId else {
            return AsyncStream { $0.finish() }
        }
        let household = householdId.uuidString.lowercased()
        let user = userId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db in
            try Row.fetchAll(
                db,
                sql: "SELECT type, enabled FROM notification_pref WHERE household_id = ? AND user_id = ?",
                arguments: [household, user]
            ).map { NotificationPrefRow(userId: userId, householdId: householdId, type: $0["type"], enabled: $0["enabled"]) }
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await rows in observation.values(in: pool) { continuation.yield(rows) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last rows.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// One local transaction: upsert my row and queue the SPEC §6.2 upsert, so the two cannot diverge. The caller kicks
    /// sync. `notification_pref` is not in SPEC's SyncTable yet; SHOP-08-api is to accept it and key the row off `data`.
    public func setNotificationPref(userId: UUID, householdId: UUID, type: String, enabled: Bool, now: Date = Date()) throws {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { throw HouseholdDatabaseError.notOpen }
        let user = userId.uuidString.lowercased()
        let household = householdId.uuidString.lowercased()
        let rowId = NotificationPrefId.rowId(userId: userId, householdId: householdId, type: type).uuidString.lowercased()
        let data = String(
            decoding: try JSONEncoder().encode(PrefData(userId: user, householdId: household, type: type, enabled: enabled)),
            as: UTF8.self
        )
        let stamp = Self.text(now)
        try pool.write { db in
            try db.execute(
                sql: """
                INSERT INTO notification_pref (user_id, household_id, type, enabled) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, household_id, type) DO UPDATE SET enabled = excluded.enabled
                """,
                arguments: [user, household, type, enabled]
            )
            try db.execute(
                sql: "INSERT INTO outbox (id, table_name, op, row_id, data, client_ts) VALUES (?, 'notification_pref', 'upsert', ?, ?, ?)",
                arguments: [UUID().uuidString.lowercased(), rowId, data, stamp]
            )
        }
    }

    private struct PrefData: Encodable {
        let userId: String
        let householdId: String
        let type: String
        let enabled: Bool
        enum CodingKeys: String, CodingKey {
            case userId = "user_id", householdId = "household_id", type, enabled
        }
    }

    // MARK: Attachments (CORE-11)

    /// Live attachments of one entity, re-emitted whenever they change. Finishes at once when that household is not open.
    public func observeAttachments(householdId: UUID, entity: String, entityId: UUID) -> AsyncStream<[Attachment]> {
        guard let pool, handle?.householdId == householdId else {
            return AsyncStream { $0.finish() }
        }
        let key = householdId.uuidString.lowercased()
        let entityKey = entityId.uuidString.lowercased()
        let observation = ValueObservation.tracking { db in
            try Row.fetchAll(
                db,
                sql: """
                SELECT * FROM attachment WHERE household_id = ? AND entity = ? AND entity_id = ? AND deleted_at IS NULL
                ORDER BY created_at, id
                """,
                arguments: [key, entity, entityKey]
            ).compactMap(Self.attachment)
        }
        return AsyncStream { continuation in
            let task = Task {
                do {
                    for try await rows in observation.values(in: pool) { continuation.yield(rows) }
                } catch {
                    // A closed pool ends the observation; the screen keeps its last rows.
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// The same rows `observeAttachments` emits, read once.
    public func attachments(householdId: UUID, entity: String, entityId: UUID) throws -> [Attachment] {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { throw HouseholdDatabaseError.notOpen }
        return try pool.read { db in
            try Row.fetchAll(
                db,
                sql: """
                SELECT * FROM attachment WHERE household_id = ? AND entity = ? AND entity_id = ? AND deleted_at IS NULL
                ORDER BY created_at, id
                """,
                arguments: [householdId.uuidString.lowercased(), entity, entityId.uuidString.lowercased()]
            ).compactMap(Self.attachment)
        }
    }

    /// Stores an attachment after its bytes reached Storage. A row of another household is dropped, like `upsertActivity`.
    public func upsertAttachment(_ row: Attachment) throws {
        let pool = try requirePool()
        guard handle?.householdId == row.householdId else { return }
        try pool.write { db in
            try db.execute(
                sql: """
                INSERT OR REPLACE INTO attachment
                  (id, household_id, entity, entity_id, storage_path, mime, size_bytes, created_by,
                   created_at, updated_at, deleted_at, sync_seq)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                arguments: [
                    row.id.uuidString.lowercased(), row.householdId.uuidString.lowercased(), row.entity,
                    row.entityId.uuidString.lowercased(), row.storagePath, row.mime, row.sizeBytes,
                    row.createdBy?.uuidString.lowercased(), Self.text(row.createdAt), Self.text(row.updatedAt),
                    row.deletedAt.map(Self.text), row.syncSeq,
                ]
            )
        }
    }

    /// Writes the bytes under the app root and queues the pick, in that order, so a queued row always has its file.
    @discardableResult
    public func enqueueAttachmentUpload(_ upload: AttachmentUpload, bytes: Data) throws -> AttachmentUpload {
        let pool = try requirePool()
        guard handle?.householdId == upload.householdId else { throw HouseholdDatabaseError.notOpen }
        var queued = upload
        queued.filePath = "attachments/\(upload.householdId.uuidString.lowercased())/\(upload.id.uuidString.lowercased()).bin"
        let file = root.appendingPathComponent(queued.filePath)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: file, options: .atomic)
        try pool.write { db in
            try db.execute(
                sql: """
                INSERT OR REPLACE INTO attachment_outbox
                  (id, household_id, entity, entity_id, filename, mime, size, file_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                arguments: [
                    queued.id.uuidString.lowercased(), queued.householdId.uuidString.lowercased(), queued.entity,
                    queued.entityId.uuidString.lowercased(), queued.filename, queued.mime, queued.size, queued.filePath,
                    Self.text(queued.createdAt),
                ]
            )
        }
        return queued
    }

    /// Queued picks of the open household in creation order, optionally only one entity's.
    public func pendingAttachmentUploads(householdId: UUID, entity: String? = nil, entityId: UUID? = nil) throws -> [AttachmentUpload] {
        let pool = try requirePool()
        guard handle?.householdId == householdId else { throw HouseholdDatabaseError.notOpen }
        let key = householdId.uuidString.lowercased()
        return try pool.read { db in
            try Row.fetchAll(
                db, sql: "SELECT * FROM attachment_outbox WHERE household_id = ? ORDER BY created_at, id", arguments: [key]
            ).compactMap(Self.upload).filter { row in
                (entity == nil || row.entity == entity) && (entityId == nil || row.entityId == entityId)
            }
        }
    }

    /// The bytes a queued pick was saved with.
    public func attachmentBytes(of upload: AttachmentUpload) throws -> Data {
        try Data(contentsOf: root.appendingPathComponent(upload.filePath))
    }

    /// Drops a queued pick and its file, after it uploaded.
    public func removeAttachmentUpload(id: UUID) throws {
        let pool = try requirePool()
        let key = id.uuidString.lowercased()
        let path = try pool.write { db -> String? in
            let path = try String.fetchOne(db, sql: "SELECT file_path FROM attachment_outbox WHERE id = ?", arguments: [key])
            try db.execute(sql: "DELETE FROM attachment_outbox WHERE id = ?", arguments: [key])
            return path
        }
        if let path { try? FileManager.default.removeItem(at: root.appendingPathComponent(path)) }
    }

    private static func attachment(_ row: Row) -> Attachment? {
        guard
            let id = UUID(uuidString: row["id"]), let householdId = UUID(uuidString: row["household_id"]),
            let entityId = UUID(uuidString: row["entity_id"]),
            let created = date(row["created_at"]), let updated = date(row["updated_at"])
        else { return nil }
        let creator: String? = row["created_by"]
        let deleted: String? = row["deleted_at"]
        return Attachment(
            id: id, householdId: householdId, entity: row["entity"], entityId: entityId, storagePath: row["storage_path"],
            mime: row["mime"], sizeBytes: row["size_bytes"], createdBy: creator.flatMap { UUID(uuidString: $0) },
            createdAt: created, updatedAt: updated, deletedAt: deleted.flatMap(date), syncSeq: row["sync_seq"]
        )
    }

    private static func upload(_ row: Row) -> AttachmentUpload? {
        guard
            let id = UUID(uuidString: row["id"]), let householdId = UUID(uuidString: row["household_id"]),
            let entityId = UUID(uuidString: row["entity_id"]), let created = date(row["created_at"])
        else { return nil }
        return AttachmentUpload(
            id: id, householdId: householdId, entity: row["entity"], entityId: entityId, filename: row["filename"],
            mime: row["mime"], size: row["size"], filePath: row["file_path"], createdAt: created
        )
    }

    /// Test seam: the module stories own the real writes into these tables.
    func insertSearchRowForTesting(_ row: SearchRow, householdId: UUID) throws {
        let pool = try requirePool()
        guard let column = Self.searchableTables[row.table] else { return }
        try pool.write { db in
            if row.table == "list_item" {
                try db.execute(
                    sql: "INSERT OR REPLACE INTO list_item (id, household_id, name, list_id, deleted_at) VALUES (?, ?, ?, ?, ?)",
                    arguments: [row.id.uuidString.lowercased(), householdId.uuidString.lowercased(), row.name,
                                row.listId?.uuidString.lowercased(), row.deletedAt]
                )
            } else {
                try db.execute(
                    sql: "INSERT OR REPLACE INTO \(row.table) (id, household_id, \(column), deleted_at) VALUES (?, ?, ?, ?)",
                    arguments: [row.id.uuidString.lowercased(), householdId.uuidString.lowercased(), row.name, row.deletedAt]
                )
            }
        }
    }

    private static let dateStyle = Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt)

    private static func text(_ date: Date) -> String { date.formatted(dateStyle) }

    private static func date(_ text: String) -> Date? {
        (try? Date(text, strategy: dateStyle)) ?? (try? Date(text, strategy: Date.ISO8601FormatStyle(timeZone: .gmt)))
    }

    private static func activity(_ row: Row) -> Activity? {
        guard
            let id = UUID(uuidString: row["id"]), let householdId = UUID(uuidString: row["household_id"]),
            let module = Activity.Module(rawValue: row["module"]), let entityId = UUID(uuidString: row["entity_id"]),
            let created = date(row["created_at"]), let updated = date(row["updated_at"])
        else { return nil }
        let actor: String? = row["actor_id"]
        let deleted: String? = row["deleted_at"]
        return Activity(
            id: id, householdId: householdId, actorId: actor.flatMap { UUID(uuidString: $0) }, module: module,
            entity: row["entity"], entityId: entityId, action: row["action"], summary: row["summary"],
            createdAt: created, updatedAt: updated, deletedAt: deleted.flatMap(date), syncSeq: row["sync_seq"]
        )
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

    /// Test seam: the queued writes, oldest first.
    func outboxForTesting() throws -> [(id: String, table: String, op: String, rowId: String, data: String)] {
        let pool = try requirePool()
        return try pool.read { db in
            try Row.fetchAll(db, sql: "SELECT id, table_name, op, row_id, data FROM outbox ORDER BY seq").map {
                (id: $0["id"], table: $0["table_name"], op: $0["op"], rowId: $0["row_id"], data: $0["data"])
            }
        }
    }

    private func requirePool() throws -> DatabasePool {
        guard let pool else { throw HouseholdDatabaseError.notOpen }
        return pool
    }
}
