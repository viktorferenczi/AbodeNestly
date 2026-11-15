package com.abodenestly.core

import com.abodenestly.contract.models.Household
import com.abodenestly.contract.models.Member
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.Landing
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.settings.CURRENCIES
import com.abodenestly.core.settings.HouseholdSettingsEvent
import com.abodenestly.core.settings.HouseholdSettingsViewModel
import com.abodenestly.core.settings.InMemoryHouseholdCache
import com.abodenestly.core.settings.ModuleKey
import com.abodenestly.core.settings.SettingsLoadState
import com.abodenestly.core.settings.SettingsMode
import com.abodenestly.core.settings.currencyOptions
import com.abodenestly.core.settings.settingsMode
import com.abodenestly.core.settings.withModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** CORE-05 logic: the settings view model's save, load and leave rules, and the pure helpers. */
class HouseholdSettingsTest {
    // Unconfined: launched work runs to its first suspension immediately, so assertions need no scheduler.
    private fun scope() = CoroutineScope(Job() + Dispatchers.Unconfined)

    private val hid = id(1)
    private val me = Fixtures.userId
    private val home = Fixtures.household(1)
    private val api = FakeApiService()
    private val cache = InMemoryHouseholdCache()
    private var online = true
    private val changed = mutableListOf<Household>()
    private var left = 0

    private fun vm() = HouseholdSettingsViewModel(
        hid, me, api, cache, isOnline = { online }, onHouseholdChanged = { changed += it }, onLeft = { left++ }, scope = scope(),
    )

    private suspend fun loaded(role: Member.Role = Member.Role.owner, household: Household = home): HouseholdSettingsViewModel {
        api.fetchHouseholdResult = ApiResult.Success(HouseholdWithRole(household, role))
        return vm().also { it.load() }
    }

    private val modules get() = api.calls.filterIsInstance<Call.UpdateModules>()

