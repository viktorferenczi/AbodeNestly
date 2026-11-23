import SwiftUI
import Sync
import UI

/// DESIGN C1 sheet: name and store. The Template switch waits for SHOP-11-ios. `onSave` returns whether the list was
/// stored; the sheet stays open with a message when it was not.
struct NewListSheet: View {
    let stores: [StoreRecord]
    let onSave: (String, UUID?) async -> Bool
    let onClose: () -> Void
    @State private var name = ""
    @State private var storeId: UUID?
    @State private var busy = false
    @State private var failed = false

    private var valid: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                    .accessibilityIdentifier("newlist.name")
                if failed {
                    Text("Could not save the list. Try again.")
                        .foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("newlist.error")
                }
                if !stores.isEmpty {
                    Picker("Store", selection: $storeId) {
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
                        guard valid, !busy else { return }
                        busy = true
                        failed = false
                        Task {
                            let saved = await onSave(name, storeId)
                            busy = false
                            failed = !saved
                        }
                    }
                    .disabled(!valid || busy)
                    .accessibilityIdentifier("newlist.save")
                }
            }
        }
        .presentationDetents([.medium])
    }
}
