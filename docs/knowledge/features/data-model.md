# Data model — Conversation / Session / Message

The core schema for everything the app shows. Mirrors the entity shape that pyrycode CLI's `conversations.json` registry (Phase 3) and the Discord client (Phase 2) will share — same fields across all consumers.

Package: `de.pyryco.mobile.data.model` (`app/src/main/java/de/pyryco/mobile/data/model/`).

## Types

### `Conversation`

```kotlin
data class Conversation(
    val id: String,
    val name: String?,             // null for discussions; user-set for channels
    val cwd: String,
    val currentSessionId: String,
    val sessionHistory: List<String>,
    val isPromoted: Boolean,       // false = discussion, true = channel
    val lastUsedAt: Instant,
    val isSleeping: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val workspaceLabel: String? = null,
    val agent: ConversationAgent = ConversationAgent.Claude,
)

enum class ConversationAgent { Claude, Codex }
```

`isPromoted` is the single flag that splits the two UI tiers (see CLAUDE.md → "Conversations model"):

- **Discussions** (`isPromoted = false`) — auto-named (`name == null`), throwaway, scratch cwd, 30-day auto-archive.
- **Channels** (`isPromoted = true`) — user-named (`name != null`), persistent, dedicated cwd by default, eligible for memory plugins.

`sessionHistory` is an ordered list of past `Session.id`s; `currentSessionId` is the live one. Together they let the thread screen paginate messages chronologically across session boundaries.

