import Contract
import Foundation

// The /sync request and response shapes (SPEC §6.2-6.3). The generated Contract has no usable types for them
// (docs/openapi.yaml types `changes` and `mutations` loosely), so they are written here, next to the loop that
// uses them, on top of the generated `JSONValue`.

/// One queued write in the SPEC §6.2 envelope.
public struct SyncMutation: Codable, Sendable, Equatable {
    public var id: String
    public var table: String
    public var op: String
    public var rowId: String
    /// Absent for `delete`.
    public var data: [String: JSONValue]?
    public var clientTs: String

    public init(id: String, table: String, op: String, rowId: String, data: [String: JSONValue]?, clientTs: String) {
        self.id = id
        self.table = table
        self.op = op
        self.rowId = rowId
        self.data = data
        self.clientTs = clientTs
    }

    enum CodingKeys: String, CodingKey {
        case id, table, op, data
        case rowId = "row_id"
        case clientTs = "client_ts"
    }
}

public struct SyncPushBody: Codable, Sendable, Equatable {
    public var householdId: String
    public var mutations: [SyncMutation]

    public init(householdId: String, mutations: [SyncMutation]) {
        self.householdId = householdId
        self.mutations = mutations
    }

    enum CodingKeys: String, CodingKey {
        case householdId = "household_id"
        case mutations
    }
}

/// Why the server turned a mutation down. A reason this app does not know yet decodes to `.unknown`, so a newer server never breaks the loop.
public enum SyncRejectionReason: Sendable, Equatable, Codable {
    case rlsDenied, validation, unknownTable, moduleDisabled
    case unknown(String)

    public init(rawValue: String) {
        switch rawValue {
        case "rls_denied": self = .rlsDenied
        case "validation": self = .validation
        case "unknown_table": self = .unknownTable
        case "module_disabled": self = .moduleDisabled
        default: self = .unknown(rawValue)
        }
    }

    public var rawValue: String {
        switch self {
        case .rlsDenied: "rls_denied"
        case .validation: "validation"
        case .unknownTable: "unknown_table"
        case .moduleDisabled: "module_disabled"
        case .unknown(let value): value
        }
    }

    public init(from decoder: Decoder) throws {
        self.init(rawValue: try decoder.singleValueContainer().decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

public struct SyncRejection: Codable, Sendable, Equatable {
    public var id: String
    public var reason: SyncRejectionReason
    public var detail: String

    public init(id: String, reason: SyncRejectionReason, detail: String) {
        self.id = id
        self.reason = reason
        self.detail = detail
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        reason = try container.decode(SyncRejectionReason.self, forKey: .reason)
        detail = try container.decodeIfPresent(String.self, forKey: .detail) ?? ""
    }

    enum CodingKeys: String, CodingKey { case id, reason, detail }
}

public struct SyncPushResult: Codable, Sendable, Equatable {
    public var applied: [String]
    public var rejected: [SyncRejection]

    public init(applied: [String], rejected: [SyncRejection] = []) {
        self.applied = applied
        self.rejected = rejected
    }
}

/// One page of `GET /sync`: rows per table (`to_jsonb`, so timestamps are Postgres' microsecond `+00:00` text).
public struct SyncPullPage: Codable, Sendable, Equatable {
    public var cursor: Int
    public var hasMore: Bool
    public var changes: [String: [[String: JSONValue]]]

    public init(cursor: Int, hasMore: Bool, changes: [String: [[String: JSONValue]]] = [:]) {
        self.cursor = cursor
        self.hasMore = hasMore
        self.changes = changes
    }

    enum CodingKeys: String, CodingKey {
        case cursor
        case hasMore = "has_more"
        case changes
    }
}
