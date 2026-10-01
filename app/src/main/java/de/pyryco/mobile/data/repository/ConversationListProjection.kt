package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.ConversationsPayload
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toConversation
import de.pyryco.mobile.data.network.toConversations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The conversation list and the last-message previews on one connection (#913): the list projection, the
 * most-recent message per conversation, and every write that folds into either, split out of
 * [RemoteConversationRepository] the way the thread store was (#912). The repository keeps the routing: its
 * `onInbound` arms hand the `conversations` snapshot here, and fold the frames that also feed something
 * else (`message`, `conversation_updated`, `workspace_updated`, `session_transition`) through the writes
 * below once they have decoded them. Its mutations upsert their confirmed replies here, and
 * [RemoteConversationRepository.promote] and [RemoteConversationRepository.archiveWorkspace] read
 * [current].
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository. Nothing here logs.
 */
internal class ConversationListProjection {
    /**
     * The demuxed list projection: `null` until the first `conversations` snapshot loads, then the
     * latest full-list snapshot. The primary read source; [observe] derives every cold
     * read from it. Written by the repository's single inbound collector (the authoritative full-replace on
     * each `conversations` snapshot, [applySnapshot]), by [RemoteConversationRepository.createDiscussion]'s
     * confirmed insert (#347), **and** by [RemoteConversationRepository.promote]'s confirmed upsert (#348) —
     * both mutations fold a [Conversation] in via
     * [upsertConversation], an atomic [MutableStateFlow.update] CAS upsert (dedup by id) run only after
     * the correlated reply (`conversation_created` / `conversation_updated`) lands, so the writers
     * retry-merge rather than clobber. Two further inbound writers land here since #721: an
     * **unsolicited** `conversation_updated` folds through that same [upsertConversation], and a
     * `workspace_updated` relabels every row sharing its path via [applyWorkspaceLabel].
     * [RemoteConversationRepository.promote] additionally **reads** [current] (a
     * lock-free snapshot) to resolve the conversation's existing cwd when its `workspace` argument is
     * null. `StateFlow` conflation means a value-equal result does not re-emit (e.g. a redundant reply
     * to a second collector's request, or the authoritative snapshot that later re-includes a
     * just-folded conversation).
     */
    private val projection = MutableStateFlow<List<Conversation>?>(null)

    /**
     * `conversationId -> most-recent` [Message] seen on this connection's live `message` stream
     * (#329). Written by the repository's single inbound collector **and** by
     * [RemoteConversationRepository.sendMessage]'s confirmed
     * insert (#346) — two writers, but every write goes through the atomic [MutableStateFlow.update]
     * fold in [recordLastMessage], so concurrent updates retry-merge correctly. [observeLastMessage] fans
     * out from it. Connection-scoped in-memory state — lost on process death and re-derived from the live
     * stream on reconnect (the cold-start gap is the #313 backfill hand-off). The fold is
     * strictly-greater-by-timestamp, mirroring the fake's `maxByOrNull { it.timestamp }`.
     */
    private val lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())

    /**
     * Apply one `conversations` snapshot. Called from the repository's ungated arm; the reasoning below
     * was written for that arm and moved here with it.
     */
    fun applySnapshot(envelope: Envelope) {
        // The reply to our request AND any unsolicited change push arrive as a full-list
        // `conversations` snapshot, so re-emission needs no in_reply_to correlation (AC #3).
        // Decode is the single failure surface: a malformed payload (missing required field
        // → SerializationException, bad timestamp → IllegalArgumentException; the former is a
        // subtype of the latter) is dropped so the single inbound consumer survives, rather
        // than letting an uncaught throw freeze every future update for the connection.
        val decoded =
            try {
                MobileJson.decodeFromJsonElement<ConversationsPayload>(envelope.payload)
            } catch (e: IllegalArgumentException) {
                return
            }
        projection.value = decoded.toConversations()
    }

    /**
     * The list this connection currently holds, as a one-time snapshot (empty before the first
     * `conversations` snapshot), read synchronously by [RemoteConversationRepository.promote] and
     * [RemoteConversationRepository.archiveWorkspace].
     */
    fun current(): List<Conversation> = projection.value.orEmpty()

    /**
     * Most-recent-by-timestamp fold for [conversationId]'s last-message preview ([lastMessages]).
     * Atomic check-then-replace: replace the stored entry **iff** [message]'s timestamp is strictly
     * greater, so out-of-order older arrivals and re-delivered duplicates are no-ops (no re-emit).
     * Called by both the live `message` collector arm and [RemoteConversationRepository.sendMessage]'s
     * confirmed insert.
     */
    fun recordLastMessage(
        conversationId: String,
        message: Message,
    ) {
        lastMessages.update { current ->
            val existing = current[conversationId]
            if (existing != null && message.timestamp <= existing.timestamp) {
                current
            } else {
                current + (conversationId to message)
            }
        }
    }

    /**
     * Fold a `session_transition`'s [newSessionId] into the list [projection] entry for [conversationId]
     * (#578) — the sibling write to [ThreadProjection.appendSessionBoundary], resolving the live session identity the v2
     * `conversations` summary omits (so a session-scoped frame like `set_session_settings` targets the
     * real session instead of the defaulted empty id). Field-updates an **existing** entry only: a
     * [List.map] over the current list, so an unknown [conversationId] yields an element-equal list
     * ([kotlinx.coroutines.flow.StateFlow] conflation ⇒ no re-emit, **no phantom conversation** — unlike
     * [ThreadProjection.appendSessionBoundary]
     * the list projection must not gain a phantom entry, so the `else it` identity branch *is* the
     * absent-conversation guard), and a `null` (pre-first-snapshot) projection stays `null`. Written
     * **verbatim for every reason** (`clear` carries the freshly-rotated-to id; `idle_evict` carries the
     * evicted id unchanged ⇒ element-equal no-op in the common case), mirroring [ThreadProjection.appendSessionBoundary]'s
     * copy-through posture. The atomic [MutableStateFlow.update] CAS retry-merges against a concurrent
     * authoritative `conversations` snapshot rather than clobbering it, as [upsertConversation] does.
     * [newSessionId] is sensitive and is never logged (Security review).
     */
    fun updateCurrentSessionId(
        conversationId: String,
        newSessionId: String,
    ) {
        projection.update { current ->
            current?.map { if (it.id == conversationId) it.copy(currentSessionId = newSessionId) else it }
        }
    }

    /**
     * Confirmed-insert [conversation] into the list [projection] (#347): an atomic
     * [MutableStateFlow.update] CAS upsert — replace the entry with the same `id` in place, else
     * append — so a concurrent authoritative `conversations` snapshot retry-merges rather than being
     * lost, and a re-delivered create is idempotent. Folding into the `null` (pre-first-snapshot)
     * projection yields a single-element list, which [observe] then emits (AC #2).
     *
     * Takes the decoded record rather than its [Conversation] because the record's `agent` may be absent
     * (#1108): an older daemon omits it on `conversation_updated`, so a record without it keeps the stored
     * row's [Conversation.agent] instead of resetting a Codex conversation to Claude. Returns the row as
     * stored, so a caller that hands the conversation back returns the kept agent too.
     *
     * The record never carries `archived_at` (#1332), so a still-archived row keeps the stamp the last
     * snapshot gave it, and an unarchived one is left without. A row archived here gets its stamp from the
     * next `conversations` snapshot.
     */
    fun upsertConversation(record: ConversationResponseDto): Conversation {
        val incoming = record.toConversation()
        var stored = incoming
        projection.update { current ->
            val existing = current.orEmpty()
            val index = existing.indexOfFirst { it.id == incoming.id }
            if (index >= 0) {
                val held = existing[index]
                stored =
                    incoming.copy(
                        agent = if (record.agent == null) held.agent else incoming.agent,
                        archivedAt = if (incoming.archived) held.archivedAt else null,
                    )
                existing.toMutableList().apply { this[index] = stored }
            } else {
                stored = incoming
                existing + incoming
            }
        }
        return stored
    }

    /**
     * Apply a `workspace_updated` notification (#721) to the list [projection]: every conversation whose
     * [Conversation.cwd] equals [path] takes [label] — a string replacing the stored display name, a
     * `null` clearing it. A workspace is a **folder**, and N conversations may share one, so this is a
     * fan-out over the whole projection rather than a keyed upsert; archived rows are included, because
     * archiving a conversation does not un-name its folder.
     *
     * Direct sibling of [updateCurrentSessionId], and it inherits that shape's three properties:
     * [path] is matched by **exact string equality** — no trim, no normalization, no filesystem access
     * (the protocol compares the path as bytes, so two paths differing by a trailing separator are
     * distinct workspaces and normalizing would merge workspaces the daemon keeps apart); the `else it`
     * identity branch means a path matching no row is a genuine no-op, since `map` then returns an
     * element-equal list and [kotlinx.coroutines.flow.StateFlow] conflation suppresses re-emission — **no
     * phantom conversation**, as the frame carries a path and not a conversation; and a `null`
     * (pre-first-snapshot) projection stays `null`, a push that arrives before the first snapshot having no
     * rows to label.
     *
     * The atomic [MutableStateFlow.update] CAS is load-bearing rather than stylistic: [projection] is
     * written from caller coroutines too (the correlated mutations, via [upsertConversation]), so a
     * `.value = …` read-modify-write would open a real check-then-mutate window against a concurrent
     * snapshot or upsert.
     *
     * [path] and [label] are never logged (Security review): the path is a filesystem location on the
     * daemon's host and the label is operator-authored text, and the daemon keeps both out of its own
     * records for this verb. [label] is stored **verbatim** — never trimmed or truncated, and a blank is
     * not folded to `null`; the daemon's 128-byte bound is a size limit and not a safety property, and
     * safe rendering of this opaque text belongs to the consuming slices (#722, #641).
     */
    fun applyWorkspaceLabel(
        path: String,
        label: String?,
    ) {
        projection.update { current ->
            current?.map { if (it.cwd == path) it.copy(workspaceLabel = label) else it }
        }
    }

    /**
     * Remove [conversationId] from the list [projection] and from [lastMessages] after a confirmed
     * `delete` (#532) — the list and last-message half of [ConversationCommands]' `removeConversation`, which
     * clears the thread through [ThreadProjection.remove] beside this call. Idempotent by construction:
     * `List.filterNot` returns an element-equal list when the id is absent, and `Map - missingKey` an
     * equals-identical map, so [kotlinx.coroutines.flow.StateFlow] conflation makes deleting an
     * already-absent id re-emit nothing on either.
     */
    fun remove(conversationId: String) {
        projection.update { current -> current?.filterNot { it.id == conversationId } }
        lastMessages.update { it - conversationId }
    }

    /**
     * The filtered, sorted list behind [RemoteConversationRepository.observeConversations]: emits nothing
     * until the first snapshot loads, then each change of [projection] through [project].
     */
    fun observe(filter: ConversationFilter): Flow<List<Conversation>> = projection.filterNotNull().map { project(it, filter) }

    /**
     * Most-recent live [Message] for [conversationId] (#329), a pure cold projection of the shared
     * [lastMessages] `StateFlow`. Issues no request — rides the live `message` stream. A `StateFlow`
     * always has a current value, so every collector (including a `flatMapLatest` re-subscription)
     * receives the current most-recent (or `null` when the conversation is absent) on subscription
     * and re-emits only on change; the one inbound consumer fans out to unlimited collectors.
     */
    fun observeLastMessage(conversationId: String): Flow<Message?> = lastMessages.map { it[conversationId] }.distinctUntilChanged()

    /** Apply the [ConversationFilter] then order most-recently-used first — mirrors the fake exactly. */
    private fun project(
        conversations: List<Conversation>,
        filter: ConversationFilter,
    ): List<Conversation> =
        conversations
            .filter { conversation ->
                when (filter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Channels -> conversation.isPromoted && !conversation.archived
                    ConversationFilter.Discussions -> !conversation.isPromoted && !conversation.archived
                    ConversationFilter.Archived -> conversation.archived
                }
            }.sortedByDescending { it.lastUsedAt }
}
