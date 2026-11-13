package com.abodenestly.core.api

import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Invite
import com.abodenestly.core.auth.AuthService
import com.abodenestly.shared.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

    override suspend fun createInvite(householdId: UUID): ApiResult<Invite> = guarded {
        val url = url { addPathSegment("households"); addPathSegment(householdId.toString()); addPathSegment("invites") }
        val reply = post(url, "{}")
        if (reply.status != 201) return@guarded ApiResult.Failure(ApiFailure.Unknown)
        runCatching { Serializer.kotlinxSerializationJson.decodeFromString(Invite.serializer(), reply.body) }
            .fold({ ApiResult.Success(it) }, { ApiResult.Failure(ApiFailure.Unknown) })
    }

    override suspend fun fetchLiveInvite(householdId: UUID): ApiResult<Invite?> = guarded {
        val url = restUrl(
            "household_id" to "eq.$householdId",
            "deleted_at" to "is.null",
            "expires_at" to "gt.${timestamp()}",
            "order" to "created_at.desc",
        )
        val reply = perform(url, "GET", restHeaders())
        if (reply.status != 200) return@guarded ApiResult.Failure(ApiFailure.Unknown)
        runCatching {
            Serializer.kotlinxSerializationJson.decodeFromString(ListSerializer(Invite.serializer()), reply.body)
        }.fold(
            // PostgREST cannot compare two columns, so `uses < max_uses` is filtered here.
            { invites -> ApiResult.Success(invites.firstOrNull { it.uses < it.maxUses }) },
            { ApiResult.Failure(ApiFailure.Unknown) },
        )
    }

    override suspend fun retireInvite(id: UUID): ApiResult<Unit> = guarded {
        val body = buildJsonObject { put("deleted_at", timestamp()) }.toString()
        val headers = restHeaders() + ("Prefer" to "return=representation")
        val reply = perform(restUrl("id" to "eq.$id"), "PATCH", headers, body)
        // RLS hides rows silently, so an empty array means nothing was retired.
        val retired = reply.status in 200..299 &&
            (runCatching { lenient.parseToJsonElement(reply.body) }.getOrNull() as? JsonArray)?.isNotEmpty() == true
        if (retired) ApiResult.Success(Unit) else ApiResult.Failure(ApiFailure.Unknown)
    }

    // MARK: Transport

    private class Reply(val status: Int, val body: String)

    private fun url(build: HttpUrl.Builder.() -> Unit): HttpUrl = config.apiBaseUrl.toHttpUrl().newBuilder().apply(build).build()

    /** `<supabaseUrl>/rest/v1/invite?…`; query values are percent-encoded by OkHttp. */
    private fun restUrl(vararg query: Pair<String, String>): HttpUrl =
        config.supabaseUrl.trimEnd('/').plus("/rest/v1/invite").toHttpUrl().newBuilder()
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

    private fun restHeaders(): Map<String, String> = mapOf("apikey" to config.supabaseAnonKey)

    private suspend fun post(url: HttpUrl, body: String): Reply = perform(url, "POST", emptyMap(), body)

    /** Sends with the user's JWT; on a 401 refreshes the session once and retries. */
    private suspend fun perform(url: HttpUrl, method: String, headers: Map<String, String>, body: String? = null): Reply {
        val first = send(url, method, headers, body, auth.accessToken(refresh = false))
        if (first.status != 401) return first
        return send(url, method, headers, body, auth.accessToken(refresh = true))
    }

    private suspend fun send(
        url: HttpUrl, method: String, headers: Map<String, String>, body: String?, token: String?,
    ): Reply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .method(method, body?.toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                if (token != null) header("Authorization", "Bearer $token")
            }
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

        /** ISO 8601 UTC with an explicit offset, which PostgREST accepts in `gt.` filters and `timestamptz` bodies. */
        private fun timestamp(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now)

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
