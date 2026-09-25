package de.pyryco.mobile.ui.conversations.thread

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentTarget
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

private const val FALLBACK_MIME_TYPE = "application/octet-stream"
private const val PACKAGE_ARCHIVE_MIME_TYPE = "application/vnd.android.package-archive"
private const val MAX_MIME_TYPE_CHARS = 127
private const val MARKDOWN_MIME_TYPE = "text/markdown"

// RFC 6838's restricted-name characters, for both halves of a concrete `type/subtype`. No wildcard, no
// parameters: a hint either names one type exactly or is not used.
private val ConcreteMimeType = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")

/** The attachment provider's authority (#985), declared in the manifest as `${applicationId}.attachments`. */
internal fun attachmentProviderAuthority(packageName: String): String = "$packageName.attachments"

/**
 * The type an attachment is opened or saved as (#985): the hint when it is one concrete media type, else
 * `application/octet-stream`. The hint is attacker-chosen, so it may pick a viewer among the apps the user
 * has but never an installer: the package-archive type falls back too.
 */
internal fun attachmentIntentType(hint: String?): String {
    val type = hint?.trim()?.lowercase() ?: return FALLBACK_MIME_TYPE
    if (type.length > MAX_MIME_TYPE_CHARS || type == PACKAGE_ARCHIVE_MIME_TYPE || !ConcreteMimeType.matches(type)) {
        return FALLBACK_MIME_TYPE
    }
    return type
}

/**
 * Android's view intent for one attachment (#985). The receiver gets a read grant on [uri] alone: never
 * write, never persistable, never a prefix of the tree.
 */
internal fun attachmentViewIntent(
    uri: Uri,
    mimeHint: String?,
): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachmentIntentType(mimeHint))
        .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/**
 * The content URI another app may read [source] through (#985), or `null` when it has none. A kept file
 * goes through the non-exported attachment provider, which canonicalises it and refuses anything outside
 * its one root, the attachment store. A sent original is handed on only while it is another app's content
 * URI ([isForeignContentUri]): never a `file` URI, and never one of this app's own authorities.
 */
internal fun attachmentContentUri(
    context: Context,
    source: AttachmentSource,
): Uri? =
    when (source) {
        is AttachmentSource.Kept ->
            try {
                FileProvider.getUriForFile(context, attachmentProviderAuthority(context.packageName), source.file)
            } catch (e: IllegalArgumentException) {
                null
            }
        is AttachmentSource.Original ->
            source.uri.toUri().takeIf { isForeignContentUri(it.scheme, it.authority, context.packageName) }
    }

/** One static sentence per outcome of an open or a save the user should hear about (#985). */
enum class AttachmentNotice(
    @StringRes val message: Int,
) {
    NO_APP(R.string.thread_attachment_no_app),
    OPEN_FAILED(R.string.thread_attachment_open_failed),
    SAVED(R.string.thread_attachment_saved),
    SAVE_FAILED(R.string.thread_attachment_save_failed),
}

/**
 * Opens [source] in whichever app the user picks for its type (#985). `null` once a viewer started; a
 * notice otherwise. Exceptions are dropped unread: a provider's or the platform's message can carry the
 * URI or the path.
 */
internal fun openAttachment(
    context: Context,
    source: AttachmentSource,
    mimeHint: String?,
): AttachmentNotice? {
    val uri = attachmentContentUri(context, source) ?: return AttachmentNotice.OPEN_FAILED
    val intent = attachmentViewIntent(uri, mimeHint)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        AttachmentNotice.NO_APP
    } catch (e: Exception) {
        AttachmentNotice.OPEN_FAILED
    }
}

/**
 * Hands [document], the text the reader shows, to whichever app the user picks from the system chooser
 * (#1068), as `text/markdown`. The text is written to the shared-note file first ([writeSharedNote], on
 * [ioDispatcher]), and the receiver gets a read grant on that one provider URI alone, as [openAttachment]
 * gives. `null` once the chooser started; [AttachmentNotice.NO_APP] when no app views markdown, since an
 * empty chooser never throws; [AttachmentNotice.OPEN_FAILED] when the file cannot be written or served.
 * Exceptions are dropped unread.
 */
