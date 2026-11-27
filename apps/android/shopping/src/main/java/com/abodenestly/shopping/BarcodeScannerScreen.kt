package com.abodenestly.shopping

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.abodenestly.ui.LocalTokens
import com.abodenestly.ui.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The full-screen modal of DESIGN C7. Closing (Close, Back, or an Add) calls [onClose]. */
@Composable
fun BarcodeScannerDialog(model: BarcodeScannerModel, device: ScannerDevice, images: ProductImageLoader, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BarcodeScannerScreen(model, device, images, onClose)
    }
}

/**
 * DESIGN C7 states `scanning`, `found`, `not-found`, `permission-denied` and `offline` (here `queued`), plus `looking`,
 * the failed lookup, `unsupported` and `camera-unavailable`. Interactions: torch, enter code manually, Add. Manual
 * entry is on offer in every state without a result card, and takes focus when there is no camera.
 */
@Composable
internal fun BarcodeScannerScreen(
    model: BarcodeScannerModel,
    device: ScannerDevice,
    images: ProductImageLoader,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTokens.current
    val ui by model.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val manualFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    val phase = ui.phase

    LaunchedEffect(model) { model.startCamera(owner) }
    // Leaving the screen (Close, Back, an Add) stops the camera.
    DisposableEffect(model) { onDispose { model.stop() } }
    LaunchedEffect(phase.lacksCamera) { if (phase.lacksCamera) manualFocus.focusSafely() }
    LaunchedEffect(phase is ScannerPhase.NotFound || phase is ScannerPhase.Failed) {
        if (phase is ScannerPhase.NotFound || phase is ScannerPhase.Failed) nameFocus.focusSafely()
    }

    Column(
        modifier.fillMaxSize().background(tokens.bg).safeDrawingPadding().testTag("barcode.screen"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Tokens.ScreenPadding, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Scan barcode", style = MaterialTheme.typography.titleLarge, color = tokens.text,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(
                onClick = {
                    model.stop()
                    onClose()
                },
                modifier = Modifier.size(Tokens.MinTouchTarget).testTag("barcode.close"),
            ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = tokens.text) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Tokens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.addFailed) {
                Text(
                    "Couldn't add that item. Try again.", color = tokens.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("barcode.error"),
                )
            }
            when (phase) {
                ScannerPhase.Scanning -> Scanning(ui, device, manualOpen, { manualOpen = true }, model, scope, manualFocus)
                is ScannerPhase.Looking -> Row(
                    Modifier.testTag("barcode.looking"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = tokens.accent, strokeWidth = 2.dp)
                    Text("Looking up ${phase.code}…", color = tokens.text2)
                }
                is ScannerPhase.Found -> FoundCard(
                    phase, ui, images,
                    onAgain = { scope.launch { model.scanAgain() } },
                    onAdd = { scope.launch { if (model.addFound()) onClose() } },
                )
                is ScannerPhase.NotFound -> NameCard(
                    "We don't know this product", phase.code, retry = false, ui, model, nameFocus,
                    onAgain = { scope.launch { model.scanAgain() } },
                    onAdd = { scope.launch { if (model.addNamed()) onClose() } },
                )
                is ScannerPhase.Failed -> NameCard(
                    "Couldn't look that up. Try again.", phase.code, retry = true, ui, model, nameFocus,
                    onAgain = { scope.launch { model.retry() } },
                    onAdd = { scope.launch { if (model.addNamed()) onClose() } },
                )
                is ScannerPhase.Queued -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Added ${phase.code}. We'll look it up when you're back online", color = tokens.text, modifier = Modifier.testTag("barcode.queued"))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { model.scanAgain() } },
                            modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("barcode.again"),
                        ) { Text("Scan another", color = tokens.accent) }
                        PrimaryButton("Done", "barcode.done", enabled = true, onClick = onClose)
                    }
                }
                ScannerPhase.PermissionDenied -> NoCamera(
                    "Camera access is off",
                    "Allow the camera for AbodeNestly in Settings, then open the scanner again. You can still enter the code.",
                    ui, model, scope, manualFocus, showSettings = true,
                )
                ScannerPhase.CameraUnavailable -> NoCamera(
                    "Can't use the camera", "Another app may be using it. You can still enter the code.",
                    ui, model, scope, manualFocus, showSettings = false,
                )
                ScannerPhase.Unsupported -> NoCamera(
                    "Scanning isn't available on this device", "You can still enter the code printed under the barcode.",
                    ui, model, scope, manualFocus, showSettings = false,
                )
            }
        }
    }
}

