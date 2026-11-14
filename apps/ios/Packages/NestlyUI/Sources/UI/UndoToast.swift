import SwiftUI

/// DESIGN A6 undo toast. Timing lives with the caller; this only shows the message and the Undo button.
public struct UndoToast: View {
    private let message: String
    private let onUndo: () -> Void

    public init(message: String, onUndo: @escaping () -> Void) {
        self.message = message
        self.onUndo = onUndo
    }

    public var body: some View {
        HStack(spacing: 12) {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(Tokens.text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("undo-toast.message")
            Button("Undo", action: onUndo)
                .font(.subheadline.weight(.semibold))
                .tint(Tokens.accent)
                .frame(minWidth: Tokens.minTouchTarget, minHeight: Tokens.minTouchTarget)
                .accessibilityLabel("Undo")
                .accessibilityHint(message)
                .accessibilityIdentifier("undo-toast.undo")
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
        .overlay(RoundedRectangle(cornerRadius: Tokens.cardRadius).stroke(Tokens.line))
        .padding(Tokens.screenPadding)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("undo-toast")
    }
}
