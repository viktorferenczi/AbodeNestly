import SwiftUI
import Testing
import ViewInspector
@testable import UI

@MainActor
private struct StepperHost: View {
    @State var text: String
    var body: some View { QuantityStepper(text: $text) }
}

@MainActor
@Suite("A6 QuantityStepper")
struct QuantityStepperTests {
    @Test("A6: shows the field with the given text and the labelled minus and plus buttons")
    func structure() throws {
        let view = try StepperHost(text: "2").inspect()
        #expect(try view.find(viewWithAccessibilityIdentifier: "stepper.field").textField().input() == "2")
        #expect(try view.find(viewWithAccessibilityIdentifier: "stepper.minus").accessibilityLabel().string() == "Decrease quantity")
        #expect(try view.find(viewWithAccessibilityIdentifier: "stepper.plus").accessibilityLabel().string() == "Increase quantity")
    }

    @Test("A6: parse accepts comma and rejects more than three decimals", arguments: [
        ("0,5", QuantityText.Parse.valid(0.5)), ("2", .valid(2)), ("", .valid(nil)), ("0.0001", .invalid), ("0", .invalid), ("1e3", .invalid),
    ])
    func parse(text: String, expected: QuantityText.Parse) {
        #expect(QuantityText.parse(text) == expected)
    }

    @Test("A6: text shows no float noise or trailing zeros", arguments: [(2.0 as Double?, "2"), (0.5, "0.5"), (1.2000000001, "1.2"), (nil, "")])
    func text(q: Double?, expected: String) {
        #expect(QuantityText.text(q) == expected)
    }
}
