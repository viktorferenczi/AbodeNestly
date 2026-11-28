import Foundation
import Sync

/// What the assistant shortcut did, and so what it says back.
public enum AssistantResult: Equatable, Sendable {
    case added(name: String, list: String)
    case noItem
    case shoppingOff
    case noHousehold
    case noList
    case failed

    /// The reply Siri speaks and shows (SHOP-15). Pure, so the wording is table-tested.
    public var reply: String {
        switch self {
        case .added(let name, let list): "Added \(name) to \(list)."
        case .noItem: "I didn't catch an item. Nothing was added."
        case .shoppingOff: "Shopping is turned off for this household. Nothing was added."
        case .noHousehold: "Open AbodeNestly and sign in first."
        case .noList: "There's no shopping list to add to."
        case .failed: "Couldn't add that. Try again in the app."
        }
    }
}

/// SHOP-15 logic, kept apart from the App Intent so it is testable without the system. The write is the one Quick add
/// uses (`QuickAdd.write`): the local row and its outbox entry in one transaction. The caller kicks sync afterwards.
public enum AssistantShortcut {
    /// The default list's name (CORE-02).
    public static let defaultListName = "Groceries"

    /// Adds what the person said to the default list of `householdId`. `householdId` is `nil` when nobody is signed in or
    /// no household was opened yet. Nothing is written for any result but `.added`.
    public static func add(
        text: String, database: HouseholdDatabase, householdId: UUID?, userId: UUID?, now: Date = Date(),
        makeId: () -> UUID = { UUID() }
    ) async -> AssistantResult {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let spoken = VoiceAdd.parse(trimmed) else { return .noItem }
        guard let householdId else { return .noHousehold }
        do {
            try await database.open(householdId: householdId)
            // A household row that has not been pulled yet is trusted: the server rejects a write with `module_disabled`.
            if try await database.shoppingModuleEnabled(householdId: householdId) == false { return .shoppingOff }
            guard let list = pickList(try await database.shoppingListRecords(householdId: householdId)) else { return .noList }
            try await QuickAdd.write(
                database: database, householdId: householdId, listId: list.id, name: spoken.name, userId: userId, now: now,
                id: makeId(), quantity: spoken.quantity, unit: spoken.unit
            )
            return .added(name: spoken.name, list: list.name)
        } catch {
            return .failed
        }
    }

    /// "Groceries" (any case) if the household has one, else its oldest list; archived lists and templates never.
    public static func pickList(_ lists: [ListRecord]) -> ListRecord? {
        let active = lists.filter { $0.archivedAt == nil && !$0.isTemplate }
            .sorted { ($0.createdAt ?? "", $0.id.uuidString) < ($1.createdAt ?? "", $1.id.uuidString) }
        let groceries = active.first {
            $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(defaultListName) == .orderedSame
        }
        return groceries ?? active.first
    }
}
