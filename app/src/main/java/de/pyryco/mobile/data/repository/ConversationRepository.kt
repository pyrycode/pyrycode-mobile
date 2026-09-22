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
     * Emits the usage-limit reading claude last reported for [conversationId], or **`null` when there
     * is none to read** (#802). `null` until the wire says otherwise; a [UsageLimitReading] once a
     * non-benign frame lands; back to `null` on the benign clearing edge or once the reading's
     * [UsageLimitReading.resetsAt] has passed. Cold flow; re-emits on every change. The thread layer
     * observes this to say why a waiting turn is waiting (the render sibling of this split).
     *
     * **`null` covers three upstream facts a consumer does not have to tell apart** — nothing has
     * arrived for this conversation, a benign frame cleared it, or the reported window has passed. A
     * *present but degenerate* reading (`status = ""`) is a real reading the daemon emitted and is
     * **not** collapsed to `null`, so the nullable return draws the only distinction a consumer needs.
     *
     * **The expiry is already applied here and must not be re-derived.** [UsageLimitReading.resetsAt]
     * is readable on the value, so a consumer could compare it again and get the rule wrong (`0` is
     * "claude reported no reset", not the epoch); this seam is the single place that rule lives. See
     * the [RemoteConversationRepository] override for the comparison and for why no timer fires at the
     * deadline.
     *
     * Default `flowOf(null)` — implementations without an interactive wire (the fake, inline test
     * doubles) inherit "nothing reported" and need no override, the same cascade-avoidance as
     * [observeStall] / [observeQueue] / [observeApiRetry] / [observeCompacting].
     */
    fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = flowOf(null)

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
     * **The backlog entry leaves only on the next snapshot; the sender's own echo leaves here (#781).**
     * The daemon owns the backlog, so the queued row is still removed by a subsequent `queue_state` on
     * [observeQueue] (#460) and never optimistically. The *thread* echo is different: the daemon never
     * authored it — the sender posted it locally after its `send_message` ack, because interactive mode
     * streams no user-message event back — so leaving it behind after a confirmed drop shows a message
     * claude was never given. On a successful drop an implementation removes the one thread row it
     * minted for the dropped item, correlating on [QueuedMessage.messageId] and **only** against ids it
     * minted itself; an item carrying `""`, or one another device queued, correlates with nothing and
     * its drop touches no thread row. Text never matches. A failed drop removes nothing, so there is
     * still nothing to roll back.
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

    /**
     * The run configuration of the session bound to [conversationId] — the settings **read** half (#590),
     * the counterpart of [setSessionSettings]'s write. Emits the latest [SessionSettings] this context
     * has read, or `null` while none is available.
     *
     * **`null` is "unavailable", never a fallback.** It covers no live connection, a connection without
     * the `interactive` capability, a failed or malformed read, and the window before the first reply
     * lands. A consumer must render it as *unknown* — it is never device defaults from `AppPreferences`
     * and never another conversation's values.
     *
     * **A fresh read is issued on four triggers**: subscription (thread entry), the owning host's
     * reconnect (a fresh connection-scoped repository re-subscribes underneath the facade), that
     * conversation's `session_transition`, and a caller's [refreshSessionSettings] — which is how a
     * settled settings write gets a fresh reading. Nothing else sends a frame, and the frame sent is
     * `request_session_settings` alone: no claude child starts, no model turn begins, no
     * `set_session_settings` rides along, and nothing is written to device preferences.
     *
     * **A superseded reply cannot overwrite the current reading.** A newer trigger cancels the in-flight
     * read before starting the next; a late or duplicate reply correlates with a request that is no
     * longer pending and is dropped; and a reply carries no conversation identity of its own, so the
     * reading is routed strictly by the id the caller asked with and cannot cross-route. A reply from
     * another host cannot arrive at all — that connection has its own repository.
     *
     * Cold and per-collector, like [observeMessages]: each subscription drives its own read.
     *
     * Default emits `null` forever — implementations without a settings wire (the inline test doubles)
     * inherit it, the same cascade-avoidance as [delete] / [requestScreenSnapshot] / [requestHistory].
     */
    fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = flowOf(null)

    /**
     * Emits the model menu the daemon published for [conversationId] (#791) — the identifiers, labels,
     * per-row effort levels and auto-mode support this server actually offers, so the operator is never
     * choosing from a list the daemon will refuse. Cold flow; re-emits on every change.
     *
     * **`null` is "unavailable", and unavailable is a normal, permanent resting state.** It covers no
     * live connection, a connection without the `interactive` capability, a conversation this
     * connection heard no frame for, and the window before the first frame lands. It is **not** an
     * error and **not** a spinner: absence of a frame is the wire's only "no list" signal, so a
     * consumer renders *unknown* and waits for nothing in particular. It is never the `Model` / `Effort`
     * device enums and never another conversation's rows.
     *
     * **Issues no request — this rides the frames the daemon sends unasked**, once per claude child
     * spawn on the live lane and as a per-conversation burst on every (re)connect. Asking for a menu
     * the burst did not cover is #792.
     *
     * **Each frame replaces that conversation's menu wholesale**, routed by the frame's own
     * conversation id; it is snapshot-shaped full state rather than a delta, so re-applying it on every
     * connect is safe by construction and nothing merges or appends.
     *
     * **The menu is per host.** Each connection has its own repository, so a host switch drops the
     * previous machine's vocabulary back to unavailable rather than leaving it standing — the
     * published vocabulary varies by machine and account, not by conversation.
     *
     * Default emits `null` forever — implementations without a model-menu wire (the inline test
     * doubles) inherit it, the same cascade-avoidance as [observeSessionSettings] / [delete] /
     * [requestHistory].
     */
    fun observeModelMenu(conversationId: String): Flow<ModelMenu?> = flowOf(null)

    /**
     * Ask for a fresh [observeSessionSettings] reading of [conversationId] (#590) — the caller-driven
     * fourth trigger, for the moment a settings write has settled and the retained reading is known to
     * be stale. The new value arrives on the flow the caller already collects; there is no second read
     * method returning it directly.
     *
     * **Fire-and-forget, non-suspending and non-throwing.** An invalidation with no live connection is a
     * no-op rather than a failure: the caller has nothing to recover, and the next connection re-reads
     * on subscription anyway. Sends no frame itself — a collector does, if one is listening.
     *
     * Default is a no-op, so no test double needs to override it.
     */
    fun refreshSessionSettings(conversationId: String) = Unit
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
 *
 * @param messageId The `message_id` the *sending client* minted for this message, relayed verbatim by
 *   the daemon (pyrycode#2092, #781). It lets a client recognise a queued item as one of its own sends
 *   — in interactive mode the daemon streams no user-message event back, so a client's optimistic echo
 *   is its only record of its own message, and without a shared key dropping the queued item leaves
 *   that echo reading as a message claude received.
 *
 *   **Compared for equality only. Never render it, never key a list on it, never log it.** It is
 *   client-chosen and unique **nowhere**: two items may legally carry the same value, so keying a
 *   `LazyColumn` on it crashes the thread on duplicate keys (the hazard
 *   [ThreadItem.UnrecognizedMessage.id] documents for its own id). It addresses nothing on the wire —
 *   [ConversationRepository.dropQueuedMessage] still resolves `conversation_id` + [id].
 *
 *   `""` is a legal value meaning "this item correlates with nothing", and it is the default so a
 *   caller that has no correlation to express says so by omission.
 */
data class QueuedMessage(
    val id: Long,
    val text: String,
    val timestamp: Instant,
    val messageId: String = "",
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
 * The run configuration of one session (#590) — the return of [ConversationRepository.observeSessionSettings].
 * The element type is co-located with the contract it serves, like [ThreadItem] / [QueuedMessage] /
 * [HistoryPage].
 *
 * **Every field is retained exactly as the daemon reported it.** Nothing here is defaulted,
 * normalised, trimmed or mapped onto a client vocabulary, because each zero value carries meaning of
 * its own. The `data` modifier is load-bearing rather than cosmetic: structural equality is what makes
 * a consumer's `distinctUntilChanged` behave, the [ApiRetryStatus] rule.
 *
 * @param sessionId The session a [ConversationRepository.setSessionSettings] must address. **`""` means
 *   the daemon has no session to address** — a consumer treats the settings as read-only rather than
 *   writing with an empty id, which the daemon rejects.
 * @param model The stored model **override**, not what claude announced for the turn. An arbitrary wire
 *   string: never mapped through `Model` (three entries; the wire carries anything), and `""` means "no
 *   override, inherited default" rather than an absent value.
 * @param effort The **saved** per-session reasoning-effort choice, `""` meaning inherited default. It
 *   stays the saved choice and is never replaced by [effectiveEffort]'s applied reading.
 * @param effectiveEffort Claude's **applied** effort, independent of [effort] — three states that must
 *   stay apart; see [EffectiveEffort].
 * @param permissionMode The last permission posture claude confirmed for the **exact current child** —
 *   one of the write half's five modes, or `bypassPermissions`, which this read accepts even though the
 *   write half refuses that spelling. **`""` means no confirmation is available**, not that no session
 *   resolved and *not* Manual approval: it accompanies a live child that has not confirmed yet and a
 *   dormant session with no child. Stored settings and launch argv are never fallback proof. An open
 *   `String`, not an enum, because `""` is a real reported value a closed set could not carry honestly.
 * @param yolo Bypass-permissions, derived from the same confirmation: `true` only for a confirmed
 *   `bypassPermissions`. **`false` alone is not proof that approvals are enforced** — it also
 *   accompanies an unavailable confirmation, so it is read beside [permissionMode], never alone.
 * @param usedTokens Context tokens consumed by the latest turn, read under the addressed session's own
 *   working directory. `0` against a non-zero [windowTokens] is a genuinely fresh session; a dormant
 *   session reports `0` here **and** in [windowTokens], the two read as a pair rather than separately.
 * @param windowTokens Context-window size. **`0` means the usage reader is unwired**, not an empty
 *   window — do not render a percentage from it.
 */
data class SessionSettings(
    val sessionId: String,
    val model: String,
    val effort: String,
    val effectiveEffort: EffectiveEffort,
    val permissionMode: String,
    val yolo: Boolean,
    val usedTokens: Long,
    val windowTokens: Long,
)

/**
 * The models a server published for one conversation (#791) — the return of
 * [ConversationRepository.observeModelMenu]. The element type is co-located with the contract it
 * serves, like [SessionSettings] / [ThreadItem] / [QueuedMessage].
 *
 * **A type rather than a bare `List<ModelMenuRow>`, for two reasons.** [droppedModels] is frame-level
 * state that must survive beside the rows, and a *present but empty* menu and an *absent* one are
 * different readings — a list alone could express the first as `emptyList()` and the second only as
 * `null`, which is exactly the pun [ConversationRepository.observeModelMenu] exists to avoid.
 *
 * `data` is load-bearing rather than cosmetic: structural equality is what makes a consumer's
 * `distinctUntilChanged` behave, the [ApiRetryStatus] rule. A value-identical re-snapshot on every
 * reconnect therefore costs a consumer nothing.
 *
 * @param rows The published models in **claude's own order**, which is the display order. Empty is a
 *   positive statement that claude offered nothing, not an absence — an absent menu is `null` at the
 *   flow instead.
 * @param droppedModels How many entries the producer cut that [rows] does **not** carry; `0` when
 *   nothing was dropped. Read as reported and **never recomputed** from `rows.size`, which is what lets
 *   a consumer say "10 of 47" rather than presenting a shortened menu as complete. The producer's entry
 *   cap is daemon-side and not a wire constant, so never hardcode one or derive a cap from `rows.size`;
 *   a non-zero count beside an empty [rows] is legal.
 */
data class ModelMenu(
    val rows: List<ModelMenuRow>,
    val droppedModels: Int,
)

/**
 * One published model in a [ModelMenu] (#791).
 *
 * Named *row* rather than *option* deliberately: [de.pyryco.mobile.data.model.ModalOption] already
 * exists one letter away, and a `ModelOption` beside it would be a homograph trap at every call site.
 *
 * **Every field is retained exactly as the daemon reported it** — no trim, no case fold, no alias
 * rewrite, no normalisation, and never mapped through `Model` or `Effort`, whose entries are this
 * device's guesses rather than this server's answer.
 *
 * **SECURITY — these strings crossed the subprocess trust boundary.** [resolvedModel], [value],
 * [displayName] and every element of [effortLevels] are **claude-authored** text. The daemon bounds
 * them but **does not sanitize them**: no control character and no terminal escape sequence is stripped
 * anywhere on this path, so they arrive untrusted and the render boundary that owes the sanitization is
 * the client's. They are safe to render as **inert text** and must never be fed to a WebView, an HTML
 * sink, an attribute, a URL, a filename, a cache key or a log line. Nothing keys off them either — a
 * retained menu is keyed by conversation id and by nothing derived from row text.
 *
 * @param resolvedModel The concrete identifier [value] resolves to **right now**, published before the
 *   first turn so a consumer can show what a family currently means instead of inferring it from an
 *   announcement afterwards.
 * @param value The argument that selects this model, sendable back on
 *   [ConversationRepository.setSessionSettings] (still re-validated there rather than trusted).
 *   **Never parse it**: it is an alias (`sonnet`), a bracketed variant (`opus[1m]`) or `default`, so no
 *   family may be derived by splitting it and it must never be presented as a version.
 * @param displayName Claude's own human label for the row, and the intended join key against a
 *   `model_announced` event.
 * @param effortLevels The reasoning-effort levels **this row** supports, in wire order. Empty is a
 *   positive statement that this model exposes no effort control — never a cue to substitute the
 *   `Effort` entries.
 * @param supportsAutoMode Whether claude accepts `auto` permission mode for this model; claude refuses
 *   per model, so a consumer greys the option out when `false`.
 * @param truncatedFields The names of **this row's** cut fields, in producer order, or `null` when
 *   nothing was cut. Each row reports its own; there is no hoisted or flattened list, and this is never
 *   recomputed from what survived.
 */
data class ModelMenuRow(
    val resolvedModel: String,
    val value: String,
    val displayName: String,
    val effortLevels: List<String>,
    val supportsAutoMode: Boolean,
    val truncatedFields: List<String>?,
)

/**
 * Claude's applied reasoning effort as reported beside the saved [SessionSettings.effort] (#590) — a
 * closed three-state reading, because the wire's `effective_effort` key has three meanings a consumer
 * must not collapse.
 *
 * A sealed family rather than a `String?` for the reason the wire itself gives: an omitted key and an
 * explicit `null` say different things, and `MobileJson`'s `explicitNulls = false` would decode both to
 * the same Kotlin `null`. Modelled like [ApiRetryStatus] — two data objects and one value-carrying
 * member, so a consumer's `when` stays exhaustive and a third meaning cannot appear as a silent blank.
 *
 * This is **never** the value to write back: it reports what claude applied, while
 * [ConversationRepository.setSessionSettings] writes the saved choice.
 */
sealed interface EffectiveEffort {
    /** The key was **omitted** — the applied value is unavailable or this producer does not report one. */
    data object Unavailable : EffectiveEffort

    /** The key was an **explicit `null`** — claude reported no effort parameter for this session. */
    data object NotReported : EffectiveEffort

    /**
     * A confirmed applied level, carried **verbatim**: `""` (claude's own default) and a level this
     * build does not recognise are both legal values, so nothing validates or maps [value].
     */
    data class Applied(
        val value: String,
    ) : EffectiveEffort
}

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

/**
 * The usage-limit reading claude last reported for one conversation (#802, pyrycode#1405/#1410) — the
 * element type of [ConversationRepository.observeUsageLimit], co-located with the contract it serves
 * (like [ApiRetryStatus] / [ThreadItem] / [QueuedMessage]).
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `rate_limited`, which is where the field semantics
 * live; they are cited here, not restated. **A frame is not proof that anything was blocked** — the one
 * measured non-benign status was seen on an account whose turns all ran normally — so this type is
 * named for the *reading* rather than for the frame, deliberately, and a consumer's copy must not
 * claim the user is rate limited. Desktop made the same naming call for the same reason
 * (`usageLimitStore`, not `rateLimitStore`).
 *
 * A **`data class` rather than a sealed family**, unlike [ApiRetryStatus]: that one is sealed because
 * its wire shape collapses into three meanings a consumer's `when` must cover exhaustively, whereas
 * this payload has one meaning with five fields. `data` is load-bearing rather than cosmetic for
 * [ModelMenu]'s reason — structural equality is what makes the repository projection's
 * `distinctUntilChanged` behave, so a value-identical re-report costs a consumer nothing.
 *
 * **The routing `conversation_id` is deliberately not a field.** It stays a map key in the repository
 * projection and never reaches this value — the rule [ApiRetryStatus] states, and here it is the
 * stronger one: a render consumer holds and draws from this object, so a daemon-asserted id inside it
 * would be one copy-paste away from a sink.
 *
 * **SECURITY.** [status] and [limitType] are claude-authored strings that crossed the subprocess trust
 * boundary; the daemon bounds them at construction and does **not** sanitize them, so they stay
 * untrusted, model-influenced text here. They are held **verbatim** — never normalised, lowercased,
 * trimmed, allow-listed or shape-checked — and are usable only as lookup keys for **client-owned
 * copy**: never rendered verbatim, never an authorization signal, never a filename, a cache key or a
 * lookup path. Nothing on this path is ever logged. **No behaviour may branch on any field of this
 * type**: the frame is a report, never a control input.
 *
 * @param status Claude's own status for the usage-limit window, verbatim. An **open string with a
 *   mostly unmeasured value set**. Exactly one comparison against it is legitimate — the benign value,
 *   which distinguishes a clearing edge from a warning, and which the decode boundary has already made
 *   before a reading reaches here. Every other value is an **opaque label** to render, never a case to
 *   branch on; treating it as a closed set is a bug waiting for claude's next release.
 * @param limitType Which limit the report concerns, verbatim. Also an open string — two observed values
 *   do not earn an enum. **Never pair a clear to it**: the clearing frame names a *different*
 *   `limit_type` than the warning it clears, so a consumer matching on it never matches. The clear is
 *   paired to the conversation, which the repository does by construction.
 * @param resetsAt When claude says the limit lifts, in **unix seconds** — claude's number, not the
 *   device's clock, unvalidated in both directions. **`0` means claude reported none, emphatically not
 *   the epoch.** Negative and absurd values are representable and none is rejected. It is **never a
 *   scheduling input**: a delay computed from it can be negative or past a timer's clamp, and both fire
 *   immediately rather than never. [ConversationRepository.observeUsageLimit] has already applied the
 *   expiry; a consumer formats this defensively and does not re-derive the rule.
 * @param utilization How much of the window claude says is **spent** — claude's own number, and **not a
 *   bounded fraction**: not clamped, rounded, rescaled or range-checked anywhere on the path, so a
 *   consumer must not assume `0..1` and must range-check before scaling a gauge by it. **`null` and
 *   `0.0` are different facts**, and absence is the *common* case: reading a missing reading as zero
 *   renders a fresh window as an exhausted one. Degrade instead — say a limit was reported without
 *   claiming how much of it is spent.
 * @param truncatedFields The fields the daemon cut to fit its cap, named by their wire keys (`status`,
 *   `limit_type`); **`null` when nothing was cut**, which is distinct from an empty list. Load-bearing
 *   rather than decoration: a consumer that ignores it presents claude's cut text as complete.
 *   `utilization` is never a member — a number cannot be cut.
 */
data class UsageLimitReading(
    val status: String,
    val limitType: String,
    val resetsAt: Long,
    val utilization: Double?,
    val truncatedFields: List<String>?,
)
