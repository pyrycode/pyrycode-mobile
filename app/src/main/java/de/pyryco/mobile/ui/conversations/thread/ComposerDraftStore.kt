package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Unsent composer text, held per `(serverId, conversationId)` pair for the life of the app process
 * (#789) — the mobile counterpart of desktop's `composerDraftStore`.
 *
 * Before this, [ThreadInputBar] owned its text in `rememberSaveable`, so a draft's lifetime was the
 * thread destination's composition: navigating away lost it, and the next chat opened in that slot
 * started from whatever that composition state happened to hold. Ownership belongs here instead,
 * keyed by the pair the route already carries.
 *
 * **The key is the pair, never the conversation id alone.** Conversation ids are host-local (see
 * `docs/knowledge/features/dependency-injection.md` § Host identity and snapshots), so the same id
 * under two hosts addresses two unrelated conversations; an id-only key would show one host's unsent
 * text in the other's composer. Both keys are opaque here — this class never parses, splits,
 * concatenates or resolves them, and builds no path or wire field from them.
 *
 * **In-memory only.** Nothing is written to disk or to `SavedStateHandle`: unsent text at rest is a
 * storage surface this deliberately does not open, and a process death dropping a draft is accepted.
 * Moving off `rememberSaveable` in fact *removes* one — composition state rides the saved-instance
 * `Bundle`, which is backup-eligible; this is heap and nothing else.
 *
 * **Never logged.** A draft is private user message content. Nothing here logs, and no draft text may
 * reach a log line, an exception message or a crash report.
 *
 * **Pending attachments (#932)** ride beside the text under the same pair key, in their own map with
 * the same pair isolation, process-local state and absent empty buckets. Attachment edits serialize
 * ownership transfer and cleanup. Entries hold URIs, metadata and optional owned-paste capabilities,
 * never in-memory file bytes. Owned copies live in private, backup-excluded temporary storage, and
 * [clearHost] / [clearConversation] drop them together with the text.
 *
 * **Sent originals (#984)** remember which picked URI each sent attachment id came from, under the same
 * pair key, so the thread can show the phone's own file instead of retrieving it while the picker's
 * grant still lets it read that file. Same rules again: in memory only, never logged, dropped with the
 * pair or the host. A grant ends with the process at the latest, and so does this map.
 */
class ComposerDraftStore {
    private val _drafts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    private val _attachments = MutableStateFlow<Map<String, Map<String, List<PendingAttachment>>>>(emptyMap())

    private val attachmentLock = Any()

    private val nextAttachmentKey = AtomicLong()

    // Host to conversation to attachment id to the content URI it was uploaded from. Not exposed as a
    // flow: nothing renders it, and the thread reads it once per attachment it shows.
    private val sentOriginals = MutableStateFlow<Map<String, Map<String, Map<String, String>>>>(emptyMap())

    /** Every live pair's pending attachments, host to conversation to entries in the order added. */
    val attachments: StateFlow<Map<String, Map<String, List<PendingAttachment>>>> = _attachments.asStateFlow()

    /** Every live draft, host to conversation to exact text. Empty entries and buckets are absent. */
    val drafts: StateFlow<Map<String, Map<String, String>>> = _drafts.asStateFlow()

    /** This pair's draft, or `""` when it has none. */
    fun draftFor(
        serverId: String,
        conversationId: String,
    ): String = _drafts.value[serverId]?.get(conversationId).orEmpty()

    /**
     * Replace this pair's draft with [text], exactly as given — surrounding whitespace included.
     *
     * Only the empty string clears: a whitespace-only draft is still the user's text. Emptying a host's
     * last conversation drops its bucket too, so the map never accumulates empty branches.
     *
     * Rebuilt through [update] — a compare-and-set loop, not a read-then-assign — so a concurrent write
     * to a different pair can never be lost.
     */
    fun setDraft(
        serverId: String,
        conversationId: String,
        text: String,
    ) {
        _drafts.update { hosts ->
            val conversations = hosts[serverId].orEmpty()
            val updated =
                if (text.isEmpty()) conversations - conversationId else conversations + (conversationId to text)
            if (updated.isEmpty()) hosts - serverId else hosts + (serverId to updated)
        }
    }

    /**
     * Drop every draft held for [serverId] (#790) — that host's whole bucket, and nothing else.
     *
     * Called when a pairing is removed. A `serverId` is stable across a re-pair, so without this,
     * pairing the same server again would resurface text typed before the unpair; unpairing is the
     * user's revocation gesture, and unsent text addressed to that host must not survive it.
     *
     * Matching is exact, case-sensitive [String] equality — the same identity rule
     * [de.pyryco.mobile.data.crypto.PairedServerCollectionStore] uses — so two hosts whose ids differ
     * only in case, or one of whose ids is a prefix of the other, keep their own drafts. Nothing here
     * builds a key: the bucket is looked up whole and removed whole.
     *
     * An unknown or already-empty [serverId] is a no-op, matching that store's own unknown-id contract,
     * so the caller needs no existence check.
     *
     * Non-suspending: text uses [update] and attachment ownership uses [attachmentLock], so a
     * concurrent write to another host cannot be lost. Its caller runs it after a removal has already
     * succeeded, where a throw would be reported as a failed unpair.
     */
    fun clearHost(serverId: String) {
        _drafts.update { it - serverId }
        synchronized(attachmentLock) {
            val removed =
                _attachments.value[serverId]
                    .orEmpty()
                    .values
                    .flatten()
            _attachments.update { it - serverId }
            removed.forEach { it.ownedPaste?.release() }
        }
        sentOriginals.update { it - serverId }
    }

    /**
     * Drop this pair's draft (#790), leaving the same host's other conversations alone.
     *
     * Called when a conversation is deleted. Named rather than spelled `setDraft(…, "")` at the call
     * site: a deletion is not an edit to empty, and the delete path must not depend on [setDraft]'s
     * "only the empty string clears" convention to mean "this chat is gone".
     */
    fun clearConversation(
        serverId: String,
        conversationId: String,
    ) {
        setDraft(serverId, conversationId, "")
        editAttachments(serverId, conversationId) { emptyList() }
        sentOriginals.update { hosts ->
            val conversations = hosts[serverId]?.minus(conversationId) ?: return@update hosts
            if (conversations.isEmpty()) hosts - serverId else hosts + (serverId to conversations)
        }
    }

    /** This pair's pending attachments in the order added, or empty when it has none (#932). */
    fun attachmentsFor(
        serverId: String,
        conversationId: String,
    ): List<PendingAttachment> = _attachments.value[serverId]?.get(conversationId).orEmpty()

    /**
     * Append a pending attachment to this pair's draft (#932), unless it is refused.
     *
     * [AttachmentAddOutcome.TOO_LARGE] when [size] is known and over [AttachmentUploadLimit.MAX_BYTES];
     * an unknown size is accepted, because the read at send time bounds the bytes anyway.
     * [AttachmentAddOutcome.TOO_MANY] when the pair already holds [MessageAttachmentIds.MAX] entries. The
     * count is checked under [attachmentLock], so two concurrent adds at one below the limit cannot
     * both land. The provider's name and type are clamped by [clampProviderText].
     */
    fun addAttachment(
        serverId: String,
        conversationId: String,
        uri: String,
        displayName: String,
        mimeType: String,
        size: Long?,
        ownedPaste: OwnedPasteCopy? = null,
    ): AttachmentAddOutcome {
        if (size != null && size > AttachmentUploadLimit.MAX_BYTES) {
            ownedPaste?.release()
            return AttachmentAddOutcome.TOO_LARGE
        }
        val entry =
            PendingAttachment(
                key = nextAttachmentKey.incrementAndGet(),
                uri = uri,
                displayName = clampProviderText(displayName),
                mimeType = clampProviderText(mimeType),
                size = size,
                ownedPaste = ownedPaste,
            )
        var outcome = AttachmentAddOutcome.ADDED
        editAttachments(serverId, conversationId) { current ->
            if (current.size >= MessageAttachmentIds.MAX) {
                outcome = AttachmentAddOutcome.TOO_MANY
                current
            } else {
                outcome = AttachmentAddOutcome.ADDED
                current + entry
            }
        }
        if (outcome != AttachmentAddOutcome.ADDED) ownedPaste?.release()
        return outcome
    }

    /** Drop the entry [key] from this pair's draft (#932), the rest kept in order. Unknown is a no-op. */
    fun removeAttachment(
        serverId: String,
        conversationId: String,
        key: Long,
    ) {
        removeAttachments(serverId, conversationId, setOf(key))
    }

    /**
     * Drop exactly the entries named by [keys] (#932) — the post-send clear. Entries added while a send
     * was in flight are not in its snapshot, so they survive it.
     */
    fun removeAttachments(
        serverId: String,
        conversationId: String,
        keys: Set<Long>,
    ) {
        editAttachments(serverId, conversationId) { current -> current.filterNot { it.key in keys } }
    }

    /**
     * Record that the daemon acknowledged entry [key]'s upload as [attachmentId] (#932), so a retry after
     * a later failure names it instead of uploading it again. An entry that is gone stays gone.
     */
    fun markUploaded(
        serverId: String,
        conversationId: String,
        key: Long,
        attachmentId: String,
    ) {
        editAttachments(serverId, conversationId) { current ->
            current.map { if (it.key == key) it.copy(attachmentId = attachmentId) else it }
        }
    }

    /**
     * Remember that each attachment id in [originals] was uploaded from its content URI (#984), for this
     * pair. Recorded when the send names them, so the thread can show the phone's own file.
     */
    fun recordSentOriginals(
        serverId: String,
        conversationId: String,
        originals: Map<String, String>,
    ) {
        if (originals.isEmpty()) return
        sentOriginals.update { hosts ->
            val conversations = hosts[serverId].orEmpty()
            val merged = conversations[conversationId].orEmpty() + originals
            hosts + (serverId to conversations + (conversationId to merged))
        }
    }

    /** The content URI this pair's [attachmentId] was sent from in this app session, or `null` (#984). */
    fun sentOriginal(
        serverId: String,
        conversationId: String,
        attachmentId: String,
    ): String? = sentOriginals.value[serverId]?.get(conversationId)?.get(attachmentId)

    /** Serialize one pair's edit and release removed copies once, outside any StateFlow CAS retries. */
    private fun editAttachments(
        serverId: String,
        conversationId: String,
        edit: (List<PendingAttachment>) -> List<PendingAttachment>,
    ) {
        synchronized(attachmentLock) {
            val hosts = _attachments.value
            val conversations = hosts[serverId].orEmpty()
            val current = conversations[conversationId].orEmpty()
            val updated = edit(current)
            val next = if (updated.isEmpty()) conversations - conversationId else conversations + (conversationId to updated)
            _attachments.value = if (next.isEmpty()) hosts - serverId else hosts + (serverId to next)
            val kept = updated.mapTo(HashSet()) { it.key }
            current.filterNot { it.key in kept }.forEach { it.ownedPaste?.release() }
        }
    }
}
