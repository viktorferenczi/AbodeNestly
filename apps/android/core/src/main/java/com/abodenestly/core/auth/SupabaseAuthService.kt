package com.abodenestly.core.auth

import com.abodenestly.contract.infrastructure.Serializer
import com.abodenestly.contract.models.Profile
import com.abodenestly.shared.AppConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

/**
 * supabase-kt `Auth` (PKCE, session persisted by the SDK) for sign-in, plus OkHttp reads of profile and
 * membership rows with the user's JWT (the SDK is limited to Auth/Realtime, SPEC §3).
 */
class SupabaseAuthService(private val config: AppConfig) : AuthService {
    private val http = OkHttpClient()
    private val client: SupabaseClient = createSupabaseClient(config.supabaseUrl, config.supabaseAnonKey) {
        install(Auth) {
            flowType = FlowType.PKCE
            scheme = config.authScheme
            host = AppConfig.AUTH_HOST
        }
    }
    private val auth get() = client.auth

    // MARK: Session

    override suspend fun currentSession(): AuthSession? {
        auth.awaitInitialization()
        return auth.currentSessionOrNull()?.toAuthSession()
    }

    override val sessionChanges: Flow<AuthSession?>
        get() = auth.sessionStatus.transform { status ->
            when (status) {
                is SessionStatus.Authenticated -> emit(status.session.toAuthSession())
                is SessionStatus.NotAuthenticated -> emit(null)
                // An expired token that cannot refresh (offline) must not read as signed out (SPEC §6.5).
                is SessionStatus.RefreshFailure -> emit(auth.currentSessionOrNull()?.toAuthSession())
                is SessionStatus.Initializing -> Unit
            }
        }.distinctUntilChanged()

    override suspend fun handle(url: String) {
        val code = AuthCallback.code(url, config.authScheme) ?: return
        // A bad or expired link leaves the person signed out on the sign-in screen, where they can ask for a new one.
        try {
            auth.exchangeCodeForSession(code)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    override suspend fun signOut() {
        // Local scope: the device forgets the session even when offline.
        try {
            auth.signOut(SignOutScope.LOCAL)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // MARK: Sign in

    override suspend fun sendMagicLink(email: String, redirectTo: String) = mapping {
        auth.signInWith(OTP, redirectUrl = redirectTo) { this.email = email }
    }

    override suspend fun signInWithApple() = mapping {
        auth.signInWith(Apple, redirectUrl = config.authCallbackUrl)
    }

    override suspend fun signInWithGoogle() = mapping {
        auth.signInWith(Google, redirectUrl = config.authCallbackUrl)
    }

    private suspend fun mapping(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AuthFailure(map(e))
        }
    }

    // MARK: Reads (PostgREST with the user JWT, RLS applies)

    override suspend fun fetchProfile(userId: UUID): Fetch<Profile?> = read {
        val body = get("profile", "select" to "*", "id" to "eq.$userId")
        Serializer.kotlinxSerializationJson.decodeFromString(ListSerializer(Profile.serializer()), body).firstOrNull()
    }

    override suspend fun fetchMemberships(userId: UUID): Fetch<List<Membership>> = read {
        val body = get(
            "member",
            "select" to "household_id,joined_at,household(id,module_shopping,module_pantry,module_maintenance,deleted_at)",
            "user_id" to "eq.$userId",
            "left_at" to "is.null",
        )
        Serializer.kotlinxSerializationJson.decodeFromString(ListSerializer(MemberRow.serializer()), body)
            .mapNotNull { it.toMembership() }
    }

    private suspend fun <T> read(block: suspend () -> T): Fetch<T> = try {
        Fetch.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Fetch.Failure(map(e))
    }

    private suspend fun get(table: String, vararg query: Pair<String, String>): String {
        val first = request(table, query, auth.currentAccessTokenOrNull())
        if (first.code != 401) return first.require()
        // The stored access token expired; refresh once and retry.
        auth.refreshCurrentSession()
        return request(table, query, auth.currentAccessTokenOrNull()).require()
    }

    private class Reply(val code: Int, val body: String) {
        fun require(): String = if (code in 200..299) body else throw IOException("HTTP $code")
    }

    private suspend fun request(table: String, query: Array<out Pair<String, String>>, token: String?): Reply =
        withContext(Dispatchers.IO) {
            val url = config.supabaseUrl.trimEnd('/').plus("/rest/v1/$table").toHttpUrl().newBuilder()
                .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token ?: config.supabaseAnonKey}")
                .header("apikey", config.supabaseAnonKey)
                .header("Accept", "application/json")
                .build()
            http.newCall(request).execute().use { Reply(it.code, it.body?.string().orEmpty()) }
        }

    // MARK: Helpers

    @Serializable
    private data class MemberRow(
        @Contextual @SerialName("household_id") val householdId: UUID,
        @Contextual @SerialName("joined_at") val joinedAt: OffsetDateTime,
        val household: HouseholdRow? = null,
    ) {
        @Serializable
        data class HouseholdRow(
            @Contextual val id: UUID,
            @SerialName("module_shopping") val moduleShopping: Boolean,
            @SerialName("module_pantry") val modulePantry: Boolean,
            @SerialName("module_maintenance") val moduleMaintenance: Boolean,
            @Contextual @SerialName("deleted_at") val deletedAt: OffsetDateTime? = null,
        )

        fun toMembership(): Membership? {
            val h = household?.takeIf { it.deletedAt == null } ?: return null
            return Membership(
                householdId, joinedAt.toInstant(),
                LandingHousehold(h.id, h.moduleShopping, h.modulePantry, h.moduleMaintenance),
            )
        }
    }

    private fun UserSession.toAuthSession(): AuthSession? =
        user?.let { AuthSession(UUID.fromString(it.id), it.email) }

    companion object {
        /** Mirrors web `toAuthError` and iOS `map(_:)`. */
        fun map(error: Throwable): AuthError = when {
            error is AuthRestException -> mapRest(error.statusCode, error.errorCode?.value)
            error is IOException || error.cause is IOException -> AuthError.Network
            error.javaClass.simpleName.contains("HttpRequest") -> AuthError.Network
            else -> AuthError.Unknown
        }

        internal fun mapRest(statusCode: Int, code: String?): AuthError = when {
            statusCode == 429 || code in RATE_LIMIT_CODES -> AuthError.RateLimited
            code == "email_address_invalid" || code == "validation_failed" -> AuthError.InvalidEmail
            else -> AuthError.Unknown
        }

        private val RATE_LIMIT_CODES = setOf("over_email_send_rate_limit", "over_request_rate_limit", "over_sms_send_rate_limit")
    }
}

internal object AuthCallback {
    /** The `code` of `<scheme>://auth/callback?code=…`; any other URL the app is opened with yields `null`. */
    fun code(url: String, scheme: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != scheme || uri.host != AppConfig.AUTH_HOST || uri.path != AppConfig.AUTH_PATH) return null
        return uri.rawQuery?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == "code" }
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
    }
}
