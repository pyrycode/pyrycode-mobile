package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement

/**
 * Phase 1 data-layer contract. The fake (Phase 1) and Ktor-backed remote
 * (Phase 4) implementations both satisfy this surface.
 *
 * Stream-shaped reads are cold [Flow]s — collectors receive the current
 * value on subscription and every subsequent change. Mutating operations
 * are `suspend` one-shots that return the affected entity so callers do
 * not need to re-fetch; the affected stream(s) will also re-emit.
 */
interface ConversationRepository {
    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>

    fun observeMessages(conversationId: String): Flow<List<ThreadItem>>

    /**
     * Emits the most-recent [Message] (by [Message.timestamp]) for the
     * conversation, or `null` if the conversation has no messages or is
     * unknown. Cold flow, re-emits on every state change.
     */
    fun observeLastMessage(conversationId: String): Flow<Message?>

    /**
     * Emits whether [conversationId] is currently stalled — its remote claude has
     * stopped making forward progress (PTY quiet while not idle, no JSONL progress;
     * typically a screen-parser break). `true` on stall onset, `false` once the wire
     * signals recovery (the next forward-progress event). Cold flow; re-emits on every
     * change. The thread layer observes this to react to a stall (#396).
     *
     * Default `flowOf(false)` — implementations without an interactive wire (the fake,
     * inline test doubles) inherit "never stalled" and need no override, the same
     * cascade-avoidance as [delete] / [requestScreenSnapshot] / [recentWorkspaces].
     */
    fun observeStall(conversationId: String): Flow<Boolean> = flowOf(false)

    /**
     * Emits [conversationId]'s ordered queued-message backlog (FIFO) — the messages waiting while
     * claude is busy (#460). Each `queue_state` snapshot the daemon broadcasts replaces the backlog in
     * full; the flow re-emits the new ordered list. Empty until the first snapshot lands. Cold flow;
     * re-emits on every change. The thread layer observes this to render the backlog (#461).
     *
     * Default `flowOf(emptyList())` — implementations without an interactive wire (the fake, inline
     * test doubles) inherit "never queued" and need no override, the same cascade-avoidance as
     * [observeStall] / [delete] / [requestScreenSnapshot].
     */
    fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = flowOf(emptyList())

    /**
     * Emits whether [conversationId]'s remote claude is currently stuck retrying an API error, and at
     * which attempt (#593). [ApiRetryStatus.NotRetrying] until the wire says otherwise; a rising edge
     * carries the parsed `attempt N/M` counter ([ApiRetryStatus.Attempt]) or
     * [ApiRetryStatus.AttemptUnknown] when the daemon could not parse one; a falling edge returns to
     * [ApiRetryStatus.NotRetrying]. A re-fired rising edge with a climbed counter re-emits with the new
     * value. Cold flow; re-emits on every change. The thread layer observes this to say "Retrying —
     * attempt N/M" instead of an indefinite thinking spinner (#594).
     *
     * Default `flowOf(NotRetrying)` — implementations without an interactive wire (the fake, inline
     * test doubles) inherit "never retrying" and need no override, the same cascade-avoidance as
     * [observeStall] / [observeQueue] / [delete].
     */
    fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = flowOf(ApiRetryStatus.NotRetrying)

    /**
     * Emits whether [conversationId]'s remote claude is currently auto-compacting its context (#596).
     * `false` until the wire says otherwise; `true` on the rising edge, back to `false` on the explicit
     * falling edge — unlike [observeStall], whose recovery is inferred from forward progress. Cold flow;
     * re-emits on every change. The thread layer observes this to distinguish compaction from a frozen
     * spinner while claude goes silent for tens of seconds (#597). On/off only — the wire carries no
     * compaction progress, so there is nothing to report beyond the edge.
     *
     * Default `flowOf(false)` — implementations without an interactive wire (the fake, inline test
     * doubles) inherit "never compacting" and need no override, the same cascade-avoidance as
     * [observeStall] / [observeQueue] / [observeApiRetry].
     */
    fun observeCompacting(conversationId: String): Flow<Boolean> = flowOf(false)

    /**
     * Whether this repository can actually perform the conversation-mutation actions
     * ([archive] / [unarchive] / [rename] / [startNewSession] / [changeWorkspace] / [delete]).
     * A UI gating consumer reads this to stop offering actions the backend cannot service.
     *
     * Default `true` — implementations that support every mutation (the fake, inline test doubles)
     * inherit "supported" and need no override, the same cascade-avoidance as [observeStall] /
     * [observeQueue] / [delete]. [RemoteConversationRepository] overrides it to `false` alongside its
     * throwing mutation methods (relay has no v2 wire message for these mutations yet).
     */
    val mutationsSupported: Boolean get() = true

