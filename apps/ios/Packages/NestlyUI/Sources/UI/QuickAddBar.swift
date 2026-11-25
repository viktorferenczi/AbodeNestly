import SwiftUI

/// DESIGN A6 QuickAddBar: a text field with an Add button and the autocomplete lines above it. Presentational, so
/// pantry can reuse it. No mic or barcode button until SHOP-14 and SHOP-10.
public struct QuickAddBar: View {
    public struct Suggestion: Identifiable, Equatable, Sendable {
        public var id: String
        public var label: String

        public init(id: String, label: String) {
            self.id = id
            self.label = label
        }
    }

    @Binding private var text: String
    private let suggestions: [Suggestion]
    private let placeholder: String
    private var focused: FocusState<Bool>.Binding
    private let onPick: (Suggestion) -> Void
    private let onSubmit: () -> Void

    public init(
        text: Binding<String>, suggestions: [Suggestion], placeholder: String, focused: FocusState<Bool>.Binding,
        onPick: @escaping (Suggestion) -> Void, onSubmit: @escaping () -> Void
    ) {
        _text = text
        self.suggestions = suggestions
        self.placeholder = placeholder
        self.focused = focused
        self.onPick = onPick
        self.onSubmit = onSubmit
    }

    private var isBlank: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    public var body: some View {
        VStack(spacing: 0) {
            if !suggestions.isEmpty {
                VStack(spacing: 0) {
                    ForEach(suggestions) { suggestion in
                        Button { onPick(suggestion) } label: {
                            Text(suggestion.label)
                                .foregroundStyle(Tokens.text)
                                .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Suggestion, \(suggestion.label)")
                        .accessibilityIdentifier("quickadd.suggestion.\(suggestion.label)")
                    }
                }
                .padding(.horizontal, Tokens.screenPadding)
                .background(Tokens.surface)
                .accessibilityIdentifier("quickadd.suggestions")
                Divider().overlay(Tokens.line)
            }
            HStack(spacing: 8) {
                TextField(placeholder, text: $text)
                    .focused(focused)
                    .submitLabel(.done)
                    .onSubmit(onSubmit)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 12)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                    .accessibilityLabel("Add item")
                    .accessibilityIdentifier("quickadd.field")
                Button("Add", action: onSubmit)
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .disabled(isBlank)
                    .accessibilityIdentifier("quickadd.add")
            }
            .padding(Tokens.screenPadding)
            .background(Tokens.surface)
        }
    }
}
