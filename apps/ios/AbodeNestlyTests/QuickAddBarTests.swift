import SwiftUI
import Testing
import ViewInspector
@testable import UI

@MainActor
private struct BarHost: View {
    @State var text: String
    let suggestions: [QuickAddBar.Suggestion]
    let picked: Box
    @FocusState private var focused: Bool

    var body: some View {
        QuickAddBar(
            text: $text, suggestions: suggestions, placeholder: "Add item", focused: $focused,
            onPick: { picked.picks.append($0.label) }, onSubmit: { picked.submits += 1 }
        )
    }
}

@MainActor
final class Box {
    var picks: [String] = []
    var submits = 0
}

@MainActor
@Suite("A6 QuickAddBar")
struct QuickAddBarTests {
    private func bar(text: String = "", suggestions: [String] = [], box: Box = Box()) throws -> InspectableView<ViewType.ClassifiedView> {
        try BarHost(text: text, suggestions: suggestions.map { .init(id: $0.lowercased(), label: $0) }, picked: box).inspect()
    }

    @Test("A6: suggestions render above the field and only when there are some")
    func suggestionsAboveField() throws {
        let view = try bar(text: "mi", suggestions: ["Milk", "Milkshake"])
        _ = try view.find(viewWithAccessibilityIdentifier: "quickadd.suggestion.Milk")
        _ = try view.find(viewWithAccessibilityIdentifier: "quickadd.suggestion.Milkshake")
        #expect((try? bar().find(viewWithAccessibilityIdentifier: "quickadd.suggestions")) == nil)
    }

    @Test("A6: tapping a suggestion calls onPick, Add calls onSubmit")
    func callbacks() throws {
        let box = Box()
        let view = try bar(text: "mi", suggestions: ["Milk"], box: box)
        try view.find(viewWithAccessibilityIdentifier: "quickadd.suggestion.Milk").button().tap()
        try view.find(viewWithAccessibilityIdentifier: "quickadd.add").button().tap()
        #expect(box.picks == ["Milk"] && box.submits == 1)
    }

    @Test("A6: Add is disabled on a blank field")
    func addDisabled() throws {
        #expect(try bar(text: "  ").find(viewWithAccessibilityIdentifier: "quickadd.add").button().isDisabled())
        #expect(try !bar(text: "Eggs").find(viewWithAccessibilityIdentifier: "quickadd.add").button().isDisabled())
    }
}
