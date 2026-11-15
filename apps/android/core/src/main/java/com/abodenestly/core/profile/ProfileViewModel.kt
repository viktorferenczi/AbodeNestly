package com.abodenestly.core.profile

import androidx.lifecycle.ViewModel
import com.abodenestly.contract.models.Profile
import com.abodenestly.core.auth.ProfileCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/** DESIGN B7 `uploading-avatar`; `idle` and `offline` are [ProfileUiState.status] and [ProfileUiState.offline]. */
enum class ProfileStatus { Idle, UploadingAvatar }

data class ProfileUiState(
    val profile: Profile? = null,
    /** From the auth session; read-only. */
    val email: String? = null,
    val nameDraft: String = "",
    /** The picked picture still waiting for its upload; shown instead of `profile.avatarPath`. */
    val avatarFile: String? = null,
    val status: ProfileStatus = ProfileStatus.Idle,
    val offline: Boolean = false,
    /** A photo that was not stored, or edits the server refused; shown under the avatar. */
    val notice: String? = null,
)

/**
 * Profile screen logic (CORE-06). Edits go to the cache and the outbox and never touch the network: the
 * [ProfileSync] drain sends them. [scope] is injectable so tests control the dispatcher.
 */
class ProfileViewModel(
    private val userId: UUID,
    email: String?,
    initialProfile: Profile?,
    private val cache: ProfileCache,
    private val outbox: ProfileOutbox,
    private val sync: ProfileSync,
    isOnline: Boolean,
    var onProfileChanged: (Profile) -> Unit,
    private val avatarDir: File,
    /** Downscales a picked photo to the JPEG that is stored; `null` for something that is not an image. */
    private val prepareAvatar: (ByteArray) -> ByteArray? = AvatarCodec::downscaleJpeg,
    private val now: () -> String = { Instant.now().toString() },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Where the photo is downscaled and written; injectable so tests need no real threads. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {
    private val mutableState = MutableStateFlow(
        ProfileUiState(
            profile = initialProfile, email = email, nameDraft = initialProfile?.displayName.orEmpty(), offline = !isOnline,
        ),
    )
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    /** The name last queued or loaded, so a blur without a change queues nothing even before the profile loads. */
    private var committedName: String? = initialProfile?.displayName

    init {
        scope.launch { reload() }
    }

    fun onNameChange(value: String) {
        mutableState.update { it.copy(nameDraft = value) }
    }

    /** On blur or IME done. A blank or unchanged name does nothing; the draft snaps back for a blank one. */
    fun onNameCommitted() {
        val name = mutableState.value.nameDraft.trim()
        if (name.isEmpty()) {
            mutableState.update { it.copy(nameDraft = committedName.orEmpty()) }
            return
        }
        if (name == committedName) {
            mutableState.update { it.copy(nameDraft = name) }
            return
        }
        committedName = name
        mutableState.update { s -> s.copy(profile = s.profile?.copy(displayName = name), nameDraft = name) }
        scope.launch {
            val updated = sync.updateCache(userId) { it.copy(displayName = name) }
            outbox.append(userId, ProfileMutation(uuid(), rowId = userId.toString(), data = ProfileData(displayName = name), clientTs = now()))
            updated?.let(onProfileChanged)
            sync.kick()
        }
    }

    /** Stores the picture locally and queues it. Online it uploads at once; offline it waits for connectivity. */
    fun onAvatarPicked(bytes: ByteArray?) {
        scope.launch {
            val jpeg = bytes?.let { withContext(io) { prepareAvatar(it) } }
            if (jpeg == null) {
                // The current photo stays; the person is told nothing was stored.
                mutableState.update { it.copy(notice = PHOTO_NOT_STORED) }
                return@launch
            }
            mutableState.update { it.copy(notice = null) }
            val file = File(avatarDir, "pending-avatar-${uuid()}.jpg")
            withContext(io) {
                avatarDir.mkdirs()
                file.writeBytes(jpeg)
            }
            val previous = outbox.pendingAvatar(userId)
            outbox.setPendingAvatar(userId, PendingAvatar(userId.toString(), file.path))
            previous?.let { old -> withContext(io) { File(old.filePath).delete() } }
            mutableState.update { it.copy(avatarFile = file.path) }
            if (!mutableState.value.offline) flush()
        }
    }

    /** The screen calls this when connectivity changes; coming back online sends whatever is queued. */
    fun setOnline(online: Boolean) {
        val wasOffline = mutableState.value.offline
        mutableState.update { it.copy(offline = !online) }
        if (online && wasOffline) scope.launch { if (hasPending()) flush() }
    }

    private suspend fun hasPending() = outbox.pending(userId).isNotEmpty() || outbox.pendingAvatar(userId) != null

    private suspend fun flush() {
        sync.clearProblem()
        val uploading = outbox.pendingAvatar(userId) != null
        if (uploading) mutableState.update { it.copy(status = ProfileStatus.UploadingAvatar) }
        try {
            sync.drain()
        } finally {
            mutableState.update { it.copy(status = ProfileStatus.Idle, notice = if (sync.problem.value) SAVE_PROBLEM else it.notice) }
            reload()
        }
    }

    /** Re-reads the local profile and the queued picture, keeping a name the person is still typing. */
    private suspend fun reload() {
        // The cache plus what is still queued is what the person last saw: a queued name survives a missing row.
        val queued = outbox.pending(userId)
        val cached = (cache.read(userId) ?: mutableState.value.profile)?.let { ProfileMutation.overlay(it, queued) }
        val pending = outbox.pendingAvatar(userId)
        val before = mutableState.value
        val untouched = before.nameDraft == before.profile?.displayName.orEmpty() || before.nameDraft == committedName
        mutableState.update { s ->
            val profile = cached ?: s.profile
            s.copy(
                profile = profile,
                avatarFile = pending?.filePath,
                nameDraft = if (untouched) profile?.displayName ?: s.nameDraft else s.nameDraft,
            )
        }
        if (committedName == null) committedName = cached?.displayName
        if (cached != null && cached != before.profile) onProfileChanged(cached)
    }

    private companion object {
        const val PHOTO_NOT_STORED = "That photo couldn't be used. Choose a JPG, PNG, WebP or GIF photo."
        const val SAVE_PROBLEM = "Some changes couldn't be saved."
    }
}