`isSleeping` is `true` when the conversation's current Claude session is closed (i.e. the next user message will start a fresh session). Defaulted to `false` so the existing constructor sites needn't pass it. **Phase 1**: derived in `FakeConversationRepository.observeConversations` from `currentSession.endedAt != null` — see [`conversation-repository.md`](conversation-repository.md). **Phase 4**: parsed directly from the conversations endpoint response (the server reports the bool); the contract on this field is what survives the migration. Surfaced visually as a leading status dot on `ConversationRow` (#20) — see [`conversation-row.md`](conversation-row.md).

`archived` is `true` once `ConversationRepository.archive(id)` has flipped the flag (#93). Authoritative bit, not derived: Phase 1 stores it on the data class; Phase 4 will parse it from the wire response. Defaulted to `false` so existing constructor sites don't change. Routes the conversation into the `ConversationFilter.Archived` slice and out of `Channels` / `Discussions`; the live tiers carry an explicit `!archived` clause so a hypothetical archived channel can't regress the channel list. The trivial inverse `unarchive(...)` is a follow-up ticket. See [`conversation-repository.md`](conversation-repository.md) for the filter matrix.

`muted` (#999) is the host's per-conversation mute, mirroring `archived`'s wire and defaulting shape exactly: [`ConversationSummaryDto`](mobile-protocol-v2-wire-layer-application-payloads.md) (list rows) and [`ConversationResponseDto`](mobile-protocol-v2-wire-layer-application-payloads.md) (`conversation_created` / `conversation_updated`) both carry `@SerialName("is_muted") val isMuted: Boolean = false` and map it straight to `muted`; the `false` default reads an older daemon's rows — or any reply shape that omits the key — as unmuted, so alerts keep firing rather than going silently suppressed. `ConversationListProjection.upsertConversation` needed no change: it already replaces the whole row, so the record's `muted` wins on every fold. Two consumers now read it: the Edit channel mute checkbox writes it back through `setMuted` (#1021, see [Channel list ViewModel](channel-list-viewmodel.md)), and [`AttentionNotifier`'s muted gate](push-messaging-service.md#the-muted-gate-1022) (#1022) reads it from each alert's own host's [`HostConversationSource.snapshots`](dependency-injection-host-conversation-source.md#attention-alerts-685) row to silence that conversation's alerts. **A field added to this class must be traced through every place a `Conversation` is stored, not only its wire decoders**: #999's first pass mirrored `archived` through the DTOs but missed [the on-disk cache](conversation-cache.md#the-cache-local-record-must-mirror-every-conversation-field-999), which `HostConversationSource` publishes on cold start before the first live list arrives — a real window for a muted channel to alert. Check the cache's `CachedConversation` alongside the DTOs whenever a boolean like this one is added.

`agent`/`ConversationAgent` ([#1108](https://github.com/pyrycode/pyrycode-mobile/issues/1108)) names which
agent — `claude` or `codex` — runs a conversation, for a client that has negotiated `multi_agent`
(mobile does not negotiate it yet, so today the daemon never sends the key and every conversation reads
Claude). Wire mapping: [`ConversationSummaryDto`](mobile-protocol-v2-wire-layer-application-payloads.md)
(`conversations` rows) and [`ConversationResponseDto`](mobile-protocol-v2-wire-layer-application-payloads.md)
(`conversation_created` / `conversation_updated`) both carry a raw, nullable `agent: String?` — kept raw,
not pre-mapped, so an absent key stays distinguishable from an explicit `"claude"` — and the one shared
`conversationAgentOf(wire: String?)` in `data/network` maps exactly `"codex"` to `Codex` and everything else,
including `null` and any unrecognised string, to `Claude`. Unlike `archived`/`muted`, a `conversation_updated`
without the key does **not** fall back to the mapped default: `ConversationListProjection.upsertConversation`
keeps the previously stored `agent` when the record's raw field is `null` and the conversation already has a
row, since an older daemon omits the key on that reply. See
[Remote conversation repository — `upsertConversation`](remote-conversation-repository-send-create-promote-rename.md#confirmed-insert-via-conversationlistprojectionupsertconversation-the-projections-second-writer)
for the fold and [Conversation cache § the cache-local record must mirror every field](conversation-cache.md#the-cache-local-record-must-mirror-every-conversation-field-999)
for a known gap: the on-disk cache does not yet carry `agent`, so a cold-started row reads Claude until the
live list arrives. Nothing renders `agent` yet — the model picker and the agent switch it will drive are
still open, per [#1108](https://github.com/pyrycode/pyrycode-mobile/issues/1108)'s stated motivation.

`workspaceLabel` (#720) is opaque, daemon-authored display text, retained verbatim and independent of `cwd` — never a path, never derived from it. Trailing-defaulted to `null` so existing constructor sites and fixtures are unaffected. Wire mapping: [`ConversationSummaryDto`](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope) (`conversations` rows, including archived) and [`ConversationResponseDto`](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope) (`conversation_created` / `conversation_updated`) both carry `@SerialName("workspace_label")` and copy it straight through their mappers; an explicit wire `null` and an absent legacy key both map to `null`. Its first render path is the shared `de.pyryco.mobile.ui.workspace.workspaceDisplayName(cwd, label)` function, added by [`#722`](https://github.com/pyrycode/pyrycode-mobile/issues/722) — see [`workspace-chip.md`](workspace-chip.md#workspacelabel-derivation) for the label-first display rule and its render-path length clamp. #722 also deleted the private `Conversation.workspaceLabel()` extension that previously lived in `ThreadViewModel.kt` and derived a cwd-basename fallback only; the parens-only naming clash between that extension and this property is retired along with it — see [`thread-screen-how-it-works-state.md`](thread-screen-how-it-works-state.md#combineobserveconversations-observemessages-pendingworkspacepickerstatein-whilesubscribed--three-upstreams-since-137) for the history.

Co-located in the same file: a top-level `const val DEFAULT_SCRATCH_CWD: String = "~/.pyrycode/scratch"` — the sentinel `cwd` for conversations with no bound workspace. Single source of truth for the value: imported by `FakeConversationRepository` seeds and `ConversationRow` (which suppresses its workspace label when `cwd == DEFAULT_SCRATCH_CWD`). Introduced in #19; renamed from `DefaultScratchCwd` in #83 to satisfy ktlint's `property-naming` default (Kotlin official style for top-level `const val`). Lives at `data/model/Conversation.kt` because both the UI and data layers already depend on this package, and a separate `WorkspacePaths.kt` for one constant would be premature.

### `Session`

```kotlin
data class Session(
    val id: String,
    val conversationId: String,
    val claudeSessionUuid: String,
    val startedAt: Instant,
    val endedAt: Instant?,
)
```

A session is one continuous claude conversation. New sessions begin on `/clear`, idle-evict, or workspace change — those transitions are what the thread screen renders as horizontal-rule delimiters. `claudeSessionUuid` is the identifier claude itself uses (distinct from our `id`, which is mobile-side).

`endedAt == null` indicates the currently-active session.

### `Message`

```kotlin
data class Message(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Instant,
    val isStreaming: Boolean,
    /** Non-null iff [role] is [Role.Tool]. */
    val toolCall: ToolCall? = null,
    /** The files this message references (#983): sent, replayed from history, or offered. */
    val attachments: List<MessageAttachment> = emptyList(),
)

enum class Role { User, Assistant, Tool }

data class MessageAttachment(
    val attachmentId: String,
    val displayName: String? = null,
    val mimeType: String? = null,
)   // #983 — toString() prints only attachmentId

enum class ToolCallStatus { Running, Done, Failed, Denied }   // Denied: #811

data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,   // #387
    val inputFields: Map<String, String> = emptyMap(),  // #810
    val parentToolUseId: String = "",                   // #810
    val denial: ToolDenial? = null,                     // #811
    val elapsedSeconds: Int? = null,                    // #812
)
```

`isStreaming = true` while assistant output is still arriving over the wire (Phase 4+). Phase 0/1 fake data is `isStreaming = false` for every message except one demo seed (the last assistant message of `seed-channel-pyrycode-mobile`'s current session, added in #184) so the [streaming reveal animation](./message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184) can be observed end-to-end without a real WS feed.

`toolCall` (#191) carries the three pieces a `Role.Tool` message needs the [`ToolCallRow`](./message-bubble.md) consumer (#131) to discriminate: `toolName` (e.g. `"Read"`, `"Edit"`, `"Bash"`), `input` (the call payload — file path, command string, params), and `output` (the response text — file contents, command stdout, etc.). The field is **last** on `Message` and **defaulted to `null`** so every existing positional and named `Message(...)` call site is source-compatible. The invariant — `toolCall != null iff role == Role.Tool` — lives as a one-line KDoc on the field; it is **not** enforced at construction (no `init { }` guard, no `require(...)`). Reasoning: `FakeConversationRepository` is the sole `Message` producer in Phase 0 — there is no external path that could violate the invariant, so a runtime check would defend an unobserved failure mode. Phase 4's wire-parsing path is the right place to enforce it when it lands. No `output: String?` for "tool call in flight, no response yet" — that's a Phase 4 streaming concern; modelling it now would be the same kind of speculative defense. The shape was chosen (over a sealed `Message` hierarchy with a `Tool` variant) for size discipline: nullable-field is a 3-file change with zero forced consumer edits; sealed promotion would have cascaded into ≥16 `seedMsg` / `Message(...)` call sites + two test files. If #131 finds the lack of compile-time discrimination genuinely painful, a sealed promotion ticket can be split out — the `toolCall: ToolCall?` field added here becomes the payload of the eventual `Tool` variant, so nothing is wasted. See `../codebase/191.md` for the trade-off in full.

`ToolCallStatus` (#387) is the live-call lifecycle: a `tool_use` event opens the row as `Running`, the correlated `tool_result` updates it in place to `Done` (or `Failed` on error). It is a **trailing defaulted** field on `ToolCall` (`status = ToolCallStatus.Done`) — the same cascade-avoidance lever as `toolCall` itself: `Done` is the pre-existing semantics (every prior `ToolCall(...)` construction is a *finished* call with output), so the model gains a state machine with zero fixture cascade and `ToolCall(a,b,c) == ToolCall(a,b,c, status=Done)` equalities stay green. The correlation that drives it (by `toolUseId`, tolerant of a misbehaving stream) lives in the repository, not the model — see [`live-tool-call.md`](live-tool-call.md) and `../codebase/387.md`. The static-payload renderer is [`ToolCallRow`](tool-call-row.md); the `status` affordance (running/done/failed visual) is the separate UI slice #388.

`inputFields` and `parentToolUseId` (#810) are the tool call's own input fields and the id of the `Agent`/`Task` call that spawned it, both carried **verbatim** from the wire and both **trailing defaulted** (`emptyMap()` / `""`) for the same zero-fixture-cascade reason as `status`. `input` stays the server's one-line précis; `inputFields` is the input's own top-level string fields (e.g. an `Edit`'s `file_path`), so a future renderer can show what a call acts on without parsing the précis. `parentToolUseId` is `""` for a main-thread call; the parent join (matching it against another row's `toolUseId` within the same conversation) is a rendering concern, not modelled here — see [`live-tool-call.md`](live-tool-call.md#tool_use-input-fields-and-parent_tool_use_id-810).

`denial` (#811) is non-null only on a `Denied` row, carrying claude's own account of the refusal (`ToolDenial`: `toolName`, `decisionReasonType`, `decisionReason`, `message`, `truncatedFields`, `droppedFields`), every field a verbatim copy of the `tool_denied` frame. It is not persisted to the disk cache — a `Denied` row restored from cache carries `denial = null`. See [`live-tool-call.md` § Denied](live-tool-call.md#denied-811).

`elapsedSeconds` (#812) is claude's latest `tool_progress` reading, retained **verbatim** — zero, negative and non-monotonic values included, no clamping or subtraction. It is non-null only while `status == Running`: closing the row (`Done`, `Failed`, or `Denied`) clears it, and a `tool_progress` for a row that is not `Running` is ignored. `null` does not mean the call is stalled — a call can finish before claude's first heartbeat, and a later frame can be lost independently of the lifecycle frames — so absence is never timing evidence. Formatting and display are [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658)'s; see [`live-tool-call.md` § Progress](live-tool-call.md#progress-812).

`attachments` (#983) names the files a message references: one entry per file the operator sent with it (in send order), per id a replayed `send_message` named (in wire order, names unset), or the one file an `attachment_offered` row carries. It is **trailing defaulted** (`emptyList()`), the same cascade-avoidance lever as `toolCall`. `MessageAttachment.attachmentId` is the id to fetch the bytes by; `displayName`/`mimeType` are hints, `null` when not known (a bare history reference) and `""` when known but cleaned to nothing (an offer whose name sanitized empty) — the same three-state convention as everywhere else in this model that "unknown" and "known-empty" are distinct. **Both hints are untrusted display text even after cleaning through `attachmentDisplayName`** (a local file's name is authored by whichever app supplied the document, exactly as an offer's name is authored by claude): render as inert text only, never a path, a cache key, a log field, or a handler choice. `MessageAttachment.toString()` omits both hints so an accidental log call cannot leak one. See [Attachment upload](attachment-upload.md) and [Remote conversation repository — send, create, promote, rename](remote-conversation-repository-send-create-promote-rename.md) (§ Naming a message's attachments) for the send path, [Remote conversation repository — reads and thread store history paging](remote-conversation-repository-reads-and-thread-store-history-paging.md) for history reduction, and [Remote conversation repository § Status projections](remote-conversation-repository.md#status-projections-one-file-per-status-event) for the offer row `AttachmentOfferProjection` appends.

## Why `kotlinx.datetime.Instant`

CLAUDE.md's "Don't" section names Compose Multiplatform as a walk-back trigger. `java.time.Instant` is JVM-only; `kotlinx.datetime.Instant` works on every Kotlin target. The data layer must stay portable, so every timestamp in this package uses the kotlinx type. See `../decisions/0001-kotlinx-datetime-for-data-layer.md`.

## What's deliberately absent

- **No serialization annotations on the model itself.** `@Serializable` / `@JsonClass` never land
  on `Conversation`, `Session` or `Message` directly. The wire layer (Phase 4) and
  [the app-private conversation cache](conversation-cache.md) (#795) each need a serializable
  shape for a subset of these fields; both keep their own private record type with its own
  mapping functions rather than annotating the domain model, which would pull persistence and
  wire concerns into a type every layer depends on.
- **No `require(...)` / `init { }` validation.** Schema-shape only; constructors cannot fail. The repository layer will enforce invariants (e.g. `currentSessionId ∈ sessionHistory ∪ {new}`) when it lands.
- **No `SessionBoundary` marker here.** CLAUDE.md describes a synthetic marker the repository interleaves into the message stream to drive thread-screen delimiters; that type lives with the repository contract as `ThreadItem.SessionBoundary` in `data/repository/ConversationRepository.kt` (landed in #3). See `conversation-repository.md`.
- **No persistence on the model itself.** The conversation cache (#795) is the first persistence
  consumer; it stores a cache-local copy rather than the domain type, so `Conversation` stays
  free of persistence shape the way it already stays free of wire shape.

## Related

- Ticket notes: `../codebase/2.md` (skeleton), `../codebase/191.md` (`Message.toolCall: ToolCall? = null` + new `ToolCall(toolName, input, output)` type), `../codebase/387.md` (`ToolCallStatus` + the `status` field — live tool-call correlation)
- Feature: [`live-tool-call.md`](live-tool-call.md) (#387 — the correlation + status model; #811 — `Denied` + `ToolDenial`; #812 — `elapsedSeconds`); [`attachment-upload.md`](attachment-upload.md) and [`remote-conversation-repository-send-create-promote-rename.md`](remote-conversation-repository-send-create-promote-rename.md) (#983 — `Message.attachments` / `MessageAttachment`)
- Spec: `docs/specs/architecture/2-conversation-session-message-data-classes.md`, `docs/specs/architecture/191-tool-message-structured-payload.md`, `docs/specs/architecture/720-retain-workspace-labels.md` (`workspaceLabel` retention), `docs/specs/architecture/983-message-attachment-references.md` (`Message.attachments` + `MessageAttachment`)
- Decision: `../decisions/0001-kotlinx-datetime-for-data-layer.md`
- Downstream: `conversation-repository.md` (#3 contract — also propagates `toolCall` through `SeedMessage`/`seedMsg(...)` since #191), conversation list + thread UI (the eventual `ToolCallRow` consumer in #131).
