package de.pyryco.mobile.ui.conversations.components

import android.content.ContentResolver
import android.content.res.Configuration
import android.graphics.ImageDecoder
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.ui.conversations.thread.attachmentTypeLabel
import de.pyryco.mobile.ui.conversations.thread.isForeignContentUri
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Figma 16:8, the `Slot` of the shared `Message` component: a 160 × 160 image, or `File field`
// (132:4605) — the 45 × 60 page glyph and the name, 12dp apart.
private val ImageSlotSize = 160.dp
private val FileGlyphWidth = 45.dp
private val FileGlyphHeight = 60.dp
private val FileFieldSpacing = 12.dp
private val TypeLabelWidth = 44.dp

// The glyph's fold: the composer's `File` tile drops its type label by the same amount.
private val TypeLabelFoldOffset = 14.dp
private val LoadingIndicatorSize = 24.dp
private val LoadingIndicatorStroke = 2.dp

// Loading and error text needs stronger contrast than the design's de-emphasized ready file field.
private const val ATTACHMENT_CONTENT_ALPHA = 0.80f
private const val IMAGE_PLACEHOLDER_ALPHA = 0.12f

// A header may ask for any aspect ratio. Covering the slot with the short side is what the crop needs;
// capping the long side is what keeps a 10^6 × 160 image from decoding at full width.
private const val THUMBNAIL_MAX_ASPECT = 4

/** The image slot of an attachment (#984). Static; never a file name. */
const val MESSAGE_ATTACHMENT_IMAGE_TEST_TAG = "message_attachment_image"

/** The file row of an attachment (#984). Static; never a file name. */
const val MESSAGE_ATTACHMENT_FILE_TEST_TAG = "message_attachment_file"

/**
 * Where a shown attachment's bytes are (#984). [toString] is redacted on both: a URI or a local path must
 * never reach a log or a crash trace.
 */
sealed interface AttachmentSource {
    /** The file this phone picked and sent in this app session, still readable through its grant. */
    data class Original(
        val uri: String,
    ) : AttachmentSource {
        override fun toString(): String = "Original"
    }

    /** The copy `AttachmentStore` keeps for the thread's host. */
    data class Kept(
        val file: File,
    ) : AttachmentSource {
        override fun toString(): String = "Kept"
    }
}

/**
 * One attachment's state in the thread (#984), keyed by its id. An id with no state yet draws as [Loading].
 * [Ready]'s hints come from retrieval, which sanitised them; they are display text only and fill in only
 * what the message's own reference left unknown.
 */
sealed interface AttachmentViewState {
    data object Loading : AttachmentViewState

    data class Ready(
        val source: AttachmentSource,
        val displayName: String?,
        val mimeType: String?,
    ) : AttachmentViewState {
        override fun toString(): String = "Ready(source=$source)"
    }

    /** The host has no such attachment. Final: there is nothing to retry. */
    data object NotFound : AttachmentViewState

    /** Retrieval failed and may be retried. */
    data object Failed : AttachmentViewState
}

/**
 * One attachment as its open and save actions see it (#985): the id, and the name and MIME type the item
 * shows — the reference's own, filled in from retrieval. Both are sanitised hints: the name is only ever the
 * save picker's suggestion, and the type only picks among the user's viewers. [toString] prints the id only.
 */
data class AttachmentTarget(
    val attachmentId: String,
    val displayName: String?,
    val mimeType: String?,
) {
    override fun toString(): String = "AttachmentTarget(id=$attachmentId)"
}

/**
 * Decodes a thumbnail no larger than a slot of [sizePx] needs, or `null` when it cannot (#984). A seam so a
 * screen test or preview can stand in for the platform decoder.
 */
fun interface AttachmentThumbnailDecoder {
    suspend fun decode(
        source: AttachmentSource,
        sizePx: Int,
    ): ImageBitmap?
}

/** Overrides the platform thumbnail decoder; `null` means use it. For tests and previews. */
internal val LocalAttachmentThumbnailDecoder = staticCompositionLocalOf<AttachmentThumbnailDecoder?> { null }

/**
 * The size to decode a [width] × [height] image at for a [sizePx] square slot (#984), or `null` when the
 * header's dimensions are unusable. Never upscales. The short side covers the slot, and the long side is
 * capped at [THUMBNAIL_MAX_ASPECT] slots, because the image bytes are attacker-shaped.
 */
internal fun thumbnailTargetSize(
    width: Int,
    height: Int,
    sizePx: Int,
): IntSize? {
    if (width <= 0 || height <= 0 || sizePx <= 0) return null
    val scale =
        minOf(
            1.0,
            sizePx.toDouble() / min(width, height),
            (sizePx.toDouble() * THUMBNAIL_MAX_ASPECT) / max(width, height),
        )
    return IntSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
}

