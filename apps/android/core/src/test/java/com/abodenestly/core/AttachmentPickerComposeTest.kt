package com.abodenestly.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.SignedUpload
import com.abodenestly.core.attachments.AttachmentEntity
import com.abodenestly.core.attachments.AttachmentPicker
import com.abodenestly.core.attachments.AttachmentPickerSection
import com.abodenestly.core.attachments.NoAttachmentLoader
import com.abodenestly.core.attachments.PickedFile
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

/** One case per State and Interaction of the attachment control (TESTING §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class AttachmentPickerComposeTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val api = FakeApiService()
    private val householdId = UUID.randomUUID()
    private val entityId = UUID.randomUUID()
    private var online by mutableStateOf(true)
    private var enabled by mutableStateOf(true)
    private var photoPicks = 0
    private var pdfPicks = 0
    private var entity = AttachmentEntity.PantryItem
    private var nextPhoto: PickedFile? = PickedFile(JPEG, "milk.jpg", "image/jpeg")
    private var nextPdf: PickedFile? = PickedFile("%PDF".toByteArray(), "warranty.pdf", "application/pdf")

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private val photoPicker: @Composable (onPicked: (PickedFile?) -> Unit) -> (() -> Unit) = { onPicked ->
        { photoPicks++; onPicked(nextPhoto) }
    }
    private val pdfPicker: @Composable (onPicked: (PickedFile?) -> Unit) -> (() -> Unit) = { onPicked ->
        { pdfPicks++; onPicked(nextPdf) }
    }

    private fun signed(path: String = "households/h/pantry_item/e/a.jpg") =
        ApiResult.Success(SignedUpload(UUID.randomUUID(), "https://upload.test/o", path))

    private fun show() {
        runBlocking { registry.open(householdId) }
        val picker = AttachmentPicker(householdId, entity, entityId, Fixtures.userId, registry, api, isOnline = { online })
        rule.setContent {
            AbodeTheme {
                AttachmentPickerSection(
                    picker, registry, householdId, entity, entityId, enabled, online, NoAttachmentLoader,
                    photoPicker = photoPicker, pdfPicker = pdfPicker,
                )
            }
        }
        rule.waitForIdle()
    }

    // 15 s, not 5: the CI android job runs these beside other modules' lint and tests, which can starve the clock.
    private fun until(condition: () -> Boolean) = rule.waitUntil(15_000) { rule.waitForIdle(); condition() }
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `CORE-11 idle shows both add buttons and no items`() {
        show()
        rule.onNodeWithTag("attachments.photo").assertIsEnabled()
        rule.onNodeWithTag("attachments.pdf").assertIsEnabled()
        assertTrue(exists("attachments.idle"))
        assertEquals(0, count("attachments.item"))
    }

    @Test
    fun `CORE-11 AC1 picking a photo shows uploading, signs and PUTs the bytes`() {
        api.signResult = signed()
        api.hold()
        show()
        rule.onNodeWithTag("attachments.photo").performClick()
        until { exists("attachments.uploading") }
        rule.onNodeWithTag("attachments.uploading").assert(hasText("Uploading…"))
        rule.onNodeWithTag("attachments.photo").assertIsNotEnabled()
        assertEquals(1, photoPicks)

        api.release()
        until { exists("attachments.idle") }
        assertEquals(listOf("SignAttachment", "Upload"), api.calls.map { it::class.simpleName })
    }

    @Test
    fun `CORE-11 AC2 after the upload the control is idle and lists the photo`() {
        api.signResult = signed()
        show()
        rule.onNodeWithTag("attachments.photo").performClick()
        until { count("attachments.item") == 1 }
        // The item is listed a frame before the control leaves "uploading", so wait for idle instead of asserting it.
        until { exists("attachments.idle") }
    }

    @Test
    fun `CORE-11 AC3 a PDF on a log entry is listed as a filename row`() {
        entity = AttachmentEntity.MaintLog
        api.signResult = signed("households/h/maint_log/e/manual.pdf")
        show()
        rule.onNodeWithTag("attachments.pdf").performClick()
        until { count("attachments.item") == 1 }
        rule.onAllNodesWithText("manual.pdf").assertCountEquals(1)
        assertEquals("maint_log", (api.calls.first() as FakeApiService.Call.SignAttachment).entity)
        assertEquals(1, pdfPicks)
    }

    @Test
    fun `CORE-11 AC4 offline the pick is queued, sign is not called, and it uploads on reconnect`() {
        online = false
        api.signResult = signed()
        show()
        rule.onNodeWithTag("attachments.photo").performClick()
        until { exists("attachments.queued") }
        rule.onNodeWithTag("attachments.queued").assert(hasText("Will upload when you're online"))
        assertEquals(1, count("attachments.queuedItem"))
        assertTrue(api.calls.isEmpty())

        online = true
        until { exists("attachments.idle") }
        assertEquals(listOf("SignAttachment", "Upload"), api.calls.map { it::class.simpleName })
        assertEquals(0, count("attachments.queuedItem"))
        assertEquals(1, count("attachments.item"))
    }

    @Test
    fun `CORE-11 AC5 a sign failure shows an error, records nothing, and Retry succeeds`() {
        api.signResult = ApiResult.Failure(ApiFailure.Unknown)
        show()
        rule.onNodeWithTag("attachments.photo").performClick()
        until { exists("attachments.error") }
        rule.onNodeWithTag("attachments.error").assert(hasText(AttachmentPicker.FAILED))
        assertEquals(0, count("attachments.item"))

        api.signResult = signed()
        rule.onNodeWithTag("attachments.retry").performClick()
        until { exists("attachments.idle") }
        assertEquals(2, api.calls.count { it is FakeApiService.Call.SignAttachment })
        assertEquals(1, count("attachments.item"))
    }

    @Test
    fun `CORE-11 a file the API would reject is refused without a request`() {
        nextPhoto = PickedFile(JPEG, "a.gif", "image/gif")
        show()
        rule.onNodeWithTag("attachments.photo").performClick()
        until { exists("attachments.error") }
        rule.onNodeWithTag("attachments.error").assert(hasText(AttachmentPicker.REJECTED))
        assertEquals(0, count("attachments.retry"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `CORE-11 module off renders nothing`() {
        enabled = false
        show()
        assertEquals(0, count("attachments"))
        assertEquals(0, count("attachments.photo"))
        assertEquals(0, count("attachments.pdf"))
    }
}
