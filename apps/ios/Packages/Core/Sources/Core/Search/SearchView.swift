import SwiftUI
import UI

/// DESIGN B8. States: empty-query (recent searches), no-results, results. A row calls `onOpen` with its target.
public struct SearchView: View {
    @Bindable private var model: SearchModel
    private let onOpen: (SearchTarget) -> Void
    @FocusState private var focused: Bool

    public init(model: SearchModel, onOpen: @escaping (SearchTarget) -> Void) {
        self.model = model
        self.onOpen = onOpen
    }

    public var body: some View {
        VStack(spacing: 0) {
            field
            List {
                switch model.state {
                case .emptyQuery: recentsSection
                case .noResults: if !model.isSearching { noResultsSection }
                case .results: ForEach(model.sections) { sectionView($0) }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
        .background(Tokens.bg)
        .onAppear { focused = true }
    }

    private var field: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Tokens.text2)
            TextField("Search your household", text: $model.query)
                .focused($focused)
                .submitLabel(.search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { model.submit() }
                .accessibilityIdentifier("search.field")
            if !model.query.isEmpty {
                Button { model.query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Tokens.text2)
                        .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
                .accessibilityIdentifier("search.clear")
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: Tokens.minTouchTarget)
        .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
        .padding(Tokens.screenPadding)
    }

    @ViewBuilder
    private var recentsSection: some View {
        Section {
            if model.recents.isEmpty {
                Text("Search items, recipes, assets and more by name.").foregroundStyle(Tokens.text2)
            } else {
                ForEach(model.recents, id: \.self) { recent in
                    Button { model.choose(recent: recent) } label: {
                        HStack {
                            Image(systemName: "clock.arrow.circlepath").foregroundStyle(Tokens.text2)
                            Text(recent).foregroundStyle(Tokens.text)
                            Spacer(minLength: 0)
                        }
                        .frame(minHeight: Tokens.minTouchTarget)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("search.recent")
                }
            }
        } header: {
            if !model.recents.isEmpty { Text("Recent searches") }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("search.empty")
    }

    private var noResultsSection: some View {
        Section {
            Text("No results for \u{201C}\(model.debouncedQuery)\u{201D}").foregroundStyle(Tokens.text)
                .accessibilityIdentifier("search.noResults")
        }
    }

    private func sectionView(_ section: SearchSection) -> some View {
        Section {
            ForEach(section.results) { result in
                Button {
                    model.open(result)
                    onOpen(result.target)
                } label: {
                    HStack {
                        Text(result.name).foregroundStyle(Tokens.text)
                        Spacer(minLength: 8)
                        Text(result.kind).font(.footnote).foregroundStyle(Tokens.text2)
                    }
                    .frame(minHeight: Tokens.minTouchTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("search.row")
            }
        } header: {
            Text(section.module.title)
                .foregroundStyle(color(section.module))
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("search.section.\(section.module.rawValue)")
        }
    }

    private func color(_ module: SearchModule) -> Color {
        switch module {
        case .shopping: Tokens.shopping
        case .pantry: Tokens.pantry
        case .maintenance: Tokens.maintenance
        }
    }
}
