import Observation
import SwiftUI
import Sync
import UI

/// DESIGN C2 store picker: "No store" and the household's stores with a checkmark, an inline "New store" field that
/// creates and selects in one step, and "Manage stores". Save is the only thing that writes the list's `store_id`.
struct StorePickerSheet: View {
    @Observable
    @MainActor
    final class Draft {
        var selection: UUID?
        var newName = ""
        var problem: StoreNameProblem?
        var busy = false
        var failed = false
        /// Stores created in this sheet, kept selectable before the observation has caught up.
        var created: [StoreRecord] = []

        init(selection: UUID? = nil) { self.selection = selection }
    }

    let stores: [StoreRecord]
    /// What the list has now; the selection falls back to it when the chosen store disappears.
    let current: UUID?
    let onCreate: (String) async -> UUID?
    let onSave: (UUID?) async -> Bool
    let onManage: () -> Void
    let onClose: () -> Void
    @Bindable private var draft: Draft

    init(
        stores: [StoreRecord], current: UUID?, draft: Draft? = nil, onCreate: @escaping (String) async -> UUID?,
        onSave: @escaping (UUID?) async -> Bool, onManage: @escaping () -> Void, onClose: @escaping () -> Void
    ) {
        self.stores = stores
        self.current = current
        self.draft = draft ?? Draft(selection: current)
        self.onCreate = onCreate
        self.onSave = onSave
        self.onManage = onManage
        self.onClose = onClose
    }

    /// Live stores plus the ones just created here, by name.
    private var choices: [StoreRecord] {
        let known = Set(stores.map(\.id))
        return (stores + draft.created.filter { !known.contains($0.id) })
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    /// A store deleted elsewhere while the sheet is open drops out; the selection falls back to the list's value.
    private var selected: UUID? {
        guard let id = draft.selection else { return nil }
        if choices.contains(where: { $0.id == id }) { return id }
        return current.flatMap { fallback in choices.contains { $0.id == fallback } ? fallback : nil }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    choiceRow(nil, name: "No store", id: "storepicker.none")
                    ForEach(choices) { store in choiceRow(store.id, name: store.name, id: "storepicker.row") }
                }
                Section("New store") {
                    HStack {
                        TextField("Store name", text: $draft.newName)
                            .accessibilityIdentifier("storepicker.newname")
                        Button("Add") { add() }
                            .disabled(draft.newName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.busy)
                            .accessibilityIdentifier("storepicker.add")
                    }
                    if let problem = draft.problem {
                        Text(problem.message)
                            .foregroundStyle(Tokens.danger)
                            .accessibilityIdentifier("storepicker.problem")
                    }
                }
                if draft.failed {
                    Text("Could not save the change. Try again.")
                        .foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("storepicker.error")
                }
                Section {
                    Button("Manage stores", action: onManage)
                        .accessibilityIdentifier("storepicker.manage")
                }
            }
            .navigationTitle("Store")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose).accessibilityIdentifier("storepicker.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(draft.busy)
                        .accessibilityIdentifier("storepicker.save")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func choiceRow(_ id: UUID?, name: String, id identifier: String) -> some View {
        Button {
            draft.selection = id
        } label: {
            HStack {
                Text(name).foregroundStyle(Tokens.text)
                Spacer()
                if selected == id { Image(systemName: "checkmark").foregroundStyle(Tokens.accent) }
            }
            .frame(minHeight: Tokens.minTouchTarget)
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selected == id ? .isSelected : [])
        .accessibilityIdentifier(identifier)
    }

    private func add() {
        if let problem = Stores.validate(name: draft.newName, stores: choices) {
            draft.problem = problem
            return
        }
        draft.problem = nil
        draft.busy = true
        draft.failed = false
        let name = draft.newName.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            if let id = await onCreate(name) {
                draft.created.append(StoreRecord(id: id, name: name))
                draft.selection = id
                draft.newName = ""
            } else {
                draft.failed = true
            }
            draft.busy = false
        }
    }

    private func save() {
        let choice = selected
        guard choice != current else {
            onClose()
            return
        }
        draft.busy = true
        draft.failed = false
        Task {
            let saved = await onSave(choice)
            draft.busy = false
            draft.failed = !saved
        }
    }
}
