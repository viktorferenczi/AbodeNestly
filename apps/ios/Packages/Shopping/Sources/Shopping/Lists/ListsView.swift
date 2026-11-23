import SwiftUI
import Sync
import UI

/// DESIGN C1. States: empty ("Make your first list"), populated (Lists, Templates, Archived), offline (a note; the
/// screen works the same). Tap opens the list, swipe left archives with a 5 s undo, "+" opens the new-list sheet.
public struct ListsView: View {
    @Bindable private var model: ListsModel
    private let isOnline: Bool
    private let onOpen: (ListRow) -> Void
    @State private var showSheet = false
    @State private var archivedOpen = false
    @Environment(\.scenePhase) private var scenePhase

    public init(model: ListsModel, isOnline: Bool, onOpen: @escaping (ListRow) -> Void) {
        self.model = model
        self.isOnline = isOnline
        self.onOpen = onOpen
    }

    public var body: some View {
        VStack(spacing: 0) {
            header
            if !isOnline {
                OfflineBanner(message: "Offline — changes will sync when you're back online")
                    .padding(.horizontal, Tokens.screenPadding)
            }
            if model.loaded {
                if model.sections.isEmpty { emptyState } else { populated }
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Tokens.bg)
        .overlay(alignment: .bottom) {
            VStack(spacing: 8) {
                ForEach(model.pending) { action in
                    UndoToast(message: action.message) { model.undo(action.id) }
                }
            }
        }
        .sheet(isPresented: $showSheet) {
            NewListSheet(stores: model.stores) { name, storeId in
                guard let row = await model.create(name: name, storeId: storeId) else { return false }
                showSheet = false
                onOpen(row)
                return true
            } onClose: {
                showSheet = false
            }
        }
        .task { await model.start() }
        // A swipe still inside its undo window is written when the person leaves, not dropped.
        .onDisappear { Task { await model.flush() } }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { Task { await model.flush() } }
        }
    }

    private var header: some View {
        HStack {
            Text("Shopping")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("tab.shopping.content")
            Spacer()
            Button { showSheet = true } label: {
                Image(systemName: "plus")
                    .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                    .contentShape(Rectangle())
            }
            .foregroundStyle(Tokens.accent)
            .accessibilityLabel("New list")
            .accessibilityIdentifier("lists.new")
        }
        .padding(.horizontal, Tokens.screenPadding)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Text("Make your first list")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Tokens.text)
            Text("Lists keep what you need to buy, shared with everyone in your household.")
                .foregroundStyle(Tokens.text2)
                .multilineTextAlignment(.center)
            Button("New list") { showSheet = true }
                .buttonStyle(.borderedProminent)
                .tint(Tokens.accent)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityIdentifier("lists.empty.new")
        }
        .padding(Tokens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("lists.empty")
    }

    private var populated: some View {
        let sections = model.sections
        return VStack(spacing: 0) {
            List {
                Section("Lists") {
                    ForEach(sections.lists) { row in activeRow(row) }
                }
                if !sections.templates.isEmpty {
                    Section("Templates") {
                        ForEach(sections.templates) { row in activeRow(row) }
                    }
                }
                if !sections.archived.isEmpty {
                    Section {
                        DisclosureGroup("Archived (\(sections.archived.count))", isExpanded: $archivedOpen) {
                            ForEach(sections.archived) { row in archivedRow(row) }
                        }
                        .accessibilityIdentifier("lists.archived")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            Button { showSheet = true } label: {
                Text("New list").frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
            }
            .buttonStyle(.borderedProminent)
            .tint(Tokens.accent)
            .padding(Tokens.screenPadding)
            .accessibilityIdentifier("lists.fab")
        }
    }

    private func content(_ row: ListRow) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name).foregroundStyle(Tokens.text)
                Text(row.count).font(.footnote).foregroundStyle(Tokens.text2)
            }
            Spacer(minLength: 8)
            if let store = row.storeName {
                Text(store)
                    .font(.caption)
                    .foregroundStyle(Tokens.accent)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Tokens.accentBg, in: Capsule())
                    .accessibilityIdentifier("lists.row.store")
            }
        }
        .frame(minHeight: Tokens.minTouchTarget)
        .contentShape(Rectangle())
    }

    private func activeRow(_ row: ListRow) -> some View {
        Button { onOpen(row) } label: { content(row) }
            .buttonStyle(.plain)
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button("Archive") { model.archive(row) }
                    .tint(Tokens.accent)
                    .accessibilityIdentifier("lists.archive")
            }
            .accessibilityAction(named: "Archive") { model.archive(row) }
            .accessibilityIdentifier("lists.row")
    }

    private func archivedRow(_ row: ListRow) -> some View {
        HStack {
            content(row)
            Button("Restore") { Task { await model.restore(row) } }
                .buttonStyle(.bordered)
                .frame(minHeight: Tokens.minTouchTarget)
                .accessibilityLabel("Restore \(row.name)")
                .accessibilityIdentifier("lists.restore")
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button("Delete", role: .destructive) { model.delete(row) }
                .accessibilityIdentifier("lists.delete")
        }
        .accessibilityAction(named: "Delete") { model.delete(row) }
        .accessibilityIdentifier("lists.archived.row")
    }
}

/// The destination of a tapped list until SHOP-02-ios builds the list detail (C2): just its name.
public struct ListPlaceholderView: View {
    private let name: String

    public init(name: String) {
        self.name = name
    }

    public var body: some View {
        Text(name)
            .font(.title2.weight(.semibold))
            .foregroundStyle(Tokens.text)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Tokens.bg)
            .navigationTitle(name)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("list.placeholder")
    }
}
