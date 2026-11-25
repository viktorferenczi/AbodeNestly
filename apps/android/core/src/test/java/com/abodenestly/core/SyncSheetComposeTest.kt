package com.abodenestly.core

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.Fixtures.id
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.sync.SyncSheetContent
import com.abodenestly.core.sync.SyncSheetViewModel
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.sync.LiveSyncEngine
import com.abodenestly.sync.SyncPhase
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One case per State and Interaction of DESIGN B12 (TESTING §7). Tests the sheet's content, not the bottom-sheet popup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class SyncSheetComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val household = id(1)
    private val engine = FakeSyncEngine()
    private val online = MutableStateFlow(true)
    private var chipClicks = 0

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun row(n: Int) = buildJsonObject { put("id", "i$n"); put("list_id", "l1"); put("name", "Item $n") }

    private fun show(queued: Int = 0, rejected: Boolean = false) {
        runBlocking {
            registry.open(household)
            repeat(queued) { registry.upsert(household, "list_item", row(it)) }
            if (rejected) {
                registry.upsert(household, "list_item", row(99))
                val api = FakeApiService()
                api.pushResult = SyncResult.Success(SyncPushResult(rejected = registry.outboxForTesting().map { SyncRejected(it.id, "validation") }))
                LiveSyncEngine(api, registry, isOnline = { true }, scope = CoroutineScope(Dispatchers.Unconfined)).run()
            }
        }
        vm = SyncSheetViewModel(household, registry, engine, online, CoroutineScope(Dispatchers.Default))
        rule.setContent {
            AbodeTheme {
                Column {
                    val state by vm.state.collectAsState()
                    SyncStatusChip(state.chip, onClick = { chipClicks++ })
                    SyncSheetContent(vm)
                }
            }
        }
    }

    private lateinit var vm: SyncSheetViewModel

    private fun failedSeq(): Long = vm.state.value.failed.single().seq

    private fun waitForText(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `idle - up to date, nothing waiting, no chip`() {
        show()
        rule.onNodeWithText("Up to date").assertIsDisplayed()
        rule.onNodeWithTag("sync.pending").assertTextContains("Pending changes: 0")
        rule.onNodeWithTag("sync.failedCount").assertTextContains("Failed changes: 0")
        rule.onAllNodesWithTag("sync.failed.row").assertCountEquals(0)
        rule.onAllNodesWithTag("sync.chip").assertCountEquals(0)
    }

    @Test
    fun `AC4 - pending - the chip reads 3 to sync and the sheet counts them`() {
        show(queued = 3)
        waitForText("3 to sync")
        rule.onNodeWithTag("sync.chip").assertIsDisplayed()
        rule.onNodeWithTag("sync.pending").assertTextContains("Pending changes: 3")
        rule.onNodeWithTag("sync.chip").performClick()
        assertEquals(1, chipClicks)
    }

    @Test
    fun `AC3 - failed - the row names the table, action and reason, with Retry and Discard`() {
        show(rejected = true)
        waitForText("list_item · save · Couldn't save: invalid")
        rule.onNodeWithTag("sync.failedCount").assertTextContains("Failed changes: 1")
        rule.onNodeWithTag("sync.retry.${failedSeq()}").performClick()
        rule.waitUntil(5_000) { engine.retried.isNotEmpty() }
        rule.onNodeWithTag("sync.discard.${failedSeq()}").performClick()
        rule.waitUntil(5_000) { engine.discarded.isNotEmpty() }
        val seq = engine.retried.single()
        assertEquals(listOf(seq), engine.discarded.toList())
    }

    @Test
    fun `AC5 - Sync now calls kick`() {
        show()
        rule.onNodeWithTag("sync.now").performClick()
        rule.waitUntil(5_000) { engine.kicks.get() == 1 }
        assertEquals(1, engine.kicks.get())
    }

    @Test
    fun `syncing shows the chip and the status line`() {
        show()
        engine.phase.value = SyncPhase.Syncing
        waitForText("Syncing")
        rule.onNodeWithText("Syncing…").assertIsDisplayed()
    }

    @Test
    fun `offline shows the chip and says changes will sync later`() {
        online.value = false
        show(queued = 1)
        waitForText("Offline")
        rule.onNodeWithTag("sync.status").assertTextContains("Offline — changes will sync when you're back online")
        rule.onNodeWithTag("sync.pending").assertTextContains("Pending changes: 1")
    }
}
