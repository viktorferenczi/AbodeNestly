import Observation
import SwiftUI
import Sync
import UI

/// DESIGN C1 sheet: name and store. The Template switch waits for SHOP-11-ios. `onSave` returns whether the list was
/// stored; the sheet stays open with a message when it was not.
struct NewListSheet: View {
    /// The fields and the save state live in an object so a component test can fill them in (a `@State` outside a
    /// hosted view is a constant).
    @Observable
    @MainActor
    final class Draft {
        var name = ""
        var storeId: UUID?
        var busy = false
        var failed = false
        var valid: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    let stores: [StoreRecord]
    let onSave: (String, UUID?) async -> Bool
    let onClose: () -> Void
    @Bindable private var draft: Draft

    init(
        stores: [StoreRecord], draft: Draft = Draft(), onSave: @escaping (String, UUID?) async -> Bool,
        onClose: @escaping () -> Void
    ) {
        self.stores = stores
        self.draft = draft
        self.onSave = onSave
        self.onClose = onClose
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $draft.name)
                    .accessibilityIdentifier("newlist.name")
                if draft.failed {
                    Text("Could not save the list. Try again.")
                        .foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("newlist.error")
                }
                if !stores.isEmpty {
                    Picker("Store", selection: $draft.storeId) {
                        Text("No store").tag(UUID?.none)
                        ForEach(stores) { store in Text(store.name).tag(UUID?.some(store.id)) }
                    }
                    .accessibilityIdentifier("newlist.store")
                }
            }
            .navigationTitle("New list")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose).accessibilityIdentifier("newlist.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard draft.valid, !draft.busy else { return }
                        draft.busy = true
                        draft.failed = false
                        Task {
                            let saved = await onSave(draft.name, draft.storeId)
                            draft.busy = false
                            draft.failed = !saved
                        }
                    }
                    .disabled(!draft.valid || draft.busy)
                    .accessibilityIdentifier("newlist.save")
                }
            }
        }
        .presentationDetents([.medium])
    }
}
