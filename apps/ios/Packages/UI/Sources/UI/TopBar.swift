import SwiftUI

/// DESIGN A6 TopBar: a title and optional trailing text. Actions arrive with the screens that need them.
public struct TopBar: View {
    private let title: String
    private let trailing: String?

    public init(title: String, trailing: String? = nil) {
        self.title = title
        self.trailing = trailing
    }

    public var body: some View {
        HStack {
            Text(title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("topbar.title")
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
}
