# Mobile Protocol v2 — wire models + codec — application payloads

Split out of [Mobile Protocol v2 — wire models + codec](mobile-protocol-v2-wire-layer.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Mobile Protocol v2 — wire models + codec](mobile-protocol-v2-wire-layer.md); see that document for the rest.

## Application payloads (decoded on top of `Envelope`)

`Envelope.payload` stays a generic `JsonElement` until a consumer decodes it into a typed payload DTO. Those application-payload DTOs — and the pure functions that map them to domain types — live in `data/network/` alongside the wire models, **one file per payload `type`**, with **decode as the single validate boundary**: a malformed/field-incomplete payload throws at `MobileJson.decodeFromJsonElement` rather than producing a partial domain object.

The first one landed in [#316](../codebase/316.md): `ConversationsPayload` / `ConversationSummaryDto` (`ConversationsPayload.kt`) for the `conversations` reply, plus a pure `ConversationsPayload.toConversations(): List<Conversation>` mapper to the domain [`Conversation`](data-model.md). Two precedents it sets for the payloads that follow (#317 `message`, #318 write-responses):

- **Object-wrapped, not a bare array.** The `conversations` payload is `{ "conversations": [ … ] }` (server SSOT `conversations_read.go`, #273), so it decodes to a wrapper DTO whose one field is the row list — **not** a top-level `List<…>`.
- **Timestamps decode to `kotlinx.datetime.Instant` at the DTO** (via an explicit `InstantIso8601Serializer`) — a deliberate departure from `Envelope.ts` staying a `String`: here a domain field (`lastUsedAt`) consumes the instant, so validating the RFC 3339 string at decode keeps the mapper total. One consequence: a malformed timestamp surfaces as **`IllegalArgumentException`** (kotlinx-datetime's `DateTimeFormatException`), **not** a `SerializationException` — a consumer catching decode failures must handle both families (or `Exception`).

Wire-absent domain fields are filled with documented list-tier defaults at the mapping site, never `null`-punned (for `conversations`: `currentSessionId=""`, `sessionHistory=emptyList()`, `isSleeping=false`, `archived=false` — full session/sleep/archive state arrives via the detail + message read paths). These payloads are **decode-only** (server → phone), so they get no encode round-trip test.

`@SerialName("workspace_label") val workspaceLabel: String? = null` (#720) is the one row field copied verbatim rather than list-tier-defaulted — every row, including archived rows, retains its own exact `workspace_label`/`cwd` pair. Explicit wire `null` and an absent legacy key both decode to `null`; the ticket deliberately chose the legacy-omission-is-null reading over `ignoreUnknownKeys`-adjacent "treat missing as malformed" — see [`data-model.md`](data-model.md#conversation).

The second landed in [#317](../codebase/317.md): `MessagePayloadDto` + a closed `WireRole` enum (`MessagePayload.kt`) for the `message` payload, mapped to a domain [`Message`](data-model.md) by `MessagePayloadDto.toMessage(envelope, sessionId)`. It follows #316's per-payload shape with three wrinkles worth folding into the pattern:

> **Premise correction ([#346](../codebase/346.md)).** #317's original framing called this DTO "also the `send_message` response echo" — i.e. assumed `send_message` returns the sender its own persisted `Message` for this mapper to decode. That is **false** against the server SSOT: `send_message`'s only sender-correlated reply is an empty `ack` (success) or an `error` (failure); a `message` envelope is a user-echo to *other* paired devices or the assistant's *later, unsolicited* reply (`in_reply_to: null`), **never** a sender echo. So this mapper has **no** `send_message`-response role — it serves the live-stream / other-device / assistant cases only. The sender's own thread updates via #346's local confirmed-insert. See [[phase4-send-message-acks-not-message-echo]]. (The stale "echo" phrasing still in the code comments and the [#317](../codebase/317.md) ticket file predates this correction and is harmless.)

- **The payload IS the object, not object-wrapped.** No wrapper DTO — `Envelope.payload` decodes straight to `MessagePayloadDto` (server SSOT `internal/protocol/messaging.go`, #272). Object-wrap vs bare is per-payload; check the Go struct, don't assume #316's wrapper.
- **Two validate sites, split across decode and map.** `WireRole` models only the *domain-mappable* roles (`user`/`assistant`), so an unmappable `system` or unknown role is a free `SerializationException` at decode — the technique to reuse whenever a wire enum carries values with no domain target. Meanwhile the message timestamp is the **envelope `ts`** parsed by `Instant.parse` **in the mapper**, so a malformed `ts` throws `IllegalArgumentException` *there*. (Same "a consumer must catch both `SerializationException` and `IllegalArgumentException`" consequence for #312 as #316 — but here the second throw is at *map*, not decode.)
- **A non-wire domain field can be caller-supplied rather than defaulted.** `sessionId` isn't on the `message` payload, so the mapper takes it as a parameter (#312 injects the active session id) instead of giving it a placeholder default like #316's list-tier fields — the value genuinely exists at the call site.

The third landed in [#318](../codebase/318.md): `ConversationResponseDto` (`ConversationResponseDto.kt`) for the **mutation-response** payloads `conversation_created` (reply to `create_conversation`) and `conversation_updated` (reply to `promote_conversation`), mapped to a domain [`Conversation`](data-model.md) by `ConversationResponseDto.toConversation()`. Consumed by the future create/promote mutation slices of `RemoteConversationRepository` (not #312's read flow). Two more wrinkles fold into the pattern:

- **One DTO models multiple `type`-strings when they share a shape.** `conversation_created` and `conversation_updated` carry the *identical* field set (`id`, `is_promoted`, `name?`, `cwd`, `last_used_at`, `workspace_label?` since #720; server SSOT `conversations_write.go`, #274) and differ only in `cwd`↔`name` key order — and kotlinx decodes by name, not position — so one `ConversationResponseDto` decodes both losslessly. The `_created`/`_updated` distinction is a `type`-string routing concern at the `Envelope.type` layer, not a shape concern at decode. It's a **bare object** (like #317, unlike #316's wrapper) and carries no `last_message_ts` — exactly one timestamp, validated at decode like #316 (so the mapper is a total, throw-free field copy — the #316 posture, not #317's map-time throw). It reuses #316's wire-absent-field rule **verbatim** (four list-tier placeholders, cross-referenced, no shared helper extracted — deferred until a third consumer of *that rule* appears).
- **A single-class payload file is named after its class.** ktlint `standard:filename` (spotless) forces a file with exactly one top-level class to be named after it, so the file is `ConversationResponseDto.kt`, **not** the spec's proposed `ConversationResponsePayload.kt`. The siblings escape only because each holds two declarations (#316: wrapper + row DTO; #317: DTO + `WireRole` enum). Name future single-DTO payload files after the DTO. See [[ktlint-filename-rule-single-class]].

### Workspace-label pushes (#721): a new DTO, and `conversation_updated`'s second producer

\#720's `workspace_label` field sits on the DTOs; #721 is what makes a *live* label change reach the phone, by decoding the two frames daemon #2208/#2209/#2210 ship. Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § Renaming a workspace.

- **`WorkspaceUpdatedPayloadDto` (`WorkspaceUpdatedPayloadDto.kt`, own file)** — `{path: String, label: String? = null}`, keyed by **workspace (a daemon-host folder), not conversation id**. `path` is required with no default (an absent one is malformed); `label` is nullable *with* a default so an omitted key and an explicit `"label":null` both decode to `null` under `explicitNulls = false` — the protocol's two spellings of "clear" need no special case. No `toDomain()` mapper: the two scalars are consumed directly by `RemoteConversationRepository.applyWorkspaceLabel`, which relabels every row whose `cwd` equals `path` — byte-for-byte, no trim/normalize/filesystem access, since two paths differing by a trailing separator name distinct workspaces on the daemon. See [`remote-conversation-repository-reads-and-thread-store.md`](remote-conversation-repository-reads-and-thread-store.md#the-repository--one-projection-cold-fan-out).
- **Both `workspace_updated` and `conversation_updated` arrive from two producers, and a client MUST accept both** (the protocol's own wording): a correlated reply to a request this client sent, and an unsolicited push the daemon fans to every *other* interactive-capable connection when a workspace or conversation changes on another client. `workspace_updated` is applied identically either way — host-scoped by construction, since each `RemoteConversationRepository` owns one connection's projection. `conversation_updated`'s correlated half (`promote`'s reply, #348) is unchanged; its unsolicited half — no `in_reply_to`, or one matching no pending request — now folds into the projection by the payload's own `id` (`upsertConversation`), carrying whatever `workspace_label` that frame holds, including a move to a differently labelled workspace. `conversation_created` stays a correlated reply only; the daemon never broadcasts a create.
- **Neither frame carries an `event_id`**, so neither rides the [replay cursor](replay-cursor.md) — a client disconnected mid-rename or mid-clear has no gap to replay; it reads the current label off its next `conversations` snapshot instead, same as any other live-only push.

### Outbound request encoders + the `ack`/`error` correlated-reply models (#346)

The three payloads above are all **decode-only** (server → phone). [#346](../codebase/346.md)'s `sendMessage` adds the first half of the **other** direction — outbound **request encoders** and the generic **correlated-reply** models that complete the request↔reply round-trip. These are built **inside the mutation tickets** (#346/#347/#348), not as a decode-only mapping slice — there is no "request-mapping" slice; the v2 wire splits decode (shared inbound slices #316/#317/#318) from encode (per-mutation) on a direction axis. See [[phase4-request-encoders-live-in-mutation-tickets]].

- **`SendMessagePayloadDto` (`MessagePayload.kt`)** — the second **encode-only request DTO** (after #313's `BackfillSincePayloadDto`): `@SerialName` snake_case, Go-struct field order, all three fields (`conversation_id`, `message_id`, `text`) required. Encode-only request DTOs get no decode round-trip and no domain mapper — they're built and `encodeToJsonElement`'d straight into the request `Envelope.payload`. `message_id` is **client-generated** (a minted UUID) — distinct from the request *envelope* id used for `ack`/`error` correlation. Wire SSOT: server `internal/protocol/messaging.go` `SendMessagePayload` (#272).
- **`CreateConversationPayloadDto` (`CreateConversationPayloadDto.kt`, [#347](../codebase/347.md))** — the third **encode-only request DTO**, the `create_conversation` request `createDiscussion` sends. In its **own file** (ktlint single-public-type rule — symmetric with the decode-side `ConversationResponseDto.kt`, **not** added to `MessagePayload.kt`). Models **only the two fields the create flow sends**: `@SerialName("is_promoted") isPromoted: Boolean = false` (always sent `false` — `createDiscussion` only ever creates *unpromoted* discussions; promotion is the separate #348 flow) and `cwd: String? = null`. **`name` is intentionally unmodeled** — discussions are *server-auto-named*, so the create flow never sends a `name`; the reply comes back `name: null`. This is the encode-only discipline: model what is *sent*, not the full decode surface (#318's job) — the KDoc forbids "add `name` for completeness". **Load-bearing encoding:** under `MobileJson` (`explicitNulls = false`) a null `cwd` is **omitted** (not `"cwd":null`), so `createDiscussion(null)` → `{"is_promoted":false}`; the server's `*string` `Cwd` (no `omitempty`) decodes an absent key identically to `null`, meaning "server assigns the scratch cwd" (#274 sanctions filling server-side defaults when the field is absent). Do not force an explicit `"cwd":null`. Wire SSOT: server `internal/protocol/conversations_write.go` `CreateConversationPayload` (#274). Built inside the #347 mutation slice, not a shared mapping slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`PromoteConversationPayloadDto` (`PromoteConversationPayloadDto.kt`, [#348](../codebase/348.md))** — the fourth **encode-only request DTO**, the `promote_conversation` request `promote` sends to turn a scratch discussion into a named channel. In its **own file** (ktlint single-public-type rule — symmetric with `CreateConversationPayloadDto.kt`). All **three fields are required/non-null** `String`: `@SerialName("conversation_id") conversationId`, `name`, `cwd` — declaration order mirrors the Go struct `PromoteConversationPayload` (#274). **Contrast `CreateConversationPayloadDto`'s optional `cwd`:** here `cwd` is non-null and always sent (there is no `explicitNulls` elision to reason about — the encoded payload always has all three keys), and the KDoc explicitly forbids relaxing it to nullable. The caller resolves a null `workspace` to the conversation's existing cwd from the read projection *before* encoding (see [`remote-conversation-repository.md` § `promote`](remote-conversation-repository-send-create-promote-rename.md#promoteconversationid-name-workspace--the-third-mutation-348)), so the wire always carries a concrete `cwd`. Encode-only — model what is *sent*, not the full decode surface (#318's `ConversationResponseDto` already models the `conversation_updated` reply). Wire SSOT: server `internal/protocol/conversations_write.go` `PromoteConversationPayload` (#274). Built inside the #348 mutation slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`RegisterPushTokenPayloadDto` (`RegisterPushTokenPayloadDto.kt`, [#359](../codebase/359.md))** — the fifth **encode-only request DTO**, the `register_push_token` request `RemoteConversationRepository.registerPushToken` sends to tell the paired daemon where to push a wake notification. In its **own file** (ktlint single-public-type rule — symmetric with the sibling DTOs). All **three fields required/non-null** `String`: `platform`, `token`, `@SerialName("device_name") deviceName` — declaration order mirrors the Go struct `RegisterPushTokenPayload` (spec #275). **`platform` is a plain `String`, not an enum** — the value is the constant `"fcm"` (Android) supplied at the call site, and an encode-only DTO models only what it sends (the server also keeps `Platform` a `string` and accepts `"apns"` for iOS, out of scope). `device_name` equals `HelloClientPayload.deviceName` (the value `hello` already sends); the server dedupes the `(platform, token, device_name)` triple, so the client does no dedupe. The `token` is a sensitive FCM routing credential held only transiently for one round-trip and **never logged** (the #346 no-secrets posture). Like `send_message`, the reply is a bare `ack` — **no response DTO modeled**. The first **device-concern** wire request (push registration is not a conversation operation — see [`remote-conversation-repository.md` § `registerPushToken`](remote-conversation-repository-workspace-and-push.md#registerpushtokentoken--the-device-concern-push-registration-359)). Wire SSOT: server `internal/protocol/push.go` `RegisterPushTokenPayload` (#275), golden `internal/protocol/testdata/register_push_token.json`, `protocol-mobile.md` § `register_push_token`. Built inside the #359 slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`ModalAnswerPayloadDto` + `ModalCancelPayloadDto` (`ModalOutboundPayloads.kt`, [#438](../codebase/438.md))** — the sixth and seventh **encode-only request DTOs**, the `modal_answer` / `modal_cancel` controls `RemoteConversationRepository.answerModal` / `cancelModal` send to answer or cancel a permission/choice modal (the outbound mirror of #437's decode DTOs — see [`remote-conversation-repository.md` § `answerModal` / `cancelModal`](remote-conversation-repository-live-stream-and-modals.md#answermodal--cancelmodal--the-v2-modal-answercancel-control-send-438)). **Both `@Serializable internal`** (the tightest scope — only the repo references them; same-module tests inspect the on-wire JSON, not the class) and **co-located in one file** (two top-level types ⇒ the ktlint single-public-type rule is exempt, the [`SnapshotPayload.kt`](#the-screen-snapshot-exchange-374) multi-type precedent). All fields required/non-null `String`, snake_case `@SerialName`, Go-struct order: `ModalAnswerPayloadDto = {modal_id, option_id, answer_token}`, `ModalCancelPayloadDto = {modal_id}`. `modal_id`/`option_id` are **opaque, caller-supplied** (decoded by #437, echoed verbatim — the DTO neither parses nor trusts them; the daemon validates). `answer_token` is a **client-minted idempotency key** (uniqueness + stability matter, *secrecy is an explicit non-goal* — pyrycode#701), derived as a pure length-prefixed encoding of `(modal_id, option_id)` so a resend dedups; **not** authorization (that is `modal_id` validity #706 + the per-device gate #702). `modal_cancel` carries **no** token (cancel is not idempotency-keyed). Like `send_message`/`register_push_token`, the reply is a bare `ack` (or `error`) — **no response DTO modeled**. **Never logged** — the modal may name a sensitive command/path. Wire SSOT: server pyrycode#701 (`modal_answer`/`modal_cancel`), `protocol-mobile.md` § Modal (v2). Built inside the #438 slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`ErrorPayload(code, message, retryable)` (`MobileWireModels.kt`)** — the **decode-only** payload of an `error` envelope, the failure reply correlated to a request via `Envelope.inReplyTo`. Single-word wire names, no `@SerialName`. The server also emits `retry_after_s`; it is **intentionally not modeled** — `MobileJson`'s `ignoreUnknownKeys = true` tolerates it on decode (add it as a nullable `@SerialName("retry_after_s")` field if a retry-UI ever needs it). Wire SSOT: server `internal/protocol/handshake.go` `ErrorPayload`.
- **`RelayErrorException(code, retryable, message)` (`MobileWireModels.kt`)** — **not** a wire model; the thrown form of an `ErrorPayload` whose `code` is not a contract-mapped domain error. Carries the structured fields so a ViewModel can branch on `retryable` and surface `code`/`message`. The `conversation.not_found` code maps to `IllegalArgumentException` instead (cross-impl parity with the fake's unknown-conversation throw); every other code — and a malformed/undecodable `error` payload — surfaces as this. Its `message` is the server's human-facing text only, **never** the user's message content.
- **No `AckPayload` model — deliberate.** A success `ack` payload is empty `{}` and is **never decoded**; correlation is purely on `Envelope.inReplyTo`. An unused `AckPayload` would be dead code, so it is omitted (a conscious choice, not an oversight).

The `ErrorPayload` / `RelayErrorException` / registry primitives are the **shared correlation
infrastructure** #347 (`createDiscussion`) and #348 (`promote`) reuse verbatim — each mutation adds only
its own `create_conversation` / `promote_conversation` **request encoder** (built in its own ticket,
above) and its own success-`type` handling. Their *success* reply is **not** a bare `ack` (that's
`send_message`'s) but a typed `conversation_created` / `conversation_updated` envelope, so those tickets
route that reply through the same completion arm and **decode #318's `ConversationResponseDto`** from the
payload (in the caller's coroutine, never the collector). Both **landed**: [#347](../codebase/347.md) for
`conversation_created` (`CreateConversationPayloadDto`; the typed reply decoded into the returned
`Conversation`) and [#348](../codebase/348.md) for `conversation_updated` (`PromoteConversationPayloadDto`;
the **same** `ConversationResponseDto` decodes both replies) — so all three #314 mutations now ship live.
The corrected wire premise that motivates all of this — `send_message` returns an `ack`/`error`, **not** a
`Message` echo — is documented above (the #346 premise-correction callout) and in
[[phase4-send-message-acks-not-message-echo]].

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

`SessionSettingsPayloads.kt` (already holding the write half's `SetSessionSettingsPayloadDto`/
`SessionSettingsUpdatedPayloadDto` — #543) gains the **read** half: `RequestSessionSettingsPayloadDto`
(encode-only `request_session_settings` request, one required `@SerialName("conversation_id")` key) and
`SessionSettingsPayloadDto` (decode-only `session_settings` reply). Wire SSOT:
`../pyrycode/docs/protocol-mobile.md` § `request_session_settings` / § `session_settings`. Consumed by
[`RemoteConversationRepository.observeSessionSettings`](remote-conversation-repository-conversation-writes.md#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590).

- **`SessionSettingsPayloadDto` models seven fields, deliberately not eight — the eighth (`effective_effort`)
  never reaches this DTO.** Every modeled field (`session_id`, `model`, `effort`, `yolo`, `permission_mode`,
  `used_tokens`, `window_tokens`) is **required, with no default** — the wire emits all seven
  unconditionally, so a missing key is a malformed reply rather than a silently-defaulted one, and each
  zero (`permission_mode: ""` especially) is a *read* answer, not a manufactured one. `used_tokens`/
  `window_tokens` decode as `Long` (the pyrycode#720 64-bit-Go-`int` width trap, same posture as `HistoryEntry.id`).
- **The reply is decoded in two steps, and the order is load-bearing — the reason it is not one DTO.**
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
  [the repository doc](remote-conversation-repository-conversation-writes.md#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590).
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

`dropped_models` is carried **verbatim** into `ModelMenu.droppedModels`, never recomputed from
`rows.size` — the producer's entry cap is daemon-side and not a wire constant, so `models.size +
droppedModels` is the menu's true size and nothing here may derive one from the other.

**Untrusted-string obligation.** `resolvedModel`, `value`, `displayName` and every element of
`effortLevels` are claude-authored text that crossed the subprocess trust boundary; the daemon bounds
them but does not sanitize them — no control character or terminal escape is stripped anywhere on this
path. Nothing on this path trims, folds, normalises, re-encodes or validates one, and nothing keys off
them: the retention is keyed by `conversation_id` alone (see
[the repository doc](remote-conversation-repository-live-stream-and-modals.md#the-model-list-inbound-arm--the-connection-scoped-retention-791)),
never by anything derived from row text. `value` in particular is **never parseable** — it is an alias
(`sonnet`), a bracketed variant (`opus[1m]`) or `default`, so no family may be derived by splitting it and
it must never be presented as a version. The obligation is stated as a KDoc on the **domain** type
`ModelMenuRow` in `ConversationRepository.kt` (a plan security-review finding), not only on the wire DTO —
the render consumer (#649) will open the domain type and never the DTO. The render-side
sanitization/length-bound obligation itself belongs to #649; this slice renders nothing and holds the
strings inert.

**A `MobileJson` lesson, not a `model_list`-specific one.** `isLenient` is off, so an *unquoted* number
where a `String` is declared fails the frame (`conversation_id: 17`, a numeric `resolved_model`) — but a
*quoted* number where the `Int` count (`dropped_models`) is declared **coerces** rather than rejects:
`"dropped_models":"40"` decodes as `40`, because the tree decoder reads a primitive's content and parses
it regardless of the JSON token's quoting. This is a property of the shared `MobileJson` codec that every
sibling payload decodes through, not a `model_list` decision, so it is not worked around on this one frame
— it is pinned by `ModelListPayloadsTest.quotedNumberForDroppedModels_coercesRatherThanFailing`. The
coercion touches only count-typed fields; every claude-authored **string** field still fails the frame
when wrong-typed, which is the case the security posture actually rests on. Worth checking before writing
a "rejects a wrong-typed numeric field" test against this codec on any future payload — the rejection is
real for a `String` target, not for an `Int` one.

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
on-demand ask](remote-conversation-repository-live-stream-and-modals.md#the-on-demand-ask--request_model_list-792),
not duplicated here.
