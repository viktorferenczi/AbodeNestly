import Contract
import Foundation
import Testing
@testable import Shopping
@testable import Sync

// MARK: shared fixtures for the SHOP-14 suites

/// Holds `add` calls until `release` and answers each with `result`, so a test can tap twice while one write runs.
@MainActor
final class VoiceAddGate {
    private(set) var calls = 0
    private var waiting: CheckedContinuation<Void, Never>?

    func wait() async {
        calls += 1
        await withCheckedContinuation { waiting = $0 }
    }

    func release() {
        waiting?.resume()
        waiting = nil
    }
}

let enUS = Locale(identifier: "en_US")

/// A voice model over a seeded household and list, with a fake recognizer (no audio engine in tests).
@MainActor
final class VoiceHarness {
    let detail: DetailHarness
    let recognizer: FakeSpeechRecognizer
    let model: VoiceAddModel

    init(recognizer: FakeSpeechRecognizer = FakeSpeechRecognizer(), online: Bool = true) async throws {
        let detail = try await DetailHarness()
        self.detail = detail
        self.recognizer = recognizer
        model = detail.model.makeVoice(recognizer: recognizer, isOnline: { online }, locale: enUS)
    }

    func settle(until condition: @MainActor () -> Bool) async { await detail.settle(until: condition) }

    /// The queued `list_item` writes, decoded.
    func queued() async throws -> [(op: String, data: [String: JSONValue])] {
        try await detail.outbox().map { ($0.op, try JSONDecoder().decode([String: JSONValue].self, from: Data($0.data.utf8))) }
    }
}

/// Opens the model and plays a final transcript into it, ending in `parsed` for a good one.
@MainActor
func speak(_ h: VoiceHarness, _ text: String) async {
    await h.model.start()
    h.recognizer.emit(.final(text))
}

// MARK: parser

private let parseCases: [(String, Double?, String?, String)] = [
    ("2 litres milk", 2, "l", "Milk"),
    ("2 litres of milk", 2, "l", "Milk"),
    ("  2   Liters   of  Milk ", 2, "l", "Milk"),
    ("2 l milk", 2, "l", "Milk"),
    ("half a kilo of flour", 0.5, "kg", "Flour"),
    ("a half kilo flour", 0.5, "kg", "Flour"),
    ("half a lemon", 0.5, nil, "Lemon"),
    ("1,5 l oat milk", 1.5, "l", "Oat milk"),
    ("1.5 kg potatoes", 1.5, "kg", "Potatoes"),
    ("three bottles of water", 3, "bottle", "Water"),
    ("a can of tomatoes", 1, "can", "Tomatoes"),
    ("two tins of beans", 2, "can", "Beans"),
    ("500 grams of rice", 500, "g", "Rice"),
    ("250ml cream", 250, "ml", "Cream"),
    ("twelve pieces of toast", 12, "pcs", "Toast"),
    ("milk", nil, nil, "Milk"),
    ("constructor milk", nil, nil, "Constructor milk"),
    ("toString milk", nil, nil, "Tostring milk"),
    ("2 constructor milk", 2, nil, "Constructor milk"),
    ("can opener", nil, nil, "Can opener"),
    ("3 apples", 3, nil, "Apples"),
    ("0 apples", nil, nil, "0 apples"),
    ("one and a half litres of milk", 1, nil, "And a half litres of milk"),
]

@Suite("SHOP-14 parser")
struct VoiceParseTests {
    @Test("SHOP-14: AC1 parse reads quantity, unit and name like web parseSpoken", arguments: parseCases)
    func parse(text: String, quantity: Double?, unit: String?, name: String) {
        #expect(VoiceAdd.parse(text, locale: enUS) == SpokenItem(name: name, quantity: quantity, unit: unit))
    }

    @Test("SHOP-14: no name left is a failure", arguments: ["", "   ", "2 litres", "half a kilo"])
    func failure(text: String) {
        #expect(VoiceAdd.parse(text, locale: enUS) == nil)
    }

    @Test("SHOP-14: an empty or missing locale is the same as the default")
    func locales() {
        let expected = SpokenItem(name: "Milk", quantity: 2, unit: "l")
        #expect(VoiceAdd.parse("2 litres milk", locale: Locale(identifier: "")) == expected)
        #expect(VoiceAdd.parse("2 litres milk") == expected)
    }

