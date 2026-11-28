package com.abodenestly.shopping

import kotlinx.coroutines.CompletableDeferred

/** A scripted recognizer (TESTING §2: hand-written fakes, no mocking libraries). The real `SpeechRecognizer` never runs in tests. */
class FakeSpeechRecognizerDevice(
    var available: Boolean = true,
    /** What `isAvailable(offline = true)` answers: whether the device can recognise without a network. */
    var onDevice: Boolean = true,
    var granted: Boolean = true,
    /** What the system dialog answers while the permission is not granted. */
    var grantsOnRequest: Boolean = true,
    var startError: Exception? = null,
) : SpeechRecognizerDevice {
    @Volatile var startCount = 0
    @Volatile var stopCount = 0
    @Volatile var requestCount = 0
    val offlineStarts = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    /** When set, [requestPermission] waits (like the system dialog) until it completes. */
    @Volatile var permissionGate: CompletableDeferred<Unit>? = null
    @Volatile private var onEvent: ((VoiceEvent) -> Unit)? = null

    val isRunning: Boolean get() = onEvent != null

    override fun isAvailable(offline: Boolean): Boolean = available && (!offline || onDevice)

    override fun hasPermission(): Boolean = granted

    override suspend fun requestPermission(): Boolean {
        requestCount++
        permissionGate?.await()
        if (grantsOnRequest) granted = true
        return granted
    }

    override fun start(offline: Boolean, onEvent: (VoiceEvent) -> Unit) {
        startError?.let { throw it }
        startCount++
        offlineStarts += offline
        this.onEvent = onEvent
    }

    override fun stop() {
        stopCount++
        onEvent = null
    }

    /** Plays one event; ignored once stopped, like the real one. */
    fun emit(event: VoiceEvent) {
        onEvent?.invoke(event)
    }
}
