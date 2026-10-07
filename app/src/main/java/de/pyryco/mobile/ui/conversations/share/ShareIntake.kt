package de.pyryco.mobile.ui.conversations.share

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.AttachmentAddOutcome
import de.pyryco.mobile.ui.conversations.thread.AttachmentSendFailure
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.PickedAttachment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Validated shape only; URI access is separately guarded at capture. Never saved or logged. */
internal class SharePayload(
    val text: String,
    val uris: List<Uri>,
    val shortcutId: String? = null,
) {
    override fun toString(): String = "SharePayload"

    companion object {
        private const val MAX_TEXT_CHARS = 32_768

        fun from(intent: Intent): SharePayload? {
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
            return try {
                // Inspect the actual types: typed Android getters can silently turn malformed extras into null.
                val extras = intent.extras
                val rawText = extras?.get(Intent.EXTRA_TEXT)
                if (rawText != null && rawText !is CharSequence) return null
                val text = (rawText as? CharSequence)?.let { it.subSequence(0, minOf(it.length, MAX_TEXT_CHARS)).toString() }.orEmpty()
                val uris =
                    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                        val stream = extras?.get(Intent.EXTRA_STREAM)
                        when (intent.action) {
                            Intent.ACTION_SEND -> listOf(stream as? Uri ?: return null)
                            else -> {
                                val list = stream as? ArrayList<*> ?: return null
                                if (list.any { it !is Uri }) return null
                                list.filterIsInstance<Uri>()
                            }
                        }
                    } else {
                        val clip = intent.clipData
                        if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    }
                if (text.isBlank() &&
                    uris.isEmpty()
                ) {
                    null
                } else {
                    SharePayload(
                        text,
                        uris.distinct(),
                        (extras?.get(Intent.EXTRA_SHORTCUT_ID) as? String)?.takeIf {
                            it.isNotBlank() &&
                                it.length <= 256
                        },
                    )
                }
            } catch (_: Exception) {
                RelayLog.d { "event=share_intake outcome=malformed" }
                null
            }
        }
    }
}

/** Preview contains capabilities, never a private path; rotation keeps the ViewModel, not a Bundle. */
internal class SharedContent(
    val generation: Long,
    val text: String,
    val files: List<PickedAttachment>,
    val capturing: Boolean,
    val shortcutId: String? = null,
) {
    override fun toString(): String = "SharedContent"
}

internal class ShareIntakeViewModel(
    private val drafts: ComposerDraftStore,
    private val capture: suspend (Uri, (AttachmentSendFailure) -> Unit) -> PickedAttachment?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val pending = MutableStateFlow<SharedContent?>(null)
    val state = pending.asStateFlow()
    private val noticeChannel = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
    val notices = noticeChannel.receiveAsFlow()
    private var generation = 0L
    private var capturing: Job? = null

    fun accept(payload: SharePayload) {
        cancel()
        val version = generation
        pending.value = SharedContent(version, payload.text, emptyList(), true, payload.shortcutId)
        RelayLog.d { "event=share_intake outcome=started count=${payload.uris.size}" }
        capturing =
            viewModelScope.launch {
                for ((index, uri) in payload.uris.withIndex()) {
                    val batch = pending.value?.takeIf { it.generation == version } ?: break
                    if (batch.files.size == MessageAttachmentIds.MAX) {
                        RelayLog.d { "event=share_intake outcome=too_many count=${payload.uris.size - index}" }
                        noticeChannel.trySend(R.plurals.thread_attachments_too_many to (payload.uris.size - index))
                        break
                    }
                    var owned: PickedAttachment? = null
                    val failures = mutableListOf<AttachmentSendFailure>()
                    try {
                        // Assignment inside IO keeps the result reachable if cancellation drops its return.
                        withContext(io) { owned = capture(uri, failures::add) }
                        val current = pending.value?.takeIf { it.generation == version } ?: break
                        failures.forEach { noticeChannel.trySend(it.message to 0) }
                        owned?.let { pending.value = SharedContent(version, current.text, current.files + it, true, current.shortcutId) }
                        owned = null
                    } finally {
                        owned?.ownedPaste?.release()
                    }
                }
                pending.value?.takeIf { it.generation == version }?.let {
                    pending.value = SharedContent(version, it.text, it.files, false, it.shortcutId)
                    RelayLog.d { "event=share_intake outcome=ready count=${it.files.size}" }
                }
            }
    }

    /** Main-thread synchronous transfer, before navigation can suspend or a second tap can run. */
    fun select(
        target: HostConversationTarget,
        expectedGeneration: Long? = null,
    ): Boolean {
        val batch =
            pending.value?.takeUnless { it.capturing || (expectedGeneration != null && it.generation != expectedGeneration) }
                ?: return false
        pending.value = null
        generation++
        var refused = 0
        for (file in batch.files) {
            when (
                drafts.addAttachment(
                    target.serverId,
                    target.conversationId,
                    file.uri,
                    file.displayName,
                    file.mimeType,
                    file.size,
                    file.ownedPaste,
                )
            ) {
                AttachmentAddOutcome.ADDED -> Unit
                AttachmentAddOutcome.TOO_MANY -> refused++
                AttachmentAddOutcome.TOO_LARGE -> noticeChannel.trySend(R.plurals.thread_attachments_too_large to 1)
            }
        }
        if (refused > 0) {
            RelayLog.d { "event=share_intake outcome=too_many count=$refused" }
            noticeChannel.trySend(R.plurals.thread_attachments_too_many to refused)
        }
        if (batch.text.isNotBlank()) {
            val existing = drafts.draftFor(target.serverId, target.conversationId)
            drafts.setDraft(target.serverId, target.conversationId, if (existing.isBlank()) batch.text else "$existing\n${batch.text}")
        }
        RelayLog.d { "event=share_intake outcome=selected count=${batch.files.size}" }
        return true
    }

    fun fallback(expectedGeneration: Long) {
        val batch = pending.value?.takeIf { it.generation == expectedGeneration } ?: return
        pending.value = SharedContent(batch.generation, batch.text, batch.files, batch.capturing)
        RelayLog.d { "event=share_shortcut_fallback" }
    }

    fun cancel() {
        generation++
        capturing?.cancel()
        capturing = null
        val old = pending.value
        pending.value = null
        old?.files?.forEach { it.ownedPaste?.release() }
        if (old != null) RelayLog.d { "event=share_intake outcome=cancelled" }
    }

    override fun onCleared() {
        cancel()
        noticeChannel.close()
    }
}