/**
 * A message's attachments in its bubble (#984), in reference order. Each reports itself shown when it is
 * composed — in the thread's lazy list, only when it is on screen — and that is what starts its retrieval.
 * A [AttachmentViewState.Ready] one opens on a tap ([onOpen]) and saves on a long-press ([onSave], #985);
 * one in any other state offers neither.
 */
@Composable
internal fun MessageAttachments(
    attachments: List<MessageAttachment>,
    states: Map<String, AttachmentViewState>,
    onShown: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (AttachmentTarget) -> Unit = {},
    onSave: (AttachmentTarget) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BubbleContentSpacing)) {
        for (attachment in attachments) {
            key(attachment.attachmentId) {
                MessageAttachmentItem(
                    attachment = attachment,
                    state = states[attachment.attachmentId] ?: AttachmentViewState.Loading,
                    onShown = onShown,
                    onRetry = onRetry,
                    onOpen = onOpen,
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun MessageAttachmentItem(
    attachment: MessageAttachment,
    state: AttachmentViewState,
    onShown: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (AttachmentTarget) -> Unit,
    onSave: (AttachmentTarget) -> Unit,
) {
    val id = attachment.attachmentId
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(id) { currentOnShown(id) }
    val ready = state as? AttachmentViewState.Ready
    // The message's own reference wins; retrieval only fills what it left unknown. Both are hints: the
    // MIME type picks a layout, never a viewer, and a wrong one ends in the file row below.
    val name = attachment.displayName.nonBlank() ?: ready?.displayName.nonBlank()
    val mimeType = attachment.mimeType.nonBlank() ?: ready?.mimeType.nonBlank()
    val isImage = mimeType?.startsWith("image/", ignoreCase = true) == true
    // #985: only a file that is here acts. Loading, not found and failed offer neither open nor save.
    val actions =
        if (ready != null) {
            val target = AttachmentTarget(id, name, mimeType)
            Modifier.attachmentActions(onOpen = { onOpen(target) }, onSave = { onSave(target) })
        } else {
            Modifier
        }
    val fileRow = @Composable { AttachmentFileRow(name = name, state = state, onRetry = { onRetry(id) }, modifier = actions) }
    if (isImage && (state is AttachmentViewState.Loading || ready != null)) {
        ImageAttachment(name = name, source = ready?.source, fallback = fileRow, modifier = actions)
    } else {
        fileRow()
    }
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

/**
 * Tap opens, long-press saves (#985). The frame draws no save control, so the long-press carries its own
 * label, which TalkBack offers as a named action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.attachmentActions(
    onOpen: () -> Unit,
    onSave: () -> Unit,
): Modifier =
    combinedClickable(
        onClickLabel = stringResource(R.string.thread_attachment_open),
        onLongClickLabel = stringResource(R.string.thread_attachment_save),
        onLongClick = onSave,
        onClick = onOpen,
    )

private sealed interface Thumbnail {
    data object Pending : Thumbnail

    class Decoded(
        val bitmap: ImageBitmap,
    ) : Thumbnail

    data object Undecodable : Thumbnail
}

/**
 * The 160 × 160 slot, the same size from the first frame to the last, so a thumbnail that lands late does
 * not resize the row. Only a decode failure changes it, to [fallback].
 */
@Composable
private fun ImageAttachment(
    name: String?,
    source: AttachmentSource?,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decoder = LocalAttachmentThumbnailDecoder.current ?: rememberPlatformThumbnailDecoder()
    val sizePx = with(LocalDensity.current) { ImageSlotSize.roundToPx() }
    val thumbnail by produceState<Thumbnail>(Thumbnail.Pending, source, decoder, sizePx) {
        if (source != null) value = decoder.decode(source, sizePx)?.let { Thumbnail.Decoded(it) } ?: Thumbnail.Undecodable
    }
    val current = thumbnail
    if (current is Thumbnail.Undecodable) return fallback()
    val label = name ?: stringResource(R.string.thread_attachment_unnamed)
    val loading = stringResource(R.string.thread_attachment_loading)
    Box(
        modifier =
            Modifier
                .testTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG)
                // Square at 160dp, or at the bubble's width on a screen too narrow for that, so the slot
                // never widens the bubble. Never fillMaxWidth: that would pin every bubble to its lane.
                .sizeIn(maxWidth = ImageSlotSize, maxHeight = ImageSlotSize)
                .aspectRatio(1f)
                .clip(BubbleShape)
                .background(LocalContentColor.current.copy(alpha = IMAGE_PLACEHOLDER_ALPHA))
                .then(modifier)
                .semantics {
                    contentDescription = label
                    if (current !is Thumbnail.Decoded) stateDescription = loading
                },
        contentAlignment = Alignment.Center,
    ) {
        if (current is Thumbnail.Decoded) {
            Image(
                bitmap = current.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                strokeWidth = LoadingIndicatorStroke,
                color = LocalContentColor.current.copy(alpha = ATTACHMENT_CONTENT_ALPHA),
                modifier = Modifier.size(LoadingIndicatorSize),
            )
        }
    }
}

/**
 * Figma's `File field`: the page glyph with a short type label, then the name on one line, shortened in
 * the middle so the extension stays visible, and never wider than the bubble. Below the name, the state
 * when it is not ready: loading, not found, or a failure with its retry.
 */
@Composable
private fun AttachmentFileRow(
    name: String?,
    state: AttachmentViewState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.inversePrimary
    val statusTint = LocalContentColor.current.copy(alpha = ATTACHMENT_CONTENT_ALPHA)
    Row(
        modifier = Modifier.testTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).then(modifier),
        horizontalArrangement = Arrangement.spacedBy(FileFieldSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileGlyph(name = name, tint = tint)
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = name ?: stringResource(R.string.thread_attachment_unnamed),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val status =
                when (state) {
                    AttachmentViewState.Loading -> R.string.thread_attachment_loading
                    AttachmentViewState.NotFound -> R.string.thread_attachment_not_found
                    AttachmentViewState.Failed -> R.string.thread_attachment_failed
                    is AttachmentViewState.Ready -> null
                }
            status?.let { Text(text = stringResource(it), style = MaterialTheme.typography.bodySmall, color = statusTint) }
            if (state is AttachmentViewState.Failed) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.thread_attachment_retry)) }
            }
        }
    }
}

