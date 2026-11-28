package com.abodenestly.shopping

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

/** Which `RecognitionListener` callback a result came from. */
enum class RecognizerCallback { Partial, Results, Error }

/**
 * SHOP-14 recognition on `android.speech.SpeechRecognizer`. Create it with [rememberAndroidSpeechRecognizerDevice], which
 * wires the permission launcher. Not covered by unit tests (Robolectric has no recognition service): the rules live in
 * [event], which is pure, and in `VoiceAddModel`, which is tested against a fake [SpeechRecognizerDevice].
 *
 * Offline: API 31+ uses the on-device recognizer when the device has one (`createOnDeviceSpeechRecognizer`); older
 * versions have no way to ask, so they set `EXTRA_PREFER_OFFLINE` and keep whatever the recognition service does.
 * The recognizer is created and used on the main thread only.
 */
class AndroidSpeechRecognizerDevice(context: Context) : SpeechRecognizerDevice {
    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Set by [rememberAndroidSpeechRecognizerDevice]: shows the system permission dialog. */
    internal var launchPermission: () -> Unit = {}
    private var permissionAnswer: CompletableDeferred<Boolean>? = null

    private var recognizer: SpeechRecognizer? = null

    /** One per run; a callback of an older run (after [stop]) is dropped. */
    private var runId = 0

    override fun isAvailable(offline: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val onDevice: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            return if (offline) onDevice else onDevice || SpeechRecognizer.isRecognitionAvailable(appContext)
        }
        return SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestPermission(): Boolean {
        if (hasPermission()) return true
        val answer = CompletableDeferred<Boolean>()
        permissionAnswer = answer
        launchPermission()
        return try {
            answer.await()
        } finally {
            if (permissionAnswer === answer) permissionAnswer = null
        }
    }

    internal fun onPermissionResult(granted: Boolean) {
        permissionAnswer?.complete(granted)
    }

    override fun start(offline: Boolean, onEvent: (VoiceEvent) -> Unit) {
        onMain {
            stopNow()
            val mine: Int = ++runId
            val created: SpeechRecognizer = create(offline)
            recognizer = created
            created.setRecognitionListener(listener(mine, onEvent))
            val intent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            created.startListening(intent)
        }
    }

    private fun create(offline: Boolean): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val onDevice: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            val service: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)
            if (useOnDevice(Build.VERSION.SDK_INT, offline, onDevice, service)) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(appContext)
    }

    private fun listener(mine: Int, sink: (VoiceEvent) -> Unit): RecognitionListener = object : RecognitionListener {
        private fun report(callback: RecognizerCallback, bundle: Bundle?, error: Int) {
            if (mine != runId) return
            val text: String? = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            event(callback, text, error)?.let(sink)
        }

        override fun onPartialResults(partialResults: Bundle?) = report(RecognizerCallback.Partial, partialResults, 0)
        override fun onResults(results: Bundle?) = report(RecognizerCallback.Results, results, 0)
        override fun onError(error: Int) = report(RecognizerCallback.Error, null, error)
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun stop() {
        onMain { stopNow() }
    }

    private fun stopNow() {
        runId++
        val old: SpeechRecognizer = recognizer ?: return
        recognizer = null
        try {
            old.cancel()
        } finally {
            old.destroy()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE are API 31 constants; the
        // numbers are written out so nothing newer than minSdk is referenced.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /**
         * Maps one callback to an event (pure, so it is unit tested). A result with text ends the run; one without text is
         * "didn't catch that". An error ends the run even when it arrives after partial results. A partial result with no
         * text is nothing. [errorCode] is the `SpeechRecognizer.ERROR_*` value for [RecognizerCallback.Error].
         */
        fun event(callback: RecognizerCallback, text: String?, errorCode: Int = 0): VoiceEvent? = when (callback) {
            RecognizerCallback.Error -> VoiceEvent.Error(errorKind(errorCode))
            RecognizerCallback.Results ->
                if (text.isNullOrBlank()) VoiceEvent.Error(VoiceErrorKind.Other) else VoiceEvent.Final(text)
            RecognizerCallback.Partial -> if (text.isNullOrBlank()) null else VoiceEvent.Partial(text)
        }

        /**
         * Whether to create the on-device recognizer (pure, so it is unit tested): API 31+, one is installed, and either the
         * person wants offline or there is no recognition service for `createSpeechRecognizer` to bind to.
         */
        fun useOnDevice(sdk: Int, offline: Boolean, onDevice: Boolean, service: Boolean): Boolean =
            sdk >= Build.VERSION_CODES.S && onDevice && (offline || !service)

        /** Access withdrawn is denied; a language the recognizer lacks is unavailable; no match, silence and the rest are "didn't catch that". */
        fun errorKind(errorCode: Int): VoiceErrorKind = when (errorCode) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceErrorKind.Denied
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> VoiceErrorKind.Unavailable
            else -> VoiceErrorKind.Other
        }
    }
}

/** An [AndroidSpeechRecognizerDevice] wired to the system permission dialog; one per composition of the sheet. */
@Composable
fun rememberAndroidSpeechRecognizerDevice(): SpeechRecognizerDevice {
    val context = LocalContext.current
    val device = remember(context) { AndroidSpeechRecognizerDevice(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        device.onPermissionResult(granted)
    }
    SideEffect { device.launchPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) } }
    return device
}
