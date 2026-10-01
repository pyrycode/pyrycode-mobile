package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Conversation(
    val id: String,
    val name: String?,
    val cwd: String,
    val currentSessionId: String,
    val sessionHistory: List<String>,
    val isPromoted: Boolean,
    val lastUsedAt: Instant,
    val isSleeping: Boolean = false,
    val archived: Boolean = false,
    /** The host's per-conversation mute; `false` when an older daemon omits it, so alerts still fire. */
    val muted: Boolean = false,
    /** Opaque daemon-authored display text; independent of [cwd] and never a path. */
    val workspaceLabel: String? = null,
    /** The agent that runs this conversation's session; Claude when the daemon does not say. */
    val agent: ConversationAgent = ConversationAgent.Claude,
    /**
     * When the daemon archived this conversation, or `null` for an active row, a row archived before the
     * daemon recorded the time, or a stamp that did not parse. See [archiveKey].
     */
    val archivedAt: Instant? = null,
)

/** The instant the Archive screen orders and labels a row by: its archive time, else its last use. */
val Conversation.archiveKey: Instant
    get() = archivedAt ?: lastUsedAt

/**
 * The Archive screen's order, shared with desktop: [archiveKey] newest first, then id ascending by UTF-16
 * code units, so equal keys still sort the same way on every client.
 */
val ArchiveOrder: Comparator<Conversation> =
    compareByDescending<Conversation> { it.archiveKey }.thenBy { it.id }

/** The agent a conversation runs on, as the daemon's `agent` field names it (`claude` or `codex`). */
enum class ConversationAgent {
    Claude,
    Codex,
}

/**
 * Sentinel `cwd` for conversations with no bound workspace.
 * Conversations whose `cwd` equals this value render without a workspace label.
 */
const val DEFAULT_SCRATCH_CWD: String = "~/.pyrycode/scratch"
