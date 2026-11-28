import AVFoundation
import Foundation
import Speech

/// `SFSpeechRecognizer` fed by `AVAudioEngine` behind `SpeechRecognizer` (SHOP-14). Offline it asks for on-device
/// recognition, which `isAvailable(offline:)` has already checked is supported. Not exercised by tests (no audio engine
/// in CI); `FakeSpeechRecognizer` stands in.
///
/// Speech and audio callbacks arrive on their own queues. They are built in `nonisolated` helpers, so they are not
/// main-actor closures (Swift 6 would trap when one ran off the main actor), and each result hops back with
/// `Task { @MainActor in }`. A `generation` counter drops what a stopped run still delivers.
@MainActor
public final class AppleSpeechRecognizer: SpeechRecognizer {
    /// `SFSpeechAudioBufferRecognitionRequest` is not `Sendable`. `append` is the one call made off the main actor (from
    /// the audio tap), and Apple documents it as safe to call from the audio thread; everything else stays on the main actor.
    private final class RequestBox: @unchecked Sendable {
        let request = SFSpeechAudioBufferRecognitionRequest()
    }

    private let recognizer: SFSpeechRecognizer?
    private var engine: AVAudioEngine?
    private var box: RequestBox?
    private var task: SFSpeechRecognitionTask?
    private var generation = 0
    private var onEvent: (@MainActor (VoiceEvent) -> Void)?

    /// Recognises in `locale` (the device's by default); `nil` recognizer when Speech has no model for it.
    public init(locale: Locale = .current) {
        recognizer = SFSpeechRecognizer(locale: locale)
    }

    public func isAvailable(offline: Bool) -> Bool {
        guard let recognizer, recognizer.isAvailable else { return false }
        return !offline || recognizer.supportsOnDeviceRecognition
    }

    public func authorization() async -> VoiceAuthorization {
        guard await Self.speechAuthorized() else { return .denied }
        return await AVAudioApplication.requestRecordPermission() ? .authorized : .denied
    }

    public func start(offline: Bool, onEvent: @escaping @MainActor (VoiceEvent) -> Void) {
        stop()
        guard let recognizer else {
            onEvent(.error(.unavailable))
            return
        }
        generation += 1
        let generation = generation
        self.onEvent = onEvent

        let box = RequestBox()
        box.request.shouldReportPartialResults = true
        box.request.addsPunctuation = false
        box.request.requiresOnDeviceRecognition = offline && recognizer.supportsOnDeviceRecognition
        let engine = AVAudioEngine()
        self.box = box
        self.engine = engine
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            try Self.attachTap(engine: engine, box: box)
            engine.prepare()
            try engine.start()
        } catch {
            stop()
            onEvent(.error(.unavailable))
            return
        }
        task = Self.recognize(recognizer, box: box) { [weak self] event in
            Task { @MainActor in self?.deliver(event, generation: generation) }
        }
    }

    public func stop() {
        generation += 1
        onEvent = nil
        let hadSession = engine != nil
        if let engine {
            if engine.isRunning { engine.stop() }
            engine.inputNode.removeTap(onBus: 0)
        }
        box?.request.endAudio()
        task?.cancel()
        engine = nil
        box = nil
        task = nil
        if hadSession { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }

    private func deliver(_ event: VoiceEvent, generation: Int) {
        guard generation == self.generation else { return }
        onEvent?(event)
    }

    // MARK: off the main actor

    /// `requestAuthorization` calls back on an arbitrary queue, so the closure must not be main-actor isolated.
    private nonisolated static func speechAuthorized() async -> Bool {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in continuation.resume(returning: status == .authorized) }
        }
    }

    /// The tap runs on the audio thread. No input (the simulator without a microphone) has a zero sample rate and would
    /// raise in `installTap`, so it throws instead.
    private nonisolated static func attachTap(engine: AVAudioEngine, box: RequestBox) throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw AudioInputError.none }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in box.request.append(buffer) }
    }

    private nonisolated static func recognize(
        _ recognizer: SFSpeechRecognizer, box: RequestBox, emit: @escaping @Sendable (VoiceEvent) -> Void
    ) -> SFSpeechRecognitionTask {
        recognizer.recognitionTask(with: box.request) { result, error in
            if let result {
                let text = result.bestTranscription.formattedString
                emit(result.isFinal ? .final(text) : .partial(text))
            } else if error != nil {
                // A cancelled run (`stop`) also lands here; its generation is stale by then and `deliver` drops it.
                emit(.error(.other))
            }
        }
    }

    private enum AudioInputError: Error { case none }
}
