import SwiftUI
import Sync
import UI

/// DESIGN C2, the SHOP-02 slice. States: empty ("Add your first item", the field focused), populated, offline (a note;
/// adding works the same), gone ("This list is gone"). Rows show quantity, unit and note and open the item sheet
/// (SHOP-03-ios), checked rows too. SHOP-04-ios adds the checkbox beside each row (undoable for 5 s, written when the
/// window ends), the all-checked state and "Clear checked". The global top bar stays above, so the title is the
/// navigation title.
public struct ListDetailView: View {
    @Bindable private var model: ListDetailModel
    private let fallbackTitle: String
    private let isOnline: Bool
    private let onManageStores: () -> Void
    @State private var checkedOpen: Bool
    @State private var showStorePicker = false
    /// "Manage stores" pushes once the picker has finished dismissing; a push during the dismissal is dropped.
    @State private var manageAfterDismiss = false
    @FocusState private var fieldFocused: Bool
    @Environment(\.scenePhase) private var scenePhase

    /// `checkedExpanded` starts the Checked group open (tests; the person opens it by tapping its header).
    public init(
        model: ListDetailModel, title: String, isOnline: Bool, checkedExpanded: Bool = false, onManageStores: @escaping () -> Void = {}
    ) {
        self.model = model
        self.onManageStores = onManageStores
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
                ForEach(model.toasts) { action in
                    UndoToast(message: action.message) { model.undo(action.id) }
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
        .sheet(isPresented: $showStorePicker, onDismiss: {
            guard manageAfterDismiss else { return }
            manageAfterDismiss = false
            onManageStores()
        }) {
            StorePickerSheet(
                stores: model.snapshot.stores, current: model.snapshot.list?.storeId,
                onCreate: { await model.createStore(name: $0) },
                onSave: { choice in
                    guard await model.setStore(choice) else { return false }
                    showStorePicker = false
                    return true
                },
                onManage: {
                    manageAfterDismiss = true
                    showStorePicker = false
                },
                onClose: { showStorePicker = false }
            )
        }
        .toolbar {
            // Only item for now (Share, Template and Archive land later), and only when there is something to clear.
            if !model.checked.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Clear checked") { model.clearChecked() }
                            .accessibilityIdentifier("list.actions.clearChecked")
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("List actions")
                    .accessibilityIdentifier("list.actions")
                }
            }
        }
        .task { await model.start() }
        // An action still inside its undo window is written when the person leaves, not dropped.
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
        HStack {
            let store = model.snapshot.storeName
            Button { showStorePicker = true } label: {
                Text(store ?? "Add store")
                    .font(.caption)
                    .foregroundStyle(store == nil ? Tokens.text2 : Tokens.accent)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(store == nil ? Tokens.text2.opacity(0.12) : Tokens.accentBg, in: Capsule())
                    .frame(minHeight: Tokens.minTouchTarget)
                    .contentShape(Rectangle())
            }
            // Voice Control activates by the visible words, so the empty chip is named "Add store".
            .accessibilityLabel(store.map { "Store: \($0)" } ?? "Add store")
            .accessibilityIdentifier("list.store")
            Spacer()
        }
        .padding(.horizontal, Tokens.screenPadding)
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
            if model.showsEmptyState { emptyState } else { populated }
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
            if model.allChecked {
                VStack(spacing: 8) {
                    Text("All done")
                        .font(.headline)
                        .foregroundStyle(Tokens.text)
                        .accessibilityIdentifier("list.allDone")
                    Button("Clear checked") { model.clearChecked() }
                        .buttonStyle(.bordered)
                        .tint(Tokens.accent)
                        .accessibilityIdentifier("list.clearChecked")
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            ForEach(model.unchecked) { item in
                let pending = model.isPendingCheck(item.id)
                ItemRow(
                    item: item, idPrefix: "list.row", color: pending ? Tokens.text2 : Tokens.text, ticked: pending, strikethrough: pending,
                    onOpen: { model.open(item) }, onToggle: { pending ? model.undoCheck(item.id) : model.check(item) }
                )
            }
            if !model.checked.isEmpty {
                Section {
                    DisclosureGroup(isExpanded: $checkedOpen) {
                        ForEach(model.checked) { item in
                            ItemRow(
                                item: item, idPrefix: "list.checked.row", color: Tokens.text2, ticked: true, strikethrough: false,
                                onOpen: { model.open(item) }, onToggle: { Task { await model.uncheck(item) } }
                            )
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

/// One item line: a checkbox, then the name with the note as a caption under it and quantity and unit trailing. The
/// checkbox sits beside the open button, both borderless, so a `List` row never fires both.
private struct ItemRow: View {
    let item: ListItemRecord
    let idPrefix: String
    let color: Color
    let ticked: Bool
    let strikethrough: Bool
    let onOpen: () -> Void
    let onToggle: () -> Void

    var body: some View {
        let qty = ItemSheetLogic.formatQty(item.quantity, item.unit)
        HStack(spacing: 4) {
            Button(action: onToggle) {
                Image(systemName: ticked ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(Tokens.accent)
                    .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .sensoryFeedback(.success, trigger: ticked) { old, new in !old && new }
            .accessibilityLabel(ticked ? "Uncheck \(item.name)" : "Check off \(item.name)")
            .accessibilityIdentifier("\(idPrefix).\(item.name).check")
            Button(action: onOpen) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name).strikethrough(strikethrough).foregroundStyle(color)
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
            .buttonStyle(.borderless)
            // Reads as "Milk, 2 l, lactose free"; the checkbox stays its own element beside it.
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("\(idPrefix).\(item.name)")
        }
        .accessibilityAction(named: ticked ? "Uncheck" : "Check off", onToggle)
    }
}
