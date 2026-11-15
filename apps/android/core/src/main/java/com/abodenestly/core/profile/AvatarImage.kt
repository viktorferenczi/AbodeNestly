package com.abodenestly.core.profile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abodenestly.ui.Avatar

/**
 * The person's picture, or their initials while it loads, when it fails and when the device is offline.
 * A queued local picture wins over the stored one.
 */
@Composable
fun AvatarImage(
    initials: String,
    avatarPath: String?,
    localFile: String?,
    loader: AvatarLoader,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    var bitmap by remember(avatarPath, localFile, loader) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(avatarPath, localFile, loader) { bitmap = loadPicture(loader, avatarPath, localFile) }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            loaded.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape).testTag("avatar-image"),
        )
    } else {
        Avatar(initials, modifier, size)
    }
}

private suspend fun loadPicture(loader: AvatarLoader, avatarPath: String?, localFile: String?): Bitmap? =
    localFile?.let { loader.loadFile(it) } ?: avatarPath?.let { loader.load(it) }

/** "Anna Kovacs" → "AK"; a single name gives one letter; nothing gives "?". */
fun initialsOf(displayName: String): String {
    val words = displayName.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val first = words.firstOrNull()?.first() ?: return "?"
    val last = if (words.size > 1) words.last().first().toString() else ""
    return (first.toString() + last).uppercase()
}
