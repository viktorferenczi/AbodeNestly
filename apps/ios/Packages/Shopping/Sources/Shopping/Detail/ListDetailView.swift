import SwiftUI
import Sync
import UI

/// DESIGN C2, the SHOP-02 slice. States: empty ("Try: 2 l milk", the field focused), populated, offline (a note;
/// adding works the same), gone ("This list is gone"). Rows show quantity, unit and note and open the item sheet
/// (SHOP-03-ios), checked rows too. SHOP-04-ios adds the checkbox beside each row (undoable for 5 s, written when the
/// window ends), the all-checked state and "Clear checked". The global top bar stays above, so the title is the
/// navigation title.
public struct ListDetailView: View {
    @Bindable private var model: ListDetailModel
    private let fallbackTitle: String
    private let isOnline: Bool
    private let onManageStores: () -> Void
    private let makeScanner: (@MainActor () -> BarcodeScannerModel)?
    private let makeVoice: (@MainActor () -> VoiceAddModel)?
    private let makeShare: (@MainActor () -> ShareSheetModel)?
    @State private var checkedOpen: Bool
    /// The open scanner (SHOP-10), built when the button is tapped so each scan starts from a fresh state.
    @State private var scanner: BarcodeScannerModel?
    /// The open voice sheet (SHOP-14), built when the mic is tapped so each run starts from a fresh state.
    @State private var voice: VoiceAddModel?
    /// "Type instead" closed the voice sheet; the Quick add field takes focus once it has finished dismissing.
    @State private var focusAfterVoice = false
    /// The open share sheet (SHOP-13), built when "Share" is tapped so it starts from the list's current link.
    @State private var sharing: ShareSheetModel?
    @State private var showStorePicker = false
    /// The "Add all to…" sheet of a template (SHOP-11).
    @State private var addingAll = false
    /// "Manage stores" pushes once the picker has finished dismissing; a push during the dismissal is dropped.
    @State private var manageAfterDismiss = false
    @FocusState private var fieldFocused: Bool
    @Environment(\.scenePhase) private var scenePhase