    @Test
    fun `CORE-05 - AC3 pantry off sends the PATCH, tells the app and the Pantry tab goes`() = runTest {
        val auth = FakeAuthService(Fixtures.session)
        auth.memberships = Fetch.Success(listOf(Fixtures.membership(1)))
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true }, scope = scope())
        session.start()
        session.awaitLoaded()
        assertTrue(AppTab.Pantry in Landing.visibleTabs(session.state.value.household))

        val vm = HouseholdSettingsViewModel(
            hid, me, api, cache, isOnline = { true }, onHouseholdChanged = { changed += it; session.updateHousehold(it) },
            onLeft = {}, scope = scope(),
        )
        api.fetchHouseholdResult = ApiResult.Success(HouseholdWithRole(home, Member.Role.owner))
        vm.load()
        api.updateModulesResult = ApiResult.Success(home.copy(modulePantry = false))
        vm.toggle(ModuleKey.Pantry, on = false)

        assertEquals(listOf(Call.UpdateModules(hid, null, false, null)), modules)
        assertEquals(listOf(home.copy(modulePantry = false)), changed)
        val tabs = Landing.visibleTabs(session.state.value.household)
        assertEquals(listOf(AppTab.Shopping, AppTab.Maintenance, AppTab.Home), tabs)
        // The destination is untouched, so the shell does not jump to the start tab.
        assertEquals(com.abodenestly.core.auth.Destination.Household(hid, AppTab.Shopping), session.state.value.destination)
    }

    @Test
    fun `updateHousehold ignores another household`() = runTest {
        val auth = FakeAuthService(Fixtures.session)
        auth.memberships = Fetch.Success(listOf(Fixtures.membership(1)))
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true }, scope = scope())
        session.start()
        session.awaitLoaded()
        session.updateHousehold(Fixtures.household(2).copy(modulePantry = false))
        assertTrue(AppTab.Pantry in Landing.visibleTabs(session.state.value.household))
    }

    @Test
    fun `the optimistic value is shown while the save is held`() = runTest {
        val vm = loaded()
        api.updateModulesResult = ApiResult.Success(home.copy(moduleShopping = false))
        api.hold()
        vm.onEvent(HouseholdSettingsEvent.Toggle(ModuleKey.Shopping, false))
        assertTrue(vm.state.value.isSaving)
        assertFalse(vm.state.value.shown!!.moduleShopping)
        assertTrue(vm.state.value.household!!.moduleShopping)
        // A second action while saving is dropped.
        vm.onEvent(HouseholdSettingsEvent.Toggle(ModuleKey.Pantry, false))
        assertEquals(1, modules.size)

        api.release()
        assertNull(vm.state.value.optimistic)
        assertFalse(vm.state.value.isSaving)
        assertFalse(vm.state.value.household!!.moduleShopping)
    }

    @Test
    fun `Forbidden shows the owners-only copy and reloads the role`() = runTest {
        val vm = loaded()
        api.updateModulesResult = ApiResult.Failure(ApiFailure.Forbidden)
        api.fetchHouseholdResult = ApiResult.Success(HouseholdWithRole(home, Member.Role.member))
        vm.toggle(ModuleKey.Pantry, false)

        assertEquals("Only owners can change household settings.", vm.state.value.saveError)
        assertNull(vm.state.value.optimistic)
        assertEquals(Member.Role.member, vm.state.value.role)
        assertEquals(2, api.calls.filterIsInstance<Call.FetchHousehold>().size)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `a network failure says to check the connection and a generic one says try again`() = runTest {
        val vm = loaded()
        api.updateModulesResult = ApiResult.Failure(ApiFailure.Network)
        vm.toggle(ModuleKey.Pantry, false)
        assertEquals("Couldn't save. Check your connection and try again.", vm.state.value.saveError)
        api.updateModulesResult = ApiResult.Failure(ApiFailure.Unknown)
        vm.toggle(ModuleKey.Pantry, false)
        assertEquals("Couldn't save. Try again.", vm.state.value.saveError)
    }

    @Test
    fun `a members, offline or unknown-role viewer cannot save`() = runTest {
        loaded(Member.Role.member).toggle(ModuleKey.Pantry, false)
        val offline = loaded()
        online = false
        offline.toggle(ModuleKey.Pantry, false)
        offline.setCurrency("EUR")
        assertTrue(api.calls.none { it is Call.UpdateModules || it is Call.UpdateHousehold })
    }

    @Test
    fun `a fetch that started before a save does not overwrite it`() = runTest {
        val vm = loaded()
        // The held fetch reads the server's current answer (pantry still on) before the save starts.
        api.updateModulesResult = ApiResult.Success(home.copy(modulePantry = false))
        api.hold()
        val stale = CoroutineScope(Dispatchers.Unconfined).async { vm.load() }
        vm.onEvent(HouseholdSettingsEvent.Toggle(ModuleKey.Pantry, false))
        api.release()
        stale.await()
        assertFalse(vm.state.value.household!!.modulePantry)
        assertFalse(cache.read(me, hid)!!.household.modulePantry)
    }

    @Test
    fun `a fetch that starts while the save is in flight does not bring the old row back`() = runTest {
        val vm = loaded()
        api.updateModulesResult = ApiResult.Success(home.copy(modulePantry = false))
        api.hold()
        vm.onEvent(HouseholdSettingsEvent.Toggle(ModuleKey.Pantry, false))
        // Started after the save began, so it reads the pre-patch row and finishes after the save did.
        val late = CoroutineScope(Dispatchers.Unconfined).async { vm.load() }
        api.release()
        late.await()
        assertFalse(vm.state.value.household!!.modulePantry)
        assertFalse(cache.read(me, hid)!!.household.modulePantry)
    }

    @Test
    fun `a name typed but not committed is saved before the tap that blurred the field`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = HouseholdSettingsViewModel(
            hid, me, api, cache, { online }, { changed += it }, { left++ }, scope(), blurDispatcher = dispatcher,
        )
        api.fetchHouseholdResult = ApiResult.Success(HouseholdWithRole(home, Member.Role.owner))
        vm.load()
        api.updateHouseholdResult = ApiResult.Success(home.copy(name = "Casa"))
        api.updateModulesResult = ApiResult.Success(home.copy(name = "Casa", modulePantry = false))
        vm.onEvent(HouseholdSettingsEvent.NameChanged("Casa"))
        // The blur and the tap arrive on the same turn: the blur commit is deferred, the tap is not.
        vm.onEvent(HouseholdSettingsEvent.CommitNameOnBlur)
        vm.onEvent(HouseholdSettingsEvent.Toggle(ModuleKey.Pantry, false))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf<Call>(Call.FetchHousehold(hid, me), Call.UpdateHousehold(hid, "Casa", null), Call.UpdateModules(hid, null, false, null)),
            api.calls,
        )
        assertNull(vm.state.value.nameDraft)
        assertFalse(vm.state.value.household!!.modulePantry)
    }

    @Test
    fun `a failed name save stops the module change`() = runTest {
        val vm = loaded()
        api.updateHouseholdResult = ApiResult.Failure(ApiFailure.Network)
        vm.onEvent(HouseholdSettingsEvent.NameChanged("Casa"))
        vm.toggle(ModuleKey.Pantry, false)
        assertTrue(modules.isEmpty())
        assertEquals("Couldn't save. Check your connection and try again.", vm.state.value.saveError)
    }

    @Test
    fun `a blank or unchanged name sends nothing and clears the draft`() = runTest {
        val vm = loaded()
        vm.onEvent(HouseholdSettingsEvent.NameChanged("   "))
        assertEquals("   ", vm.state.value.name)
        vm.commitName()
        assertNull(vm.state.value.nameDraft)
        vm.onEvent(HouseholdSettingsEvent.NameChanged(" ${home.name} "))
        vm.commitName()
        assertNull(vm.state.value.nameDraft)
        assertTrue(api.calls.none { it is Call.UpdateHousehold })
    }

    @Test
    fun `a changed name is trimmed and saved without an optimistic value`() = runTest {
        val vm = loaded()
        api.updateHouseholdResult = ApiResult.Success(home.copy(name = "Casa"))
        vm.onEvent(HouseholdSettingsEvent.NameChanged("  Casa "))
        api.hold()
        vm.onEvent(HouseholdSettingsEvent.CommitName)
        assertNull(vm.state.value.optimistic)
        api.release()
        assertEquals(listOf<Call>(Call.FetchHousehold(hid, me), Call.UpdateHousehold(hid, "Casa", null)), api.calls)
        assertEquals("Casa", vm.state.value.name)
        assertNull(vm.state.value.nameDraft)
    }

    @Test
    fun `currency is optimistic and the cache holds the saved household`() = runTest {
        val vm = loaded()
        api.updateHouseholdResult = ApiResult.Success(home.copy(currency = "EUR"))
        api.hold()
        vm.onEvent(HouseholdSettingsEvent.SetCurrency("EUR"))
        assertEquals("EUR", vm.state.value.shown!!.currency)
        api.release()
        assertEquals("EUR", cache.read(me, hid)!!.household.currency)
        assertEquals(Call.UpdateHousehold(hid, null, "EUR"), api.calls.last())
    }

    @Test
    fun `the cache is not written when the role is unknown`() = runTest {
        // Only reachable if a save lands before any load did; the snapshot would be unreadable offline.
        val vm = vm()
        api.updateModulesResult = ApiResult.Success(home)
        vm.toggle(ModuleKey.Pantry, false)
        assertNull(cache.read(me, hid))
        assertTrue(modules.isEmpty())
    }

    @Test
    fun `load shows the cached copy offline and unavailable when there is none`() = runTest {
        cache.write(me, HouseholdWithRole(home, Member.Role.member))
        online = false
        val vm = vm()
        vm.load()
        assertEquals(SettingsLoadState.Loaded, vm.state.value.loadState)
        assertEquals(Member.Role.member, vm.state.value.role)
        assertTrue(api.calls.isEmpty())

        val empty = HouseholdSettingsViewModel(id(9), me, api, cache, { false }, {}, {}, scope())
        empty.load()
        assertEquals(SettingsLoadState.Unavailable, empty.state.value.loadState)
    }

    @Test
    fun `no row clears the cache and marks the screen unavailable`() = runTest {
        cache.write(me, HouseholdWithRole(home, Member.Role.owner))
        api.fetchHouseholdResult = ApiResult.Success(null)
        val vm = vm()
        vm.load()
        assertEquals(SettingsLoadState.Unavailable, vm.state.value.loadState)
        assertNull(vm.state.value.household)
        assertNull(cache.read(me, hid))
    }

    @Test
    fun `the cache is keyed by user`() = runTest {
        cache.write(me, HouseholdWithRole(home, Member.Role.owner))
        assertNull(cache.read(id(77), hid))
    }

    @Test
    fun `leave removes the viewer, clears the cache and calls onLeft`() = runTest {
        val vm = loaded()
        vm.onEvent(HouseholdSettingsEvent.BeginLeave)
        assertTrue(vm.state.value.confirmingLeave)
        api.removeResult = ApiResult.Success(Unit)
        vm.leave()
        assertEquals(Call.RemoveMember(hid, me), api.calls.last())
        assertEquals(1, left)
        assertNull(cache.read(me, hid))
        assertFalse(vm.state.value.confirmingLeave)
    }

    @Test
    fun `leave treats NotFound as done`() = runTest {
        val vm = loaded()
        api.removeResult = ApiResult.Failure(ApiFailure.NotFound)
        vm.leave()
        assertEquals(1, left)
    }

    @Test
    fun `last_owner and other leave errors show inside the sheet`() = runTest {
        val vm = loaded()
        vm.onEvent(HouseholdSettingsEvent.BeginLeave)
        api.removeResult = ApiResult.Failure(ApiFailure.LastOwner)
        vm.leave()
        assertEquals("You're the last owner. Make someone else an owner before leaving.", vm.state.value.leaveError)
        assertNull(vm.state.value.saveError)
        api.removeResult = ApiResult.Failure(ApiFailure.Unknown)
        vm.leave()
        assertEquals("Couldn't leave the household. Try again.", vm.state.value.leaveError)
        assertEquals(0, left)
        assertTrue(vm.state.value.confirmingLeave)
    }

    @Test
    fun `Cancel is ignored while leaving`() = runTest {
        val vm = loaded()
        vm.onEvent(HouseholdSettingsEvent.BeginLeave)
        api.hold()
        vm.onEvent(HouseholdSettingsEvent.ConfirmLeave)
        assertTrue(vm.state.value.isLeaving)
        vm.onEvent(HouseholdSettingsEvent.CancelLeave)
        assertTrue(vm.state.value.confirmingLeave)
        api.release()
    }

    @Test
    fun `settingsMode is offline first, then owner, then member`() {
        val cases = listOf(
            Triple(Member.Role.owner, true, SettingsMode.Owner),
            Triple(Member.Role.owner, false, SettingsMode.Offline),
            Triple(Member.Role.member, true, SettingsMode.Member),
            Triple(Member.Role.member, false, SettingsMode.Offline),
            Triple(null, true, SettingsMode.Member),
            Triple(null, false, SettingsMode.Offline),
        )
        cases.forEach { (role, online, expected) -> assertEquals(expected, settingsMode(role, online), "$role online=$online") }
    }

    @Test
    fun `withModule changes only the named flag`() {
        val off = withModule(home, ModuleKey.Maintenance, false)
        assertEquals(home.copy(moduleMaintenance = false), off)
    }

    @Test
    fun `currencyOptions keeps the list and inserts an unknown current value in order`() {
        assertEquals(13, CURRENCIES.size)
        assertEquals(CURRENCIES, currencyOptions("EUR"))
        val withBrl = currencyOptions("BRL")
        assertEquals(14, withBrl.size)
        assertEquals(withBrl.sorted(), withBrl)
        assertTrue("BRL" in withBrl)
    }
}
