package com.abodenestly.shopping

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner

/** Why the camera could not start. A denied permission is not an error here: it shows in [ScannerDevice.requestPermission]. */
class ScannerException(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        /** No camera, or the device cannot read barcodes. */
        Unsupported,

        /** The camera is busy or failed to start. */
        Unavailable,
    }
}

/**
 * The camera, its permission, its preview and its torch behind one small interface, so tests use a fake (Robolectric has
 * no camera). Twins: iOS `ScannerDevice`, web `ScannerDevice.ts`. The model calls everything except [Preview] from the main thread.
 */
interface ScannerDevice {
    /** False on a device with no camera at all (`FEATURE_CAMERA_ANY`): the scanner shows `unsupported`. */
    val hasCamera: Boolean

    fun hasPermission(): Boolean

    /** Asks the system for camera access; true when granted. A permission the system will not ask for again answers false at once. */
    suspend fun requestPermission(): Boolean

    /**
     * Starts reading and calls [onCode] on the main thread with the raw value of each EAN-13, EAN-8, UPC-A or UPC-E code
     * seen (ML Kit reports UPC-A as UPC-A; EAN-13 with a leading 0 is [Barcode.normalize]d by the model). Throws [ScannerException].
     * The preview from [Preview] must be on screen; the device waits briefly for it.
     */
    suspend fun start(owner: LifecycleOwner, onCode: (String) -> Unit)

    fun stop()

    /** Only meaningful after [start] succeeded. */
    val hasTorch: Boolean

    fun setTorch(on: Boolean)

    /** The live camera picture, filling [modifier]. */
    @Composable
    fun Preview(modifier: Modifier)
}
