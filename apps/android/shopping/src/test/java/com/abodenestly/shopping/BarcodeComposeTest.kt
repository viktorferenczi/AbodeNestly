package com.abodenestly.shopping

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.abodenestly.core.api.ProductLookupResult
import com.abodenestly.sync.DatabaseRegistry
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
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val CODE = "5000112637922"

/**
 * One case per State and Interaction of DESIGN C7 for Android (TESTING §7), on a real Room file and a fake camera:
 * AC1 scanning, AC2 found, AC3 not-found, AC4 offline, AC5 permission-denied, plus the torch, the manual code, Add,
 * the failed lookup and the other camera states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class BarcodeComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-27T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val online = MutableStateFlow(true)
    private val ids = AtomicInteger()
    private val closes = AtomicInteger()
    private val kicks = AtomicInteger()
    private val lookups = CopyOnWriteArrayList<String>()
    private val store = BarcodeStore.inMemory()
    private val images = FakeImages()
    private lateinit var vm: ListDetailViewModel
    private lateinit var device: FakeScannerDevice
    private var answer: suspend (String) -> ProductLookupResult = { ProductLookupResult.NotFound }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun show(fake: FakeScannerDevice = FakeScannerDevice(), isOnline: Boolean = true, lookup: suspend (String) -> ProductLookupResult = answer) {
        device = fake
        online.value = isOnline
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = online,
            now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        runBlocking { awaitUntil { vm.state.value.loaded && vm.state.value.detail.list != null } }
        val model = vm.makeScanner(device, { lookups += it; lookup(it) }, store, scope)
        rule.setContent { AbodeTheme { BarcodeScannerScreen(model, device, images, onClose = { closes.incrementAndGet() }) } }
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    private fun awaitCamera() = rule.waitUntil(5_000) { device.startCount > 0 }

    private fun names() = runBlocking { registry.observeListDetail(household, "l1").first().items.map { it.name } }

    private fun awaitNames(vararg expected: String) = rule.waitUntil(5_000) { names().toSet() == expected.toSet() }

    private fun typeInto(tag: String, text: String) = rule.onNodeWithTag(tag).performTextInput(text)

    // ---------- scanning (AC1) ----------

    @Test
    fun `AC1 scanning shows the preview, the guide box and the hint, and starts the camera`() {
        show()
        await("barcode.guide")
        awaitCamera()
        rule.onNodeWithTag("barcode.screen").assertIsDisplayed()
        rule.onNodeWithTag("barcode.guide").assertIsDisplayed()
        rule.onNodeWithTag("barcode.fake.preview").assertIsDisplayed()
        rule.onNodeWithTag("barcode.hint").assertTextEquals("Point the camera at a barcode")
        rule.onNodeWithTag("barcode.manual.open").assertIsDisplayed()
        assertEquals(1, device.startCount)
    }

    @Test
    fun `the torch button is absent when the camera has no torch`() {
        show(FakeScannerDevice(hasTorch = false))
        await("barcode.guide")
        awaitCamera()
        assertFalse(exists("barcode.torch"))
    }

    @Test
    fun `torch toggles on and off with a state description`() {
        show(FakeScannerDevice(hasTorch = true))
        await("barcode.torch")
        rule.onNodeWithTag("barcode.torch").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        rule.onNodeWithTag("barcode.torch").performClick()
        rule.waitUntil(5_000) { device.torchOn }
        rule.onNodeWithTag("barcode.torch").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        rule.onNodeWithTag("barcode.torch").performClick()
        rule.waitUntil(5_000) { !device.torchOn }
    }

    @Test
    fun `Enter code manually opens the field and a bad code shows the error`() {
        show()
        await("barcode.manual.open")
        rule.onNodeWithTag("barcode.manual.open").performClick()
        await("barcode.manual.field")
        typeInto("barcode.manual.field", "12345")
        rule.onNodeWithTag("barcode.manual.submit").performClick()
        await("barcode.manual.error")
        rule.onNodeWithTag("barcode.manual.error").assertTextEquals("Enter 8 to 14 digits")
        assertTrue(lookups.isEmpty())
    }

    // ---------- found (AC2) ----------

    @Test
    fun `AC2 found shows name, brand and image, and Add puts the name on the list and closes`() {
        answer = { ProductLookupResult.Found(nutella()) }
        show()
        awaitCamera()
        device.scan("3017620422003")
        await("barcode.found.name")
        rule.onNodeWithTag("barcode.found.name").assertTextEquals("Nutella")
        rule.onNodeWithTag("barcode.found.brand").assertTextEquals("Ferrero")
        await("barcode.image")
        rule.onNodeWithTag("barcode.image").assertIsDisplayed()
        assertEquals(listOf("https://images.test/nutella.jpg"), images.urls.toList())

        rule.onNodeWithTag("barcode.add").performClick()
        rule.waitUntil(5_000) { closes.get() == 1 }
        awaitNames("Nutella")
        // The camera was stopped when the code was taken.
        assertTrue(device.stopCount >= 1)
    }

    @Test
    fun `found without a brand or an https image shows neither`() {
        answer = { ProductLookupResult.Found(nutella(imageUrl = "http://images.test/n.jpg", brand = null)) }
        show()
        awaitCamera()
        device.scan("3017620422003")
        await("barcode.found.name")
        assertFalse(exists("barcode.found.brand"))
        assertFalse(exists("barcode.image"))
        assertTrue(images.urls.isEmpty())
    }

    @Test
    fun `found, Scan again goes back to the camera`() {
        answer = { ProductLookupResult.Found(nutella()) }
        show()
        awaitCamera()
        device.scan("3017620422003")
        await("barcode.again")
        rule.onNodeWithTag("barcode.again").performClick()
        await("barcode.guide")
        rule.waitUntil(5_000) { device.startCount == 2 }
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `a UPC-A read as EAN-13 is looked up as 12 digits`() {
        show()
        awaitCamera()
        device.scan("0036000291452")
        await("barcode.notfound.title")
        assertEquals(listOf("036000291452"), lookups.toList())
    }

    @Test
    fun `looking shows the code while the lookup runs`() {
        val gate = CompletableDeferred<ProductLookupResult>()
        answer = { gate.await() }
        show()
        awaitCamera()
        device.scan(CODE)
        await("barcode.looking")
        rule.onNodeWithTag("barcode.looking").assertIsDisplayed()
        gate.complete(ProductLookupResult.NotFound)
        await("barcode.notfound.title")
    }

    // ---------- not found (AC3) ----------

    @Test
    fun `AC3 not-found shows the name field, Add needs a name, and the name goes on the list without a barcode`() {
        show()
        awaitCamera()
        device.scan(CODE)
        await("barcode.notfound.title")
        rule.onNodeWithTag("barcode.notfound.title").assertTextEquals("We don't know this product")
        rule.onNodeWithTag("barcode.code").assertTextEquals(CODE)
        rule.onNodeWithTag("barcode.add").assertIsNotEnabled()

        typeInto("barcode.name.field", "Oat drink")
        rule.onNodeWithTag("barcode.add").assertIsEnabled()
        rule.onNodeWithTag("barcode.add").performClick()

        rule.waitUntil(5_000) { closes.get() == 1 }
        awaitNames("Oat drink")
        assertEquals("Oat drink", store.cachedName(CODE))
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `not-found prefills the name this device gave the code before`() {
        store.cacheName(CODE, "Oat drink")
        show()
        awaitCamera()
        device.scan(CODE)
        await("barcode.name.field")
        rule.onNodeWithTag("barcode.name.field").assertTextContains("Oat drink")
        rule.onNodeWithTag("barcode.add").assertIsEnabled()
    }

    // ---------- the failed lookup ----------

    @Test
    fun `a failed lookup shows Retry and a name field, and writes nothing`() {
        answer = { ProductLookupResult.Failed }
        show()
        awaitCamera()
        device.scan(CODE)
        await("barcode.failed.title")
        rule.onNodeWithTag("barcode.failed.title").assertTextEquals("Couldn't look that up. Try again.")
        rule.onNodeWithTag("barcode.retry").assertIsDisplayed()
        rule.onNodeWithTag("barcode.name.field").assertIsDisplayed()
        assertEquals(emptyList<String>(), names())
        assertEquals(0, store.pending().size)
        assertEquals(0, kicks.get())

        // A name typed here survives Retry.
        typeInto("barcode.name.field", "Mine")
        rule.onNodeWithTag("barcode.retry").performClick()
        rule.waitUntil(5_000) { lookups.size == 2 }
        await("barcode.failed.title")
        assertEquals(listOf(CODE, CODE), lookups.toList())
        assertEquals(emptyList<String>(), names())
        rule.onNodeWithTag("barcode.add").assertIsEnabled()
    }

    // ---------- offline (AC4) ----------

    @Test
    fun `AC4 offline adds the code as the name, says the lookup is queued, and Done closes`() {
        show(isOnline = false)
        awaitCamera()
        device.scan(CODE)
        await("barcode.queued")
        rule.onNodeWithTag("barcode.queued").assertTextEquals("Added $CODE. We'll look it up when you're back online")
        assertTrue(lookups.isEmpty())
        awaitNames(CODE)
        assertEquals(1, store.pending().size)

        rule.onNodeWithTag("barcode.done").performClick()
        assertEquals(1, closes.get())
    }

    @Test
    fun `a network error on the lookup reads as offline too`() {
        show(lookup = { throw java.io.IOException("no route") })
        awaitCamera()
        device.scan(CODE)
        await("barcode.queued")
        awaitNames(CODE)
    }

    // ---------- permission and camera states (AC5) ----------

    @Test
    fun `AC5 permission-denied shows its message, Open settings, and a code can still be entered`() {
        answer = { ProductLookupResult.Found(nutella()) }
        show(FakeScannerDevice(granted = false, grantsOnRequest = false))
        await("barcode.nocamera.title")
        rule.onNodeWithTag("barcode.nocamera.title").assertTextEquals("Camera access is off")
        rule.onNodeWithTag("barcode.nocamera.hint").assertTextEquals(
            "Allow the camera for AbodeNestly in Settings, then open the scanner again. You can still enter the code.",
        )
        rule.onNodeWithTag("barcode.settings").assertIsDisplayed()
        assertFalse(exists("barcode.guide"))
        assertEquals(0, device.startCount)

        typeInto("barcode.manual.field", "3017620422003")
        rule.onNodeWithTag("barcode.manual.submit").performClick()
        await("barcode.found.name")
        rule.onNodeWithTag("barcode.found.name").assertTextEquals("Nutella")
        assertEquals(listOf("3017620422003"), lookups.toList())
    }

    @Test
    fun `a granted dialog starts the camera`() {
        show(FakeScannerDevice(granted = false, grantsOnRequest = true))
        await("barcode.guide")
        awaitCamera()
        assertEquals(1, device.requestCount)
        assertFalse(exists("barcode.nocamera.title"))
    }

    @Test
    fun `unsupported shows its copy and manual entry, with no settings button`() {
        show(FakeScannerDevice(hasCamera = false))
        await("barcode.nocamera.title")
        rule.onNodeWithTag("barcode.nocamera.title").assertTextEquals("Scanning isn't available on this device")
        rule.onNodeWithTag("barcode.nocamera.hint").assertTextEquals("You can still enter the code printed under the barcode.")
        rule.onNodeWithTag("barcode.manual.field").assertIsDisplayed()
        assertFalse(exists("barcode.settings"))
    }

    @Test
    fun `camera-unavailable shows its copy and manual entry`() {
        show(FakeScannerDevice(startError = ScannerException(ScannerException.Kind.Unavailable)))
        await("barcode.nocamera.title")
        rule.onNodeWithTag("barcode.nocamera.title").assertTextEquals("Can't use the camera")
        rule.onNodeWithTag("barcode.nocamera.hint").assertTextEquals("Another app may be using it. You can still enter the code.")
        rule.onNodeWithTag("barcode.manual.field").assertIsDisplayed()
    }

    // ---------- close ----------

    @Test
    fun `Close stops the camera and closes without adding anything`() {
        show()
        awaitCamera()
        rule.onNodeWithTag("barcode.close").performClick()
        assertEquals(1, closes.get())
        assertTrue(device.stopCount >= 1)
        assertEquals(emptyList<String>(), names())
    }

    // ---------- the quick-add bar ----------

    @Test
    fun `the scan button appears only when a scanner is set up, and calls onScan`() {
        val taps = AtomicInteger()
        val state = ListDetailUiState(loaded = true, listId = "l1", detail = com.abodenestly.sync.ListDetail(list = com.abodenestly.sync.ListRecord("l1", "Groceries", null, false, null, null)))
        rule.setContent { AbodeTheme { ListDetailContent(state, onEvent = {}, onBack = {}, onScan = { taps.incrementAndGet() }) } }
        rule.onNodeWithTag("quickadd.scan").assertIsDisplayed()
        rule.onNodeWithTag("quickadd.scan").performClick()
        assertEquals(1, taps.get())
    }

    @Test
    fun `without a scanner the bar has no scan button`() {
        val state = ListDetailUiState(loaded = true, listId = "l1", detail = com.abodenestly.sync.ListDetail(list = com.abodenestly.sync.ListRecord("l1", "Groceries", null, false, null, null)))
        rule.setContent { AbodeTheme { ListDetailContent(state, onEvent = {}, onBack = {}) } }
        assertFalse(exists("quickadd.scan"))
    }

    @Test
    fun `the scan button on the list opens the scanner over it`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        device = FakeScannerDevice()
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = online,
            now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        val setup = ScannerSetup(lookup = { ProductLookupResult.NotFound }, store = store, images = images, device = { device })
        rule.setContent { AbodeTheme { ListDetailScreen(vm, onBack = {}, scanner = setup) } }
        await("quickadd.scan")
        assertFalse(exists("barcode.screen"))
        rule.onNodeWithTag("quickadd.scan").performClick()
        await("barcode.screen")
        awaitCamera()
        rule.onNodeWithTag("barcode.close").performClick()
        rule.waitUntil(5_000) { !exists("barcode.screen") }
    }
}
