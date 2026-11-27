package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.core.api.ProductLookupResult
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.remove
import com.abodenestly.sync.patch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val CODE = "5000112637922"

/**
 * SHOP-10 logic on a real Room file (Robolectric): the add rules (foreign key, which failures count as offline, queue
 * before kick, no double add, typed names), the name cache and the queue drain. Twins: iOS `BarcodeTests`, web `Barcode.test.ts`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarcodeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-27T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = BarcodeStore.inMemory()
    private val device = FakeScannerDevice()
    private val online = MutableStateFlow(true)
    private val ids = AtomicInteger()

    /** How many lookups were queued at the moment of each kick. */
    private val queuedAtKick = CopyOnWriteArrayList<Int>()
    private lateinit var vm: ListDetailViewModel

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun open() = runBlocking {
        registry.open(household)
        createList(registry, household, "Groceries", null, now, "l1")
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { queuedAtKick += store.pending().size }, isOnline = online,
            now = { now }, newId = { "i${ids.incrementAndGet()}" }, scope = scope,
        )
        awaitUntil { vm.state.value.loaded && vm.state.value.detail.list != null }
    }

    private fun scanner(lookup: suspend (String) -> ProductLookupResult) = vm.makeScanner(device, lookup, store, scope)

    private fun names() = runBlocking { registry.observeListDetail(household, "l1").first().items.map { it.name } }

    private fun storedBarcode(id: String): String? = runBlocking { registry.withSearchDao { it.listItem(hid, id) }!!.barcode }

    private fun itemName(id: String): String? = runBlocking { registry.withSearchDao { it.listItem(hid, id) }?.name }

    private fun listItemMutations() = runBlocking { registry.outboxForTesting().filter { it.tableName == "list_item" } }

    // ---------- helpers ----------

    @Test
    fun `isBarcode takes 8 to 14 digits and nothing else`() {
        for (ok in listOf("12345678", "3017620422003", "12345678901234")) assertTrue(ok, Barcode.isBarcode(ok))
        for (bad in listOf("", "1234567", "123456789012345", "1234567a", " 12345678", "12 345 678", "１２３４５６７８")) assertFalse(bad, Barcode.isBarcode(bad))
    }

    @Test
    fun `normalize drops the leading zero of a 13-digit read so UPC-A is 12 digits`() {
        assertEquals("036000291452", Barcode.normalize("0036000291452"))
        assertEquals("3017620422003", Barcode.normalize("3017620422003"))
        assertEquals("73513537", Barcode.normalize("73513537"))
        // Not 13 digits: left alone.
        assertEquals("0123456789012345", Barcode.normalize("0123456789012345"))
        assertEquals("012345678905", Barcode.normalize("012345678905"))
    }

    @Test
    fun `imageUrl is https only`() {
        assertEquals("https://img.test/a.jpg", Barcode.imageUrl("https://img.test/a.jpg"))
        assertNull(Barcode.imageUrl("http://img.test/a.jpg"))
        assertNull(Barcode.imageUrl("javascript:alert(1)"))
        assertNull(Barcode.imageUrl("https://"))
        assertNull(Barcode.imageUrl("not a url"))
        assertNull(Barcode.imageUrl(null))
    }

    // ---------- the add rules ----------

    @Test
    fun `AC4 offline adds the code as the name with no barcode and queues one lookup`() = runBlocking<Unit> {
        open()
        online.value = false
        val lookups = AtomicInteger()
        val model = scanner { lookups.incrementAndGet(); ProductLookupResult.NotFound }

        model.handle(CODE)

        assertEquals(ScannerPhase.Queued(CODE), model.state.value.phase)
        assertEquals(0, lookups.get())
        awaitUntil { names() == listOf(CODE) }
        val id = store.pending().single().itemId
        assertEquals(PendingLookup(hid, id, CODE), store.pending().single())
        assertNull(storedBarcode(id))
        val mutation = listItemMutations().single()
        assertEquals("upsert", mutation.op)
        assertEquals(JsonNull, Json.parseToJsonElement(mutation.data).jsonObject["barcode"])
        assertEquals(CODE, (Json.parseToJsonElement(mutation.data).jsonObject["name"] as JsonPrimitive).content)
    }

    @Test
    fun `a network error or a 502 reads as offline, whether it throws or answers`() = runBlocking<Unit> {
        open()
        val throwing = scanner { throw IOException("no route") }
        throwing.handle("11111111")
        assertEquals(ScannerPhase.Queued("11111111"), throwing.state.value.phase)

        val answering = scanner { ProductLookupResult.Offline }
        answering.handle("22222222")
        assertEquals(ScannerPhase.Queued("22222222"), answering.state.value.phase)

        awaitUntil { names().toSet() == setOf("11111111", "22222222") }
        assertEquals(2, store.pending().size)
        assertEquals(2, listItemMutations().size)
    }

    @Test
    fun `found sets the barcode, not-found does not`() = runBlocking<Unit> {
        open()
        val found = scanner { ProductLookupResult.Found(nutella()) }
        found.handle("3017620422003")
        assertEquals(ScannerPhase.Found(nutella()), found.state.value.phase)
        assertTrue(found.addFound())
        awaitUntil { names() == listOf("Nutella") }
        val foundId = registry.withSearchDao { it.listItem(hid, "i1") }!!
        assertEquals("3017620422003", foundId.barcode)
        assertEquals(0, store.pending().size)

        val missing = scanner { ProductLookupResult.NotFound }
        missing.handle("44444444")
        assertEquals(ScannerPhase.NotFound("44444444"), missing.state.value.phase)
        missing.setName("  Oat drink ")
        assertTrue(missing.addNamed())
        awaitUntil { names().toSet() == setOf("Nutella", "Oat drink") }
        assertNull(storedBarcode("i2"))
        assertEquals("Oat drink", store.cachedName("44444444"))
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `any other failure writes nothing and queues nothing`() = runBlocking<Unit> {
        open()
        val failed = scanner { ProductLookupResult.Failed }
        failed.handle(CODE)
        assertEquals(ScannerPhase.Failed(CODE), failed.state.value.phase)
        // An exception that is not a network error (a 401 surfaced as one, a parse bug) is a failure too.
        val crashing = scanner { throw IllegalStateException("boom") }
        crashing.handle("66666666")
        assertEquals(ScannerPhase.Failed("66666666"), crashing.state.value.phase)

        assertEquals(emptyList<String>(), names())
        assertEquals(0, store.pending().size)
        assertEquals(0, listItemMutations().size)
        assertTrue(queuedAtKick.isEmpty())
    }

    @Test
    fun `the lookup is queued before sync is kicked`() = runBlocking<Unit> {
        open()
        online.value = false
        scanner { ProductLookupResult.NotFound }.handle(CODE)

        assertEquals(listOf(1), queuedAtKick.toList())
    }

    @Test
    fun `a found add and a typed add kick with nothing queued`() = runBlocking<Unit> {
        open()
        val model = scanner { ProductLookupResult.Found(nutella()) }
        model.handle("3017620422003")
        model.addFound()
        assertEquals(listOf(0), queuedAtKick.toList())
    }

    @Test
    fun `Retry twice gives one item and a lookup in flight blocks a second`() = runBlocking<Unit> {
        open()
        val calls = AtomicInteger()
        val gate = CompletableDeferred<ProductLookupResult>()
        val model = scanner {
            if (calls.incrementAndGet() == 1) ProductLookupResult.Failed else gate.await()
        }
        model.handle(CODE)
        assertEquals(ScannerPhase.Failed(CODE), model.state.value.phase)
        // The first Retry stays inside the lookup, so the guard is held.
        val first = scope.launch { model.retry() }
        assertEquals(ScannerPhase.Looking(CODE), model.state.value.phase)
        val second = scope.launch { model.retry() }
        second.join()
        gate.complete(ProductLookupResult.Offline)
        joinAll(first, second)

        assertEquals(2, calls.get())
        awaitUntil { names() == listOf(CODE) }
        assertEquals(1, store.pending().size)
        assertEquals(1, listItemMutations().size)
    }

    @Test
    fun `a second Add while the first is running does nothing`() = runBlocking<Unit> {
        open()
        val gate = CompletableDeferred<Unit>()
        val adds = AtomicInteger()
        val model = BarcodeScannerModel(
            hid, device, { ProductLookupResult.NotFound }, { true }, store,
            add = { _, _, _ -> adds.incrementAndGet(); gate.await(); "id" }, scope = scope,
        )
        model.handle("44444444")
        model.setName("Jam")

        val first = scope.launch { model.addNamed() }
        val second = model.addNamed()
        gate.complete(Unit)
        first.join()

        assertFalse(second)
        assertEquals(1, adds.get())
        assertEquals("Jam", store.cachedName("44444444"))
    }

    @Test
    fun `a typed name survives a retry, and the cache prefills only when a code first appears`() = runBlocking<Unit> {
        open()
        store.cacheName(CODE, "From cache")
        val model = scanner { ProductLookupResult.Failed }
        model.handle(CODE)
        assertEquals("From cache", model.state.value.name)

        model.setName("My own name")
        model.retry()

        assertEquals(ScannerPhase.Failed(CODE), model.state.value.phase)
        assertEquals("My own name", model.state.value.name)

        // A different code brings its own prefill (here none), and Scan again forgets the old code.
        model.scanAgain()
        model.handle("77777777")
        assertEquals("", model.state.value.name)
    }

    @Test
    fun `a manual code that is not 8 to 14 digits shows the error and looks nothing up`() = runBlocking<Unit> {
        open()
        val lookups = AtomicInteger()
        val model = scanner { lookups.incrementAndGet(); ProductLookupResult.NotFound }
        model.setCode("12345")
        model.submitCode()
        assertTrue(model.state.value.codeError)
        assertEquals(0, lookups.get())
        model.setCode(" 12345678 ")
        assertFalse(model.state.value.codeError)
        model.submitCode()
        assertEquals(ScannerPhase.NotFound("12345678"), model.state.value.phase)
        assertEquals(1, lookups.get())
    }

    @Test
    fun `a camera read of UPC-A as EAN-13 is looked up as 12 digits`() = runBlocking<Unit> {
        open()
        val seen = CopyOnWriteArrayList<String>()
        val model = scanner { seen += it; ProductLookupResult.NotFound }
        model.startCamera(ResumedOwner())
        assertEquals(1, device.startCount)

        device.scan("1234") // too short: ignored, the next frame tries again
        device.scan("0036000291452")

        awaitUntil { seen.isNotEmpty() }
        assertEquals(listOf("036000291452"), seen.toList())
        // The camera stops once a code is taken.
        assertTrue(device.stopCount >= 1)
    }

    @Test
    fun `no camera, a denied permission and a busy camera each get their own state`() = runBlocking<Unit> {
        open()
        val none = FakeScannerDevice(hasCamera = false)
        val noneModel = vm.makeScanner(none, { ProductLookupResult.NotFound }, store, scope)
        noneModel.startCamera(ResumedOwner())
        assertEquals(ScannerPhase.Unsupported, noneModel.state.value.phase)
        assertEquals(0, none.startCount)

        val denied = FakeScannerDevice(granted = false, grantsOnRequest = false)
        val deniedModel = vm.makeScanner(denied, { ProductLookupResult.NotFound }, store, scope)
        deniedModel.startCamera(ResumedOwner())
        assertEquals(ScannerPhase.PermissionDenied, deniedModel.state.value.phase)
        assertEquals(1, denied.requestCount)

        val busy = FakeScannerDevice(startError = ScannerException(ScannerException.Kind.Unavailable))
        val busyModel = vm.makeScanner(busy, { ProductLookupResult.NotFound }, store, scope)
        busyModel.startCamera(ResumedOwner())
        assertEquals(ScannerPhase.CameraUnavailable, busyModel.state.value.phase)

        val unsupported = FakeScannerDevice(startError = ScannerException(ScannerException.Kind.Unsupported))
        val unsupportedModel = vm.makeScanner(unsupported, { ProductLookupResult.NotFound }, store, scope)
        unsupportedModel.startCamera(ResumedOwner())
        assertEquals(ScannerPhase.Unsupported, unsupportedModel.state.value.phase)

        // The system dialog is shown once and a grant starts the camera.
        val asks = FakeScannerDevice(granted = false, grantsOnRequest = true)
        val asksModel = vm.makeScanner(asks, { ProductLookupResult.NotFound }, store, scope)
        asksModel.startCamera(ResumedOwner())
        assertEquals(ScannerPhase.Scanning, asksModel.state.value.phase)
        assertEquals(1, asks.startCount)
    }

    @Test
    fun `a code typed while the permission dialog is up is not overwritten by its answer`() = runBlocking<Unit> {
        open()
        val gate = CompletableDeferred<Boolean>()
        val slow = object : ScannerDevice by FakeScannerDevice(granted = false) {
            override suspend fun requestPermission(): Boolean = gate.await()
        }
        val model = vm.makeScanner(slow, { ProductLookupResult.NotFound }, store, scope)
        val starting = scope.launch { model.startCamera(ResumedOwner()) }
        model.handle("12345678")
        gate.complete(false)
        starting.join()
        assertEquals(ScannerPhase.NotFound("12345678"), model.state.value.phase)
    }

    // ---------- the name cache ----------

    @Test
    fun `the name cache keeps the newest 200, whatever the codes look like`() {
        val cache = BarcodeStore.inMemory()
        // Interleaves 8-digit codes (integer-like keys in a JS object) with 13-digit ones: eviction must follow insertion.
        val codes = (0 until 205).map { if (it % 2 == 0) "%08d".format(10_000_000 + it) else "%013d".format(3_000_000_000_000L + it) }
        codes.forEach { cache.cacheName(it, "name $it") }

        val kept = cache.names()
        assertEquals(200, kept.size)
        assertEquals(codes.drop(5), kept.map { it.code })
        assertNull(cache.cachedName(codes[0]))
        assertEquals("name ${codes.last()}", cache.cachedName(codes.last()))

        // Caching a code again moves it to the newest end and keeps one entry.
        cache.cacheName(codes[10], "renamed")
        assertEquals(200, cache.names().size)
        assertEquals(NameEntry(codes[10], "renamed"), cache.names().last())
    }

    @Test
    fun `a damaged entry does not drop the rest of the cache or the queue`() {
        val raw = HashMap<String, String>()
        val damaged = BarcodeStore({ raw[it] }, { k, v -> raw[k] = v })
        raw[BarcodeStore.NAMES_KEY] = """[{"code":"12345678","name":"Jam"},{"code":5},"x",{"code":"87654321","name":"Tea"}]"""
        raw[BarcodeStore.LOOKUPS_KEY] = """[{"householdId":"h","itemId":"i","code":"12345678"},{"householdId":"h"}]"""
        assertEquals(listOf(NameEntry("12345678", "Jam"), NameEntry("87654321", "Tea")), damaged.names())
        assertEquals(listOf(PendingLookup("h", "i", "12345678")), damaged.pending())
        raw[BarcodeStore.NAMES_KEY] = "not json"
        assertEquals(emptyList<NameEntry>(), damaged.names())
    }

    // ---------- the drain ----------

    private fun request(
        lookup: suspend (String) -> ProductLookupResult,
        drainOnline: Boolean = true,
        household: java.util.UUID = this.household,
        onRenamed: suspend () -> Unit = {},
    ) = DrainRequest(registry, household, lookup, { drainOnline }, store, onRenamed)

    /** An item named after [code] with its lookup queued, as an offline scan leaves it. */
    private suspend fun queueItem(code: String): String =
        vm.addScanned(code, null) { id -> store.appendPending(PendingLookup(hid, id, code)) }!!

    private fun queuedItem(code: String): String = runBlocking { queueItem(code) }

    @Test
    fun `drain renames a found item, sets its barcode and pushes only name and barcode`() = runBlocking<Unit> {
        open()
        val id = queuedItem(CODE)
        var renamed = 0

        BarcodeLookupDrainer().drain(request({ ProductLookupResult.Found(product(CODE, "Cola")) }, onRenamed = { renamed++ }))

        assertEquals("Cola", itemName(id))
        assertEquals(CODE, storedBarcode(id))
        assertEquals(0, store.pending().size)
        assertEquals(1, renamed)
        val patch = listItemMutations().last()
        assertEquals("upsert", patch.op)
        assertEquals(setOf("name", "barcode"), Json.parseToJsonElement(patch.data).jsonObject.keys)
        // The local name_key follows the rename, so suggestions keep matching until the next pull.
        assertEquals("cola", registry.withSearchDao { it.listItem(hid, id) }!!.nameKey)
    }

    @Test
    fun `drain leaves an item the person renamed or deleted alone, and drops its entry`() = runBlocking<Unit> {
        open()
        val renamedId = queuedItem("11111111")
        val deletedId = queuedItem("22222222")
        registry.patch(household, "list_item", renamedId, buildJsonObject { put("name", "Mine") }, now)
        registry.remove(household, "list_item", deletedId, now)
        var renamed = 0

        BarcodeLookupDrainer().drain(request({ ProductLookupResult.Found(product(it, "Cola $it")) }, onRenamed = { renamed++ }))

        assertEquals("Mine", itemName(renamedId))
        assertNull(storedBarcode(renamedId))
        assertEquals("22222222", itemName(deletedId))
        assertEquals(0, store.pending().size)
        assertEquals(0, renamed)
    }

    @Test
    fun `drain drops the entry for 404 and for any other failure, and keeps it for a network failure`() = runBlocking<Unit> {
        open()
        queuedItem("11111111")
        queuedItem("22222222")
        queuedItem("33333333")
        queuedItem("44444444")
        val results = mapOf(
            "11111111" to ProductLookupResult.NotFound,
            "22222222" to ProductLookupResult.Failed,
            "33333333" to ProductLookupResult.Offline,
        )

        BarcodeLookupDrainer().drain(request({ code -> results[code] ?: throw IOException("down") }))

        assertEquals(listOf("33333333", "44444444"), store.pending().map { it.code })
        assertEquals(setOf("11111111", "22222222", "33333333", "44444444"), names().toSet())
    }

    @Test
    fun `drain does nothing offline, and waits for the household of an entry`() = runBlocking<Unit> {
        open()
        queuedItem(CODE)
        store.appendPending(PendingLookup(uuid(2).toString(), "other", "99999999"))
        val calls = AtomicInteger()

        BarcodeLookupDrainer().drain(request({ calls.incrementAndGet(); ProductLookupResult.NotFound }, drainOnline = false))
        assertEquals(0, calls.get())
        assertEquals(2, store.pending().size)

        BarcodeLookupDrainer().drain(request({ calls.incrementAndGet(); ProductLookupResult.NotFound }))
        assertEquals(1, calls.get())
        assertEquals(listOf("other"), store.pending().map { it.itemId })
    }

    @Test
    fun `drain of a household that is not open throws`() = runBlocking<Unit> {
        open()
        queuedItem(CODE)
        assertThrows(HouseholdClosedException::class.java) {
            runBlocking { BarcodeLookupDrainer().drain(request({ ProductLookupResult.NotFound }, household = uuid(2))) }
        }
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `a request during a pass runs once after it, only the latest of several, and skips what just failed`() = runBlocking<Unit> {
        open()
        queuedItem("11111111")
        val drainer = BarcodeLookupDrainer()
        val order = CopyOnWriteArrayList<String>()
        lateinit var second: DrainRequest
        lateinit var third: DrainRequest
        val first = request({ code ->
            order += "first:$code"
            // A scan queued while the pass runs, and two drains asked for by sync runs: the first returns at once.
            queueItem("22222222")
            drainer.drain(second)
            drainer.drain(third)
            ProductLookupResult.Offline
        })
        second = request({ order += "second:$it"; ProductLookupResult.Offline })
        third = request({ order += "third:$it"; ProductLookupResult.Offline })

        drainer.drain(first)

        // Only the latest request runs, once; it reads the entry queued during the pass and skips the one that just failed transiently.
        assertEquals(listOf("first:11111111", "third:22222222"), order.toList())
        assertEquals(2, store.pending().size)
    }

    @Test
    fun `a request during a pass still runs when the pass throws`() = runBlocking<Unit> {
        open()
        val a = queuedItem("11111111")
        val drainer = BarcodeLookupDrainer()
        lateinit var second: DrainRequest
        val first = request(
            {
                // B arrives during the pass, with its own drain request; then the rename of A (below) throws.
                queueItem("22222222")
                drainer.drain(second)
                ProductLookupResult.Found(product("11111111", "Cola"))
            },
            onRenamed = { throw IllegalStateException("push failed") },
        )
        second = request({ ProductLookupResult.Found(product(it, "Tea")) })

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { drainer.drain(first) } }

        assertEquals("push failed", thrown.message)
        assertEquals("Cola", itemName(a))
        // B was renamed by the request that came during the failed pass.
        assertEquals(0, store.pending().size)
        assertEquals(setOf("Cola", "Tea"), names().toSet())
        // And the drainer is free again.
        queuedItem("33333333")
        drainer.drain(request({ ProductLookupResult.NotFound }))
        assertEquals(0, store.pending().size)
    }
}
