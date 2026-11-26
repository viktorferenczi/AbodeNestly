package com.abodenestly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** What the DESIGN A6 sync chip shows. [Hidden] when there is nothing to say (idle, nothing waiting). */
sealed interface SyncChipState {
    data object Hidden : SyncChipState
    data object Syncing : SyncChipState
    data object Offline : SyncChipState
    data class Pending(val count: Int) : SyncChipState
}

/** Precedence as web and iOS: syncing, then offline, then "N to sync" (queued plus failed), else nothing. */
fun syncChipState(syncing: Boolean, offline: Boolean, pending: Int, failed: Int): SyncChipState = when {
    syncing -> SyncChipState.Syncing
    offline -> SyncChipState.Offline
    pending + failed > 0 -> SyncChipState.Pending(pending + failed)
    else -> SyncChipState.Hidden
}

/** DESIGN A6 `SyncStatusChip`: a 48 dp target that opens the sync sheet (B12). Draws nothing for [SyncChipState.Hidden]. */
@Composable
fun SyncStatusChip(state: SyncChipState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state == SyncChipState.Hidden) return
    val tokens = LocalTokens.current
    val label = when (state) {
        SyncChipState.Syncing -> "Syncing"
        SyncChipState.Offline -> "Offline"
        is SyncChipState.Pending -> "${state.count} to sync"
        SyncChipState.Hidden -> ""
    }
    val color = if (state == SyncChipState.Offline) tokens.warn else tokens.text2
    Row(
        modifier
            .heightIn(min = Tokens.MinTouchTarget)
            .widthIn(min = Tokens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("sync.chip")
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.background(tokens.surface2, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (state) {
                SyncChipState.Syncing -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
                SyncChipState.Offline -> Icon(CloudOffIcon, contentDescription = null, Modifier.size(16.dp), tint = color)
                else -> Icon(CloudUploadIcon, contentDescription = null, Modifier.size(16.dp), tint = color)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
    }
}

// Material "cloud_upload" and "cloud_off" paths, drawn here so the app needs no icon-pack dependency.
private fun cloudIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

private val CloudUploadIcon: ImageVector = cloudIcon(
    "CloudUpload",
    "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM14,13v4h-4v-4H7l5,-5 5,5h-3z",
)

private val CloudOffIcon: ImageVector = cloudIcon(
    "CloudOff",
    "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3 0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
)