    @Test("SHOP-14: the decimal mark is swapped by hand, so a comma and a point agree whatever the device locale")
    func decimalMark() {
        for id in ["en_US", "de_DE", "fr_FR"] {
            let loc = Locale(identifier: id)
            #expect(VoiceAdd.parse("1,5 l oat milk", locale: loc)?.quantity == 1.5)
            #expect(VoiceAdd.parse("1.5 l oat milk", locale: loc)?.quantity == 1.5)
        }
    }

    @Test("SHOP-14: every spoken unit maps to a C3 preset")
    func unitsArePresets() {
        for word in ["litre", "milliliters", "kilograms", "grams", "pcs", "packets", "bottle", "tins"] {
            let unit = VoiceAdd.parse("2 \(word) stuff", locale: enUS)?.unit
            #expect(unit != nil && ItemSheetLogic.unitPresets.contains(unit!))
        }
    }
}

// MARK: model

@MainActor
@Suite("SHOP-14 voice model", .serialized)
struct VoiceModelTests {
    @Test("SHOP-14: AC5 an unavailable recognizer is unsupported, and nothing starts")
    func unavailable() async throws {
        let h = try await VoiceHarness(recognizer: FakeSpeechRecognizer(available: false))
        await h.model.start()
        #expect(h.model.state == .unsupported)
        #expect(h.recognizer.startCount == 0)
    }

    @Test("SHOP-14: AC5 offline without on-device recognition is unsupported; with it, listening starts offline")
    func offline() async throws {
        let cannot = try await VoiceHarness(recognizer: FakeSpeechRecognizer(onDevice: false), online: false)
        await cannot.model.start()
        #expect(cannot.model.state == .unsupported && cannot.recognizer.startCount == 0)

        let can = try await VoiceHarness(recognizer: FakeSpeechRecognizer(onDevice: true), online: false)
        await can.model.start()
        #expect(can.model.state == .listening("") && can.recognizer.offlineStarts == [true])
    }

    @Test("SHOP-14: AC4 refused access is permissionDenied, and nothing starts")
    func denied() async throws {
        let h = try await VoiceHarness(recognizer: FakeSpeechRecognizer(grants: .denied))
        await h.model.start()
        #expect(h.model.state == .permissionDenied)
        #expect(h.recognizer.startCount == 0)
    }

    @Test("SHOP-14: partial results show as the live transcript, then final parses it")
    func partialThenFinal() async throws {
        let h = try await VoiceHarness()
        await h.model.start()
        #expect(h.model.state == .listening("") && h.recognizer.startCount == 1)
        h.recognizer.emit(.partial("2 litres"))
        #expect(h.model.state == .listening("2 litres"))
        h.recognizer.emit(.final("2 litres milk"))
        #expect(h.model.state == .parsed("2 litres milk", SpokenItem(name: "Milk", quantity: 2, unit: "l")))
        #expect(!h.recognizer.isRunning)
    }

    @Test("SHOP-14: a final with no name is noItem; a recognizer error is notHeard, denied or unsupported")
    func endings() async throws {
        let none = try await VoiceHarness()
        await speak(none, "2 litres")
        #expect(none.model.state == .noItem("2 litres"))

        for (kind, expected) in [
            (VoiceErrorKind.other, VoiceAddState.notHeard), (.denied, .permissionDenied), (.unavailable, .unsupported),
        ] {
            let h = try await VoiceHarness()
            await h.model.start()
            h.recognizer.emit(.error(kind))
            #expect(h.model.state == expected)
        }
    }

    @Test("SHOP-14: AC3 Try again clears the result and restarts the recognizer")
    func tryAgain() async throws {
        let h = try await VoiceHarness()
        await speak(h, "2 litres milk")
        await h.model.tryAgain()
        #expect(h.model.state == .listening(""))
        #expect(h.recognizer.startCount == 2 && h.recognizer.isRunning)
        h.recognizer.emit(.final("3 apples"))
        #expect(h.model.state == .parsed("3 apples", SpokenItem(name: "Apples", quantity: 3, unit: nil)))
    }

