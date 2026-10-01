package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * One conversation's system prompt as its editor sees it (#824).
 *
 * [Unavailable] is a failed read, which is not the same statement as "no prompt stored": that one is a
 * [Loaded] whose [Loaded.confirmed] is `null`. Nothing can be edited or written from either of the
 * other two states, so a prompt that was never read can never be overwritten.
 */
sealed interface SystemPromptEditorState {
    data object Loading : SystemPromptEditorState

    data object Unavailable : SystemPromptEditorState

    /**
     * A reading arrived. [confirmed] keeps the three stored states apart — `null` is no prompt, `""` an
     * explicitly empty one — and is held verbatim: it is untrusted operator-authored text.
     *
     * [appliedStatus] is whether the running session uses [confirmed]; `null` is unknown, which is what a
     * save leaves until its follow-up read lands, and what a failed follow-up read leaves for good.
     *
     * [changed] reads an absent prompt as an empty box. Since #1342 no control gates on it: [canSave] and
     * [canClear] are desktop's `deriveSystemPromptSection` rules, so Save sends the box verbatim whatever it
     * holds and Clear sends `null` whenever no write is running.
     *
     * [saved] is the last write's ack; [refusal] says why the last write failed. A new write clears both.
     *
     * [toString] is overridden: the generated one would print the prompt, which may hold a pasted
     * credential, into any crash trace or logged state.
     */
    data class Loaded(
        val confirmed: String?,
        val appliedStatus: SessionPromptStatus?,
        val draft: String,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
        val refusal: SystemPromptRefusal? = null,
        val saved: Boolean = false,
    ) : SystemPromptEditorState {
        val draftBytes: Int = SystemPromptLimit.utf8Bytes(draft)
        val overLimit: Boolean = draftBytes > SystemPromptLimit.MAX_BYTES
        val changed: Boolean get() = draft != confirmed.orEmpty()
        val canSave: Boolean get() = !saving && !overLimit
        val canClear: Boolean get() = !saving

        override fun toString(): String =
            "Loaded(confirmed=${if (confirmed == null) "absent" else "<redacted>"}, appliedStatus=$appliedStatus, " +
                "saving=$saving, saveFailed=$saveFailed, refusal=$refusal, saved=$saved)"
    }
}

/** Why a write was refused, one per line of desktop's `WRITE_REJECTED` that the phone can reach (#1342). */
enum class SystemPromptRefusal { Malformed, NotFound, Unclassified }

/**
 * Classifies a failed write by its code alone; the exception's message is never read. `conversation.not_found`
 * reaches here as an [IllegalArgumentException], because `RelayRequests.mapError` converts it. Two other
 * [IllegalArgumentException]s are not that refusal: a [SerializationException] from an ack that failed to
 * decode, after which the daemon has probably applied the write, so it is [SystemPromptRefusal.Unclassified];
 * and the length pre-flight in `setSystemPrompt`, which cannot fire behind [SystemPromptEditorState.Loaded.canSave].
 */
internal fun refusalFor(error: Exception): SystemPromptRefusal =
    when {
        error is RelayErrorException && error.code == "protocol.malformed" -> SystemPromptRefusal.Malformed
        error is RelayErrorException && error.code == "conversation.not_found" -> SystemPromptRefusal.NotFound
        error is SerializationException -> SystemPromptRefusal.Unclassified
        error is IllegalArgumentException -> SystemPromptRefusal.NotFound
        else -> SystemPromptRefusal.Unclassified
    }

/**
 * One conversation's system-prompt editing state, mounted by `ThreadViewModel` for the Channel info sheet's
 * System prompt section (#1342) — `HostEditorController`'s shape: a plain object composed into its owner.
 *
 * Bound at construction to one host's [repository] and one [conversationId], and reads the prompt once
 * on construction. It never starts, resets or restarts a session: a saved prompt applies at the
 * conversation's next session start, and [SystemPromptEditorState.Loaded.appliedStatus] reports that
 * rather than acting on it.
 *
 * **[scope] must be the owning view model's `viewModelScope`**, so clearing the owner cancels the read and
 * any write. Calls are expected on the main dispatcher.
 *
 * Logs are static event names only: never the prompt, its length, the conversation id, or an exception's
 * message, which is not assumed to be free of either.
 */
class SystemPromptEditor(
    private val scope: CoroutineScope,
    private val repository: ConversationRepository,
    private val conversationId: String,
) {
    private val current = MutableStateFlow<SystemPromptEditorState>(SystemPromptEditorState.Loading)

    val state: StateFlow<SystemPromptEditorState> = current.asStateFlow()

    init {
        scope.launch {
            current.value =
                try {
                    val reading = repository.requestSystemPrompt(conversationId)
                    RelayLog.d { "event=system_prompt_read" }
                    SystemPromptEditorState.Loaded(
                        confirmed = reading.systemPrompt,
                        appliedStatus = reading.sessionPromptStatus,
                        draft = reading.systemPrompt.orEmpty(),
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=system_prompt_read_failed" }
                    SystemPromptEditorState.Unavailable
                }
        }
    }

    /** Replaces the draft; allowed during a save, whose completion leaves the new text in place. */
    fun edit(text: String) {
        current.update { if (it is SystemPromptEditorState.Loaded) it.copy(draft = text) else it }
    }

    /** Sends the draft verbatim, `""` included; nothing when it is over the limit or a save is running. */
    fun save() {
        val target = begin { it.canSave } ?: return
        write(target.draft)
    }

    /** Sends `null`, removing the stored prompt; nothing when a save is running. */
    fun clear() {
        begin { it.canClear } ?: return
        write(null)
    }

    /** Marks a save as running when [allowed], in one atomic step so two taps cannot both start one. */
    private fun begin(allowed: (SystemPromptEditorState.Loaded) -> Boolean): SystemPromptEditorState.Loaded? {
        while (true) {
            val before = current.value as? SystemPromptEditorState.Loaded ?: return null
            if (!allowed(before)) return null
            if (current.compareAndSet(before, before.copy(saving = true, saveFailed = false, refusal = null, saved = false))) {
                return before
            }
        }
    }

    private fun write(value: String?) {
        scope.launch {
            try {
                repository.setSystemPrompt(conversationId, value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                RelayLog.d { "event=system_prompt_save_failed" }
                // The draft and the last confirmed value both stay, so saving again sends the same draft.
                updateLoaded { it.copy(saving = false, saveFailed = true, refusal = refusalFor(error)) }
                return@launch
            }
            RelayLog.d { "event=system_prompt_saved" }
            // The ack carries neither the value nor the status, so the status is unknown until re-read.
            updateLoaded { loaded ->
                loaded.copy(
                    confirmed = value,
                    appliedStatus = null,
                    draft = if (value == null) "" else loaded.draft,
                    saved = true,
                )
            }
            val status =
                try {
                    repository.requestSystemPrompt(conversationId).sessionPromptStatus
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=system_prompt_refresh_failed" }
                    null
                }
            // Only the status is adopted: the value just saved stays the confirmed one.
            updateLoaded { it.copy(appliedStatus = status, saving = false) }
        }
    }

    private fun updateLoaded(transform: (SystemPromptEditorState.Loaded) -> SystemPromptEditorState.Loaded) {
        current.update { if (it is SystemPromptEditorState.Loaded) transform(it) else it }
    }
}
