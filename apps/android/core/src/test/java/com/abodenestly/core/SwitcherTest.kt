package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.contract.models.Member
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.core.auth.AppTab
import com.abodenestly.core.auth.AuthError
import com.abodenestly.core.auth.Destination
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.LandingHousehold
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.auth.SwitchError
import com.abodenestly.core.switcher.InMemoryHouseholdsCache
import com.abodenestly.core.switcher.Switcher
import com.abodenestly.core.switcher.SwitcherState
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ShoppingList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** CORE-07 logic: the per-household Room files, the switch rules and the pure helpers. Real Room files in Robolectric's temp context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwitcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val a = id(1)
    private val b = id(2)
    private val me = Fixtures.userId

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun file(id: UUID) = context.getDatabasePath("household-$id.db")

    @Test
    fun `AC2 - the open database file changes from A to B and each cursor starts at 0`() = runBlocking {
        registry.open(a)
        assertEquals("household-$a.db", registry.fileName(a))
        assertEquals(a, registry.current.value)
        assertTrue(file(a).exists())
        assertEquals(0L, registry.readCursor())

        registry.writeCursor(7L)
        registry.open(b)
        assertEquals(b, registry.current.value)
        assertEquals("household-$b.db", registry.fileName(b))
        assertTrue(file(b).exists())
        assertEquals(0L, registry.readCursor())

        registry.open(a)
        assertEquals("the cursor lives in the household's own file", 7L, registry.readCursor())
    }

    @Test
    fun `a cursor beyond Int is stored and read back whole`() = runBlocking {
        registry.open(a)
        registry.writeCursor(5_000_000_000L)
        assertEquals(5_000_000_000L, registry.readCursor())
    }

    @Test
    fun `an open whose caller went stale leaves the open household in place and closes the new file`() = runBlocking {
        registry.open(a)
        assertFalse(registry.open(b) { false })
        assertEquals(a, registry.current.value)
        assertEquals(0L, registry.readCursor())
        assertTrue("the stale file may exist but is closed: a fresh open works", registry.open(b))
        assertEquals(b, registry.current.value)
    }

    @Test
    fun `enter leaves Welcome before the file open finishes`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : DatabaseRegistry(context) {
            override suspend fun open(id: UUID, isCurrent: () -> Boolean): Boolean {
                gate.await()
                return super.open(id, isCurrent)
            }
        }
        val rig = Rig(context, slow, online = true)
        rig.auth.memberships = Fetch.Success(emptyList())
        rig.session.start()
        rig.session.awaitLoaded()
        assertEquals(Destination.Welcome, rig.session.state.value.destination)
        rig.session.enter(LandingHousehold(a, true, true, true))
        assertNull("not Welcome while the file opens", rig.session.state.value.destination)
        gate.complete(Unit)
        rig.session.awaitLoaded()
        kotlinx.coroutines.withTimeout(5_000) { while (rig.session.state.value.destination == null) kotlinx.coroutines.delay(10) }
        assertEquals(Destination.Household(a, AppTab.Home), rig.session.state.value.destination)
    }

    @Test
    fun `a switch that outlives its session does not replace the open database`() = runBlocking {
        val rig = rig()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : DatabaseRegistry(context) {
            override suspend fun open(id: UUID, isCurrent: () -> Boolean): Boolean {
                if (id == b) gate.await()
                return super.open(id, isCurrent)
            }
        }
        // Same user, but a registry that holds the target's open; sign out while it waits.
        val session = SessionViewModel(
            rig.auth, rig.last, isOnline = { true }, database = slow, householdsCache = rig.cache, api = rig.api,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        registry.close()
        session.start(); session.awaitLoaded()
        val switch = async(Dispatchers.Default) { runCatching { session.switchTo(b) } }
        kotlinx.coroutines.delay(100)
        rig.auth.emit(null)
        gate.complete(Unit)
        val result = switch.await().exceptionOrNull()
        assertEquals(SwitchError.SessionEnded, result)
        slow.close()
    }

    @Test
    fun `opening the open household keeps it, close leaves none and a read then fails`() = runBlocking {
        registry.open(a)
        registry.insertList(ShoppingList(UUID.randomUUID(), a, "Kept"))
        registry.open(a)
        assertEquals(listOf("Kept"), registry.lists().map { it.name })
        registry.close()
        assertNull(registry.current.value)
        try {
            registry.lists()
            fail("expected DatabaseNotOpenException")
        } catch (_: com.abodenestly.sync.DatabaseNotOpenException) {
        }
    }

    @Test
    fun `AC4 - a list added in A is not seen in B and back`() = runBlocking {
        registry.open(a)
        registry.insertList(ShoppingList(UUID.randomUUID(), a, "Alpha list"))
        registry.open(b)
        assertEquals(emptyList<String>(), registry.lists().map { it.name })
        registry.insertList(ShoppingList(UUID.randomUUID(), b, "Beta list"))
        assertEquals(listOf("Beta list"), registry.lists().map { it.name })
        registry.open(a)
        assertEquals(listOf("Alpha list"), registry.lists().map { it.name })
    }

    @Test
    fun `seed lists are inserted once into a household without any`() = runBlocking {
        val seeded = DatabaseRegistry(context, seedLists = mapOf(a to "Seed"))
        seeded.open(a)
        seeded.close()
        seeded.open(a)
        assertEquals(listOf("Seed"), seeded.lists().map { it.name })
        seeded.open(b)
        assertEquals(emptyList<String>(), seeded.lists().map { it.name })
        seeded.close()
    }

    @Test
    fun `pendingCount reads the open household, a closed one's file and 0 for a household never opened`() = runBlocking {
        assertEquals(0, registry.pendingCount(a))
        assertFalse("counting must not create the file", file(a).exists())
        registry.open(a)
        registry.enqueueForTesting()
        registry.enqueueForTesting()
        assertEquals(2, registry.pendingCount(a))
        registry.open(b)
        registry.enqueueForTesting()
        assertEquals(1, registry.pendingCount(b))
        assertEquals("A is closed now: counted from its file", 2, registry.pendingCount(a))
        assertEquals(b, registry.current.value)
    }

    // --- SessionViewModel.switchTo ---

    private class Rig(context: Context, val registry: DatabaseRegistry, online: Boolean) {
        val auth = FakeAuthService(Fixtures.session)
        val api = FakeApiService()
        val cache = InMemoryHouseholdsCache()
        val last = InMemoryLastHouseholdStore()
        var online = online
        val session = SessionViewModel(
            auth, last, isOnline = { this.online }, database = registry, householdsCache = cache, api = api,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        )
    }

    private fun entry(n: Int, name: String, pantry: Boolean = true) =
        HouseholdWithRole(Fixtures.household(n, name).copy(modulePantry = pantry), Member.Role.owner)

    /** Signed in to household [1] (Alpha, pantry on), with Beta (pantry [betaPantry]) cached too. */
    private suspend fun rig(online: Boolean = true, betaPantry: Boolean = false): Rig {
        val rig = Rig(context, registry, online)
        val list = listOf(entry(1, "Alpha"), entry(2, "Beta", pantry = betaPantry))
        rig.auth.memberships = Fetch.Success(listOf(Fixtures.membership(1), Fixtures.membership(2, joinedDay = 2, pantry = betaPantry)))
        rig.api.myHouseholdsResult = ApiResult.Success(list)
        rig.session.start()
        rig.session.awaitLoaded()
        return rig
    }

    @Test
    fun `a landing opens the household's database and names it from the fetched list`() = runBlocking {
        val rig = rig()
        assertEquals(a, registry.current.value)
        assertEquals("Alpha", rig.session.state.value.householdName)
        assertEquals(a, rig.last.read())
    }

    @Test
    fun `AC2 - switching keeps the tab, swaps the database and the name`() = runBlocking {
        val rig = rig(betaPantry = true)
        rig.session.selectTab(AppTab.Pantry)
        rig.session.switchTo(b)
        val state = rig.session.state.value
        assertEquals(Destination.Household(b, AppTab.Pantry), state.destination)
        assertEquals(AppTab.Pantry, state.selectedTab)
        assertEquals("Beta", state.householdName)
        assertEquals(b, state.household?.id)
        assertEquals(b, registry.current.value)
        assertEquals(b, rig.last.read())
        assertNull(state.switching)
    }

    @Test
    fun `a tab whose module is off in the target falls back to its start tab`() = runBlocking {
        val rig = rig(betaPantry = false)
        rig.session.selectTab(AppTab.Pantry)
        rig.session.switchTo(b)
        assertEquals(Destination.Household(b, AppTab.Shopping), rig.session.state.value.destination)
        assertEquals(AppTab.Shopping, rig.session.state.value.selectedTab)
    }

    @Test
    fun `AC5 - offline with a failing API the switch still completes from the cache`() = runBlocking {
        val rig = rig(online = false)
        rig.api.myHouseholdsResult = ApiResult.Failure(ApiFailure.Network)
        // Offline, no priming ran: seed the cache as an earlier online run would have.
        rig.cache.write(me, listOf(entry(1, "Alpha"), entry(2, "Beta")))
        rig.session.switchTo(b)
        assertEquals(b, registry.current.value)
        assertEquals("Beta", rig.session.state.value.householdName)
        assertTrue("no API call while offline", rig.api.calls.isEmpty())
    }

    @Test
    fun `an unknown household throws and changes nothing`() = runBlocking {
        val rig = rig()
        try {
            rig.session.switchTo(id(9))
            fail("expected UnknownHousehold")
        } catch (e: SwitchError) {
            assertEquals(SwitchError.UnknownHousehold, e)
        }
        assertEquals(a, registry.current.value)
        assertEquals(Destination.Household(a, AppTab.Shopping), rig.session.state.value.destination)
        assertNull(rig.session.state.value.switching)
    }

    @Test
    fun `a database that cannot open reopens the previous one and throws OpenFailed`() = runBlocking {
        val rig = rig()
        // A directory where the file should be makes SQLite fail to open it.
        file(b).apply { parentFile?.mkdirs() }.mkdirs()
        try {
            rig.session.switchTo(b)
            fail("expected OpenFailed")
        } catch (e: SwitchError) {
            assertEquals(SwitchError.OpenFailed, e)
        }
        assertEquals(a, registry.current.value)
        assertEquals(Destination.Household(a, AppTab.Shopping), rig.session.state.value.destination)
        assertEquals(a, rig.last.read())
        assertNull(rig.session.state.value.switching)
    }

    @Test
    fun `switching to the current household with openSettings only asks for Settings`() = runBlocking {
        val rig = rig()
        rig.session.switchTo(a, openSettings = true)
        assertTrue(rig.session.state.value.settingsRequested)
        assertEquals(AppTab.Home, rig.session.state.value.selectedTab)
        rig.session.settingsShown()
        assertFalse(rig.session.state.value.settingsRequested)
    }

    @Test
    fun `long-press switch lands on Home and requests Settings`() = runBlocking {
        val rig = rig()
        rig.session.switchTo(b, openSettings = true)
        assertEquals(b, registry.current.value)
        assertEquals(AppTab.Home, rig.session.state.value.selectedTab)
        assertTrue(rig.session.state.value.settingsRequested)
    }

    @Test
    fun `signing out closes the database and clears the cached list`() = runBlocking {
        val rig = rig()
        assertTrue(rig.cache.read(me).isNotEmpty())
        rig.auth.emit(null)
        // The close runs on the IO dispatcher.
        registry.close()
        assertNull(registry.current.value)
        assertTrue(rig.cache.read(me).isEmpty())
    }

    @Test
    fun `a failed open of the landing household is a load failure, not a household without a file`() = runBlocking {
        file(a).apply { parentFile?.mkdirs() }.mkdirs()
        val rig = rig()
        assertTrue(rig.session.state.value.loadFailed)
        assertNull(rig.session.state.value.destination)
    }

    @Test
    fun `offline landing opens the last household's database at Home`() = runBlocking {
        val rig = Rig(context, registry, online = false)
        rig.last.write(b)
        rig.cache.write(me, listOf(entry(1, "Alpha"), entry(2, "Beta")))
        rig.auth.memberships = Fetch.Failure(AuthError.Network)
        rig.session.start()
        rig.session.awaitLoaded()
        assertEquals(Destination.Home, rig.session.state.value.destination)
        assertEquals(b, registry.current.value)
        assertEquals("Beta", rig.session.state.value.householdName)
    }

    // --- pure helpers ---

    @Test
    fun `targetTab - table`() {
        fun h(s: Boolean, p: Boolean, m: Boolean) = LandingHousehold(id(1), s, p, m)
        val cases = listOf(
            Triple(AppTab.Shopping, h(true, true, true), AppTab.Shopping),
            Triple(AppTab.Pantry, h(true, true, true), AppTab.Pantry),
            Triple(AppTab.Maintenance, h(true, true, true), AppTab.Maintenance),
            Triple(AppTab.Home, h(false, false, false), AppTab.Home),
            Triple(AppTab.Pantry, h(true, false, true), AppTab.Shopping),
            Triple(AppTab.Shopping, h(false, true, true), AppTab.Pantry),
            Triple(AppTab.Maintenance, h(false, false, false), AppTab.Home),
            Triple(AppTab.Shopping, h(false, false, true), AppTab.Maintenance),
        )
        for ((current, target, expected) in cases) assertEquals("$current → $target", expected, Switcher.targetTab(current, target))
    }

    @Test
    fun `state - table`() {
        assertEquals(SwitcherState.OneHousehold, Switcher.state(0, null))
        assertEquals(SwitcherState.OneHousehold, Switcher.state(1, null))
        assertEquals(SwitcherState.Many, Switcher.state(2, null))
        assertEquals(SwitcherState.Many, Switcher.state(5, null))
        assertEquals(SwitcherState.Switching("Beta"), Switcher.state(1, "Beta"))
        assertEquals(SwitcherState.Switching("Beta"), Switcher.state(3, "Beta"))
    }

    @Test
    fun `HouseholdsCache - upsert and remove are per user`() = runBlocking {
        val cache = InMemoryHouseholdsCache()
        val other = id(77)
        cache.upsert(me, entry(1, "Alpha"))
        cache.upsert(me, entry(2, "Beta"))
        cache.upsert(me, entry(1, "Alpha renamed"))
        cache.upsert(other, entry(3, "Gamma"))
        assertEquals(listOf("Alpha renamed", "Beta"), cache.read(me).map { it.household.name })
        cache.remove(me, id(2))
        assertEquals(listOf("Alpha renamed"), cache.read(me).map { it.household.name })
        assertEquals(listOf("Gamma"), cache.read(other).map { it.household.name })
        cache.clear(me)
        assertTrue(cache.read(me).isEmpty())
        assertEquals(1, cache.read(other).size)
    }
}
