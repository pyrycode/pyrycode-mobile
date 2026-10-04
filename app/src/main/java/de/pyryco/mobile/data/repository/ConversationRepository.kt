package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
     * How many rows each conversation's thread holds on this repository (#1361), keyed by conversation id.
     * A row appended raises its count; growth of an existing row does not. Defaulted empty for a repository
     * that has no live thread store.
     */
    fun observeThreadRowCounts(): Flow<Map<String, Int>> = emptyFlow()

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
     * Latest session-error wire code for this conversation, or null when none is held. Unknown codes
     * remain verbatim; daemon prose is discarded. Supplies the current value on subscription. Clears
     * on send entry, decoded non-idle turn state, and connection loss; never retries a message.
     * Implementations without this feature inherit no error.
     */
    fun observeSessionError(conversationId: String): Flow<String?> = flowOf(null)

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
     * Emits [conversationId]'s current turn phase (#1313): the latest `turn_state`, back to
     * [LiveSessionEvent.TurnState.Phase.Idle] on `turn_end`. Held per conversation for the connection, so a
     * collector that subscribes mid-turn reads the running phase at once, and a new connection starts every
     * conversation idle. Cold flow; re-emits on every change.
     *
     * Default `flowOf(Idle)` — implementations without an interactive wire (the fake, inline test doubles)
     * inherit "no turn running" and need no override, the same cascade-avoidance as [observeCompacting].
     */
    fun observeTurnPhase(conversationId: String): Flow<LiveSessionEvent.TurnState.Phase> = flowOf(LiveSessionEvent.TurnState.Phase.Idle)

    /**
     * Emits which phase of a Reset [conversationId] is in, and what became of its handoff note (#871), or
     * **`null` when no reset is running**. `null` until the wire says otherwise; a [ResetStatus] on each
     * rising edge; back to `null` on the falling edge or on the conversation's session transition. Cold
     * flow; re-emits on every change. The thread layer observes this to say whether the reset is writing
     * a handoff note or restarting.
     *
     * **One reset is one reading that changes, never two readings.** The wire sends two rising edges
     * before its single falling edge — `wrapping_up` then `restarting` — so the second replaces the
     * first; a consumer that treats a new non-null value as a new reset draws two where one happened.
     * The falling edge always arrives (pyrycode `docs/protocol-mobile.md` § `resetting`), so a consumer
     * owes no timeout of its own.
     *
     * Default `flowOf(null)` — implementations without an interactive wire (the fake, inline test
     * doubles) inherit "no reset" and need no override, the same cascade-avoidance as
     * [observeCompacting] / [observeThinkingProgress].
     */
    fun observeResetting(conversationId: String): Flow<ResetStatus?> = flowOf(null)

    /**
     * Emits the ids of every conversation on this connection that is busy outside a running turn (#1452):
     * stalled, retrying the API, compacting or resetting. Each fact rises and clears on exactly the edges of
     * [observeStall], [observeApiRetry], [observeCompacting] and [observeResetting]. Cold flow; re-emits on
     * every change. The host's list observes this to blink a busy chat's status dot, as desktop's `isWorking`.
     *
     * Default `flowOf(emptySet())` — implementations without an interactive wire (the fake, inline test
     * doubles) inherit "nothing busy" and need no override, the same cascade-avoidance as [observeStall].
     */
    fun observeBusyConversations(): Flow<Set<String>> = flowOf(emptySet())

    /**
     * Emits the model claude last announced for [conversationId]'s turn (#890), or **`null` until an
     * announcement arrives**. Cold flow; re-emits on every change. Each `model_announced` frame replaces the
     * reading, because claude announces on every turn and a `/model` turn still names the old model: a
     * consumer that latched the first one would show a stale value. Cleared by the conversation's session
     * transition, and by a reconnect or host switch through the fresh connection-scoped repository.
     *
     * **Not the saved override.** [SessionSettings.model] is the per-session override, ordinarily `""`, while
     * this is what claude says it runs. Keep the two separate values; nothing here writes the other.
     *
     * Default `flowOf(null)` — implementations without an interactive wire (the fake, inline test doubles)
     * inherit "nothing announced" and need no override.
     */
    fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = flowOf(null)

    /**
     * Emits [conversationId]'s live refusal frames and session transitions (#1360), in wire order, as they
     * arrive. Hot, nothing replayed: history pages, the cache and a reopened thread never emit here, which is
     * what lets a consumer tell a refusal that just happened from a restored row.
     *
     * Default `emptyFlow()`, the same cascade-avoidance as [observeAnnouncedModel].
     */
    fun observeLiveRefusalEvents(conversationId: String): Flow<LiveRefusalEvent> = emptyFlow()

    /**
     * Emits the facts claude last reported about its own run for [conversationId] (#890): its build and the
     * permission posture it claims, or **`null` until a report arrives**. Cold flow; re-emits on every
     * change. Each `session_facts` frame replaces the reading. Cleared exactly as [observeAnnouncedModel] is.
     *
     * [SessionFacts.permissionMode] is **claude's claim**, never the permission reading: the confirmed mode
     * stays [SessionSettings.permissionMode], and nothing here writes it.
     *
     * Default `flowOf(null)`, the same cascade-avoidance as [observeAnnouncedModel].
     */
    fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = flowOf(null)

    /**
     * Emits the context-window reading Claude last reported for [conversationId] (#945), or **`null` while
     * there is none**, which reads as "unavailable", never as zero. Cold flow; re-emits on every change. Each
     * `context_usage` frame replaces the reading: the daemon's push after a turn, or the answer to
     * [requestContextUsage]. The conversation's session transition clears it. Observing sends nothing; the open
     * thread asks through [requestContextUsage] (#1410).
     *
     * **Preferred over [SessionSettings.usedTokens] / [SessionSettings.windowTokens].** Those are
     * transcript-derived; the thread shows this reading's token totals while one exists and falls back to the
     * settings pair only when it is absent (#1411).
     *
     * Default `flowOf(null)`, the same cascade-avoidance as [observeSessionFacts].
     */
    fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = flowOf(null)

    /**
     * Emits [conversationId]'s MCP server reading on this connection (#1343): the report Claude last gave, `null`
     * before any, and the five request flags. Cold flow; re-emits on every change. Each `mcp_status` frame,
     * pushed or answering one of the three requests below, replaces the report and clears all five flags. The
     * reading is per connection: a reconnect starts from [McpStatus] with nothing in it.
     *
     * Default `flowOf(McpStatus())`: a repository with no MCP wire never holds a report or a flag.
     */
    fun observeMcpStatus(conversationId: String): Flow<McpStatus> = flowOf(McpStatus())

    /**
     * Ask once for [conversationId]'s current MCP status (#1343). Fire-and-forget: the answer is a report on
     * [observeMcpStatus], and a refusal as `mcp_status.unavailable` sets [McpStatus.unavailable]. When nothing
     * can be sent, nothing happens. Never retries, never throws.
     *
     * Default no-op, the [refreshSessionSettings] shape: "nothing could be sent" is this contract's own outcome.
     */
    fun requestMcpStatus(conversationId: String) {}

    /**
     * Ask once for [serverName] to be reconnected on [conversationId]'s live child (#1343). Sending sets
     * [McpStatus.reconnecting]; the next report ends it, and any correlated refusal ends it as
     * [McpStatus.reconnectRefused]. When nothing can be sent, nothing is set. Never retries, never throws.
     * [serverName] is claude-authored and only put on the wire.
     *
     * Default no-op, as [requestMcpStatus].
     */
    fun reconnectMcpServer(
        conversationId: String,
        serverName: String,
    ) {}

    /**
     * Ask once for [serverName] on [conversationId]'s live child to be turned on or off (#1343). Sending sets
     * [McpStatus.toggling]; the next report ends it, and any correlated refusal ends it as
     * [McpStatus.toggleRefused]. When nothing can be sent, nothing is set. Never retries, never throws.
     *
     * Default no-op, as [requestMcpStatus].
     */
    fun toggleMcpServer(
        conversationId: String,
        serverName: String,
        enabled: Boolean,
    ) {}

    /**
     * Clear [McpStatus.reconnecting] only (#1343), called by the surface that started the wait when it goes away,
     * so a daemon that never answers cannot leave the control stuck. Default no-op: no wait is ever held.
     */
    fun endMcpReconnectWait(conversationId: String) {}

    /** Clear [McpStatus.toggling] only (#1343), as [endMcpReconnectWait]. Default no-op. */
    fun endMcpToggleWait(conversationId: String) {}

    /**
     * Emits the files the daemon has offered in [conversationId] on this connection (#898), in arrival order
     * with one entry per attachment id, or an empty list until one arrives. Cold flow; re-emits when an offer
     * for this conversation lands. The offer is **live-only** on the wire (no replay, no list verb), so this
     * is the set of offers the connection happened to receive, never the set of files the conversation
     * holds, and it starts empty on every new connection. Pass [AttachmentOffer.attachmentId] back to fetch
     * the bytes.
     *
     * Default `flowOf(emptyList())` — implementations without a live wire (the fake, inline test doubles)
     * inherit "nothing offered" and need no override, the same cascade-avoidance as [observeCompacting].
     */
    fun observeAttachmentOffers(conversationId: String): Flow<List<AttachmentOffer>> = flowOf(emptyList())

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
     * Emits how far [conversationId]'s current reasoning has got (#801) — claude's own running token
     * estimate, and the **only** mid-turn proof of life the stream-json surface offers, since nothing
     * else crosses the wire during a long assistant turn. Cold flow; re-emits on every change.
     *
     * **`null` is "no reading", and it is never a statement that claude is not thinking.** It covers no
     * live connection, a connection without the `interactive` capability, a conversation no frame named,
     * the window before the first frame, and the state after a clear. **Absence proves nothing**, for two
     * measured reasons the wire contract owns: the PTY surface emits none of these at all, and the
     * producer's rate bound means a quiet window may only be one where the accumulated delta has not yet
     * crossed the threshold. Nothing may infer a stall from a gap here — [observeStall] is the separate
     * signal for that, untouched by this one in both directions.
     *
     * **The reading is not monotonic.** It restarts near zero at every inference-request boundary, which
     * happens repeatedly inside one turn, so a consumer must never clamp it with a running maximum and
     * never subtract two readings expecting a non-negative result. A reading that falls, repeats or
     * arrives as `0` is a real reading carried verbatim, not an absent one — `0` in particular is a
     * fresh restart and must not be read as "nothing to show".
     *
     * **It reports no turn.** The frame carries no turn id and opens or closes no turn; the turn's own
     * thinking state is [de.pyryco.mobile.data.network.LiveSessionEvent.TurnState]'s. Having no falling
     * edge of its own, the reading is cleared by *other* events — the conversation's turn end and its
     * session transition — and is never held across a reconnect, since the daemon re-asserts none on
     * connect and a held one would report the depth of a think that has since finished.
     *
     * Default `flowOf(null)` — implementations without an interactive wire (the fake, inline test
     * doubles) inherit "no reading" and need no override, the same cascade-avoidance as
     * [observeCompacting] / [observeApiRetry] / [observeModelMenu].
     */
    fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> = flowOf(null)

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

    /**
     * Create a named, promoted channel in [workspace] in one step (#956), rather than a discussion that is
     * promoted afterwards. Null [workspace] uses the daemon's default folder. Values are sent verbatim:
     * trimming the name is the caller's job, and the daemon re-validates both.
     *
     * Returns the daemon's confirmed conversation — its values, not the request's — which then appears as a
     * promoted row in [observeConversations]. A server `error`, a disconnected session or a malformed reply
     * throws and inserts nothing, as with [createDiscussion].
     *
     * Default throws — implementations without the verb (inline test doubles) inherit it, the same
     * cascade-avoidance as [setSystemPrompt].
     */
    suspend fun createChannel(
        name: String,
        workspace: String?,
    ): Conversation = error("createChannel is not implemented for this ConversationRepository")

    suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String? = null,
    ): Conversation

    suspend fun archive(conversationId: String)

    suspend fun unarchive(conversationId: String)

    /**
     * Set ([muted] `true`) or clear ([muted] `false`) [conversationId]'s mute-notifications flag (#1000),
     * one `set_conversation_muted` per call. On success [observeConversations] shows the confirmed
     * [Conversation.muted] value with no re-list.
     *
     * Throws [IllegalArgumentException] for an unknown conversation, like [archive], and
     * [de.pyryco.mobile.data.network.RelayErrorException] for any other refusal; neither changes the list.
     *
     * Default throws — implementations without the verb (inline test doubles) inherit it, the same
     * cascade-avoidance as [createChannel].
     */
    suspend fun setMuted(
        conversationId: String,
        muted: Boolean,
    ): Unit = error("setMuted is not implemented for this ConversationRepository")

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
     * [permissionMode] (#650) is the posture field beside [yolo]: never pass both, which the daemon
     * rejects and the request DTO refuses to build ([IllegalArgumentException]).
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
        permissionMode: String? = null,
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
     * [sendMessage] naming the uploaded attachments the message references (#830), each id once in the
     * caller's order, over the same connection and to the same conversation as the message. An empty
     * [attachments] sends exactly what the two-argument form sends. More than
     * [de.pyryco.mobile.data.network.MessageAttachmentIds.MAX] distinct ids throws
     * [IllegalArgumentException] before anything is sent. A daemon refusal such as `attachment.not_found`
     * fails as the two-argument send fails, and an `ack` adds the message to the thread as it does.
     *
     * Only each [MessageAttachment.attachmentId] goes on the wire. The name and MIME hints go only on the
     * thread row the `ack` adds (#983), one reference per distinct id in caller order.
     *
     * Default throws, like [setSessionSettings], so the inline test doubles need no override.
     */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
        attachments: List<MessageAttachment>,
    ): Message = error("sendMessage with attachments is not implemented for this ConversationRepository")

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
     * Set or clear the display name this host stores for the workspace at [path] (#663), one
     * `rename_workspace` per call. [path] is the workspace's exact `cwd`, matched as bytes; [label] is
     * sent verbatim, and `null` clears it. Trimming, and treating a blank or the folder's own name as a
     * clear, are the caller's concern.
     *
     * Returns only after the daemon's correlated reply has been applied: by then every row at [path] in
     * this repository, archived rows included, carries the stored label (or none). Rows at other paths
     * are unchanged.
     *
     * Throws [de.pyryco.mobile.data.network.RelayErrorException] carrying the daemon's `code` for a
     * refusal (`workspace.not_found`, or `protocol.malformed` for a blank or over-long label) and for a
     * reply that does not confirm [path]; [IllegalStateException] when the session is not connected or
     * tears down before the reply. No row changes on any failure.
     *
     * Default throws, like [createWorkspaceFolder]: only the relay repository implements it.
     */
    suspend fun renameWorkspace(
        path: String,
        label: String?,
    ): Unit = error("renameWorkspace is not implemented for this ConversationRepository")

    /**
     * Archive every active channel and discussion on this host whose `cwd` equals [path] byte for byte
     * (#663), through one [archive] per row. There is no workspace verb on the wire; this mirrors
     * desktop's fan-out. Archived rows and other paths are left alone, nothing is renamed or deleted,
     * and the stored workspace label stays.
     *
     * Targets are this repository's current rows at call time; a path with no active rows sends nothing.
     * Each row leaves the active list when its own archive is confirmed, and the call returns after all
     * of them. If any archive fails, the remaining rows are still attempted and the call then throws the
     * first failure, with the types [archive] documents. Confirmed rows stay archived, so calling again
     * archives only the rows still active.
     *
     * Default throws, like [renameWorkspace].
     */
    suspend fun archiveWorkspace(path: String): Unit = error("archiveWorkspace is not implemented for this ConversationRepository")

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
     * **Fire-and-forget: the daemon never replies (#859).** An implementation returns once the frame is
     * sent. The daemon owns the backlog, so the queued row is removed only by a subsequent `queue_state`
     * on [observeQueue] (#460) and never optimistically, and that snapshot is also the drop's only
     * confirmation. A request the daemon cannot apply (unknown, already delivered or in-flight) is
     * silent too.
     *
     * **The sender's own echo leaves with the item (#781).** The daemon never authored the thread echo —
     * the sender posted it locally after its `send_message` ack, because interactive mode streams no
     * user-message event back — so leaving it behind after a drop shows a message claude was never
     * given. When a `queue_state` for [conversationId] arrives without the dropped [queuedMessageId], an
     * implementation removes the one thread row it minted for that item, correlating on
     * [QueuedMessage.messageId] and **only** against ids it minted itself; an item carrying `""`, or one
     * another device queued, correlates with nothing and its drop touches no thread row. Text never
     * matches. Removal is keyed on the id this device asked to drop, never on the backlog shrinking, so
     * an item that drains normally keeps its echo. The one ambiguity — the head item draining just
     * before the dequeue lands, so the daemon ignores the dequeue and the next snapshot looks exactly
     * like a drop — resolves as a drop.
     *
     * Throws [IllegalStateException] when the session is not connected; the drop was never sent, so the
     * backlog and the echo stay unchanged and there is nothing to roll back.
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
     * The history position saved for [conversationId] (#1354), or `null` when none is: never received,
     * cleared, or unreadable. Read once when a thread opens, to resume its walk where the saved rows end.
     *
     * Default `null` — a repository with no cache always starts from the newest page.
     */
    suspend fun readHistoryPosition(conversationId: String): HistoryPosition? = null

    /**
     * Save [position] for [conversationId] beside its cached rows (#1354), or clear it with `null`. Called
     * when an ask settles; a failed ask never calls it. Never throws for a storage failure: losing a
     * position costs only a re-fetched page.
     *
     * Default does nothing.
     */
    suspend fun writeHistoryPosition(
        conversationId: String,
        position: HistoryPosition?,
    ) {}

    /**
     * Read this repository's host instructions and daemon-supplied reset text without a conversation.
     * Both strings are required and preserved verbatim; no interactive capability is needed.
     * Failures are explicit and content-free; caller cancellation still propagates.
     * The default failure keeps implementations without host settings source-compatible.
     */
    suspend fun requestHostSystemPrompt(): Result<HostSystemPromptReading> =
        Result.failure(UnsupportedOperationException("Host system prompt read is not supported"))

    /**
     * Durably store [systemPrompt] verbatim on this host and return the acknowledged current/default
     * pair. Empty clears; reset is an ordinary write of the returned default. Rejects values above
     * [SystemPromptLimit.MAX_BYTES] UTF-8 bytes before sending. No session is reset or restarted.
     */
    suspend fun setHostSystemPrompt(systemPrompt: String): Result<HostSystemPromptReading> =
        Result.failure(UnsupportedOperationException("Host system prompt write is not supported"))

    /**
     * Read the system prompt stored for [conversationId] (#823), one `request_system_prompt` per call.
     * Keyed by **conversation**, never by session: a conversation with nothing running reads normally,
     * and the read changes nothing on the daemon. The stored value keeps its three states apart (see
     * [SystemPromptReading]), so it can be written straight back through [setSystemPrompt].
     *
     * The daemon never answers with an error — an unhosted conversation reads exactly like a hosted one
     * that stores no prompt and runs no session. Throws [IllegalStateException] when the session is not
     * connected **or** did not negotiate `interactive` (the daemon ignores this verb there, so the remote
     * fails before sending rather than waiting forever), and the decode exception for a malformed reply.
     * A failure is scoped to this read alone.
     *
     * Default throws — implementations without the verb (inline test doubles) inherit it, the same
     * cascade-avoidance as [requestHistory].
     */
    suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading =
        error("requestSystemPrompt is not implemented for this ConversationRepository")

    /**
     * Upload a file's [bytes], [filename] and declared [mimeType] to [conversationId] on this repository's
     * host (#829), and report how it ended: [AttachmentUploadResult.Stored] with the minted id only when
     * the daemon answers `attachment_stored` for it, otherwise one [AttachmentUploadResult.Failed]. Files
     * over [AttachmentUploadLimit.MAX_BYTES] are refused before anything is sent. Uploads on one
     * connection run one at a time. Never throws except on cancellation.
     *
     * [onProgress] receives the count of chunks handed to the socket and the total (#1326), 1..N in order,
     * once per chunk and never after the upload has settled; a failed chunk is not reported. It runs on
     * the upload's coroutine and must not throw.
     *
     * Default throws, like [requestSystemPrompt].
     */
    suspend fun uploadAttachment(
        conversationId: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        onProgress: (sentChunks: Int, totalChunks: Int) -> Unit = { _, _ -> },
    ): AttachmentUploadResult = error("uploadAttachment is not implemented for this ConversationRepository")

    /**
     * Fetch [attachmentId] of [conversationId] over this repository's connection (#899): one
     * `request_attachment`, and the verified bytes in memory, or one [AttachmentRetrievalResult.Failed].
     * Connection-level and host-blind; screens call [retrieveAttachment], which keeps the file for its host.
     * Never throws except on cancellation.
     *
     * Default throws, like [requestSystemPrompt].
     */
    suspend fun fetchAttachment(
        conversationId: String,
        attachmentId: String,
    ): AttachmentFetchResult = error("fetchAttachment is not implemented for this ConversationRepository")

    /**
     * Read [path] live from [conversationId]'s workspace over this repository's connection (#1049): one
     * `read_workspace_file` per call, and the verified bytes in memory, or one [AttachmentRetrievalResult.Failed]
     * with the same meanings as [fetchAttachment]'s. Nothing is cached or kept: two calls send two requests.
     * [path] is sent as given; the daemon confines it. Never throws except on cancellation.
     *
     * Default throws, like [requestSystemPrompt].
     */
    suspend fun readWorkspaceFile(
        conversationId: String,
        path: String,
    ): AttachmentFetchResult = error("readWorkspaceFile is not implemented for this ConversationRepository")

    /**
     * The file [attachmentId] of [conversationId], kept in app-private storage for this repository's host
     * (#899). A file kept earlier is returned without sending anything; otherwise it is fetched once, however
     * many callers ask at the same time. The id may come from an offer or from an upload: the request is the
     * same. Never throws except on cancellation.
     *
     * Default throws, like [requestSystemPrompt]: only the host-bound [CachingConversationRepository] keeps files.
     */
    suspend fun retrieveAttachment(
        conversationId: String,
        attachmentId: String,
    ): AttachmentRetrievalResult = error("retrieveAttachment is not implemented for this ConversationRepository")

    /**
     * Store [systemPrompt] as [conversationId]'s system prompt (#823), one `set_system_prompt` per call,
     * returning after the daemon's ack. `null` clears it, `""` stores an explicitly empty prompt, and any
     * other string is stored **verbatim** — never trimmed or normalised. It takes effect at the
     * conversation's next session start; nothing here restarts or resets a running session.
     *
     * Throws [IllegalArgumentException] **before any frame is sent** when the value exceeds
     * [SystemPromptLimit.MAX_BYTES] (check [SystemPromptLimit.fits] first to tell that apart), and for an
     * unknown conversation (server `conversation.not_found`, as [rename]);
     * [de.pyryco.mobile.data.network.RelayErrorException] for any other server error;
     * [IllegalStateException] when the session is not connected.
     *
     * Default throws, like [requestSystemPrompt].
     */
    suspend fun setSystemPrompt(
        conversationId: String,
        systemPrompt: String?,
    ): Unit = error("setSystemPrompt is not implemented for this ConversationRepository")

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
     * Emits the slash-command menu the daemon published for [conversationId] (#882) — the commands this
     * conversation's session, in its working directory, will accept, for the composer to offer. Cold flow;
     * re-emits on every change.
     *
     * **`null` means no frame heard**, which is distinct from a present menu with no rows. It covers no live
     * connection, a connection without the `interactive` capability, a conversation this connection heard no
     * frame for, and the window before the first frame lands. The wire's delivery is best-effort, so a
     * consumer never blocks on it: it renders a usable UI without a menu and lets the next connect fill it.
     *
     * **Issues no request** — the frame has no inbound verb. It arrives once per claude child spawn on the
     * live lane and per conversation on every (re)connect. Each frame replaces that conversation's menu
     * wholesale, routed by the frame's own conversation id.
     *
     * **The menu is per host and per connection.** Each connection has its own repository, so a reconnect
     * or host switch starts empty and the connect-time snapshot fills it again.
     *
     * Default emits `null` forever — the cascade-avoidance [observeModelMenu] uses, so inline test doubles
     * need no override.
     */
    fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> = flowOf(null)

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

    /**
     * Ask for a fresh [observeContextUsage] reading of [conversationId] now rather than at the next turn end
     * (#1410, `request_context_usage`). The answer arrives on the flow the caller already collects.
     *
     * **Fire-and-forget, non-suspending and non-throwing.** With no live connection it is a no-op. A refusal
     * (`conversation.not_found`, `context_usage.unavailable`) leaves the current reading as it is and surfaces
     * nothing, and nothing retries.
     *
     * Default is a no-op, so no test double needs to override it.
     */
    fun requestContextUsage(conversationId: String) = Unit
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
     *
     * Identity: `(previousSessionId, newSessionId, occurredAt)`. Invariant:
     * unique within a thread (#775). The thread's `LazyColumn` keys a
     * boundary on exactly these fields, so a duplicate crashes it; the pair
     * alone is not unique, since an idle-evicted session keeps its id and
     * every eviction of it is `A->A`. Uniqueness is a producer obligation —
     * both thread writers skip a boundary the thread already holds
     * (`holdsBoundary`) — documented here and asserted in tests, not
     * enforced at construction (as [UnrecognizedMessage.id]).
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

    /**
     * Text claude printed **about** the session rather than as part of an answer (#873) — a hook's reason
     * for blocking a prompt, a local command's output, a loop notification. Carried by the `banner` frame,
     * which is conversation-scoped and has no `turn_id`, so the row drives no turn and no status indicator.
     * The frame's `stops_turn` is a report nothing on mobile acts on, so it is not carried here at all.
     *
     * **[text] is claude-authored and unsanitized** — bounded daemon-side at 4 KiB, never cleaned. It is
     * held verbatim; the render boundary owes the control-character and escape stripping (see
     * `BannerNoticeRow`). Consumers must render it inert and attributed to claude and must not log it. The
     * thread cache stores it as held (#1353), and a restored row renders through the same boundary.
     *
     * Identity: [occurredAt], the envelope's (or stored entry's) `ts` — the protocol's `(type, ts)` join
     * key with the type implied by this variant. Invariant: unique among a thread's banners. The thread's
     * `LazyColumn` keys a banner on it, so a duplicate crashes the list; uniqueness is a producer
     * obligation — both thread writers skip a banner the thread already holds (`holdsBanner`) —
     * documented here and asserted in tests, not enforced at construction (as [SessionBoundary]).
     *
     * @param level Closed client-side: claude's open `level` set narrows to [BannerLevel], so the wire
     *   string itself never reaches the UI.
     * @param truncated Whether the daemon cut [text] to fit its bound — the daemon's answer, never re-derived.
     */
    data class Banner(
        val level: BannerLevel,
        val text: String,
        val truncated: Boolean,
        val occurredAt: Instant,
    ) : ThreadItem

    /**
     * A finished compaction (#874): claude shrank the conversation's context, so it no longer remembers
     * detail from above this point, or tried to and failed. Drawn by a `compacting` falling edge (#1358) and
     * filled in by the `compaction_boundary` frame that follows it, or appended by a `compaction_boundary`
     * with no edge before it. Both frames are conversation-scoped with no `turn_id`, so the row drives no
     * turn and no status indicator. **Not a session boundary** — it changes no above-the-line de-emphasis.
     *
     * Every field is narrowed from claude's assertion at decode, so no claude-authored string is held here.
     *
     * Identity: [occurredAt], the envelope's (or stored entry's) `ts` — the protocol's `(type, ts)` join key
     * with the type implied by this variant. Invariant: unique among a thread's compaction boundaries. The
     * thread's `LazyColumn` keys the row on it, so a duplicate crashes the list; uniqueness is a producer
     * obligation — both thread writers skip one the thread already holds (`holdsCompactionBoundary`), and
     * a boundary that fills a pending divider replaces it in place, or removes it when the boundary's `ts`
     * is already held — documented here and asserted in tests, not enforced at construction (as [SessionBoundary]).
     *
     * The thread cache stores it (#1353), since history loads only when the user asks.
     *
     * @param preTokens claude's context size before the compaction, or null when it stated none, stated
     *   `null`, or stated a value that is not a non-negative safe integer. Never a stand-in `0`.
     * @param postTokens The size after, on the same rule.
     * @param manual Whether claude's open `trigger` was exactly `manual`; every other token reads as unknown.
     * @param failed Whether the `compacting` falling edge this divider was drawn from reported a failure
     *   (#1358). A divider drawn from that edge carries no counts until a `compaction_boundary` replaces it,
     *   taking the boundary's `ts`; a failed one is never replaced.
     */
    data class CompactionBoundary(
        val preTokens: Long?,
        val postTokens: Long?,
        val manual: Boolean,
        val occurredAt: Instant,
        val failed: Boolean = false,
    ) : ThreadItem

    /**
     * claude refused a turn on one model and either retried it on another or did not (#875). Carried by the
     * `model_refusal_fallback` / `model_refusal_no_fallback` frames, which are conversation-scoped with no
     * `turn_id`, so the row drives no turn, no status indicator and no model state — `model_announced`
     * stays the authority for which model runs. The wire cannot name the refused partial reply, so no other
     * row is retracted or edited. The frames' `scope` and `refusal_category` drive nothing and are not
     * carried; a field the daemon dropped for size simply arrives empty.
     *
     * **Every string here is claude-authored and unsanitized** — bounded daemon-side, never cleaned. Held
     * verbatim; the render boundary owes the stripping (see `ModelRefusalRow`). Consumers must render them
     * inert and attributed to claude and must not log them. The thread cache stores them as held (#1353), and
     * a restored row renders through the same boundary.
     *
     * Identity: the frame type — `fallbackModel != null` — plus [occurredAt], the envelope's (or stored
     * entry's) `ts`: the protocol's `(type, ts)` join key. Invariant: unique among a thread's refusal rows.
     * The thread's `LazyColumn` keys the row on it, so a duplicate crashes the list; uniqueness is a producer
     * obligation — both thread writers skip one the thread already holds (`holdsModelRefusal`) — documented
     * here and asserted in tests, not enforced at construction (as [SessionBoundary]).
     *
     * @param originalModel The model claude says refused the turn; empty when claude named none.
     * @param fallbackModel The model claude says it retried on — **non-null iff the frame was
     *   `model_refusal_fallback`**, which is what makes it the row's type half.
     * @param banner claude's display prose about the refusal; empty when it sent none.
     * @param bannerTruncated Whether the daemon named `banner` in `truncated_fields` — its answer, never re-derived.
     */
    data class ModelRefusal(
        val originalModel: String,
        val fallbackModel: String?,
        val banner: String,
        val bannerTruncated: Boolean,
        val occurredAt: Instant,
    ) : ThreadItem

    /**
     * A turn that failed or stopped early (#1356), kept so its reason survives the next turn — desktop's
     * `turnBoundary` row. Built from a `turn_end` only when `stoppedTurn` says the turn did not end cleanly;
     * a cancelled or successful turn has no row.
     *
     * Both strings already crossed `stoppedReportText`, so they hold no control, format or separator
     * character and at most 256 UTF-8 bytes. They are still agent-authored: consumers render them as plain
     * text after client-owned copy (see `StoppedTurnRow`) and must not log them. The thread cache stores the
     * row (#1356), and a restored row renders through the same sanitizer.
     *
     * Identity: [turnId]. Invariant: unique among a thread's stopped rows. The thread's `LazyColumn` keys the
     * row on it, so a duplicate crashes the list; uniqueness is a producer obligation — both thread writers
     * skip one the thread already holds (`holdsStoppedTurn`) — documented here and asserted in tests, not
     * enforced at construction (as [SessionBoundary]).
     *
     * @param reason The reason token the row's copy is chosen by; empty reads as a bare error.
     * @param category The API error category the agent reported; empty when none.
     * @param occurredAt When the turn ended: the stored entry's `ts`, or the arrival instant on the live lane.
     */
    data class StoppedTurn(
        val turnId: String,
        val reason: String,
        val category: String,
        val occurredAt: Instant,
    ) : ThreadItem
}

/**
 * How a [ThreadItem.Banner] reads (#873). claude's `level` is an open set; `warning` reads as a warning,
 * `info` is kept in the thread but not drawn, as desktop does (#1359), and every other value — `notice`,
 * `suggestion`, empty, or one claude ships later — reads as a muted notice.
 */
enum class BannerLevel { Warning, Notice, Info }

enum class BoundaryReason { Clear, IdleEvict, WorkspaceChange }

/**
 * Where the daemon's stream-json parser met a message it could not understand (#608). Closed at the
 * six documented wire values, which is what lets the UI's label lookup stay exhaustive: a future seventh
 * site is a compile error rather than a blank slot. [CodexMethod] and [CodexItem] (#1109) are the Codex
 * translator's lanes for an unmapped app-server notification method and an unmapped item type.
 */
enum class UnrecognizedSite { LineType, AssistantBlock, UserBlock, Undecodable, CodexMethod, CodexItem }

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
 * How far back one thread's history has been received (#1354), saved beside its cached rows: the last
 * received [HistoryPage]'s [cursor] and [atStart], desktop's received `coverage`. Only a received page sets
 * it, even an empty one; the row count never implies it, and a thread only ever fed live has none.
 *
 * [cursor] is the daemon's opaque value, echoed verbatim and never logged, parsed, or used as a path or
 * key — so [toString] leaves it out.
 */
data class HistoryPosition(
    val cursor: String,
    val atStart: Boolean,
) {
    override fun toString(): String = "HistoryPosition(atStart=$atStart)"
}

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
 * @param capabilities What the session accepts (#1111), or `null` when the reply carried no list — a conn
 *   without `multi_agent`, or a reply that resolved no session. `null` narrows nothing.
 * @param memorySearch Search access reported for this session, or unknown when omitted or invalid. This
 *   says nothing about knowledge capture; only explicit aggregate `Absent` confirms no installation.
 * @param held `true` for a reading carried across a reconnect (#1320) rather than answered on the current
 *   connection: shown, but never acted on, and replaced by the connection's own reply.
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
    val capabilities: SessionCapabilities? = null,
    val memorySearch: MemorySearchReport = MemorySearchReport.Unknown,
    val held: Boolean = false,
)

/** Search access for the selected session's agent and workspace, not knowledge capture. */
enum class MemorySearchAvailability { Available, Unavailable, Absent, Unknown }

/** A detected provider; installed and enabled are independent of effective availability. */
data class MemorySearchProvider(
    val id: String,
    val displayName: String,
    val installed: Boolean,
    val enabled: Boolean,
    val availability: MemorySearchAvailability,
)

/** Only an explicit [MemorySearchAvailability.Absent] confirms no installation. */
data class MemorySearchReport(
    val availability: MemorySearchAvailability,
    val providers: List<MemorySearchProvider>,
) {
    companion object {
        val Unknown = MemorySearchReport(MemorySearchAvailability.Unknown, emptyList())
    }
}

/**
 * The part of a `session_settings` reply's `capabilities` object this client reads (#1111). The daemon
 * builds it from the same checks that refuse a write, so a client offers only what it lists.
 *
 * The strings are daemon-authored. They are compared against published row values and the client's own
 * permission-mode wire values, and never rendered, logged or sent.
 *
 * @param effortLevels The effort levels the session's current model accepts. `""` is accepted but never listed.
 * @param permissionModes The `permission_mode` values a write accepts. Never `bypassPermissions`, which is
 *   reachable only as `yolo`.
 * @param slashCommands Whether the session answers slash commands at all; `true` when the daemon predates
 *   the flag.
 * @param mcpServers Whether the session answers MCP status at all (#1344); `true` when the daemon predates the flag.
 */
data class SessionCapabilities(
    val effortLevels: List<String>,
    val permissionModes: List<String>,
    val slashCommands: Boolean = true,
    val mcpServers: Boolean = true,
)

/**
 * A conversation's stored system prompt and how it relates to the running session (#823) — the return of
 * [ConversationRepository.requestSystemPrompt]. `data` for structural equality, the [SessionSettings] rule.
 *
 * @param systemPrompt The stored prompt, with **three distinct states**: `null` means no prompt is stored,
 *   `""` an explicitly empty prompt, and any other string the stored text. Untrusted operator-authored
 *   text, held verbatim; render it as plain text only and keep it out of logs and exception messages.
 * @param sessionPromptStatus Whether the running session was started with that value. Independent of
 *   [systemPrompt] — never derive one from the other.
 */
data class SystemPromptReading(
    val systemPrompt: String?,
    val sessionPromptStatus: SessionPromptStatus,
)

/**
 * A validated host read or durable-write acknowledgement. Both strings are untrusted instructions:
 * render as plain text only; never use them as paths, URLs, log fields or exception text.
 * The daemon owns the default. This reading is never persisted in the conversation cache.
 */
data class HostSystemPromptReading(
    val systemPrompt: String,
    val defaultSystemPrompt: String,
) {
    override fun toString(): String = "HostSystemPromptReading(systemPrompt=<redacted>, defaultSystemPrompt=<redacted>)"
}

/** The daemon's three-value verdict on the running session's prompt (#823); nothing else decodes. */
enum class SessionPromptStatus {
    /** The running session was started with the stored value. */
    Matches,

    /** The running session was started with a different value; the stored one applies at the next start. */
    Differs,

    /** Nothing is running to compare against — also the answer for a conversation the daemon does not host. */
    NoSession,
}

/**
 * The system prompt's size limit (#823) — the one place the 8192-byte cap and its UTF-8 count live, so
 * the editing state and the channel modals reuse it rather than counting again. The daemon counts
 * **UTF-8 bytes**, not characters, and the limit is inclusive.
 */
object SystemPromptLimit {
    const val MAX_BYTES = 8192

    /** The UTF-8 byte length of [text], which is what the daemon measures. */
    fun utf8Bytes(text: String): Int = text.encodeToByteArray().size

    /** Whether [text] is within the limit; exactly [MAX_BYTES] bytes fits. */
    fun fits(text: String): Boolean = utf8Bytes(text) <= MAX_BYTES
}

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
 * @param agent The agent this row's model belongs to (#1110): a `multi_agent` menu merges Claude's rows
 *   and Codex's, the same list for every conversation, and a conversation lists only its own agent's.
 *   Claude when the daemon did not tag the row; `null` for an agent this client does not know, which no
 *   conversation lists.
 * @param family The row's model family as the daemon tagged it, or `null` when untagged. Daemon-authored
 *   text under the same obligation as the strings above; nothing parses, renders or keys off it.
 */
data class ModelMenuRow(
    val resolvedModel: String,
    val value: String,
    val displayName: String,
    val effortLevels: List<String>,
    val supportsAutoMode: Boolean,
    val truncatedFields: List<String>?,
    val agent: ConversationAgent? = ConversationAgent.Claude,
    val family: String? = null,
)

/**
 * The slash commands a server published for one conversation (#882) — the return of
 * [ConversationRepository.observeSlashCommandMenu]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § `slash_command_list`. A type rather than a bare list for [ModelMenu]'s reasons: [droppedCommands] must
 * survive beside the rows, and an empty menu must stay distinct from the absent `null` one.
 *
 * `data` is load-bearing: structural equality is what makes the repository's `distinctUntilChanged` skip a
 * value-identical re-snapshot on every reconnect.
 *
 * @param rows The published commands in **claude's own order**. Empty is a positive statement that claude
 *   offered nothing.
 * @param droppedCommands How many entries the producer cut that [rows] does **not** carry; `0` when nothing
 *   was dropped. `rows.size + droppedCommands` is the menu's true size. Read as reported and **never
 *   recomputed**: two daemon-side cuts feed it, so a non-zero count can sit beside any number of rows and a
 *   short list is not evidence of a complete one. Never hardcode or infer a cap.
 */
data class SlashCommandMenu(
    val rows: List<SlashCommandMenuRow>,
    val droppedCommands: Int,
)

/**
 * One published slash command in a [SlashCommandMenu] (#882). Every field is retained **exactly as the
 * daemon reported it** — no trim, no case fold, no line fold, no validation.
 *
 * **SECURITY — these strings are workspace-authored.** [name], [argumentHint], [description] and every
 * element of [aliases] were written by whoever wrote the repository the session runs in, a lower-trust origin
 * than claude, and crossed the subprocess trust boundary. The daemon bounds them but **does not sanitize
 * them**: newlines occur in real descriptions and nothing strips control characters or terminal escapes, so
 * the render boundary owes the sanitization. They are safe to render as **inert text** only and must never be
 * fed to a WebView, an HTML sink, an attribute, a URL, a filename, a cache key or a log line. Nothing keys off
 * them — a retained menu is keyed by conversation id alone.
 *
 * @param name The command name **without** the leading `/`. Not an identifier: one real name is
 *   `__remote-workflow`, so assume no character set.
 * @param argumentHint What the command expects after it (`[name]`, `key=value`, `<model>`). Empty is the
 *   ordinary case, not missing data.
 * @param description The command's summary, which may span several lines.
 * @param aliases Other names that invoke this command, in wire order. Empty covers both "none" and "cut to
 *   nothing"; only [truncatedFields] naming `aliases` tells them apart, and then the aliases are unknown.
 * @param truncatedFields The wire names of **this row's** cut fields (`name`, `argument_hint`,
 *   `description`, `aliases`), or `null` when nothing was cut. A consumer must not present cut text as
 *   complete.
 */
data class SlashCommandMenuRow(
    val name: String,
    val argumentHint: String,
    val description: String,
    val aliases: List<String>,
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
 * Where a running Reset is (#871, pyrycode#2478) — the element type of
 * [ConversationRepository.observeResetting], co-located with the contract it serves like
 * [ApiRetryStatus]. Wire SSOT: pyrycode `docs/protocol-mobile.md` § `resetting`.
 *
 * **Two closed enums and no [String].** The wire's `phase` and `handoff` are closed sets while a reset
 * runs, so the decode boundary narrows both and drops a frame carrying any other token; the routing
 * `conversation_id` stays a key in the repository projection. No daemon-supplied text reaches a
 * consumer through this type — and none exists to reach it: the handoff note itself never crosses the
 * wire, only whether one was made.
 *
 * There is no "not resetting" member: that is `null` at the flow. The two fields are carried
 * independently because the contract states a closed set per field and no rule about their combination.
 *
 * `data` is load-bearing rather than cosmetic: structural equality is what makes the repository's
 * `distinctUntilChanged` projection behave (the [ApiRetryStatus] rule). The `wrapping_up` → `restarting`
 * phase change is a different value and re-emits; another conversation's frame leaves this one equal and
 * does not.
 */
data class ResetStatus(
    val phase: Phase,
    val handoff: Handoff,
) {
    /** The reset's current step: the wrap-up turn writing the note, or the respawn under a new session. */
    enum class Phase { WrappingUp, Restarting }

    /**
     * Whether the successor gets a handoff note. [Pending] throughout the wrap-up turn; resolved once, on
     * the move to [Phase.Restarting]. [Skipped] is a **reported outcome, not a missing value**: it covers
     * every reason no note was made, and all of them mean the successor starts without one.
     */
    enum class Handoff { Pending, Written, Skipped }
}

/**
 * The model claude announced for a conversation's latest turn (#890, pyrycode#1616/#1638) — the element type
 * of [ConversationRepository.observeAnnouncedModel]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § `model_announced`.
 *
 * [model] is **never empty** (the decoder drops a frame that breaks that rule) and is held **verbatim**: not
 * trimmed, normalised, dated or looked up in any model list, where a miss is ordinary. [truncated] says the
 * daemon cut [model] to fit its cap, so a consumer must not present the text as complete when it is set.
 *
 * **SECURITY.** [model] is claude-authored text that crossed the subprocess trust boundary. The daemon bounds
 * it but does not sanitize it, so render it as **inert text only** — never as markup, an attribute, a URL, a
 * filename, a cache key or a log line — and never key a behaviour on it. It is a report, not a control input.
 *
 * `data` is load-bearing: structural equality is what the repository's `distinctUntilChanged` relies on.
 */
data class AnnouncedModel(
    val model: String,
    val truncated: Boolean,
)

/**
 * One live event of [ConversationRepository.observeLiveRefusalEvents] (#1360): what arms or clears a thread's
 * switch-back offer. Wire SSOT: pyrycode `docs/protocol-mobile.md` § `model_refusal_fallback`.
 */
sealed interface LiveRefusalEvent {
    /**
     * A refusal frame as it arrived: the row it folded and, for a fallback frame, its `scope`, verbatim
     * (`null` for `model_refusal_no_fallback`, which has none). `scope` is claude's open string; compare it,
     * never render or log it.
     */
    data class Refused(
        val refusal: ThreadItem.ModelRefusal,
        val scope: String?,
    ) : LiveRefusalEvent

    /** The conversation's session was replaced (`session_transition`), which ends a Reset too. */
    data object SessionReplaced : LiveRefusalEvent
}

/**
 * What claude reported about its own run for a conversation's latest turn (#890, pyrycode#2253/#2254) — the
 * element type of [ConversationRepository.observeSessionFacts]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § `session_facts`.
 *
 * Both strings **may be empty**, which is "not reported", not an error. [claudeCodeVersion] is claude's own
 * build as a string, never parsed or compared. [permissionMode] is an **open set** and **a claim, not a
 * guarantee**: nothing allow-lists it, and it is never the confirmed permission reading. [truncatedFields]
 * names the fields the daemon cut, by their wire keys (`claude_code_version`, `permission_mode`), or is
 * `null` when nothing was cut.
 *
 * **SECURITY.** Both strings are claude-authored text that crossed the subprocess trust boundary, bounded by
 * the daemon but not sanitized. Render them as **inert text only** — never as markup, an attribute, a URL, a
 * filename, a cache key or a log line — and never key a behaviour on them.
 */
data class SessionFacts(
    val claudeCodeVersion: String,
    val permissionMode: String,
    val truncatedFields: List<String>?,
)

/**
 * How full a conversation's context window is, as Claude last reported it (#945, pyrycode#2370/#2431/#2461) — the
 * element type of [ConversationRepository.observeContextUsage]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § `context_usage`.
 *
 * [percentage] is **Claude's own number**, held verbatim; the three need not agree. It is never negative (the
 * decoder drops a frame that says otherwise). The thread does not show it: since #1411 the displayed percentage is
 * computed from [totalTokens] / [maxTokens], as desktop does, so the footer and the Status sheet share one clamp. [asOf] is non-null
 * only on a **remembered** answer, the daemon's record of when Claude last reported it for a dormant
 * conversation; the figure is still the last one Claude gave, so it is held like any other.
 *
 * The frame's inventories and its `model` are deliberately not carried: every string on it is claude-authored,
 * and this type holds numbers only.
 *
 * `data` is load-bearing: structural equality is what the repository's `distinctUntilChanged` relies on.
 */
data class ContextUsage(
    val totalTokens: Long,
    val maxTokens: Long,
    val percentage: Int,
    val asOf: Instant?,
)

/**
 * One conversation's MCP server reading on this connection (#1343) — the element type of
 * [ConversationRepository.observeMcpStatus]. The rules are desktop's `mcpStatusStore`.
 *
 * [report] is `null` before any report; a report with an empty server list is Claude saying there are no servers.
 * [unavailable] is set only when a status ask is refused as `mcp_status.unavailable`. [reconnecting] and
 * [toggling] are set when the request is sent; [reconnectRefused] and [toggleRefused] are set, ending the matching
 * wait, by any correlated refusal of that verb. A report clears all five flags. No refusal touches [report].
 *
 * Report and flags share one value so a report and the flags it clears change in one emission.
 */
data class McpStatus(
    val report: McpStatusReport? = null,
    val unavailable: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectRefused: Boolean = false,
    val toggling: Boolean = false,
    val toggleRefused: Boolean = false,
)

/**
 * The MCP servers Claude reported for a conversation (#1343), in Claude's order. [droppedServers] is the daemon's
 * count of tail entries it omitted, so `servers.size + droppedServers` is the original length; it is never
 * recomputed. Wire SSOT: pyrycode `docs/protocol-mobile.md` § `mcp_status`.
 */
data class McpStatusReport(
    val servers: List<McpServerStatus>,
    val droppedServers: Int,
)

/**
 * One MCP server row (#1343). Every field is the wire's string verbatim, `""` when Claude reported none.
 *
 * **SECURITY.** All five are claude-authored and unsanitized: render as **inert text only**, never log them, never
 * use one as a key, a path, a URL or an authority, and never put one in an exception message. [status] and [scope]
 * are claims, not states to act on; [version] is opaque. [toString] leaves every field out.
 */
data class McpServerStatus(
    val name: String,
    val status: String,
    val error: String,
    val scope: String,
    val version: String,
) {
    override fun toString(): String = "McpServerStatus(redacted)"
}

/**
 * A file the daemon offered in a conversation (#898, pyrycode#2082/#2166) — the element type of
 * [ConversationRepository.observeAttachmentOffers]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § Attachments → `attachment_offered`.
 *
 * [attachmentId] is a validated lowercase UUIDv4, the id to pass back when fetching the file. It is not a
 * capability: the daemon re-validates it on every fetch. [displayName] is the announced file name with every
 * ISO control character, Unicode format character (bidi overrides included), line and paragraph separator
 * and unpaired surrogate removed, cut to 255 UTF-8 bytes. It may be empty.
 *
 * **SECURITY.** [displayName] is claude-authored text even after cleaning. Render it as **inert text only**:
 * never as a path or any part of one, never as a cache key or a log line, and never choose a viewer or
 * handler from its extension, which is not evidence of what the bytes are. [toString] leaves it out.
 */
data class AttachmentOffer(
    val attachmentId: String,
    val displayName: String,
) {
    override fun toString(): String = "AttachmentOffer(attachmentId=$attachmentId)"
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
 * copy**: never rendered, never an authorization signal, never a filename, a cache key or a lookup
 * path. Nothing on this path is ever logged. The frame is a report, never a control input: the only
 * branches on these strings pick the usage pill's client-owned copy and variant (#1519, desktop's
 * `usageLimitNotice` rule), each by **exact equality** — no trim, case fold, prefix or substring test —
 * and every unrecognised value takes the softer arm. Nothing else may branch on any field of this type.
 *
 * @param status Claude's own status for the usage-limit window, verbatim. An **open string with a
 *   mostly unmeasured value set**, never drawn. The decode boundary has already made the benign
 *   comparison, which distinguishes a clearing edge from a warning. The usage pill then compares it
 *   exactly against `rejected` (the "Usage limit reached" lead) and `allowed_warning` (the dismissible
 *   variant); every other value reads "Nearly at usage limit" on the Error pill. Treating it as a closed
 *   set is a bug waiting for claude's next release.
 * @param limitType Which limit the report concerns, verbatim, never drawn. Also an open string — two
 *   observed values do not earn an enum. The usage pill names a window only for exact `five_hour` and
 *   `seven_day` and adds nothing for any other value. **Never pair a clear to it**: the clearing frame
 *   names a *different* `limit_type` than the warning it clears, so a consumer matching on it never
 *   matches. The clear is paired to the conversation, which the repository does by construction.
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

/**
 * How far a conversation's current reasoning has got (#801, pyrycode#1386) — the element type of
 * [ConversationRepository.observeThinkingProgress], co-located with the contract it serves like
 * [ApiRetryStatus] / [SessionSettings] / [ModelMenu].
 *
 * A **reading, not a state transition**: it reports depth, never that a turn began or ended, and
 * absence of one proves nothing (see the observe seam for both measured reasons). There is no
 * "not thinking" member and deliberately no sealed family — the wire has no falling edge of its own, so
 * such a member would be a claim the daemon never makes; "no reading" is `null` at the flow instead.
 *
 * **Two [Long]s and no [String].** The routing `conversation_id` stays a key in the repository
 * projection and never reaches this value, so no daemon-supplied text — banner, screen scrape or
 * otherwise — can structurally reach a consumer through this arm; it is the narrowest payload in the
 * conversation-status family. [Long] rather than [Int] because the wire field is a 64-bit integer, and
 * because [SessionSettings.usedTokens] / [SessionSettings.windowTokens] are already [Long] — a consumer
 * reading this against the context window needs no widening cast.
 *
 * `data` is load-bearing rather than cosmetic: structural equality is what makes the repository's
 * `distinctUntilChanged` projection behave (the [ApiRetryStatus] rule). A changed reading is a
 * different value and re-emits; another conversation's frame leaves this one equal and does not.
 *
 * @param estimatedTokens Claude's estimate of the tokens spent thinking as of the emitting line,
 *   **carried verbatim and not monotonic**: it is cumulative within one *inference request*, not within
 *   a turn, and restarts near zero at every request boundary — repeatedly inside a single turn. Never
 *   clamp it with a running maximum and never difference two readings expecting a non-negative result.
 *   `0` is a real reading (a fresh restart), not an absent one.
 * @param estimatedTokensDelta Claude's per-line increment, exactly as the emitting line carried it.
 *   **The values a client receives do not sum to the turn's total** — the producer's rate bound drops
 *   most of claude's lines and their increments go with them, and no field reports the residue. It is a
 *   rate reading, never an accumulator input.
 */
data class ThinkingProgress(
    val estimatedTokens: Long,
    val estimatedTokensDelta: Long,
)
