import SwiftUI
import Sync
import UI

/// DESIGN C2, the SHOP-02 slice. States: empty ("Add your first item", the field focused), populated, offline (a note;
/// adding works the same), gone ("This list is gone"). Rows show quantity, unit and note and open the item sheet
/// (SHOP-03-ios), checked rows too; check-off waits for SHOP-04-ios. The global top bar stays above, so the title is
/// the navigation title.
public struct ListDetailView: View {
    @Bindable private var model: ListDetailModel
    private let fallbackTitle: String
    private let isOnline: Bool
    @State private var checkedOpen: Bool
    @FocusState private var fieldFocused: Bool
    @Environment(\.scenePhase) private var scenePhase

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
        .overlay(alignment: .bottom) {
            VStack(spacing: 8) {
                ForEach(model.pendingDeletes) { action in
                    UndoToast(message: action.message) { model.undoDelete(action.id) }
                }
            }
        }
        .sheet(item: $model.editing) { state in
            ItemSheetView(
                state: state, unitSuggestions: ItemSheetLogic.unitSuggestions(history: model.snapshot.history),
                onSave: { fields in
                    switch state {
                    case .new: await model.create(fields)
                    case .edit(let item): await model.save(item, fields)
                    }
                },
                onDelete: { model.delete($0) }, onCancel: { model.closeSheet() }
            )
        }
        .task { await model.start() }
        // A delete still inside its undo window is written when the person leaves, not dropped.
        .onDisappear { Task { await model.flush() } }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { Task { await model.flush() } }
        }
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
            },
            onDetails: { model.openNew(prefill: $0) }
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
                ItemRow(item: item, idPrefix: "list.row", color: Tokens.text) { model.open(item) }
            }
            if !model.checked.isEmpty {
                Section {
                    DisclosureGroup(isExpanded: $checkedOpen) {
                        ForEach(model.checked) { item in
                            ItemRow(item: item, idPrefix: "list.checked.row", color: Tokens.text2) { model.open(item) }
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

/// One item line: name, the note as a caption under it, quantity and unit trailing. The whole row opens the sheet.
private struct ItemRow: View {
    let item: ListItemRecord
    let idPrefix: String
    let color: Color
    let onOpen: () -> Void

    var body: some View {
        let qty = ItemSheetLogic.formatQty(item.quantity, item.unit)
        Button(action: onOpen) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).foregroundStyle(color)
                    if let note = item.note, !note.isEmpty {
                        Text(note)
                            .font(.caption)
                            .foregroundStyle(Tokens.text2)
                            .lineLimit(1)
                            .accessibilityIdentifier("\(idPrefix).\(item.name).note")
                    }
                }
                Spacer(minLength: 8)
                if !qty.isEmpty {
                    Text(qty)
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(Tokens.text2)
                        .accessibilityIdentifier("\(idPrefix).\(item.name).qty")
                }
            }
            .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Reads as "Milk, 2 l, lactose free".
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("\(idPrefix).\(item.name)")
    }
}
