package de.pyryco.mobile.ui.conversations.list

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/**
 * The Edit channel modal's state machine (#667), shared by the two screens that open it (#1561): a Channels
 * row on the list and a channel's own thread menu.
 *
 * Extracted from `ChannelListViewModel` as [de.pyryco.mobile.ui.host.HostEditorController] was (#751): a plain
 * object, so the list, which resolves a repository per host, and the thread, which holds its own, each
 * compose one without inheriting the other's dependencies. One instance per owner.
 *
 * **[scope] must be the owning view model's `viewModelScope`**, so clearing the owner cancels the prompt read
 * and any write chain. [isHostLive] gates every open and press; [repositoryFor] is resolved at each press,
 * since a reconnect replaces it; [awaitRepository] is what the prompt read waits on, so a modal opened while
 * the host is down fills once it connects. [onArchived] runs after a confirmed archive.
 */
class ChannelEditorController(
    private val scope: CoroutineScope,
    private val isHostLive: (serverId: String) -> Boolean,
    private val repositoryFor: (serverId: String) -> ConversationRepository?,
    private val awaitRepository: suspend (serverId: String) -> ConversationRepository,
    private val onArchived: suspend () -> Unit = {},
) {
    // #667: the editor's own prompt stays at its default here; the reading lives apart, tagged with the
    // channel it was read for, so a read landing mid-write never breaks that write's `compareAndSet` and no
    // emission can pair one channel's modal with another channel's prompt.
    private val editor = MutableStateFlow<ChannelEditorState?>(null)
    private val reading = MutableStateFlow<Pair<HostConversationTarget, ChannelPromptReading>?>(null)
    private var promptRead: Job? = null

    /** The open Edit channel modal with its own prompt reading, or null when none is open. */
    val state: Flow<ChannelEditorState?> =
        combine(editor, reading) { editor, reading ->
            editor?.copy(prompt = channelPromptFor(editor, reading))
        }

    /**
     * Opens the Edit channel modal on [target] with the channel's [name] and [muted] flag as its owner read
     * them, then reads the stored prompt once. The read calls nothing that starts or resets a session.
     */
    fun open(
        target: HostConversationTarget,
        name: String?,
        muted: Boolean,
    ) {
        if (!isHostLive(target.serverId)) {
            RelayLog.d { "event=channel_editor_open_rejected code=disconnected" }
            return
        }
        promptRead?.cancel()
        reading.value = target to ChannelPromptReading.Reading
        editor.value =
            ChannelEditorState(
                serverId = target.serverId,
                conversationId = target.conversationId,
                savedName = boundedName(name?.takeIf { it.isNotBlank() }.orEmpty()),
                savedMuted = muted,
            )
        RelayLog.d { "event=channel_editor_opened" }
        promptRead =
            scope.launch {
                val live = awaitRepository(target.serverId)
                reading.value = target to readChannelPrompt(live, target.conversationId)
            }
    }

    /**
     * OK: renames the open editor's channel when the trimmed [name] differs from its saved name, then writes
     * [muted] when it differs from the saved flag (#1021), then writes [systemPrompt] verbatim when it differs
     * from the stored prompt that was read, then closes (#667). [muted] `null` writes no mute.
     *
     * [systemPrompt] is `null` when the modal never showed a stored prompt, and it is ignored unless this
     * editor's reading arrived: a prompt the operator never saw can never be overwritten. An absent stored
     * prompt reads as an empty box, as `SystemPromptEditorState.Loaded.changed` does, and a box emptied over
     * a stored prompt sends `null`, clearing it (#1342). A confirmed rename is
     * recorded as the saved name and a confirmed mute as the saved flag; the prompt, whose confirmation is not
     * recorded, goes last. So a retry sends only the writes the host has not confirmed.
     *
     * Every terminal transition is a `compareAndSet`. The chain is not cancelled by a dismissal: the operator
     * already pressed OK.
     */
    fun submit(
        name: String,
        systemPrompt: String?,
        muted: Boolean?,
    ) {
        val state = editor.value ?: return
        if (state.saving) return
        val trimmed = name.trim()
        val read = channelPromptFor(state, reading.value) as? ChannelPromptReading.Read
        val draft = systemPrompt?.takeIf { read != null }
        if (trimmed.isEmpty() || (draft != null && !SystemPromptLimit.fits(draft))) {
            RelayLog.d { "event=channel_edit_rejected code=invalid" }
            return
        }
        if (!isHostLive(state.serverId)) {
            RelayLog.d { "event=channel_edit_rejected code=disconnected" }
            close(state)
            return
        }
        val live = repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=channel_edit_rejected code=unavailable" }
            editor.value = state.copy(failed = true, archiveFailed = false)
            return
        }
        val renameTo = trimmed.takeIf { it != state.savedName.trim() }
        val muteTo = muted?.takeIf { it != state.savedMuted }
        // Desktop's promptWriteFor (#1342): an unchanged box sends nothing, an emptied one clears the prompt
        // with null, and any other text is sent verbatim.
        val writesPrompt = draft != null && draft != read?.prompt.orEmpty()
        val promptToWrite = draft?.takeIf { it.isNotEmpty() }
        val pending = state.copy(saving = true, failed = false, archiveFailed = false)
        editor.value = pending
        scope.launch {
            var current = pending
            if (renameTo != null) {
                try {
                    live.rename(state.conversationId, renameTo)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Never log the name, the prompt, the ids or the server's message.
                    RelayLog.d { "event=channel_rename_failed" }
                    editor.compareAndSet(pending, pending.copy(saving = false, failed = true))
                    return@launch
                }
                current = pending.copy(savedName = renameTo)
                editor.compareAndSet(pending, current)
            }
            if (muteTo != null) {
                val before = current
                try {
                    live.setMuted(state.conversationId, muteTo)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=channel_mute_write_failed" }
                    editor.compareAndSet(before, before.copy(saving = false, failed = true))
                    return@launch
                }
                current = before.copy(savedMuted = muteTo)
                editor.compareAndSet(before, current)
            }
            if (writesPrompt) {
                try {
                    live.setSystemPrompt(state.conversationId, promptToWrite)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=channel_prompt_write_failed" }
                    editor.compareAndSet(current, current.copy(saving = false, failed = true))
                    return@launch
                }
            }
            // The owner picks the new name and flag up from the host's own conversation stream; nothing is patched here.
            editor.compareAndSet(current, null)
            RelayLog.d {
                "event=channel_edited renamed=${renameTo != null} muted=${muteTo != null} prompt=$writesPrompt"
            }
        }
    }

    /**
     * Archives the open editor's channel, then closes the modal and runs [onArchived] (#667).
     *
     * No confirmation, since Archive restores it, and nothing the fields hold — a blank name or an unread
     * prompt — stands in the way.
     */
    fun archive() {
        val state = editor.value ?: return
        if (state.saving) return
        if (!isHostLive(state.serverId)) {
            RelayLog.d { "event=channel_archive_rejected code=disconnected" }
            close(state)
            return
        }
        val live = repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=channel_archive_rejected code=unavailable" }
            editor.value = state.copy(failed = false, archiveFailed = true)
            return
        }
        val pending = state.copy(saving = true, failed = false, archiveFailed = false)
        editor.value = pending
        scope.launch {
            RelayLog.d { "event=channel_archive_started" }
            try {
                live.archive(state.conversationId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                RelayLog.d { "event=channel_archive_failed" }
                editor.compareAndSet(pending, pending.copy(saving = false, archiveFailed = true))
                return@launch
            }
            editor.compareAndSet(pending, null)
            RelayLog.d { "event=channel_archived" }
            onArchived()
        }
    }

    /** Cancel, Close and Back: close the Edit channel modal, stop its prompt read and send nothing. */
    fun dismiss() {
        editor.value = null
        promptRead?.cancel()
        RelayLog.d { "event=channel_editor_dismissed" }
    }

    /** Closes the open modal, and stops its prompt read, unless [keep] holds for it (#1336's disconnect rule). */
    fun closeUnless(keep: (ChannelEditorState) -> Boolean) {
        val before = editor.getAndUpdate { state -> state?.takeIf(keep) }
        if (before != null && !keep(before)) {
            RelayLog.d { "event=channel_editor_closed code=disconnected" }
            promptRead?.cancel()
        }
    }

    /** Closes [state]'s Edit channel modal, and stops its prompt read, unless another one replaced it. */
    private fun close(state: ChannelEditorState) {
        if (editor.compareAndSet(state, null)) promptRead?.cancel()
    }

    /**
     * One stored-prompt read. A reply over the byte limit is [ChannelPromptReading.Unavailable]: the decoder
     * bounds nothing, and a prompt the field cannot hold must never be rendered or written back.
     */
    private suspend fun readChannelPrompt(
        live: ConversationRepository,
        conversationId: String,
    ): ChannelPromptReading =
        try {
            val reading = live.requestSystemPrompt(conversationId)
            val prompt = reading.systemPrompt
            if (prompt != null && !SystemPromptLimit.fits(prompt)) {
                RelayLog.d { "event=channel_prompt_read_failed code=oversize" }
                ChannelPromptReading.Unavailable
            } else {
                RelayLog.d { "event=channel_prompt_read" }
                ChannelPromptReading.Read(prompt, reading.sessionPromptStatus)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RelayLog.d { "event=channel_prompt_read_failed" }
            ChannelPromptReading.Unavailable
        }
}

/** [editor]'s own reading, or [ChannelPromptReading.Reading] while the latest one is another channel's. */
private fun channelPromptFor(
    editor: ChannelEditorState,
    reading: Pair<HostConversationTarget, ChannelPromptReading>?,
): ChannelPromptReading =
    reading?.takeIf { it.first == HostConversationTarget(editor.serverId, editor.conversationId) }?.second
        ?: ChannelPromptReading.Reading

/**
 * A daemon-authored name clamped before layout, as `EditChatModal` clamps its own. The clamp must not end on
 * half a surrogate pair, since an untouched field is compared against it.
 */
private fun boundedName(name: String): String =
    name.take(MAX_WORKSPACE_LABEL_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
