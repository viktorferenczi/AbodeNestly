package com.abodenestly.core

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.abodenestly.contract.models.Profile
import com.abodenestly.core.auth.FakeAuthService
import com.abodenestly.core.auth.FakeAuthService.Call as AuthCall
import com.abodenestly.core.auth.Fetch
import com.abodenestly.core.auth.InMemoryLastHouseholdStore
import com.abodenestly.core.auth.InMemoryProfileCache
import com.abodenestly.core.auth.SessionStatus
import com.abodenestly.core.auth.SessionViewModel
import com.abodenestly.core.profile.FakeProfileRemote
import com.abodenestly.core.profile.FakeProfileRemote.Call
import com.abodenestly.core.profile.InMemoryProfileOutbox
import com.abodenestly.core.profile.NoAvatarLoader
import com.abodenestly.core.profile.ProfileDeps
import com.abodenestly.core.profile.ProfileRemoteError
import com.abodenestly.core.profile.ProfileScreen
import com.abodenestly.core.profile.ProfileSync
import com.abodenestly.core.signin.SignInScreen
import com.abodenestly.core.signin.SignInViewModel
import com.abodenestly.ui.AbodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One case per State and Interaction of DESIGN B7 (TESTING §7). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class ProfileComposeTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val userId = Fixtures.userId
    private val profile = Fixtures.profile("Ada Lovelace")
    private val cache = InMemoryProfileCache().apply { runBlocking { write(userId, profile) } }
    private val outbox = InMemoryProfileOutbox()
    private val remote = FakeProfileRemote()
    private var online by mutableStateOf(true)
    private var picks = 0
    private val changed = mutableListOf<Profile>()
    private var signedOut = 0

    // Real dispatchers: the sync runs on its own thread, so tests wait with `waitUntil` instead of advancing time.
    private fun deps() = ProfileDeps(
        cache, outbox,
        ProfileSync(outbox, remote, cache, { userId }, CoroutineScope(Job() + Dispatchers.Default)),
        NoAvatarLoader, avatarDir = folder.newFolder("avatars"), prepareAvatar = { it },
    )

    /** A stand-in for the system photo picker: counts taps and hands back a tiny "image". */
    private val picker: @Composable (onPicked: (ByteArray) -> Unit) -> (() -> Unit) = { onPicked ->
        { picks++; onPicked(byteArrayOf(1, 2, 3)) }
    }

    private fun show() {
        val deps = deps()
        rule.setContent {
            AbodeTheme {
                Column {
                    // A second focus target, so the name field can lose focus ("blur").
                    TextField("", onValueChange = {}, modifier = Modifier.testTag("other").fillMaxWidth())
                    ProfileScreen(
                        userId, "ada@example.com", profile, deps, isOnline = online,
                        onProfileChanged = { changed += it }, onSignOut = { signedOut++ },
                        modifier = Modifier.weight(1f), picker = picker,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** Background work (IO hops, the sync thread) posts back to the main looper, so idle it on every poll. */
    private fun until(condition: () -> Boolean) = rule.waitUntil(5_000) { rule.waitForIdle(); condition() }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun queuedNames() = runBlocking { outbox.pending(userId) }.map { it.data.displayName }

    @Test
    fun `CORE-06 - AC1 idle shows avatar, name and email`() {
        show()
        rule.onNodeWithTag("profile.avatar").assertIsEnabled()
        rule.onAllNodesWithText("AL", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("profile.name").assert(hasText("Ada Lovelace"))
        rule.onNodeWithTag("profile.email").assert(hasText("ada@example.com"))
        rule.onNodeWithTag("profile.notifications").assertIsNotEnabled()
        rule.onNodeWithTag("profile.signout").assertIsEnabled()
        assertTrue(!exists("profile.uploading"))
        assertTrue(!exists("profile.offline"))
    }

    @Test
    fun `CORE-06 - AC3 a held upload shows uploading-avatar`() {
        remote.hold()
        show()
        rule.onNodeWithTag("profile.avatar").performClick()
        until { exists("profile.uploading") }
        rule.onNodeWithTag("profile.avatar").assertIsNotEnabled()
        assertTrue(remote.calls.first() is Call.Upload)

        remote.release()
        until { !exists("profile.uploading") }
        rule.onNodeWithTag("profile.avatar").assertIsEnabled()
        assertEquals(listOf("Upload", "Patch"), remote.calls.map { it::class.simpleName })
    }

    @Test
    fun `CORE-06 - AC4 offline shows the caption, keeps a name edit and queues a picked avatar`() {
        online = false
        // Offline, every request fails like a real connection would.
        remote.failure = ProfileRemoteError.Network
        show()
        rule.onNodeWithTag("profile.offline").assertExists()

        rule.onNodeWithTag("profile.name").performTextClearance()
        rule.onNodeWithTag("profile.name").performTextInput("Ada L")
        rule.onNodeWithTag("profile.name").performImeAction()
        until { remote.calls.isNotEmpty() }
        // The drain tried and failed: the edit is still queued.
        assertEquals(listOf<String?>("Ada L"), queuedNames())
        rule.onNodeWithTag("profile.name").assert(hasText("Ada L"))

        rule.onNodeWithTag("profile.avatar").performClick()
        until { runBlocking { outbox.pendingAvatar(userId) } != null }
        // Queued, not failed: no spinner, the caption stays, nothing went to the network.
        assertTrue(!exists("profile.uploading"))
        rule.onNodeWithTag("profile.offline").assertExists()
        rule.onNodeWithTag("profile.avatar").assertIsEnabled()
        // Only the name edit's attempt reached the remote; the picture did not go out.
        assertTrue(remote.calls.none { it is Call.Upload })
        assertEquals(listOf<String?>("Ada L"), queuedNames())
    }

    @Test
    fun `CORE-06 - a name edit commits on blur`() {
        show()
        rule.onNodeWithTag("profile.name").performTextClearance()
        rule.onNodeWithTag("profile.name").performTextInput("Ada B")
        assertTrue(queuedNames().isEmpty())

        rule.onNodeWithTag("other").performClick()
        // Committed on blur: the local profile moved at once, and the drain follows on its own.
        until { remote.calls.isNotEmpty() }
        assertEquals(listOf("Ada B"), changed.map { it.displayName })
        assertEquals(Call.Patch(userId, "Ada B", null), remote.calls.single())
        until { queuedNames().isEmpty() }
    }

    @Test
    fun `CORE-06 - a blank name is not saved`() {
        show()
        rule.onNodeWithTag("profile.name").performTextClearance()
        rule.onNodeWithTag("profile.name").performImeAction()
        rule.waitForIdle()
        assertTrue(queuedNames().isEmpty())
        rule.onNodeWithTag("profile.name").assert(hasText("Ada Lovelace"))
    }

    @Test
    fun `CORE-06 - tapping the avatar calls the picker`() {
        show()
        assertEquals(0, picks)
        rule.onNodeWithTag("profile.avatar").performClick()
        assertEquals(1, picks)
        until { remote.calls.size == 2 }
    }

    @Test
    fun `CORE-06 - AC5 sign out calls the callback`() {
        show()
        rule.onNodeWithTag("profile.signout").performClick()
        assertEquals(1, signedOut)
    }

    /** The shell's rule: once the session is gone, the sign-in screen replaces the profile. */
    @Test
    fun `CORE-06 - AC5 sign out lands on the sign-in screen`() {
        val auth = FakeAuthService(Fixtures.session).apply { this.profile = Fetch.Success(this@ProfileComposeTest.profile) }
        val session = SessionViewModel(
            auth, InMemoryLastHouseholdStore(), isOnline = { true }, profileCache = cache,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        val signIn = SignInViewModel(auth, "abodenestly-dev://auth/callback", scope = CoroutineScope(Job() + Dispatchers.Unconfined))
        val deps = deps()
        session.start()
        rule.setContent {
            AbodeTheme {
                val state by session.state.collectAsState()
                if (state.status == SessionStatus.SignedOut) SignInScreen(signIn, isOnline = true)
                else ProfileScreen(
                    userId, session.email, state.profile, deps, isOnline = true,
                    onProfileChanged = session::updateProfile, onSignOut = session::signOut, picker = picker,
                )
            }
        }
        rule.onNodeWithTag("profile.email").assert(hasText("ada@example.com"))
        rule.onNodeWithTag("profile.signout").performClick()
        until { exists("signin.email") }
        assertTrue(auth.calls.contains(AuthCall.SignOut))
        assertTrue(!exists("profile.screen"))
    }
}
