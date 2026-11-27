import SwiftUI

/// DESIGN A6 QuickAddBar: a text field with an Add button and the autocomplete lines above it. Presentational, so
/// pantry can reuse it. The barcode button shows when `onScan` is given (SHOP-10); no mic button until SHOP-14.
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
    private let onDetails: ((String) -> Void)?
    private let onScan: (() -> Void)?

    /// `onDetails` shows a "Details" button that hands over the typed text (SHOP-03: opens the item sheet). `onScan`
    /// shows a "Scan barcode" button (SHOP-10: opens the scanner).
    public init(
        text: Binding<String>, suggestions: [Suggestion], placeholder: String, focused: FocusState<Bool>.Binding,
        onPick: @escaping (Suggestion) -> Void, onSubmit: @escaping () -> Void, onDetails: ((String) -> Void)? = nil,
        onScan: (() -> Void)? = nil
    ) {
        self.onDetails = onDetails
        self.onScan = onScan
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
                if let onScan {
                    Button(action: onScan) { Image(systemName: "barcode.viewfinder") }
                        .tint(Tokens.accent)
                        .frame(minWidth: Tokens.minTouchTarget, minHeight: Tokens.minTouchTarget)
                        .accessibilityLabel("Scan barcode")
                        .accessibilityIdentifier("quickadd.scan")
                }
                if let onDetails {
                    Button { onDetails(text) } label: { Image(systemName: "slider.horizontal.3") }
                        .tint(Tokens.accent)
                        .frame(minWidth: Tokens.minTouchTarget, minHeight: Tokens.minTouchTarget)
                        .accessibilityLabel("Details")
                        .accessibilityIdentifier("quickadd.details")
                }
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
