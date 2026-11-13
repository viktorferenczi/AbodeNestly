package com.abodenestly.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.membership
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.DataStoreProfileCache
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.AuthError
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.InMemoryProfileCache
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.LastHouseholdStore
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.welcome.WelcomeOutcome
import com.abodenestly.core.welcome.WelcomeState
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.core.welcome.land
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/** CORE-02 logic: Welcome view model, session entry points, profile cache and the accept-invite error table. */
class WelcomeTest {
    // Unconfined: launched work runs to its first suspension immediately, so assertions need no scheduler.
    private fun scope() = CoroutineScope(Job() + Dispatchers.Unconfined)

    private val api = FakeApiService()
    private val outcomes = mutableListOf<WelcomeOutcome>()
    private fun welcome() = WelcomeViewModel(api, onLanded = { outcomes += it }, scope = scope())

    private fun session(
        auth: FakeAuthService = FakeAuthService(Fixtures.session),
        lastHousehold: InMemoryLastHouseholdStore = InMemoryLastHouseholdStore(),
        cache: InMemoryProfileCache = InMemoryProfileCache(),
    ) = SessionViewModel(auth, lastHousehold, isOnline = { true }, profileCache = cache, scope = scope())

    // ---- AC2: create ----

    @Test
    fun `AC2 create success reports Created and the household opens on Home`() {
        val household = Fixtures.household(1)
        api.createResult = ApiResult.Success(household)
        val vm = welcome()
        vm.onNameChange("  Test Home  ")
        vm.create()

        assertEquals(listOf<Call>(Call.CreateHousehold("Test Home")), api.calls)
        assertEquals(listOf<WelcomeOutcome>(WelcomeOutcome.Created(household)), outcomes)
        assertEquals(WelcomeState.Idle, vm.state.value)
        assertEquals(Destination.Household(household.id, AppTab.Home), WelcomeViewModel.landing(household))
    }

    @Test
    fun `AC2 session enter lands on that household's Home whatever its modules`() {
        val last = InMemoryLastHouseholdStore()
        val session = session(lastHousehold = last)
        val household = LandingHousehold(id(1), moduleShopping = true, modulePantry = true, moduleMaintenance = true)
        session.enter(household)

        assertEquals(WelcomeViewModel.landing(Fixtures.household(1)), session.state.value.destination)
        assertEquals(household, session.state.value.household)
        assertEquals(id(1), runBlocking { last.read() })
    }

