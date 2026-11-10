package com.abodenestly.core

import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.membership
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.AuthCallback
import com.abodenestly.core.auth.AuthError
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.Landing
import com.abodenestly.core.auth.AuthSession
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.emitSessionFor
import io.github.jan.supabase.auth.status.RefreshFailureCause
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SupabaseAuthService
import com.abodenestly.core.signin.SignInState
import com.abodenestly.core.signin.SignInViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SignInTest {
    // ---- Landing table (CORE-01 AC4/AC5) ----

    @Test
    fun `no household lands on Welcome`() {
        assertEquals(Destination.Welcome, Landing.destination(emptyList(), null))
    }

    @Test
    fun `one household lands on its start tab`() {
        val cases = listOf(
            Triple(true, true, true) to AppTab.Shopping,
            Triple(false, true, true) to AppTab.Pantry,
            Triple(false, false, true) to AppTab.Maintenance,
        )
        for ((flags, tab) in cases) {
            val m = membership(1, shopping = flags.first, pantry = flags.second, maintenance = flags.third)
            assertEquals(Destination.Household(id(1), tab), Landing.destination(listOf(m), null), "flags $flags")
        }
    }

    @Test
    fun `last used household wins over the earliest joined`() {
        val early = membership(1, joinedDay = 1)
        val late = membership(2, joinedDay = 2, shopping = false)
        assertEquals(Destination.Household(id(1), AppTab.Shopping), Landing.destination(listOf(late, early), null))
        assertEquals(Destination.Household(id(2), AppTab.Pantry), Landing.destination(listOf(late, early), id(2)))
    }

    @Test
    fun `an unknown last household falls back to the earliest`() {
        val list = listOf(membership(2, joinedDay = 5), membership(1, joinedDay = 1))
        assertEquals(id(1), Landing.pickHousehold(list, id(99))?.householdId)
    }

    @Test
    fun `all modules off lands on Home (module-off matrix)`() {
        val m = membership(1, shopping = false, pantry = false, maintenance = false)
        assertEquals(Destination.Household(id(1), AppTab.Home), Landing.destination(listOf(m), null))
    }

    // ---- SessionViewModel ----

    private fun TestScope.session(
        auth: FakeAuthService,
        online: Boolean = true,
        store: InMemoryLastHouseholdStore = InMemoryLastHouseholdStore(),
    ) = SessionViewModel(auth, store, isOnline = { online }, scope = backgroundScope)

    @Test
    fun `profile row exposes the display name (AC8)`() = runTest {
        val auth = FakeAuthService(Fixtures.session).apply { profile = Fetch.Success(Fixtures.profile("Ada")) }
        val vm = session(auth)
        vm.start(); runCurrent()
        assertEquals("Ada", vm.state.value.profile?.displayName)
        assertEquals(SessionStatus.SignedIn, vm.state.value.status)
    }

    @Test
    fun `missing profile row leaves the name empty`() = runTest {
        val auth = FakeAuthService(Fixtures.session).apply { memberships = Fetch.Success(listOf(membership(1))) }
        val vm = session(auth)
        vm.start(); runCurrent()
        assertNull(vm.state.value.profile)
        assertEquals(Destination.Household(id(1), AppTab.Shopping), vm.state.value.destination)
    }

    @Test
    fun `no stored session is signed out`() = runTest {
        val vm = session(FakeAuthService())
        vm.start(); runCurrent()
        assertEquals(SessionStatus.SignedOut, vm.state.value.status)
    }

    @Test
    fun `offline membership failure enters at Home`() = runTest {
        val auth = FakeAuthService(Fixtures.session).apply { memberships = Fetch.Failure(AuthError.Network) }
        val vm = session(auth, online = false)
        vm.start(); runCurrent()
        assertEquals(Destination.Home, vm.state.value.destination)
        assertEquals(false, vm.state.value.loadFailed)
    }

    @Test
    fun `online membership failure is a blocking error and retry recovers`() = runTest {
        val auth = FakeAuthService(Fixtures.session).apply { memberships = Fetch.Failure(AuthError.Unknown) }
        val vm = session(auth)
        vm.start(); runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertNull(vm.state.value.destination)

        auth.memberships = Fetch.Success(emptyList())
        vm.retry(); runCurrent()
        assertEquals(Destination.Welcome, vm.state.value.destination)
        assertEquals(false, vm.state.value.loadFailed)
    }

    @Test
    fun `token refresh re-emit does not reload`() = runTest {
        val auth = FakeAuthService(Fixtures.session).apply { memberships = Fetch.Success(listOf(membership(1))) }
        val vm = session(auth)
        vm.start(); runCurrent()
        auth.emit(Fixtures.session.copy(email = "ada@example.com")); runCurrent()
        assertEquals(1, auth.membershipFetches.get())
        assertEquals(1, auth.profileFetches.get())
        assertEquals(Destination.Household(id(1), AppTab.Shopping), vm.state.value.destination)
    }

    @Test
    fun `landing remembers the household it chose`() = runTest {
        val store = InMemoryLastHouseholdStore()
        val auth = FakeAuthService(Fixtures.session).apply { memberships = Fetch.Success(listOf(membership(3, joinedDay = 1))) }
        val vm = session(auth, store = store)
        vm.start(); runCurrent()
        assertEquals(id(3), store.read())
    }

    @Test
    fun `sign out returns to signed out`() = runTest {
        val auth = FakeAuthService(Fixtures.session)
        val vm = session(auth)
        vm.start(); runCurrent()
        vm.signOut(); runCurrent()
        assertEquals(SessionStatus.SignedOut, vm.state.value.status)
        assertEquals(listOf<FakeAuthService.Call>(FakeAuthService.Call.SignOut), auth.calls)
    }

    // ---- SignInViewModel ----

    private val redirect = "abodenestly-dev://auth/callback"

    @Test
    fun `invalid email sets an error and sends nothing (AC3)`() = runTest {
        val auth = FakeAuthService()
        val vm = SignInViewModel(auth, redirect, scope = backgroundScope)
        vm.onEmailChange("not-an-email"); vm.submit(); runCurrent()
        assertEquals(SignInState.Error(AuthError.InvalidEmail), vm.state.value)
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun `resend countdown runs 30 seconds then enables`() = runTest {
        val auth = FakeAuthService()
        val vm = SignInViewModel(auth, redirect, scope = backgroundScope)
        vm.onEmailChange(" ada@example.com "); vm.submit(); runCurrent()
        assertEquals(SignInState.LinkSent(30, resending = false, error = null), vm.state.value)
        assertEquals(listOf<FakeAuthService.Call>(FakeAuthService.Call.SendMagicLink("ada@example.com", redirect)), auth.calls)
        advanceTimeBy(29_001)
        assertEquals(1, (vm.state.value as SignInState.LinkSent).secondsLeft)
        advanceTimeBy(1_000)
        assertEquals(0, (vm.state.value as SignInState.LinkSent).secondsLeft)
        vm.resend(); runCurrent()
        assertEquals(2, auth.calls.size)
        assertEquals(30, (vm.state.value as SignInState.LinkSent).secondsLeft)
    }

    @Test
    fun `cancelled provider returns to idle`() = runTest {
        val auth = FakeAuthService().apply { googleError = AuthError.Cancelled }
        val vm = SignInViewModel(auth, redirect, scope = backgroundScope)
        vm.signInWithGoogle(); runCurrent()
        assertEquals(SignInState.Idle, vm.state.value)
        assertNull(vm.action.value)
    }

    // ---- Callback parsing and error mapping ----

    @Test
    fun `callback code is read only from the app's own callback URL`() {
        assertEquals("abc123", AuthCallback.code("abodenestly-dev://auth/callback?code=abc123", "abodenestly-dev"))
        assertNull(AuthCallback.code("abodenestly-dev://auth/callback", "abodenestly-dev"))
        assertNull(AuthCallback.code("abodenestly://auth/callback?code=x", "abodenestly-dev"))
        assertNull(AuthCallback.code("abodenestly-dev://other/callback?code=x", "abodenestly-dev"))
        assertNull(AuthCallback.code("abodenestly-dev://auth/elsewhere?code=x", "abodenestly-dev"))
        assertNull(AuthCallback.code("not a url", "abodenestly-dev"))
    }

    @Test
    fun `errors map like web and iOS`() {
        assertEquals(AuthError.RateLimited, SupabaseAuthService.mapRest(429, null))
        assertEquals(AuthError.RateLimited, SupabaseAuthService.mapRest(400, "over_email_send_rate_limit"))
        assertEquals(AuthError.InvalidEmail, SupabaseAuthService.mapRest(400, "validation_failed"))
        assertEquals(AuthError.InvalidEmail, SupabaseAuthService.mapRest(400, "email_address_invalid"))
        assertEquals(AuthError.Unknown, SupabaseAuthService.mapRest(500, null))
        assertEquals(AuthError.Network, SupabaseAuthService.map(IOException("offline")))
        assertEquals(AuthError.Unknown, SupabaseAuthService.map(IllegalStateException()))
    }

    // ---- Status mapping (SPEC §6.5) ----

    private suspend fun changes(status: io.github.jan.supabase.auth.status.SessionStatus, stored: AuthSession?) =
        flow { emitSessionFor(status) { stored } }.toList()

    @Test
    fun `refresh failure keeps the stored session instead of signing out`() = runTest {
        val failure = io.github.jan.supabase.auth.status.SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(IOException("offline")))
        assertEquals(listOf<AuthSession?>(Fixtures.session), changes(failure, Fixtures.session))
    }

    @Test
    fun `not authenticated signs out and initializing emits nothing`() = runTest {
        assertEquals(listOf<AuthSession?>(null), changes(io.github.jan.supabase.auth.status.SessionStatus.NotAuthenticated(isSignOut = true), Fixtures.session))
        assertEquals(emptyList<AuthSession?>(), changes(io.github.jan.supabase.auth.status.SessionStatus.Initializing, Fixtures.session))
    }
}
