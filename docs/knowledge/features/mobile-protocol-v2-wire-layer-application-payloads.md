# Mobile Protocol v2 — wire models + codec — application payloads

Split out of [Mobile Protocol v2 — wire models + codec](mobile-protocol-v2-wire-layer.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Mobile Protocol v2 — wire models + codec](mobile-protocol-v2-wire-layer.md); see that document for the rest.

## Application payloads (decoded on top of `Envelope`)

`Envelope.payload` stays a generic `JsonElement` until a consumer validates it, using a typed payload DTO or a manual decoder for sensitive fields. Those application-payload DTOs — and the pure functions that map them to domain types — live in `data/network/` alongside the wire models, **one file per payload `type`**, with **decode as the single validate boundary**: a malformed/field-incomplete payload throws at `MobileJson.decodeFromJsonElement` rather than producing a partial domain object.

The first one landed in [#316](../codebase/316.md): `ConversationsPayload` / `ConversationSummaryDto` (`ConversationsPayload.kt`) for the `conversations` reply, plus a pure `ConversationsPayload.toConversations(): List<Conversation>` mapper to the domain [`Conversation`](data-model.md). Two precedents it sets for the payloads that follow (#317 `message`, #318 write-responses):

- **Object-wrapped, not a bare array.** The `conversations` payload is `{ "conversations": [ … ] }` (server SSOT `conversations_read.go`, #273), so it decodes to a wrapper DTO whose one field is the row list — **not** a top-level `List<…>`.
- **Timestamps decode to `kotlinx.datetime.Instant` at the DTO** (via an explicit `InstantIso8601Serializer`) — a deliberate departure from `Envelope.ts` staying a `String`: here a domain field (`lastUsedAt`) consumes the instant, so validating the RFC 3339 string at decode keeps the mapper total. One consequence: a malformed timestamp surfaces as **`IllegalArgumentException`** (kotlinx-datetime's `DateTimeFormatException`), **not** a `SerializationException` — a consumer catching decode failures must handle both families (or `Exception`).

Wire-absent domain fields are filled with documented list-tier defaults at the mapping site, never `null`-punned (for `conversations`: `currentSessionId=""` when no valid summary binding is supplied, `sessionHistory=emptyList()`, `isSleeping=false`, `archived=false` — a valid string `current_session_id` now supplies the summary binding; session history/sleep/archive state arrives via the detail + message read paths). These payloads are **decode-only** (server → phone), so they get no encode round-trip test.

`@SerialName("workspace_label") val workspaceLabel: String? = null` (#720) is the one row field copied verbatim rather than list-tier-defaulted — every row, including archived rows, retains its own exact `workspace_label`/`cwd` pair. Explicit wire `null` and an absent legacy key both decode to `null`; the ticket deliberately chose the legacy-omission-is-null reading over `ignoreUnknownKeys`-adjacent "treat missing as malformed" — see [`data-model.md`](data-model.md#conversation).

`@SerialName("pinned_at") @Serializable(with = InstantIso8601Serializer::class) val pinnedAt: Instant? = null`
(#2213) follows #316's own timestamp rule rather than `workspace_label`'s verbatim-string one: since a
domain field (`Conversation.pinnedAt`) consumes the parsed instant, decode validates it the same way as
`last_used_at` — a present non-null value that is not a valid RFC 3339 instant fails the whole `conversations`
decode (`IllegalArgumentException`, the kotlinx-datetime `DateTimeFormatException` family, same as every
other instant on this payload), rather than dropping just that row or that field. An absent key and an
explicit JSON `null` both decode to Kotlin `null` under `explicitNulls = false`, read as unpinned.
[`ConversationResponseDto`](#application-payloads-decoded-on-top-of-envelope) (`conversation_created` /
`conversation_updated`) carries the identical field and serializer, so both mappers (`toConversations()`,
`toConversation()`) copy `pinnedAt` straight through with no list-tier placeholder. `PinnedConversationPayloadTest`
pins valid/null/absent mapping from both DTOs and malformed-non-null rejection (a bad string, an empty
string, an out-of-range year, and a wrong JSON token type — number, boolean, object, array) from both.
See [`data-model.md`](data-model.md#conversation) for the domain field and the cache/repository mirrors.

The second landed in [#317](../codebase/317.md): `MessagePayloadDto` + a closed `WireRole` enum (`MessagePayload.kt`) for the `message` payload, mapped to a domain [`Message`](data-model.md) by `MessagePayloadDto.toMessage(envelope, sessionId)`. It follows #316's per-payload shape with three wrinkles worth folding into the pattern:

> **Premise correction ([#346](../codebase/346.md)).** #317's original framing called this DTO "also the `send_message` response echo" — i.e. assumed `send_message` returns the sender its own persisted `Message` for this mapper to decode. That is **false** against the server SSOT: `send_message`'s only sender-correlated reply is an empty `ack` (success) or an `error` (failure); a `message` envelope is a user-echo to *other* paired devices or the assistant's *later, unsolicited* reply (`in_reply_to: null`), **never** a sender echo. So this mapper has **no** `send_message`-response role — it serves the live-stream / other-device / assistant cases only. The sender's own thread updates via #346's local confirmed-insert. See [[phase4-send-message-acks-not-message-echo]]. (The stale "echo" phrasing still in the code comments and the [#317](../codebase/317.md) ticket file predates this correction and is harmless.)

- **The payload IS the object, not object-wrapped.** No wrapper DTO — `Envelope.payload` decodes straight to `MessagePayloadDto` (server SSOT `internal/protocol/messaging.go`, #272). Object-wrap vs bare is per-payload; check the Go struct, don't assume #316's wrapper.
- **Two validate sites, split across decode and map.** `WireRole` models only the *domain-mappable* roles (`user`/`assistant`), so an unmappable `system` or unknown role is a free `SerializationException` at decode — the technique to reuse whenever a wire enum carries values with no domain target. Meanwhile the message timestamp is the **envelope `ts`** parsed by `Instant.parse` **in the mapper**, so a malformed `ts` throws `IllegalArgumentException` *there*. (Same "a consumer must catch both `SerializationException` and `IllegalArgumentException`" consequence for #312 as #316 — but here the second throw is at *map*, not decode.)
- **A non-wire domain field can be caller-supplied rather than defaulted.** `sessionId` isn't on the `message` payload, so the mapper takes it as a parameter (#312 injects the active session id) instead of giving it a placeholder default like #316's list-tier fields — the value genuinely exists at the call site.

The third landed in [#318](../codebase/318.md): `ConversationResponseDto` (`ConversationResponseDto.kt`) for the **mutation-response** payloads `conversation_created` (reply to `create_conversation`) and `conversation_updated` (reply to `promote_conversation`), mapped to a domain [`Conversation`](data-model.md) by `ConversationResponseDto.toConversation()`. Consumed by the future create/promote mutation slices of `RemoteConversationRepository` (not #312's read flow). Two more wrinkles fold into the pattern:

- **One DTO models multiple `type`-strings when they share a shape.** `conversation_created` and `conversation_updated` carry the *identical* field set (`id`, `is_promoted`, `name?`, `cwd`, `last_used_at`, `workspace_label?` since #720; server SSOT `conversations_write.go`, #274) and differ only in `cwd`↔`name` key order — and kotlinx decodes by name, not position — so one `ConversationResponseDto` decodes both losslessly. The `_created`/`_updated` distinction is a `type`-string routing concern at the `Envelope.type` layer, not a shape concern at decode. It's a **bare object** (like #317, unlike #316's wrapper) and carries no `last_message_ts` — exactly one timestamp, validated at decode like #316 (so the mapper is a total, throw-free field copy — the #316 posture, not #317's map-time throw). It reuses #316's wire-absent-field rule **verbatim** (four list-tier placeholders, cross-referenced, no shared helper extracted — deferred until a third consumer of *that rule* appears).
- **A single-class payload file is named after its class.** ktlint `standard:filename` (spotless) forces a file with exactly one top-level class to be named after it, so the file is `ConversationResponseDto.kt`, **not** the spec's proposed `ConversationResponsePayload.kt`. The siblings escape only because each holds two declarations (#316: wrapper + row DTO; #317: DTO + `WireRole` enum). Name future single-DTO payload files after the DTO. See [[ktlint-filename-rule-single-class]].

### Assistant parent attribution (#1826)

`AssistantDeltaPayloadDto` in `InteractivePayloads.kt` retains
`@SerialName("parent_tool_use_id") val parentToolUseId: String = ""`.
`toEvent` copies it verbatim into `LiveSessionEvent.AssistantDelta`; live and history reduction
retain it on [`Message.parentToolUseId`](data-model.md#message). An absent field or explicit empty
string decodes as empty; explicit JSON `null` rejects the payload at the existing strict decode/drop
boundary, matching the sibling tool DTOs. `AssistantDeltaPayloadsTest` checks these cases with real
JSON and a hostile-looking value whose whitespace and contents must survive unchanged.

The hint stays inert and conversation-local, grants no authority and must never be logged or
interpreted as a path, command or URL. It does not replace wire `turn_id` or `seq` as identity:
main and child lanes keep distinct turn ids and independent sequences. No new lane or completion
protocol is introduced. The wire SSOT remains pyrycode's `docs/protocol-mobile.md`, `assistant_delta`;
[segment reconciliation](remote-conversation-repository-assistant-reply-segments.md#parent-attribution-through-seams-and-merges-1826)
owns retaining known hints when later frames omit them.

### Background-task payloads (#1782)

`BackgroundTaskPayloads.kt` groups the scalar lifecycle, roster and progress DTOs used by the
background-task projection. `BackgroundTaskRowDto.toolCallId` decodes the additive roster string
`tool_call_id` with a default of `""` for older daemons; a present wrong-typed value still fails decode.
The projection normalizes an empty join to unknown. A roster can therefore supply the launching tool
join before any started frame, while missing/empty roster joins cannot erase a known join.

A known join no longer proves that a started frame supplied the description. `BackgroundTaskProjection`
tracks started origin separately per conversation/task: once started arrives, its description, type
and truncation report take precedence over the roster row, and its known join wins. If the started join
is unknown, a roster can fill it. Both roster-before-start and start-before-roster preserve update,
progress and finish slots under the existing panel rules.

The panel roster remains replacement truth: omitted tasks and their pending slots, started-origin
tracking and finished marks are pruned; disappearance is not completion. Retained
[`BackgroundTaskLifecycle` evidence](remote-conversation-repository-reads-and-thread-store-history-paging.md#history-pages-fold-into-the-same-thread-645)
is separate, survives empty/replacing rosters and comes only from started and terminal updated entries.
Live and history lifecycle handling both require `interactive`; the additive roster field adds no
capability. Task descriptions, patches and summaries remain inert text, never evaluated or logged;
malformed decode exceptions are discarded unread. The daemon protocol remains the wire SSOT.

### Workspace-label pushes (#721): a new DTO, and `conversation_updated`'s second producer

\#720's `workspace_label` field sits on the DTOs; #721 is what makes a *live* label change reach the phone, by decoding the two frames daemon #2208/#2209/#2210 ship. Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § Renaming a workspace.

- **`WorkspaceUpdatedPayloadDto` (`WorkspaceUpdatedPayloadDto.kt`, own file)** — `{path: String, label: String? = null}`, keyed by **workspace (a daemon-host folder), not conversation id**. `path` is required with no default (an absent one is malformed); `label` is nullable *with* a default so an omitted key and an explicit `"label":null` both decode to `null` under `explicitNulls = false` — the protocol's two spellings of "clear" need no special case. No `toDomain()` mapper: the two scalars are consumed directly by `RemoteConversationRepository.applyWorkspaceLabel`, which relabels every row whose `cwd` equals `path` — byte-for-byte, no trim/normalize/filesystem access, since two paths differing by a trailing separator name distinct workspaces on the daemon. See [`remote-conversation-repository-reads-and-thread-store.md`](remote-conversation-repository-reads-and-thread-store.md#the-repository--one-projection-cold-fan-out).
- **Both `workspace_updated` and `conversation_updated` arrive from two producers, and a client MUST accept both** (the protocol's own wording): a correlated reply to a request this client sent, and an unsolicited push the daemon fans to every *other* interactive-capable connection when a workspace or conversation changes on another client. `workspace_updated` is applied identically either way — host-scoped by construction, since each `RemoteConversationRepository` owns one connection's projection. `conversation_updated`'s correlated half (`promote`'s reply, #348, and the other mutations' replies) was left to its waiter by #721; since #996 the collector also folds it by `id` before completing the waiter, so a rename survives a caller cancelled mid-reply. Its unsolicited half — no `in_reply_to`, or one matching no pending request — now folds into the projection by the payload's own `id` (`upsertConversation`), carrying whatever `workspace_label` that frame holds, including a move to a differently labelled workspace. `conversation_created` stays a correlated reply only; the daemon never broadcasts a create.
- **Neither frame carries an `event_id`**, so neither rides the [replay cursor](replay-cursor.md) — a client disconnected mid-rename or mid-clear has no gap to replay; it reads the current label off its next `conversations` snapshot instead, same as any other live-only push.

### Outbound request encoders + the `ack`/`error` correlated-reply models (#346)

See [Mobile Protocol v2 — wire models + codec — outbound request encoders](mobile-protocol-v2-wire-layer-request-encoders.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346)
for this part of the wire-layer contract: the `sendMessage`/`createDiscussion`/`promote`/push-registration/modal
encoders, the `ErrorPayload`/`RelayErrorException` correlated-reply models, and how a `conversation_created`
/ `conversation_updated` success reply is decoded and folded (moved into the single inbound collector by
[#2213](remote-conversation-repository-state-errors-and-handoff.md#state--concurrency-model)).

### The screen-snapshot exchange ([#374](../codebase/374.md))

The first wire **exchange modeled as a co-located request + event pair in one file** —
`SnapshotPayload.kt` holds both halves because they are one logical round-trip (the in-file precedent is
`BackfillSincePayloadDto` + `MessageChunkPayloadDto` sharing `MessagePayload.kt`). It is the
parser-independent **floor** of ADR 025's safe-degradation strategy: the phone asks the daemon for a
one-shot **text** picture of the current claude screen, and the daemon renders it via tui-driver inside
the substrate seal — depending on no screen parser, so it survives any parser break. Wire SSOT: server
`internal/protocol/snapshot.go` (pyrycode#617, merged; daemon handler #618). This slice is the
**wire-vocabulary half only** — no repository method, no dispatch, no trust decision; those landed in the
consumer [#375](../codebase/375.md) (`ConversationRepository.requestScreenSnapshot`).

- **`RequestSnapshotPayloadDto` (`SnapshotPayload.kt`)** — the sixth **encode-only request DTO**:
  `request_snapshot` `{conversation_id}` (phone → daemon control), a single `@SerialName("conversation_id")`
  field, narrowed from `RegisterPushTokenPayloadDto`'s shape. Built and `encodeToJsonElement`'d straight
  into the request `Envelope.payload` by the consumer (#375); no domain mapper, no round-trip test.
- **`ScreenSnapshotPayloadDto` (`SnapshotPayload.kt`)** — the **first decode-only payload with NO domain
  mapper**. `screen_snapshot` `{conversation_id, text, ts}` (daemon → phone event), all three required +
  non-null `String` in Go-struct order (a malformed frame fails closed with `SerializationException`,
  never a partial value). Unlike every prior decode-only payload (#316 → `Conversation`-list, #317 →
  `Message`, #318 → `Conversation`), this DTO is **terminal display data**, not a wire→domain edge — the
  consumer reads `.text` directly, so there is no `toX()` mapper and no domain target. Two modeling
  decisions are load-bearing:
  - **`text` is modeled verbatim — no trim/normalize/sanitize.** Decode fidelity is the *entire point* of
    the parser-independent floor; the DTO must reproduce the literal screen. The no-raw-bytes guarantee
    (ADR 025: rendered text only, never raw control codes) is enforced **server-side** by the daemon
    renderer (the trusted authenticated peer), not re-litigated client-side. A test pins a multi-line /
    leading-whitespace value round-tripping byte-for-byte.
  - **`ts` is a plain `String`, deliberately unparsed.** A departure from #316/#318, where a payload
    timestamp decodes to `Instant` via `InstantIso8601Serializer` *because* a domain field consumes it.
    Here nothing consumes `ts` (the consumer returns `text` only), so parsing it would defend nothing.
    **Refined rule:** parse-at-decode a payload timestamp only when a domain field consumes the instant;
    otherwise keep it a `String` like `Envelope.ts`.

The consumer defines `TYPE_REQUEST_SNAPSHOT` / `TYPE_SCREEN_SNAPSHOT` companion
constants in `RemoteConversationRepository`, alongside its conversation verbs.
This is a class-local collection, not an exhaustive shared protocol registry:
diagnostic bundle type strings live in the repository's request and transfer
receiver. Because `SnapshotPayload.kt` holds **two** public types, ktlint `standard:filename` does not fire,
so it keeps the spec's `SnapshotPayload.kt` name — unlike #318's single-class rename ([[ktlint-filename-rule-single-class]]).
`ScreenSnapshotPayloadDto` keeps the `data class` auto-`toString()` (which includes `text`) — matching the
content-bearing `MessagePayloadDto`; `toString`-redaction is reserved for the `token` credential. The
no-content-logging obligation (the #346 posture) was a **code-level invariant on the consumer**, now
honored by [#375](../codebase/375.md)'s `requestScreenSnapshot` (which adds zero log calls — the request,
reply, `conversationId`, and decoded `text` are never logged), not this zero-log-call wire slice.

### The session-settings read exchange (#590)

On the supplied route, [session-scoped admission](mobile-protocol-v2-wire-layer.md#session-scoped-live-admission-2084)
validates envelope ownership and the payload write target before typed decoding or
waiter completion. Empty reads remain unavailable/read-only; update acknowledgements
require the producing session target.

`SessionSettingsPayloads.kt` (already holding the write half's `SetSessionSettingsPayloadDto`/
`SessionSettingsUpdatedPayloadDto` — #543) gains the **read** half: `RequestSessionSettingsPayloadDto`
(encode-only `request_session_settings` request, one required `@SerialName("conversation_id")` key) and
`SessionSettingsPayloadDto` (decode-only `session_settings` reply). Wire SSOT:
`../pyrycode/docs/protocol-mobile.md` § `request_session_settings` / § `session_settings`. Consumed by
[`RemoteConversationRepository.observeSessionSettings`](remote-conversation-repository-session-settings-read.md#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590).

- **`SessionSettingsPayloadDto` models seven original fields, deliberately not eight — the eighth
  (`effective_effort`) never reaches this DTO.** Every modeled original field (`session_id`, `model`,
  `effort`, `yolo`, `permission_mode`, `used_tokens`, `window_tokens`) is **required, with no default** —
  the wire emits all seven unconditionally, so a missing key is a malformed reply rather than a
  silently-defaulted one, and each zero (`permission_mode: ""` especially) is a *read* answer, not a
  manufactured one. `used_tokens`/`window_tokens` decode as `Long` (the pyrycode#720 64-bit-Go-`int` width
  trap, same posture as `HistoryEntry.id`). [#1111](https://github.com/pyrycode/pyrycode-mobile/issues/1111)
  added `capabilities` as `val capabilities: SessionCapabilitiesDto? = null` directly on the
  same DTO — unlike `effective_effort`, it needs no manual presence read, because the wire omits the whole
  object rather than punning an omission into a defaulted scalar. `SessionCapabilitiesDto` in turn requires
  its own `effort_levels` and `permission_modes` arrays (the daemon always sends both when it sends the
  object at all) and defaults `slash_commands` to `true`, so an object from a daemon that predates that flag
  (pyrycode#2670) still decodes. A present object missing either array fails the frame at the same
  structural step as the seven originals. See [Conversation repository](conversation-repository.md) for the
  domain `SessionCapabilities` type and [Thread composer footer § Sourcing — Permission mode](thread-composer-footer.md#permission-mode-650)
  for what reads it.
- **`memory_search` is an optional, independently decoded report.** The DTO holds its raw `JsonElement`;
  `toSessionSettings()` validates it separately after the required settings. Omission (including an older
  daemon), `null`, malformed or incomplete content, and future availability values produce
  `MemorySearchReport.Unknown` without discarding the model, effort, token counts or other optional
  readings. A valid `unknown` report with no providers is still unknown; only an explicit valid aggregate
  `absent` confirms no installation. Provider `installed` and `enabled` remain separate from availability,
  so an installed but disabled provider is not presented as usable. The report describes search access,
  never knowledge capture. See the [daemon protocol](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md)
  § `session_settings` → `memory_search` for the wire contract and [Conversation repository](conversation-repository.md)
  for the portable reading.
- **The required settings and effective effort decode in two ordered steps — the reason they are not one DTO.**
  `MobileJson`'s `explicitNulls = false` means a `String?`-with-`null`-default field cannot tell an omitted
  key from an explicit `null` apart; both decode to the same Kotlin `null`. That is exactly the collapse
  [`WorkspaceUpdatedPayloadDto`](#workspace-label-pushes-721-a-new-dto-and-conversation_updateds-second-producer)
  deliberately *wants* for its `label` field — and the trap this payload must *avoid* for `effective_effort`,
  since the three wire states (omitted / `null` / a string) mean three different things here (unavailable /
  Claude-reports-none / a confirmed level). So `toSessionSettings()` runs:
  1. `MobileJson.decodeFromJsonElement<SessionSettingsPayloadDto>(this)` — the **structural boundary** for
     the seven original fields. A non-object payload, a missing key, or a wrong-typed one fails **here**,
     before any presence read runs.
  2. A private `JsonObject.readEffectiveEffort()` reads the optional eighth key **by presence** off the
     same object step 1 already proved is an object: an **absent** key → `EffectiveEffort.Unavailable`
     (also every reply from an older daemon that predates the field — it decodes successfully rather than
     failing); `JsonNull` (checked first, since `JsonNull` is itself a `JsonPrimitive`) →
     `EffectiveEffort.NotReported`; a string `JsonPrimitive` → `EffectiveEffort.Applied(content)` verbatim,
     `""` and an unrecognised level both included; anything else (number, boolean, object, array) throws.
  This is the [`HistoryEntryDto`](#the-screen-snapshot-exchange-374) idea — keep the raw element where
  decoding must not flatten the wire shape — applied to one field of an otherwise-ordinary DTO rather than
  a whole entry.
- **The thrown message for a wrong-typed `effective_effort` is a static literal naming only the key** —
  `"session_settings: effective_effort must be a string or null"`, no value, no type fragment. It is the
  **one** failure mode in this exchange whose message is this repository's own; every other decode failure
  (a missing/wrong-typed original field) is authored by kotlinx-serialization, whose message can quote the
  offending input, so the no-payload-content guarantee for *those* rests on the caught throwable being
  discarded at the consumer, not on the message being clean — see the read flow's `.catch` in
  [the repository doc](remote-conversation-repository-session-settings-read.md#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590).
- **Never an error frame.** An empty/unhosted/unbound/dormant conversation id is answered with the same
  all-zero `session_settings` reply as a populated one — the daemon's own contract, not a client-side
  fallback — which is what keeps this verb from being usable as a conversation-membership probe. `model`/
  `effort`/`Applied.value` are never mapped through `Model`/`Effort`; `""` means "no override, inherited
  default", a real value rather than an absence.
- **`RequestSessionSettingsPayloadDto` is encode-only**, the same one-required-key discipline as
  `CreateConversationPayloadDto` — model only what is sent. Correlation rides `Envelope.inReplyTo` as
  usual, so there is no request-id field on the payload itself.

### The model-list retention (#791)

New `ModelListPayloads.kt` decodes the `model_list` frame — the per-conversation menu of models claude
will accept, drawn from its `initialize` control reply — through `ModelListPayloadDto` /
`ModelListRowDto`, both `internal` (the `InteractivePayloads` posture: only the domain
[`ModelMenu`](conversation-repository.md) crosses the package boundary). `internal fun
ModelListPayloadDto.toMenu(): ModelMenu` is total and non-throwing, the `toSessionSettings` discipline of
one `MobileJson.decodeFromJsonElement` as the single validate boundary followed by a pure field copy —
but taking `QueueStatePayloadDto.toQueue`'s *shape* rather than `toSessionSettings`'s, because this frame
carries its own routing `conversation_id` and the caller (`RemoteConversationRepository.decodeModelList`)
pairs it with the mapped value rather than returning the bare domain value. Wire SSOT:
`../pyrycode/docs/protocol-mobile.md` § `model_list`.

**Three optionality shapes, and each is a distinct decision:**

- `models` and each row's `effort_levels` are **required, non-nullable arrays** — the wire always sends
  both, so an empty `[]` decodes into a present-but-empty value with no null branch, and an explicit wire
  `null` is out of contract and fails the whole frame rather than masquerading as empty. Same never-`null`
  array contract, applied at two nesting levels.
- `supports_auto_mode` **defaults to `false` when absent, and the default is a read, not an invented
  value**: the wire's own contract states that an absent key means `false`. Contrast `session_settings`'s
  `permission_mode` (above), which has **no** default, because defaulting it would invent a confirmation
  posture the daemon never stated — the same "default only when the absence itself has a stated meaning"
  rule, cutting both ways.
- `truncated_fields` is the frame's one **nullable** array — `null` means nothing was cut, and omitted
  vs. explicit-`null` collapse to the same Kotlin `null` under `MobileJson`'s `explicitNulls = false`.
  That collapse is correct here (both wire spellings mean the same thing), the posture
  [`WorkspaceUpdatedPayloadDto`](#workspace-label-pushes-721-a-new-dto-and-conversation_updateds-second-producer)
  deliberately wants and `effective_effort` (above) had to *avoid*, because *its* three states mean three
  different things. An out-of-contract `[]` decodes to an empty list rather than being punned to `null` —
  what arrived is what is retained.
- `agent` and `family` ([#1110](https://github.com/pyrycode/pyrycode-mobile/issues/1110)) are `omitempty`
  strings a `multi_agent` client's merged row carries; a client that hasn't negotiated `multi_agent` is
  never sent either key. Absence defaults `agent` to `Claude`, the one string default on this frame that is
  *not* read from a stated wire contract the way `supports_auto_mode`'s is — it is this client's own choice,
  matching `Conversation.agent`'s default, and it holds only because a non-`multi_agent` client is never
  sent a Codex row to mis-default. `family` stays `null` when absent and is never given a fallback value.

`dropped_models` is carried **verbatim** into `ModelMenu.droppedModels`, never recomputed from
`rows.size` — the producer's entry cap is daemon-side and not a wire constant, so `models.size +
droppedModels` is the menu's true size and nothing here may derive one from the other.

**Untrusted-string obligation.** `resolvedModel`, `value`, `displayName` and every element of
`effortLevels` are claude-authored text that crossed the subprocess trust boundary; the daemon bounds
them but does not sanitize them — no control character or terminal escape is stripped anywhere on this
path. Nothing on this path trims, folds, normalises, re-encodes or validates one, and nothing keys off
them: the retention is keyed by `conversation_id` alone (see
[the repository doc](remote-conversation-repository-model-and-slash-command-menus.md#the-model-list-inbound-arm--the-connection-scoped-retention-791)),
never by anything derived from row text. `value` in particular is **never parseable** — it is an alias
(`sonnet`), a bracketed variant (`opus[1m]`) or `default`, so no family may be derived by splitting it and
it must never be presented as a version. The obligation is stated as a KDoc on the **domain** type
`ModelMenuRow` in `ConversationRepository.kt` (a plan security-review finding), not only on the wire DTO —
the render consumer (#649) will open the domain type and never the DTO. The render-side
sanitization/length-bound obligation itself belongs to #649; this slice renders nothing and holds the
strings inert.

**Validate JSON token types at untrusted boundaries.** A Kotlin serialization `String`
declaration and `isLenient = false` do not by themselves guarantee rejection of numeric
JSON primitives during tree decoding. For a required wire string, retain the field
as a `JsonPrimitive` and check `isString` before taking `content`, or check the raw
object before DTO decoding. [Session errors (#1677)](remote-conversation-repository-state-errors-and-handoff.md#conversation-session-errors-1677)
apply this to all three required fields and to `turn_state`'s routing id/state before
allowing a clear. Otherwise a malformed error can replace held state, or a numeric id
can clear the error under its string representation. Test numeric, boolean and null
values explicitly rather than inferring strictness from valid strings or array failures.

Count decoding also coerces: `"dropped_models":"40"` becomes `40`, pinned by
`ModelListPayloadsTest.quotedNumberForDroppedModels_coercesRatherThanFailing`.
The tree decoder parses primitive content regardless of quoting; strict token shape
therefore needs an explicit check whenever the contract requires it.

### The on-demand ask — `request_model_list` (#792)

`RequestModelListPayloadDto` (`ModelListPayloads.kt`, joining `ModelListPayloadDto`) is the third and
last way a client gets a menu, and the only one it can trigger itself — the connect-time reconcile and
the live per-spawn frame each cover every conversation that exists at one of those two edges, but a
conversation created *after* the phone connected crosses neither. **Encode-only, `internal`**, one
always-present `@SerialName("conversation_id")` key with no default — the `RequestSessionSettingsPayloadDto`
/ `RequestHistoryPayloadDto` decision: correlation rides `Envelope.inReplyTo`, so the payload carries no
request-id key. `internal` joins this file's existing visibility rather than the public request DTOs
elsewhere, since the one consumer (`RemoteConversationRepository`) is in the same module.

The reply is **the same `ModelListPayloadDto`** #791 already decodes — no second decode, no second
payload shape — carrying an `in_reply_to` and, like the reconcile burst's own frames, no `event_id`; it
never enters the [replay ring](replay-cursor.md) and advances no cursor. A refusal is an `error` with one
of two codes told apart at the reading: `model_list.unavailable` (the daemon hosts the conversation but
has no vocabulary to answer with yet — the same ask may succeed later) and `conversation.not_found` (the
daemon does not host what was named — terminal for that id). `TYPE_REQUEST_MODEL_LIST =
"request_model_list"` and `ERROR_MODEL_LIST_UNAVAILABLE = "model_list.unavailable"` join the
`RemoteConversationRepository` companion registry beside `TYPE_MODEL_LIST` and
`ERROR_CONVERSATION_NOT_FOUND`.

**The empty string names nothing and is refused daemon-side**, so the sender declines to put one on the
wire at all rather than sending a frame it knows will be refused — desktop's falsy-id guard
(`requestModelList` in `modelListBridge.ts`), same reasoning. A connection that has not negotiated
`interactive` is answered with **nothing at all** — no menu, no error, no signal — so nothing is ever
sent on one.

The triggering rule, the one-shot ledger, the split success/refusal reply paths and the no-retry
discipline are a repository-layer concern, not a wire-decode one — see [Remote repository § The
on-demand ask](remote-conversation-repository-model-and-slash-command-menus.md#the-on-demand-ask--request_model_list-792),
not duplicated here.

### The slash-command-list retention (#882)

New `SlashCommandListPayloads.kt` decodes the `slash_command_list` frame — the per-conversation menu of
slash commands claude will accept, drawn from its `initialize` control reply — through
`SlashCommandListPayloadDto` / `SlashCommandListRowDto`, both `internal` (the `ModelListPayloadDto`
posture: only the domain [`SlashCommandMenu`](conversation-repository.md) crosses the package boundary).
`internal fun SlashCommandListPayloadDto.toMenu(): SlashCommandMenu` is total and non-throwing, the
`toMenu`/`toSessionSettings` discipline of one `MobileJson.decodeFromJsonElement` as the single validate
boundary followed by a pure field copy. This frame has **no inbound verb** — the phone never sends one, and
there is no `request_slash_command_list` sibling to `request_model_list` (#792) — so this section documents
one DTO family, not two. Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `slash_command_list`.

**Optionality matches `model_list`'s pattern, not its exact shape.** `commands` is a **required,
non-nullable array** — an empty `[]` decodes into a present-but-empty menu, and an explicit wire `null`
fails the whole frame. `name`, `argument_hint`, `description` and `aliases` are required on every row — a
row missing one fails the frame, so there is no partial row. `truncated_fields` is the one **nullable**
array on a row, the `model_list` `truncated_fields` pattern: `null` means nothing was cut, and omitted vs.
explicit-`null` collapse to the same Kotlin `null` under `MobileJson`'s `explicitNulls = false`.

`dropped_commands` is carried **verbatim** into `SlashCommandMenu.droppedCommands`, never recomputed from
`commands.size` — the same rule `dropped_models` follows, but the consequence differs: the daemon feeds
`dropped_commands` from **two** independent producer-side cuts (an entry cap and a byte bound), and the
byte bound can fire before the entry cap does, so a non-zero count can arrive beside *any* row count, not
only a short list the way a single-cause cut might suggest. A consumer must read `droppedCommands` and
never infer completeness from `rows.size`.

**Untrusted-string obligation, one trust hop lower than `model_list`.** `name`, `argumentHint`,
`description` and every element of `aliases` are **workspace-authored** text — written by whoever authored
the repository the session runs in, crossing the subprocess trust boundary one hop earlier than
`model_list`'s claude-authored strings, since claude did not write them either. The daemon bounds them but
does not sanitize them: embedded newlines occur in real descriptions, and nothing strips control characters
or terminal escapes on this path. Nothing here trims, folds, normalises, re-encodes or validates one, and
nothing keys off them — retention is keyed by `conversation_id` alone (see
[the repository doc](remote-conversation-repository-model-and-slash-command-menus.md#the-slash-command-list-inbound-arm--the-connection-scoped-retention-882)).
`name` in particular is **not an identifier** — one real captured name is `__remote-workflow` — so no
character set may be assumed, and a decode test pins that padding and an embedded escape sequence survive a
round trip unchanged, so a later "cleanup" cannot quietly start rejecting or rewriting real names. The
obligation is stated as a KDoc on the **domain** type `SlashCommandMenuRow` in `ConversationRepository.kt`
(a plan security-review finding), not only on the wire DTO. The render-side sanitization obligation itself
belongs to the Actions control and slash-completion tickets split from
[#655](https://github.com/pyrycode/pyrycode-mobile/issues/655); this slice renders nothing and holds the
strings inert.

**One retention-layer departure from `decodeModelList`, decided at the plan's Design section rather than
here:** `SlashCommandMenuProjection`'s decoder also drops a frame whose `conversation_id` is empty (the
daemon's `_zero` fixture shape), where `decodeModelList` would retain such a frame under the map key `""`.
This is a routing decision on the decoded value, not a DTO-shape one, so it lives in the repository doc
linked above rather than being duplicated on this wire-layer page.

### Host system prompt validation (#1774)

`HostSystemPromptPayloads.kt` manually decodes the correlated `host_system_prompt`
reply through `toHostSystemPromptReading`. The payload must be an object and both
`system_prompt` and `default_system_prompt` must be JSON strings. Missing, null,
numeric, boolean, array or object values fail; empty strings remain valid. Each
string must fit `SystemPromptLimit` (8192 UTF-8 bytes inclusive). Additional keys
are tolerated; strings are never trimmed, truncated or replaced with defaults.

Manual decoding avoids serialization exceptions that quote sensitive input.
Errors name only static fields and constraints; both reading fields are redacted
by `toString`. The host command layer also sanitizes daemon error replies, since
safe payload decoding alone cannot prevent error messages from exposing prompts.
See [host operations](remote-conversation-repository-conversation-writes.md#host-system-prompt-read-and-durable-save-1774)
for the exchange and authoritative daemon protocol links. The channel's nullable
prompt decoder is a separate contract and must not be used here.
