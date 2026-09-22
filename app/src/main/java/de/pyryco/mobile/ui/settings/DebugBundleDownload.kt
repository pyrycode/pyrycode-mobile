package de.pyryco.mobile.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.DebugBundleArchive
import de.pyryco.mobile.data.repository.DebugBundleStatus
import de.pyryco.mobile.data.repository.DebugBundleTransfer
import de.pyryco.mobile.ui.components.MobileModal
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** The document name every save suggests. Fixed: no daemon field may influence what is written. */
const val DEBUG_BUNDLE_FILE_NAME = "pyrycode-debug-bundle.tar.gz"

/** The daemon assembles a gzip-wrapped tar; this is its media type, also fixed. */
const val DEBUG_BUNDLE_MEDIA_TYPE = "application/gzip"

/**
 * The document a completed archive is written into, as the picker handed it back (#683).
 *
 * Every method is called off the main thread, from [DebugBundleDownloadController]'s own IO
 * dispatcher, because all three touch a `ContentResolver`. Each may throw; the controller treats any
 * throw as one failed save.
 */
interface ArchiveDestination {
    /** The document's own display name, for the success line. Provider-authored, so it is clamped. */
    fun name(): String

    fun openStream(): OutputStream

    /** Removes a document this save created but could not fill, so no partial archive survives. */
    fun discard()
}

/**
 * One static sentence per way a download or a save can fail (#683).
 *
 * The seven transfer arms are [DebugBundleStatus]'s own non-terminal values, mapped by the single
 * exhaustive `when` in [DebugBundleDownloadController] — adding an arm to that enum breaks the build
 * there and nowhere else. The last two have no status behind them: they are this ticket's own steps.
 *
 * Each arm carries its string id rather than the resolved string, so the controller and the view
 * model above it stay free of `Context` and no raw daemon or exception text can reach the shell's
 * live region.
 */
enum class DebugBundleFailure(
    @StringRes val message: Int,
) {
    UNAVAILABLE(R.string.log_data_failed_unavailable),
    BUSY(R.string.log_data_failed_busy),
    RECONNECT_REQUIRED(R.string.log_data_failed_reconnect),
    SEND_FAILED(R.string.log_data_failed_send),
    REFUSED(R.string.log_data_failed_refused),
    INVALID_STREAM(R.string.log_data_failed_stream),
    DISCONNECTED(R.string.log_data_failed_disconnected),
    PICKER_CANCELLED(R.string.log_data_failed_cancelled),
    WRITE_FAILED(R.string.log_data_failed_write),
}

/**
 * The Log data modal as the screen draws it (#683).
 *
 * Flags rather than a phase enum, following [de.pyryco.mobile.ui.host.HostEditorState]: the two
 * orthogonal facts — an archive is held, the last attempt failed — have to be true together, which
 * is exactly the state a cancelled picker leaves behind.
 *
 * **Never holds the archive.** [readyBytes] is a size; the [DebugBundleArchive] itself stays in a
 * private field on the controller. This class has no redacting `toString`, so a crash trace renders
 * whatever it holds — the same rule [SettingsHost] carries about pairing records.
 *
 * Both strings here are externally authored and both are clamped where they enter: [hostName]
 * originates in a scanned QR payload, and [savedTo] comes from whichever document provider the
 * operator chose, which is a third-party app under no obligation to return the name we suggested.
 */
data class DebugBundleDownloadState(
    val hostName: String,
    val receiving: Boolean = false,
    val saving: Boolean = false,
    /** Non-null exactly while a completed archive is held and unsaved. */
    val readyBytes: Long? = null,
    val acceptedChunks: Int = 0,
    val savedTo: String? = null,
    val failure: DebugBundleFailure? = null,
) {
    /** Nothing in flight and nothing held: the one state in which a fresh request is allowed. */
    val idle: Boolean get() = !receiving && !saving && readyBytes == null && savedTo == null
}

/**
 * The Log data download's state machine, one instance per Settings destination (#683).
 *
 * A plain controller over the owning view model's `viewModelScope`, as
 * [de.pyryco.mobile.ui.host.HostEditorController] is and for the same reason: clearing that owner has
 * to cancel the collect and the write, and two Settings entries on the back stack must never share
 * one download.
 *
 * [serverId] is fixed at construction from the id this destination captured (#749). It is the only
 * thing ever handed to [request], so compatibility selection, another host's Settings and an unpair
 * can none of them move a request or a pending save onto a different host: there is no second id
 * here to move it to. An unknown or disconnected id is answered by the registry with a rejected
 * transfer rather than another host's.
 *
 * [request] is a lambda rather than the registry itself so this class reaches no store, no record and
 * no credential — it can ask exactly one question about exactly one host.
 */