    suspend fun createDiscussion(workspace: String? = null): Conversation

    suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String? = null,
    ): Conversation

    suspend fun archive(conversationId: String)

    suspend fun unarchive(conversationId: String)

    /**
     * Permanently removes the conversation from the store. Tolerant of unknown
     * ids: calling `delete` on an id that is not present is a silent no-op.
     *
     * Unlike [archive] and [unarchive], which throw [IllegalArgumentException]
     * on unknown ids, `delete` converges on the post-condition — after a
     * successful return, the conversation is not in [observeConversations].
     *
     * Streams collected for the deleted conversation re-emit the empty
     * projection ([observeMessages] → `emptyList()`; [observeLastMessage] →
     * `null`); they do not complete.
     *
     * Default throws — implementations that do not support deletion inherit
     * the default. The Channel Info sheet is the only production consumer;
     * test fakes never invoke this method, so the throwing default is
     * unreachable in tests today.
     */
    suspend fun delete(conversationId: String): Unit = error("delete is not implemented for this ConversationRepository")

    suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation

    /**
     * Applies the operator's run-configuration change — model / effort / YOLO — to the running
     * session [sessionId], sending a `set_session_settings` request and returning only after the
     * daemon's ack (#543). **Takes a session id, not a conversation id** — this is the first
     * session-scoped mutation; consumers source it from [Conversation.currentSessionId].
     *
     * Each setting carries a **presence contract**: a `null` argument means "leave unchanged" and is
     * omitted from the request; a non-null value (including `false` / `""`) is sent. So a single-control
     * change carries only that one field — `setSessionSettings(id, model = "opus")` sends model alone.
     *
     * Returns [Unit]: the ack echoes only the input `session_id`, so success is a normal return and
     * failure a thrown exception the caller catches. Throws [IllegalStateException] when the session is
     * not connected, and [de.pyryco.mobile.data.network.RelayErrorException] for any server error
     * (e.g. `session.not_found` for an unhosted session, `protocol.malformed` for an invalid
     * model/effort the daemon re-validates) — distinguishable by its `code`.
     *
     * Default throws — implementations that do not support settings inherit it, so the inline test
     * doubles need no override (the same cascade-avoidance as [delete] / [requestScreenSnapshot]). The
     * Fake and Remote override it.
     */
    suspend fun setSessionSettings(
        sessionId: String,
        model: String? = null,
        effort: String? = null,
        yolo: Boolean? = null,
    ): Unit = error("setSessionSettings is not implemented for this ConversationRepository")

    suspend fun startNewSession(
        conversationId: String,
        workspace: String? = null,
    ): Session

    suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session

    /**
     * Appends a user-authored [Message] to the conversation's current session.
     * Returns the persisted message. Throws [IllegalArgumentException] if
     * [conversationId] does not exist. Caller is responsible for non-blank
     * validation of [text]; this method does not trim or reject blank input.
     */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message

    /**
     * Workspace folders previously bound to any conversation, deduped and ordered
     * most-recent-first by the latest cwd-affecting write across all conversations.
     *
     * Excludes "no bound workspace" cwds: the empty string `""` and
     * [DEFAULT_SCRATCH_CWD]. Cold flow; re-emits on every state change.
     *
     * Default returns an empty flow — implementations that do not track workspace
     * history (e.g. test fakes that ignore this surface) inherit the default and
     * do not need to override.
     */
    fun recentWorkspaces(): Flow<List<String>> = flowOf(emptyList())

    /**
     * Creates a new workspace folder under the `pyry-workspace/` prefix and
     * registers it in the recents stream. Returns the created path string
     * (e.g. `"pyry-workspace/scratch-1"`).
     *
     * Phase 0 implementations operate in-memory only; no filesystem I/O.
     * Phase 4 implementations will create the folder server-side.
     *
     * Throws [IllegalArgumentException] if [name] is blank or whitespace-only.
     * Trimming and basename normalization are caller concerns.
     *
     * Default throws — implementations that do not support folder creation
     * (e.g. test fakes that ignore this surface) inherit the default. The
     * Workspace Picker is the only production consumer; test fakes never
     * invoke this method, so the throwing default is unreachable in tests.
     */
    suspend fun createWorkspaceFolder(name: String): String =
        error("createWorkspaceFolder is not implemented for this ConversationRepository")

    /**
     * Requests the current claude screen for [conversationId] and returns its rendered text — the
     * always-available, parser-independent snapshot floor (pyrycode#596, ADR 025 § Safe degradation).
     * The returned text is **verbatim**: never parsed, trimmed, or sanitized — decode fidelity is the
     * whole point of the floor.
     *
     * Throws [IllegalArgumentException] for an unknown [conversationId] (the fake throws it
     * synchronously; the remote surfaces the server's `conversation.not_found` as the same type).
     * Throws on a server error ([de.pyryco.mobile.data.network.RelayErrorException]) or a
     * not-connected session ([IllegalStateException]) — the caller handles failure.
     *
     * Default throws — implementations that do not support snapshots inherit it, so the inline test
     * doubles need no override (the same cascade-avoidance as [delete] / [createWorkspaceFolder]).
     */
    suspend fun requestScreenSnapshot(conversationId: String): String =
        error("requestScreenSnapshot is not implemented for this ConversationRepository")

    /**
     * Drops a not-yet-drained message from [conversationId]'s queued backlog by sending a
     * `dequeue_message` frame carrying the conversation id and the message's [queuedMessageId] (#466,
     * ADR 025). [queuedMessageId] is the [QueuedMessage.id] the caller received from [observeQueue],
     * passed back **verbatim**; it is the daemon's per-conversation `queued_msg_id` — a wire `uint64`,
     * so a [Long] (not a `String`; the pyrycode#720 trap), the same width [observeQueue] decodes.
     *
     * **No projection side effect.** Success is "returned without throwing"; this slice mutates no
     * local state. The backlog updates only by a subsequent `queue_state` snapshot on [observeQueue]
     * (#460) — there is nothing to roll back on failure.
     *
     * Throws [IllegalArgumentException] for an unknown [conversationId] (the remote surfaces the
     * server's `conversation.not_found` as that type). Throws on a server error
     * ([de.pyryco.mobile.data.network.RelayErrorException]) — a stale / already-drained id surfaces
     * generically there — or a not-connected session ([IllegalStateException]); the caller handles
     * failure and leaves the backlog unchanged.
     *
     * Default throws — implementations without an interactive wire (the fake, inline test doubles)
     * inherit it, so no test double needs to override it (the same cascade-avoidance as [delete] /
     * [createWorkspaceFolder] / [requestScreenSnapshot]).
     */
    suspend fun dropQueuedMessage(
        conversationId: String,
        queuedMessageId: Long,
    ): Unit = error("dropQueuedMessage is not implemented for this ConversationRepository")

    /**
     * Fetches one page of [conversationId]'s stored history — the scroll-back read (#623). A client
     * that opens an existing conversation sees only what arrived after it connected; this walks
     * **backwards** through the daemon's on-disk log, newest-first, one page per call.
     *
     * **Not a reconnect backfill.** The daemon's replay ring is an in-memory catch-up across a dropped
     * connection and is empty after a daemon restart; this log is on disk and survives one. A client
     * uses both, and never joins their ids — see [HistoryEntry.id].
     *
     * @param cursor The [HistoryPage.cursor] the previous page handed back, echoed **verbatim** —
     *   opaque, never parsed or rebuilt. Empty (the default) means "start at the newest", which is the
     *   normal opening value of a walk and not a missing one.
     * @param limit How many entries the caller wants. `0` (the default) asks the daemon to choose and
     *   **never** means zero entries; a large ask is clamped rather than refused; and a page may come
     *   back shorter than asked so the daemon's own frame fits its size cap. Read
     *   [HistoryPage.entries]`.size`, never assume the ask was honoured — and never read a short page
     *   as the start of the log ([HistoryPage.atStart] is the only signal for that). A negative value
     *   is rejected by the daemon.
     *
     * Throws [IllegalArgumentException] for an unknown [conversationId] (the remote surfaces the
     * server's `conversation.not_found` as that type, the fake throws it directly), and
     * [de.pyryco.mobile.data.network.RelayErrorException] for any server error — the `history.*` codes
     * are distinguishable by its `code`, and `history.unavailable` is the only **retryable** member.
     * Throws [IllegalStateException] when the session is not connected, and the decode exception for a
     * malformed page. Every failure is scoped to **this ask alone**: no projection is touched and no
     * other conversation's state changes.
     *
     * Default throws — implementations without a history log (inline test doubles) inherit it, the
     * same cascade-avoidance as [delete] / [requestScreenSnapshot] / [dropQueuedMessage].
     */
    suspend fun requestHistory(
        conversationId: String,
        cursor: String = "",
        limit: Int = 0,
    ): HistoryPage = error("requestHistory is not implemented for this ConversationRepository")
}

