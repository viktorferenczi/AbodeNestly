import Observation
import SwiftUI
import Sync
import UI

/// DESIGN C4 sheet: one name field. `onSave` returns whether the store was stored; the sheet stays open with a message
/// when it was not. A duplicate or empty name shows its message inline and blocks Save.
struct StoreSheet: View {
    /// The fields and the save state live in an object so a component test can fill them in (a `@State` outside a
    /// hosted view is a constant).
    @Observable
    @MainActor
    final class Draft {
        var name = ""
        var busy = false
        var failed = false
    }

    let stores: [StoreRecord]
    let onSave: (String) async -> Bool
    let onClose: () -> Void
    @Bindable private var draft: Draft

    init(stores: [StoreRecord], draft: Draft = Draft(), onSave: @escaping (String) async -> Bool, onClose: @escaping () -> Void) {
        self.stores = stores
        self.draft = draft
        self.onSave = onSave
        self.onClose = onClose
    }

    /// Empty is not nagged about until something was typed.
    private var problem: StoreNameProblem? {
        let typed = !draft.name.isEmpty
        let found = Stores.validate(name: draft.name, stores: stores)
        return found == .empty && !typed ? nil : found
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $draft.name)
                    .accessibilityIdentifier("storesheet.name")
                if let problem {
                    Text(problem.message)
                        .foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("storesheet.problem")
                }
                if draft.failed {
                    Text("Could not save the store. Try again.")
                        .foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("storesheet.error")
                }
            }
            .navigationTitle("New store")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose).accessibilityIdentifier("storesheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard Stores.validate(name: draft.name, stores: stores) == nil, !draft.busy else { return }
                        draft.busy = true
                        draft.failed = false
                        Task {
                            let saved = await onSave(draft.name)
                            draft.busy = false
                            draft.failed = !saved
                        }
                    }
                    .disabled(Stores.validate(name: draft.name, stores: stores) != nil || draft.busy)
                    .accessibilityIdentifier("storesheet.save")
                }
            }
        }
        .presentationDetents([.medium])
    }
}
