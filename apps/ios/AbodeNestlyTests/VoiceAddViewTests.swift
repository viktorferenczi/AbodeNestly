import SwiftUI
import Testing
import ViewInspector
@testable import Shopping
@testable import Sync

/// Records what the sheet asked for.
@MainActor
private final class Calls {
    var closed = 0
    var typed = 0
    var settings = 0
}

@MainActor
private func inspect(_ h: VoiceHarness, _ calls: Calls = Calls()) throws -> InspectableView<ViewType.ClassifiedView> {
    try VoiceAddView(
        model: h.model, onClose: { calls.closed += 1 }, onTypeInstead: { calls.typed += 1 }, openSettings: { calls.settings += 1 }
    ).inspect()
}

@MainActor
private func has(_ h: VoiceHarness, _ id: String) -> Bool {
    (try? inspect(h).find(viewWithAccessibilityIdentifier: id)) != nil
}

@MainActor
private func text(_ h: VoiceHarness, _ id: String) throws -> String {
    try inspect(h).find(viewWithAccessibilityIdentifier: id).text().string()
}

/// DESIGN C6, one case per state and interaction.
@MainActor
@Suite("SHOP-14 voice view", .serialized)
struct VoiceAddViewTests {
    @Test("SHOP-14: listening shows the title, Listening…, the live transcript and Cancel")
    func listening() async throws {
        let h = try await VoiceHarness()
        await h.model.start()
        h.recognizer.emit(.partial("2 litres"))
        #expect(try text(h, "voice.title") == "Voice add")
        #expect(try text(h, "voice.listening") == "Listening…")
        #expect(try text(h, "voice.transcript") == "2 litres")
        #expect(has(h, "voice.indicator") && has(h, "voice.cancel"))
        #expect(!has(h, "voice.add"))
        let calls = Calls()
        try inspect(h, calls).find(viewWithAccessibilityIdentifier: "voice.cancel").button().tap()
        #expect(calls.closed == 1 && !h.recognizer.isRunning)
    }

    @Test("SHOP-14: AC1 parsed shows the transcript and chips for quantity, unit and name")
    func parsed() async throws {
        let h = try await VoiceHarness()
        await speak(h, "2 litres milk")
        #expect(try text(h, "voice.transcript") == "2 litres milk")
        #expect(try text(h, "voice.chip.quantity") == "2")
        #expect(try text(h, "voice.chip.unit") == "l")
        #expect(try text(h, "voice.chip.name") == "Milk")
        #expect(has(h, "voice.add") && has(h, "voice.tryagain"))
    }

    @Test("SHOP-14: a parsed item without a quantity or unit shows only the name chip")
    func parsedNameOnly() async throws {
        let h = try await VoiceHarness()
        await speak(h, "milk")
        #expect(!has(h, "voice.chip.quantity") && !has(h, "voice.chip.unit"))
        #expect(try text(h, "voice.chip.name") == "Milk")
    }

    @Test("SHOP-14: AC2 Add writes one list_item with quantity and unit, and the sheet closes")
    func add() async throws {
        let h = try await VoiceHarness()
        await speak(h, "2 litres milk")
        let calls = Calls()
        try inspect(h, calls).find(viewWithAccessibilityIdentifier: "voice.add").button().tap()
        await h.settle { calls.closed == 1 }
        #expect(calls.closed == 1)
        await h.settle { h.detail.model.unchecked.count == 1 }
        #expect(h.detail.model.unchecked.map(\.name) == ["Milk"])
        let queued = try await h.queued()
        #expect(queued.count == 1 && queued[0].data["unit"] == .string("l"))
    }

    @Test("SHOP-14: a failed Add shows the error and keeps the sheet open")
    func addFails() async throws {
        let h = try await VoiceHarness()
        await speak(h, "milk")
        await h.detail.db.close()
        let calls = Calls()
        try inspect(h, calls).find(viewWithAccessibilityIdentifier: "voice.add").button().tap()
        await h.settle { h.model.addFailed }
        #expect(try text(h, "voice.error") == "Could not save the change. Try again.")
        #expect(calls.closed == 0)
    }