enum class ConversationFilter { All, Channels, Discussions, Archived }

/**
 * One row in the conversation thread. The stream interleaves messages
 * with synthetic [SessionBoundary] markers and diagnostic
 * [UnrecognizedMessage] rows in chronological order; the thread screen
 * renders boundaries as horizontal-rule delimiters and de-emphasizes
 * rows above the latest delimiter.
 */
sealed interface ThreadItem {
    data class MessageItem(
        val message: Message,
    ) : ThreadItem

    /**
     * Marks a transition between two sessions in the thread stream.
     *
     * Invariant: [workspaceCwd] is non-null iff [reason] is
     * [BoundaryReason.WorkspaceChange]. For [BoundaryReason.Clear] and
     * [BoundaryReason.IdleEvict] callers must observe `null`. Documented
     * here and asserted in tests; not enforced at construction.
     */
    data class SessionBoundary(
        val previousSessionId: String,
        val newSessionId: String,
        val reason: BoundaryReason,
        val occurredAt: Instant,
        val workspaceCwd: String? = null,
    ) : ThreadItem

    /**
     * A claude message the interactive daemon's stream-json parser could not understand (#608). The
     * daemon forwards genuinely-unknown kinds as their own wire frame rather than dropping them, so a
     * gap in our own parser leaves a visible trace in the thread instead of a silent hole.
     *
     * **The values here are the most untrusted strings the thread holds** — unbounded, model-adjacent
     * JSON. Consumers must render them inert (plain text, never markdown), must not persist them, and
     * must not log them; see `UnrecognizedMessageRow`.
     *
     * @param id A **client-owned** stable identity, not a wire field: the frame carries neither a
     *   message id nor a `turn_id`. Invariant: unique within a thread. Duplicate values crash the
     *   thread's `LazyColumn`, which keys on it, so uniqueness is a producer obligation — documented
     *   here and asserted in tests, not enforced at construction (as [SessionBoundary]'s
     *   [workspaceCwd][SessionBoundary.workspaceCwd] invariant). Stamped by #609.
     * @param site Where the parser met the message. A closed set, decoded from the wire string by #609.
     * @param messageType The offending message or content-block type. **Empty when [site] is
     *   [UnrecognizedSite.Undecodable]** — nothing decoded, so no type was ever read. Not nullable: the
     *   wire field is present-and-empty, and modelling it as `""` keeps the decode arm total.
     * @param raw The offending JSON verbatim, capped daemon-side at 16 KiB. A [String], **not** nested
     *   JSON — a truncated blob is no longer valid JSON. The daemon scrubs invalid UTF-8 after cutting,
     *   so it arrives well-formed even when the cut landed mid-rune. Never parse, trim, or reformat it.
     * @param truncated Whether the daemon cut [raw] to fit the cap.
     * @param occurredAt Arrival instant, stamped by #609 — the wire carries **no** timestamp. Load-bearing
     *   beyond list ordering: `toChannelInfoUiModel` reads the first row's timestamp for the
     *   channel-info "created" label, so an unrecognized row landing first in a thread supplies it.
     */
    data class UnrecognizedMessage(
        val id: String,
        val site: UnrecognizedSite,
        val messageType: String,
        val raw: String,
        val truncated: Boolean,
        val occurredAt: Instant,
    ) : ThreadItem
}

