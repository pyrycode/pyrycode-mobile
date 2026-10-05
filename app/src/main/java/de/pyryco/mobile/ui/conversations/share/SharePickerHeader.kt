package de.pyryco.mobile.ui.conversations.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.thread.PendingAttachment
import de.pyryco.mobile.ui.conversations.thread.rememberThumbnail
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
internal fun SharePickerHeader(
    state: SharedContent,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag(if (state.capturing) "share-capturing" else "share-ready").padding(bottom = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_thread_back), stringResource(R.string.cd_back), tint = colors.onSurface)
            }
            Text(stringResource(R.string.share_to_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val image = state.files.firstOrNull { it.mimeType.startsWith("image/") }
            val thumbnail =
                key(state.generation, image?.ownedPaste) {
                    image?.let {
                        rememberThumbnail(
                            PendingAttachment(0, it.uri, it.displayName, it.mimeType, it.size, ownedPaste = it.ownedPaste),
                        )
                    }
                }
            val shape = MaterialTheme.shapes.small
            Box(
                Modifier
                    .size(
                        48.dp,
                    ).testTag("share-preview")
                    .clip(shape)
                    .background(colors.surfaceContainerHigh)
                    .border(1.dp, colors.onSurfaceVariant, shape),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        thumbnail,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).testTag("share-image-preview"),
                        contentScale = ContentScale.Crop,
                    )
                } else if (state.capturing) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Icon(
                        painterResource(R.drawable.ic_attachment_file),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val summary =
                    when {
                        state.files.isEmpty() -> state.text
                        state.files.all {
                            it.mimeType.startsWith(
                                "image/",
                            )
                        } -> pluralStringResource(R.plurals.share_images, state.files.size, state.files.size)
                        else -> pluralStringResource(R.plurals.share_files, state.files.size, state.files.size)
                    }
                Text(
                    summary,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.files.firstOrNull()?.let {
                    Text(
                        it.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun SharePickerHeaderPreview() {
    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
        SharePickerHeader(SharedContent(1, "A shared link", emptyList(), false), {})
    }
}
