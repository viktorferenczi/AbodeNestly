import Foundation

/// Speech and microphone access together: voice add needs both, and either one refused (or restricted) is `denied`.
public enum VoiceAuthorization: Sendable, Equatable {
    case authorized, denied
}

/// Why recognition stopped without a result.
public enum VoiceErrorKind: Sendable, Equatable {
    /// Access was withdrawn or refused.
    case denied
    /// The recognizer or the audio input cannot run (no input, busy, not available now).
    case unavailable
    /// Anything else, such as no speech heard.
    case other
}

/// What a recognition run reports. `final` ends the run.
public enum VoiceEvent: Sendable, Equatable {
    case partial(String)
    case final(String)
    case error(VoiceErrorKind)
}

/// Speech recognition behind one small interface, so tests and previews use `FakeSpeechRecognizer` and never touch the
/// audio engine. Web twin: `VoiceRecognizer` in `web/src/shopping/VoiceRecognizer.ts`. Main-actor only: the Speech
/// callbacks arrive on other queues, and the Apple implementation hops back before it calls `onEvent`.
@MainActor
public protocol SpeechRecognizer: AnyObject {
    /// `false` when there is no recognizer for the language, or `offline` and it cannot run on the device.
    func isAvailable(offline: Bool) -> Bool
    /// Asks for speech and microphone access (the system prompts only the first time).
    func authorization() async -> VoiceAuthorization
    /// Starts one run; `onEvent` gets partial results, then one `final` or one `error`. A run that was `stop`ped reports nothing.
    func start(offline: Bool, onEvent: @escaping @MainActor (VoiceEvent) -> Void)
    /// Ends the run and releases the microphone. Safe to call when nothing runs.
    func stop()
}

/// A scripted recognizer for tests (TESTING §2: hand-written fakes, no mocking libraries).
@MainActor
public final class FakeSpeechRecognizer: SpeechRecognizer {
    public var available: Bool
    /// What `isAvailable(offline: true)` answers: whether the device can recognise without a network.
    public var onDevice: Bool
    public var grants: VoiceAuthorization
    public private(set) var startCount = 0
    public private(set) var stopCount = 0
    public private(set) var offlineStarts: [Bool] = []
    /// When set, `authorization` waits (like the system prompt) until `answerAuthorization`.
    public var holdsAuthorization = false
    private var pendingAuthorization: CheckedContinuation<Void, Never>?
    private var onEvent: (@MainActor (VoiceEvent) -> Void)?

    public init(available: Bool = true, onDevice: Bool = true, grants: VoiceAuthorization = .authorized) {
        self.available = available
        self.onDevice = onDevice
        self.grants = grants
    }

    public var isRunning: Bool { onEvent != nil }

    public func isAvailable(offline: Bool) -> Bool { available && (!offline || onDevice) }

    public var isAwaitingAuthorization: Bool { pendingAuthorization != nil }

    public func authorization() async -> VoiceAuthorization {
        if holdsAuthorization { await withCheckedContinuation { pendingAuthorization = $0 } }
        return grants
    }

    public func answerAuthorization() {
        pendingAuthorization?.resume()
        pendingAuthorization = nil
    }

    public func start(offline: Bool, onEvent: @escaping @MainActor (VoiceEvent) -> Void) {
        startCount += 1
        offlineStarts.append(offline)
        self.onEvent = onEvent
    }

    public func stop() {
        stopCount += 1
        onEvent = nil
    }

    /// Plays one event; ignored once stopped, like the real one.
    public func emit(_ event: VoiceEvent) { onEvent?(event) }
}