enum class BoundaryReason { Clear, IdleEvict, WorkspaceChange }

/**
 * Where the daemon's stream-json parser met a message it could not understand (#608). Closed at the
 * four documented wire values, which is what lets the UI's label lookup stay exhaustive: a future fifth
 * site is a compile error rather than a blank slot.
 */
enum class UnrecognizedSite { LineType, AssistantBlock, UserBlock, Undecodable }

/**
 * One message waiting in a conversation's queued backlog while claude is busy (#460). The element type
 * of [ConversationRepository.observeQueue], co-located with the contract it serves (like [ThreadItem]).
 *
 * [id] is the daemon's per-conversation `queued_msg_id` counter (a wire `uint64`, so [Long]); it is a
 * monotonic ordinal, not a secret. [timestamp] is enqueue time.
 */
data class QueuedMessage(
    val id: Long,
    val text: String,
    val timestamp: Instant,
)

/**
 * One backward step of a history walk (#623) — the return of [ConversationRepository.requestHistory].
 * The element type is co-located with the contract it serves, like [ThreadItem] / [QueuedMessage].
 *
 * @param entries This page's entries in the wire's **newest-first** order, preserved verbatim; the
 *   client never re-sorts. An empty list is a normal page, not an error and not a termination signal.
 * @param cursor The position to ask with next, opaque — store it and hand it back unexamined. Empty
 *   whenever [atStart] is true, so the two are never both meaningful. Not a secret and not a
 *   capability: it is unsigned by design and carries only the conversation id the caller already
 *   knows. Authorization is pairing, enforced at the Noise handshake.
 * @param atStart The start of the log was reached while filling this page — **the only termination
 *   signal there is**. A walk stops on this and never on an empty [entries] list: a page that fills
 *   exactly at the log's first entry reports `false` with a usable [cursor], and the call after it
 *   returns no entries with `true`. A **short** page says nothing about the end of the log either —
 *   the daemon narrows a page to fit its frame size cap without touching this flag.
 */
