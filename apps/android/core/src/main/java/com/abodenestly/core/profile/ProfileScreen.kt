package com.abodenestly.core.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abodenestly.contract.models.Profile
import com.abodenestly.core.auth.ProfileCache
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** What the profile screen needs besides the signed-in person; built once in `AppGraph`. */
class ProfileDeps(
    val cache: ProfileCache,
    val outbox: ProfileOutbox,
    val sync: ProfileSync,
    val avatars: AvatarLoader,
    /** Where a picked picture waits for its upload. */
    val avatarDir: File,
    /** Downscales a picked photo to the stored JPEG; tests pass an identity function (no real bitmaps). */
    val prepareAvatar: (ByteArray) -> ByteArray? = AvatarCodec::downscaleJpeg,
)

/** Opens the system photo picker; the picked image's bytes come back through [onPicked]. */
@Composable
fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readCapped(MAX_PICK_BYTES) } }.getOrNull()
            }
            // `null` is a photo that could not be read or is too big: the screen says so.
            onPicked(bytes)
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private const val MAX_PICK_BYTES = 40 * 1024 * 1024

/** `null` for an image bigger than [limit], so a huge file cannot exhaust memory. */
internal fun java.io.InputStream.readCapped(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * DESIGN B7: avatar (tap to change), display name, read-only email, a link to notification preferences (B9) and sign out. Name and photo edits are local first, so everything works offline.
 * [picker] is injected so tests can drive it.
 */
@Composable
fun ProfileScreen(
    userId: UUID,
    email: String?,
    profile: Profile?,
    deps: ProfileDeps,
    isOnline: Boolean,
    onProfileChanged: (Profile) -> Unit,
    onSignOut: () -> Unit,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    picker: @Composable (onPicked: (ByteArray?) -> Unit) -> (() -> Unit) = { onPicked -> rememberPhotoPicker(onPicked) },
) {
    val tokens = LocalTokens.current
    // Held by the activity's ViewModelStore, so a queued edit and the upload status survive rotation.
    val vm: ProfileViewModel = viewModel(key = "profile.$userId") {
        ProfileViewModel(
            userId, email, profile, deps.cache, deps.outbox, deps.sync, isOnline, onProfileChanged, deps.avatarDir, deps.prepareAvatar,
        )
    }
    // The instance outlives this composition, so give it the current callback and connectivity each time.
    SideEffect { vm.onProfileChanged = onProfileChanged }
    LaunchedEffect(isOnline) { vm.setOnline(isOnline) }
    // Leaving the screen with the field focused must not lose the edit.
    DisposableEffect(vm) { onDispose { vm.onNameCommitted() } }

    val state by vm.state.collectAsState()
    val pickAvatar = picker(vm::onAvatarPicked)
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val uploading = state.status == ProfileStatus.UploadingAvatar
    val name = state.profile?.displayName ?: state.nameDraft

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Tokens.ScreenPadding).testTag("profile.screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .clickable(enabled = !uploading, role = Role.Button, onClick = pickAvatar)
                .semantics { contentDescription = "Change profile photo" }
                .testTag("profile.avatar"),
            contentAlignment = Alignment.Center,
        ) {
            AvatarImage(
                initials = initialsOf(name),
                avatarPath = state.profile?.avatarPath, localFile = state.avatarFile, loader = deps.avatars, size = 96.dp,
            )
            if (uploading) {
                CircularProgressIndicator(
                    Modifier.size(40.dp).semantics { contentDescription = "Uploading photo" }.testTag("profile.uploading"),
                    color = tokens.accent,
                )
            }
        }
        state.notice?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = tokens.danger,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("profile.notice"),
            )
        }
        if (state.offline) {
            Text(
                if (state.avatarFile != null) "You're offline. Your photo will upload when you're back online."
                else "You're offline. Changes will sync when you're back online.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.warn,
                modifier = Modifier.fillMaxWidth().testTag("profile.offline"),
            )
        }
        OutlinedTextField(
            value = state.nameDraft,
            onValueChange = vm::onNameChange,
            label = { Text("Display name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.onNameCommitted(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                .onFocusChanged { if (focused && !it.isFocused) vm.onNameCommitted(); focused = it.isFocused }
                .testTag("profile.name"),
        )
        OutlinedTextField(
            value = state.email.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget).testTag("profile.email"),
        )
        Box(
            Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                .clickable(role = Role.Button, onClick = onOpenNotifications)
                .semantics(mergeDescendants = true) { contentDescription = "Notification preferences" }
                .testTag("profile.notifications"),
            contentAlignment = Alignment.CenterStart,
        ) { Text("Notification preferences", color = tokens.text) }
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.MinTouchTarget)
                .semantics { contentDescription = "Sign out" }.testTag("profile.signout"),
        ) { Text("Sign out", color = tokens.danger) }
    }
}
