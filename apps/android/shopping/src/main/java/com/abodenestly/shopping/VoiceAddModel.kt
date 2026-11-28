package com.abodenestly.shopping

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * DESIGN C6 states. [Listening] carries the live transcript (empty until something is heard, and while access is being
 * asked); [Parsed] the final transcript and what it came to; [NoItem] a transcript with no name in it.
 */
sealed interface VoicePhase {
    data class Listening(val partial: String = "") : VoicePhase
    data class Parsed(val transcript: String, val item: SpokenItem) : VoicePhase
    data class NoItem(val transcript: String) : VoicePhase
    data object NotHeard : VoicePhase
    data object PermissionDenied : VoicePhase
    data object Unsupported : VoicePhase
}

/**
 * What the sheet shows. [addFailed] is the item that could not be stored ("Could not save the change. Try again.");
 * [adding] is an Add running.
 */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Listening(),
    val addFailed: Boolean = false,
    val adding: Boolean = false,
)

/**
 * SHOP-14 voice sheet logic. Twins: iOS `VoiceAddModel`, web `VoiceAdd.tsx`; keep the rules in step. The recognizer and
 * the item write are injected ([add]: name, quantity, unit; true when stored), so this type never touches the microphone
 * or the database: `ListDetailViewModel.makeVoice` supplies the outbox write. The model never adds on its own; the person taps Add.
 */
class VoiceAddModel(
    private val device: SpeechRecognizerDevice,
    private val isOnline: () -> Boolean,
    private val locale: Locale,
    private val add: suspend (name: String, quantity: Double?, unit: String?) -> Boolean,
) {
    private val mutableState = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = mutableState.asStateFlow()

    /** One per [start]; a result or a permission answer that belongs to an older run is dropped. */
    private val attempt = AtomicInteger()
    private val addingNow = AtomicBoolean(false)

    private val phase: VoicePhase get() = mutableState.value.phase

    private fun setPhase(next: VoicePhase) = mutableState.update { it.copy(phase = next) }

    /** Opens the sheet or runs Try again: checks the recognizer, asks for access, then listens. */
    suspend fun start() {
        val mine: Int = attempt.incrementAndGet()
        device.stop()
        setPhase(VoicePhase.Listening())
        val offline: Boolean = !isOnline()
        if (!device.isAvailable(offline)) {
            setPhase(VoicePhase.Unsupported)
            return
        }
        if (!device.hasPermission()) {
            val granted: Boolean = device.requestPermission()
            // Closed, or Try again, while the system dialog was up.
            if (mine != attempt.get()) return
            if (!granted) {
                setPhase(VoicePhase.PermissionDenied)
                return
            }
        }
        if (mine != attempt.get()) return
        try {
            device.start(offline) { event -> receive(event, mine) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // Access was withdrawn between the check and the start.
            if (mine == attempt.get()) setPhase(VoicePhase.PermissionDenied)
        } catch (e: Exception) {
            if (mine == attempt.get()) setPhase(VoicePhase.Unsupported)
        }
    }

    /** The sheet is leaving: stop listening and drop anything still on its way. */
    fun stop() {
        attempt.incrementAndGet()
        device.stop()
    }

    /** Clears the transcript and chips and listens again (AC3). Waits while an Add is saving. */
    suspend fun tryAgain() {
        if (addingNow.get()) return
        mutableState.update { it.copy(addFailed = false) }
        start()
    }

    /** Writes the parsed item. True when stored, so the sheet closes; a second tap while one runs does nothing. */
    suspend fun addParsed(): Boolean {
        val parsed = phase as? VoicePhase.Parsed ?: return false
        if (!addingNow.compareAndSet(false, true)) return false
        // Cleared first, so a repeat failure is a change the sheet announces again.
        mutableState.update { it.copy(addFailed = false, adding = true) }
        try {
            val stored: Boolean = add(parsed.item.name, parsed.item.quantity, parsed.item.unit)
            if (!stored) mutableState.update { it.copy(addFailed = true) }
            return stored
        } finally {
            addingNow.set(false)
            mutableState.update { it.copy(adding = false) }
        }
    }

    private fun receive(event: VoiceEvent, forAttempt: Int) {
        if (forAttempt != attempt.get() || phase !is VoicePhase.Listening) return
        when (event) {
            is VoiceEvent.Partial -> setPhase(VoicePhase.Listening(event.text))
            is VoiceEvent.Final -> {
                device.stop()
                val item: SpokenItem? = VoiceAdd.parse(event.text, locale)
                setPhase(if (item != null) VoicePhase.Parsed(event.text, item) else VoicePhase.NoItem(event.text))
            }
            is VoiceEvent.Error -> {
                device.stop()
                setPhase(
                    when (event.kind) {
                        VoiceErrorKind.Denied -> VoicePhase.PermissionDenied
                        VoiceErrorKind.Unavailable -> VoicePhase.Unsupported
                        VoiceErrorKind.Other -> VoicePhase.NotHeard
                    },
                )
            }
        }
    }
}
