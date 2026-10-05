package de.pyryco.mobile.ui.conversations.thread

/**
 * One file chosen for a chat's unsent message (#932), held in [ComposerDraftStore] beside the draft
 * text until a send delivers it.
 *
 * **Metadata and an optional owned-paste capability, never in-memory bytes.** [uri] is the external
 * content URI, kept as a string so this type stays free of `android.net.Uri`. Picker entries are read
 * through [AttachmentReader] at send time; pasted images use [ownedPaste] for both preview and upload.
 * One file's bytes are read at a time, and no external URI is ever turned into a filesystem path.
 *
 * [displayName], [mimeType] and [size] come from another app's content provider and are untrusted: the
 * two strings are clamped where they enter (see [clampProviderText]) and bounded again on the wire by
 * `AttachmentChunkPlan`; the size is a hint the read re-checks, since a provider can report anything.
 *
 * [attachmentId] is set once the daemon acknowledged this file's upload, so a send that failed later
 * does not upload it again on retry. [key] is minted by the store: the handle a remove names and the
 * strip's stable list key. It is local and never sent.
 *
 * **Never logged.** [toString] prints no URI, name, type or id, so a crash trace cannot carry them.
 */
data class PendingAttachment(
    val key: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val size: Long?,
    val attachmentId: String? = null,
    val ownedPaste: OwnedPasteCopy? = null,
) {
    override fun toString(): String = "PendingAttachment(key=$key, size=$size, uploaded=${attachmentId != null})"
}

/** What [ComposerDraftStore.addAttachment] did with an entry (#932), so the UI can show a refusal. */
enum class AttachmentAddOutcome {
    ADDED,

    /** The provider's reported size is over `AttachmentUploadLimit.MAX_BYTES`. */
    TOO_LARGE,

    /** The draft already holds `MessageAttachmentIds.MAX` entries, the most one message may name. */
    TOO_MANY,
}

/**
 * The most characters of a provider's display name or MIME type a pending entry keeps (#932). Far above
 * the 255-byte wire cut `AttachmentChunkPlan` applies, so it never changes what is uploaded; it only
 * stops a hostile provider parking megabytes of name text in the heap.
 */
internal const val PENDING_ATTACHMENT_TEXT_MAX_CHARS = 1024

/** [text] cut to [PENDING_ATTACHMENT_TEXT_MAX_CHARS], never leaving half of a surrogate pair at the end. */
internal fun clampProviderText(text: String): String {
    if (text.length <= PENDING_ATTACHMENT_TEXT_MAX_CHARS) return text
    val end = PENDING_ATTACHMENT_TEXT_MAX_CHARS.let { if (text[it - 1].isHighSurrogate()) it - 1 else it }
    return text.substring(0, end)
}
