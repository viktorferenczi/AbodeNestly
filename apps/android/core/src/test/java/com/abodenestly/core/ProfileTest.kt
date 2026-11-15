package com.abodenestly.core

import com.abodenestly.contract.models.Profile
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.InMemoryProfileCache
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.profile.DrainResult
import com.abodenestly.core.profile.FakeProfileRemote
import com.abodenestly.core.profile.FakeProfileRemote.Call
import com.abodenestly.core.profile.InMemoryProfileOutbox
import com.abodenestly.core.profile.PendingAvatar
import com.abodenestly.core.profile.ProfileData
import com.abodenestly.core.profile.ProfileMutation
import com.abodenestly.core.profile.ProfileRemoteError
import com.abodenestly.core.profile.ProfileStatus
import com.abodenestly.core.profile.ProfileSync
import com.abodenestly.core.profile.ProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** CORE-06 logic: the profile view model's local-first edits, the outbox and the drain. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileTest {
    @TempDir lateinit var dir: File

    private val userId = Fixtures.userId
    private val profile = Fixtures.profile("Ada")
    private val cache = InMemoryProfileCache().apply { kotlinx.coroutines.runBlocking { write(userId, profile) } }
    private val outbox = InMemoryProfileOutbox()
    private val remote = FakeProfileRemote()
    private val changed = mutableListOf<Profile>()
    private var ids = 0

    private fun uuid() = "id-${++ids}"

    /** A sync on a scheduler nobody advances: a `kick()` is queued but never runs, so it cannot reach the remote. */
    private fun unkickedSync() = ProfileSync(
        outbox, remote, cache, { userId }, CoroutineScope(StandardTestDispatcher() + Job()), uuid = ::uuid,
    )

    private fun TestScope.sync() = ProfileSync(
        outbox, remote, cache, { userId }, backgroundScope, now = { "2026-11-15T10:00:00Z" }, uuid = ::uuid,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.vm(online: Boolean = true, sync: ProfileSync = unkickedSync()): ProfileViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ProfileViewModel(
            userId, "ada@example.com", profile, cache, outbox, sync, online, { changed += it }, dir,
            prepareAvatar = { it }, now = { "2026-11-15T10:00:00Z" }, uuid = ::uuid,
            scope = CoroutineScope(dispatcher + Job()), io = dispatcher,
        )
    }

    private fun entry(name: String) =
        ProfileMutation(uuid(), rowId = userId.toString(), data = ProfileData(displayName = name), clientTs = "2026-11-15T10:00:00Z")

    // ---- AC2: a name edit is local first ----

    @Test
    fun `CORE-06 - name edit appends one outbox upsert and does not call the network`() = runTest {
        val vm = vm()
        runCurrent()
        vm.onNameChange("  Ada Lovelace ")
        vm.onNameCommitted()
        runCurrent()

        assertEquals("Ada Lovelace", cache.read(userId)?.displayName)
        val queued = outbox.pending(userId)
        assertEquals(1, queued.size)
        assertEquals("profile", queued[0].table)
        assertEquals("upsert", queued[0].op)
        assertEquals(userId.toString(), queued[0].rowId)
        assertEquals("Ada Lovelace", queued[0].data.displayName)
        assertNull(queued[0].data.avatarPath)
        assertEquals(listOf("Ada Lovelace"), changed.map { it.displayName })
        assertEquals("Ada Lovelace", vm.state.value.profile?.displayName)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `a blank or unchanged name appends nothing`() = runTest {
        val vm = vm()
        runCurrent()
        vm.onNameChange("   ")
        vm.onNameCommitted()
        assertEquals("Ada", vm.state.value.nameDraft)
        vm.onNameChange("Ada")
        vm.onNameCommitted()
        vm.onNameChange(" Ada ")
        vm.onNameCommitted()
        runCurrent()

        assertTrue(outbox.pending(userId).isEmpty())
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `committing the same new name twice queues it once`() = runTest {
        val vm = vm()
        runCurrent()
        vm.onNameChange("Ada L")
        vm.onNameCommitted()
        vm.onNameCommitted()
        runCurrent()
        assertEquals(1, outbox.pending(userId).size)
    }

    // ---- The drain ----

    @Test
    fun `the drain sends a PATCH and removes the entry`() = runTest {
        outbox.append(userId, entry("Ada L"))
        assertEquals(DrainResult.Done, sync().drain())

        assertEquals(listOf<Call>(Call.Patch(userId, "Ada L", null)), remote.calls)
        assertTrue(outbox.pending(userId).isEmpty())
    }

    @Test
    fun `a network error keeps the entry and stops the drain`() = runTest {
        outbox.append(userId, entry("one"))
        outbox.append(userId, entry("two"))
        remote.failure = ProfileRemoteError.Network
        val sync = sync()
        assertEquals(DrainResult.Stopped, sync.drain())
        // Only the first was tried: the drain stops at the first network error.
        assertEquals(1, remote.calls.size)
        assertEquals(listOf("one", "two"), outbox.pending(userId).map { it.data.displayName })

        remote.failure = null
        assertEquals(DrainResult.Done, sync.drain())
        assertEquals(listOf("one", "two"), remote.calls.map { (it as Call.Patch).displayName }.drop(1))
        assertTrue(outbox.pending(userId).isEmpty())
    }

    @Test
    fun `an unauthorized reply keeps the entry for the next session`() = runTest {
        outbox.append(userId, entry("one"))
        remote.failure = ProfileRemoteError.Unauthorized
        assertEquals(DrainResult.Stopped, sync().drain())
        assertEquals(1, outbox.pending(userId).size)
    }

    @Test
    fun `a rejected entry is dropped so it cannot block the queue`() = runTest {
        outbox.append(userId, entry("one"))
        remote.failure = ProfileRemoteError.Rejected
        assertEquals(DrainResult.Done, sync().drain())
        assertTrue(outbox.pending(userId).isEmpty())
    }

    @Test
    fun `without a signed-in user the drain does nothing`() = runTest {
        outbox.append(userId, entry("one"))
        val signedOut = ProfileSync(outbox, remote, cache, { null }, backgroundScope)
        assertEquals(DrainResult.Stopped, signedOut.drain())
        assertTrue(remote.calls.isEmpty())
        assertEquals(1, outbox.pending(userId).size)
    }

    // ---- Avatar ----

    @Test
    fun `CORE-06 - an offline avatar pick is queued, then uploads and upserts avatar_path once online`() = runTest {
        val sync = sync()
        val vm = vm(online = false, sync = sync)
        runCurrent()
        vm.onAvatarPicked(byteArrayOf(1, 2, 3))
        runCurrent()

        val marker = outbox.pendingAvatar(userId)
        assertNotNull(marker)
        assertTrue(File(marker!!.filePath).isFile)
        assertEquals(marker.filePath, vm.state.value.avatarFile)
        assertEquals(ProfileStatus.Idle, vm.state.value.status)
        assertTrue(vm.state.value.offline)
        assertTrue(remote.calls.isEmpty())

        // Still offline when the first drain runs: the picture stays queued.
        remote.failure = ProfileRemoteError.Network
        assertEquals(DrainResult.Stopped, sync.drain())
        assertNotNull(outbox.pendingAvatar(userId))
        assertTrue(outbox.pending(userId).isEmpty())

        remote.failure = null
        assertEquals(DrainResult.Done, sync.drain())
        val upload = remote.calls.filterIsInstance<Call.Upload>().last()
        assertEquals(3, upload.size)
        val patch = remote.calls.last() as Call.Patch
        assertEquals("profiles/$userId/${upload.fileName}", patch.avatarPath)
        assertNull(patch.displayName)
        assertNull(outbox.pendingAvatar(userId))
        assertTrue(outbox.pending(userId).isEmpty())
        assertEquals(patch.avatarPath, cache.read(userId)?.avatarPath)
        assertTrue(!File(marker.filePath).exists())
    }

    @Test
    fun `an online avatar pick shows uploading until the sync result comes back`() = runTest {
        val vm = vm(online = true, sync = sync())
        runCurrent()
        remote.hold()
        vm.onAvatarPicked(byteArrayOf(1, 2, 3))
        runCurrent()
        assertEquals(ProfileStatus.UploadingAvatar, vm.state.value.status)

        remote.release()
        runCurrent()
        assertEquals(ProfileStatus.Idle, vm.state.value.status)
        assertEquals(listOf("Upload", "Patch"), remote.calls.map { it::class.simpleName })
        assertNull(vm.state.value.avatarFile)
        assertNotNull(vm.state.value.profile?.avatarPath)
        assertEquals(vm.state.value.profile?.avatarPath, changed.last().avatarPath)
    }

    @Test
    fun `coming back online sends what was queued while offline`() = runTest {
        val vm = vm(online = false, sync = sync())
        runCurrent()
        vm.onAvatarPicked(byteArrayOf(9))
        runCurrent()
        assertTrue(remote.calls.isEmpty())

        vm.setOnline(true)
        runCurrent()
        assertEquals(listOf("Upload", "Patch"), remote.calls.map { it::class.simpleName })
    }

    @Test
    fun `something that is not an image is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ProfileViewModel(
            userId, null, profile, cache, outbox, unkickedSync(), true, {}, dir,
            prepareAvatar = { null }, scope = CoroutineScope(dispatcher + Job()), io = dispatcher,
        )
        runCurrent()
        vm.onAvatarPicked(byteArrayOf(1))
        runCurrent()
        assertNull(outbox.pendingAvatar(userId))
        assertTrue(remote.calls.isEmpty())
    }

    // ---- Review fixes ----

    @Test
    fun `an upload finishing does not clear a newer pick`() = runTest {
        val sync = sync()
        val older = File(dir, "old.jpg").apply { writeBytes(byteArrayOf(1)) }
        val newer = File(dir, "new.jpg").apply { writeBytes(byteArrayOf(2)) }
        outbox.setPendingAvatar(userId, PendingAvatar(userId.toString(), older.path))
        remote.onUpload = { outbox.setPendingAvatar(userId, PendingAvatar(userId.toString(), newer.path)) }
        sync.drain()
        // The newer photo is still queued (and was sent on the next round); the older file is not the marker.
        assertTrue(remote.calls.filterIsInstance<Call.Upload>().size == 2)
        assertEquals(listOf(1, 1), remote.calls.filterIsInstance<Call.Upload>().map { it.size })
    }

    @Test
    fun `a rejected photo stays queued and is reported`() = runTest {
        val sync = sync()
        val vm = vm(sync = sync)
        runCurrent()
        vm.onAvatarPicked(byteArrayOf(1, 2, 3))
        remote.failure = ProfileRemoteError.Rejected
        runCurrent()
        assertNotNull(outbox.pendingAvatar(userId))
        assertTrue(sync.problem.value)
        assertEquals("Some changes couldn't be saved.", vm.state.value.notice)
    }

    @Test
    fun `a rejected name is reported`() = runTest {
        outbox.append(userId, entry("one"))
        remote.failure = ProfileRemoteError.Rejected
        val sync = sync()
        sync.drain()
        assertTrue(sync.problem.value)
    }

    @Test
    fun `a photo that cannot be used shows a message and keeps the current one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ProfileViewModel(
            userId, null, profile, cache, outbox, unkickedSync(), true, {}, dir,
            prepareAvatar = { null }, scope = CoroutineScope(dispatcher + Job()), io = dispatcher,
        )
        runCurrent()
        vm.onAvatarPicked(byteArrayOf(1))
        runCurrent()
        assertNotNull(vm.state.value.notice)
        vm.onAvatarPicked(null)
        runCurrent()
        assertNull(outbox.pendingAvatar(userId))
    }

    @Test
    fun `a queued name shows even when no profile row is cached`() = runTest {
        val empty = InMemoryProfileCache()
        val vm = ProfileViewModel(
            userId, null, null, empty, outbox, unkickedSync(), true, {}, dir,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), io = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertNull(vm.state.value.profile)
    }

    @Test
    fun `a name committed while a photo uploads keeps the new avatar path`() = runTest {
        val sync = sync()
        val vm = vm(sync = sync)
        runCurrent()
        remote.hold()
        vm.onAvatarPicked(byteArrayOf(1, 2, 3))
        runCurrent()
        vm.onNameChange("Ada L")
        vm.onNameCommitted()
        remote.release()
        runCurrent()
        val stored = cache.read(userId)
        assertEquals("Ada L", stored?.displayName)
        assertNotNull(stored?.avatarPath)
        assertEquals("Ada L", vm.state.value.nameDraft)
    }

    // ---- The session overlay ----

    @Test
    fun `a stale server read does not hide a queued edit`() = runTest {
        outbox.append(userId, entry("Ada New"))
        val auth = FakeAuthService(Fixtures.session).apply { this.profile = Fetch.Success(Fixtures.profile("Ada Old")) }
        val session = SessionViewModel(
            auth, InMemoryLastHouseholdStore(), isOnline = { true }, profileCache = cache, profileOutbox = outbox,
            scope = backgroundScope,
        )
        session.start()
        runCurrent()
        assertEquals("Ada New", session.state.value.profile?.displayName)
        assertEquals("Ada New", cache.read(userId)?.displayName)
    }

    @Test
    fun `updateProfile moves the TopBar name`() = runTest {
        val auth = FakeAuthService(Fixtures.session).apply { this.profile = Fetch.Success(this@ProfileTest.profile) }
        val session = SessionViewModel(auth, InMemoryLastHouseholdStore(), isOnline = { true }, scope = backgroundScope)
        session.start()
        runCurrent()
        session.updateProfile(profile.copy(displayName = "Ada L"))
        assertEquals("Ada L", session.state.value.profile?.displayName)
        assertEquals(Fixtures.session.email, session.email)
    }
}