data class HistoryPage(
    val entries: List<HistoryEntry>,
    val cursor: String,
    val atStart: Boolean,
)

/**
 * One stored frame in a conversation's history log (#623) — a wire type, its payload, a timestamp and
 * a durable id, which is what makes a loaded page renderable by re-reducing it **oldest-first**
 * through the same timeline reducer the live stream already runs.
 *
 * **[type] and [payload] are replayed content and are exactly as untrusted as the live lane's** —
 * operator-authored for a stored `send_message`, `claude`-authored for a stored assistant frame — so
 * a consumer applies the same sanitisation it applies live: render as inert text, never as markup, a
 * URL, a filename, a cache key or a log line. This is the [ThreadItem.UnrecognizedMessage] posture,
 * and it holds for **every** entry, not only the unrecognized ones. Nothing in the data layer logs
 * either field.
 *
 * @param id The **durable, per-conversation** log id: monotonic within one conversation and stable
 *   across daemon restarts. **Never join it to a replay `event_id`** ([de.pyryco.mobile.data.network.Envelope.eventId]),
 *   which is the in-memory ring's per-process id. They are different sequences that both look like
 *   small integers, and some live frames carry no `event_id` at all.
 * @param type The wire type the stored frame carried. **Nothing re-validates it**, so a consumer must
 *   tolerate a type it does not recognise rather than treating one as a protocol violation — the same
 *   forward-compatibility rule the wire applies to unknown fields.
 * @param payload The stored frame's body, verbatim and still undecoded — a [JsonElement] rather than
 *   a `String` so a consumer feeds it straight back into the same `MobileJson.decodeFromJsonElement`
 *   arms the live lane uses, with no second parse and no second failure surface.
 * @param timestamp When the entry was appended. Together with [type] it is the join key against the
 *   live lane: an entry in a page and its twin on the live stream carry the same pair, which is how a
 *   client meets the two with no gap and no duplicate.
 */
data class HistoryEntry(
    val id: Long,
    val type: String,
    val payload: JsonElement,
    val timestamp: Instant,
)

/**
 * Whether a conversation's remote claude is stuck retrying an API error, and how far into the retry
 * run it is (#593, pyrycode#1074). The element type of [ConversationRepository.observeApiRetry],
 * co-located with the contract it serves (like [ThreadItem] / [QueuedMessage]).
 *
 * A closed sealed type carrying two [Int]s and **no [String]**: the routing `conversation_id` stays a
 * map key in the repository projection and never reaches this value, so no daemon-supplied text —
 * banner, screen scrape, or otherwise — can structurally reach the UI through this arm.
 *
 * The `data` modifiers are load-bearing, not cosmetic: structural equality is what makes the
 * repository's `distinctUntilChanged` projection behave. A climbed counter is a *different* [Attempt]
 * value and re-emits, while an unrelated conversation's frame leaves this value equal and does not.
 */
sealed interface ApiRetryStatus {
    /** No retry in flight — the default, and the state a falling edge returns to. */
    data object NotRetrying : ApiRetryStatus

    /**
     * Retrying, but the daemon could not parse claude's on-screen `attempt N/M` counter (the wire's
     * `{current: 0, total: 0}`). A legitimate retry state, not an error and not a decode failure.
     */
    data object AttemptUnknown : ApiRetryStatus

    /** Retrying at attempt [current] of [total], carried verbatim from the wire (no clamping — #594 bounds display). */
    data class Attempt(
        val current: Int,
        val total: Int,
    ) : ApiRetryStatus
}
