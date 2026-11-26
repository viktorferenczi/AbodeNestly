package com.abodenestly.shopping

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.StoreEntity
import com.abodenestly.sync.patch
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One case per State and Interaction of DESIGN C1 (TESTING §7), on a real Room file. The undo window is a gate the test
 * opens, so nothing waits in real time. The sheet's body is mounted inline: Robolectric never drives the popup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class ListsComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private val online = MutableStateFlow(true)
    private val opened = CopyOnWriteArrayList<String>()
    private val kicks = AtomicInteger()

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun seedList(id: String, name: String, storeId: String? = null, at: Instant = now, archivedAt: String? = null) = runBlocking {
        createList(registry, household, name, storeId, at, id)
        if (archivedAt != null) registry.patch(household, "shopping_list", id, buildJsonObject { put("archived_at", JsonPrimitive(archivedAt)) }, at)
    }

    private fun seedStore() = runBlocking {
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", household.toString(), "Test Market")) }
    }

    private fun seedItems(listId: String, total: Int, checked: Int) = runBlocking {
        registry.withSearchDao { dao ->
            repeat(total) { i -> dao.upsertListItem(ListItemEntity("$listId-i$i", household.toString(), listId, "Item $i", checked = i < checked)) }
        }
    }

    private fun outboxSize() = runBlocking { registry.outboxForTesting().size }

    /** Opens the household and mounts the screen. [modelFor] is the household the model reads and writes (another one makes writes fail). */
    private fun show(isOnline: Boolean = true, modelFor: UUID = household, seed: () -> Unit = {}) {
        runBlocking { registry.open(household) }
        seed()
        online.value = isOnline
        val vm = ListsViewModel(
            modelFor, registry, kick = { kicks.incrementAndGet() }, isOnline = online,
            delayer = { gate.await() }, now = { now }, newId = { "created" }, scope = scope,
        )
        rule.setContent {
            AbodeTheme {
                ListsScreenHost(vm, onOpen = { opened += it }) { stores, failed, onSave, onClose ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { NewListSheetContent(stores, failed, onSave, onClose) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String, unmerged: Boolean = false) = rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(5_000) { !exists(tag) }

    /** The TalkBack action of the same name; swiping itself is the platform's. */
    private fun customAction(tag: String, label: String) {
        val action = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
    }

    private fun populated() {
        seedStore()
        seedList("l1", "Groceries", storeId = "s1")
        seedItems("l1", total = 12, checked = 3)
        seedList("l2", "Hardware", at = now.plusSeconds(1))
        seedList("l3", "Old", at = now.plusSeconds(2), archivedAt = "2026-11-01T00:00:00.000Z")
    }

    @Test
    fun `AC1 empty shows Make your first list and no FAB`() {
        show()
        await("lists.empty")
        rule.onNodeWithText("Make your first list").assertIsDisplayed()
        assertFalse(exists("lists.fab"))
        rule.onNodeWithTag("lists.new").assertIsEnabled()
    }

    @Test
    fun `AC2 populated rows show name, store chip and the checked count`() {
        show(seed = ::populated)
        await("lists.row.Groceries")
        rule.onNodeWithTag("lists.row.Groceries").assertContentDescriptionEquals("Groceries, Test Market, 3 of 12")
        rule.onNodeWithTag("lists.row.Hardware").assertContentDescriptionEquals("Hardware, No items")
        // The chip is on the list with a store only.
        rule.onAllNodesWithTag("lists.row.store", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText("Test Market", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText("Templates").assertCountEquals(0)
        rule.onNodeWithTag("lists.fab").assertIsDisplayed()
        assertFalse(exists("empty-state.action"))
    }

    @Test
    fun `a template gets its own section`() {
        show(seed = {
            seedList("l1", "Groceries")
            seedList("t1", "Weekly", at = now.plusSeconds(1))
            runBlocking { registry.patch(household, "shopping_list", "t1", buildJsonObject { put("is_template", JsonPrimitive(true)) }, now) }
        })
        await("lists.row.Weekly")
        rule.onNodeWithText("Templates").assertIsDisplayed()
    }

    @Test
    fun `Archived is collapsed with its count and opens on tap`() {
        show(seed = ::populated)
        await("lists.archived.toggle")
        rule.onNodeWithText("Archived (1)").assertIsDisplayed()
        assertFalse(exists("lists.archived.row.Old"))
        rule.onNodeWithTag("lists.archived.toggle").performClick()
        await("lists.archived.row.Old")
        rule.onNodeWithTag("lists.archived.row.Old").assertContentDescriptionEquals("Old, No items")
    }

    @Test
    fun `offline shows the banner and the screen works the same`() {
        show(isOnline = false, seed = ::populated)
        await("lists.row.Groceries")
        rule.onNodeWithTag("offline-banner").assertIsDisplayed()
        rule.onNodeWithText("You're offline. Changes will sync when you reconnect.").assertIsDisplayed()
        rule.onNodeWithTag("lists.new").assertIsEnabled()
        rule.onNodeWithTag("lists.fab").assertIsEnabled()
    }

    @Test
    fun `online shows no banner`() {
        show(seed = ::populated)
        await("lists.row.Groceries")
        assertFalse(exists("offline-banner"))
    }

    @Test
    fun `tapping a row opens that list`() {
        show(seed = ::populated)
        await("lists.row.Groceries")
        rule.onNodeWithTag("lists.row.Groceries").performClick()
        assertEquals(listOf("l1"), opened.toList())
    }

    @Test
    fun `AC3 archiving hides the row at once, shows the toast, and writes when the window ends`() {
        show(seed = ::populated)
        await("lists.row.Groceries")
        val before = outboxSize()
        customAction("lists.row.Groceries", "Archive")
        await("undo-toast")
        rule.onNodeWithText("Archived Groceries").assertIsDisplayed()
        assertFalse(exists("lists.row.Groceries"))
        assertEquals(before, outboxSize())

        gate.complete(Unit)
        awaitGone("undo-toast")
        assertEquals(before + 1, outboxSize())
        assertFalse(exists("lists.row.Groceries"))
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Archived (2)").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `AC4 Undo brings the row back and writes nothing`() {
        show(seed = ::populated)
        await("lists.row.Groceries")
        val before = outboxSize()
        customAction("lists.row.Groceries", "Archive")
        await("undo-toast")
        rule.onNodeWithTag("undo-toast.undo").performClick()
        await("lists.row.Groceries")
        assertFalse(exists("undo-toast"))
        gate.complete(Unit)
        rule.waitForIdle()
        assertEquals(before, outboxSize())
        assertTrue(exists("lists.row.Groceries"))
    }

    @Test
    fun `an Archived row offers Restore and Delete, and Restore brings it back to Lists`() {
        show(seed = ::populated)
        await("lists.archived.toggle")
        rule.onNodeWithTag("lists.archived.toggle").performClick()
        await("lists.archived.row.Old")
        rule.onNodeWithTag("lists.restore.Old").assertIsDisplayed()
        val labels = rule.onNodeWithTag("lists.archived.row.Old").fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Restore", "Delete"), labels)

        rule.onNodeWithTag("lists.restore.Old").performClick()
        await("lists.row.Old")
        awaitGone("lists.archived.toggle")
    }

    @Test
    fun `deleting an Archived row shows the Deleted toast with Undo`() {
        show(seed = ::populated)
        await("lists.archived.toggle")
        rule.onNodeWithTag("lists.archived.toggle").performClick()
        await("lists.archived.row.Old")
        customAction("lists.archived.row.Old", "Delete")
        await("undo-toast")
        rule.onNodeWithText("Deleted Old").assertIsDisplayed()
        assertFalse(exists("lists.archived.row.Old"))
    }

    @Test
    fun `plus opens the sheet with the name focused`() {
        show()
        await("lists.empty")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.sheet")
        rule.waitForIdle()
        rule.onNodeWithTag("newlist.name").assertIsFocused()
    }

    @Test
    fun `the FAB and the empty state button open the sheet too`() {
        show(seed = ::populated)
        await("lists.fab")
        rule.onNodeWithTag("lists.fab").performClick()
        await("newlist.sheet")
    }

    @Test
    fun `the empty state button opens the sheet`() {
        show()
        await("empty-state.action")
        rule.onNodeWithTag("empty-state.action").performClick()
        await("newlist.sheet")
    }

    @Test
    fun `Save is off while the trimmed name is blank`() {
        show()
        await("lists.empty")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.sheet")
        rule.onNodeWithTag("newlist.save").assertIsNotEnabled()
        rule.onNodeWithTag("newlist.name").performTextInput("   ")
        rule.onNodeWithTag("newlist.save").assertIsNotEnabled()
        rule.onNodeWithTag("newlist.name").performTextInput("Hardware")
        rule.onNodeWithTag("newlist.save").assertIsEnabled()
    }

    @Test
    fun `the store picker shows only when the household has stores`() {
        show()
        await("lists.empty")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.sheet")
        assertFalse(exists("newlist.store"))
    }

    @Test
    fun `the store picker shows with stores`() {
        show(seed = ::seedStore)
        await("lists.empty")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.store")
    }

    @Test
    fun `AC5 Save creates the list, closes the sheet and opens the list`() {
        show()
        await("lists.empty")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.sheet")
        rule.onNodeWithTag("newlist.name").performTextInput("  Hardware ")
        rule.onNodeWithTag("newlist.save").performClick()
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("created"), opened.toList())
        awaitGone("newlist.sheet")
        assertEquals(1, outboxSize())
        assertEquals(1, kicks.get())
        await("lists.row.Hardware")
    }

    @Test
    fun `Cancel closes the sheet and writes nothing`() {
        show()
        await("lists.empty")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.sheet")
        rule.onNodeWithTag("newlist.name").performTextInput("Hardware")
        rule.onNodeWithTag("newlist.cancel").performClick()
        awaitGone("newlist.sheet")
        assertEquals(0, outboxSize())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a save that fails keeps the sheet open with the message and Save usable again`() {
        // The model is for another household than the open one, so its write throws like a switch mid-write.
        show(modelFor = uuid(2))
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.sheet")
        rule.onNodeWithTag("newlist.name").performTextInput("Hardware")
        rule.onNodeWithTag("newlist.save").performClick()
        await("newlist.error")
        rule.onNodeWithText("Could not save the list. Try again.").assertIsDisplayed()
        rule.onNodeWithTag("newlist.save").assertIsEnabled()
        assertTrue(exists("lists.error"))
        assertTrue(opened.isEmpty())
    }
}
