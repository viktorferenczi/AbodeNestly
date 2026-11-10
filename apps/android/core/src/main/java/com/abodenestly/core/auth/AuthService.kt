package com.abodenestly.core.auth

import com.abodenestly.contract.models.Profile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** The slice of a session the app reads. Keeps Supabase types out of view models and tests. */
data class AuthSession(val userId: UUID, val email: String?)

sealed class AuthError {
    data object InvalidEmail : AuthError()
    data object RateLimited : AuthError()
    data object Network : AuthError()

    /** The person closed the browser before finishing. Not shown as an error. */
    data object Cancelled : AuthError()
    data object Unknown : AuthError()
}

/** Thrown by the sign-in calls; carries the typed error (AGENTS §4: errors are values with a code). */
class AuthFailure(val error: AuthError) : Exception(error.toString())

/** Outcome of a read that can fail with a typed [AuthError]. */
sealed interface Fetch<out T> {
    data class Success<T>(val value: T) : Fetch<T>
    data class Failure(val error: AuthError) : Fetch<Nothing>
}

/**
 * The calls this app makes to auth and profile storage; mirrors iOS `AuthService` and web `AuthClient`.
 * URLs are plain strings so view models and tests stay free of `android.net.Uri`.
 */
interface AuthService {
    /** The stored session, even if its access token has expired (an existing session stays usable offline). */
    suspend fun currentSession(): AuthSession?
    val sessionChanges: Flow<AuthSession?>

    /** @throws AuthFailure */
    suspend fun sendMagicLink(email: String, redirectTo: String)

    /** Opens the system browser; the session arrives later through [handle]. @throws AuthFailure */
    suspend fun signInWithApple()

    /** Opens the system browser; the session arrives later through [handle]. @throws AuthFailure */
    suspend fun signInWithGoogle()

    /** Completes a sign-in from the `<scheme>://auth/callback?code=…` URL. Anything else is ignored. */
    suspend fun handle(url: String)
    suspend fun signOut()

    /** `Success(null)`: the server trigger has not created the row yet. */
    suspend fun fetchProfile(userId: UUID): Fetch<Profile?>
    suspend fun fetchMemberships(userId: UUID): Fetch<List<Membership>>
}
