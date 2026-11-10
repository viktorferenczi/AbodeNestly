package com.abodenestly.core.auth

import com.abodenestly.contract.models.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hand-written fake for component and unit tests (TESTING §2: no mocking libraries).
 * Lives in the main source set, like iOS `FakeAuthService`, so `:app` tests can use it too.
 */
class FakeAuthService(session: AuthSession? = null) : AuthService {
    sealed interface Call {
        data class SendMagicLink(val email: String, val redirectTo: String) : Call
        data object SignInWithApple : Call
        data object SignInWithGoogle : Call
        data class Handle(val url: String) : Call
        data object SignOut : Call
    }

    @Volatile private var session: AuthSession? = session
    private val recorded = CopyOnWriteArrayList<Call>()
    private val changes = MutableSharedFlow<AuthSession?>(extraBufferCapacity = 16)

    @Volatile var sendMagicLinkError: AuthError? = null
    @Volatile var appleError: AuthError? = null
    @Volatile var googleError: AuthError? = null
    @Volatile var profile: Fetch<Profile?> = Fetch.Success(null)
    @Volatile var memberships: Fetch<List<Membership>> = Fetch.Success(emptyList())

    @Volatile private var gate: CompletableDeferred<Unit>? = null

    val calls: List<Call> get() = recorded.toList()

    /** How many times each read ran, so tests can assert a token refresh does not reload. */
    val profileFetches = AtomicInteger()
    val membershipFetches = AtomicInteger()

    /** Pushes a session change to every [sessionChanges] collector. */
    fun emit(session: AuthSession?) {
        this.session = session
        changes.tryEmit(session)
    }

    /** Makes the sign-in calls suspend until [releaseSignIn], so tests can observe the `sending` state. */
    fun holdSignIn() {
        gate = CompletableDeferred()
    }

    fun releaseSignIn() {
        gate?.complete(Unit)
        gate = null
    }

    private suspend fun suspendIfHeld() {
        gate?.await()
    }

    override suspend fun currentSession(): AuthSession? = session
    override val sessionChanges: Flow<AuthSession?> get() = changes

    override suspend fun sendMagicLink(email: String, redirectTo: String) {
        recorded += Call.SendMagicLink(email, redirectTo)
        suspendIfHeld()
        sendMagicLinkError?.let { throw AuthFailure(it) }
    }

    override suspend fun signInWithApple() {
        recorded += Call.SignInWithApple
        suspendIfHeld()
        appleError?.let { throw AuthFailure(it) }
    }

    override suspend fun signInWithGoogle() {
        recorded += Call.SignInWithGoogle
        suspendIfHeld()
        googleError?.let { throw AuthFailure(it) }
    }

    override suspend fun handle(url: String) {
        recorded += Call.Handle(url)
    }

    override suspend fun signOut() {
        recorded += Call.SignOut
        emit(null)
    }

    override suspend fun fetchProfile(userId: UUID): Fetch<Profile?> {
        profileFetches.incrementAndGet()
        return profile
    }

    override suspend fun fetchMemberships(userId: UUID): Fetch<List<Membership>> {
        membershipFetches.incrementAndGet()
        return memberships
    }
}
