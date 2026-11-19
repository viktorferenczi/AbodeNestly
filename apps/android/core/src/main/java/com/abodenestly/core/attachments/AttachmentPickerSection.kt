package com.abodenestly.core.attachments

import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.abodenestly.contract.models.Attachment
import com.abodenestly.core.profile.readCapped
import com.abodenestly.sync.AttachmentUpload
import com.abodenestly.sync.DatabaseRegistry
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What a system picker hands back: the file's bytes with the name and type the API needs. */
class PickedFile(val bytes: ByteArray, val filename: String, val mime: String)

/** Opens the system photo picker; the picked image comes back through `onPicked` (`null` when it could not be read). */
@Composable
fun rememberPhotoFilePicker(onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { onPicked(readPicked(context, uri, fallbackMime = "image/jpeg")) }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Opens the system document picker for PDFs only. */
@Composable
fun rememberPdfFilePicker(onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { onPicked(readPicked(context, uri, fallbackMime = "application/pdf")) }
    }
    return { launcher.launch(arrayOf("application/pdf")) }
}

private suspend fun readPicked(context: android.content.Context, uri: Uri, fallbackMime: String): PickedFile? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // One byte over the limit is enough to know the file is too big, without reading all of it.
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readCapped(AttachmentPicker.MAX_BYTES + 1) } }.getOrNull()
            ?: return@withContext null
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "attachment"
        PickedFile(bytes, name, resolver.getType(uri) ?: fallbackMime)
    }

/**
 * The shared attachment control (CORE-11) that pantry item, log entry and asset screens host. [enabled] is the entity's
 * module flag: off renders nothing (SPEC §9 X7). [isOnline] drains the queue whenever it turns true.
 */
@Composable
fun AttachmentPickerSection(
    picker: AttachmentPicker,
    registry: DatabaseRegistry,
    householdId: UUID,
    entity: AttachmentEntity,
    entityId: UUID,
    enabled: Boolean,
    isOnline: Boolean,
    loader: AttachmentLoader,
    modifier: Modifier = Modifier,
    photoPicker: @Composable (onPicked: (PickedFile?) -> Unit) -> (() -> Unit) = { onPicked -> rememberPhotoFilePicker(onPicked) },
    pdfPicker: @Composable (onPicked: (PickedFile?) -> Unit) -> (() -> Unit) = { onPicked -> rememberPdfFilePicker(onPicked) },
) {
    if (!enabled) return
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val state by picker.state.collectAsState()
    val attachments by picker.attachments.collectAsState()
    val queued by picker.queued.collectAsState()

    // On appear and whenever the device comes back online (step 4).
    LaunchedEffect(picker, isOnline) { picker.start() }
    // Rows another writer adds appear without a reload.
    LaunchedEffect(picker, householdId, entity, entityId) {
        registry.observeAttachments(householdId, entity.value, entityId).collect(picker::showStored)
    }

    val onPicked: (PickedFile?) -> Unit = { file ->
        scope.launch {
            if (file == null) picker.pick(ByteArray(0), "attachment", "")
            else picker.pick(file.bytes, file.filename, file.mime)
        }
    }
    val pickPhoto = photoPicker(onPicked)
    val pickPdf = pdfPicker(onPicked)
    val busy = state == AttachmentPickerState.Uploading

    Column(modifier.fillMaxWidth().testTag("attachments"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = pickPhoto, enabled = !busy, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("attachments.photo")) {
                Text("Add photo")
            }
            OutlinedButton(onClick = pickPdf, enabled = !busy, modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("attachments.pdf")) {
                Text("Add PDF")
            }
        }
        when (val current = state) {
            AttachmentPickerState.Idle -> Column(Modifier.testTag("attachments.idle")) {}
            AttachmentPickerState.Uploading -> StatusText("Uploading…", tokens.text2, "attachments.uploading")
            AttachmentPickerState.Queued -> StatusText("Will upload when you're online", tokens.text2, "attachments.queued")
            is AttachmentPickerState.Error -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusText(current.message, tokens.danger, "attachments.error")
                if (picker.canRetry) {
                    OutlinedButton(
                        onClick = { scope.launch { picker.retry() } },
                        modifier = Modifier.heightIn(min = Tokens.MinTouchTarget).testTag("attachments.retry"),
                    ) { Text("Retry") }
                }
            }
        }
        attachments.forEach { AttachmentRow(it, loader) }
        queued.forEach { QueuedRow(it, registry, loader) }
    }
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color, tag: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = color,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    )
}

@Composable
private fun AttachmentRow(attachment: Attachment, loader: AttachmentLoader) {
    val tokens = LocalTokens.current
    if (attachment.mime.startsWith("image/")) {
        var bitmap by remember(attachment.storagePath) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(attachment.storagePath, loader) { bitmap = loader.load(attachment.storagePath) }
        Thumbnail(bitmap, "attachments.item", attachment.storagePath.substringAfterLast('/').ifEmpty { "Photo" })
    } else {
        Text(
            attachment.storagePath.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, color = tokens.text,
            modifier = Modifier.testTag("attachments.item"),
        )
    }
}

@Composable
private fun QueuedRow(upload: AttachmentUpload, registry: DatabaseRegistry, loader: AttachmentLoader) {
    val tokens = LocalTokens.current
    if (upload.mime.startsWith("image/")) {
        var bitmap by remember(upload.id) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(upload.id, loader) { bitmap = runCatching { loader.decode(registry.attachmentBytes(upload)) }.getOrNull() }
        Thumbnail(bitmap, "attachments.queuedItem", upload.filename)
    } else {
        Text(upload.filename, style = MaterialTheme.typography.bodyLarge, color = tokens.text, modifier = Modifier.testTag("attachments.queuedItem"))
    }
}

@Composable
private fun Thumbnail(bitmap: Bitmap?, tag: String, description: String) {
    val tokens = LocalTokens.current
    val shape = RoundedCornerShape(Tokens.CardRadius)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(shape).testTag(tag))
    } else {
        // Same size while it loads, offline, or when the load fails.
        Box(Modifier.size(96.dp).clip(shape).background(tokens.surface2).semantics { contentDescription = description }.testTag(tag))
    }
}