class DebugBundleDownloadController(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val request: (String) -> DebugBundleTransfer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val download = MutableStateFlow<DebugBundleDownloadState?>(null)

    /** The open modal's state, or null when none is open. */
    val state: StateFlow<DebugBundleDownloadState?> = download.asStateFlow()

    /**
     * The completed archive, held here and never published. Cleared only by a save that returned and
     * by [dismiss] — so a cancelled picker or a failed write leaves it exactly where the retry looks.
     *
     * Read and written only from main-dispatched callbacks and from [scope] continuations, which
     * resume on the same dispatcher, so it needs no synchronisation of its own.
     */
    private var archive: DebugBundleArchive? = null
    private var transferJob: Job? = null

    /** Opens the modal on [hostName]'s host. Re-opening after a dismissal starts from idle. */
    fun open(hostName: String) {
        download.value = DebugBundleDownloadState(hostName = hostName.take(MAX_WORKSPACE_LABEL_CHARS))
        RelayLog.d { "event=log_data_opened" }
    }

    /**
     * Asks this destination's own host for its archive.
     *
     * The guard is the single-request lock AC2 names: only an idle modal may ask, so the tap is inert
     * while a transfer is receiving, while a save is running, and once an archive is held or saved.
     * Its belt-and-suspenders half is deterministic code in a different fabric —
     * `RemoteConversationRepository.requestDebugBundle` holds one transfer per connection and answers
     * a second request `BUSY` — not a second copy of this rule.
     */
    fun requestArchive() {
        val current = download.value ?: return
        if (!current.idle) {
            RelayLog.d { "event=log_data_request_rejected code=not_idle" }
            return
        }
        val pending = current.copy(receiving = true, acceptedChunks = 0, failure = null)
        download.value = pending
        val transfer = request(serverId)
        RelayLog.d { "event=log_data_requested" }
        transferJob?.cancel()
        transferJob =
            scope.launch {
                transfer.state.collect { transferState ->
                    val failure = failureFor(transferState.status)
                    when {
                        failure != null -> {
                            // Back to idle, not to a dead end: the action stays usable for a fresh request.
                            download.value = download.value?.copy(receiving = false, acceptedChunks = 0, failure = failure)
                            RelayLog.d { "event=log_data_failed code=${transferState.status}" }
                            return@collect
                        }
                        transferState.status == DebugBundleStatus.COMPLETE -> {
                            // Taken once, into this object, while a retry can still find it: taking it at
                            // save time would leave a failed write with nothing left to save.
                            val completed = transfer.takeArchive() ?: return@collect
                            archive = completed
                            download.value =
                                download.value?.copy(receiving = false, readyBytes = completed.sizeBytes, failure = null)
                            RelayLog.d { "event=log_data_ready" }
                        }
                        else -> download.value = download.value?.copy(acceptedChunks = transferState.acceptedChunks)
                    }
                }
            }
    }

    /**
     * Writes the held archive to the picked [destination], or reports a cancelled picker for null.
     *
     * Everything that touches the document runs on [io]: the name query, opening the stream and the
     * write itself. [DebugBundleDownloadState.savedTo] is published only after the stream closes, so
     * no path reports a save that has not returned, and a throw anywhere in the block discards the
     * document the picker created rather than leaving a truncated archive behind.
     *
     * The terminal transitions are `compareAndSet` against the state published before the write, so a
     * save landing after a dismissal cannot resurrect a closed modal.
     */
    fun onDestination(destination: ArchiveDestination?) {
        val current = download.value ?: return
        val held = archive ?: return
        if (current.saving) return
        if (destination == null) {
            download.value = current.copy(failure = DebugBundleFailure.PICKER_CANCELLED)
            RelayLog.d { "event=log_data_save_cancelled" }
            return
        }
        val pending = current.copy(saving = true, failure = null)
        download.value = pending
        scope.launch {
            RelayLog.d { "event=log_data_save_started" }
            val name =
                try {
                    withContext(io) {
                        val label = destination.name()
                        destination.openStream().use { output ->
                            held.writeTo(output)
                            output.flush()
                        }
                        label
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Never log the provider's message, the document or the archive; the UI gets one sentence.
                    RelayLog.d { "event=log_data_save_failed" }
                    withContext(io) { runCatching { destination.discard() } }
                    download.compareAndSet(pending, pending.copy(saving = false, failure = DebugBundleFailure.WRITE_FAILED))
                    return@launch
                }
            // Only now: the bytes are in the document, so dropping them cannot lose an unsaved archive.
            archive = null
            download.compareAndSet(
                pending,
                pending.copy(saving = false, readyBytes = null, savedTo = name.take(MAX_WORKSPACE_LABEL_CHARS)),
            )
            RelayLog.d { "event=log_data_saved" }
        }
    }

    /**
     * Closes the modal, stops collecting and drops the held archive.
     *
     * Dropping is deliberate: daemon bytes do not outlive the modal that was saving them. The picker
     * round trip does not come through here — the launched activity stops ours without touching
     * composition — so only a real dismissal discards an unsaved download.
     */
    fun dismiss() {
        transferJob?.cancel()
        transferJob = null
        archive = null
        download.value = null
        RelayLog.d { "event=log_data_dismissed" }
    }

    /**
     * The one place #682's closed status set is interpreted.
     *
     * Exhaustive with no `else`, so a new [DebugBundleStatus] arm stops this file compiling rather
     * than falling silently into a wrong sentence.
     */
    private fun failureFor(status: DebugBundleStatus): DebugBundleFailure? =
        when (status) {
            DebugBundleStatus.RECEIVING, DebugBundleStatus.COMPLETE -> null
            DebugBundleStatus.UNAVAILABLE -> DebugBundleFailure.UNAVAILABLE
            DebugBundleStatus.BUSY -> DebugBundleFailure.BUSY
            DebugBundleStatus.RECONNECT_REQUIRED -> DebugBundleFailure.RECONNECT_REQUIRED
            DebugBundleStatus.SEND_FAILED -> DebugBundleFailure.SEND_FAILED
            DebugBundleStatus.REFUSED -> DebugBundleFailure.REFUSED
            DebugBundleStatus.INVALID_STREAM -> DebugBundleFailure.INVALID_STREAM
            DebugBundleStatus.DISCONNECTED -> DebugBundleFailure.DISCONNECTED
        }
}

/**
 * The picked document, backed by the platform's own resolver (#683).
 *
 * The only handle is the `Uri` the picker returned, which is a grant to this one document: nothing
 * here builds a path, and [discard] can therefore reach nothing the operator did not just create.
 * `"wt"` truncates, so a retry over the same document replaces a truncated earlier attempt.
 */
internal fun documentArchiveDestination(
    resolver: ContentResolver,
    uri: Uri,
): ArchiveDestination =
    object : ArchiveDestination {
        override fun name(): String =
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: DEBUG_BUNDLE_FILE_NAME

        override fun openStream(): OutputStream =
            checkNotNull(resolver.openOutputStream(uri, "wt")) { "document provider returned no stream" }

        override fun discard() {
            DocumentsContract.deleteDocument(resolver, uri)
        }
    }

/**
 * The Log data modal, and the one place this download is drawn (#683).
 *
 * Present exactly while [state] is non-null — the presence rule
 * [de.pyryco.mobile.ui.host.HostEditorModal] already uses, since [MobileModal] closes on none of its
 * callbacks. This binding owns the flag-to-string resolution, which is what keeps the view model
 * free of `Context` and keeps every failure sentence static.
 *
 * The shell's footer labels are fixed, so OK's meaning comes from the state and the copy carries it:
 * request while idle, save once an archive is held, dismiss once one is saved.
 */
@Composable
internal fun DebugBundleModal(
    state: DebugBundleDownloadState?,
    onRequest: () -> Unit,
    onSave: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (state == null) return
    val busy = state.receiving || state.saving
    MobileModal(
        title = stringResource(R.string.log_data_title),
        onDismissRequest = onDismissRequest,
        onSubmit = {
            when {
                busy -> Unit
                state.savedTo != null -> onDismissRequest()
                state.readyBytes != null -> onSave()
                else -> onRequest()
            }
        },
        loading = busy,
        error = state.failure?.let { stringResource(it.message) },
    ) {
        DebugBundleModalContent(state)
    }
}

@Composable
private fun ColumnScope.DebugBundleModalContent(state: DebugBundleDownloadState) {
    // Names the host and says what the archive covers: the whole daemon, and its paths and logs —
    // the operator is about to choose where that goes, so both halves belong here.
    BoundedModalLine(stringResource(R.string.log_data_scope, state.hostName))
    val progress =
        when {
            state.saving -> stringResource(R.string.log_data_saving)
            state.receiving -> stringResource(R.string.log_data_receiving, state.acceptedChunks)
            state.savedTo != null -> stringResource(R.string.log_data_saved, state.savedTo)
            state.readyBytes != null ->
                stringResource(
                    R.string.log_data_ready,
                    Formatter.formatShortFileSize(LocalContext.current, state.readyBytes),
                )
            else -> null
        }
    if (progress != null) BoundedModalLine(progress)
}

/**
 * One line of modal copy, held to a single line.
 *
 * Both values interpolated into this content are externally authored — a host name from a scanned
 * payload, a document name from a third-party provider — and both are already clamped in
 * [DebugBundleDownloadState]; `maxLines` is the render-side half, so an embedded newline cannot
 * restructure the content column either.
 */
@Composable
private fun BoundedModalLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
