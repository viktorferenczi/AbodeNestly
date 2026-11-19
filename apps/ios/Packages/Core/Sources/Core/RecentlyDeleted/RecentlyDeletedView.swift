import SwiftUI
import UI

/// DESIGN B11. States: empty, populated (segment per enabled module, rows with days left and Restore).
public struct RecentlyDeletedView: View {
    @Bindable private var model: RecentlyDeletedModel

    public init(model: RecentlyDeletedModel) {
        self.model = model
    }

    public var body: some View {
        VStack(spacing: 0) {
            if model.loaded {
                switch model.state {
                case .empty: emptyState
                case .populated: populated
                }
            } else {
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Tokens.bg)
    }

    private var emptyState: some View {
        Text("Nothing deleted in the last 30 days.")
            .foregroundStyle(Tokens.text2)
            .multilineTextAlignment(.center)
            .padding(Tokens.screenPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier("deleted.empty")
    }

    private var populated: some View {
        VStack(spacing: 0) {
            Picker("Module", selection: Binding(
                get: { model.selectedModule ?? .shopping },
                set: { model.selected = $0 }
            )) {
                ForEach(model.sections) { section in
                    Text(section.module.title)
                        .tag(section.module)
                        .accessibilityIdentifier("deleted.segment.\(section.module.rawValue)")
                }
            }
            .pickerStyle(.segmented)
            .padding(Tokens.screenPadding)
            List {
                if model.selectedItems.isEmpty {
                    Text("Nothing deleted here.").foregroundStyle(Tokens.text2)
                        .accessibilityIdentifier("deleted.none")
                }
                ForEach(model.selectedItems) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).foregroundStyle(Tokens.text)
                            Text(RecentlyDeleted.daysLeftText(item.daysLeft))
                                .font(.footnote).foregroundStyle(Tokens.text2)
                        }
                        Spacer(minLength: 8)
                        Button("Restore") { Task { await model.restore(item) } }
                            .buttonStyle(.bordered)
                            .frame(minHeight: Tokens.minTouchTarget)
                            .accessibilityLabel("Restore \(item.name)")
                            .accessibilityIdentifier("deleted.restore")
                    }
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("deleted.row")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }
}