/** The page glyph with the type label below its fold, the composer `File` tile's drawing (#933). */
@Composable
private fun FileGlyph(
    name: String?,
    tint: Color,
) {
    Box(modifier = Modifier.size(FileGlyphWidth, FileGlyphHeight), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_attachment_file),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(FileGlyphWidth, FileGlyphHeight),
        )
        Text(
            text = name?.let(::attachmentTypeLabel) ?: stringResource(R.string.thread_attachment_file),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(TypeLabelWidth).padding(top = TypeLabelFoldOffset),
        )
    }
}

@Composable
private fun rememberPlatformThumbnailDecoder(): AttachmentThumbnailDecoder {
    val context = LocalContext.current
    return remember(context) { PlatformThumbnailDecoder(context.contentResolver, context.packageName) }
}

/**
 * [ImageDecoder] at [thumbnailTargetSize], off the main thread (#984). An original is decoded only when it
 * is another app's `content` URI ([isForeignContentUri]), so a hostile picker result cannot show this app's
 * own files. Every failure is `null` and its exception is dropped unread: a provider's message can carry
 * the URI, and a decoder's can carry the path.
 */
private class PlatformThumbnailDecoder(
    private val resolver: ContentResolver,
    private val ownPackage: String,
) : AttachmentThumbnailDecoder {
    override suspend fun decode(
        source: AttachmentSource,
        sizePx: Int,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            try {
                val decoderSource =
                    when (source) {
                        is AttachmentSource.Original -> {
                            val uri = source.uri.toUri()
                            if (!isForeignContentUri(uri.scheme, uri.authority, ownPackage)) return@withContext null
                            ImageDecoder.createSource(resolver, uri)
                        }
                        is AttachmentSource.Kept -> ImageDecoder.createSource(source.file)
                    }
                ImageDecoder
                    .decodeBitmap(decoderSource) { decoder, info, _ ->
                        val target = thumbnailTargetSize(info.size.width, info.size.height, sizePx) ?: throw IOException()
                        decoder.setTargetSize(target.width, target.height)
                    }.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
}

private val PreviewThumbnails = AttachmentThumbnailDecoder { _, _ -> ImageBitmap(160, 160) }

private val PreviewKept = AttachmentSource.Kept(File("preview"))

@Composable
private fun MessageAttachmentsPreviewContent() {
    CompositionLocalProvider(LocalAttachmentThumbnailDecoder provides PreviewThumbnails) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            MessageAttachments(
                attachments =
                    listOf(
                        MessageAttachment("a1", "rock.jpg", "image/jpeg"),
                        MessageAttachment("a2", "Filename of the Best file attachment that the assistant generated.pdf", "application/pdf"),
                        MessageAttachment("a3", "notes.txt", "text/plain"),
                        MessageAttachment("a4"),
                        MessageAttachment("a5", "gone.zip", "application/zip"),
                    ),
                states =
                    mapOf(
                        "a1" to AttachmentViewState.Ready(PreviewKept, null, null),
                        "a2" to AttachmentViewState.Ready(PreviewKept, null, null),
                        "a3" to AttachmentViewState.Failed,
                        "a5" to AttachmentViewState.NotFound,
                    ),
                onShown = {},
                onRetry = {},
                modifier = Modifier.width(232.dp).padding(16.dp),
            )
        }
    }
}

@Preview(name = "MessageAttachments — Light", showBackground = true)
@Composable
private fun MessageAttachmentsLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) { MessageAttachmentsPreviewContent() }
}

@Preview(name = "MessageAttachments — Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MessageAttachmentsDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) { MessageAttachmentsPreviewContent() }
}
