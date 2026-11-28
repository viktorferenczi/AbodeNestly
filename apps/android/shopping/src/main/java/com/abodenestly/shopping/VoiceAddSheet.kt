package com.abodenestly.shopping

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.MicIcon
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.launch

internal const val VOICE_SAVE_FAILED = "Could not save the change. Try again."
internal const val VOICE_NO_ITEM = "Didn't catch an item. Try again."
internal const val VOICE_NOT_HEARD = "Didn't catch that."
internal const val VOICE_DENIED = "Microphone access is off. Allow it in Settings, or type the item instead."
internal const val VOICE_UNSUPPORTED = "Voice input isn't available here. Type the item instead."

/**
 * What the list detail needs to offer voice add (SHOP-14): how to get a recognizer. Absent (`null`), the quick-add bar shows
 * no mic button. [device] is composed when the sheet opens, so the permission launcher lives and dies with it; tests return a fake.
 */
class VoiceSetup(val device: @Composable () -> SpeechRecognizerDevice)

/** The C6 sheet as [ListDetailScreenHost] mounts it; tests mount the sheet's body inline instead of a popup. */
internal typealias VoiceSlot = @Composable (model: VoiceAddModel, onClose: () -> Unit, onTypeInstead: () -> Unit) -> Unit

/** The app's voice setup: the platform `SpeechRecognizer` and the system microphone permission dialog. */
@Composable
fun rememberVoiceSetup(): VoiceSetup = remember { VoiceSetup(device = { rememberAndroidSpeechRecognizerDevice() }) }

/** DESIGN C6 in a [ModalBottomSheet]. Dismissing it (swipe, scrim, Back) is Cancel: nothing is written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAddSheet(model: VoiceAddModel, onClose: () -> Unit, onTypeInstead: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = LocalTokens.current.bg) {
        VoiceAddContent(model, onClose, onTypeInstead)
    }
}

/**
 * The sheet's body. States: `listening` (pulsing mic, "Listening…", the live transcript as a polite live region, Cancel),
 * `parsed` (the transcript, read-only chips, Add, Try again), no item, not heard, `permission-denied` and `unsupported`.
 * Opening starts the model; leaving composition stops it. Tested directly so Robolectric never drives the popup.
 */
@Composable
internal fun VoiceAddContent(model: VoiceAddModel, onClose: () -> Unit, onTypeInstead: () -> Unit) {
    val tokens = LocalTokens.current
    val context: Context = LocalContext.current
    val ui: VoiceUiState by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.start() }
    // Closing (Cancel, Add, Back, the scrim) releases the microphone.
    DisposableEffect(model) { onDispose { model.stop() } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenPadding).padding(bottom = Tokens.ScreenPadding).testTag("voice.sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Voice add", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = tokens.text,
            modifier = Modifier.semantics { heading() },
        )
        when (val phase = ui.phase) {
            is VoicePhase.Listening -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningIndicator() }
                Text(
                    "Listening…", style = MaterialTheme.typography.titleMedium, color = tokens.text,
                    modifier = Modifier.fillMaxWidth().testTag("voice.listening"),
                )
                Text(
                    phase.partial, style = MaterialTheme.typography.bodyLarge, color = tokens.text2,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("voice.transcript")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onClose, modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("voice.cancel")) {
                    Text("Cancel", color = tokens.accent)
                }
            }
            is VoicePhase.Parsed -> {
                Text(
                    phase.transcript, style = MaterialTheme.typography.bodyLarge, color = tokens.text2,
                    modifier = Modifier.fillMaxWidth().testTag("voice.parsed.transcript"),
                )
                Chips(phase.item)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { if (model.addParsed()) onClose() } },
                        enabled = !ui.adding,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("voice.add"),
                    ) { Text("Add") }
                    OutlinedButton(
                        onClick = { scope.launch { model.tryAgain() } },
                        enabled = !ui.adding,
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("voice.tryagain"),
                    ) { Text("Try again") }
                }
                if (ui.addFailed) {
                    Text(
                        VOICE_SAVE_FAILED, color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("voice.error").semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
            is VoicePhase.NoItem -> {
                Text(
                    phase.transcript, style = MaterialTheme.typography.bodyLarge, color = tokens.text2,
                    modifier = Modifier.fillMaxWidth().testTag("voice.parsed.transcript"),
                )
                Message(VOICE_NO_ITEM)
                TryAgainButton { scope.launch { model.tryAgain() } }
            }
            VoicePhase.NotHeard -> {
                Message(VOICE_NOT_HEARD)
                TryAgainButton { scope.launch { model.tryAgain() } }
            }
            VoicePhase.PermissionDenied -> {
                Message(VOICE_DENIED)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("voice.settings"),
                    ) { Text("Open settings") }
                    TypeInsteadButton(onTypeInstead)
                }
            }
            VoicePhase.Unsupported -> {
                Message(VOICE_UNSUPPORTED)
                TypeInsteadButton(onTypeInstead)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = LocalTokens.current.text,
        modifier = Modifier.fillMaxWidth().testTag("voice.message").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun TryAgainButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("voice.tryagain")) {
        Text("Try again")
    }
}

@Composable
private fun TypeInsteadButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = LocalTokens.current.accent, contentColor = LocalTokens.current.surface),
        modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("voice.type"),
    ) { Text("Type instead") }
}

/** The quantity, unit (only those that were said) and name, read-only; each chip is one TalkBack node ("Quantity 2", "Unit l", "Name Milk"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(item: SpokenItem) {
    // FlowRow, so a long name or a large font scale wraps instead of running off the sheet.
    FlowRow(
        Modifier.fillMaxWidth().testTag("voice.chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.quantity?.let { Chip("voice.chip.quantity", "Quantity", ItemSheetLogic.quantityText(it)) }
        item.unit?.let { Chip("voice.chip.unit", "Unit", it) }
        Chip("voice.chip.name", "Name", item.name)
    }
}

@Composable
private fun Chip(tag: String, label: String, value: String) {
    val tokens = LocalTokens.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.accentBg,
        border = BorderStroke(1.dp, tokens.line),
        modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) { contentDescription = "$label $value" },
    ) {
        Text(
            value, style = MaterialTheme.typography.bodyLarge, color = tokens.text,
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The pulsing mic of `listening`. The pulse state lives here, so it starts again each time `listening` is entered (Try
 * again) and goes with the composition when the state changes. Still when animations are off.
 */
@Composable
internal fun ListeningIndicator(modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val context: Context = LocalContext.current
    val animate: Boolean = remember(context) { animationsOn(context) }
    val pulse: Float = if (animate) {
        rememberInfiniteTransition(label = "voice.pulse").animateFloat(
            initialValue = 1f, targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "voice.pulse.scale",
        ).value
    } else {
        1f
    }
    Surface(
        shape = CircleShape, color = tokens.accentBg,
        modifier = modifier.size(72.dp).scale(pulse).testTag("voice.indicator"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(MicIcon, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(32.dp))
        }
    }
}

/** False when the person turned animations off (the system's "Remove animations": the animator duration scale is 0). */
private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
