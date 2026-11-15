package com.abodenestly.core.profile

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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Loads avatar pictures without an image library (Coil is not allowed). Every failure is `null`, and the
 * caller shows the initials instead.
 */
interface AvatarLoader {
    /** [avatarPath] is a Storage object path or the provider's http(s) URL (SPEC §3). */
    suspend fun load(avatarPath: String): Bitmap?
    suspend fun loadFile(filePath: String): Bitmap?
}

/** Always the initials: tests and previews. */
object NoAvatarLoader : AvatarLoader {
    override suspend fun load(avatarPath: String): Bitmap? = null
    override suspend fun loadFile(filePath: String): Bitmap? = null
}

class HttpAvatarLoader(private val config: AppConfig, private val auth: AuthService) : AvatarLoader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

    override suspend fun load(avatarPath: String): Bitmap? {
        cache.get(avatarPath)?.let { return it }
        val bitmap = try {
            if (isUrl(avatarPath)) fetch(avatarPath.toHttpUrlOrNull(), token = null)
            else if (STORAGE_PATH.matches(avatarPath)) fetch(storageUrl(avatarPath), token = auth.accessToken(refresh = false))
            else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return bitmap?.also { cache.put(avatarPath, it) }
    }

    override suspend fun loadFile(filePath: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { File(filePath).takeIf { it.isFile }?.readBytes()?.let(::decode) }.getOrNull()
    }

    // The JWT goes only to the Supabase host, never to a provider avatar URL.
    private suspend fun fetch(url: okhttp3.HttpUrl?, token: String?): Bitmap? {
        url ?: return null
        return withContext(Dispatchers.IO) {
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
                    else readCapped(body.byteStream())?.let(::decode)
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun storageUrl(path: String) =
        config.supabaseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("storage/v1/object/authenticated/households")
            .apply { path.split('/').forEach(::addPathSegment) }
            .build()

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()

    /** Counts bytes as they arrive, so a body without a Content-Length cannot exceed [MAX_BYTES] either. */
    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private const val CACHE_SIZE = 8
        private const val MAX_BYTES = 5L * 1024 * 1024

        /** `profiles/<user>/<file>`: exactly the two folders the Storage policy allows. */
        private val STORAGE_PATH = Regex("^profiles/[0-9a-fA-F-]{36}/[A-Za-z0-9._-]+$")

        fun isUrl(avatarPath: String): Boolean = avatarPath.startsWith("http://", true) || avatarPath.startsWith("https://", true)
    }
}