internal suspend fun openNoteInAnotherApp(
    context: Context,
    document: MarkdownDocument,
    chooserTitle: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AttachmentNotice? {
    val file =
        withContext(ioDispatcher) {
            writeSharedNote(sharedNoteDirectory(context.noBackupFilesDir), document.name, document.text)
        } ?: return AttachmentNotice.OPEN_FAILED
    val uri = attachmentContentUri(context, AttachmentSource.Kept(file)) ?: return AttachmentNotice.OPEN_FAILED
    val view = attachmentViewIntent(uri, MARKDOWN_MIME_TYPE)
    val viewers =
        context.packageManager.queryIntentActivities(
            view,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    if (viewers.isEmpty()) return AttachmentNotice.NO_APP
    // createChooser carries the target's URI and its read grant, and nothing wider, to the chosen app.
    val chooser = Intent.createChooser(view, chooserTitle)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        null
    } catch (e: ActivityNotFoundException) {
        AttachmentNotice.NO_APP
    } catch (e: Exception) {
        AttachmentNotice.OPEN_FAILED
    }
}

/**
 * Copies an attachment's exact bytes into a picked document (#985). The input opens first, so a source
 * that has gone never truncates the document; any failure [discard]s the document the picker created, so
 * no partial file survives. Blocking and not interruptible, so a copy that has started always finishes or
 * fails whole: call it on an IO dispatcher. Exceptions are dropped unread, as in [openAttachment].
 */
internal fun copyAttachment(
    openInput: () -> InputStream,
    openOutput: () -> OutputStream,
    discard: () -> Unit,
): Boolean =
    try {
        openInput().use { input ->
            openOutput().use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        true
    } catch (e: Exception) {
        runCatching(discard)
        false
    }

/** What a message attachment's tap and long-press do in the thread (#985). */
class AttachmentActions(
    val open: (AttachmentTarget) -> Unit,
    val save: (AttachmentTarget) -> Unit,
)

/** The system's create-document picker for one attachment: its type, and its name as the suggestion. */
private class CreateAttachmentDocument : ActivityResultContract<CreateAttachmentDocument.Request, Uri?>() {
    class Request(
        val suggestedName: String,
        val mimeType: String,
    ) {
        override fun toString(): String = "Request"
    }

    override fun createIntent(
        context: Context,
        input: Request,
    ): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.suggestedName)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/**
 * Open and save for the thread's message attachments (#985), bound to this composition.
 *
 * Only a [AttachmentViewState.Ready] attachment acts; anything else is ignored. Open hands a markdown file
 * (by name, [isMarkdownAttachmentName]) to [onOpenMarkdown] for the in-app reader (#1027), and anything else
 * as a content URI to the viewer the user picks. Save launches the system's create-document picker and copies the bytes into
 * the document it returns. The pending save keeps only the attachment id across the picker round trip, and
 * resolves its source against the live [states] when the result arrives, so no path or URI enters the
 * saved-state bundle. A cancelled picker writes nothing and says nothing.
 */
@Composable
internal fun rememberAttachmentActions(
    states: Map<String, AttachmentViewState>,
    onOpenMarkdown: (attachmentId: String) -> Unit,
    onNotice: (AttachmentNotice) -> Unit,
): AttachmentActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentStates by rememberUpdatedState(states)
    val currentOnNotice by rememberUpdatedState(onNotice)
    val currentOnOpenMarkdown by rememberUpdatedState(onOpenMarkdown)
    val unnamed = stringResource(R.string.thread_attachment_unnamed)
    var pendingSaveId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(CreateAttachmentDocument()) { destination ->
            val id = pendingSaveId ?: return@rememberLauncherForActivityResult
            pendingSaveId = null
            if (destination == null) {
                RelayLog.d { "event=thread_attachment_save id=$id outcome=cancelled" }
                return@rememberLauncherForActivityResult
            }
            val source = (currentStates[id] as? AttachmentViewState.Ready)?.source
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val discard = {
                            DocumentsContract.deleteDocument(resolver, destination)
                            Unit
                        }
                        val input = source?.let { sourceInput(context, it) }
                        if (input == null) {
                            runCatching(discard)
                            return@withContext false
                        }
                        copyAttachment(
                            openInput = input,
                            openOutput = { checkNotNull(resolver.openOutputStream(destination, "wt")) },
                            discard = discard,
                        )
                    }
                RelayLog.d { "event=thread_attachment_save id=$id outcome=${if (saved) "saved" else "failed"}" }
                currentOnNotice(if (saved) AttachmentNotice.SAVED else AttachmentNotice.SAVE_FAILED)
            }
        }
    return remember(context, scope, launcher, unnamed) {
        AttachmentActions(
            open = { target ->
                val source = (currentStates[target.attachmentId] as? AttachmentViewState.Ready)?.source
                if (source != null && isMarkdownAttachmentName(target.displayName)) {
                    // #1027: read in-app from the host's store, whichever source the row shows.
                    currentOnOpenMarkdown(target.attachmentId)
                } else if (source != null) {
                    val notice = openAttachment(context, source, target.mimeType)
                    val outcome =
                        when (notice) {
                            null -> "opened"
                            AttachmentNotice.NO_APP -> "no_app"
                            else -> "failed"
                        }
                    RelayLog.d { "event=thread_attachment_open id=${target.attachmentId} outcome=$outcome" }
                    notice?.let(currentOnNotice)
                }
            },
            save = { target ->
                if (currentStates[target.attachmentId] is AttachmentViewState.Ready) {
                    pendingSaveId = target.attachmentId
                    launcher.launch(
                        CreateAttachmentDocument.Request(
                            suggestedName = target.displayName ?: unnamed,
                            mimeType = attachmentIntentType(target.mimeType),
                        ),
                    )
                }
            },
        )
    }
}

/** The bytes of [source] for a save, or `null` when a sent original is not another app's content URI. */
private fun sourceInput(
    context: Context,
    source: AttachmentSource,
): (() -> InputStream)? {
    if (source is AttachmentSource.Kept) {
        val file: File = source.file
        return { FileInputStream(file) }
    }
    val uri = attachmentContentUri(context, source) ?: return null
    return { checkNotNull(context.contentResolver.openInputStream(uri)) }
}
