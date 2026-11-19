package com.abodenestly.core.attachments

import com.abodenestly.contract.models.Attachment
import com.abodenestly.core.api.ApiFailure
import com.abodenestly.core.api.ApiResult
import com.abodenestly.core.api.ApiService
import com.abodenestly.core.api.SignedUpload
import com.abodenestly.sync.AttachmentUpload
import com.abodenestly.sync.DatabaseRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Entities that can carry an attachment (SPEC §4.1 CORE-11). [value] is what the API and the local table store. */
enum class AttachmentEntity(val value: String) {
    PantryItem("pantry_item"),
    Asset("asset"),
    MaintLog("maint_log"),
}

/** The states of the control (CORE-11); the list is shown in every one of them. */
sealed interface AttachmentPickerState {
    data object Idle : AttachmentPickerState
    data object Uploading : AttachmentPickerState
    data object Queued : AttachmentPickerState
    data class Error(val message: String) : AttachmentPickerState
}

/**
 * Logic of the shared attachment control that pantry items, assets and log entries host (port of iOS `AttachmentPicker`).
 * Offline picks go to the local `attachment_outbox` queue, which [drain] works through when online.
 * [isOnline] is read at each step, so the host can pass a lambda over its connectivity state.
 */
class AttachmentPicker(
    private val householdId: UUID,
    private val entity: AttachmentEntity,
    private val entityId: UUID,
    private val userId: UUID?,
    private val registry: DatabaseRegistry,
    private val api: ApiService,
    private val isOnline: () -> Boolean = { true },
    private val newId: () -> UUID = UUID::randomUUID,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.UTC) },
) {
    private val mutableState = MutableStateFlow<AttachmentPickerState>(AttachmentPickerState.Idle)
    private val mutableAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    private val mutableQueued = MutableStateFlow<List<AttachmentUpload>>(emptyList())

    val state: StateFlow<AttachmentPickerState> = mutableState.asStateFlow()
    val attachments: StateFlow<List<Attachment>> = mutableAttachments.asStateFlow()
    val queued: StateFlow<List<AttachmentUpload>> = mutableQueued.asStateFlow()

    /** The pick that failed, so Retry re-runs it; [retryDrain] when a drain stopped instead. */
    private var lastPick: Pending? = null
    private var retryDrain = false
    @Volatile private var draining = false

    private class Pending(val filename: String, val mime: String, val bytes: ByteArray)

    private sealed interface Outcome {
        data object Uploaded : Outcome
        data object Network : Outcome
        data class Failed(val failure: ApiFailure) : Outcome
    }

    /** On appear: show what is stored and upload what was queued earlier. */
    suspend fun start() {
        reload()
        drain()
    }

    /** Lets rows another writer adds (the pull, later) show without a reload. */
    fun showStored(rows: List<Attachment>) {
        mutableAttachments.value = rows
    }

    /** Steps 1 to 3 of the pick flow: offline queues without calling sign (AC4); online signs, PUTs and records. */
    suspend fun pick(bytes: ByteArray, filename: String, mime: String) {
        if (!isAllowed(mime, bytes.size)) {
            lastPick = null
            retryDrain = false
            mutableState.value = AttachmentPickerState.Error(REJECTED)
            return
        }
        val pending = Pending(filename, mime, bytes)
        lastPick = pending
        retryDrain = false
        // Offline goes straight to the queue; "uploading" would flash for nothing.
        val online = isOnline()
        if (online) mutableState.value = AttachmentPickerState.Uploading
        when (val outcome = if (online) send(pending, entity.value, entityId) else Outcome.Network) {
            Outcome.Uploaded -> Unit
            // Offline, or the network dropped mid-way: keep the pick for the drain.
            Outcome.Network -> {
                val upload = AttachmentUpload(newId(), householdId, entity.value, entityId, filename, mime, bytes.size, createdAt = now())
                try {
                    registry.enqueueAttachmentUpload(upload, bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableState.value = AttachmentPickerState.Error(FAILED)
                    return
                }
            }
            // Nothing is recorded locally (AC5); Retry re-runs the same pick.
            is Outcome.Failed -> {
                mutableState.value = AttachmentPickerState.Error(messageFor(outcome.failure))
                return
            }
        }
        lastPick = null
        settle()
    }

    /** Whether Retry has something to re-run: a failed pick or a stopped drain. A rejected file has nothing. */
    val canRetry: Boolean get() = lastPick != null || retryDrain

    /** Re-runs the pick that failed, or the drain that stopped. */
    suspend fun retry() {
        val pending = lastPick
        if (pending != null) pick(pending.bytes, pending.filename, pending.mime) else if (retryDrain) drain()
    }

    /** Step 4: queued picks in creation order. A row leaves the queue only after it was uploaded and recorded. */
    suspend fun drain() {
        if (draining) return
        draining = true
        try {
            val waiting = runCatching { registry.pendingAttachmentUploads(householdId) }.getOrDefault(emptyList())
            // Nothing queued, or no network: show the settled state without flashing "uploading".
            if (waiting.isEmpty() || !isOnline()) {
                settle()
                return
            }
            mutableState.value = AttachmentPickerState.Uploading
            retryDrain = false
            var firstFailure: ApiFailure? = null
            for (next in waiting) {
                if (!isOnline()) break
                val bytes = runCatching { registry.attachmentBytes(next) }.getOrNull()
                if (bytes == null) {
                    // The file is gone, so this pick can never upload; dropping it keeps the rest of the queue moving.
                    registry.removeAttachmentUpload(householdId, next.id)
                    continue
                }
                val outcome = send(Pending(next.filename, next.mime, bytes), next.entity, next.entityId)
                when (outcome) {
                    // A permanent failure (module off, entity gone) must not hold up the picks behind it: the row stays
                    // for Retry, the rest of the queue goes on, and the error is shown when the pass ends.
                    is Outcome.Failed -> if (firstFailure == null) firstFailure = outcome.failure
                    Outcome.Network -> break
                    Outcome.Uploaded -> registry.removeAttachmentUpload(householdId, next.id)
                }
            }
            if (firstFailure != null) {
                retryDrain = true
                reload()
                mutableState.value = AttachmentPickerState.Error(messageFor(firstFailure))
            } else {
                settle()
            }
        } finally {
            draining = false
        }
    }

    private suspend fun send(pending: Pending, entityName: String, targetId: UUID): Outcome {
        val signed: SignedUpload = when (
            val result = api.signAttachment(householdId, entityName, targetId, pending.filename, pending.mime, pending.bytes.size)
        ) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return if (result.failure == ApiFailure.Network) Outcome.Network else Outcome.Failed(result.failure)
        }
        when (val put = api.uploadToSignedUrl(signed.uploadUrl, pending.bytes, pending.mime)) {
            is ApiResult.Success -> Unit
            is ApiResult.Failure -> return if (put.failure == ApiFailure.Network) Outcome.Network else Outcome.Failed(put.failure)
        }
        val stamp = now()
        val row = Attachment(
            id = signed.attachmentId, householdId = householdId, entity = entityName, entityId = targetId,
            storagePath = signed.storagePath, mime = pending.mime, sizeBytes = pending.bytes.size, createdBy = userId,
            createdAt = stamp, updatedAt = stamp, deletedAt = null, syncSeq = 0,
        )
        return try {
            registry.upsertAttachment(row)
            Outcome.Uploaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(ApiFailure.Unknown)
        }
    }

    private suspend fun settle() {
        reload()
        mutableState.value = if (mutableQueued.value.isEmpty()) AttachmentPickerState.Idle else AttachmentPickerState.Queued
    }

    private suspend fun reload() {
        runCatching { registry.attachments(householdId, entity.value, entityId) }.onSuccess { mutableAttachments.value = it }
        mutableQueued.value = runCatching { registry.pendingAttachmentUploads(householdId, entity.value, entityId) }.getOrDefault(emptyList())
    }

    private fun messageFor(failure: ApiFailure) = if (failure == ApiFailure.ModuleDisabled) MODULE_OFF else FAILED

    companion object {
        val ALLOWED_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf")
        const val MAX_BYTES = 25 * 1024 * 1024
        const val REJECTED = "Choose a photo or a PDF up to 25 MB."
        const val FAILED = "Could not attach the file."
        const val MODULE_OFF = "Attachments are turned off for this household."

        /** Mirrors the API's limits; the server stays the authority. */
        fun isAllowed(mime: String, size: Int): Boolean = mime.lowercase() in ALLOWED_MIMES && size in 1..MAX_BYTES
    }
}
