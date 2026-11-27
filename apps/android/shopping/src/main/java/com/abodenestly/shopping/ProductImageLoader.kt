package com.abodenestly.shopping

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Loads a product's picture for the scanner's result card, without an image library (Coil is not allowed). Every failure
 * is `null`, and the card then shows no picture.
 */
interface ProductImageLoader {
    /** [url] is an https URL (see [Barcode.imageUrl]). */
    suspend fun load(url: String): Bitmap?
}

/** No pictures: tests and previews. */
object NoProductImageLoader : ProductImageLoader {
    override suspend fun load(url: String): Bitmap? = null
}

/** The same OkHttp + `BitmapFactory` pattern as the avatar loader, but with no bearer token: the URL is Open Food Facts', not ours. */
class HttpProductImageLoader(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : ProductImageLoader {
    override suspend fun load(url: String): Bitmap? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { reply ->
                    val body = reply.body
                    if (!reply.isSuccessful || body == null || body.contentLength() > MAX_BYTES) null
                    else readCapped(body.byteStream())?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    /** Counts bytes as they arrive, so a body without a Content-Length cannot exceed [MAX_BYTES] either. */
    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}
