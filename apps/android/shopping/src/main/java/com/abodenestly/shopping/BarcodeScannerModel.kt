package com.abodenestly.shopping

import androidx.lifecycle.LifecycleOwner
import com.abodenestly.contract.models.Product
import com.abodenestly.core.api.ProductLookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** DESIGN C7 states. [Looking] is between a code being read and the lookup answering; [Queued] is the offline state. */
sealed interface ScannerPhase {
    data object Scanning : ScannerPhase
    data class Looking(val code: String) : ScannerPhase
    data class Found(val product: Product) : ScannerPhase
    data class NotFound(val code: String) : ScannerPhase

    /** The lookup failed in a way that is not "offline" (401, 500): nothing was written; Retry or type a name. */
    data class Failed(val code: String) : ScannerPhase
    data class Queued(val code: String) : ScannerPhase
    data object PermissionDenied : ScannerPhase
    data object Unsupported : ScannerPhase
    data object CameraUnavailable : ScannerPhase

    /** The states with no camera: only the manual code field is on offer. */
    val lacksCamera: Boolean get() = this is PermissionDenied || this is Unsupported || this is CameraUnavailable
}

/**
 * What the scanner shows. [code] is the manual code field, [name] the name field of not-found and failed (the cache
 * prefills it only when a code first appears, so a retry never wipes what was typed). [addFailed] is the item that could
 * not be stored ("Couldn't add that item. Try again."); [adding] is an Add running.
 */
data class ScannerUiState(
    val phase: ScannerPhase = ScannerPhase.Scanning,
    val hasTorch: Boolean = false,
    val torchOn: Boolean = false,
    val code: String = "",
    val codeError: Boolean = false,
    val name: String = "",
    val addFailed: Boolean = false,
    val adding: Boolean = false,
)

/**
 * SHOP-10 scanner logic. Twins: iOS `BarcodeScannerModel`, web `BarcodeScanner.tsx` with `Barcode.ts`; keep the rules in
 * step. The item write is injected ([add]: name, barcode, then a hook that runs after the row is stored and before sync
 * is kicked; returns the new item's id, or null when it could not be stored), so this type never touches the database:
 * `ListDetailViewModel.makeScanner` supplies the outbox write. [scope] runs camera reads and is the caller's to cancel.
 */
