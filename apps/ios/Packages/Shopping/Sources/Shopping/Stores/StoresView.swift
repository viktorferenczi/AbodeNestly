import SwiftUI
import Sync
import UI

/// DESIGN C4. States: empty ("No stores yet"), populated (name, "learned N items"), offline (a note; the screen works
/// the same). Swipe left deletes with a 5 s undo; "+" opens the new-store sheet. A row tap does nothing until
/// SHOP-06-ios adds the aisle-order screen.
public struct StoresView: View {
    @Bindable private var model: StoresModel
    private let isOnline: Bool
    @State private var showSheet = false
    @Environment(\.scenePhase) private var scenePhase

    public init(model: StoresModel, isOnline: Bool) {
        self.model = model
        self.isOnline = isOnline
    }

    public var body: some View {
        VStack(spacing: 0) {
            if model.writeFailed {
                Text("Could not save the change. Try again.")
                    .font(.subheadline)
                    .foregroundStyle(Tokens.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Tokens.screenPadding)
                    .accessibilityIdentifier("stores.error")
            }
            if !isOnline {
                OfflineBanner(message: "Offline — changes will sync when you're back online")
                    .padding(.horizontal, Tokens.screenPadding)
            }
            if model.loaded {
                if model.rows.isEmpty && model.pending.isEmpty { emptyState } else { populated }
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Tokens.bg)
        .navigationTitle("Stores")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showSheet = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("New store")
                    .accessibilityIdentifier("stores.new")
            }
        }
        .overlay(alignment: .bottom) {
            VStack(spacing: 8) {
                ForEach(model.pending) { action in
                    UndoToast(message: action.message) { model.undo(action.id) }
                }
            }
        }
        .sheet(isPresented: $showSheet) {
            StoreSheet(stores: model.records) { name in
                guard await model.create(name: name) != nil else { return false }
                showSheet = false
                return true
            } onClose: {
                showSheet = false
            }
        }
        .task { await model.start() }
        // A swipe still inside its undo window is written when the person leaves, not dropped.
        .onDisappear { Task { await model.flush() } }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { Task { await model.flush() } }
        }
        .accessibilityIdentifier("stores.screen")
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Text("No stores yet")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
            Text("Name the shops you go to, then pick one on a list.")
                .foregroundStyle(Tokens.text2)
                .multilineTextAlignment(.center)
            Button("New store") { showSheet = true }
                .buttonStyle(.borderedProminent)
                .tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityIdentifier("stores.empty.new")
        }
        .padding(Tokens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("stores.empty")
    }

    private var populated: some View {
        List {
            ForEach(model.rows) { row in
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).foregroundStyle(Tokens.text)
                    if let learned = Stores.learnedLabel(row.learned) {
                        Text(learned)
                            .font(.footnote)
                            .foregroundStyle(Tokens.text2)
                            .accessibilityIdentifier("stores.row.learned")
                    }
                }
                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button("Delete", role: .destructive) { model.delete(row) }
                        .accessibilityIdentifier("stores.delete")
                }
                .accessibilityElement(children: .combine)
                .accessibilityAction(named: "Delete") { model.delete(row) }
                .accessibilityIdentifier("stores.row")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }
}
