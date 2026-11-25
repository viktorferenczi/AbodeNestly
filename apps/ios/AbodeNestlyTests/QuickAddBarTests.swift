import SwiftUI
import Testing
import ViewInspector
@testable import UI

@MainActor
private struct BarHost: View {
    @State var text: String
    let suggestions: [QuickAddBar.Suggestion]
    let picked: Box
    var withDetails = false
    @FocusState private var focused: Bool

    var body: some View {
        QuickAddBar(
            text: $text, suggestions: suggestions, placeholder: "Add item", focused: $focused,
            onPick: { picked.picks.append($0.label) }, onSubmit: { picked.submits += 1 },
            onDetails: withDetails ? { picked.details.append($0) } : nil
        )
    }
}

@MainActor
final class Box {
    var picks: [String] = []
    var submits = 0
    var details: [String] = []
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

    @Test("SHOP-03: Details shows only with onDetails and hands over the typed text")
    func details() throws {
        #expect((try? bar(text: "Eggs").find(viewWithAccessibilityIdentifier: "quickadd.details")) == nil)
        let box = Box()
        let view = try BarHost(text: "Eggs", suggestions: [], picked: box, withDetails: true).inspect()
        try view.find(viewWithAccessibilityIdentifier: "quickadd.details").button().tap()
        #expect(box.details == ["Eggs"])
    }
}