    @Test("SHOP-14: AC3 Try again clears the chips and listens again")
    func tryAgain() async throws {
        let h = try await VoiceHarness()
        await speak(h, "2 litres milk")
        try inspect(h).find(viewWithAccessibilityIdentifier: "voice.tryagain").button().tap()
        await h.settle { h.recognizer.startCount == 2 }
        #expect(h.recognizer.startCount == 2)
        #expect(h.model.state == .listening(""))
        #expect(has(h, "voice.listening") && !has(h, "voice.chip.name"))
    }

    @Test("SHOP-14: Add and Try again are disabled while an Add is saving")
    func busy() async throws {
        let gate = VoiceAddGate()
        let recognizer = FakeSpeechRecognizer()
        let model = VoiceAddModel(recognizer: recognizer, isOnline: { true }, locale: enUS, add: { _, _, _ in
            await gate.wait()
            return true
        })
        await model.start()
        recognizer.emit(.final("milk"))
        let saving = Task { await model.addParsed() }
        for _ in 0..<2000 where !model.adding { try await Task.sleep(for: .milliseconds(5)) }
        let view = try VoiceAddView(model: model, onClose: {}, onTypeInstead: {}, openSettings: {}).inspect()
        #expect(try view.find(viewWithAccessibilityIdentifier: "voice.tryagain").button().isDisabled())
        #expect(try view.find(viewWithAccessibilityIdentifier: "voice.add").button().isDisabled())
        gate.release()
        _ = await saving.value
    }

    @Test("SHOP-14: a transcript with no name says so and offers only Try again")
    func noItem() async throws {
        let h = try await VoiceHarness()
        await speak(h, "2 litres")
        #expect(try text(h, "voice.hint") == "Didn't catch an item. Try again.")
        #expect(try text(h, "voice.transcript") == "2 litres")
        #expect(has(h, "voice.tryagain") && !has(h, "voice.add"))
    }

    @Test("SHOP-14: not heard says so and offers Try again")
    func notHeard() async throws {
        let h = try await VoiceHarness()
        await h.model.start()
        h.recognizer.emit(.error(.other))
        #expect(try text(h, "voice.hint") == "Didn't catch that.")
        #expect(has(h, "voice.tryagain"))
    }

    @Test("SHOP-14: AC4 permission-denied shows its copy, Open Settings and Type instead")
    func permissionDenied() async throws {
        let h = try await VoiceHarness(recognizer: FakeSpeechRecognizer(grants: .denied))
        await h.model.start()
        #expect(try text(h, "voice.blocked.message") == "Microphone or speech access is off. Allow it in Settings, or type the item instead.")
        let calls = Calls()
        try inspect(h, calls).find(viewWithAccessibilityIdentifier: "voice.settings").button().tap()
        try inspect(h, calls).find(viewWithAccessibilityIdentifier: "voice.typeinstead").button().tap()
        #expect(calls.settings == 1 && calls.typed == 1)
    }

    @Test("SHOP-14: AC5 unsupported shows its copy; Type instead calls the focus hook and there is no Settings button")
    func unsupported() async throws {
        let h = try await VoiceHarness(recognizer: FakeSpeechRecognizer(available: false))
        await h.model.start()
        #expect(try text(h, "voice.blocked.message") == "Voice input isn't available here. Type the item instead.")
        #expect(!has(h, "voice.settings"))
        let calls = Calls()
        try inspect(h, calls).find(viewWithAccessibilityIdentifier: "voice.typeinstead").button().tap()
        #expect(calls.typed == 1)
    }

    @Test("SHOP-14: the quick-add bar shows a Voice add mic only when a voice sheet can be built")
    func micButton() async throws {
        let h = try await VoiceHarness()
        let without = try ListDetailView(model: h.detail.model, title: "Groceries", isOnline: true).inspect()
        #expect((try? without.find(viewWithAccessibilityIdentifier: "quickadd.voice")) == nil)
        let model = h.detail.model
        let recognizer = h.recognizer
        let with = try ListDetailView(
            model: model, title: "Groceries", isOnline: true,
            makeVoice: { model.makeVoice(recognizer: recognizer, isOnline: { true }) }
        ).inspect()
        _ = try with.find(viewWithAccessibilityIdentifier: "quickadd.voice").button()
    }
}
