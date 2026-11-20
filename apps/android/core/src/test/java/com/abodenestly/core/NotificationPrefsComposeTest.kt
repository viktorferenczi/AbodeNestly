package com.abodenestly.core

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.notificationprefs.NotificationPrefsContent
import com.abodenestly.core.notificationprefs.NotificationPrefsEvent
import com.abodenestly.core.notificationprefs.NotificationPrefsViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.SearchModule
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private val NOW: Instant = Instant.parse("2026-11-20T10:00:00Z")
private val ALL_MODULES = SearchModule.entries.toSet()
private val TYPES = listOf("eat_soon", "low_stock", "task_due", "warranty", "activity")

/** One case per State and Interaction of DESIGN B9 (TESTING §7), over a real Room file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class NotificationPrefsComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)
    private val user = id(2)
    private val sync = FakeSyncEngine()
    private var settingsOpened = 0

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun show(modules: Set<SearchModule> = ALL_MODULES, allowed: Boolean = true) {
        runBlocking { registry.open(household) }
        val vm = NotificationPrefsViewModel(
            registry.observeNotificationPrefs(household, user), modules,
            save = { type, enabled, now -> registry.setNotificationPref(user, household, type.key, enabled, now) }, sync,
            notificationsAllowed = { allowed }, openSystemSettings = { settingsOpened++ },
            now = { NOW }, scope = CoroutineScope(Dispatchers.Unconfined),
        )
        rule.setContent {
            AbodeTheme {
                val ui by vm.state.collectAsState()
                NotificationPrefsContent(ui, vm::onEvent)
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) = rule.waitUntil(5_000) { rule.waitForIdle(); exists(tag) }

    @Test
    fun `CORE-12 AC1 idle shows five switches, all on`() {
        show()
        await("notif.switch.activity")
        TYPES.forEach { rule.onNodeWithTag("notif.switch.$it").assertIsOn() }
        assertTrue(!exists("notif.denied"))
    }

    @Test
    fun `CORE-12 AC2 turning Eat soon off queues one upsert, kicks sync and the switch follows`() {
        show()
        await("notif.switch.eat_soon")
        rule.onNodeWithTag("notif.switch.eat_soon").performClick()
        rule.waitUntil(5_000) { rule.waitForIdle(); sync.kicks.get() == 1 }
        // The flow re-emits after the write, so the switch follows it.
        rule.waitUntil(5_000) { rule.waitForIdle(); runCatching { rule.onNodeWithTag("notif.switch.eat_soon").assertIsOff() }.isSuccess }
        assertEquals(1, sync.kicks.get())
        val out = runBlocking { registry.outboxForTesting() }
        assertEquals("notification_pref", out[0].tableName)
        assertTrue(out[0].data.contains(""""type":"eat_soon","enabled":false"""))
    }

    @Test
    fun `CORE-12 AC3 permission denied shows the banner and keeps the switches`() {
        show(allowed = false)
        await("notif.denied")
        TYPES.forEach { assertTrue(exists("notif.switch.$it")) }
    }

    @Test
    fun `CORE-12 AC4 pantry off hides Eat soon and Low stock`() {
        show(setOf(SearchModule.Shopping, SearchModule.Maintenance))
        await("notif.switch.activity")
        assertTrue(!exists("notif.switch.eat_soon") && !exists("notif.switch.low_stock"))
        assertTrue(exists("notif.switch.task_due") && exists("notif.switch.warranty"))
    }

    @Test
    fun `CORE-12 AC5 maintenance off hides Task due and Warranty, Activity stays`() {
        show(setOf(SearchModule.Shopping, SearchModule.Pantry))
        await("notif.switch.activity")
        assertTrue(!exists("notif.switch.task_due") && !exists("notif.switch.warranty"))
        rule.onAllNodesWithTag("notif.switch.eat_soon").assertCountEquals(1)
    }

    @Test
    fun `CORE-12 the settings link opens the system notification settings`() {
        show()
        await("notif.settings")
        rule.onNodeWithTag("notif.settings").performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun `CORE-12 coming back from the system settings re-reads the permission`() {
        var allowed = false
        runBlocking { registry.open(household) }
        val vm = NotificationPrefsViewModel(
            registry.observeNotificationPrefs(household, user), ALL_MODULES,
            save = { _, _, _ -> }, sync, notificationsAllowed = { allowed }, openSystemSettings = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        rule.setContent {
            AbodeTheme {
                val ui by vm.state.collectAsState()
                NotificationPrefsContent(ui, vm::onEvent)
            }
        }
        await("notif.denied")
        allowed = true
        vm.onEvent(NotificationPrefsEvent.Resumed)
        rule.waitUntil(5_000) { rule.waitForIdle(); !exists("notif.denied") }
    }
}
