package com.abodenestly.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.abodenestly.core.home.HomeModules
import com.abodenestly.core.sync.HouseholdRealtimeEffect
import com.abodenestly.sync.FakeRealtime
import com.abodenestly.sync.RealtimeTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** SHOP-09 wiring: when the shell subscribes, to which household and tables, and what ends the subscription. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HouseholdRealtimeEffectTest {
    @get:Rule val rule = createComposeRule()

    private val realtime = FakeRealtime()
    private val kicks = AtomicInteger()
    private val first = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val second = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val allOn = HomeModules(shopping = true, pantry = true, maintenance = true)
    private val shoppingOff = HomeModules(shopping = false, pantry = true, maintenance = true)

    private var household by mutableStateOf<UUID?>(first)
    private var modules by mutableStateOf<HomeModules?>(allOn)
    private var foreground by mutableStateOf(true)
    private var shellShown by mutableStateOf(true)

    private fun show() {
        rule.setContent {
            if (shellShown) HouseholdRealtimeEffect(realtime, household, modules, foreground) { kicks.incrementAndGet() }
        }
        rule.waitForIdle()
    }

    private fun change(block: () -> Unit) {
        rule.runOnIdle(block)
        rule.waitForIdle()
    }

    private fun awaitActive(householdId: UUID?, tables: List<String>? = null) = rule.waitUntil(5_000) {
        val live = realtime.activeCalls
        if (householdId == null) live.isEmpty()
        else live.size == 1 && live[0].householdId == householdId.toString() && (tables == null || live[0].tables == tables)
    }

    @Test
    fun `it subscribes to the open household with the tables of its modules`() {
        show()
        awaitActive(first, RealtimeTables.tables(true, true, true))
        assertEquals(9, realtime.activeCalls.single().tables.size)
    }

    @Test
    fun `an event kicks the sync engine`() {
        show()
        awaitActive(first)
        realtime.fireEvent()
        assertEquals(1, kicks.get())
    }

    @Test
    fun `a household switch drops the old subscription and subscribes to the new household`() {
        show()
        awaitActive(first)
        change { household = second }
        awaitActive(second)
        assertEquals(listOf(first.toString(), second.toString()), realtime.calls.map { it.householdId })
    }

    @Test
    fun `AC4 shopping off resubscribes without any shopping table`() {
        show()
        awaitActive(first)
        change { modules = shoppingOff }
        awaitActive(first, RealtimeTables.tables(false, true, true))
        assertTrue(realtime.activeCalls.single().tables.none { it in RealtimeTables.shopping })
    }

    @Test
    fun `the background unsubscribes and the foreground subscribes again`() {
        show()
        awaitActive(first)
        change { foreground = false }
        awaitActive(null)
        change { foreground = true }
        awaitActive(first)
        assertEquals(2, realtime.calls.size)
    }

    @Test
    fun `leaving the shell on sign-out unsubscribes`() {
        show()
        awaitActive(first)
        change { shellShown = false }
        awaitActive(null)
    }

    @Test
    fun `nothing subscribes while the household or its flags are unknown`() {
        household = null
        show()
        assertEquals(0, realtime.calls.size)
        change { household = first; modules = null }
        assertEquals(0, realtime.calls.size)
        change { modules = allOn }
        awaitActive(first)
    }
}
