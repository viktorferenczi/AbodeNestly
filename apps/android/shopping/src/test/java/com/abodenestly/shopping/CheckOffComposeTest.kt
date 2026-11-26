package com.abodenestly.shopping

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * DESIGN C2 populated, all-checked and offline for SHOP-04 (TESTING §7), on a real Room file. The undo window is a gate
 * the test never opens, so nothing waits in real time and nothing is written inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class CheckOffComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private lateinit var vm: ListDetailViewModel

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun seedItem(id: String, name: String, checked: Boolean = false) = runBlocking {
        registry.withSearchDao {
            it.upsertListItem(
                ListItemEntity(
                    id, hid, "l1", name, nameKey = name.lowercase(), checked = checked,
                    checkedAt = if (checked) "2026-11-25T09:00:00.000Z" else null, createdAt = "2026-11-01T00:00:00.000Z",
                ),
            )
        }
    }

    private fun show(online: Boolean = true, seed: () -> Unit = {}) {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        seed()
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { }, isOnline = MutableStateFlow(online),
            delayer = { gate.await() }, now = { now }, newId = { "n1" }, scope = scope,
        )
        rule.setContent { AbodeTheme { ListDetailScreenHost(vm, onBack = {}) { _, _, _, _, _, _, _ -> } } }
        rule.waitForIdle()
    }

    private fun exists(tag: String, unmerged: Boolean = false) = rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(5_000) { !exists(tag) }

    private fun itemOutbox() = runBlocking { registry.outboxForTesting().filter { it.tableName == "list_item" } }

    private fun isTicked() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Checked")

    @Test
    fun `AC1 the checkbox is labelled, a tap shows the Checked toast and ticks the row in place`() {
        show(seed = { seedItem("i1", "Milk") })
        await("list.row.Milk.check")
        rule.onNodeWithTag("list.row.Milk.check").assertContentDescriptionEquals("Check off Milk")
        rule.onNodeWithTag("list.row.Milk.check").performClick()

        await("undo-toast")
        rule.onNodeWithText("Checked Milk").assertIsDisplayed()
        // Still among the unchecked rows, ticked and struck through; nothing is in the Checked group or the outbox yet.
        rule.onNodeWithTag("list.row.Milk").assert(isTicked())
        rule.onNodeWithTag("list.row.Milk.check").assertContentDescriptionEquals("Uncheck Milk")
        assertFalse(exists("list.checked"))
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `Undo takes the toast away and the row is unchecked again, with nothing written`() {
        show(seed = { seedItem("i1", "Milk") })
        await("list.row.Milk.check")
        rule.onNodeWithTag("list.row.Milk.check").performClick()
        await("undo-toast")
        rule.onNodeWithTag("undo-toast.undo").performClick()

        awaitGone("undo-toast")
        rule.onNodeWithTag("list.row.Milk.check").assertContentDescriptionEquals("Check off Milk")
        assertFalse(exists("list.checked"))
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `tapping the ticked box of a pending row takes the check back`() {
        show(seed = { seedItem("i1", "Milk") })
        await("list.row.Milk.check")
        rule.onNodeWithTag("list.row.Milk.check").performClick()
        await("undo-toast")
        rule.onNodeWithTag("list.row.Milk.check").performClick()

        awaitGone("undo-toast")
        rule.onNodeWithTag("list.row.Milk.check").assertContentDescriptionEquals("Check off Milk")
    }

    @Test
    fun `the row body still opens the item sheet`() {
        show(seed = { seedItem("i1", "Milk") })
        await("list.row.Milk")
        rule.onNodeWithTag("list.row.Milk").performClick()
        rule.waitUntil(5_000) { vm.state.value.sheet == SheetState.Edit("i1") }
        assertTrue(vm.state.value.pending.isEmpty())
    }

    @Test
    fun `AC4 all checked shows All done and Clear checked, and the button starts the clear`() {
        show(seed = { seedItem("i1", "Milk", checked = true) })
        await("list.allDone")
        rule.onNodeWithTag("list.allDone").assertIsDisplayed()
        rule.onNodeWithTag("list.clearChecked").assertIsDisplayed()
        rule.onNodeWithTag("list.clearChecked").performClick()

        await("undo-toast")
        rule.onNodeWithText("Cleared 1 item").assertIsDisplayed()
        // The rows are only hidden during the window: no empty state, and quick add is not focused.
        rule.onNodeWithTag("list.empty").assertDoesNotExist()
        assertEquals(0, itemOutbox().size)
    }

    @Test
    fun `an empty list keeps Add your first item and shows neither All done nor List actions`() {
        show()
        await("list.empty")
        assertFalse(exists("list.allDone"))
        assertFalse(exists("list.actions"))
    }

    @Test
    fun `List actions shows only with a checked item, and its Clear checked item starts the clear`() {
        show(seed = { seedItem("i1", "Milk"); seedItem("i2", "Bread", checked = true) })
        await("list.actions")
        // Not all-checked: Milk is still to buy.
        assertFalse(exists("list.allDone"))
        rule.onNodeWithTag("list.actions").assertContentDescriptionEquals("List actions")
        rule.onNodeWithTag("list.actions").performClick()
        await("list.actions.clearChecked")
        rule.onNodeWithTag("list.actions.clearChecked").performClick()

        await("undo-toast")
        rule.onNodeWithText("Cleared 1 item").assertIsDisplayed()
        // The rows are only hidden during the window: no empty state, and quick add is not focused.
        rule.onNodeWithTag("list.empty").assertDoesNotExist()
    }

    @Test
    fun `no List actions while nothing is checked`() {
        show(seed = { seedItem("i1", "Milk") })
        await("list.row.Milk")
        assertFalse(exists("list.actions"))
    }

    @Test
    fun `a checked row has an Uncheck box that moves it back at once with one outbox upsert`() {
        show(seed = { seedItem("i1", "Milk", checked = true) })
        await("list.checked")
        rule.onNodeWithTag("list.checked").performClick()
        await("list.checked.row.Milk.check")
        rule.onNodeWithTag("list.checked.row.Milk.check").assertContentDescriptionEquals("Uncheck Milk")
        rule.onNodeWithTag("list.checked.row.Milk.check").performClick()

        await("list.row.Milk")
        assertFalse(exists("undo-toast"))
        rule.waitUntil(5_000) { itemOutbox().size == 1 }
        assertEquals("upsert", itemOutbox().single().op)
    }

    @Test
    fun `AC6 offline shows the banner and the checkbox still works`() {
        show(online = false, seed = { seedItem("i1", "Milk") })
        await("offline-banner")
        await("list.row.Milk.check")
        rule.onNodeWithTag("list.row.Milk.check").performClick()
        await("undo-toast")
        rule.onNodeWithText("Checked Milk").assertIsDisplayed()
        assertTrue(exists("offline-banner"))
    }
}
