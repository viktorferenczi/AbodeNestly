package com.abodenestly.shopping

import android.content.Context
import com.abodenestly.core.api.ProductLookupResult
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.listItemIdentity
import com.abodenestly.sync.patch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.util.UUID

/** SHOP-10 pure helpers. Twins: iOS `Barcode`, web `Barcode.ts`; keep the rules in step. */
object Barcode {
    const val CODE_ERROR = "Enter 8 to 14 digits"

    /** Same rule as the API (`api/_lib/products.ts`): 8 to 14 digits, no check-digit test. */
    fun isBarcode(code: String): Boolean = code.length in 8..14 && code.all { it in '0'..'9' }

    /**
     * A UPC-A code arrives from the camera as EAN-13 with a leading 0. Dropping it gives the 12-digit form that Open
     * Food Facts and the web client store. Camera reads only: a typed code is looked up as typed.
     */
    fun normalize(raw: String): String = if (raw.length == 13 && raw.startsWith("0")) raw.drop(1) else raw

    /** The product image is shown only for an https URL. */
    fun imageUrl(url: String?): String? {
        if (url == null) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() }
    }
}

/** A name typed for a code the API does not know (DESIGN C7 not-found). */
data class NameEntry(val code: String, val name: String)

/** A lookup queued while offline: the item carries the code as its name until the lookup resolves. */
data class PendingLookup(val householdId: String, val itemId: String, val code: String)

/**
 * The per-device name cache and the lookup queue (web: `localStorage`, iOS: `UserDefaults`). Both are JSON arrays under
 * the web keys. [read] and [write] are the one seam to the platform, so tests pass an in-memory pair and the app passes
 * [forContext]. One lock for the process: the scanner and the drainer both read-modify-write the same keys.
 */
class BarcodeStore(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    // An ordered array, not a map, so eviction follows insertion order whatever the codes look like.
    fun names(): List<NameEntry> = synchronized(LOCK) { load(NAMES_KEY) { o -> NameEntry(o.str("code") ?: return@load null, o.str("name") ?: return@load null) } }

    fun cachedName(code: String): String? = names().firstOrNull { it.code == code }?.name

    /** Remembers `code → name` on this device, newest last; the oldest entries beyond [NAMES_CAP] are dropped. */
    fun cacheName(code: String, name: String) = synchronized(LOCK) {
        val list = load(NAMES_KEY) { o -> NameEntry(o.str("code") ?: return@load null, o.str("name") ?: return@load null) }
            .filter { it.code != code } + NameEntry(code, name)
        save(NAMES_KEY, list.takeLast(NAMES_CAP).map { buildJsonObject { put("code", it.code); put("name", it.name) } })
    }

    fun pending(): List<PendingLookup> = synchronized(LOCK) { loadPending() }

    fun appendPending(entry: PendingLookup) = synchronized(LOCK) { savePending(loadPending() + entry) }

    /** Re-reads the queue first, since it may have grown while a lookup was in flight. */
    fun removePending(householdId: String, itemId: String) = synchronized(LOCK) {
        savePending(loadPending().filterNot { it.householdId == householdId && it.itemId == itemId })
    }

    private fun loadPending(): List<PendingLookup> = load(LOOKUPS_KEY) { o ->
        PendingLookup(o.str("householdId") ?: return@load null, o.str("itemId") ?: return@load null, o.str("code") ?: return@load null)
    }

    private fun savePending(list: List<PendingLookup>) =
        save(LOOKUPS_KEY, list.map { buildJsonObject { put("householdId", it.householdId); put("itemId", it.itemId); put("code", it.code) } })

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Reads entry by entry, so one bad entry does not drop the rest. */
    private fun <T : Any> load(key: String, entry: (JsonObject) -> T?): List<T> {
        val array = runCatching { Json.parseToJsonElement(read(key) ?: return emptyList()) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(entry) }
    }

    private fun save(key: String, entries: List<JsonObject>) {
        // The cache and the queue are conveniences; the list item itself is already stored.
        runCatching { write(key, buildJsonArray { entries.forEach { add(it) } }.toString()) }
    }

    companion object {
        const val NAMES_KEY = "abodenestly.barcodeNames"
        const val LOOKUPS_KEY = "abodenestly.barcodeLookups"
        const val NAMES_CAP = 200
        private val LOCK = Any()
        private const val PREFS = "abodenestly.barcode"

        fun inMemory(): BarcodeStore {
            val map = HashMap<String, String>()
            return BarcodeStore({ synchronized(map) { map[it] } }, { k, v -> synchronized(map) { map[k] = v } })
        }

        /** SharedPreferences, private to the app (the queue is device-local, like web's `localStorage`). */
        fun forContext(context: Context): BarcodeStore {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BarcodeStore({ prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).apply() })
        }
    }
}

