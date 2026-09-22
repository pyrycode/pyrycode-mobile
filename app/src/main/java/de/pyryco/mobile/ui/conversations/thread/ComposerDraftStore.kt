package de.pyryco.mobile.ui.conversations.thread

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 */
class ComposerDraftStore {
    private val _drafts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

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
}
