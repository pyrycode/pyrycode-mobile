package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One file the system picker handed back (#933), described at pick time so an oversized file is refused
 * before send. [uri] stays a string, as in [PendingAttachment]; [displayName], [mimeType] and [size] are
 * the provider's own, untrusted, and clamped or re-checked where [ComposerDraftStore] takes them.
 *
 * **Never logged.** [toString] prints the size only.
 */
data class PickedAttachment(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val size: Long?,
) {
    override fun toString(): String = "PickedAttachment(size=$size)"
}

/** How many of one pick's entries [ThreadViewModel.addPickedAttachments] refused, by reason (#933). */
data class AttachmentRefusal(
    val tooLarge: Int,
    val tooMany: Int,
)

private const val ANY_TYPE = "*/*"
private const val FALLBACK_MIME_TYPE = "application/octet-stream"
private const val FALLBACK_DISPLAY_NAME = "file"
private const val IMAGE_TYPES = "image/*"

/**
 * The composer's file picker (#933): returns the action that opens Android's document picker for any file
 * type, images included, with multiple selection. It hands content URIs back with a grant of their own, so
 * no storage permission is needed or requested.
 *
 * A cancel returns no URIs and calls nothing. Otherwise each URI is described off the main thread — a
 * provider query is a binder call another app answers — and [onPicked] gets them once, in the picker's
 * order. The work is bound to this composition and dropped with it.
 */
@Composable
fun rememberAttachmentPicker(onPicked: (List<PickedAttachment>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val picked =
                    withContext(Dispatchers.IO) {
                        uris.mapNotNull { describePickedAttachment(context.contentResolver, it, context.packageName) }
                    }
                RelayLog.d { "event=composer_attachment_pick count=${picked.size}" }
                if (picked.isNotEmpty()) currentOnPicked(picked)
            }
        }
    return { launcher.launch(arrayOf(ANY_TYPE)) }
}

/**
 * Whether a pasted clip [item] is an image to attach rather than content for the text field (#934): a
 * URI [isForeignContentUri] accepts, in a clip [description] that declares an image type. The declared
 * type is the pasting app's claim; [describePastedImage] checks the provider's own type afterwards. No
 * binder call, so it is safe on the main thread inside a content receiver.
 */
internal fun isPastedImageItem(
    item: ClipData.Item,
    description: ClipDescription,
    ownPackage: String,
): Boolean {
    val uri = item.uri ?: return false
    return isForeignContentUri(uri.scheme, uri.authority, ownPackage) && description.hasMimeType(IMAGE_TYPES)
}

/**
 * [describePickedAttachment] for a pasted [uri] (#934), kept only when the provider itself types it as an
 * image. A provider that reports no type, or another type, adds nothing.
 */
internal fun describePastedImage(
    resolver: ContentResolver,
    uri: Uri,
    ownPackage: String,
): PickedAttachment? {
    val described = describePickedAttachment(resolver, uri, ownPackage) ?: return null
    if (!ClipDescription.compareMimeTypes(described.mimeType, IMAGE_TYPES)) {
        RelayLog.d { "event=composer_attachment_paste outcome=refused_type" }
        return null
    }
    return described
}

/**
 * The composer's paste sink (#934): returns the action that takes the image URIs a paste or a keyboard
 * image insert handed the field, describes them off the main thread, and passes the images to [onPicked]
 * in clip order — the same sink a pick uses, so the size and count refusals apply unchanged. Bound to
 * this composition, like [rememberAttachmentPicker].
 */
@Composable
fun rememberPastedImageReceiver(onPicked: (List<PickedAttachment>) -> Unit): (List<Uri>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember(context, scope) {
        { uris ->
            scope.launch {
                val pasted =
                    withContext(Dispatchers.IO) {
                        uris.mapNotNull { describePastedImage(context.contentResolver, it, context.packageName) }
                    }
                RelayLog.d { "event=composer_attachment_paste count=${pasted.size}" }
                if (pasted.isNotEmpty()) currentOnPicked(pasted)
            }
        }
    }
}

/**
 * [uri]'s name, type and size as its provider reports them (#933), or `null` for a URI the send-time read
 * would refuse anyway ([isForeignContentUri]): nothing of this app's own is ever described or shown. No
 * bytes are read. A provider that fails the query still yields an entry, with a fallback name and an
 * unknown size, which the bounded read at send time re-checks.
 */
internal fun describePickedAttachment(
    resolver: ContentResolver,
    uri: Uri,
    ownPackage: String,
): PickedAttachment? {
    if (!isForeignContentUri(uri.scheme, uri.authority, ownPackage)) {
        RelayLog.d { "event=composer_attachment_pick outcome=refused_uri" }
        return null
    }
    var name: String? = null
    var size: Long? = null
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
            }
        }
    } catch (e: Exception) {
        // A provider's failure text is its own: dropped unread, the entry keeps the fallbacks.
        name = null
        size = null
    }
    val mimeType =
        try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }
    return PickedAttachment(
        uri = uri.toString(),
        displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: FALLBACK_DISPLAY_NAME,
        mimeType = mimeType?.takeIf { it.isNotBlank() } ?: FALLBACK_MIME_TYPE,
        size = size,
    )
}
