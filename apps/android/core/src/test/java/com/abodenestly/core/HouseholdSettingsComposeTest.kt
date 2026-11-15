package com.abodenestly.core

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.abodenestly.contract.models.Member
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.api.HouseholdWithRole
import com.abodenestly.core.settings.HouseholdSettingsContent
import com.abodenestly.core.settings.HouseholdSettingsScreen
import com.abodenestly.core.settings.HouseholdSettingsViewModel
import com.abodenestly.core.settings.LeaveSheetContent
import com.abodenestly.core.settings.InMemoryHouseholdCache
import com.abodenestly.ui.AbodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One case per State and Interaction of DESIGN B5 (TESTING §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class HouseholdSettingsComposeTest {
    @get:Rule val rule = createComposeRule()

    private val api = FakeApiService()
    private val cache = InMemoryHouseholdCache()
    private val home = Fixtures.household(1, "Test Home")
    private var online by mutableStateOf(true)
    private var left = 0

    /** Like [HouseholdSettingsScreen], but the Leave sheet body is inline: Robolectric cannot click inside the popup. */
    private fun showWithInlineSheet() {
        api.fetchHouseholdResult = ApiResult.Success(HouseholdWithRole(home, Member.Role.owner))
        val vm = HouseholdSettingsViewModel(
            home.id, Fixtures.userId, api, cache, isOnline = { online }, onHouseholdChanged = {}, onLeft = { left++ },
        )
        rule.setContent {
            AbodeTheme {
                val state by vm.state.collectAsState()
                LaunchedEffect(Unit) { vm.load() }
                Column {
                    HouseholdSettingsContent(state, online, vm::onEvent, Modifier.weight(1f))
                    if (state.confirmingLeave) LeaveSheetContent(state, online, vm::onEvent)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun show(role: Member.Role = Member.Role.owner) {
        api.fetchHouseholdResult = ApiResult.Success(HouseholdWithRole(home, role))
        rule.setContent {
            AbodeTheme {
                HouseholdSettingsScreen(
                    home.id, Fixtures.userId, api, cache, isOnline = online,
                    onHouseholdChanged = {}, onLeft = { left++ },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `CORE-05 - AC1 owner sees the fields enabled`() {
        show()
        rule.onNodeWithTag("settings.name").assertIsEnabled()
        rule.onNodeWithTag("settings.module.pantry").assertIsEnabled().assertIsOn()
        rule.onNodeWithTag("settings.currency").assertIsEnabled().assertTextContains("USD")
        rule.onNodeWithTag("settings.leave").assertIsEnabled()
        rule.onNodeWithTag("offline-banner").assertDoesNotExist()
    }

    @Test
    fun `CORE-05 - AC2 member sees the fields read-only but can leave`() {
        show(Member.Role.member)
        rule.onNodeWithTag("settings.name").assertIsNotEnabled()
        rule.onNodeWithTag("settings.module.shopping").assertIsNotEnabled()
        rule.onNodeWithTag("settings.currency").assertIsNotEnabled()
        rule.onNodeWithTag("settings.leave").assertIsEnabled()
        rule.onNodeWithTag("settings.module.pantry").performClick()
        assertTrue(api.calls.none { it is Call.UpdateModules })
    }

    @Test
    fun `CORE-05 - AC3 saving shows Saving while the PATCH is held`() {
        show()
        api.updateModulesResult = ApiResult.Success(home.copy(modulePantry = false))
        api.hold()
        rule.onNodeWithTag("settings.module.pantry").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings.saving").assertTextEquals("Saving…")
        // The optimistic value is on screen and the controls are locked meanwhile.
        rule.onNodeWithTag("settings.module.pantry").assertIsNotEnabled()
        api.release()
        rule.waitForIdle()
        rule.onNodeWithTag("settings.saving").assertDoesNotExist()
        rule.onNodeWithTag("settings.module.pantry").assertIsEnabled()
    }

    @Test
    fun `CORE-05 - AC4 offline disables the switches and shows the banner`() {
        show()
        online = false
        rule.waitForIdle()
        rule.onNodeWithTag("offline-banner").assertExists()
        rule.onNodeWithText("You're offline. Connect to change household settings.").assertExists()
        rule.onNodeWithTag("settings.module.pantry").assertIsNotEnabled()
        rule.onNodeWithTag("settings.name").assertIsNotEnabled()
        rule.onNodeWithTag("settings.leave").assertIsNotEnabled()
    }

    @Test
    fun `toggling a row calls the API`() {
        show()
        api.updateModulesResult = ApiResult.Success(home.copy(moduleShopping = false))
        rule.onNodeWithTag("settings.module.shopping").performClick()
        rule.waitForIdle()
        assertEquals(Call.UpdateModules(home.id, false, null, null), api.calls.last())
    }

    @Test
    fun `a failed save shows the error`() {
        show()
        api.updateModulesResult = ApiResult.Failure(ApiFailure.Network)
        rule.onNodeWithTag("settings.module.pantry").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings.error").assertTextEquals("Couldn't save. Check your connection and try again.")
    }

    @Test
    fun `editing the name and pressing Done saves it`() {
        show()
        api.updateHouseholdResult = ApiResult.Success(home.copy(name = "Casa"))
        rule.onNodeWithTag("settings.name").performTextClearance()
        rule.onNodeWithTag("settings.name").performTextInput("Casa")
        rule.onNodeWithTag("settings.name").performImeAction()
        rule.waitForIdle()
        assertEquals(Call.UpdateHousehold(home.id, "Casa", null), api.calls.last())
    }

    @Test
    fun `tapping a switch with an uncommitted name saves both`() {
        show()
        api.updateHouseholdResult = ApiResult.Success(home.copy(name = "Casa"))
        api.updateModulesResult = ApiResult.Success(home.copy(name = "Casa", modulePantry = false))
        rule.onNodeWithTag("settings.name").performTextClearance()
        rule.onNodeWithTag("settings.name").performTextInput("Casa")
        rule.onNodeWithTag("settings.module.pantry").performClick()
        rule.waitForIdle()
        val writes = api.calls.filter { it is Call.UpdateHousehold || it is Call.UpdateModules }
        assertEquals(listOf<Call>(Call.UpdateHousehold(home.id, "Casa", null), Call.UpdateModules(home.id, null, false, null)), writes)
    }

    @Test
    fun `picking a currency saves it`() {
        show()
        api.updateHouseholdResult = ApiResult.Success(home.copy(currency = "EUR"))
        rule.onNodeWithTag("settings.currency").performClick()
        rule.onNodeWithTag("settings.currency.EUR").performClick()
        rule.waitForIdle()
        assertEquals(Call.UpdateHousehold(home.id, null, "EUR"), api.calls.last())
    }

    @Test
    fun `CORE-05 - AC5 Leave, confirm calls removeMember and onLeft`() {
        showWithInlineSheet()
        rule.onNodeWithTag("settings.leave").performClick()
        rule.onNodeWithText("Leave Test Home?").assertExists()
        rule.onNodeWithTag("settings.leave.confirm").assertIsEnabled()
        api.removeResult = ApiResult.Success(Unit)
        rule.onNodeWithTag("settings.leave.confirm").performClick()
        rule.waitForIdle()
        assertEquals(Call.RemoveMember(home.id, Fixtures.userId), api.calls.last())
        assertEquals(1, left)
    }

    @Test
    fun `Cancel closes the sheet`() {
        showWithInlineSheet()
        rule.onNodeWithTag("settings.leave").performClick()
        rule.onNodeWithTag("settings.leave.cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings.leave.sheet").assertDoesNotExist()
        assertTrue(api.calls.none { it is Call.RemoveMember })
    }

    @Test
    fun `last_owner shows its error inside the sheet`() {
        showWithInlineSheet()
        api.removeResult = ApiResult.Failure(ApiFailure.LastOwner)
        rule.onNodeWithTag("settings.leave").performClick()
        rule.onNodeWithTag("settings.leave.confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings.leave.error")
            .assertTextEquals("You're the last owner. Make someone else an owner before leaving.")
        rule.onNodeWithTag("settings.error").assertDoesNotExist()
        assertEquals(0, left)
    }
}