class BarcodeScannerModel(
    private val householdId: String,
    private val device: ScannerDevice,
    private val lookup: suspend (String) -> ProductLookupResult,
    private val isOnline: () -> Boolean,
    private val store: BarcodeStore,
    private val add: suspend (name: String, barcode: String?, beforeKick: (String) -> Unit) -> String?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = mutableState.asStateFlow()

    /** A code has been taken from the camera (or typed); no second one is handled until Scan again or Retry. */
    private val handled = AtomicBoolean(false)

    /** True from the start of a lookup to its end: one code can never produce two rows. */
    private val looking = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val addingNow = AtomicBoolean(false)
    private var nameCode: String? = null
    private var owner: LifecycleOwner? = null

    private val phase: ScannerPhase get() = mutableState.value.phase

    private fun setPhase(next: ScannerPhase) = mutableState.update { it.copy(phase = next) }

    // --- camera ---

    /** Checks the camera and its permission, then starts reading. Manual entry works in every state. */
    suspend fun startCamera(owner: LifecycleOwner) {
        this.owner = owner
        if (phase != ScannerPhase.Scanning || !starting.compareAndSet(false, true)) return
        try {
            handled.set(false)
            mutableState.update { it.copy(torchOn = false) }
            if (!device.hasCamera) {
                setPhase(ScannerPhase.Unsupported)
                return
            }
            if (!device.hasPermission()) {
                val granted = device.requestPermission()
                // A code typed while the system dialog was up already moved on; do not overwrite its state.
                if (phase != ScannerPhase.Scanning) return
                if (!granted) {
                    setPhase(ScannerPhase.PermissionDenied)
                    return
                }
            }
            try {
                device.start(owner) { raw -> didRead(raw) }
                mutableState.update { it.copy(hasTorch = device.hasTorch) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScannerException) {
                if (phase == ScannerPhase.Scanning) {
                    setPhase(if (e.kind == ScannerException.Kind.Unsupported) ScannerPhase.Unsupported else ScannerPhase.CameraUnavailable)
                }
            } catch (e: Exception) {
                if (phase == ScannerPhase.Scanning) setPhase(ScannerPhase.CameraUnavailable)
            }
            // A code was typed while the camera was starting (or the camera failed): the camera is not needed any more.
            if (phase != ScannerPhase.Scanning) stop()
        } finally {
            starting.set(false)
        }
    }

    fun stop() {
        device.stop()
        mutableState.update { it.copy(hasTorch = false, torchOn = false) }
    }

    fun toggleTorch() {
        val next = !mutableState.value.torchOn
        val ok = try {
            device.setTorch(next)
            true
        } catch (e: Exception) {
            false
        }
        mutableState.update { it.copy(torchOn = ok && next) }
    }

    /** Unreadable or partial reads are ignored; the next frame tries again. */
    private fun didRead(raw: String) {
        val code = Barcode.normalize(raw)
        if (phase != ScannerPhase.Scanning || !Barcode.isBarcode(code)) return
        scope.launch { handle(code) }
    }

    // --- lookup ---

    /** A code from the camera or the manual field. Ignored while another one is being handled. */
    suspend fun handle(raw: String) {
        if (handled.get() || !looking.compareAndSet(false, true)) return
        handled.set(true)
        try {
            stop()
            mutableState.update { it.copy(addFailed = false, phase = ScannerPhase.Looking(raw)) }
            // Offline is decided here, without a request, as on web (`navigator.onLine`).
            val result = if (isOnline()) lookupSafely(lookup, raw) else ProductLookupResult.Offline
            when (result) {
                is ProductLookupResult.Found -> setPhase(ScannerPhase.Found(result.product))
                ProductLookupResult.NotFound -> {
                    prefill(raw)
                    setPhase(ScannerPhase.NotFound(raw))
                }
                ProductLookupResult.Failed -> {
                    prefill(raw)
                    setPhase(ScannerPhase.Failed(raw))
                }
                ProductLookupResult.Offline -> queueOffline(raw)
            }
        } finally {
            looking.set(false)
        }
    }

    /**
     * The code becomes the name, with no barcode (the list_item foreign key needs a product row), and the lookup waits.
     * The lookup is queued before the add's kick, so the drain after that sync run already sees it.
     */
    private suspend fun queueOffline(raw: String) {
        val id = add(raw, null, { itemId -> store.appendPending(PendingLookup(householdId, itemId, raw)) })
        if (id != null) {
            setPhase(ScannerPhase.Queued(raw))
            return
        }
        mutableState.update { it.copy(addFailed = true, phase = ScannerPhase.Scanning) }
        handled.set(false)
        owner?.let { startCamera(it) }
    }

    /** Retry after a failed lookup. A tap while the lookup runs does nothing. */
    suspend fun retry() {
        val failed = phase as? ScannerPhase.Failed ?: return
        if (looking.get()) return
        handled.set(false)
        handle(failed.code)
    }

    fun setCode(text: String) = mutableState.update { it.copy(code = text, codeError = false) }

    fun setName(text: String) = mutableState.update { it.copy(name = text) }

    suspend fun submitCode() {
        val trimmed = mutableState.value.code.trim()
        if (!Barcode.isBarcode(trimmed)) {
            mutableState.update { it.copy(codeError = true) }
            return
        }
        mutableState.update { it.copy(codeError = false) }
        handle(trimmed)
    }

    /** The cache fills the name field only when a new code appears. */
    private fun prefill(raw: String) {
        if (nameCode == raw) return
        nameCode = raw
        mutableState.update { it.copy(name = store.cachedName(raw).orEmpty()) }
    }

    // --- adding ---

    /** Found: the product's name and its barcode (the product row exists now, so the foreign key holds). True when stored. */
    suspend fun addFound(): Boolean {
        val found = phase as? ScannerPhase.Found ?: return false
        if (!addingNow.compareAndSet(false, true)) return false
        mutableState.update { it.copy(adding = true) }
        try {
            if (add(found.product.name, found.product.barcode, NO_HOOK) == null) {
                mutableState.update { it.copy(addFailed = true) }
                return false
            }
            return true
        } finally {
            addingNow.set(false)
            mutableState.update { it.copy(adding = false) }
        }
    }

    /** Not found, or the lookup failed: the typed name with no barcode, remembered on this device for next time. True when stored. */
    suspend fun addNamed(): Boolean {
        val code = when (val p = phase) {
            is ScannerPhase.NotFound -> p.code
            is ScannerPhase.Failed -> p.code
            else -> return false
        }
        val typed = mutableState.value.name.trim()
        if (typed.isEmpty() || !addingNow.compareAndSet(false, true)) return false
        mutableState.update { it.copy(adding = true) }
        try {
            if (add(typed, null, NO_HOOK) == null) {
                mutableState.update { it.copy(addFailed = true) }
                return false
            }
            store.cacheName(code, typed)
            return true
        } finally {
            addingNow.set(false)
            mutableState.update { it.copy(adding = false) }
        }
    }

    /** Back to the camera for the next code. */
    suspend fun scanAgain() {
        nameCode = null
        handled.set(false)
        mutableState.update { it.copy(addFailed = false, code = "", codeError = false, phase = ScannerPhase.Scanning) }
        owner?.let { startCamera(it) }
    }
}

private val NO_HOOK: (String) -> Unit = {}
