package com.abodenestly.core

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.sync.SyncSheetContent
import com.abodenestly.core.sync.SyncSheetViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncPushResult
import com.abodenestly.sync.SyncRejected
import com.abodenestly.sync.SyncResult
import com.abodenestly.sync.upsert
import com.abodenestly.ui.AbodeTheme
import com.abodenestly.ui.SyncStatusChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * E2E for the B12 journey that exists before any list screen (same scope as iOS `SyncSheetUITests`): one rejected
 * `list_item` write, the chip reads "1 to sync", the sheet offers Retry and Discard, Discard clears the chip.
 * Runs on the device against its real Room file with a rejecting transport, so it needs no backend. The add/check
 * journey (E2E-SHOP-2) moves to SHOP-02/04-android.
 */
class SyncSheetE2ETest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = DatabaseRegistry(context)
    private val household = UUID.randomUUID()

    @After
    fun cleanUp() = runBlocking {
        registry.close()
        context.deleteDatabase(registry.fileName(household))
    }

    @Test
    fun testSHOP08_rejectedWriteShowsInChipAndSheet_discardClearsIt() {
        runBlocking {
            registry.open(household)
            registry.upsert(household, "list_item", buildJsonObject { put("id", UUID.randomUUID().toString()); put("list_id", UUID.randomUUID().toString()); put("name", "Milk") })
        }
        val api = FakeApiService()
        val engine = LiveSyncEngine(api, registry, isOnline = { true }, scope = CoroutineScope(Dispatchers.Default))
        val vm = SyncSheetViewModel(household, registry, engine, MutableStateFlow(true), CoroutineScope(Dispatchers.Default))
        rule.setContent {
            AbodeTheme {
                Column {
                    val state by vm.state.collectAsState()
                    SyncStatusChip(state.chip, onClick = {})
                    SyncSheetContent(vm)
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("1 to sync").fetchSemanticsNodes().isNotEmpty() }

        api.pushResult = SyncResult.Success(SyncPushResult(rejected = runBlocking { registry.outboxForTesting() }.map { SyncRejected(it.id, "validation") }))
        rule.onNodeWithTag("sync.now").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("list_item · save · Couldn't save: invalid").fetchSemanticsNodes().isNotEmpty() }
        val seq = vm.state.value.failed.single().seq
        rule.onNodeWithTag("sync.retry.$seq").assertIsDisplayed()

        rule.onNodeWithTag("sync.discard.$seq").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("sync.chip").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, vm.state.value.failed.size)
    }
}
