package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

// Figma 16:8's `Attachment area` (390:7136): 45 × 60 tiles, 6dp corners, a 20dp remove icon overlapping each
// tile's top-trailing corner by 5dp. Each item reserves that overlap so the scrolling row does not clip it,
// and the item gap drops by the same 5dp so tiles keep the design's 12dp spacing.
private val TileWidth = 45.dp
private val TileHeight = 60.dp
private val TileCorner = RoundedCornerShape(6.dp)
private val RemoveOverlap = 5.dp
private val RemoveSize = 20.dp
private val RemoveCutOutSize = 14.dp
private val ItemGap = 12.dp - RemoveOverlap
private val TypeLabelWidth = 44.dp
private val SendingIndicatorStroke = 2.dp
private const val SENDING_ALPHA = 0.55f
private const val TYPE_LABEL_MAX_CHARS = 4

/** Marks the composer's attachment strip for screen tests (#933). A static tag; never a file name. */
const val ATTACHMENT_STRIP_TEST_TAG = "composer_attachment_strip"

/**
 * Figma `16:8`'s `Attachment area` (#933): this chat's pending attachments as a row of tiles between the
 * status area and the input field, in the order added. The screen mounts it only when there is at least one.
 *
 * An image shows a thumbnail, and any other file, or an image whose thumbnail fails, shows the `File` tile
 * with a short type label. Each tile's remove control drops only that entry. While [sending], the remove
 * controls give way to progress indicators, the tiles dim and the row says "Sending", because the send has
 * already taken its snapshot of these entries. The tile named by [uploadProgress] fills its indicator to that
 * figure instead of spinning, and the row says "Uploading… N%" (#1327). Neither is a live region, so no chunk
 * is announced. While the thread is not [connected], the file tiles take the muted tint of Figma `627:1740`
 * (#1532); image tiles are unchanged.
 *
 * File names come from another app's provider. They appear only as content descriptions and are never logged.
 */
@Composable
fun ComposerAttachmentStrip(
    attachments: List<PendingAttachment>,
    sending: Boolean,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
    uploadProgress: AttachmentUploadProgress? = null,
    connected: Boolean = true,
) {
    val sendingDescription =
        if (uploadProgress != null) {
            stringResource(R.string.thread_attachments_uploading, uploadProgress.percent)
        } else {
            stringResource(R.string.thread_attachments_sending)
        }
    LazyRow(
        modifier =
            modifier
                .testTag(ATTACHMENT_STRIP_TEST_TAG)
                .semantics { if (sending) stateDescription = sendingDescription },
        horizontalArrangement = Arrangement.spacedBy(ItemGap),
    ) {
        items(attachments, key = { it.key }) { attachment ->
            AttachmentItem(
                attachment = attachment,
                sending = sending,
                connected = connected,
                percent = uploadProgress?.takeIf { it.key == attachment.key }?.percent,
                onRemove = { onRemove(attachment.key) },
            )
        }
    }
}

@Composable
private fun AttachmentItem(
    attachment: PendingAttachment,
    sending: Boolean,
    connected: Boolean,
    percent: Int?,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.padding(top = RemoveOverlap, end = RemoveOverlap)) {
        val tileModifier =
            Modifier
                .size(TileWidth, TileHeight)
                .alpha(if (sending) SENDING_ALPHA else 1f)
                .semantics(mergeDescendants = true) { contentDescription = attachment.displayName }
        val thumbnail = if (attachment.mimeType.startsWith("image/")) rememberThumbnail(attachment) else null
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = tileModifier.clip(TileCorner),
            )
        } else {
            FileTile(displayName = attachment.displayName, connected = connected, modifier = tileModifier)
        }
        Box(
            // Over the tile's top-trailing corner, the reserved overlap outside it both ways.
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = RemoveOverlap, y = -RemoveOverlap)
                    .size(RemoveSize),
            contentAlignment = Alignment.Center,
        ) {
            if (sending && percent != null) {
                // #1327: the same indicator as drawn, with the indeterminate one's empty track; only the fill differs.
                CircularProgressIndicator(
                    progress = { percent / 100f },
                    strokeWidth = SendingIndicatorStroke,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                    modifier = Modifier.size(RemoveSize),
                )
            } else if (sending) {
                CircularProgressIndicator(
                    strokeWidth = SendingIndicatorStroke,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(RemoveSize),
                )
            } else {
                RemoveControl(name = attachment.displayName, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun RemoveControl(
    name: String,
    onRemove: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(RemoveSize)
                .shadow(2.dp, CircleShape)
                .clickable(role = Role.Button, onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        // The design's glyph is a disc with the x cut out, showing On Primary through it.
        Box(
            modifier =
                Modifier
                    .size(RemoveCutOutSize)
                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
        )
        Icon(
            painter = painterResource(R.drawable.ic_modal_close),
            contentDescription = stringResource(R.string.cd_remove_attachment, name),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(RemoveSize),
        )
    }
}

/** Figma's `File` tile: the outlined page with the type label centred below the fold. */
@Composable
private fun FileTile(
    displayName: String,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    // Connected, the page and 12sp type label take primary for text contrast on the dark thread. The
    // connection frames (627:1740, 627:4657, 627:4910) mute them to inverse primary until the host is back.
    val tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inversePrimary
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_attachment_file),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TileWidth, TileHeight),
        )
        Text(
            text = attachmentTypeLabel(displayName) ?: stringResource(R.string.thread_attachment_file),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(TypeLabelWidth).padding(top = RemoveCutOutSize),
        )
    }
}

