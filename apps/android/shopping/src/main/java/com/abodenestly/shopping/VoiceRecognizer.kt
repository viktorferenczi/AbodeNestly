package com.abodenestly.shopping

/** Why recognition stopped without a result. */
enum class VoiceErrorKind {
    /** Microphone access was withdrawn or refused. */
    Denied,

    /** There is no recognizer for the language, or none can run now. */
    Unavailable,

    /** Anything else, such as no speech heard. */
    Other,
}

/** What a recognition run reports. [Final] and [Error] end the run. */
sealed interface VoiceEvent {
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Error(val kind: VoiceErrorKind) : VoiceEvent
}

/**
 * Speech recognition and its microphone permission behind one small interface, so tests use a fake and never start the
 * real `SpeechRecognizer` (SHOP-14). Twins: iOS `SpeechRecognizer`, web `VoiceRecognizer`. All calls come from the main
 * thread; [AndroidSpeechRecognizerDevice] is the app's implementation.
 */
interface SpeechRecognizerDevice {
    /** False when there is no recognizer, or [offline] and none can run on the device. */
    fun isAvailable(offline: Boolean): Boolean

    fun hasPermission(): Boolean

    /** Asks the system for the microphone; true when granted. A permission the system will not ask for again answers false at once. */
    suspend fun requestPermission(): Boolean

    /**
     * Starts one run; [onEvent] gets partial results, then one [VoiceEvent.Final] or one [VoiceEvent.Error]. A run that was
     * [stop]ped reports nothing. May throw [SecurityException] (access withdrawn) or [IllegalStateException].
     */
    fun start(offline: Boolean, onEvent: (VoiceEvent) -> Unit)

    /** Ends the run and releases the microphone. Safe to call when nothing runs. */
    fun stop()
}