    @Test("SHOP-14: closing stops the recognizer, and a late event changes nothing")
    func closing() async throws {
        let h = try await VoiceHarness()
        await h.model.start()
        let before = h.recognizer.stopCount
        h.model.stop()
        #expect(h.recognizer.stopCount == before + 1 && !h.recognizer.isRunning)
        h.recognizer.emit(.final("2 litres milk"))
        #expect(h.model.state == .listening(""))
    }

    @Test("SHOP-14: closing while the access prompt is up never starts listening")
    func closeDuringPrompt() async throws {
        let recognizer = FakeSpeechRecognizer()
        recognizer.holdsAuthorization = true
        let h = try await VoiceHarness(recognizer: recognizer)
        let opening = Task { await h.model.start() }
        for _ in 0..<2000 where !recognizer.isAwaitingAuthorization { try await Task.sleep(for: .milliseconds(5)) }
        #expect(recognizer.isAwaitingAuthorization)
        h.model.stop()
        recognizer.answerAuthorization()
        await opening.value
        #expect(h.recognizer.startCount == 0)
    }

    @Test("SHOP-14: AC2 Add writes one list_item with quantity and unit, and the sheet may close")
    func addWrites() async throws {
        let h = try await VoiceHarness()
        await speak(h, "2 litres of milk")
        #expect(await h.model.addParsed())
        await h.settle { h.detail.model.unchecked.count == 1 }
        #expect(h.detail.model.unchecked.map(\.name) == ["Milk"])
        let item = try #require(h.detail.model.unchecked.first)
        #expect(item.quantity == 2 && item.unit == "l")
        let queued = try await h.queued()
        #expect(queued.count == 1 && queued[0].op == "upsert")
        #expect(queued[0].data["name"] == .string("Milk") && queued[0].data["unit"] == .string("l"))
        #expect(queued[0].data["quantity"] == .double(2) || queued[0].data["quantity"] == .int(2))
        #expect(await h.detail.kickCount == 1)
    }

    @Test("SHOP-14: Add without a parsed item does nothing")
    func addNothing() async throws {
        let h = try await VoiceHarness()
        await h.model.start()
        #expect(await h.model.addParsed() == false)
        #expect(try await h.queued().isEmpty)
    }

    @Test("SHOP-14: a second Add tap while one is saving writes once; Try again waits too")
    func doubleTap() async throws {
        let gate = VoiceAddGate()
        let recognizer = FakeSpeechRecognizer()
        let model = VoiceAddModel(recognizer: recognizer, isOnline: { true }, locale: enUS, add: { _, _, _ in
            await gate.wait()
            return true
        })
        await model.start()
        recognizer.emit(.final("2 litres milk"))
        let first = Task { await model.addParsed() }
        for _ in 0..<2000 where gate.calls == 0 { try await Task.sleep(for: .milliseconds(5)) }
        #expect(model.adding)
        #expect(await model.addParsed() == false)
        await model.tryAgain()
        #expect(gate.calls == 1 && recognizer.startCount == 1)
        gate.release()
        #expect(await first.value)
        #expect(!model.adding)
    }

    @Test("SHOP-14: the failure flag is cleared when an Add starts, so a repeat failure shows again")
    func repeatFailure() async throws {
        let gate = VoiceAddGate()
        let recognizer = FakeSpeechRecognizer()
        let model = VoiceAddModel(recognizer: recognizer, isOnline: { true }, locale: enUS, add: { _, _, _ in
            await gate.wait()
            return false
        })
        await model.start()
        recognizer.emit(.final("milk"))
        for round in 1...2 {
            let attempt = Task { await model.addParsed() }
            for _ in 0..<2000 where gate.calls < round { try await Task.sleep(for: .milliseconds(5)) }
            #expect(!model.addFailed)
            gate.release()
            #expect(await attempt.value == false)
            #expect(model.addFailed)
        }
    }

    @Test("SHOP-14: a store that cannot write shows the failure and leaves the sheet open")
    func storeFails() async throws {
        let h = try await VoiceHarness()
        await speak(h, "milk")
        await h.detail.db.close()
        #expect(await h.model.addParsed() == false)
        #expect(h.model.addFailed)
        #expect(h.detail.model.writeFailed)
    }
}
