package com.abodenestly.core.profile

import com.abodenestly.contract.models.Profile
import com.abodenestly.core.auth.ProfileCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/** How a drain ended. */
enum class DrainResult {
    /** Nothing is left to send (rejected entries were dropped). */
    Done,

    /** Stopped by a network or auth failure; the entries stay queued for the next trigger. */
    Stopped,
}

/**
 * Drains the profile outbox for the signed-in user. SHOP-08's `/sync` only takes household tables, so the
 * profile goes straight to PostgREST with the user's JWT (SPEC §7 allows direct writes on core settings screens).
 * Runs coalesce on a [Mutex]; entries go out oldest first.
 */
class ProfileSync(
    private val outbox: ProfileOutbox,
    private val remote: ProfileRemote,
    private val cache: ProfileCache,
    private val currentUser: suspend () -> UUID?,
    private val scope: CoroutineScope,
    private val now: () -> String = { Instant.now().toString() },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val readFile: (String) -> ByteArray? = { path -> File(path).takeIf { it.isFile }?.readBytes() },
    private val deleteFile: (String) -> Unit = { File(it).delete() },
    /** Where file reads and deletes run; injectable so tests need no real threads. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Serialises read-modify-write on the cached profile row, which the screen and the drain both change. */
    private val cacheLock = Mutex()

    private val mutableProblem = MutableStateFlow(false)

    /** True once the server refused an edit or a photo, so the screen can say "Some changes couldn't be saved." */
    val problem: StateFlow<Boolean> = mutableProblem.asStateFlow()

    fun clearProblem() {
        mutableProblem.value = false
    }

    /** Changes one field set of the cached row under [cacheLock]; returns the stored row, or `null` if none is cached. */
    suspend fun updateCache(userId: UUID, change: (Profile) -> Profile): Profile? = cacheLock.withLock {
        cache.read(userId)?.let(change)?.also { cache.write(userId, it) }
    }

    /** Fire and forget, for triggers (an enqueue, connectivity returning, a session appearing). */
    fun kick() {
        scope.launch { drain() }
    }

    suspend fun drain(): DrainResult = mutex.withLock { drainLocked() }

    private suspend fun drainLocked(): DrainResult {
        val userId = currentUser() ?: return DrainResult.Stopped
        while (true) {
            // Re-read each round: an edit queued while a request was in flight must not be skipped.
            val entry = outbox.pending(userId).firstOrNull()
            if (entry != null) {
                when (send(userId, entry)) {
                    Outcome.Stop -> return DrainResult.Stopped
                    Outcome.Applied, Outcome.Dropped -> outbox.remove(userId, entry.id)
                }
                continue
            }
            val avatar = outbox.pendingAvatar(userId) ?: return DrainResult.Done
            if (!upload(userId, avatar)) return DrainResult.Stopped
        }
    }

    private enum class Outcome { Applied, Dropped, Stop }

    private suspend fun send(userId: UUID, entry: ProfileMutation): Outcome = try {
        remote.patchProfile(userId, entry.data.displayName, entry.data.avatarPath)
        entry.data.avatarPath?.let { path -> updateCache(userId) { it.copy(avatarPath = path) } }
        Outcome.Applied
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProfileRemoteFailure) {
        // A rejected entry (RLS, validation) would block the queue forever, so it is dropped.
        if (e.error == ProfileRemoteError.Rejected) {
            mutableProblem.value = true
            Outcome.Dropped
        } else Outcome.Stop
    }

    /** Uploads the queued picture and turns it into an `avatar_path` upsert, which the loop drains next. */
    private suspend fun upload(userId: UUID, avatar: PendingAvatar): Boolean {
        val bytes = withContext(io) { readFile(avatar.filePath) }
        if (bytes == null) {
            // The file is gone (cleared cache, reinstall): nothing to upload.
            clearIfCurrent(userId, avatar)
            return true
        }
        return try {
            val path = remote.uploadAvatar(userId, "avatar-${uuid()}.jpg", bytes)
            outbox.append(userId, ProfileMutation(uuid(), rowId = userId.toString(), data = ProfileData(avatarPath = path), clientTs = now()))
            clearIfCurrent(userId, avatar)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProfileRemoteFailure) {
            // A refused photo stays queued (the person's only copy) and is reported; picking another replaces it.
            if (e.error == ProfileRemoteError.Rejected) mutableProblem.value = true
            false
        }
    }

    /** A newer pick may have replaced the slot while this upload ran; only the picture that was sent is cleared. */
    private suspend fun clearIfCurrent(userId: UUID, avatar: PendingAvatar) {
        if (outbox.pendingAvatar(userId)?.filePath != avatar.filePath) return
        outbox.setPendingAvatar(userId, null)
        withContext(io) { deleteFile(avatar.filePath) }
    }
}
