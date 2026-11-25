import Contract
import Foundation
import Sync

/// SHOP-04 logic, kept apart from the model so it is table-testable. Web twin: `web/src/shopping/CheckOff.ts`.
public enum CheckOff {
    /// The three columns a check-off sets; `now` is the millisecond UTC text.
    public static func checkFields(userId: UUID?, now: String) -> [String: JSONValue] {
        ["checked": .bool(true), "checked_at": .string(now), "checked_by": userId.map { .string($0.uuidString.lowercased()) } ?? .null]
    }

    /// Unchecking puts all three back.
    public static func uncheckFields() -> [String: JSONValue] {
        ["checked": .bool(false), "checked_at": .null, "checked_by": .null]
    }

    /// The all-checked state (AC4): nothing left to buy and nothing about to be checked, but something checked.
    public static func allChecked(unchecked: Int, pending: Int, checked: Int) -> Bool {
        unchecked == 0 && pending == 0 && checked > 0
    }

    /// Newest check-off first; a row without `checkedAt` falls back to `createdAt`, then the id, so the order is stable.
    public static func newestFirst(_ a: ListItemRecord, _ b: ListItemRecord) -> Bool {
        (a.checkedAt ?? a.createdAt ?? "", a.id.uuidString) > (b.checkedAt ?? b.createdAt ?? "", b.id.uuidString)
    }
}

/// An action inside its undo window. The change is written when the window ends, so Undo has nothing to take back.
public enum PendingAction: Equatable, Identifiable, Sendable {
    case delete(id: UUID, name: String)
    case check(id: UUID, name: String)
    case clear(id: UUID, itemIds: [UUID])

    public var id: UUID {
        switch self {
        case .delete(let id, _), .check(let id, _), .clear(let id, _): id
        }
    }

    public var message: String {
        switch self {
        case .delete(_, let name): "Deleted \(name)"
        case .check(_, let name): "Checked \(name)"
        case .clear(_, let ids): ids.count == 1 ? "Cleared 1 item" : "Cleared \(ids.count) items"
        }
    }

    /// The item rows this action hides from the list. A pending check hides nothing: the row shows ticked in place.
    var hiddenIds: [UUID] {
        switch self {
        case .delete(let id, _): [id]
        case .check: []
        case .clear(_, let ids): ids
        }
    }
}
