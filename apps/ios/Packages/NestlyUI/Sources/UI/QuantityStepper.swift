import SwiftUI

/// Quantity text <-> number, shared by the stepper and the item sheets. Locale-independent on purpose: "0,5" and
/// "0.5" both mean a half. Web twin: `web/src/lib/quantity.ts`.
public enum QuantityText {
    public enum Parse: Equatable, Sendable {
        case valid(Double?)
        case invalid
    }

    /// Blank is "no quantity". Otherwise a number above 0 with at most 3 decimals; a comma works as the decimal mark.
    public static func parse(_ text: String) -> Parse {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return .valid(nil) }
        guard t.wholeMatch(of: /[0-9]+([.,][0-9]{1,3})?/) != nil,
              let value = Double(t.replacingOccurrences(of: ",", with: ".")), value > 0
        else { return .invalid }
        return .valid(value)
    }

    /// The text a quantity is shown as in the field (no float noise, no trailing zeros).
    public static func text(_ quantity: Double?) -> String {
        guard let quantity else { return "" }
        let rounded = (quantity * 1000).rounded() / 1000
        if rounded == rounded.rounded() { return String(Int(rounded)) }
        return String(rounded)
    }

    /// Stepper arithmetic on the field text: + on empty gives 1, − from 1 or below clears. Unparseable text stays.
    public static func step(_ text: String, by direction: Int) -> String {
        guard case .valid(let current) = parse(text) else { return text }
        if direction >= 0 { return Self.text((current ?? 0) + 1) }
        guard let current, current - 1 > 0 else { return "" }
        return Self.text(current - 1)
    }
}

/// DESIGN A6 Stepper: − value + with long-press repeat. The value is an editable decimal field so 0.5 kg works. Named
/// so it does not clash with SwiftUI's `Stepper`; pantry reuses it.
public struct QuantityStepper: View {
    @Binding private var text: String
    @State private var repeating: Task<Void, Never>?
    @State private var held = false

    public init(text: Binding<String>) { _text = text }

    public var body: some View {
        HStack(spacing: 8) {
            button(systemName: "minus", label: "Decrease quantity", id: "stepper.minus", direction: -1)
            TextField("Quantity", text: $text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.center)
                .font(.body.monospacedDigit())
                .frame(minWidth: 64, minHeight: Tokens.minTouchTarget)
                .background(Tokens.surface2, in: RoundedRectangle(cornerRadius: Tokens.cardRadius))
                .accessibilityLabel("Quantity")
                .accessibilityIdentifier("stepper.field")
            button(systemName: "plus", label: "Increase quantity", id: "stepper.plus", direction: 1)
        }
    }

    private func button(systemName: String, label: String, id: String, direction: Int) -> some View {
        // A tap steps once. A hold steps after 0.4 s and then every 0.1 s; the tap that ends a hold is swallowed so it
        // does not step again (web twin: `held` in `web/src/ui/Stepper.tsx`).
        Button {
            if held { held = false } else { text = QuantityText.step(text, by: direction) }
        } label: {
            Image(systemName: systemName)
                .font(.body.weight(.semibold))
                .foregroundStyle(Tokens.accent)
                .frame(width: Tokens.minTouchTarget, height: Tokens.minTouchTarget)
                .background(Tokens.accentBg, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard repeating == nil else { return }
                    repeating = Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(400))
                        while !Task.isCancelled {
                            held = true
                            text = QuantityText.step(text, by: direction)
                            try? await Task.sleep(for: .milliseconds(100))
                        }
                    }
                }
                .onEnded { _ in
                    repeating?.cancel()
                    repeating = nil
                }
        )
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }
}
