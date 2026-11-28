import Testing
@testable import Shopping

/// SHOP-14: how a Speech callback `(result, error)` becomes a `VoiceEvent`.
@Suite("SHOP-14 speech callback mapping")
struct AppleSpeechRecognizerTests {
    @Test("SHOP-14: a partial result is a live update")
    func partial() {
        #expect(AppleSpeechRecognizer.event(isFinal: false, text: "2 litres", hasError: false) == .partial("2 litres"))
    }

    @Test("SHOP-14: a final result ends the run with its text")
    func final() {
        #expect(AppleSpeechRecognizer.event(isFinal: true, text: "2 litres milk", hasError: false) == .final("2 litres milk"))
    }

    @Test("SHOP-14: a final result wins over an error that arrives with it")
    func finalWinsOverError() {
        #expect(AppleSpeechRecognizer.event(isFinal: true, text: "milk", hasError: true) == .final("milk"))
    }

    @Test("SHOP-14: an error with a partial result still reports the error")
    func errorWithPartial() {
        #expect(AppleSpeechRecognizer.event(isFinal: false, text: "2 lit", hasError: true) == .error(.other))
    }

    @Test("SHOP-14: an error with no result reports the error")
    func errorOnly() {
        #expect(AppleSpeechRecognizer.event(isFinal: nil, text: nil, hasError: true) == .error(.other))
    }

    @Test("SHOP-14: no result and no error emits nothing")
    func nothing() {
        #expect(AppleSpeechRecognizer.event(isFinal: nil, text: nil, hasError: false) == nil)
    }
}