    /// `checkedExpanded` starts the Checked group open (tests; the person opens it by tapping its header).
    /// `makeScanner` shows the "Scan barcode" button in the quick-add bar (SHOP-10), `makeVoice` the "Voice add" mic (SHOP-14); without them there is none.
    public init(
        model: ListDetailModel, title: String, isOnline: Bool, checkedExpanded: Bool = false, onManageStores: @escaping () -> Void = {},
        makeScanner: (@MainActor () -> BarcodeScannerModel)? = nil, makeShare: (@MainActor () -> ShareSheetModel)? = nil,
        makeVoice: (@MainActor () -> VoiceAddModel)? = nil
    ) {
        self.model = model
        self.onManageStores = onManageStores
        self.makeScanner = makeScanner
        self.makeShare = makeShare
        self.makeVoice = makeVoice
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
                priceStoreName: model.priceStore?.name, lastBoughtLine: { model.lastBoughtLine(for: $0) },
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
        .fullScreenCover(isPresented: Binding(get: { scanner != nil }, set: { if !$0 { scanner = nil } })) {
            if let scanner {
                BarcodeScannerView(model: scanner, onClose: { self.scanner = nil })
            }
        }
        .sheet(isPresented: Binding(get: { voice != nil }, set: { if !$0 { voice = nil } }), onDismiss: {
            // A focus request made while the sheet is still going away is dropped, so it waits for the dismissal.
            guard focusAfterVoice else { return }
            focusAfterVoice = false
            fieldFocused = true
        }) {
            if let voice {
                VoiceAddView(
                    model: voice, onClose: { self.voice = nil },
                    onTypeInstead: {
                        focusAfterVoice = true
                        self.voice = nil
                    },
                    openSettings: {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                )
                .presentationDetents([.medium])
            }
        }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) {
            if let sharing {
                ShareSheetView(model: sharing, isOnline: isOnline, onClose: { self.sharing = nil })
            }
        }
        .toolbar {
            if !listActions.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(listActions, id: \.self) { action in actionButton(action) }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("List actions")
                    .accessibilityIdentifier("list.actions")
                }
            }
        }
        .sheet(isPresented: $addingAll) {
            AddAllSheet(
                lists: model.targets,
                onPick: { target in await model.addAll(to: target) },
                onClose: { addingAll = false }
            )
        }
        .task { await model.start() }
        // An action still inside its undo window is written when the person leaves, not dropped.
        .onDisappear { Task { await model.flush() } }
        // The add-all sheet has just closed and focus is elsewhere, so say the result (web: `role="status"`).
        .onChange(of: model.status) { _, status in
            if let status { AccessibilityNotification.Announcement(status).post() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { Task { await model.flush() } }
        }
        .accessibilityIdentifier("list.screen")
    }

    /// Always the menu, "Share" first (SHOP-13), then "Add all to list" on a template (SHOP-11) and "Clear checked" when
    /// something is checked (SHOP-04). "Share" is left out where nothing can build the sheet, and until the list row is
    /// here (not loaded yet, or gone).
    private var listActions: [ListAction] {
        let actions: [ListAction] = Templates.actions(isTemplate: model.isTemplate, hasChecked: !model.checked.isEmpty)
        return makeShare == nil || !model.canShare ? actions.filter { $0 != .share } : actions
    }

    @ViewBuilder
    private func actionButton(_ action: ListAction) -> some View {
        switch action {
        case .share:
            Button("Share") { sharing = makeShare?() }
                .accessibilityIdentifier("list.actions.share")
        case .addAll:
            Button("Add all to list") { addingAll = true }
                .accessibilityIdentifier("list.actions.addAll")
        case .clearChecked:
            Button("Clear checked") { model.clearChecked() }
                .accessibilityIdentifier("list.actions.clearChecked")
        }
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
        if model.isTemplate {
            Text("Template")
                .font(.caption)
                .foregroundStyle(Tokens.text2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Tokens.screenPadding)
                .accessibilityIdentifier("list.template")
        }
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
        if let status = model.status {
            Text(status)
                .font(.subheadline)
                .foregroundStyle(Tokens.text2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Tokens.screenPadding)
                .accessibilityIdentifier("list.status")
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
            onDetails: { model.openNew(prefill: $0) },
            onScan: makeScanner.map { make in { scanner = make() } },
            onVoice: makeVoice.map { make in { voice = make() } }
        )
    }

    private var emptyState: some View {
        Text("Try: 2 l milk")
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
                    priceCaption: model.priceCaption(for: item), onOpen: { model.open(item) }, onToggle: { pending ? model.undoCheck(item.id) : model.check(item) }
                )
            }
            if !model.checked.isEmpty {
                Section {
                    DisclosureGroup(isExpanded: $checkedOpen) {
                        ForEach(model.checked) { item in
                            ItemRow(
                                item: item, idPrefix: "list.checked.row", color: Tokens.text2, ticked: true, strikethrough: false,
                                priceCaption: model.priceCaption(for: item), onOpen: { model.open(item) }, onToggle: { Task { await model.uncheck(item) } }
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
    /// The last price under the name, "€1.20 · Test Market" (SHOP-12); `nil` shows nothing.
    let priceCaption: String?
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
                        if let priceCaption {
                            Text(priceCaption)
                                .font(.caption)
                                .foregroundStyle(Tokens.text2)
                                .lineLimit(1)
                                .accessibilityIdentifier("\(idPrefix).\(item.name).price")
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
            // Reads as "Milk, 2 l, lactose free, €1.20 · Test Market"; the checkbox stays its own element beside it.
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("\(idPrefix).\(item.name)")
        }
        .accessibilityAction(named: ticked ? "Uncheck" : "Check off", onToggle)
    }
}
