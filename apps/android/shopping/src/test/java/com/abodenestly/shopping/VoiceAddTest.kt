package com.abodenestly.shopping

import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private val EN = Locale.US

/** SHOP-14 parser, recognizer-event mapping and model rules (DESIGN C6), table-driven where it is pure. The recognizer is a fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAddTest {
    private data class Case(val text: String, val quantity: Double?, val unit: String?, val name: String)

    private val cases = listOf(
        Case("2 litres milk", 2.0, "l", "Milk"),
        Case("2 litres of milk", 2.0, "l", "Milk"),
        Case("  2   Liters   of  Milk ", 2.0, "l", "Milk"),
        Case("2 l milk", 2.0, "l", "Milk"),
        Case("half a kilo of flour", 0.5, "kg", "Flour"),
        Case("a half kilo flour", 0.5, "kg", "Flour"),
        Case("half a lemon", 0.5, null, "Lemon"),
        Case("1,5 l oat milk", 1.5, "l", "Oat milk"),
        Case("1.5 kg potatoes", 1.5, "kg", "Potatoes"),
        Case("three bottles of water", 3.0, "bottle", "Water"),
        Case("a can of tomatoes", 1.0, "can", "Tomatoes"),
        Case("two tins of beans", 2.0, "can", "Beans"),
        Case("500 grams of rice", 500.0, "g", "Rice"),
        Case("250ml cream", 250.0, "ml", "Cream"),
        Case("twelve pieces of toast", 12.0, "pcs", "Toast"),
        Case("milk", null, null, "Milk"),
        Case("constructor milk", null, null, "Constructor milk"),
        Case("toString milk", null, null, "Tostring milk"),
        Case("2 constructor milk", 2.0, null, "Constructor milk"),
        Case("can opener", null, null, "Can opener"),
        Case("3 apples", 3.0, null, "Apples"),
        Case("0 apples", null, null, "0 apples"),
        Case("one and a half litres of milk", 1.0, null, "And a half litres of milk"),
    )

    // ---------- parser ----------

    @Test
    fun `SHOP-14 AC1 parse reads quantity, unit and name like web parseSpoken`() {
        for (c in cases) {
            assertEquals(c.text, SpokenItem(c.name, c.quantity, c.unit), VoiceAdd.parse(c.text, EN))
        }
    }

    @Test
    fun `SHOP-14 no name left is a failure`() {
        for (text in listOf("", "   ", "2 litres", "half a kilo", "2")) {
            assertNull(text, VoiceAdd.parse(text, EN))
        }
    }

    @Test
    fun `SHOP-14 an empty or missing locale is the same as the default`() {
        val expected = SpokenItem("Milk", 2.0, "l")
        assertEquals(expected, VoiceAdd.parse("2 litres milk", Locale("")))
        assertEquals(expected, VoiceAdd.parse("2 litres milk", Locale.ROOT))
        assertEquals(expected, VoiceAdd.parse("2 litres milk"))
        assertEquals(expected, VoiceAdd.parse("2 litres milk", null))
    }

    @Test
    fun `SHOP-14 the decimal mark is swapped by hand, so a comma and a point agree whatever the device locale`() {
        for (loc in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale("ar", "EG"))) {
            assertEquals(1.5, VoiceAdd.parse("1,5 l oat milk", loc)?.quantity)
            assertEquals(1.5, VoiceAdd.parse("1.5 l oat milk", loc)?.quantity)
        }
    }

    @Test
    fun `SHOP-14 the locale cases the name`() {
        assertEquals("Ice", VoiceAdd.parse("ice", EN)?.name)
        assertEquals("Ilk", VoiceAdd.parse("ilk", EN)?.name)
        assertEquals("İce", VoiceAdd.parse("ice", Locale("tr", "TR"))?.name)
    }

    @Test
    fun `SHOP-14 every spoken unit maps to a C3 preset`() {
        for (word in listOf("litre", "milliliters", "kilograms", "grams", "pcs", "packets", "bottle", "tins")) {
            val unit = VoiceAdd.parse("2 $word stuff", EN)?.unit
            assertTrue(word, unit != null && unit in ItemSheetLogic.UNIT_PRESETS)
        }
    }

    // ---------- recognizer events ----------

    @Test
    fun `SHOP-14 a partial result is a live update, and an empty one is nothing`() {
        assertEquals(VoiceEvent.Partial("2 litres"), AndroidSpeechRecognizerDevice.event(RecognizerCallback.Partial, "2 litres"))
        assertNull(AndroidSpeechRecognizerDevice.event(RecognizerCallback.Partial, null))
        assertNull(AndroidSpeechRecognizerDevice.event(RecognizerCallback.Partial, "  "))
    }

    @Test
    fun `SHOP-14 a final result ends the run with its text, and one with no text is not heard`() {
        assertEquals(VoiceEvent.Final("2 litres milk"), AndroidSpeechRecognizerDevice.event(RecognizerCallback.Results, "2 litres milk"))
        assertEquals(VoiceEvent.Error(VoiceErrorKind.Other), AndroidSpeechRecognizerDevice.event(RecognizerCallback.Results, null))
        assertEquals(VoiceEvent.Error(VoiceErrorKind.Other), AndroidSpeechRecognizerDevice.event(RecognizerCallback.Results, ""))
    }

    @Test
    fun `SHOP-14 an error after a partial result still reports the error`() {
        assertEquals(
            VoiceEvent.Error(VoiceErrorKind.Other),
            AndroidSpeechRecognizerDevice.event(RecognizerCallback.Error, "2 lit", SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun `SHOP-14 error codes map to denied, unavailable or not heard`() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to VoiceErrorKind.Denied,
            12 to VoiceErrorKind.Unavailable, // ERROR_LANGUAGE_NOT_SUPPORTED
            13 to VoiceErrorKind.Unavailable, // ERROR_LANGUAGE_UNAVAILABLE
            SpeechRecognizer.ERROR_NO_MATCH to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_AUDIO to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_NETWORK to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_SERVER to VoiceErrorKind.Other,
            SpeechRecognizer.ERROR_CLIENT to VoiceErrorKind.Other,
            0 to VoiceErrorKind.Other,
        )
        for ((code, kind) in expected) {
            assertEquals("code $code", VoiceEvent.Error(kind), AndroidSpeechRecognizerDevice.event(RecognizerCallback.Error, null, code))
        }
    }

    // ---------- model ----------

    private val added = java.util.concurrent.CopyOnWriteArrayList<Triple<String, Double?, String?>>()

    private fun model(device: FakeSpeechRecognizerDevice, online: Boolean = true, add: suspend (String, Double?, String?) -> Boolean = { n, q, u -> added += Triple(n, q, u); true }) =
        VoiceAddModel(device, { online }, EN, add)

    private fun VoiceAddModel.phase() = state.value.phase

    @Test
    fun `SHOP-14 AC5 an unavailable recognizer is unsupported, and nothing starts`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice(available = false)
        val m = model(device)
        m.start()
        assertEquals(VoicePhase.Unsupported, m.phase())
        assertEquals(0, device.startCount)
        assertEquals(0, device.requestCount)
    }

    @Test
    fun `SHOP-14 AC5 offline without an on-device recognizer is unsupported, and with one listening starts offline`() = runBlocking<Unit> {
        val cannot = FakeSpeechRecognizerDevice(onDevice = false)
        val offlineModel = model(cannot, online = false)
        offlineModel.start()
        assertEquals(VoicePhase.Unsupported, offlineModel.phase())
        assertEquals(0, cannot.startCount)

        val can = FakeSpeechRecognizerDevice(onDevice = true)
        val m = model(can, online = false)
        m.start()
        assertEquals(VoicePhase.Listening(""), m.phase())
        assertEquals(listOf(true), can.offlineStarts.toList())

        val onlineDevice = FakeSpeechRecognizerDevice(onDevice = false)
        model(onlineDevice).start()
        assertEquals(listOf(false), onlineDevice.offlineStarts.toList())
    }

    @Test
    fun `SHOP-14 AC4 refused access is permission-denied, and nothing starts`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice(granted = false, grantsOnRequest = false)
        val m = model(device)
        m.start()
        assertEquals(VoicePhase.PermissionDenied, m.phase())
        assertEquals(1, device.requestCount)
        assertEquals(0, device.startCount)
    }

    @Test
    fun `SHOP-14 access granted by the system dialog starts listening`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice(granted = false, grantsOnRequest = true)
        val m = model(device)
        m.start()
        assertEquals(VoicePhase.Listening(""), m.phase())
        assertEquals(1, device.requestCount)
        assertEquals(1, device.startCount)
    }

    @Test
    fun `SHOP-14 a start that fails does not leave the sheet listening`() = runBlocking<Unit> {
        val withdrawn = FakeSpeechRecognizerDevice(startError = SecurityException("RECORD_AUDIO"))
        val a = model(withdrawn)
        a.start()
        assertEquals(VoicePhase.PermissionDenied, a.phase())

        val broken = FakeSpeechRecognizerDevice(startError = IllegalStateException("no service"))
        val b = model(broken)
        b.start()
        assertEquals(VoicePhase.Unsupported, b.phase())
    }

    @Test
    fun `SHOP-14 AC1 partial results show as the live transcript, then final parses it`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device)
        m.start()
        assertEquals(VoicePhase.Listening(""), m.phase())
        device.emit(VoiceEvent.Partial("2 litres"))
        assertEquals(VoicePhase.Listening("2 litres"), m.phase())
        device.emit(VoiceEvent.Final("2 litres milk"))
        assertEquals(VoicePhase.Parsed("2 litres milk", SpokenItem("Milk", 2.0, "l")), m.phase())
        assertFalse(device.isRunning)
    }

    @Test
    fun `SHOP-14 a final with no name is no-item, and a recognizer error is not-heard, denied or unsupported`() = runBlocking<Unit> {
        val none = FakeSpeechRecognizerDevice()
        val noItem = model(none)
        noItem.start()
        none.emit(VoiceEvent.Final("2 litres"))
        assertEquals(VoicePhase.NoItem("2 litres"), noItem.phase())

        for ((kind, expected) in listOf(
            VoiceErrorKind.Other to VoicePhase.NotHeard,
            VoiceErrorKind.Denied to VoicePhase.PermissionDenied,
            VoiceErrorKind.Unavailable to VoicePhase.Unsupported,
        )) {
            val device = FakeSpeechRecognizerDevice()
            val m = model(device)
            m.start()
            device.emit(VoiceEvent.Error(kind))
            assertEquals(expected, m.phase())
            assertFalse(device.isRunning)
        }
    }

    @Test
    fun `SHOP-14 an error that arrives after partial results leaves listening`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device)
        m.start()
        device.emit(VoiceEvent.Partial("2 lit"))
        device.emit(VoiceEvent.Error(VoiceErrorKind.Other))
        assertEquals(VoicePhase.NotHeard, m.phase())
        assertFalse(device.isRunning)
    }

    @Test
    fun `SHOP-14 AC3 Try again clears the result and restarts the recognizer`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device)
        m.start()
        device.emit(VoiceEvent.Final("2 litres milk"))
        m.tryAgain()
        assertEquals(VoicePhase.Listening(""), m.phase())
        assertEquals(2, device.startCount)
        assertTrue(device.isRunning)
        device.emit(VoiceEvent.Final("3 apples"))
        assertEquals(VoicePhase.Parsed("3 apples", SpokenItem("Apples", 3.0, null)), m.phase())
    }

    @Test
    fun `SHOP-14 closing stops the recognizer, and a late event changes nothing`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device)
        m.start()
        val before = device.stopCount
        m.stop()
        assertEquals(before + 1, device.stopCount)
        assertFalse(device.isRunning)
        device.emit(VoiceEvent.Final("2 litres milk"))
        assertEquals(VoicePhase.Listening(""), m.phase())
    }

    @Test
    fun `SHOP-14 closing while the permission dialog is up never starts listening`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val device = FakeSpeechRecognizerDevice(granted = false, grantsOnRequest = true).also { it.permissionGate = gate }
        val m = model(device)
        val opening = async(start = CoroutineStart.UNDISPATCHED) { m.start() }
        assertEquals(1, device.requestCount)
        m.stop()
        gate.complete(Unit)
        opening.await()
        assertEquals(0, device.startCount)
    }

    @Test
    fun `SHOP-14 AC2 Add hands the item to the writer, once`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device)
        m.start()
        device.emit(VoiceEvent.Final("2 litres of milk"))
        assertTrue(m.addParsed())
        assertEquals(listOf(Triple("Milk", 2.0, "l")), added.toList())
        assertFalse(m.state.value.adding)
        assertFalse(m.state.value.addFailed)
    }

    @Test
    fun `SHOP-14 Add without a parsed item does nothing`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device)
        m.start()
        assertFalse(m.addParsed())
        assertEquals(emptyList<Triple<String, Double?, String?>>(), added.toList())
    }

    @Test
    fun `SHOP-14 a second Add tap while one is saving writes once, and Try again waits too`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val device = FakeSpeechRecognizerDevice()
        val m = model(device, add = { _, _, _ -> calls.incrementAndGet(); gate.await(); true })
        m.start()
        device.emit(VoiceEvent.Final("2 litres milk"))
        val first = async(start = CoroutineStart.UNDISPATCHED) { m.addParsed() }
        assertTrue(m.state.value.adding)
        assertFalse(m.addParsed())
        m.tryAgain()
        assertEquals(1, calls.get())
        assertEquals(1, device.startCount)
        assertTrue(m.phase() is VoicePhase.Parsed)
        gate.complete(Unit)
        assertTrue(first.await())
        assertFalse(m.state.value.adding)
    }

    @Test
    fun `SHOP-14 the failure flag is cleared when an Add starts, so a repeat failure shows again`() = runBlocking<Unit> {
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        val calls = AtomicInteger()
        val device = FakeSpeechRecognizerDevice()
        val m = model(device, add = { _, _, _ -> gates[calls.getAndIncrement()].await(); false })
        m.start()
        device.emit(VoiceEvent.Final("milk"))
        for (round in 0..1) {
            val attempt = async(start = CoroutineStart.UNDISPATCHED) { m.addParsed() }
            assertEquals(round + 1, calls.get())
            assertFalse("round $round: cleared at the start", m.state.value.addFailed)
            gates[round].complete(Unit)
            assertFalse(attempt.await())
            assertTrue("round $round: shown after the failure", m.state.value.addFailed)
        }
    }

    @Test
    fun `SHOP-14 Try again clears an earlier failure`() = runBlocking<Unit> {
        val device = FakeSpeechRecognizerDevice()
        val m = model(device, add = { _, _, _ -> false })
        m.start()
        device.emit(VoiceEvent.Final("milk"))
        assertFalse(m.addParsed())
        assertTrue(m.state.value.addFailed)
        m.tryAgain()
        assertFalse(m.state.value.addFailed)
    }
}