/** What one drain asks for. [onRenamed] runs after a pass renamed at least one item, so the change is pushed. */
class DrainRequest(
    val database: DatabaseRegistry,
    val householdId: UUID,
    val lookup: suspend (String) -> ProductLookupResult,
    val isOnline: () -> Boolean,
    val store: BarcodeStore,
    val onRenamed: suspend () -> Unit = {},
)

/** The household whose queue is draining is no longer the open one (a switch during the pass). */
class HouseholdClosedException : IllegalStateException("The household closed during the drain")

/**
 * Resolves the queued lookups. Twins: iOS `BarcodeLookupDrainer`, web `drainLookups`.
 *
 * A product renames the item and sets its barcode, but only while the item still exists and is still named after the
 * code, so a rename by the person wins. A 404 or any other non-transient result drops the entry; a network failure or
 * 502 keeps it for the next drain. Entries of other households wait until that household is open.
 * A call during a pass is remembered (the latest one wins) and runs as one more pass when the current one ends, even if
 * it threw (a household switch closes the database). For the same household that pass skips the entries that just
 * failed transiently, so they are not fetched twice back to back.
 */
class BarcodeLookupDrainer(private val now: () -> java.time.Instant = java.time.Instant::now) {
    companion object {
        /** One per process: the scanner screen and the app shell both feed the same queue. */
        val shared = BarcodeLookupDrainer()
    }

    private var running = false
    private var latest: DrainRequest? = null

    suspend fun drain(request: DrainRequest) {
        synchronized(this) {
            if (running) {
                latest = request
                return
            }
            running = true
        }
        var failure: Exception? = null
        var released = false
        try {
            var current: DrainRequest? = request
            var skip = emptySet<String>()
            while (current != null) {
                val pass: DrainRequest = current
                synchronized(this) { latest = null }
                var failedNow = emptySet<String>()
                try {
                    failedNow = run(pass, skip)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (pass === request) failure = e
                }
                // A call during the pass may have set it. Deciding to stop and letting go happen together, so a call
                // that arrives in between is either seen here or starts its own drain.
                val next = synchronized(this) {
                    latest.also {
                        if (it == null) {
                            running = false
                            released = true
                        }
                    }
                }
                skip = if (next != null && next.householdId == pass.householdId) failedNow else emptySet()
                current = next
            }
        } finally {
            if (!released) synchronized(this) {
                running = false
                latest = null
            }
        }
        failure?.let { throw it }
    }

    /** One pass over the queue; returns the item ids that failed transiently. */
    private suspend fun run(request: DrainRequest, skip: Set<String>): Set<String> {
        val failedNow = HashSet<String>()
        if (!request.isOnline()) return failedNow
        if (request.database.current.value != request.householdId) throw HouseholdClosedException()
        val household = request.householdId.toString()
        var renamed = false
        for (entry in request.store.pending().filter { it.householdId == household && it.itemId !in skip }) {
            val result = lookupSafely(request.lookup, entry.code)
            // Keep the entry only for failures that can pass; anything else would be retried forever.
            if (result is ProductLookupResult.Offline) {
                failedNow += entry.itemId
                continue
            }
            if (result is ProductLookupResult.Found) {
                // A switch during the lookup must not read the other household's file as "no such item".
                if (request.database.current.value != request.householdId) throw HouseholdClosedException()
                val item = request.database.listItemIdentity(request.householdId, entry.itemId)
                if (item != null && item.deletedAt == null && item.name == entry.code) {
                    val product = result.product
                    // name_key is local only: patch writes it locally and never queues it (SPEC §6.2).
                    val fields = buildJsonObject {
                        put("name", product.name)
                        put("barcode", product.barcode)
                        put("name_key", NameKey.make(product.name))
                    }
                    request.database.patch(request.householdId, "list_item", entry.itemId, fields, now())
                    renamed = true
                }
            }
            request.store.removePending(entry.householdId, entry.itemId)
        }
        if (renamed) request.onRenamed()
        return failedNow
    }
}

/** A lookup that throws is read like the API would have answered: a network error passes by itself, anything else does not. */
internal suspend fun lookupSafely(lookup: suspend (String) -> ProductLookupResult, code: String): ProductLookupResult = try {
    lookup(code)
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ProductLookupResult.Offline
} catch (e: Exception) {
    ProductLookupResult.Failed
}
