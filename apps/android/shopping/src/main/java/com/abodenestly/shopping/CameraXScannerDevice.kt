package com.abodenestly.shopping

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode as MlKitBarcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SHOP-10 camera: CameraX `Preview` + `ImageAnalysis` (keep only the latest frame) feeding ML Kit `BarcodeScanning`
 * (EAN-13, EAN-8, UPC-A, UPC-E). Create it with [rememberCameraXScannerDevice], which wires the permission launcher.
 * Not covered by unit tests (Robolectric has no camera): the model's rules are tested against a fake [ScannerDevice].
 */
class CameraXScannerDevice(context: Context) : ScannerDevice {
    private val appContext = context.applicationContext

    /** Set by [rememberCameraXScannerDevice]: shows the system permission dialog. */
    internal var launchPermission: () -> Unit = {}
    private var permissionAnswer: CompletableDeferred<Boolean>? = null

    /** The `PreviewView` of the Preview on screen, or null while there is none. */
    private val previewView = MutableStateFlow<PreviewView?>(null)

    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    override val hasCamera: Boolean
        get() = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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

    override val hasTorch: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() == true

    override fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    override suspend fun start(owner: LifecycleOwner, onCode: (String) -> Unit) {
        // The preview is composed in the same frame the model asks to start; wait a moment for its view.
        val view = withTimeoutOrNull(PREVIEW_WAIT_MS) { previewView.filterNotNull().first() }
            ?: throw ScannerException(ScannerException.Kind.Unavailable)
        val cameraProvider = try {
            awaitProvider(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ScannerException(ScannerException.Kind.Unavailable)
        }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val hasBack = try {
            cameraProvider.hasCamera(selector)
        } catch (e: Exception) {
            false
        }
        if (!hasBack) throw ScannerException(ScannerException.Kind.Unsupported)
        val pool = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(MlKitBarcode.FORMAT_EAN_13, MlKitBarcode.FORMAT_EAN_8, MlKitBarcode.FORMAT_UPC_A, MlKitBarcode.FORMAT_UPC_E)
                .build(),
        )
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        analysis.setAnalyzer(pool) { proxy ->
            val media = proxy.mediaImage()
            if (media == null) {
                proxy.close()
                return@setAnalyzer
            }
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener(mainExecutor) { codes ->
                    // Unreadable or partial reads give an empty list; the next frame tries again.
                    codes.firstNotNullOfOrNull { it.rawValue }?.let(onCode)
                }
                .addOnCompleteListener { proxy.close() }
        }
        try {
            // Binding and the surface provider must be set on the main thread.
            camera = withContext(Dispatchers.Main) {
                val preview = CameraPreview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
            }
        } catch (e: CancellationException) {
            pool.shutdown()
            scanner.close()
            throw e
        } catch (e: Exception) {
            pool.shutdown()
            scanner.close()
            throw ScannerException(ScannerException.Kind.Unavailable)
        }
        provider = cameraProvider
        executor = pool
        closer = { scanner.close() }
    }

    private var closer: (() -> Unit)? = null

    override fun stop() {
        val toUnbind = provider
        val pool = executor
        val close = closer
        provider = null
        executor = null
        closer = null
        camera = null
        // The analyzer may still be mid-frame; unbinding stops new frames, and the pool and scanner are released after.
        toUnbind?.unbindAll()
        pool?.shutdown()
        close?.invoke()
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView.value = this
                }
            },
            onRelease = { if (previewView.value === it) previewView.value = null },
        )
    }

    private companion object {
        const val PREVIEW_WAIT_MS = 3_000L

        suspend fun awaitProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }
}

/** A [CameraXScannerDevice] wired to the system permission dialog; one per composition of the scanner. */
@Composable
fun rememberCameraXScannerDevice(): ScannerDevice {
    val context = LocalContext.current
    val device = remember(context) { CameraXScannerDevice(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        device.onPermissionResult(granted)
    }
    SideEffect { device.launchPermission = { launcher.launch(Manifest.permission.CAMERA) } }
    return device
}

// ExperimentalGetImage is an androidx opt-in marker, so lint only accepts androidx.annotation.OptIn
// (not kotlin.OptIn), and only on the declaration that reads `image`.
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun ImageProxy.mediaImage(): android.media.Image? = image
