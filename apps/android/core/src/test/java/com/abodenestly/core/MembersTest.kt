package com.abodenestly.core

import com.abodenestly.contract.models.Member
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.member
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.api.MemberRow
import com.abodenestly.core.members.InMemoryMembersCache
import com.abodenestly.core.members.MembersLoadState
import com.abodenestly.core.members.MembersViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** CORE-04 logic: the members view model's undo window, leave and make-owner rules, and the pure helpers. */
@OptIn(ExperimentalCoroutinesApi::class)
class MembersTest {
    private val household = id(1)
    private val me = id(10)
    private val owner = member(10, "Ada", Member.Role.owner)
    private val bob = member(11, "Bob")
    private val api = FakeApiService()
    private val cache = InMemoryMembersCache()
    private var online = true
    private var left = 0

    private fun TestScope.vm(
        service: ApiService = api,
        undoMillis: Long = 5_000,
    ) = MembersViewModel(
        household, me, service, cache, isOnline = { online }, onLeft = { left++ },
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), undoMillis = undoMillis,
    )

    private suspend fun MembersViewModel.loaded(vararg rows: MemberRow): MembersViewModel {
        api.membersResult = ApiResult.Success(rows.toList())
        load()
        return this
    }

    private val removes get() = api.calls.filterIsInstance<Call.RemoveMember>()

    @Test
    fun `CORE-04 - AC3 undo within 5 s sends no DELETE and the row stays`() = runTest {
        val vm = vm().loaded(owner, bob)
        vm.remove(bob)
        assertEquals("Removed Bob", vm.state.value.toast)
        assertFalse(vm.state.value.visibleRows.any { it.id == bob.id })
        advanceTimeBy(3_000); runCurrent()
        vm.undo()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(removes.isEmpty())
        assertTrue(vm.state.value.visibleRows.any { it.id == bob.id })
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `CORE-04 - AC4 the only owner leaving makes no call and sets the alert`() = runTest {
        val vm = vm().loaded(owner, bob)
        vm.leave()
        assertEquals(MembersViewModel.LAST_OWNER_MESSAGE, vm.state.value.alert)
        assertNull(vm.state.value.toast)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(removes.isEmpty())
        assertEquals(0, left)
    }

    @Test
    fun `CORE-04 - AC2 the DELETE is sent at 5 s and not at 4_999 ms`() = runTest {
        val vm = vm().loaded(owner, bob)
        vm.remove(bob)
        advanceTimeBy(4_999); runCurrent()
        assertTrue(removes.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(Call.RemoveMember(household, bob.id)), removes)
        assertEquals(listOf(owner), vm.state.value.rows)
        assertNull(vm.state.value.pending)
        assertEquals(listOf(owner), cache.read(household))
    }

    @Test
    fun `CORE-04 - a non-last owner leaving calls onLeft after the DELETE`() = runTest {
        val second = member(12, "Cy", Member.Role.owner)
        val vm = vm().loaded(owner, second)
        vm.leave()
        assertEquals("Leaving household", vm.state.value.toast)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(Call.RemoveMember(household, me)), removes)
        assertEquals(1, left)
    }

    @Test
    fun `CORE-04 - flushPending commits right away and only once`() = runTest {
        val vm = vm().loaded(owner, bob)
        vm.remove(bob)
        vm.flushPending()
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, removes.size)
        assertEquals(listOf(owner), vm.state.value.rows)
    }

    @Test
    fun `CORE-04 - NotFound counts as success`() = runTest {
        api.removeResult = ApiResult.Failure(ApiFailure.NotFound)
        val vm = vm().loaded(owner, bob)
        vm.remove(bob)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(owner), vm.state.value.rows)
        assertNull(vm.state.value.alert)
    }

    @Test
    fun `CORE-04 - a failed removal brings the row back, sets the alert and loads again`() = runTest {
        api.removeResult = ApiResult.Failure(ApiFailure.Unknown)
        val vm = vm().loaded(owner, bob)
        vm.remove(bob)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("Couldn't remove Bob. Try again.", vm.state.value.alert)
        assertTrue(vm.state.value.visibleRows.any { it.id == bob.id })
        assertEquals(2, api.calls.filterIsInstance<Call.FetchMembers>().size)
    }

    @Test
    fun `CORE-04 - failure messages follow the failure kind`() = runTest {
        val cases = listOf(
            ApiFailure.LastOwner to MembersViewModel.LAST_OWNER_MESSAGE,
            ApiFailure.Forbidden to "You don't have permission to remove Bob.",
            ApiFailure.Network to "Couldn't remove Bob. Try again.",
        )
        for ((failure, message) in cases) {
            api.removeResult = ApiResult.Failure(failure)
            val vm = vm().loaded(owner, bob)
            vm.remove(bob)
            advanceTimeBy(5_000); runCurrent()
            assertEquals(message, vm.state.value.alert)
        }
        api.removeResult = ApiResult.Failure(ApiFailure.Unknown)
        val second = member(12, "Cy", Member.Role.owner)
        val leaving = vm().loaded(owner, second)
        leaving.leave()
        advanceTimeBy(5_000); runCurrent()
        assertEquals("Couldn't leave the household. Try again.", leaving.state.value.alert)
        assertEquals(0, left)
    }

    @Test
    fun `CORE-04 - a fetch that started before a removal is ignored`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var slow = false
        val gated = object : ApiService by api {
            override suspend fun fetchMembers(householdId: java.util.UUID): ApiResult<List<MemberRow>> {
                val result = api.fetchMembers(householdId)
                if (slow) { started.complete(Unit); gate.await() }
                return result
            }
        }
        val vm = vm(gated).loaded(owner, bob)
        slow = true
        val stale = launch { vm.load() }
        runCurrent()
        started.await()
        vm.remove(bob)
        advanceTimeBy(5_000); runCurrent()
        gate.complete(Unit)
        stale.join()
        assertEquals(listOf(owner), vm.state.value.rows)
    }

    @Test
    fun `CORE-04 - offline makes no calls and the list comes from the cache`() = runTest {
        cache.write(listOf(owner, bob), household)
        online = false
        val vm = vm()
        vm.load()
        assertEquals(MembersLoadState.Loaded, vm.state.value.loadState)
        assertEquals(2, vm.state.value.rows.size)
        vm.remove(bob)
        vm.leave()
        vm.makeOwner(bob)
        assertTrue(api.calls.isEmpty())
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `CORE-04 - load states without a cache`() = runTest {
        online = false
        val offline = vm()
        offline.load()
        assertEquals(MembersLoadState.OfflineNoCache, offline.state.value.loadState)

        online = true
        api.membersResult = ApiResult.Failure(ApiFailure.Network)
        val failing = vm()
        failing.load()
        assertEquals(MembersLoadState.Error, failing.state.value.loadState)
    }

    @Test
    fun `CORE-04 - a failed refresh keeps the list on screen`() = runTest {
        val vm = vm().loaded(owner, bob)
        api.membersResult = ApiResult.Failure(ApiFailure.Network)
        vm.load()
        assertEquals(MembersLoadState.Loaded, vm.state.value.loadState)
    }

    @Test
    fun `CORE-04 - make owner success clears the alert and loads again`() = runTest {
        val vm = vm().loaded(owner, bob)
        api.makeOwnerResult = ApiResult.Failure(ApiFailure.Unknown)
        vm.makeOwner(bob)
        assertEquals("Couldn't make Bob an owner. Try again.", vm.state.value.alert)

        api.makeOwnerResult = ApiResult.Success(Unit)
        api.membersResult = ApiResult.Success(listOf(owner, member(11, "Bob", Member.Role.owner)))
        vm.makeOwner(bob)
        assertNull(vm.state.value.alert)
        assertEquals(Call.MakeOwner(household, bob.id), api.calls.filterIsInstance<Call.MakeOwner>().last())
        assertEquals(2, api.calls.filterIsInstance<Call.FetchMembers>().size)
        assertEquals(Member.Role.owner, vm.state.value.rows.single { it.id == bob.id }.member.role)
    }

    @Test
    fun `CORE-04 - make owner is for owners acting on non-owners`() = runTest {
        val member = vm().loaded(member(10, "Ada"), bob)
        member.makeOwner(bob)
        val owners = vm().loaded(owner, member(12, "Cy", Member.Role.owner))
        owners.makeOwner(member(12, "Cy", Member.Role.owner))
        assertTrue(api.calls.none { it is Call.MakeOwner })
    }

    @Test
    fun `CORE-04 - remove and leave are no-ops while something is pending`() = runTest {
        val second = member(12, "Cy", Member.Role.owner)
        val vm = vm().loaded(owner, bob, second)
        vm.remove(bob)
        vm.remove(second)
        vm.leave()
        assertEquals("Removed Bob", vm.state.value.toast)
        assertTrue(vm.state.value.isBusy)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(Call.RemoveMember(household, bob.id)), removes)
    }

    @Test
    fun `CORE-04 - only owners can remove others`() = runTest {
        val vm = vm().loaded(member(10, "Ada"), bob)
        vm.remove(bob)
        assertNull(vm.state.value.toast)
        assertFalse(MembersViewModel.canRemove(Member.Role.owner, owner, me))
        assertTrue(MembersViewModel.canRemove(Member.Role.owner, bob, me))
        assertFalse(MembersViewModel.canRemove(Member.Role.member, bob, me))
    }

    @Test
    fun `CORE-04 - helpers sort initials and display name`() {
        val rows = listOf(member(1, "zed"), member(2, "Bea"), member(3, "amy", Member.Role.owner), member(4, "Amy"))
        assertEquals(listOf(3, 4, 2, 1).map { id(it) }, MembersViewModel.sortMembers(rows).map { it.id })
        assertEquals("AK", MembersViewModel.initials("Anna Kovacs"))
        assertEquals("AZ", MembersViewModel.initials("  anna  maria zed "))
        assertEquals("A", MembersViewModel.initials("anna"))
        assertEquals("?", MembersViewModel.initials("  "))
        assertEquals("Member", MembersViewModel.displayName(member(5, name = null)))
        assertEquals("Member", MembersViewModel.displayName(member(5, name = "   ")))
        assertEquals("Ann", MembersViewModel.displayName(member(5, name = " Ann ")))
        assertTrue(MembersViewModel.isLastOwner(listOf(owner, bob), me))
        assertFalse(MembersViewModel.isLastOwner(listOf(owner, member(12, "Cy", Member.Role.owner)), me))
        assertFalse(MembersViewModel.isLastOwner(listOf(member(10, "Ada"), member(12, "Cy", Member.Role.owner)), me))
    }

    @Test
    fun `CORE-04 - removeFailure maps the error code first, then the status`() {
        val f = HttpApiService.Companion::removeFailure
        assertEquals(ApiFailure.LastOwner, f(409, "last_owner"))
        assertEquals(ApiFailure.Forbidden, f(403, "forbidden"))
        assertEquals(ApiFailure.NotFound, f(404, "not_found"))
        assertEquals(ApiFailure.Unknown, f(409, "something_else"))
        assertEquals(ApiFailure.LastOwner, f(409, null))
        assertEquals(ApiFailure.Forbidden, f(403, null))
        assertEquals(ApiFailure.NotFound, f(404, null))
        assertEquals(ApiFailure.Unknown, f(500, null))
    }
}
