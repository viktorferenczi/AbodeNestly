package com.abodenestly.shopping

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.liveListItems
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * DESIGN C1 and C2 for SHOP-11 (TESTING §7), on a real Room file: the Template switch, the Templates section, the
 * template's menu and caption, and the "Add all to…" sheet. The sheet's body is mounted inline (Robolectric never drives
 * the popup). The status line's clock is a gate the test opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class TemplatesComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-28T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = CompletableDeferred<Unit>()
    private val kicks = AtomicInteger()
    private val ids = AtomicInteger()
    private val opened = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(15_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(15_000) { !exists(tag) }

    private fun seedItem(id: String, listId: String, name: String, at: String) = runBlocking {
        registry.withSearchDao { it.upsertListItem(ListItemEntity(id, hid, listId, name, nameKey = name.lowercase(), checked = false, createdAt = at)) }
    }

    private fun seedTemplate(withItems: Boolean) = runBlocking {
        createList(registry, household, "Weekly", null, now.plusSeconds(1), "t1", isTemplate = true)
        if (withItems) {
            seedItem("a", "t1", "Milk", "2026-11-01T00:00:01.000Z")
            seedItem("b", "t1", "Eggs", "2026-11-01T00:00:02.000Z")
        }
    }

    private fun showLists() {
        runBlocking { registry.open(household) }
        val vm = ListsViewModel(
            household, registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
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

    private fun showDetail(listId: String) {
        val vm = ListDetailViewModel(
            household, listId, uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = MutableStateFlow(true),
            delayer = { gate.await() }, now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        rule.setContent {
            AbodeTheme {
                ListDetailScreenHost(
                    vm, onBack = {},
                    addAllSheet = { targets, failed, onPick, onClose ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { AddAllSheetContent(targets, failed, onPick, onClose) }
                    },
                ) { _, _, _, _, _, _, _ -> }
            }
        }
        rule.waitForIdle()
    }

    private fun openAddAll() {
        await("list.actions")
        rule.onNodeWithTag("list.actions").performClick()
        await("list.actions.addAll")
        rule.onNodeWithTag("list.actions.addAll").performClick()
        await("addall.sheet")
    }

    private fun live(listId: String) = runBlocking { registry.liveListItems(household, listId) }

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    @Test
    fun `the Template switch is off by default`() {
        showLists()
        await("lists.new")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.template")
        rule.onNodeWithTag("newlist.template").assertIsOff()
        rule.onNodeWithTag("newlist.template").performClick()
        rule.onNodeWithTag("newlist.template").assertIsOn()
    }

    @Test
    fun `AC1 Template on and Save files the list under Templates and not under Lists`() {
        showLists()
        await("lists.new")
        rule.onNodeWithTag("lists.new").performClick()
        await("newlist.name")
        rule.onNodeWithTag("newlist.name").performTextInput("Meal prep")
        rule.onNodeWithTag("newlist.template").performClick()
        rule.onNodeWithTag("newlist.save").performClick()

        rule.waitUntil(15_000) { opened.isNotEmpty() }
        assertEquals(listOf("created"), opened.toList())
        val sections = ListsSections.make(runBlocking { registry.observeListOverview(household).first() })
        assertEquals(listOf("Meal prep"), sections.templates.map { it.name })
        assertEquals(emptyList<String>(), sections.lists.map { it.name })
        await("lists.fab")
        rule.onNodeWithText("Templates").assertIsDisplayed()
        rule.onNodeWithText("Meal prep").assertIsDisplayed()
    }

    @Test
    fun `a template shows its caption and Add all to list even when nothing is checked, a normal list has none`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        seedTemplate(withItems = false)
        showDetail("t1")
        await("list.templateCaption")
        rule.onNodeWithText("Template").assertIsDisplayed()
        await("list.actions")
        rule.onNodeWithTag("list.actions").performClick()
        await("list.actions.addAll")
        assertFalse(exists("list.actions.clearChecked"))
    }

    @Test
    fun `a normal list has no caption, no menu and no Add all`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        showDetail("l1")
        await("list.title")
        assertFalse(exists("list.templateCaption"))
        assertFalse(exists("list.actions"))
    }

    @Test
    fun `AC2 picking Groceries copies the items, updates its count and shows the status`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
            createList(registry, household, "Hardware", null, now.plusSeconds(2), "l2")
        }
        seedTemplate(withItems = true)
        showDetail("t1")
        openAddAll()
        // Oldest first.
        await("addall.row.l1")
        await("addall.row.l2")
        rule.onNodeWithTag("addall.row.l1").performClick()

        await("list.status")
        rule.onNodeWithTag("list.status").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        rule.onNodeWithTag("list.status").assert(liveRegion(LiveRegionMode.Polite))
        rule.onNodeWithText("Added 2 items to Groceries").assertIsDisplayed()
        awaitGone("addall.sheet")
        assertEquals(listOf("Eggs", "Milk"), live("l1").map { it.name }.sorted())
        assertEquals(0, live("l2").size)
        assertEquals(2, runBlocking { registry.observeListOverview(household).first() }.counts["l1"]!!.all)
        assertEquals(1, kicks.get())
        // The status clears when its window ends, with no Undo.
        assertFalse(exists("undo-toast"))
        gate.complete(Unit)
        awaitGone("list.status")
    }

    @Test
    fun `Make a list first shows when there is no list to add to`() {
        seedEmptyHousehold()
        seedTemplate(withItems = true)
        showDetail("t1")
        openAddAll()
        rule.onNodeWithTag("addall.none").assertIsDisplayed()
        rule.onNodeWithText("Make a list first").assertIsDisplayed()
    }

    private fun seedEmptyHousehold() = runBlocking { registry.open(household) }

    @Test
    fun `AC3 an empty template says there is nothing to add and leaves the list unchanged`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        seedTemplate(withItems = false)
        showDetail("t1")
        openAddAll()
        await("addall.row.l1")
        rule.onNodeWithTag("addall.row.l1").performClick()

        await("list.status")
        rule.onNodeWithText("Nothing to add: this template is empty").assertIsDisplayed()
        assertEquals(0, live("l1").size)
        assertEquals(0, kicks.get())
        awaitGone("addall.sheet")
    }

    @Test
    fun `a failed copy keeps the sheet open with an assertive message and never kicks`() {
        runBlocking {
            registry.open(household)
            createList(registry, household, "Groceries", null, now, "l1")
        }
        seedTemplate(withItems = true)
        showDetail("t1")
        openAddAll()
        await("addall.row.l1")
        // The database closes under the screen: the copy cannot be stored.
        runBlocking { registry.close() }
        rule.onNodeWithTag("addall.row.l1").performClick()

        await("addall.error")
        rule.onNodeWithText("Could not add the items. Try again.").assertIsDisplayed()
        rule.onNodeWithTag("addall.error").assert(liveRegion(LiveRegionMode.Assertive))
        assertFalse(exists("list.status"))
        assertEquals(0, kicks.get())
    }
}
