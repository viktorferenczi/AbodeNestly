package com.abodenestly.shopping

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.abodenestly.core.api.ProductLookupResult
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SyncEngine
import com.abodenestly.sync.SyncPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * What the list detail needs to offer the scanner (SHOP-10): the lookup, the per-device store, the picture loader, and how to get a
 * camera. Absent (`null`), the quick-add bar shows no scan button. [device] is composed when the scanner opens, so the
 * permission launcher lives and dies with it; tests return a fake.
 */
class ScannerSetup(
    val lookup: suspend (String) -> ProductLookupResult,
    val store: BarcodeStore,
    val images: ProductImageLoader = NoProductImageLoader,
    val device: @Composable () -> ScannerDevice,
)

/** The app's scanner: the real camera, the device's SharedPreferences and OkHttp for pictures. [lookup] is `ApiService::product`. */
@Composable
fun rememberScannerSetup(lookup: suspend (String) -> ProductLookupResult): ScannerSetup {
    val context = LocalContext.current
    val latest by rememberUpdatedState(lookup)
    return remember(context) {
        ScannerSetup(
            lookup = { latest(it) },
            store = BarcodeStore.forContext(context),
            images = HttpProductImageLoader(),
            device = { rememberCameraXScannerDevice() },
        )
    }
}

/**
 * SHOP-10: resolves the barcode lookups queued while offline. Mount it only while the Shopping module is on, with the
 * open household. It drains when the household opens, when the network comes back and after every sync run, so a queued
 * lookup whose `list_item` rename needs a push is sent by the kick that [DrainRequest.onRenamed] asks for. That kick runs a
 * sync, which drains again; the second drain finds nothing new to rename, so it stops there.
 * [householdId] null (no household, or Shopping off) drains nothing.
 */
@Composable
fun BarcodeDrainEffect(
    householdId: UUID?,
    database: DatabaseRegistry,
    sync: SyncEngine,
    isOnline: StateFlow<Boolean>,
    lookup: suspend (String) -> ProductLookupResult,
    drainer: BarcodeLookupDrainer = BarcodeLookupDrainer.shared,
) {
    val context = LocalContext.current
    val store = remember(context) { BarcodeStore.forContext(context) }
    val latestLookup by rememberUpdatedState(lookup)
    LaunchedEffect(householdId, database, sync, isOnline, store) {
        if (householdId == null) return@LaunchedEffect
        val request = DrainRequest(
            database, householdId, { latestLookup(it) }, { isOnline.value }, store, onRenamed = { sync.kick() },
        )
        coroutineScope {
            // Household open, and a sync run that ended (the engine goes back to idle after each pass).
            launch { sync.phase.collect { if (it == SyncPhase.Idle) drainQuietly(drainer, request) } }
            // Reconnect: the flow's first value is the current one, which is the household-open drain when the sync is not idle yet.
            launch { isOnline.collect { if (it) drainQuietly(drainer, request) } }
        }
    }
}

/** A drain that fails (a household switch closed the database) is retried by the next trigger. */
private suspend fun drainQuietly(drainer: BarcodeLookupDrainer, request: DrainRequest) {
    try {
        drainer.drain(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Nothing to report: the entries stay queued.
    }
}
