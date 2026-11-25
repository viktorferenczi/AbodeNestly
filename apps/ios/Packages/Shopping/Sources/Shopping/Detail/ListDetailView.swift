import SwiftUI
import Sync
import UI

/// DESIGN C2, the SHOP-02 slice. States: empty ("Add your first item", the field focused), populated, offline (a note;
/// adding works the same), gone ("This list is gone"). Items show only their name until SHOP-03-ios, and the Checked
/// group is read-only until SHOP-04-ios. The global top bar stays above, so the title is the navigation title.
public struct ListDetailView: View {
    @Bindable private var model: ListDetailModel
    private let fallbackTitle: String
    private let isOnline: Bool
    @State private var checkedOpen: Bool
    @FocusState private var fieldFocused: Bool

    /// `checkedExpanded` starts the Checked group open (tests; the person opens it by tapping its header).
    public init(model: ListDetailModel, title: String, isOnline: Bool, checkedExpanded: Bool = false) {
        self.model = model
        fallbackTitle = title
        self.isOnline = isOnline
        _checkedOpen = State(initialValue: checkedExpanded)
    }

    public var body: some View {
        VStack(spacing: 0) {
            if model.isGone {
                gone
            } else {
                detail
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Tokens.bg)
        .navigationTitle(model.snapshot.list?.name ?? fallbackTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.start() }
        .accessibilityIdentifier("list.screen")
    }

    private var gone: some View {
        Text("This list is gone")
            .font(.title3.weight(.semibold))
            .foregroundStyle(Tokens.text)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("list.gone")
    }

    @ViewBuilder
    private var detail: some View {
        if let store = model.snapshot.storeName {
            HStack {
                Text(store)
                    .font(.caption)
                    .foregroundStyle(Tokens.accent)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Tokens.accentBg, in: Capsule())
                    .accessibilityIdentifier("list.store")
                Spacer()
            }
            .padding(.horizontal, Tokens.screenPadding)
            .padding(.top, 8)
        }
        if model.writeFailed {
            Text("Could not save the change. Try again.")
                .font(.subheadline)
                .foregroundStyle(Tokens.danger)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Tokens.screenPadding)
                .accessibilityIdentifier("list.error")
        }
        if !isOnline {
            OfflineBanner(message: "Offline — changes will sync when you're back online")
                .padding(.horizontal, Tokens.screenPadding)
        }
        if model.loaded {
            if model.unchecked.isEmpty && model.checked.isEmpty { emptyState } else { populated }
        } else {
            Spacer()
        }
        QuickAddBar(
            text: $model.query,
            suggestions: model.suggestions.map { .init(id: $0.key, label: $0.label) },
            placeholder: "Add item", focused: $fieldFocused,
            onPick: { picked in
                guard let suggestion = model.suggestions.first(where: { $0.key == picked.id }) else { return }
                Task {
                    await model.pick(suggestion)
                    fieldFocused = true
                }
            },
            onSubmit: {
                Task {
                    await model.submit()
                    fieldFocused = true
                }
            }
        )
    }

    private var emptyState: some View {
        Text("Add your first item")
            .foregroundStyle(Tokens.text2)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("list.empty")
            .onAppear { fieldFocused = true }
    }

    private var populated: some View {
        List {
            ForEach(model.unchecked) { item in
                Text(item.name)
                    .foregroundStyle(Tokens.text)
                    .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
                    .accessibilityIdentifier("list.row.\(item.name)")
            }
            if !model.checked.isEmpty {
                Section {
                    DisclosureGroup(isExpanded: $checkedOpen) {
                        ForEach(model.checked) { item in
                            Text(item.name)
                                .foregroundStyle(Tokens.text2)
                                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
                                .accessibilityIdentifier("list.checked.row.\(item.name)")
                        }
                    } label: {
                        Text("Checked (\(model.checked.count))")
                            .foregroundStyle(Tokens.text2)
                            .accessibilityIdentifier("list.checked")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }
}
