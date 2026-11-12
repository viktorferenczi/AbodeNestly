package com.abodenestly.core.api

import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.contract.models.Household
import com.abodenestly.core.auth.AuthService
import com.abodenestly.shared.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OkHttp client for the Vercel API. Attaches the user's JWT and, on a 401, refreshes the session once and
 * retries (kotlin-android skill, Networking). Lives in `:core` because `:contract` is generated-only; port of
 * iOS `HttpApiService`.
 */
class HttpApiService(
    private val config: AppConfig,
    private val auth: AuthService,
) : ApiService {
    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun createHousehold(name: String): ApiResult<Household> = guarded {
        // `CreateHouseholdRequest` is not in the generated models (the generator emits only schemas that
        // responses use), so the one-field body is built by hand.
        val body = Json.encodeToString(CreateBody.serializer(), CreateBody(name))
        val reply = post(url { addPathSegment("households") }, body)
        when {
            reply.status == 201 -> runCatching {
                Serializer.kotlinxSerializationJson.decodeFromString(Household.serializer(), reply.body)
            }.fold({ ApiResult.Success(it) }, { ApiResult.Failure(ApiFailure.Unknown) })
            errorCode(reply.body) == "validation" || reply.status == 400 -> ApiResult.Failure(ApiFailure.Validation)
            else -> ApiResult.Failure(ApiFailure.Unknown)
        }
    }

    override suspend fun acceptInvite(code: String): ApiResult<UUID> = guarded {
        // A code is user-typed text; addPathSegment percent-encodes `/` and `?` so they cannot change the route.
        val url = url { addPathSegment("invites"); addPathSegment(code); addPathSegment("accept") }
        val reply = post(url, "{}")
        if (reply.status !in 200..299) return@guarded ApiResult.Failure(acceptFailure(reply.status, errorCode(reply.body)))
        runCatching { Serializer.kotlinxSerializationJson.decodeFromString(Accepted.serializer(), reply.body).householdId }
            .fold({ ApiResult.Success(it) }, { ApiResult.Failure(ApiFailure.Unknown) })
    }

    // MARK: Transport

    private class Reply(val status: Int, val body: String)

    private fun url(build: HttpUrl.Builder.() -> Unit): HttpUrl = config.apiBaseUrl.toHttpUrl().newBuilder().apply(build).build()

    private suspend fun post(url: HttpUrl, body: String): Reply {
        val first = send(url, body, auth.accessToken(refresh = false))
        if (first.status != 401) return first
        return send(url, body, auth.accessToken(refresh = true))
    }

    private suspend fun send(url: HttpUrl, body: String, token: String?): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { Reply(it.code, it.body?.string().orEmpty()) }
    }

    private suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Failure(ApiFailure.Network)
    } catch (e: Exception) {
        ApiResult.Failure(ApiFailure.Unknown)
    }

    // MARK: Bodies

    @Serializable
    private data class CreateBody(val name: String)

    @Serializable
    private data class Accepted(@Contextual @SerialName("household_id") val householdId: UUID)

    /** `{ "error": { "code": … } }`, the contract's `ApiError` shape. */
    @Serializable
    private data class ErrorBody(val error: Inner? = null) {
        @Serializable
        data class Inner(val code: String? = null)
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private val JSON = "application/json".toMediaType()
        private val lenient = Json { ignoreUnknownKeys = true }

        private fun errorCode(body: String): String? =
            runCatching { lenient.decodeFromString(ErrorBody.serializer(), body).error?.code }.getOrNull()

        // CORE-03-api has not fixed its error code yet; the SQL raises `invalid_invite`. `not_found` and `gone`
        // are accepted so the pre-CORE-03 missing route (404 `not_found`) reads as an invalid code, like web.
        // Any other code, `validation` included, stays `Unknown`.
        internal fun acceptFailure(status: Int, code: String?): ApiFailure {
            if (code != null) return if (code in INVALID_CODES) ApiFailure.InvalidCode else ApiFailure.Unknown
            // No parseable error body (e.g. a bare proxy 404): fall back to the status.
            return if (status == 404 || status == 410) ApiFailure.InvalidCode else ApiFailure.Unknown
        }

        private val INVALID_CODES = setOf("invalid_invite", "not_found", "gone")
    }
}
