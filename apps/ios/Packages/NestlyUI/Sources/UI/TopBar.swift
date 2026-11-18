import SwiftUI

/// An icon button in the TopBar (DESIGN A6 allows two). `label` is the VoiceOver label.
public struct TopBarAction {
    public let systemImage: String
    public let label: String
    public let identifier: String
    public let action: () -> Void

    public init(systemImage: String, label: String, identifier: String, action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.label = label
        self.identifier = identifier
        self.action = action
    }
}

/// DESIGN A6 TopBar: a title, optional trailing text and up to two icon actions.
public struct TopBar: View {
    private let title: String
    private let trailing: String?
    private let onTitleTap: (() -> Void)?
    private let actions: [TopBarAction]

    /// With `onTitleTap` the title is a button ("<title> ▾") that opens the household switcher (DESIGN B3).
    public init(title: String, trailing: String? = nil, onTitleTap: (() -> Void)? = nil,
        actions: [TopBarAction] = []
    ) {
        self.actions = Array(actions.prefix(2))
        self.title = title
        self.trailing = trailing
        self.onTitleTap = onTitleTap
    }

    public var body: some View {
        HStack {
            titleView
            Spacer(minLength: Tokens.screenPadding)
            ForEach(actions.indices, id: \.self) { index in
                let item = actions[index]
                Button(action: item.action) {
                    Image(systemName: item.systemImage)
                        .foregroundStyle(Tokens.accent)
                        .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.label)
                .accessibilityIdentifier(item.identifier)
            }
            if let trailing {
                Text(trailing)
                    .font(.subheadline)
                    .foregroundStyle(Tokens.text2)
                    .lineLimit(1)
                    .accessibilityIdentifier("topbar.trailing")
            }
        }
        .padding(.horizontal, Tokens.screenPadding)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget)
        .background(Tokens.surface)
        .overlay(alignment: .bottom) { Tokens.line.frame(height: 1) }
    }

    @ViewBuilder
    private var titleView: some View {
        if let onTitleTap {
            Button(action: onTitleTap) {
                Text("\(title) ▾")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Tokens.text)
                    .lineLimit(1)
                    .frame(minHeight: Tokens.minTouchTarget, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Households")
            // The visible name stays readable to tests and VoiceOver users as the button's value.
            .accessibilityValue(title)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("topbar.title")
        } else {
            Text(title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("topbar.title")
        }
    }
}
