import SwiftUI
import Sync
import UI

/// DESIGN C2 sheet "Add all to…" (SHOP-11): picks the list that receives a template's items. `lists` are the active,
/// non-template lists, oldest first; with none it says "Make a list first". `onPick` stores the copy and returns its
/// result; the sheet closes unless the copy failed, which it says in place.
struct AddAllSheet: View {
    let lists: [ListRecord]
    let onPick: (ListRecord) async -> AddAllResult
    let onClose: () -> Void
    @State private var busy = false
    @State private var failed = false

    var body: some View {
        NavigationStack {
            List {
                if lists.isEmpty {
                    Text("Make a list first")
                        .foregroundStyle(Tokens.text2)
                        .accessibilityIdentifier("addall.none")
                } else {
                    ForEach(lists) { list in
                        Button { pick(list) } label: {
                            Text(list.name)
                                .foregroundStyle(Tokens.text)
                                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .disabled(busy)
                        .accessibilityLabel(list.name)
                        .accessibilityIdentifier("addall.row")
                    }
                }
                if failed {
                    Text("Could not add the items. Try again.")
                        .foregroundStyle(Tokens.danger)
                        .accessibilityIdentifier("addall.error")
                }
            }
            .navigationTitle("Add all to…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onClose).accessibilityIdentifier("addall.cancel")
                }
            }
        }
        .presentationDetents([.medium])
        // Focus stays on the list just tapped, so say the failure (web: `role="alert"`).
        .onChange(of: failed) { _, isFailed in
            if isFailed { AccessibilityNotification.Announcement("Could not add the items. Try again.").post() }
        }
    }

    private func pick(_ list: ListRecord) {
        guard !busy else { return }
        busy = true
        failed = false
        Task {
            let result = await onPick(list)
            busy = false
            if result == .failed { failed = true } else { onClose() }
        }
    }
}
