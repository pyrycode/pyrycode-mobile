# ConversationRepository — data-layer contract

The single interface UI ViewModels consume, implemented by the in-memory fake and the relay-backed repositories.

Package: `de.pyryco.mobile.data.repository` (`app/src/main/java/de/pyryco/mobile/data/repository/`).

Normal builds bind the [`StableConversationRepository`](stable-conversation-repository.md) facade over the live [`RemoteConversationRepository`](remote-conversation-repository.md). The Phase 1 `FakeConversationRepository` remains the explicit demo/test selection. See [Phase 1 implementation](#phase-1-implementation--fakeconversationrepository) below.

## Shape

```kotlin
interface ConversationRepository {
    fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ThreadItem>>
    fun observeLastMessage(conversationId: String): Flow<Message?>
    fun observeStall(conversationId: String): Flow<Boolean> = flowOf(false)
    fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = flowOf(emptyList())
    fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = flowOf(ApiRetryStatus.NotRetrying)
    fun observeCompacting(conversationId: String): Flow<Boolean> = flowOf(false)
    fun observeResetting(conversationId: String): Flow<ResetStatus?> = flowOf(null)  // #871 — which phase a Reset is in and its handoff outcome; null means no reset running
    fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = flowOf(null)  // #590 — the settings READ, counterpart to setSessionSettings
    fun refreshSessionSettings(conversationId: String) = Unit  // #590 — invalidate the observeSessionSettings reading; fire-and-forget, never throws
    fun observeModelMenu(conversationId: String): Flow<ModelMenu?> = flowOf(null)  // #791 — the server-published model/effort menu, retained per conversation; null means unavailable, never the device Model/Effort enums
    fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> = flowOf(null)  // #882 — the server-published slash-command menu, retained per conversation; null means no frame heard, distinct from an empty menu; issues no ask, unlike observeModelMenu
    fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> = flowOf(null)  // #801 — how far claude's current reasoning has got; null means no reading, never "not thinking"
    fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = flowOf(null)  // #802 — what claude last reported about its usage-limit window; null means nothing to read (never reported, benign clear, or expired)
    fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = flowOf(null)  // #890 — the model claude last announced for this conversation's turn; null until an announcement arrives, never SessionSettings.model
    fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = flowOf(null)  // #890 — the facts claude last reported about its own run; null until a report arrives, permissionMode is claude's claim, never the confirmed reading
    fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = flowOf(null)  // #945 — how full the context window is, as claude last reported it; null means unavailable, never zero, and never SessionSettings.usedTokens/.windowTokens
    fun observeAttachmentOffers(conversationId: String): Flow<List<AttachmentOffer>> = flowOf(emptyList())  // #898 — files the daemon offered in this conversation on this connection, live-only (no replay, no list verb), arrival order, one entry per attachment id
    val mutationsSupported: Boolean get() = true  // #507 — capability gate for the mutation methods below

    suspend fun createDiscussion(workspace: String? = null): Conversation
    suspend fun createChannel(name: String, workspace: String?): Conversation =  // #956/#1189 — null omits cwd so the daemon chooses its default; default throws, unlike createDiscussion
        error("createChannel is not implemented for this ConversationRepository")
    suspend fun promote(conversationId: String, name: String, workspace: String? = null): Conversation
    suspend fun archive(conversationId: String)
    suspend fun unarchive(conversationId: String)
    suspend fun delete(conversationId: String): Unit =
        error("delete is not implemented for this ConversationRepository")
    suspend fun rename(conversationId: String, name: String): Conversation
    suspend fun setSessionSettings(  // #543 — SESSION-scoped, not conversation-scoped
        sessionId: String, model: String? = null, effort: String? = null, yolo: Boolean? = null,
    ): Unit = error("setSessionSettings is not implemented for this ConversationRepository")
    suspend fun startNewSession(conversationId: String, workspace: String? = null): Session
    suspend fun changeWorkspace(conversationId: String, workspace: String): Session
    suspend fun sendMessage(conversationId: String, text: String): Message
    suspend fun sendMessage(conversationId: String, text: String, attachments: List<MessageAttachment>): Message =  // #830/#983 — only each MessageAttachment.attachmentId goes on the wire, in caller order, deduped, capped by MessageAttachmentIds.MAX (32); the confirmed row carries one reference per distinct id with the name/MIME hint used for the send
        error("sendMessage with attachments is not implemented for this ConversationRepository")

    fun recentWorkspaces(): Flow<List<String>> = flowOf(emptyList())
    suspend fun createWorkspaceFolder(name: String): String =
        error("createWorkspaceFolder is not implemented for this ConversationRepository")
    suspend fun requestScreenSnapshot(conversationId: String): String =
        error("requestScreenSnapshot is not implemented for this ConversationRepository")
    suspend fun dropQueuedMessage(conversationId: String, queuedMessageId: Long): Unit =  // #466 send, #781 also drops the sender's own thread echo
        error("dropQueuedMessage is not implemented for this ConversationRepository")
    suspend fun requestHistory(conversationId: String, cursor: String = "", limit: Int = 0): HistoryPage =  // #623 — one backward page of on-disk history
        error("requestHistory is not implemented for this ConversationRepository")
    suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading =  // #823 — the stored prompt + running-session status; conversation-scoped, never session-scoped
        error("requestSystemPrompt is not implemented for this ConversationRepository")
    suspend fun setSystemPrompt(conversationId: String, systemPrompt: String?): Unit =  // #823 — null clears, "" stores an explicit empty prompt, any other text verbatim
        error("setSystemPrompt is not implemented for this ConversationRepository")
    suspend fun setMuted(conversationId: String, muted: Boolean): Unit =  // #1000 — one set_conversation_muted frame; muted is always on the wire, true and false alike
        error("setMuted is not implemented for this ConversationRepository")
    suspend fun uploadAttachment(conversationId: String, bytes: ByteArray, filename: String, mimeType: String): AttachmentUploadResult =  // #829 — attachment_chunk frames on this repository's own host, settled once
        error("uploadAttachment is not implemented for this ConversationRepository")
    suspend fun fetchAttachment(conversationId: String, attachmentId: String): AttachmentFetchResult =  // #899 — connection-level, host-blind: one request_attachment, verified bytes in memory
        error("fetchAttachment is not implemented for this ConversationRepository")
    suspend fun retrieveAttachment(conversationId: String, attachmentId: String): AttachmentRetrievalResult =  // #899 — the file kept in app-private storage for this repository's host
        error("retrieveAttachment is not implemented for this ConversationRepository")
}

enum class ConversationFilter { All, Channels, Discussions, Archived }

sealed interface ThreadItem {
    data class MessageItem(val message: Message) : ThreadItem
    data class SessionBoundary(
        val previousSessionId: String,
        val newSessionId: String,
        val reason: BoundaryReason,
        val occurredAt: Instant,
        val workspaceCwd: String? = null,
    ) : ThreadItem
}

enum class BoundaryReason { Clear, IdleEvict, WorkspaceChange }

data class QueuedMessage(val id: Long, val text: String, val timestamp: Instant, val messageId: String = "")  // #460, messageId #781

data class HistoryPage(val entries: List<HistoryEntry>, val cursor: String, val atStart: Boolean)  // #623 — return of requestHistory
data class HistoryEntry(val id: Long, val type: String, val payload: JsonElement, val timestamp: Instant)  // #623 — element type of HistoryPage.entries

sealed interface ApiRetryStatus {  // #593 — element type of observeApiRetry
    data object NotRetrying : ApiRetryStatus
    data object AttemptUnknown : ApiRetryStatus
    data class Attempt(val current: Int, val total: Int) : ApiRetryStatus
}

data class ResetStatus(val phase: Phase, val handoff: Handoff) {  // #871 — element type of observeResetting
    enum class Phase { WrappingUp, Restarting }
    enum class Handoff { Pending, Written, Skipped }
}

data class SessionSettings(  // #590 — return element of observeSessionSettings
    val sessionId: String, val model: String, val effort: String, val effectiveEffort: EffectiveEffort,
    val permissionMode: String, val yolo: Boolean, val usedTokens: Long, val windowTokens: Long,
    val capabilities: SessionCapabilities? = null,  // #1111, trailing/defaulted — null means no list (a non-multi_agent conn or an unresolved session), never "nothing accepted"
    val memorySearch: MemorySearchReport = MemorySearchReport.Unknown,  // optional report; only explicit Absent confirms no installation
)

enum class MemorySearchAvailability { Available, Unavailable, Absent, Unknown }
data class MemorySearchProvider(val id: String, val displayName: String, val installed: Boolean,
    val enabled: Boolean, val availability: MemorySearchAvailability)
data class MemorySearchReport(val availability: MemorySearchAvailability,
    val providers: List<MemorySearchProvider>)  // Unknown = (Unknown, emptyList())

data class SessionCapabilities(  // #1111 — what the session accepts; daemon-authored strings, compared only, never rendered or logged
    val effortLevels: List<String>, val permissionModes: List<String>, val slashCommands: Boolean = true,
)

data class SystemPromptReading(  // #823 — return of requestSystemPrompt
    val systemPrompt: String?, val sessionPromptStatus: SessionPromptStatus,
)

enum class SessionPromptStatus { Matches, Differs, NoSession }  // #823 — the daemon's three published values, nothing else decodes

object SystemPromptLimit {  // #823 — the one public helper the editing state (#824) and channel modals reuse
    const val MAX_BYTES = 8192
    fun utf8Bytes(text: String): Int = text.encodeToByteArray().size
    fun fits(text: String): Boolean = utf8Bytes(text) <= MAX_BYTES
}

sealed interface EffectiveEffort {  // #590 — effective_effort's three wire states, kept apart
    data object Unavailable : EffectiveEffort   // key omitted
    data object NotReported : EffectiveEffort   // explicit null
    data class Applied(val value: String) : EffectiveEffort
}

data class ModelMenu(val rows: List<ModelMenuRow>, val droppedModels: Int)  // #791 — return element of observeModelMenu
data class ModelMenuRow(  // #791 — one published row, retained exactly as the daemon reported it
    val resolvedModel: String, val value: String, val displayName: String,
    val effortLevels: List<String>, val supportsAutoMode: Boolean, val truncatedFields: List<String>?,
    val agent: ConversationAgent? = ConversationAgent.Claude, val family: String? = null,  // #1110, both trailing/defaulted
)

data class SlashCommandMenu(val rows: List<SlashCommandMenuRow>, val droppedCommands: Int)  // #882 — return element of observeSlashCommandMenu
data class SlashCommandMenuRow(  // #882 — one published row, retained exactly as the daemon reported it; workspace-authored text, not claude-authored
    val name: String, val argumentHint: String, val description: String,
    val aliases: List<String>, val truncatedFields: List<String>?,
)

data class ThinkingProgress(val estimatedTokens: Long, val estimatedTokensDelta: Long)  // #801 — return element of observeThinkingProgress

data class UsageLimitReading(  // #802 — return element of observeUsageLimit; conversation_id stays the projection's map key, never a field here
    val status: String, val limitType: String, val resetsAt: Long,
    val utilization: Double?, val truncatedFields: List<String>?,
)

data class AnnouncedModel(val model: String, val truncated: Boolean)  // #890 — return element of observeAnnouncedModel; never SessionSettings.model
data class SessionFacts(  // #890 — return element of observeSessionFacts; permissionMode is claude's claim, never SessionSettings.permissionMode
    val claudeCodeVersion: String, val permissionMode: String, val truncatedFields: List<String>?,
)
data class ContextUsage(  // #945 — return element of observeContextUsage; percentage is claude's own number, never derived from the token counts
    val totalTokens: Long, val maxTokens: Long, val percentage: Int, val asOf: Instant?,
)

data class AttachmentOffer(val attachmentId: String, val displayName: String)  // #898 — return element of observeAttachmentOffers; attachmentId is a validated lowercase UUIDv4, displayName is claude-authored even after cleaning — render as inert text only, never a path
```

The first `AttachmentOffer` of each attachment id also becomes a `Message.attachments` thread row (#983), since the wire never replays `attachment_offered` and the thread cache is its only retention — see [Remote conversation repository § Status projections](remote-conversation-repository.md#status-projections-one-file-per-status-event).

`QueuedMessage` (#460) is the element type of `observeQueue`'s return, **co-located with the interface** (like `ThreadItem` / `ConversationFilter` / `BoundaryReason`) rather than in `data/model/` — a contract's element type lives beside the contract, which also keeps the ktlint single-class-filename rule satisfied (the file already has multiple public top-level types) and avoids a needless extra file. `messageId: String = ""` (#781) is the daemon-relayed `send_message` client id (pyrycode#2092), added last and defaulted so the existing positional `QueuedMessage(1L, "…", t0)` preview literals in `QueuedBacklog` and `ThreadScreen` stay untouched. It is a correlation key only — compared for equality, never rendered, never a list key (client-chosen, unique nowhere), never logged — and `""` is the value meaning "correlates with nothing". See [Queued backlog](queued-backlog.md).

`ApiRetryStatus` (#593) is the element type of `observeApiRetry`'s return, co-located the same way. A flat 3-member sealed type rather than a nested `Retrying(attempt: Attempt?)` — avoids a nullable payload and gives a consumer an exhaustive `when` with no null branch. The `data class`/`data object` modifiers are load-bearing: structural equality is what makes the repository's `distinctUntilChanged` projection let a climbed counter (a different `Attempt` value) through while suppressing value-identical re-emissions. See [API-retry status](api-retry-status.md).

`ResetStatus` (#871) is the element type of `observeResetting`'s return, co-located the same way — which phase a conversation's daemon-driven Reset is in, and what became of its handoff note. Two closed enums and no `String`: the wire's `phase` and `handoff` are closed sets while a reset runs, so the decode boundary narrows both and drops a frame carrying any other token; `conversation_id` stays the projection's map key, never a field here, so no daemon-supplied text reaches a consumer through this type. There is no "not resetting" member — that is `null` at the flow, the `ThinkingProgress` posture, not `ApiRetryStatus`'s `NotRetrying`. The wire sends **two** rising edges before its one falling edge (`wrapping_up` then `restarting`), so a later rising edge **replaces** the reading rather than starting a second reset; `data` is load-bearing for the same `distinctUntilChanged` reason as `ApiRetryStatus` — it is what makes that phase change reach the observer as a new emission. See [Resetting state](resetting-state.md).

`SessionSettings`/`EffectiveEffort` (#590) are the return of `observeSessionSettings` and its nested three-state effort reading, co-located the same way. The seven required settings fields retain the daemon's readings — nothing defaulted, normalised, or mapped onto a client vocabulary, because each zero (`sessionId = ""`, `permissionMode = ""`, `windowTokens = 0`) carries its own meaning rather than standing in for an absent value; `observeSessionSettings` itself emits `null` for "unavailable". `effort` is the **saved** choice and `effectiveEffort` is Claude's **applied** reading — neither substitutes for the other. `EffectiveEffort` is a flat 3-member sealed type for the same exhaustive-`when` reason as `ApiRetryStatus`, but it exists because the wire's `effective_effort` key has three states a single nullable field cannot keep apart under `MobileJson`'s `explicitNulls = false` (an omitted key and an explicit `null` would both decode to Kotlin `null`): `Unavailable` (key omitted — unsupported or unreported by this daemon), `NotReported` (explicit `null` — Claude reported no effort parameter), and `Applied(value)` (a confirmed level, carried verbatim, `""` and unrecognised strings included). `data`/`data object` are load-bearing here too, for the same `distinctUntilChanged` reason. See [Remote conversation repository — session settings, archive, delete and workspace change](remote-conversation-repository-conversation-writes.md#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590) and [Mobile Protocol v2 — application payloads](mobile-protocol-v2-wire-layer-application-payloads.md#the-session-settings-read-exchange-590).

`MemorySearchReport` is the optional search-access reading beside those settings. `Unknown` is the default for an omitted, invalid or pending report; `providers.isEmpty()` under `Unknown` does not mean nothing is installed. Only an explicit valid aggregate `Absent` confirms no installation. A detected provider keeps its ID, display name, installed and enabled flags, and availability independently: installed but disabled is still unavailable. This reading describes search for the selected session's agent and workspace, not knowledge capture. The existing cold settings subscription clears to `null` on a new subscription or host replacement, cancels superseded reads on refresh and session transition, and re-reads for the selected conversation; no separate memory-search subscription or persisted value exists. See [Thread state](thread-screen-how-it-works-state.md#memory-search-in-the-current-run-configuration) for the session mismatch mask.

`SystemPromptReading`/`SessionPromptStatus`/`SystemPromptLimit` (#823) are the return of `requestSystemPrompt` and the one shared size-limit helper, co-located the same way. `requestSystemPrompt`/`setSystemPrompt` name a **conversation**, never a session — the prompt can be read and written while nothing is running, and a write takes effect only at the conversation's next session start; neither call restarts or resets a running one. `systemPrompt` keeps **three states distinct in both directions**: `null` is "no prompt stored" (wire: key absent), `""` is an explicitly empty prompt, and any other string is the stored text, held **verbatim** — untrusted operator-authored text that later renders in Compose (#824/#666/#667's concern, not this contract's), never trimmed, normalised, or logged. Reading a value and writing it straight back must not change its state — a distinct property from `SessionSettings`' zeros-carry-meaning rule, but the same "don't collapse wire states" discipline. `sessionPromptStatus` is independent of `systemPrompt` — never derive one from the other — and decodes to exactly the three values the daemon publishes (`Matches`/`Differs`/`NoSession`); `NoSession` is also the answer for a conversation the daemon does not host, since the daemon never answers this read with an error. `SystemPromptLimit` is the **one** place the 8192-UTF-8-byte cap and its byte-counting live, so `setSystemPrompt`'s own over-limit check and any future caller (the shared editing state, the create/edit-channel modals) call `fits`/`utf8Bytes` rather than re-counting; the limit is inclusive (exactly `MAX_BYTES` fits) and counts UTF-8 bytes, not `String` length, so multi-byte text needs the real count, not `.length`. See [Remote conversation repository — session settings, archive, delete and workspace change](remote-conversation-repository-conversation-writes.md#requestsystempromptconversationid--setsystempromptconversationid-systemprompt--the-system-prompt-read-and-write-823).

`ModelMenu`/`ModelMenuRow` (#791) are the return of `observeModelMenu` and its row element, co-located the same way. `observeModelMenu` retains the server-published model/effort vocabulary the daemon sends unasked over a `model_list` frame (once per claude child spawn, and again as a per-conversation burst on every reconnect); `null` is "unavailable", the same resting-state reading `observeSessionSettings` gives, never the hardcoded device `Model`/`Effort` enums and never another conversation's rows. Every `ModelMenuRow` field is retained exactly as the daemon reported it — no trim, fold, alias rewrite, or mapping through `Model`/`Effort` — because the four strings (`resolvedModel`/`value`/`displayName`/`effortLevels`) are claude-authored text that crossed the subprocess trust boundary and the daemon does not sanitize; `value` is never parsed (an alias, a bracketed variant, or `default` — never a version to split a family out of). The row is named *row*, not *option*: `de.pyryco.mobile.data.model.ModalOption` already exists one letter away, and a `ModelOption` beside it would be a homograph trap at every call site. `ModelMenu` wraps the rows (rather than a bare `List<ModelMenuRow>`) so `droppedModels` — the producer's cut count, carried verbatim and never derived from `rows.size` — has somewhere to live beside them, and so a *present-but-empty* menu (`rows == []`) and an *absent* one (`observeModelMenu` emitting `null`) stay distinguishable, which a nullable list alone could not do.

`agent`/`family` ([#1110](https://github.com/pyrycode/pyrycode-mobile/issues/1110)) are trailing, defaulted fields a `multi_agent` client's merged `model_list` tags per row — Claude's rows followed by Codex's, the same list for every conversation — so a conversation can be shown only its own agent's rows before the daemon has to refuse a pick outside the session's agent. `agent` is `ConversationAgent?`, not `ConversationAgent`: an absent or `"claude"` wire tag reads `Claude`, `"codex"` reads `Codex`, and any other value — a case variant included — is `null`, a row **listed in no conversation**. This deliberately **disagrees** with [`conversationAgentOf`](data-model.md#conversation) (an unrecognised *conversation* agent reads `Claude`): a row is a candidate to offer, so an unrecognised tag must fail closed to invisible, while a conversation is something already open, so it must fail open to the agent every client already assumes. Do not fold the two mappings into one function. `family` is copied verbatim and is never parsed, rendered or keyed off — this ticket carries it only because the wire sends it beside `agent`. The filter that turns a merged `ModelMenu` into one conversation's menu — `ModelMenu.forAgent`, run in `ThreadViewModel.runConfigFlow` before the render cap applies — is documented in [Thread screen § the model-menu agent filter](thread-screen-how-it-works-state.md#the-model-menu-agent-filter-1110). **Renders nothing** — [#649](https://github.com/pyrycode/pyrycode-mobile/issues/649) reads this when the composer's model/effort controls land; the on-demand ask for a menu the reconnect burst didn't cover is [#792](https://github.com/pyrycode/pyrycode-mobile/issues/792). See [Remote conversation repository § the model-list inbound arm](remote-conversation-repository-model-and-slash-command-menus.md#the-model-list-inbound-arm--the-connection-scoped-retention-791) and [Mobile Protocol v2 § the model-list retention](mobile-protocol-v2-wire-layer-application-payloads.md#the-model-list-retention-791).

`SlashCommandMenu`/`SlashCommandMenuRow` (#882) are the return of `observeSlashCommandMenu` and its row element, co-located the same way — the `ModelMenu`/`ModelMenuRow` shape, minus the on-demand ask: `slash_command_list` declares no inbound verb, so there is no `request_model_list`-style trigger-from-the-reading and no refusal correlation, and `observeSlashCommandMenu` issues nothing on subscription. `null` is "no frame heard", the same resting-state reading `observeModelMenu` gives, distinct from a present menu with zero rows. Every `SlashCommandMenuRow` field is retained exactly as the daemon reported it — no trim, fold or validation — but the four strings (`name`/`argumentHint`/`description`/each `aliases` element) are **workspace-authored**, a lower-trust origin than `ModelMenuRow`'s claude-authored text, since they crossed the subprocess trust boundary one hop earlier. `name` is not an identifier — one real name is `__remote-workflow` — so no character set may be assumed and nothing here rejects or rewrites one. `SlashCommandMenu` wraps the rows the same reason `ModelMenu` does: `droppedCommands` — carried verbatim, never derived from `rows.size` — needs somewhere to live beside them, and a present-but-empty menu must stay distinguishable from the absent `null` one. Unlike `droppedModels`, `droppedCommands` can arrive non-zero beside *any* row count, not only a short one, because the daemon feeds it from two independent cuts (an entry cap and a byte bound) that can fire in either order — never infer completeness from `rows.size`. The projection also drops a frame whose `conversation_id` is empty (the daemon's `_zero` fixture shape), the one deliberate departure from `ModelMenuProjection`'s decoder, which would retain such a frame under the map key `""`. **Renders nothing** — the Actions control and slash-completion tickets split from [#655](https://github.com/pyrycode/pyrycode-mobile/issues/655) read this and own the render-time sanitization the workspace-authored text still needs. See [Remote conversation repository § the slash-command-list inbound arm](remote-conversation-repository-model-and-slash-command-menus.md#the-slash-command-list-inbound-arm--the-connection-scoped-retention-882) and [Mobile Protocol v2 § the slash-command-list retention](mobile-protocol-v2-wire-layer-application-payloads.md#the-slash-command-list-retention-882).

`ThinkingProgress` (#801) is the element type of `observeThinkingProgress`'s return, co-located the same way — how far a conversation's current reasoning has got, claude's only mid-turn proof of life on the stream-json surface. Two `Long`s and no `String`: the routing `conversation_id` stays a projection key and never reaches this value, so no daemon-supplied text can structurally reach a consumer through this arm — the narrowest payload in the conversation-status family. `estimatedTokens` is **not monotonic** — it restarts near zero at every inference-request boundary, several times inside one turn — so it is carried verbatim with no clamp, no difference against a prior reading, and no truthiness check that would misread a legitimate `0` restart as absence; `estimatedTokensDelta` is a per-line increment, not an accumulator input. `data` is load-bearing for the same `distinctUntilChanged` reason as `ApiRetryStatus`. `observeThinkingProgress` itself has no "not thinking" member the way `ApiRetryStatus` has `NotRetrying`: the wire has no falling edge of its own, so `null` at the flow — not a domain member — is the single "no reading" case, and absence proves nothing (the PTY surface emits none of these frames, and the rate bound means a quiet window is not a stall). See [Thinking-progress state](thinking-progress-state.md).

`UsageLimitReading` (#802) is the element type of `observeUsageLimit`'s return, co-located the same way
— what claude last reported about its usage-limit window, so a turn that stalls on one can say why.
`conversation_id` is deliberately **not** a field: it stays the projection's map key and never reaches
the value a render consumer holds and draws from, the `ApiRetryStatus` rule taken further because this
is the first arm in the family carrying daemon-authored text into a domain type at all. `status` and
`limitType` cross verbatim — never normalised, trimmed or allow-listed — and only `status` is ever
compared, and only against the single benign literal that distinguishes a falling edge from a warning;
every other value, and `utilization` entirely, is an opaque reading to surface, never a case to branch
on. `resetsAt` is a `Long` (the Go `int64` width) carrying claude's unvalidated unix-seconds number
verbatim — `0` means no reset was reported, not the epoch, and neither it nor `utilization` is clamped,
rounded or range-checked. `utilization` and `truncatedFields` are nullable because the wire's absence and
an explicit `0`/`[]` are different facts; `null` is the common case, not the exception. `data` is
load-bearing for the same `distinctUntilChanged` reason as `ApiRetryStatus`. `observeUsageLimit` itself
has no "cleared" domain member: `null` at the flow is the single absent case, covering three upstream
facts a consumer need not tell apart — nothing reported, a benign clear, or an expired reading — and the
**read-time expiry against `resetsAt` is already applied by the time a consumer sees the flow**, so a
consumer must not re-derive the comparison. See [Usage-limit state](usage-limit-state.md).

`AnnouncedModel`/`SessionFacts` (#890) are the element types of `observeAnnouncedModel`'s and `observeSessionFacts`'s returns, co-located the same way — the model claude announced and the facts claude reported about its own run, both drawn from claude's `system/init` line and decoded once per turn. Both types are held **verbatim**: no trim, normalisation, version parsing or `permission_mode` allow-list, because every string crossed the subprocess trust boundary and is untrusted text to render as inert text only, never as markup, a log line or a cache key — the same posture as `ModelMenuRow` and `UsageLimitReading`. `AnnouncedModel.model` is **never empty** — the decoder drops a frame that breaks that rule, the one field-level validation either type performs — and is **not** `SessionSettings.model`: the saved override is ordinarily `""` while claude names a concrete model, and nothing here writes the other. `SessionFacts.permissionMode` is **claude's claim, not the confirmed permission reading** — `SessionSettings.permissionMode` (#650) stays the source of truth, and neither `observeAnnouncedModel` nor `observeSessionFacts` ever writes into `SessionSettings`. Both `SessionFacts` strings may be legitimately empty ("not reported", not an error). Each reading replaces the previous one — the daemon does not dedupe and sends both frames on every turn, so latest-wins is the only correct fold, the same rule `ResetStatus`'s rising edges and `ThinkingProgress`'s per-request restarts follow. `data` is load-bearing for the same `distinctUntilChanged` reason as `ApiRetryStatus`. Cleared by that conversation's `session_transition`, and by a reconnect or host switch through the facade's fresh connection-scoped repository — never by another conversation's frame. See [Remote conversation repository — live stream, modal seams and the replay cursor § `model_announced` / `session_facts`](remote-conversation-repository-live-stream-and-modals.md#model_announced--session_facts--the-announced-model-and-session-facts-readings-890).

`ContextUsage` (#945) is the element type of `observeContextUsage`'s return, co-located the same way — how full a conversation's context window is, as claude last reported it. `percentage` is **claude's own number**, held verbatim and never derived from `totalTokens`/`maxTokens` — the three need not agree, and the decoder drops a frame reporting a negative one rather than admit a value this type cannot legitimately hold. `asOf` is non-null only on a **remembered** answer, the daemon's record of when claude last reported the figure for a dormant conversation; the reading is still claude's last one, so it is held the same as a fresh push. Deliberately **not** `SessionSettings.usedTokens`/`.windowTokens` — those are transcript-derived, this is claude's own arithmetic, and neither substitutes for the other. Pushed after every completed turn only — **the phone sends no `request_context_usage` ask** (#946 Rework 1 removed it: a mid-turn ask blocks the daemon's connection-serial frame worker until the turn ends, which deadlocked a reconnect scenario), so a conversation's reading stays absent until its next turn ends on the current connection, including a freshly opened idle conversation. See [Remote conversation repository — live stream, modal seams and the replay cursor § `context_usage`](remote-conversation-repository-live-stream-and-modals.md#context_usage--the-context-usage-reading-945) for the wire contract and the still-open daemon fix, [pyrycode/pyrycode#2563](https://github.com/pyrycode/pyrycode/issues/2563), that would let a future ticket restore the ask. Rendered in the [composer footer](thread-composer-footer.md#context-usage-segment-946) and the [Status sheet](status-sheet-readings.md#contextwindowsection) since #946, split from #591.

`AttachmentOffer` (#898) is the element type of `observeAttachmentOffers`'s return — a file the daemon offered in a conversation, decoded from `attachment_offered`. Unlike every other status-family arm, this one is **not** gated on the negotiated `interactive` capability: the protocol delivers the frame to every attached client, the same posture as the upload leg's `attachment_stored`, so a non-interactive client would silently lose offers if the arm copied its gated neighbours. Both `conversation_id` and `attachment_id` must pass the published lowercase-UUIDv4 shape check before anything is stored; either failing, or the payload not decoding at all, drops the frame without touching any other inbound envelope. `displayName` is the announced `filename` — the first claude-authored string on this wire that later reaches a file name and a share sheet — cleaned by **code point**, not `Char`: ISO control characters, Unicode format characters (bidi overrides and isolates, zero-width joiners, and supplementary-plane tag characters, which are two surrogate `Char`s that never test as `FORMAT` individually), U+2028/U+2029 (neither control nor format, but rendered as a line break by Compose), and unpaired surrogates are all dropped, then the result is cut to 255 UTF-8 bytes at a code-point boundary (`truncateUtf8`). **SECURITY:** the cleaned name is still claude-authored text — render it as inert text only, never as a path or path component, never a viewer/MIME choice from its extension, never a cache key or a log line; only `attachmentId`, after its shape validates, is ever logged. First arrival wins for a repeated attachment id: a re-announcement neither moves nor renames the held entry. Offers are connection-scoped and unbounded — the wire publishes no count bound and no replay, so a reconnect or host switch starts empty (see the facade below) and there is no cap to revisit without an observed flood. `AttachmentOffer.attachmentId` is the id `uploadAttachment`'s sibling fetch call passes back; fetching the bytes and rendering the offer in the thread are both out of scope here (`#672`). See [Remote conversation repository § Status projections](remote-conversation-repository.md#status-projections-one-file-per-status-event) and [Attachment upload](attachment-upload.md).

`HistoryPage`/`HistoryEntry` (#623) are the return of `requestHistory` and its element type, co-located the same way — one backward step of a conversation's on-disk history log, walked newest-first. `HistoryPage.cursor` is opaque (store and hand back verbatim, never parse or rebuild) and empty exactly when `atStart` is true, which is the **only** termination signal a walk has — a short page says nothing about the end of the log, because the daemon narrows a page to fit its own frame size cap without touching the flag. `HistoryEntry.id` is the durable per-conversation log id and is **never** joined to a live-stream `Envelope.eventId` — different sequences that both look like small integers, pinned in KDoc and covered by a decode test that feeds an entry carrying both keys. `HistoryEntry.type`/`.payload` are replayed content — operator- or `claude`-authored stored frames — and stay exactly as untrusted as the live lane's: `payload` is a `JsonElement`, not a `String`, so a consumer (#645) re-reduces it through the same `MobileJson.decodeFromJsonElement` arms the live lane runs rather than paying for a second parse and a second failure surface; `type` is an open `String` because an entry type this app does not recognise must survive rather than fail the decode. Neither field is logged anywhere in this data layer. See [Remote conversation repository — screen snapshot, dequeue, interrupt and new session](remote-conversation-repository-control-sends.md#requesthistoryconversationid-cursor-limit--the-on-disk-history-page-read-623).

Invariant on `SessionBoundary.workspaceCwd` (#192): non-null iff `reason == BoundaryReason.WorkspaceChange`; `null` for `Clear` and `IdleEvict`. Documented in KDoc on the class, asserted in tests, **not** enforced via `require(...)` — the data class stays a plain DTO, consistent with `Message.toolCall` (#191), `Conversation.archived` (#93), and `Conversation.isSleeping` (#20).

## Conventions

Split into [ConversationRepository — data-layer contract — Conventions](conversation-repository-conventions.md) on 2026-09-23 to keep this document under the 50000-byte cap the docs guard enforces. That section moved there verbatim, headings and anchors intact.

## `ThreadItem` — why a sealed wrapper

The thread screen renders messages chronologically and inserts a horizontal-rule delimiter at each session boundary (`/clear`, idle-evict, workspace change — see CLAUDE.md → "Conversations model"). Above-delimiter messages are visually de-emphasized; the line below offers the memory-plugin install affordance.

The stream interleaves both kinds of row in order, so the consumer never paginates manually across `sessionHistory`. Design notes:

- `MessageItem` **wraps** `Message` rather than having `Message` implement `ThreadItem` directly — keeping a `data/repository/` type out of `data/model/`'s parent chain preserves the layer direction.
- `SessionBoundary` carries both `previousSessionId` and `newSessionId`. Previous anchors the delimiter to the messages above it; new gives the "claude doesn't remember above the line" prompt a stable handle.
- `SessionBoundary`'s identity is `(previousSessionId, newSessionId, occurredAt)`, not the session pair
  alone — invariant, unique within a thread ([#775](../codebase/775.md)). `ThreadScreen`'s `LazyColumn`
  keys a boundary row on exactly these three fields, so a duplicate triple crashes it; the pair alone is
  not unique, because an idle-evicted session keeps its id and every eviction of it is `A->A`. Uniqueness
  is a producer obligation, not construction-enforced — both thread writers (the live lane's
  `appendSessionBoundary` and the history merge's `holdsBoundary`) skip a boundary the thread already
  holds — documented in KDoc and asserted in tests, the same posture as `UnrecognizedMessage.id`.
- `BoundaryReason` lists exactly the three triggers CLAUDE.md names. No speculative `Manual` / `Other` / `Unknown` — add a value if and when a fourth trigger lands.
- `SessionBoundary.workspaceCwd` (#192) carries the new workspace path on the `WorkspaceChange` variant only. Same defaulted-nullable-last-field pattern as `Message.toolCall` (#191); see [`../codebase/192.md`](../codebase/192.md).

## What `observeMessages` does not do

- **No `PagingData`.** "Paginates transparently" in the ticket AC means the consumer doesn't drive pagination; it does not mean lazy windowing in the contract. The Phase 1 fake emits the full list; Phase 4 can window internally and emit a growing prefix without changing the signature. If the channel list or thread grows past hundreds of rows in practice, switching to Paging 3 is a contract change at that point.
- **No `Flow<Result<…>>`.** Element type is `ThreadItem`, not `Result<ThreadItem>`. Failures terminate the flow.
- **No eager boundary emission from `startNewSession` / `changeWorkspace`.** Mutators still mint new sessions without writing an authored `SessionBoundary` to storage; the projection still derives boundaries from message session-id deltas. The two non-derived inputs that *do* exist (since #192) are seed-side: each `SeedSession` declares the `nextBoundaryReason` (and, on `WorkspaceChange`, the `nextWorkspaceCwd`) of the boundary that follows it, flattened into a per-record `boundariesBySessionId: Map<String, AuthoredBoundary>` keyed by **successor session id** and consulted by `buildThreadItems`. Mutator-minted sessions and the `initialMessages` constructor path produce no map entries; the projection falls back to `BoundaryReason.Clear` + `workspaceCwd = null` for those — same observable behaviour as pre-#192 for those paths. The deferred follow-up (lifting `mintNewSession` to accept `(reason, workspaceCwd)` and write `boundariesBySessionId[newSessionId] = AuthoredBoundary(...)` inside the existing `state.update { ... }` block) is unchanged in scope — but its target shape is now in place, so it stays a pure-additive change when it lands.
- **No `.distinctUntilChanged()`.** Consistent with `observeConversations`. Both projections re-emit on every `state.update {}` — even mutations that don't affect the observed slice. If a future chatty mutator (per-token streaming append) makes this matter, fix with a subtype-aware dedupe in the projection, not a sibling state holder.

## Dependency wiring

`kotlinx-coroutines-core 1.10.2` is pinned in `gradle/libs.versions.toml` and declared as `implementation(libs.kotlinx.coroutines.core)`. Compose and lifecycle pull coroutines onto the runtime classpath transitively, but `data/` declares its own pin so the contract isn't coupled to a transitive BOM bump.

`-core` (not `-android`) keeps the data layer JVM-portable for the Compose Multiplatform walk-back (`Main` dispatcher comes in via the UI layer's `-android` transitive).

## Phase 1 implementation — `FakeConversationRepository`

Split into [ConversationRepository — data-layer contract — Phase 1 fake implementation](conversation-repository-fake-implementation.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. That section, Phase 1 implementation — `FakeConversationRepository`, moved there verbatim, headings and anchors intact.

## What's deliberately absent

- **No `getConversation(id)` / `searchConversations` / `archiveBatch`.** Add when a real use case needs them. (The idle-archive `sweep(referenceTime)` (#267) is a *policy* sweep over the whole store, not a caller-supplied `archiveBatch(ids)` — and it lives on the fake only, not the interface.)
- **No companion / factory.** Construction is Koin's job.
- **No `@Throws` annotation.** Kotlin doesn't enforce checked exceptions; doc-comments will name thrown types when concrete impls land.
- **No tests at the interface level.** An interface without an implementation can't be unit-tested. Behavioural tests live with `FakeConversationRepository` in `FakeConversationRepositoryTest.kt` (#4).

## Related

Split into [ConversationRepository — data-layer contract — Related](conversation-repository-related.md) on 2026-09-24 to keep this document under the 50000-byte cap the docs guard enforces. That section moved there verbatim, headings and anchors intact.
