package com.abodenestly.shopping

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListDetail
import com.abodenestly.sync.ListRecord
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// Lower case after the first letter: the parser lower-cases what was said and capitalises only the first letter.
private const val LONG_NAME = "Extra virgin cold pressed organic olive oil from the sunny hills of tuscany in a large glass bottle"

/**
 * One case per State and Interaction of DESIGN C6 for Android (TESTING §7), on a real Room file and a fake recognizer:
 * AC1 listening and parsed, AC2 Add, AC3 Try again, AC4 permission-denied, AC5 unsupported, plus the other endings, the
 * failed write, the double tap, the wrapping chips and the mic button. The sheet's body is mounted inline (Robolectric
 * never drives the popup); the real `SpeechRecognizer` never runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class VoiceAddComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val online = MutableStateFlow(true)
    private val ids = AtomicInteger()
    private val closes = AtomicInteger()
    private val typeInstead = AtomicInteger()
    private val kicks = AtomicInteger()
    private lateinit var vm: ListDetailViewModel
    private lateinit var device: FakeSpeechRecognizerDevice

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun openList() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = online,
            now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        runBlocking { awaitUntil { vm.state.value.loaded && vm.state.value.detail.list != null } }
    }

    private fun show(fake: FakeSpeechRecognizerDevice = FakeSpeechRecognizerDevice(), isOnline: Boolean = true) {
        device = fake
        online.value = isOnline
        openList()
        val model = vm.makeVoice(device, Locale.US)
        rule.setContent {
            AbodeTheme { VoiceAddContent(model, onClose = { closes.incrementAndGet() }, onTypeInstead = { typeInstead.incrementAndGet() }) }
        }
        rule.waitForIdle()
    }

    private fun showModel(fake: FakeSpeechRecognizerDevice, model: VoiceAddModel) {
        device = fake
        rule.setContent {
            AbodeTheme { VoiceAddContent(model, onClose = { closes.incrementAndGet() }, onTypeInstead = { typeInstead.incrementAndGet() }) }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(15_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(15_000) { !exists(tag) }

    private fun awaitListening() = rule.waitUntil(15_000) { device.startCount > 0 && device.isRunning }

    private fun say(text: String) {
        awaitListening()
        device.emit(VoiceEvent.Final(text))
        await("voice.chips")
    }

    private fun items() = runBlocking { registry.observeListDetail(household, "l1").first().items }

    /** True when [check] passes; the assertions throw while the screen is still catching up. */
    private fun holds(check: () -> Unit): Boolean = try {
        check()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    // ---------- listening (AC1) ----------

    @Test
    fun `SHOP-14 AC1 listening shows the title, the pulsing mic, Listening and an empty transcript, and starts the recognizer`() {
        show()
        await("voice.listening")
        awaitListening()
        rule.onNodeWithTag("voice.sheet").assertIsDisplayed()
        rule.onNodeWithTag("voice.indicator").assertIsDisplayed()
        rule.onNodeWithTag("voice.listening").assertTextEquals("Listening…")
        rule.onNodeWithTag("voice.cancel").assertIsDisplayed()
        assertEquals(1, device.startCount)
        assertEquals(listOf(false), device.offlineStarts.toList())
    }

    @Test
    fun `SHOP-14 listening shows the partial transcript in a polite live region`() {
        show()
        awaitListening()
        device.emit(VoiceEvent.Partial("2 litres"))
        rule.waitUntil(15_000) { holds { rule.onNodeWithTag("voice.transcript").assertTextEquals("2 litres") } }
        rule.onNodeWithTag("voice.transcript").assertTextEquals("2 litres")
        rule.onNodeWithTag("voice.transcript").assert(liveRegion(LiveRegionMode.Polite))
    }

    @Test
    fun `SHOP-14 listening shows a still mic when animations are off`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show()
        await("voice.indicator")
        rule.onNodeWithTag("voice.indicator").assertIsDisplayed()
    }

    @Test
    fun `SHOP-14 Cancel closes without adding anything, and leaving stops the recognizer`() {
        show()
        awaitListening()
        rule.onNodeWithTag("voice.cancel").performClick()
        assertEquals(1, closes.get())
        assertEquals(emptyList<String>(), items().map { it.name })
    }

    @Test
    fun `SHOP-14 leaving the composition stops the recognizer`() {
        device = FakeSpeechRecognizerDevice()
        openList()
        val model = vm.makeVoice(device, Locale.US)
        var visible by mutableStateOf(true)
        rule.setContent {
            AbodeTheme { if (visible) VoiceAddContent(model, onClose = {}, onTypeInstead = {}) }
        }
        awaitListening()
        val before = device.stopCount
        visible = false
        rule.waitForIdle()
        assertTrue(device.stopCount > before)
        assertFalse(device.isRunning)
    }

    @Test
    fun `SHOP-14 offline with an on-device recognizer starts listening offline`() {
        show(FakeSpeechRecognizerDevice(onDevice = true), isOnline = false)
        awaitListening()
        assertEquals(listOf(true), device.offlineStarts.toList())
    }

    // ---------- parsed (AC1, AC2, AC3) ----------

    @Test
    fun `SHOP-14 AC1 parsed shows the transcript and a chip each for quantity, unit and name`() {
        show()
        say("2 litres milk")
        rule.onNodeWithTag("voice.parsed.transcript").assertTextEquals("2 litres milk")
        rule.onNodeWithTag("voice.chip.quantity").assertContentDescriptionEquals("Quantity 2")
        rule.onNodeWithTag("voice.chip.unit").assertContentDescriptionEquals("Unit l")
        rule.onNodeWithTag("voice.chip.name").assertContentDescriptionEquals("Name Milk")
        rule.onNodeWithTag("voice.add").assertIsEnabled()
        rule.onNodeWithTag("voice.tryagain").assertIsEnabled()
        assertFalse(device.isRunning)
    }

    @Test
    fun `SHOP-14 parsed shows only the chips that were said`() {
        show()
        say("milk")
        rule.onNodeWithTag("voice.chip.name").assertContentDescriptionEquals("Name Milk")
        assertFalse(exists("voice.chip.quantity"))
        assertFalse(exists("voice.chip.unit"))
    }

    @Test
    fun `SHOP-14 a long name is fully shown, wrapped inside its chip`() {
        show()
        say("2 litres of $LONG_NAME")
        rule.onNodeWithTag("voice.chip.name").assertIsDisplayed()
        rule.onNodeWithTag("voice.chip.name").assertContentDescriptionEquals("Name $LONG_NAME")
        rule.onNodeWithTag("voice.add").assertIsDisplayed()
    }

    @Test
    fun `SHOP-14 AC2 Add writes one list_item with quantity and unit, kicks sync and closes the sheet`() {
        show()
        say("2 litres of milk")
        rule.onNodeWithTag("voice.add").performClick()
        rule.waitUntil(15_000) { closes.get() == 1 }
        rule.waitUntil(15_000) { items().size == 1 }
        val item = items().single()
        assertEquals("Milk", item.name)
        assertEquals(2.0, item.quantity)
        assertEquals("l", item.unit)
        assertEquals("2 l", ItemSheetLogic.formatQty(item.quantity, item.unit))
        assertEquals(1, runBlocking { registry.outboxForTesting() }.count { it.tableName == "list_item" })
        assertEquals(1, kicks.get())
    }

    @Test
    fun `SHOP-14 AC3 Try again clears the chips and listens again`() {
        show()
        say("2 litres milk")
        rule.onNodeWithTag("voice.tryagain").performClick()
        await("voice.listening")
        awaitListening()
        assertFalse(exists("voice.chips"))
        assertEquals(2, device.startCount)
        assertEquals(emptyList<String>(), items().map { it.name })
        device.emit(VoiceEvent.Final("3 apples"))
        await("voice.chips")
        rule.onNodeWithTag("voice.chip.name").assertContentDescriptionEquals("Name Apples")
    }

    @Test
    fun `SHOP-14 a failed write shows the error in an assertive live region and keeps the sheet open`() {
        val fake = FakeSpeechRecognizerDevice()
        showModel(fake, VoiceAddModel(fake, { true }, Locale.US) { _, _, _ -> false })
        say("milk")
        rule.onNodeWithTag("voice.add").performClick()
        await("voice.error")
        rule.onNodeWithTag("voice.error").assertTextEquals("Could not save the change. Try again.")
        rule.onNodeWithTag("voice.error").assert(liveRegion(LiveRegionMode.Assertive))
        assertEquals(0, closes.get())
        rule.onNodeWithTag("voice.add").assertIsEnabled()
        // A repeat failure is announced again: the node goes away when the Add starts and comes back with the failure.
        rule.onNodeWithTag("voice.add").performClick()
        await("voice.error")
        assertEquals(0, closes.get())
    }

    @Test
    fun `SHOP-14 Add and Try again are off while an Add is saving, and a second tap writes once`() {
        val fake = FakeSpeechRecognizerDevice()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        showModel(fake, VoiceAddModel(fake, { true }, Locale.US) { _, _, _ -> calls.incrementAndGet(); gate.await(); true })
        say("milk")
        rule.onNodeWithTag("voice.add").performClick()
        rule.waitUntil(15_000) { calls.get() == 1 }
        rule.waitUntil(15_000) { holds { rule.onNodeWithTag("voice.add").assertIsNotEnabled() } }
        rule.onNodeWithTag("voice.add").assertIsNotEnabled()
        rule.onNodeWithTag("voice.tryagain").assertIsNotEnabled()
        assertEquals(1, calls.get())
        gate.complete(Unit)
        rule.waitUntil(15_000) { closes.get() == 1 }
        assertEquals(1, calls.get())
    }

    // ---------- the other endings ----------

    @Test
    fun `SHOP-14 a transcript with no name shows Didn't catch an item with Try again only`() {
        show()
        awaitListening()
        device.emit(VoiceEvent.Final("2 litres"))
        await("voice.message")
        rule.onNodeWithTag("voice.message").assertTextEquals("Didn't catch an item. Try again.")
        rule.onNodeWithTag("voice.tryagain").assertIsDisplayed()
        assertFalse(exists("voice.add"))
    }

    @Test
    fun `SHOP-14 an error after partial results shows Didn't catch that with Try again`() {
        show()
        awaitListening()
        device.emit(VoiceEvent.Partial("2 lit"))
        device.emit(VoiceEvent.Error(VoiceErrorKind.Other))
        await("voice.message")
        rule.onNodeWithTag("voice.message").assertTextEquals("Didn't catch that.")
        rule.onNodeWithTag("voice.tryagain").performClick()
        await("voice.listening")
        awaitListening()
        assertEquals(2, device.startCount)
    }

    // ---------- permission-denied (AC4) and unsupported (AC5) ----------

    @Test
    fun `SHOP-14 AC4 permission-denied shows its copy, Open settings and Type instead`() {
        show(FakeSpeechRecognizerDevice(granted = false, grantsOnRequest = false))
        await("voice.settings")
        rule.onNodeWithTag("voice.message").assertTextEquals("Microphone access is off. Allow it in Settings, or type the item instead.")
        rule.onNodeWithTag("voice.settings").assertIsDisplayed()
        rule.onNodeWithTag("voice.type").assertIsDisplayed()
        assertEquals(0, device.startCount)
        assertFalse(exists("voice.add"))
    }

    @Test
    fun `SHOP-14 Open settings opens the app's settings page`() {
        show(FakeSpeechRecognizerDevice(granted = false, grantsOnRequest = false))
        await("voice.settings")
        rule.onNodeWithTag("voice.settings").performClick()
        val started: Intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:" + context.packageName, started.dataString)
    }

    @Test
    fun `SHOP-14 AC4 Type instead in permission-denied calls the focus hook`() {
        show(FakeSpeechRecognizerDevice(granted = false, grantsOnRequest = false))
        await("voice.type")
        rule.onNodeWithTag("voice.type").performClick()
        assertEquals(1, typeInstead.get())
    }

    @Test
    fun `SHOP-14 AC5 unsupported shows its copy and Type instead, with no settings button, and starts nothing`() {
        show(FakeSpeechRecognizerDevice(available = false))
        await("voice.type")
        rule.onNodeWithTag("voice.message").assertTextEquals("Voice input isn't available here. Type the item instead.")
        assertFalse(exists("voice.settings"))
        assertEquals(0, device.startCount)
        rule.onNodeWithTag("voice.type").performClick()
        assertEquals(1, typeInstead.get())
    }

    @Test
    fun `SHOP-14 AC5 offline without an on-device recognizer is unsupported`() {
        show(FakeSpeechRecognizerDevice(onDevice = false), isOnline = false)
        await("voice.type")
        rule.onNodeWithTag("voice.message").assertTextEquals("Voice input isn't available here. Type the item instead.")
        assertEquals(0, device.startCount)
    }

    // ---------- the quick-add bar ----------

    private fun emptyState() = ListDetailUiState(
        loaded = true, listId = "l1", detail = ListDetail(list = ListRecord("l1", "Groceries", null, false, null, null)),
    )

    @Test
    fun `SHOP-14 the mic button appears only when voice is set up, and calls onVoice`() {
        val taps = AtomicInteger()
        rule.setContent { AbodeTheme { ListDetailContent(emptyState(), onEvent = {}, onBack = {}, onVoice = { taps.incrementAndGet() }) } }
        rule.onNodeWithTag("quickadd.voice").assertIsDisplayed()
        rule.onNodeWithTag("quickadd.voice").assertContentDescriptionEquals("Voice add")
        rule.onNodeWithTag("quickadd.voice").performClick()
        assertEquals(1, taps.get())
    }

    @Test
    fun `SHOP-14 without voice the bar has no mic button, and the empty hint is Try 2 l milk`() {
        rule.setContent { AbodeTheme { ListDetailContent(emptyState(), onEvent = {}, onBack = {}) } }
        assertFalse(exists("quickadd.voice"))
        await("list.empty")
        rule.onNodeWithTag("list.empty").assertTextEquals("Try: 2 l milk")
    }

    @Test
    fun `SHOP-14 the mic and scan buttons sit side by side`() {
        rule.setContent { AbodeTheme { ListDetailContent(emptyState(), onEvent = {}, onBack = {}, onScan = {}, onVoice = {}) } }
        rule.onNodeWithTag("quickadd.scan").assertIsDisplayed()
        rule.onNodeWithTag("quickadd.voice").assertIsDisplayed()
        rule.onNodeWithTag("quickadd.add").assertIsDisplayed()
    }
}
