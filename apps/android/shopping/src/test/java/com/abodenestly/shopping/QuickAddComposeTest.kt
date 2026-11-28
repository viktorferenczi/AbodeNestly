package com.abodenestly.shopping

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.ListItemEntity
import com.abodenestly.sync.StoreEntity
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * One case per State and Interaction of DESIGN C2's SHOP-02 slice (TESTING §7), on a real Room file. Waits poll with
 * `waitUntil`, because Room answers on its own threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class QuickAddComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val registry = DatabaseRegistry(context)
    private val household = uuid(1)
    private val hid = household.toString()
    private val now = Instant.parse("2026-11-26T08:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val online = MutableStateFlow(true)
    private val kicks = AtomicInteger()
    private val ids = AtomicInteger()
    private val backs = AtomicInteger()
    private lateinit var vm: ListDetailViewModel

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        registry.close()
    }

    private fun seedList(storeId: String? = null) = runBlocking {
        createList(registry, household, "Groceries", storeId, now, "l1")
    }

    private fun seedStore() = runBlocking {
        registry.withSearchDao { it.upsertStore(StoreEntity("s1", hid, "Test Market")) }
    }

    private fun seedItem(id: String, name: String, listId: String = "l1", checked: Boolean = false, createdAt: String = "2026-11-01T00:00:00.000Z") = runBlocking {
        registry.withSearchDao { it.upsertListItem(ListItemEntity(id, hid, listId, name, nameKey = name.lowercase(), checked = checked, createdAt = createdAt)) }
    }

    private fun show(isOnline: Boolean = true, seed: () -> Unit = { seedList() }) {
        runBlocking { registry.open(household) }
        seed()
        online.value = isOnline
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = online,
            now = { now }, newId = { "n${ids.incrementAndGet()}" }, scope = scope,
        )
        rule.setContent { AbodeTheme { ListDetailScreen(vm, onBack = { backs.incrementAndGet() }) } }
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }

    private fun awaitGone(tag: String) = rule.waitUntil(5_000) { !exists(tag) }

    private fun seedMilkElsewhere() {
        seedItem("old", "Milk", listId = "l2", checked = true)
    }

    @Test
    fun `empty shows the hint, a focused bar and Add off`() {
        show()
        await("list.empty")
        rule.onNodeWithText("Try: 2 l milk").assertIsDisplayed()
        rule.onNodeWithTag("list.title").assertIsDisplayed()
        rule.onNodeWithTag("quickadd.field").assertIsDisplayed()
        rule.onNodeWithTag("quickadd.add").assertIsNotEnabled()
        rule.waitForIdle()
        rule.onNodeWithTag("quickadd.field").assertIsFocused()
    }

    @Test
    fun `populated shows the title, the store chip, unchecked rows, and Checked collapsed until tapped`() {
        show(seed = {
            seedStore()
            seedList(storeId = "s1")
            seedItem("a", "Eggs", createdAt = "2026-11-01T00:00:00.000Z")
            seedItem("b", "Bread", createdAt = "2026-11-02T00:00:00.000Z")
            seedItem("c", "Butter", checked = true)
        })
        await("list.row.Eggs")
        rule.onNodeWithTag("list.title").assertIsDisplayed()
        rule.onNodeWithTag("list.store").assertIsDisplayed()
        rule.onNodeWithTag("list.row.Bread").assertIsDisplayed()
        assertFalse(exists("list.empty"))
        rule.onNodeWithText("Checked (1)").assertIsDisplayed()
        assertFalse(exists("list.checked.row.Butter"))
        rule.onNodeWithTag("list.checked").performClick()
        await("list.checked.row.Butter")
        rule.onNodeWithTag("list.checked.row.Butter").assertIsDisplayed()
        rule.onNodeWithTag("list.checked.row.Butter").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Checked"))
    }

    @Test
    fun `AC1 typing mi suggests Milk`() {
        show(seed = { seedList(); seedMilkElsewhere() })
        await("quickadd.field")
        rule.waitUntil(5_000) { vm.state.value.detail.history.isNotEmpty() }
        rule.onNodeWithTag("quickadd.field").performTextInput("mi")
        await("quickadd.suggestion.Milk")
        rule.onNodeWithTag("quickadd.suggestion.Milk").assertContentDescriptionEquals("Suggestion, Milk")
    }

    @Test
    fun `AC2 tapping the suggestion adds Milk and clears the field`() {
        show(seed = { seedList(); seedMilkElsewhere() })
        await("quickadd.field")
        rule.waitUntil(5_000) { vm.state.value.detail.history.isNotEmpty() }
        rule.onNodeWithTag("quickadd.field").performTextInput("mi")
        await("quickadd.suggestion.Milk")
        rule.onNodeWithTag("quickadd.suggestion.Milk").performClick()
        await("list.row.Milk")
        rule.waitUntil(5_000) { vm.state.value.query == "" }
        awaitGone("quickadd.suggestion.Milk")
        rule.waitForIdle()
        rule.onNodeWithTag("quickadd.field").assertIsFocused()
        assertEquals(listOf("Milk"), vm.state.value.unchecked.map { it.name })
    }

    @Test
    fun `AC3 a name with no history is added as typed with the IME action`() {
        show()
        await("quickadd.field")
        rule.onNodeWithTag("quickadd.field").performTextInput("Lightbulbs")
        rule.onNodeWithTag("quickadd.field").performImeAction()
        await("list.row.Lightbulbs")
        rule.waitUntil(5_000) { vm.state.value.query == "" }
        assertEquals(listOf("Lightbulbs"), vm.state.value.unchecked.map { it.name })
    }

    @Test
    fun `the Add button adds the typed text`() {
        show()
        await("quickadd.field")
        rule.onNodeWithTag("quickadd.field").performTextInput("Soap")
        rule.onNodeWithTag("quickadd.add").assertIsEnabled()
        rule.onNodeWithTag("quickadd.add").performClick()
        await("list.row.Soap")
        rule.waitUntil(5_000) { vm.state.value.query == "" }
        assertEquals(listOf("Soap"), vm.state.value.unchecked.map { it.name })
    }

    @Test
    fun `Add is off while the field is blank`() {
        show()
        await("quickadd.field")
        rule.onNodeWithTag("quickadd.add").assertIsNotEnabled()
        rule.onNodeWithTag("quickadd.field").performTextInput("   ")
        rule.onNodeWithTag("quickadd.add").assertIsNotEnabled()
        rule.onNodeWithTag("quickadd.field").performTextInput("Soap")
        rule.onNodeWithTag("quickadd.add").assertIsEnabled()
    }

    @Test
    fun `offline shows the banner and still adds from a suggestion`() {
        show(isOnline = false, seed = { seedList(); seedMilkElsewhere() })
        await("offline-banner")
        await("quickadd.field")
        rule.waitUntil(5_000) { vm.state.value.detail.history.isNotEmpty() }
        rule.onNodeWithTag("quickadd.field").performTextInput("mi")
        await("quickadd.suggestion.Milk")
        rule.onNodeWithTag("quickadd.add").assertIsEnabled()
        rule.onNodeWithTag("quickadd.suggestion.Milk").performClick()
        await("list.row.Milk")
        rule.waitUntil(5_000) { runBlocking { registry.outboxForTesting().count { it.tableName == "list_item" } } == 1 }
        assertEquals(1, runBlocking { registry.outboxForTesting().count { it.tableName == "list_item" } })
    }

    @Test
    fun `a write that cannot be stored shows the message under the title`() {
        runBlocking { registry.open(household) }
        seedList()
        vm = ListDetailViewModel(
            household, "l1", uuid(7), registry, kick = { kicks.incrementAndGet() }, isOnline = online,
            now = { now }, newId = { "n" }, scope = scope,
        )
        rule.setContent { AbodeTheme { ListDetailScreen(vm, onBack = {}) } }
        await("quickadd.field")
        // The database closes under the open screen (a switch away), so the write throws.
        runBlocking { registry.close() }
        rule.onNodeWithTag("quickadd.field").performTextInput("Soap")
        rule.onNodeWithTag("quickadd.add").performClick()
        await("list.error")
        rule.onNodeWithText(SAVE_ERROR).assertIsDisplayed()
        assertEquals("Soap", vm.state.value.query)
    }

    @Test
    fun `an unknown list shows This list is gone and no bar`() {
        show(seed = {})
        await("list.gone")
        rule.onNodeWithText("This list is gone").assertIsDisplayed()
        assertFalse(exists("quickadd.field"))
        rule.onNodeWithTag("list.back").assertIsDisplayed()
    }

    @Test
    fun `Back calls back`() {
        show()
        await("list.back")
        rule.onNodeWithTag("list.back").performClick()
        assertEquals(1, backs.get())
    }
}
