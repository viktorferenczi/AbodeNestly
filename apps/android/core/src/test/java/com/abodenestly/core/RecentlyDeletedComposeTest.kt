package com.abodenestly.core

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.recentlydeleted.RecentlyDeletedContent
import com.abodenestly.core.recentlydeleted.RecentlyDeletedViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LocationEntity
import com.abodenestly.sync.SearchModule
import com.abodenestly.sync.StoreEntity
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
import java.time.Duration
import java.time.Instant

private val NOW: Instant = Instant.parse("2026-11-19T12:00:00Z")
private fun ago(days: Long) = NOW.minus(Duration.ofDays(days)).toString()
private val ALL_MODULES = SearchModule.entries.toSet()

/** One case per State and Interaction of DESIGN B11 (TESTING §7), over a real Room file with a fixed clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class RecentlyDeletedComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)
    private val sync = FakeSyncEngine()

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun show(modules: Set<SearchModule> = ALL_MODULES, seed: suspend (com.abodenestly.sync.SearchDao) -> Unit = {}) {
        runBlocking {
            registry.open(household)
            registry.withSearchDao(seed)
        }
        val vm = RecentlyDeletedViewModel(
            registry.deletedRows(household, modules), modules,
            restore = { table, rowId, now -> registry.restore(household, table, rowId, now) }, sync,
            now = { NOW }, scope = CoroutineScope(Dispatchers.Unconfined),
        )
        rule.setContent {
            AbodeTheme {
                val ui by vm.state.collectAsState()
                RecentlyDeletedContent(ui, vm::onEvent)
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) = rule.waitUntil(5_000) { exists(tag) }
    private fun awaitGone(tag: String) = rule.waitUntil(5_000) { !exists(tag) }
    private fun store(id: String, name: String, days: Long) = StoreEntity(id, household.toString(), name, ago(days))

    @Test
    fun `CORE-10 AC1 nothing deleted shows the empty state`() {
        show()
        await("deleted.empty")
        rule.onNodeWithTag("deleted.empty").assertTextEquals("Nothing deleted in the last 30 days.")
        assertTrue(!exists("deleted.row"))
    }

    @Test
    fun `CORE-10 AC2 a row deleted 2 days ago shows Restore and 28 days left`() {
        show { it.upsertStore(store("s1", "Lidl", 2)) }
        await("deleted.row")
        rule.onNodeWithText("Lidl").assertIsDisplayed()
        rule.onNodeWithText("28 days left").assertIsDisplayed()
        rule.onNodeWithTag("deleted.restore").assertIsDisplayed()
    }

    @Test
    fun `CORE-10 AC3 Restore queues one upsert with a null deleted_at, kicks sync and the row leaves`() {
        show { it.upsertStore(store("s1", "Lidl", 2)) }
        await("deleted.row")
        rule.onNodeWithTag("deleted.restore").performClick()
        await("deleted.empty")
        assertEquals(1, sync.kicks.get())
        val out = runBlocking { registry.outboxForTesting() }
        assertEquals(1, out.size)
        assertEquals("store", out[0].tableName)
        assertEquals("s1", out[0].rowId)
        assertTrue(out[0].data.contains(""""deleted_at":null"""))
    }

    @Test
    fun `CORE-10 AC4 a row deleted 31 days ago is not listed`() {
        show { it.upsertStore(store("s1", "Old", 31)) }
        await("deleted.empty")
        assertTrue(!exists("deleted.row"))
    }

    @Test
    fun `CORE-10 AC5 with pantry off the Pantry segment is hidden and Shopping still works`() {
        show(setOf(SearchModule.Shopping, SearchModule.Maintenance)) {
            it.upsertStore(store("s1", "Lidl", 1))
            it.upsertLocation(LocationEntity("p1", household.toString(), "Shelf", ago(1)))
        }
        await("deleted.row")
        rule.onNodeWithTag("deleted.segment.shopping").assertIsDisplayed()
        rule.onNodeWithTag("deleted.segment.maintenance").assertIsDisplayed()
        assertTrue(!exists("deleted.segment.pantry"))
        rule.onNodeWithText("Lidl").assertIsDisplayed()
        rule.onNodeWithTag("deleted.restore").performClick()
        await("deleted.empty")
    }

    @Test
    fun `CORE-10 segments switch modules and an empty segment shows a one-line note`() {
        show { it.upsertLocation(LocationEntity("p1", household.toString(), "Shelf", ago(1))) }
        await("deleted.segment.empty")
        rule.onAllNodesWithTag("deleted.row").assertCountEquals(0)
        rule.onNodeWithTag("deleted.segment.pantry").performClick()
        await("deleted.row")
        rule.onNodeWithText("Shelf").assertIsDisplayed()
        awaitGone("deleted.segment.empty")
    }
}
