import Foundation
import Observation

/// DESIGN C6 states. `listening` carries the live transcript (empty until something is heard, and while access is being
/// asked); `parsed` the final transcript and what it came to; `noItem` a transcript with no name in it.
public enum VoiceAddState: Equatable, Sendable {
    case listening(String)
    case parsed(String, SpokenItem)
    case noItem(String)
    case notHeard
    case permissionDenied
    case unsupported
}

/// SHOP-14 voice sheet logic. Web twin: the `VoiceAdd` component. The recognizer and the item write are injected, so
/// this type never touches the audio engine or the database: `ListDetailModel.makeVoice` supplies the outbox write.
/// The model never adds on its own; the person taps Add.
@Observable
@MainActor
public final class VoiceAddModel {
    public private(set) var state: VoiceAddState = .listening("")
    /// The item could not be stored; shown as "Could not save the change. Try again."
    public private(set) var addFailed = false
    /// An Add is running; a second tap must not make a second item, and Try again waits.
    public private(set) var adding = false

    @ObservationIgnored private let recognizer: any SpeechRecognizer
    @ObservationIgnored private let isOnline: @MainActor () -> Bool
    @ObservationIgnored private let locale: Locale
    @ObservationIgnored private let add: @MainActor (_ name: String, _ quantity: Double?, _ unit: String?) async -> Bool
    /// One per `start`; a result or a permission answer that belongs to an older run is dropped.
    @ObservationIgnored private var attempt = 0

    public init(
        recognizer: any SpeechRecognizer, isOnline: @escaping @MainActor () -> Bool, locale: Locale = .current,
        add: @escaping @MainActor (_ name: String, _ quantity: Double?, _ unit: String?) async -> Bool
    ) {
        self.recognizer = recognizer
        self.isOnline = isOnline
        self.locale = locale
        self.add = add
    }

    /// Opens the sheet or runs Try again: checks the recognizer, asks for access, then listens.
    public func start() async {
        attempt += 1
        let mine = attempt
        recognizer.stop()
        state = .listening("")
        let offline = !isOnline()
        guard recognizer.isAvailable(offline: offline) else {
            state = .unsupported
            return
        }
        let access = await recognizer.authorization()
        // Closed, or Try again, while the system prompt was up.
        guard mine == attempt else { return }
        guard access == .authorized else {
            state = .permissionDenied
            return
        }
        recognizer.start(offline: offline) { [weak self] event in self?.receive(event, attempt: mine) }
    }

    /// The sheet is leaving: stop listening and drop anything still on its way.
    public func stop() {
        attempt += 1
        recognizer.stop()
    }

    /// Clears the transcript and chips and listens again (AC3). Waits while an Add is saving.
    public func tryAgain() async {
        guard !adding else { return }
        addFailed = false
        await start()
    }

    /// Writes the parsed item. `true` when stored, so the sheet closes; a second tap while one runs does nothing.
    public func addParsed() async -> Bool {
        guard case .parsed(_, let item) = state, !adding else { return false }
        // Cleared first, so a repeat failure is a change the view announces again.
        addFailed = false
        adding = true
        defer { adding = false }
        guard await add(item.name, item.quantity, item.unit) else {
            addFailed = true
            return false
        }
        return true
    }

    private func receive(_ event: VoiceEvent, attempt: Int) {
        guard attempt == self.attempt, case .listening = state else { return }
        switch event {
        case .partial(let text):
            state = .listening(text)
        case .final(let text):
            recognizer.stop()
            if let item = VoiceAdd.parse(text, locale: locale) {
                state = .parsed(text, item)
            } else {
                state = .noItem(text)
            }
        case .error(let kind):
            recognizer.stop()
            switch kind {
            case .denied: state = .permissionDenied
            case .unavailable: state = .unsupported
            case .other: state = .notHeard
            }
        }
    }
}