@Composable
private fun Scanning(
    ui: ScannerUiState,
    device: ScannerDevice,
    manualOpen: Boolean,
    openManual: () -> Unit,
    model: BarcodeScannerModel,
    scope: kotlinx.coroutines.CoroutineScope,
    manualFocus: FocusRequester,
) {
    val tokens = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Tokens.CardRadius)).background(Color.Black).testTag("barcode.preview"),
        ) {
            device.Preview(Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth(0.75f).height(120.dp)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .testTag("barcode.guide"),
            )
        }
        Text("Point the camera at a barcode", color = tokens.text2, modifier = Modifier.testTag("barcode.hint"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.hasTorch) {
                OutlinedButton(
                    onClick = model::toggleTorch,
                    modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("barcode.torch")
                        .semantics { contentDescription = "Torch"; stateDescription = if (ui.torchOn) "On" else "Off" },
                ) { Text("Torch", color = tokens.accent) }
            }
            if (!manualOpen) {
                OutlinedButton(
                    onClick = openManual,
                    modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("barcode.manual.open"),
                ) { Text("Enter code manually", color = tokens.accent) }
            }
        }
        if (manualOpen) ManualForm(ui, model, scope, manualFocus)
    }
}

@Composable
private fun ManualForm(ui: ScannerUiState, model: BarcodeScannerModel, scope: kotlinx.coroutines.CoroutineScope, focus: FocusRequester) {
    val tokens = LocalTokens.current
    val submit: () -> Unit = { scope.launch { model.submitCode() } }
    LaunchedEffect(Unit) { focus.focusSafely() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Enter code manually", style = MaterialTheme.typography.titleSmall, color = tokens.text2)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.code,
                onValueChange = model::setCode,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = Tokens.MinTouchTarget).focusRequester(focus).testTag("barcode.manual.field"),
                label = { Text("Barcode") },
                singleLine = true,
                isError = ui.codeError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            PrimaryButton("Look up", "barcode.manual.submit", enabled = true, onClick = submit)
        }
        if (ui.codeError) {
            Text(Barcode.CODE_ERROR, color = tokens.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("barcode.manual.error"))
        }
    }
}

@Composable
private fun NoCamera(
    title: String,
    hint: String,
    ui: ScannerUiState,
    model: BarcodeScannerModel,
    scope: kotlinx.coroutines.CoroutineScope,
    focus: FocusRequester,
    showSettings: Boolean,
) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.text, modifier = Modifier.testTag("barcode.nocamera.title"))
        Text(hint, color = tokens.text2, modifier = Modifier.testTag("barcode.nocamera.hint"))
        if (showSettings) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("barcode.settings"),
            ) { Text("Open settings", color = tokens.accent) }
        }
        ManualForm(ui, model, scope, focus)
    }
}

@Composable
private fun FoundCard(phase: ScannerPhase.Found, ui: ScannerUiState, images: ProductImageLoader, onAgain: () -> Unit, onAdd: () -> Unit) {
    val tokens = LocalTokens.current
    val product = phase.product
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProductImage(product.imageUrl, product.name, images)
        Text(product.name, style = MaterialTheme.typography.titleLarge, color = tokens.text, modifier = Modifier.testTag("barcode.found.name"))
        product.brand?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = tokens.text2, modifier = Modifier.testTag("barcode.found.brand"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onAgain,
                modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag("barcode.again"),
            ) { Text("Scan again", color = tokens.accent) }
            PrimaryButton("Add", "barcode.add", enabled = !ui.adding, onClick = onAdd)
        }
    }
}

/** The picture is shown only for an https URL, and only once it has loaded: a failed load shows nothing. */
@Composable
private fun ProductImage(url: String?, name: String, images: ProductImageLoader) {
    val safe = Barcode.imageUrl(url) ?: return
    val bitmap by produceState<Bitmap?>(null, safe) {
        value = try {
            images.load(safe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(), contentDescription = name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(160.dp).testTag("barcode.image"),
        )
    }
}

/** Not found and failed share this card: the code, a name field, and either Scan again or Retry beside Add. */
@Composable
private fun NameCard(
    title: String,
    code: String,
    retry: Boolean,
    ui: ScannerUiState,
    model: BarcodeScannerModel,
    focus: FocusRequester,
    onAgain: () -> Unit,
    onAdd: () -> Unit,
) {
    val tokens = LocalTokens.current
    val typed = ui.name.trim()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, color = tokens.text,
            modifier = Modifier.testTag(if (retry) "barcode.failed.title" else "barcode.notfound.title"),
        )
        Text(code, color = tokens.text2, modifier = Modifier.testTag("barcode.code"))
        OutlinedTextField(
            value = ui.name,
            onValueChange = model::setName,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = Tokens.MinTouchTarget).focusRequester(focus).testTag("barcode.name.field"),
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (typed.isNotEmpty()) onAdd() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onAgain,
                modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag(if (retry) "barcode.retry" else "barcode.again"),
            ) { Text(if (retry) "Retry" else "Scan again", color = tokens.accent) }
            PrimaryButton("Add", "barcode.add", enabled = typed.isNotEmpty() && !ui.adding, onClick = onAdd)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = tokens.accent, contentColor = tokens.surface),
        modifier = Modifier.defaultMinSize(minHeight = Tokens.MinTouchTarget).testTag(tag),
    ) { Text(label) }
}

private fun FocusRequester.focusSafely() {
    // The field may not be attached yet when the effect runs; the next state change asks again.
    runCatching { requestFocus() }
}
