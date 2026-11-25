import Contract
import Foundation
import Sync
import UI

/// What the sheet edits, as the person typed it.
public struct ItemForm: Equatable, Sendable {
    public var name = ""
    public var quantityText = ""
    public var unit = ""
    public var note = ""

    public init(name: String = "", quantityText: String = "", unit: String = "", note: String = "") {
        self.name = name
        self.quantityText = quantityText
        self.unit = unit
        self.note = note
    }

    public init(_ item: ListItemRecord) {
        self.init(name: item.name, quantityText: QuantityText.text(item.quantity), unit: item.unit ?? "", note: item.note ?? "")
    }
}

/// What gets stored: trimmed, blanks as nil.
public struct ItemFields: Equatable, Sendable {
    public var name: String
    public var quantity: Double?
    public var unit: String?
    public var note: String?

    public init(name: String, quantity: Double? = nil, unit: String? = nil, note: String? = nil) {
        self.name = name
        self.quantity = quantity
        self.unit = unit
        self.note = note
    }
}

/// What the sheet is open on.
public enum ItemSheetState: Equatable, Identifiable, Sendable {
    case new(prefill: String)
    case edit(ListItemRecord)

    public var id: String {
        switch self {
        case .new: "new"
        case .edit(let item): item.id.uuidString
        }
    }
}

/// SHOP-03 logic, kept apart from the view so it is table-testable. Web twin: `web/src/shopping/ItemSheet.ts`.
public enum ItemSheetLogic {
    public static let unitPresets = ["pcs", "g", "kg", "ml", "l", "pack", "bottle", "can"]

    /// `nil` when the form cannot be saved (blank name, bad quantity).
    public static func fields(_ form: ItemForm) -> ItemFields? {
        let name = form.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, case .valid(let quantity) = QuantityText.parse(form.quantityText) else { return nil }
        return ItemFields(name: name, quantity: quantity, unit: blankToNil(form.unit), note: blankToNil(form.note))
    }

    /// True when the quantity text is non-blank and not a valid quantity (drives the inline message).
    public static func quantityInvalid(_ form: ItemForm) -> Bool { QuantityText.parse(form.quantityText) == .invalid }

    /// "2 l", "0.5 kg", "3". A unit without a quantity shows nothing on the row.
    public static func formatQty(_ quantity: Double?, _ unit: String?) -> String {
        guard let quantity else { return "" }
        let number = quantity.formatted(.number.precision(.fractionLength(0...3)))
        guard let unit, !unit.isEmpty else { return number }
        return "\(number) \(unit)"
    }

    /// Presets plus the units this household already uses, without duplicates (case-insensitive).
    public static func unitSuggestions(history: [ListItemRecord]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for unit in unitPresets + history.filter({ $0.deletedAt == nil }).map({ $0.unit ?? "" }) {
            let t = unit.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !t.isEmpty, seen.insert(t.lowercased()).inserted else { continue }
            out.append(t)
        }
        return out
    }

    /// Only the columns that changed, so editing a note does not undo a check-off made on another device. A name
    /// change also returns the local-only `name_key`, which keeps suggestions matching until the next pull.
    public static func changes(
        before: ListItemRecord, after: ItemFields
    ) -> (fields: [String: JSONValue], localOnly: [String: JSONValue]) {
        var fields: [String: JSONValue] = [:]
        if after.name != before.name { fields["name"] = .string(after.name) }
        if after.quantity != before.quantity { fields["quantity"] = after.quantity.map { .double($0) } ?? .null }
        if after.unit != before.unit { fields["unit"] = after.unit.map { .string($0) } ?? .null }
        if after.note != before.note { fields["note"] = after.note.map { .string($0) } ?? .null }
        let local: [String: JSONValue] = fields["name"] == nil ? [:] : ["name_key": .string(NameKey.make(after.name))]
        return (fields, local)
    }

    private static func blankToNil(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
