import Observation
import SwiftUI
import Sync
import UI

/// Reference-typed form state so edits are visible to tests without hosting the view.
@MainActor @Observable
final class ItemFormBox {
    var form: ItemForm
    init(_ form: ItemForm) { self.form = form }
}

/// DESIGN C3 (SHOP-03 slice): name, quantity with the stepper, unit with suggestion chips, note; Delete when editing.
/// Swiping the sheet down is Cancel. Web twin: `web/src/shopping/ItemSheet.tsx`.
struct ItemSheetView: View {
    let state: ItemSheetState
    let unitSuggestions: [String]
    let onSave: (ItemFields) async -> Bool
    let onDelete: (ListItemRecord) -> Void
    let onCancel: () -> Void

    @State private var box: ItemFormBox
    @State private var saving = false

    init(
        state: ItemSheetState, unitSuggestions: [String], onSave: @escaping (ItemFields) async -> Bool,
        onDelete: @escaping (ListItemRecord) -> Void, onCancel: @escaping () -> Void
    ) {
        self.state = state
        self.unitSuggestions = unitSuggestions
        self.onSave = onSave
        self.onDelete = onDelete
        self.onCancel = onCancel
        switch state {
        case .new(let prefill): _box = State(initialValue: ItemFormBox(ItemForm(name: prefill)))
        case .edit(let item): _box = State(initialValue: ItemFormBox(ItemForm(item)))
        }
    }

    private var isEdit: Bool {
        if case .edit = state { return true }
        return false
    }

    private var form: ItemForm { box.form }
    private var fields: ItemFields? { ItemSheetLogic.fields(form) }

    /// Chips narrow as the person types; an exact match needs no chip.
    private var chips: [String] {
        let typed = form.unit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return unitSuggestions.filter { typed.isEmpty || ($0.lowercased().hasPrefix(typed) && $0.lowercased() != typed) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Name", text: $box.form.name)
                        .accessibilityIdentifier("item.name")
                }
                Section("Quantity") {
                    QuantityStepper(text: $box.form.quantityText)
                    if ItemSheetLogic.quantityInvalid(form) {
                        Text("Enter a number above 0")
                            .font(.footnote)
                            .foregroundStyle(Tokens.danger)
                            .accessibilityIdentifier("item.qtyError")
                    }
                    TextField("Unit", text: $box.form.unit)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("item.unit")
                    if !chips.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(chips, id: \.self) { unit in
                                    Button(unit) { box.form.unit = unit }
                                        .buttonStyle(.bordered)
                                        .tint(Tokens.accent)
                                        .frame(minHeight: Tokens.minTouchTarget)
                                        .accessibilityIdentifier("item.unit.suggestion.\(unit)")
                                }
                            }
                        }
                    }
                }
                Section("Note") {
                    TextField("Note", text: $box.form.note, axis: .vertical)
                        .lineLimit(2...4)
                        .accessibilityIdentifier("item.note")
                }
                if case .edit(let item) = state {
                    Section {
                        Button("Delete item", role: .destructive) { onDelete(item) }
                            .frame(minHeight: Tokens.minTouchTarget)
                            .accessibilityIdentifier("item.delete")
                    }
                }
            }
            .navigationTitle(isEdit ? "Edit item" : "New item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel).accessibilityIdentifier("item.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let fields else { return }
                        saving = true
                        Task {
                            // A failed write keeps the sheet open with the input as typed.
                            if await onSave(fields) { onCancel() }
                            saving = false
                        }
                    }
                    .disabled(fields == nil || saving)
                    .accessibilityIdentifier("item.save")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("item.sheet")
    }
}
