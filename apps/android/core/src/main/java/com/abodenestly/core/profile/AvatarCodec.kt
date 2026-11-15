package com.abodenestly.core.profile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Turns a picked photo into the small JPEG that gets stored and uploaded. */
object AvatarCodec {
    const val MAX_SIDE = 512
    private const val QUALITY = 85

    /** `null` when [bytes] is not an image. Keeps the aspect ratio and the EXIF orientation. */
    fun downscaleJpeg(bytes: ByteArray, maxSide: Int = MAX_SIDE): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // Decode at most 2x the target, then scale exactly, so a 48 MP photo never sits in memory at full size.
        while (bounds.outWidth / (sample * 2) >= maxSide * 2 && bounds.outHeight / (sample * 2) >= maxSide * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation(bytes)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
    }

    // The framework class is used on purpose: androidx.exifinterface is not an allowed dependency (kotlin-android skill).
    @SuppressLint("ExifInterface")
    private fun orientation(bytes: ByteArray): Int =
        runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}
