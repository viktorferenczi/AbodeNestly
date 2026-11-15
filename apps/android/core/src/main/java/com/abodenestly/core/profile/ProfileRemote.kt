package com.abodenestly.core.profile

import com.abodenestly.core.auth.AuthService
import com.abodenestly.shared.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

sealed class ProfileRemoteError {
    /** No connection or a server that is down: keep the entry and retry on the next trigger. */
    data object Network : ProfileRemoteError()

    /** Still 401 after one refresh: the session is gone; keep the entry for the next sign-in. */
    data object Unauthorized : ProfileRemoteError()

    /** The server refused this edit (RLS, validation, no such row): retrying cannot help. */
    data object Rejected : ProfileRemoteError()
}

/** Thrown by [ProfileRemote]; carries the typed error (AGENTS §4: errors are values with a code). */
class ProfileRemoteFailure(val error: ProfileRemoteError) : Exception(error.toString())

/** The two writes the profile drainer makes with the user's JWT. */
interface ProfileRemote {
    /** `null` fields are left unchanged. @throws ProfileRemoteFailure */
    suspend fun patchProfile(userId: UUID, displayName: String?, avatarPath: String?)

    /** Uploads to `households/profiles/<userId>/<fileName>` and returns the bucket-relative path. @throws ProfileRemoteFailure */
    suspend fun uploadAvatar(userId: UUID, fileName: String, bytes: ByteArray): String
}

/** OkHttp against PostgREST and Storage; the same bearer and one-retry-on-401 as `HttpApiService`. */
class HttpProfileRemote(private val config: AppConfig, private val auth: AuthService) : ProfileRemote {
    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun patchProfile(userId: UUID, displayName: String?, avatarPath: String?) {
        val body = buildJsonObject {
            displayName?.let { put("display_name", it) }
            avatarPath?.let { put("avatar_path", it) }
        }.toString()
        val url = base("rest", "v1", "profile").addQueryParameter("id", "eq.$userId").build()
        val reply = perform(url, "PATCH", mapOf("Prefer" to "return=representation"), body.toRequestBody(JSON), "application/json")
        failIfNotOk(reply.status)
        // RLS hides rows silently, so an empty array means nothing changed.
        val changed = (runCatching { lenient.parseToJsonElement(reply.body) }.getOrNull() as? JsonArray)?.isNotEmpty() == true
        if (!changed) throw ProfileRemoteFailure(ProfileRemoteError.Rejected)
    }

    override suspend fun uploadAvatar(userId: UUID, fileName: String, bytes: ByteArray): String {
        val path = "profiles/$userId/$fileName"
        val url = base("storage", "v1", "object", "households", "profiles", userId.toString(), fileName).build()
        val reply = perform(url, "POST", mapOf("x-upsert" to "true"), bytes.toRequestBody(JPEG), "application/json")
        failIfNotOk(reply.status)
        return path
    }

    private fun base(vararg segments: String): HttpUrl.Builder =
        config.supabaseUrl.trimEnd('/').toHttpUrl().newBuilder().apply { segments.forEach(::addPathSegment) }

    private class Reply(val status: Int, val body: String)

    private fun failIfNotOk(status: Int) {
        if (status in 200..299) return
        throw ProfileRemoteFailure(statusError(status))
    }

    private suspend fun perform(
        url: HttpUrl, method: String, headers: Map<String, String>, body: RequestBody, accept: String,
    ): Reply = try {
        val first = send(url, method, headers, body, accept, auth.accessToken(refresh = false))
        if (first.status != 401) first else send(url, method, headers, body, accept, auth.accessToken(refresh = true))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw ProfileRemoteFailure(ProfileRemoteError.Network)
    }

    private suspend fun send(
        url: HttpUrl, method: String, headers: Map<String, String>, body: RequestBody, accept: String, token: String?,
    ): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).method(method, body)
            .header("Accept", accept)
            .header("apikey", config.supabaseAnonKey)
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                if (token != null) header("Authorization", "Bearer $token")
            }
            .build()
        http.newCall(request).execute().use { Reply(it.code, it.body?.string().orEmpty()) }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        private val lenient = Json { ignoreUnknownKeys = true }

        /** 401 after the refresh is a lost session, 5xx / 408 / 429 are the server being away, other 4xx are final. */
        internal fun statusError(status: Int): ProfileRemoteError = when {
            status == 401 -> ProfileRemoteError.Unauthorized
            status >= 500 || status == 408 || status == 429 -> ProfileRemoteError.Network
            else -> ProfileRemoteError.Rejected
        }
    }
}

/** Hand-written fake for tests (TESTING §2); lives in main like `FakeAuthService`. */
class FakeProfileRemote : ProfileRemote {
    sealed interface Call {
        data class Patch(val userId: UUID, val displayName: String?, val avatarPath: String?) : Call
        data class Upload(val userId: UUID, val fileName: String, val size: Int) : Call
    }

    private val recorded = CopyOnWriteArrayList<Call>()
    val calls: List<Call> get() = recorded.toList()

    /** When set, every call throws it. */
    @Volatile var failure: ProfileRemoteError? = null
    @Volatile private var gate: CompletableDeferred<Unit>? = null

    /** Makes the next calls suspend until [release], so tests can observe the uploading state. */
    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun patchProfile(userId: UUID, displayName: String?, avatarPath: String?) {
        recorded += Call.Patch(userId, displayName, avatarPath)
        gate?.await()
        failure?.let { throw ProfileRemoteFailure(it) }
    }

    override suspend fun uploadAvatar(userId: UUID, fileName: String, bytes: ByteArray): String {
        recorded += Call.Upload(userId, fileName, bytes.size)
        gate?.await()
        failure?.let { throw ProfileRemoteFailure(it) }
        return "profiles/$userId/$fileName"
    }
}
