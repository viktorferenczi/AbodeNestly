import Contract
import Foundation
import Sync

/// Why a store name cannot be saved.
public enum StoreNameProblem: Equatable, Sendable {
    case empty
    case duplicate(String)

    public var message: String {
        switch self {
        case .empty: "Give the store a name"
        case .duplicate(let name): "You already have \(name)"
        }
    }
}

/// DESIGN C4 logic, kept apart from the views so it is table-testable. Web twin: `web/src/shopping/Stores.ts`.
public enum Stores {
    /// By name, ignoring case; the id breaks ties so the order never flickers.
    public static func sorted(_ rows: [StoreRow]) -> [StoreRow] {
        rows.sorted {
            switch $0.name.localizedCaseInsensitiveCompare($1.name) {
            case .orderedAscending: true
            case .orderedDescending: false
            case .orderedSame: $0.id.uuidString < $1.id.uuidString
            }
        }
    }

    /// "learned 3 items"; nothing when no item has a learned position yet.
    public static func learnedLabel(_ count: Int) -> String? {
        count <= 0 ? nil : "learned \(count) \(count == 1 ? "item" : "items")"
    }

    /// Trimmed and compared ignoring case, against live stores only. `exceptId` leaves one store out (a future rename).
    public static func validate(name: String, stores: [StoreRecord], exceptId: UUID? = nil) -> StoreNameProblem? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .empty }
        let twin = stores.first { $0.id != exceptId && $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(trimmed) == .orderedSame }
        return twin.map { .duplicate($0.name) }
    }

    /// The `store` row for a new store; the server owns `updated_at` and `sync_seq`, so the local row carries placeholders
    /// until the next pull replaces it (same shape as `ListsModel.create`).
    public static func row(id: UUID, householdId: UUID, name: String, now: Date) -> [String: JSONValue] {
        let stamp = now.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: .gmt))
        return [
            "id": .string(id.uuidString.lowercased()), "household_id": .string(householdId.uuidString.lowercased()),
            "name": .string(name.trimmingCharacters(in: .whitespacesAndNewlines)), "deleted_at": .null,
            "created_at": .string(stamp), "updated_at": .string(stamp), "sync_seq": .int(0),
        ]
    }
}

/// A store delete waiting out its undo window.
public struct PendingStoreDelete: Equatable, Identifiable, Sendable {
    public var id: UUID
    public var name: String

    public var message: String { "Deleted \(name)" }
}