/**
 * The pending source's thumbnail at tile size, or `null` while loading or unreadable (#933/#1727).
 * Only a minted owned-paste capability permits private reads. External sources retain the
 * [isForeignContentUri] boundary; externally supplied own-provider URIs are never loaded.
 * The load runs off the main thread and is cancelled through its [CancellationSignal] when the tile leaves,
 * so a provider that stalls holds nothing past that.
 */
@Composable
private fun rememberThumbnail(attachment: PendingAttachment): ImageBitmap? {
    val uri = attachment.uri
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { Size(TileHeight.roundToPx(), TileHeight.roundToPx()) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, uri) {
        val owned = attachment.ownedPaste
        if (owned != null) {
            value =
                withContext(Dispatchers.IO) {
                    owned
                        .withInput { input ->
                            val bytes = readBounded(input, AttachmentUploadLimit.MAX_BYTES) ?: return@withInput null
                            // ImageDecoder applies EXIF rotation and mirroring before sampling; BitmapFactory does not.
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                                var sample = 1
                                while (info.size.width / sample > sizePx.width * 2 || info.size.height / sample > sizePx.height * 2) {
                                    sample *= 2
                                }
                                decoder.setTargetSampleSize(sample)
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        }?.asImageBitmapOrNull()
                }
            return@produceState
        }
        val parsed = uri.toUri()
        if (!isForeignContentUri(parsed.scheme, parsed.authority, context.packageName)) return@produceState
        val signal = CancellationSignal()
        coroutineContext[Job]?.invokeOnCompletion { signal.cancel() }
        value =
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(parsed, sizePx, signal).asImageBitmapOrNull()
                } catch (e: Exception) {
                    // A provider's failure text is its own: dropped unread, the tile falls back to the file tile.
                    null
                }
            }
    }
    return thumbnail
}

private fun Bitmap.asImageBitmapOrNull(): ImageBitmap? = if (width > 0 && height > 0) asImageBitmap() else null

/**
 * The file tile's type label (#933): [displayName]'s extension, upper-cased, when it is one to four letters or
 * digits, else `null`. The name is untrusted, so the tile never shows it whole. This keeps what the tile does
 * show short and plain, with no spacing or control characters.
 */
internal fun attachmentTypeLabel(displayName: String): String? {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
    if (extension.length !in 1..TYPE_LABEL_MAX_CHARS || !extension.all { it.isLetterOrDigit() }) return null
    return extension.uppercase()
}

private val previewAttachments =
    listOf(
        PendingAttachment(key = 1, uri = "content://preview/1", displayName = "report.pdf", mimeType = "application/pdf", size = 1L),
        PendingAttachment(key = 2, uri = "content://preview/2", displayName = "notes", mimeType = "text/plain", size = 1L),
        PendingAttachment(key = 3, uri = "content://preview/3", displayName = "photo.jpg", mimeType = "image/jpeg", size = 1L),
    )

@Preview(name = "AttachmentStrip — Dark", showBackground = true, backgroundColor = 0xFF101418, widthDp = 372)
@Composable
private fun ComposerAttachmentStripDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ComposerAttachmentStrip(attachments = previewAttachments, sending = false, onRemove = {})
    }
}

@Preview(name = "AttachmentStrip — Light, sending", showBackground = true, widthDp = 372)
@Composable
private fun ComposerAttachmentStripLightSendingPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ComposerAttachmentStrip(attachments = previewAttachments, sending = true, onRemove = {})
    }
}