    @Test
    fun `AC2 enter publishes Home before the last-household write returns`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : LastHouseholdStore {
            override suspend fun read(): UUID? = null
            override suspend fun write(id: UUID) = gate.await()
        }
        val session = SessionViewModel(FakeAuthService(Fixtures.session), slow, isOnline = { true }, scope = scope())
        session.enter(LandingHousehold(id(1), true, true, true))
        assertEquals(Destination.Household(id(1), AppTab.Home), session.state.value.destination)
        gate.complete(Unit)
    }

    @Test
    fun `a sign-out during the write is not overwritten`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : LastHouseholdStore {
            override suspend fun read(): UUID? = null
            override suspend fun write(id: UUID) = gate.await()
        }
        val auth = FakeAuthService(Fixtures.session)
        val session = SessionViewModel(auth, slow, isOnline = { true }, scope = scope())
        session.start()
        session.enter(LandingHousehold(id(1), true, true, true))
        auth.emit(null)
        gate.complete(Unit)
        assertEquals(SessionStatus.SignedOut, session.state.value.status)
        assertNull(session.state.value.destination)
    }

    @Test
    fun `AC2 land hands a Created outcome to the session`() {
        val session = session()
        session.land(WelcomeOutcome.Created(Fixtures.household(3)))
        assertEquals(Destination.Household(id(3), AppTab.Home), session.state.value.destination)
    }

    // ---- AC3: failure keeps the typed name ----

    @Test
    fun `AC3 create failure keeps the typed name and reports the failure`() {
        for (failure in listOf(ApiFailure.Network, ApiFailure.Unknown, ApiFailure.Validation)) {
            api.createResult = ApiResult.Failure(failure)
            val vm = welcome()
            vm.onNameChange("Test Home")
            vm.create()
            assertEquals(WelcomeState.CreateError(failure), vm.state.value)
            assertEquals("Test Home", vm.name.value)
        }
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `an empty name is a validation error and calls nothing`() {
        val vm = welcome()
        vm.onNameChange("   ")
        vm.create()
        assertEquals(WelcomeState.CreateError(ApiFailure.Validation), vm.state.value)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an empty code is a validation error and calls nothing`() {
        val vm = welcome()
        vm.join()
        assertEquals(WelcomeState.JoinError(ApiFailure.Validation), vm.state.value)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a second tap while creating is ignored`() {
        api.hold()
        val vm = welcome()
        vm.onNameChange("Test Home")
        vm.create()
        vm.create()
        vm.onCodeChange("ABC")
        vm.join()
        assertEquals(WelcomeState.Creating, vm.state.value)
        assertEquals(1, api.calls.size)
        api.release()
    }

    // ---- AC5: join ----

    @Test
    fun `AC5 an invalid code shows error-invalid-code and keeps the code`() {
        api.acceptResult = ApiResult.Failure(ApiFailure.InvalidCode)
        val vm = welcome()
        vm.onCodeChange(" ABC123 ")
        vm.join()
        assertEquals(listOf<Call>(Call.AcceptInvite("abc123")), api.calls)
        assertEquals(WelcomeState.ErrorInvalidCode, vm.state.value)
        assertEquals(" ABC123 ", vm.code.value)
    }

    @Test
    fun `a network failure while joining is a join error`() {
        api.acceptResult = ApiResult.Failure(ApiFailure.Network)
        val vm = welcome()
        vm.onCodeChange("ABC123")
        vm.join()
        assertEquals(WelcomeState.JoinError(ApiFailure.Network), vm.state.value)
    }

    @Test
    fun `joined reports the household and enterJoined lands on its Home`() {
        api.acceptResult = ApiResult.Success(id(2))
        val vm = welcome()
        vm.onCodeChange("ABC123")
        vm.join()
        assertEquals(listOf<WelcomeOutcome>(WelcomeOutcome.Joined(id(2))), outcomes)

        // Joined household has Shopping on, which would normally land on Shopping; joining opens Home instead.
        val auth = FakeAuthService(Fixtures.session)
        auth.memberships = Fetch.Success(listOf(membership(1, joinedDay = 1)))
        val last = InMemoryLastHouseholdStore()
        val session = session(auth, last)
        session.start()
        runBlocking { session.awaitLoaded() }
        assertEquals(Destination.Household(id(1), AppTab.Shopping), session.state.value.destination)

        auth.memberships = Fetch.Success(listOf(membership(1, joinedDay = 1), membership(2, joinedDay = 2)))
        session.land(outcomes.single())
        runBlocking { session.awaitLoaded() }
        assertEquals(Destination.Household(id(2), AppTab.Home), session.state.value.destination)
        assertEquals(id(2), runBlocking { last.read() })
    }

    // ---- Offline behaviour: profile cache ----

    @Test
    fun `a failed profile read falls back to the cached profile`() {
        val cache = InMemoryProfileCache()
        val first = FakeAuthService(Fixtures.session).apply { profile = Fetch.Success(Fixtures.profile("Ada")) }
        session(first, cache = cache).also { it.start(); runBlocking { it.awaitLoaded() } }

        val offline = FakeAuthService(Fixtures.session).apply { profile = Fetch.Failure(AuthError.Network) }
        val vm = session(offline, cache = cache)
        vm.start()
        runBlocking { vm.awaitLoaded() }
        assertEquals("Ada", vm.state.value.profile?.displayName)
    }

    @Test
    fun `a failed profile read without a cache leaves the name empty`() {
        val auth = FakeAuthService(Fixtures.session).apply { profile = Fetch.Failure(AuthError.Network) }
        val vm = session(auth)
        vm.start()
        runBlocking { vm.awaitLoaded() }
        assertNull(vm.state.value.profile)
    }

    @Test
    fun `the DataStore profile cache round-trips a profile per user`(@TempDir dir: File) = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Job() + Dispatchers.IO)) { File(dir, "profile.preferences_pb") }
        val cache = DataStoreProfileCache(store)
        val profile = Fixtures.profile("Ada")
        assertNull(cache.read(Fixtures.userId))
        cache.write(Fixtures.userId, profile)
        assertEquals(profile, cache.read(Fixtures.userId))
        assertNull(cache.read(UUID.randomUUID()))
    }

    // ---- Accept-invite error table (mirrors iOS `acceptFailure`) ----

    @Test
    fun `acceptFailure maps codes and statuses like iOS`() {
        val table = listOf(
            Triple(400, "invalid_invite", ApiFailure.InvalidCode),
            Triple(404, "not_found", ApiFailure.InvalidCode),
            Triple(410, "gone", ApiFailure.InvalidCode),
            Triple(400, "validation", ApiFailure.Unknown),
            Triple(500, "internal", ApiFailure.Unknown),
            // A known status does not override an unrelated code.
            Triple(404, "validation", ApiFailure.Unknown),
            Triple(404, null, ApiFailure.InvalidCode),
            Triple(410, null, ApiFailure.InvalidCode),
            Triple(500, null, ApiFailure.Unknown),
            Triple(400, null, ApiFailure.Unknown),
        )
        for ((status, code, expected) in table) {
            assertEquals(expected, HttpApiService.acceptFailure(status, code), "status $status code $code")
        }
    }
}
