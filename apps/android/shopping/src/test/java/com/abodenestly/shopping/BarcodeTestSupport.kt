package com.abodenestly.shopping

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.abodenestly.contract.models.Product

/** A scripted camera (TESTING §2: hand-written fakes, no mocking libraries). Robolectric has no camera. */
class FakeScannerDevice(
    override var hasCamera: Boolean = true,
    var granted: Boolean = true,
    /** What the system dialog answers while the permission is not granted. */
    var grantsOnRequest: Boolean = true,
    override var hasTorch: Boolean = false,
    var startError: ScannerException? = null,
) : ScannerDevice {
    @Volatile var startCount = 0
    @Volatile var stopCount = 0
    @Volatile var requestCount = 0
    @Volatile var torchOn = false
    @Volatile private var onCode: ((String) -> Unit)? = null

    override fun hasPermission(): Boolean = granted

    override suspend fun requestPermission(): Boolean {
        requestCount++
        if (grantsOnRequest) granted = true
        return granted
    }

    override suspend fun start(owner: LifecycleOwner, onCode: (String) -> Unit) {
        startCount++
        startError?.let { throw it }
        this.onCode = onCode
    }

    override fun stop() {
        stopCount++
        onCode = null
        torchOn = false
    }

    override fun setTorch(on: Boolean) {
        torchOn = on
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        Box(modifier.testTag("barcode.fake.preview"))
    }

    /** Plays one camera read; ignored once the device is stopped, like a real one. */
    fun scan(raw: String) {
        onCode?.invoke(raw)
    }
}

/** A lifecycle owner that is already resumed, for tests that call the model without a screen. */
class ResumedOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}

class FakeImages(private val bitmap: Bitmap? = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)) : ProductImageLoader {
    val urls = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun load(url: String): Bitmap? {
        urls += url
        return bitmap
    }
}

fun nutella(imageUrl: String? = "https://images.test/nutella.jpg", brand: String? = "Ferrero") =
    Product(barcode = "3017620422003", name = "Nutella", brand = brand, category = null, imageUrl = imageUrl, defaultUnit = null)

fun product(barcode: String, name: String) =
    Product(barcode = barcode, name = name, brand = null, category = null, imageUrl = null, defaultUnit = null)
