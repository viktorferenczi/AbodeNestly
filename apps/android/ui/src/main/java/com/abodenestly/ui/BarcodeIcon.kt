package com.abodenestly.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A barcode glyph. material-icons-extended (which has `QrCodeScanner`) is not a dependency, so the bars are drawn here:
 * x, width pairs on a 24 x 24 grid. Filled black; callers tint it.
 */
val BarcodeIcon: ImageVector by lazy {
    val bars = listOf(3f to 2f, 7f to 1f, 10f to 3f, 15f to 1f, 18f to 3f)
    ImageVector.Builder(
        name = "Barcode", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            for ((x, w) in bars) {
                moveTo(x, 5f)
                horizontalLineTo(x + w)
                verticalLineTo(19f)
                horizontalLineTo(x)
                close()
            }
        }
    }.build()
}
