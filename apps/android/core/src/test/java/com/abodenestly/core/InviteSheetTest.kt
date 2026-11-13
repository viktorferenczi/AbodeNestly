package com.abodenestly.core

import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.Fixtures.invite
import com.abodenestly.core.Fixtures.membership
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.invite.InviteSheetState
import com.abodenestly.core.invite.InviteSheetViewModel
import com.abodenestly.core.welcome.WelcomeOutcome
import com.abodenestly.core.welcome.WelcomeViewModel
import com.abodenestly.core.welcome.land
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.Locale

/** CORE-03 logic: AC4 join by code, and the invite sheet's view model. */
class InviteSheetTest {
    private fun scope() = CoroutineScope(Job() + Dispatchers.Unconfined)

    private val household = id(1)
    private val api = FakeApiService()
    private val copied = mutableListOf<String>()
    private fun sheet() = InviteSheetViewModel(household, api, copy = { copied += it }, scope = scope())

    @Test
    fun `CORE-03 - AC4 a pasted upper-case code with spaces is joined lowercase and lands on Home`() {
        val auth = FakeAuthService(Fixtures.session)
        auth.memberships = Fetch.Success(emptyList())
        val last = InMemoryLastHouseholdStore()
        val session = SessionViewModel(auth, last, isOnline = { true }, scope = scope())
        session.start()
        runBlocking { session.awaitLoaded() }

        val joined = id(7)
        auth.memberships = Fetch.Success(listOf(membership(7)))
        api.acceptResult = ApiResult.Success(joined)
        val outcomes = mutableListOf<WelcomeOutcome>()
        val welcome = WelcomeViewModel(api, onLanded = { outcomes += it }, scope = scope())
        welcome.onCodeChange(" ABC123def456 ")
        welcome.join()
        session.land(outcomes.single())
        runBlocking { session.awaitLoaded() }

        assertEquals(listOf<Call>(Call.AcceptInvite("abc123def456")), api.calls)
        assertEquals(Destination.Household(joined, AppTab.Home), session.state.value.destination)
        assertEquals(joined, runBlocking { last.read() })
    }

    @Test
    fun `CORE-03 - a live invite is shown and none is created`() {
        val live = invite()
        api.liveInviteResult = ApiResult.Success(live)
        val vm = sheet()
        vm.loadIfNeeded()
        assertEquals(InviteSheetState.Ready(live), vm.state.value)
        assertEquals(listOf<Call>(Call.FetchLiveInvite(household)), api.calls)
    }

    @Test
    fun `CORE-03 - with no live invite one is created`() {
        val created = invite("fedcba654321")
        api.createInviteResult = ApiResult.Success(created)
        val vm = sheet()
        vm.loadIfNeeded()
        assertEquals(InviteSheetState.Ready(created), vm.state.value)
        assertEquals(listOf<Call>(Call.FetchLiveInvite(household), Call.CreateInvite(household)), api.calls)
    }

    @Test
    fun `CORE-03 - a failed load is an error without an invite and Try again can recover`() {
        api.liveInviteResult = ApiResult.Failure(ApiFailure.Network)
        val vm = sheet()
        vm.loadIfNeeded()
        assertEquals(InviteSheetState.Error(ApiFailure.Network, null), vm.state.value)

        val live = invite()
        api.liveInviteResult = ApiResult.Success(live)
        vm.loadIfNeeded()
        assertEquals(InviteSheetState.Ready(live), vm.state.value)
    }

    @Test
    fun `CORE-03 - AC2 New code creates the new invite, then retires the old one`() {
        val old = invite("aaaaaaaaaaaa")
        val fresh = invite("bbbbbbbbbbbb")
        api.liveInviteResult = ApiResult.Success(old)
        api.createInviteResults.add(ApiResult.Success(fresh))
        val vm = sheet()
        vm.loadIfNeeded()
        vm.newCode()
        assertEquals(InviteSheetState.Ready(fresh), vm.state.value)
        assertFalse(vm.oldCodeMayWork.value)
        assertEquals(
            listOf(Call.FetchLiveInvite(household), Call.CreateInvite(household), Call.RetireInvite(old.id)),
            api.calls,
        )
    }

    @Test
    fun `CORE-03 - AC2 a failed retire keeps the new code and sets oldCodeMayWork`() {
        val old = invite("aaaaaaaaaaaa")
        val fresh = invite("bbbbbbbbbbbb")
        api.liveInviteResult = ApiResult.Success(old)
        api.createInviteResult = ApiResult.Success(fresh)
        api.retireResult = ApiResult.Failure(ApiFailure.Unknown)
        val vm = sheet()
        vm.loadIfNeeded()
        vm.newCode()
        assertEquals(InviteSheetState.Ready(fresh), vm.state.value)
        assertTrue(vm.oldCodeMayWork.value)
    }

    @Test
    fun `CORE-03 - a failed create keeps the old invite and retires nothing`() {
        val old = invite("aaaaaaaaaaaa")
        api.liveInviteResult = ApiResult.Success(old)
        api.createInviteResult = ApiResult.Failure(ApiFailure.Network)
        val vm = sheet()
        vm.loadIfNeeded()
        vm.newCode()
        assertEquals(InviteSheetState.Error(ApiFailure.Network, old), vm.state.value)
        assertTrue(api.calls.none { it is Call.RetireInvite })
    }

    @Test
    fun `CORE-03 - copy hands the code to the clipboard and shows a notice`() {
        api.liveInviteResult = ApiResult.Success(invite("abc123def456"))
        val vm = sheet()
        vm.loadIfNeeded()
        vm.copyCode()
        assertEquals(listOf("abc123def456"), copied)
        assertEquals("Code copied", vm.notice.value)
    }

    @Test
    fun `CORE-03 - link joins the base and the code as one encoded path segment`() {
        assertEquals("http://localhost:5173/join/abc123", InviteSheetViewModel.link("http://localhost:5173", "abc123"))
        assertEquals("https://app.example.com/join/abc123", InviteSheetViewModel.link("https://app.example.com/", "abc123"))
        assertEquals("http://localhost:5173/join/a%2Fb%3Fc%20d", InviteSheetViewModel.link("http://localhost:5173", "a/b?c d"))
    }

    @Test
    fun `CORE-03 - link is null for a blank or invalid base`() {
        assertNull(InviteSheetViewModel.link("", "abc123"))
        assertNull(InviteSheetViewModel.link("not a url", "abc123"))
    }

    @Test
    fun `CORE-03 - metaText reads uses left and the expiry day`() {
        val utc = ZoneOffset.UTC
        assertEquals("1 use left · Expires 19 Nov", InviteSheetViewModel.metaText(invite(uses = 9), Locale.ENGLISH, utc))
        assertEquals("10 uses left · Expires 19 Nov", InviteSheetViewModel.metaText(invite(uses = 0), Locale.ENGLISH, utc))
    }
}
