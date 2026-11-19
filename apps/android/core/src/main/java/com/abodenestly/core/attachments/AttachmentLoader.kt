package com.abodenestly.core.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.abodenestly.core.auth.AuthService
import com.abodenestly.shared.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Loads attachment photos without an image library (Coil is not allowed), the way `HttpAvatarLoader` loads avatars.
 * Every failure is `null`, and the caller shows a placeholder instead.
 */
interface AttachmentLoader {
    /** [storagePath] is the attachment's Storage object path in the `households` bucket. */
    suspend fun load(storagePath: String): Bitmap?

    /** The local preview of a pick that has not uploaded yet. */
    suspend fun decode(bytes: ByteArray): Bitmap?
}

/** Always the placeholder: tests and previews. */
object NoAttachmentLoader : AttachmentLoader {
    override suspend fun load(storagePath: String): Bitmap? = null
    override suspend fun decode(bytes: ByteArray): Bitmap? = null
}

class HttpAttachmentLoader(private val config: AppConfig, private val auth: AuthService) : AttachmentLoader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

    override suspend fun load(storagePath: String): Bitmap? {
        cache.get(storagePath)?.let { return it }
        if (!STORAGE_PATH.matches(storagePath)) return null
        val bitmap = try {
            fetch(storagePath, auth.accessToken(refresh = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return bitmap?.also { cache.put(storagePath, it) }
    }

    override suspend fun decode(bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // The JWT goes only to the Supabase host.
    private suspend fun fetch(storagePath: String, token: String?): Bitmap? = withContext(Dispatchers.IO) {
        val url = config.supabaseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("storage/v1/object/authenticated/households")
            .apply { storagePath.split('/').forEach(::addPathSegment) }
            .build()
        val request = Request.Builder().url(url)
            .apply {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                    header("apikey", config.supabaseAnonKey)
                }
            }
            .build()
        try {
            http.newCall(request).execute().use { reply ->
                val body = reply.body
                if (!reply.isSuccessful || body == null || body.contentLength() > MAX_BYTES) null
                else {
                    val bytes = body.bytes()
                    if (bytes.size > MAX_BYTES) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private const val CACHE_SIZE = 16
        private const val MAX_BYTES = 25L * 1024 * 1024

        /** `households/<household>/<entity>/<entity id>/<file>`, the shape `POST /attachments/sign` returns. */
        private val STORAGE_PATH =
            Regex("^households/[0-9a-fA-F-]{36}/(pantry_item|asset|maint_log)/[0-9a-fA-F-]{36}/[A-Za-z0-9._-]+$")
    }
}
