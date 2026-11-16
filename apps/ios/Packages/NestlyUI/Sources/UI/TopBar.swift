import SwiftUI

/// DESIGN A6 TopBar: a title and optional trailing text. Actions arrive with the screens that need them.
public struct TopBar: View {
    private let title: String
    private let trailing: String?
    private let onTitleTap: (() -> Void)?

    /// With `onTitleTap` the title is a button ("<title> ▾") that opens the household switcher (DESIGN B3).
    public init(title: String, trailing: String? = nil, onTitleTap: (() -> Void)? = nil) {
        self.title = title
        self.trailing = trailing
        self.onTitleTap = onTitleTap
    }

    public var body: some View {
        HStack {
            titleView
            Spacer(minLength: Tokens.screenPadding)
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
