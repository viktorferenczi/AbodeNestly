import Foundation
import Sync

/// One row of the C1 overview.
public struct ListRow: Equatable, Identifiable, Sendable {
    public var id: UUID
    public var name: String
    /// The list's store when it points to a live store.
    public var storeName: String?
    /// "3 of 12" (checked of live items), or "No items".
    public var count: String
}

public struct ListSections: Equatable, Sendable {
    public var lists: [ListRow] = []
    public var templates: [ListRow] = []
    public var archived: [ListRow] = []

    public var isEmpty: Bool { lists.isEmpty && templates.isEmpty && archived.isEmpty }
}

/// DESIGN C1 logic, kept apart from the view so it is table-testable. Web twin: `web/src/shopping/Lists.ts`.
public enum Lists {
    /// How long a swipe waits before it is written; Undo inside the window cancels it (DESIGN A6).
    public static let undoWindow: Duration = .seconds(5)

    /// Lists and Templates: oldest first, so the default list leads and new ones land at the bottom. Archived: newest
    /// archived first. `hidden` are lists with a swipe still inside its undo window.
    public static func sections(_ snapshot: ShoppingListsSnapshot, hidden: Set<UUID> = []) -> ListSections {
        let storeNames = Dictionary(snapshot.stores.map { ($0.id, $0.name) }) { first, _ in first }
        func row(_ list: ListRecord) -> ListRow {
            let count = snapshot.counts[list.id]
            return ListRow(
                id: list.id, name: list.name, storeName: list.storeId.flatMap { storeNames[$0] },
                count: count.map { "\($0.checked) of \($0.all)" } ?? "No items"
            )
        }
        let live = snapshot.lists.filter { !hidden.contains($0.id) }
        // Text order is time order (millisecond UTC); the id breaks ties so the order never flickers.
        let byCreated: (ListRecord, ListRecord) -> Bool = {
            ($0.createdAt ?? "", $0.id.uuidString) < ($1.createdAt ?? "", $1.id.uuidString)
        }
        let active = live.filter { $0.archivedAt == nil }.sorted(by: byCreated)
        return ListSections(
            lists: active.filter { !$0.isTemplate }.map(row),
            templates: active.filter(\.isTemplate).map(row),
            archived: live.filter { $0.archivedAt != nil }
                .sorted { ($0.archivedAt ?? "") > ($1.archivedAt ?? "") }.map(row)
        )
    }
}
