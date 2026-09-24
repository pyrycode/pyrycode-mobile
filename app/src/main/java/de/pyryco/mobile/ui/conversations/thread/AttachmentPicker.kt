package de.pyryco.mobile.ui.conversations.thread

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
