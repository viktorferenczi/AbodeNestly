package com.abodenestly.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.FakeApiService
import com.abodenestly.core.api.FakeApiService.Call
import com.abodenestly.core.api.SignedUpload
import com.abodenestly.core.attachments.AttachmentEntity
import com.abodenestly.core.attachments.AttachmentPicker
import com.abodenestly.core.attachments.AttachmentPickerState
import com.abodenestly.sync.DatabaseRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

/** CORE-11 pick flow over a real Room database (Robolectric) and a hand-written fake API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentPickerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = DatabaseRegistry(context)
    private val api = FakeApiService()
    private val householdId = UUID.randomUUID()
    private val entityId = UUID.randomUUID()
    private var online = true
    private val picker by lazy {
        runBlocking { registry.open(householdId) }
        AttachmentPicker(householdId, AttachmentEntity.PantryItem, entityId, Fixtures.userId, registry, api, isOnline = { online })
    }

    @After
    fun closeDatabase() = runBlocking { registry.close() }

    private fun signed(id: UUID = UUID.randomUUID()) =
        ApiResult.Success(SignedUpload(id, "https://upload.test/o", "households/h/pantry_item/e/a.jpg"))

    private fun pending() = runBlocking { registry.pendingAttachmentUploads(householdId) }
    private fun stored() = runBlocking { registry.attachments(householdId, "pantry_item", entityId) }
    private fun signs() = api.calls.filterIsInstance<Call.SignAttachment>()

    @Test
    fun `CORE-11 isAllowed mirrors the API limits`() {
        val mb = 1024 * 1024
        assertTrue(AttachmentPicker.isAllowed("image/jpeg", 1))
        assertTrue(AttachmentPicker.isAllowed("IMAGE/PNG", 1))
        assertTrue(AttachmentPicker.isAllowed("image/webp", 1))
        assertTrue(AttachmentPicker.isAllowed("image/heic", 1))
        assertTrue(AttachmentPicker.isAllowed("application/pdf", 25 * mb))
        assertFalse(AttachmentPicker.isAllowed("application/pdf", 25 * mb + 1))
        assertFalse(AttachmentPicker.isAllowed("image/gif", 1))
        assertFalse(AttachmentPicker.isAllowed("text/plain", 1))
        assertFalse(AttachmentPicker.isAllowed("image/jpeg", 0))
    }

    @Test
    fun `CORE-11 AC4 offline writes an outbox row with the bytes and never calls sign`() = runBlocking {
        online = false
        picker.pick(JPEG, "milk.jpg", "image/jpeg")

        assertEquals(AttachmentPickerState.Queued, picker.state.value)
        assertTrue(api.calls.isEmpty())
        val rows = pending()
        assertEquals(1, rows.size)
        assertEquals("milk.jpg", rows[0].filename)
        assertEquals("image/jpeg", rows[0].mime)
        assertEquals(4, rows[0].size)
        assertEquals("pantry_item", rows[0].entity)
        assertEquals(entityId, rows[0].entityId)
        assertArrayEquals(JPEG, registry.attachmentBytes(rows[0]))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `CORE-11 AC1 AC2 online signs, PUTs the bytes and records the attachment`() = runBlocking {
        val id = UUID.randomUUID()
        api.signResult = signed(id)
        picker.pick(JPEG, "milk.jpg", "image/jpeg")

        assertEquals(AttachmentPickerState.Idle, picker.state.value)
        assertEquals(
            listOf(
                Call.SignAttachment(householdId, "pantry_item", entityId, "milk.jpg", "image/jpeg", 4),
                Call.Upload("https://upload.test/o", 4, "image/jpeg"),
            ),
            api.calls,
        )
        val rows = stored()
        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals(Fixtures.userId, rows[0].createdBy)
        assertEquals("image/jpeg", rows[0].mime)
        assertEquals(4, rows[0].sizeBytes)
        assertEquals(rows, picker.attachments.value)
        assertTrue(pending().isEmpty())
    }

    @Test
    fun `CORE-11 AC5 a sign failure is an error, records nothing, and Retry succeeds`() = runBlocking {
        api.signResult = ApiResult.Failure(ApiFailure.Unknown)
        picker.pick(JPEG, "milk.jpg", "image/jpeg")
        assertEquals(AttachmentPickerState.Error(AttachmentPicker.FAILED), picker.state.value)
        assertTrue(stored().isEmpty())
        assertTrue(pending().isEmpty())

        api.signResult = signed()
        picker.retry()
        assertEquals(AttachmentPickerState.Idle, picker.state.value)
        assertEquals(1, stored().size)
    }

    @Test
    fun `CORE-11 a module-off sign failure says so`() = runBlocking {
        api.signResult = ApiResult.Failure(ApiFailure.ModuleDisabled)
        picker.pick(JPEG, "milk.jpg", "image/jpeg")
        assertEquals(AttachmentPickerState.Error(AttachmentPicker.MODULE_OFF), picker.state.value)
    }

    @Test
    fun `CORE-11 a network failure while signing falls back to the queue`() = runBlocking {
        api.signResult = ApiResult.Failure(ApiFailure.Network)
        picker.pick(JPEG, "milk.jpg", "image/jpeg")
        assertEquals(AttachmentPickerState.Queued, picker.state.value)
        assertEquals(1, pending().size)
    }

    @Test
    fun `CORE-11 a failed PUT is an error, records nothing, and Retry signs again`() = runBlocking {
        api.signResult = signed()
        api.uploadResult = ApiResult.Failure(ApiFailure.Unknown)
        picker.pick(JPEG, "milk.jpg", "image/jpeg")
        assertEquals(AttachmentPickerState.Error(AttachmentPicker.FAILED), picker.state.value)
        assertTrue(stored().isEmpty())

        api.uploadResult = ApiResult.Success(Unit)
        picker.retry()
        assertEquals(AttachmentPickerState.Idle, picker.state.value)
        assertEquals(2, signs().size)
    }

    @Test
    fun `CORE-11 a file the API would reject is refused without a request`() = runBlocking {
        picker.pick(JPEG, "a.gif", "image/gif")
        assertEquals(AttachmentPickerState.Error(AttachmentPicker.REJECTED), picker.state.value)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `CORE-11 going online drains the queue in order and deletes each row and its file`() = runBlocking {
        online = false
        picker.pick(JPEG, "first.jpg", "image/jpeg")
        picker.pick("%PDF".toByteArray(), "second.pdf", "application/pdf")
        val queued = pending()
        assertEquals(listOf("first.jpg", "second.pdf"), queued.map { it.filename })

        online = true
        api.signResult = signed()
        picker.drain()

        assertEquals(AttachmentPickerState.Idle, picker.state.value)
        assertTrue(pending().isEmpty())
        assertEquals(listOf("first.jpg", "second.pdf"), signs().map { it.filename })
        queued.forEach { assertFalse(File(context.filesDir, it.filePath).exists()) }
    }

    @Test
    fun `CORE-11 a drain that fails for a non-network reason keeps the row and shows an error`() = runBlocking {
        online = false
        picker.pick(JPEG, "first.jpg", "image/jpeg")
        online = true
        api.signResult = ApiResult.Failure(ApiFailure.Validation)
        picker.drain()
        assertEquals(AttachmentPickerState.Error(AttachmentPicker.FAILED), picker.state.value)
        assertEquals(1, pending().size)
    }

    @Test
    fun `CORE-11 a drain does not call sign while offline`() = runBlocking {
        online = false
        picker.pick(JPEG, "first.jpg", "image/jpeg")
        picker.drain()
        assertTrue(api.calls.isEmpty())
        assertEquals(AttachmentPickerState.Queued, picker.state.value)
    }
}
