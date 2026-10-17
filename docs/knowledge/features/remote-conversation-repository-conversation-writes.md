# Remote conversation repository — the Phase 4 `ConversationRepository` — session settings, archive, delete and workspace change

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## Agent switching (#1117)

`switchAgent` delegates through `ConversationCommands` to the connection-owned
`SwitchAgentCommands`. Both negotiated `interactive` and `multi_agent` are
required before sending; missing either returns typed `Unsupported` without a
send. Required strings preserve empty model, and `MobileJson` omits only null
effort. Keep field validation and refusal semantics linked to the
[daemon's `switch_agent` contract](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#switch_agent).

Success is uncorrelated, so `RelayRequests.sendAndAwaitReply` cannot implement
this command. The helper shares `RelayRequests.nextRequestId` but registers its
own pending target before sending. The ordinary `conversation_updated` arm fully
decodes and folds the row before offering it for confirmation. Only explicit
original wire strings for `id` and the requested `agent` confirm that target.
A cached Claude value cannot prove a switch back to Claude: omitted agents are
preserved by ordinary updates, and unknown agents map to Claude for display.
Other conversations, omitted/null/unknown/different agents and malformed rows
cannot confirm; neither reset progress, session transitions nor plain acks settle
the call. No optimistic fold is added. A confirmed commitment stays successful
when a late cleanup error arrives.

Only a matching `error.in_reply_to` settles a refusal. Switches bypass generic
`RelayRequests.mapError`, which can expose daemon message text, and classify by
code into [typed failures](conversation-repository-shape.md#shape), preserving
valid decoded retryability. Unknown codes and malformed errors yield sanitized
fallbacks; unrelated errors cannot settle a switch. Failures retain no daemon
message, request values, payload or caught cause. Switch logs contain only
`event=switch_agent` and static outcome names. An offline operational failure
retains the old binding but may follow stored handover or dropped backlog; it
must not be described as a rollback.

[Connection cleanup](remote-conversation-repository-state-errors-and-handoff.md#state--concurrency-model)
settles pending work without a deadline or retry: outgoing wrap-up can take about
90 seconds. `RemoteConversationRepositorySwitchAgentTest` covers both directions,
explicit wire identity, row visibility before completion, refusal sanitization
and pending-call lifecycle. `SwitchAgentDelegationTest` covers the stable and fake
seams. [#1118](https://github.com/pyrycode/pyrycode-mobile/issues/1118) owns the
picker caller, operator confirmation and live-flow proof.

## `setSessionSettings(sessionId, model, effort, yolo)` — the fifth mutation, first session-scoped ([#543](../codebase/543.md))

Applies the operator's model / effort / YOLO change to a **running session** over v2
`set_session_settings`, and returns only after the daemon's ack. [#543](../codebase/543.md) is `rename`
([#530](../codebase/530.md)) **minus the state fold**: session settings live in the ViewModel (#544's
scope), not this repository's projection, so there is nothing to `upsertConversation`. It is also the
**first session-scoped mutation** — every prior one (`sendMessage`, `createDiscussion`, `promote`,
`rename`, `startNewSession`) keys off `conversationId`; this one takes `sessionId`, sourced by #544 from
`Conversation.currentSessionId`. The body now lives on `SessionSettingsCommands` (#916,
`data/repository/SessionSettingsCommands.kt`, alongside `observeSessionSettings` and the system-prompt
pair below); the repository's `override suspend fun setSessionSettings` is a one-line hand-off.

```kotlin
// SessionSettingsCommands
suspend fun setSessionSettings(
    sessionId: String, model: String?, effort: String?, yolo: Boolean?,
) {
    val request = Envelope(
        id = requests.nextRequestId(),
        type = TYPE_SET_SESSION_SETTINGS, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            SetSessionSettingsPayloadDto(sessionId = sessionId, model = model, effort = effort, yolo = yolo),
        ),
    )
    val reply = requests.sendAndAwaitReply(request)   // throws on server `error` / not-Open before any decode
    MobileJson.decodeFromJsonElement<SessionSettingsUpdatedPayloadDto>(reply)  // validation only, discarded
}
```

- **Presence contract, not a full settings object.** `model` / `effort`: `String? = null`,
  `yolo: Boolean? = null` — under `MobileJson`'s `explicitNulls = false`, a `null` argument is **omitted**
  from the wire payload ("leave unchanged"), while a non-null value — including `false` / `""` — is
  **always** sent. This mirrors the server struct's pointer + `omitempty` fields exactly (an omitted
  `yolo` can never masquerade as a sent `false`), so `setSessionSettings(id, model = "opus")` sends only
  `{"session_id": "…", "model": "opus"}` — a two-key payload, not three.
- **No fold — the structural delta from every prior #314/#530 mutation.** The reply,
  `session_settings_updated`, carries only `{session_id}` (an echo of the input, not the applied
  settings) — there is nothing new to project. The decode through `SessionSettingsUpdatedPayloadDto`
  exists purely to **validate the reply shape** (a malformed ack throws the #318-posture decode
  exception before returning); the decoded value is discarded and `setSessionSettings` returns `Unit`.
- **The reply type is new, not reused.** Unlike `rename`/`promote` (which both ride the pre-existing
  `conversation_updated` arm), `session_settings_updated` is a **new** verb added to the same
  reply-completion `when` arm (`TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED,
  TYPE_SCREEN_SNAPSHOT, TYPE_SESSION_SETTINGS_UPDATED -> …`). Correlation still rides `inReplyTo`;
  `complete` is still idempotent; an unmatched `inReplyTo` is still a harmless no-op (the daemon never
  broadcasts this reply).
- **No IAE-crash path — simpler error posture than `rename`.** The unhosted-session error code is
  `session.not_found`, **not** `conversation.not_found`, so `RelayRequests.mapError` routes it through the
  else-branch to a swallowable `RelayErrorException` carrying `.code`, never `IllegalArgumentException`. Every server
  error this verb can produce (`session.not_found`, `protocol.malformed` for an invalid model/effort the
  daemon re-validates, `server.binary_offline`) is a `RelayErrorException`; not-connected is
  `IllegalStateException` via `RelayRequests.sendAndAwaitReply`, same as every sibling verb.
- **`model` / `effort` vocabulary is the caller's concern, not this method's.** The strings are forwarded
  **verbatim** (as `rename` forwards the dialog's name); the daemon re-validates
  (`validModel`/`validEffort`) and rejects an invalid value with `protocol.malformed` before persisting.
  #544 maps its `Model`/`Effort` enums to wire strings before calling.

## `observeSessionSettings(conversationId)` / `refreshSessionSettings(conversationId)` — the settings read, counterpart to `setSessionSettings` (#590)

Moved to [Remote conversation repository — session-settings read](remote-conversation-repository-session-settings-read.md#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590) to keep this document under the docs guard's byte cap.

## `archive(conversationId)` / `unarchive(conversationId)` — the sixth and seventh mutations (#549)

Archives or restores an existing conversation over v2 `archive_conversation` / `unarchive_conversation`
(server pyrycode#881), replacing the two `UnsupportedOperationException` throws these methods carried since
\#312. The body now lives on `ConversationCommands` (#914, `data/repository/ConversationCommands.kt`); the
repository's `override suspend fun archive` / `unarchive` are one-line hand-offs. On `ConversationCommands`,
both public commands delegate to one private helper parameterized by wire type — the mobile mirror of the
server's single handler registered under both verbs:

```kotlin
// ConversationCommands
suspend fun archive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_ARCHIVE_CONVERSATION)
suspend fun unarchive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_UNARCHIVE_CONVERSATION)

private suspend fun sendArchiveToggle(conversationId: String, type: String) {
    val request = Envelope(
        id = requests.nextRequestId(), type = type, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(ArchiveConversationPayloadDto(conversationId = conversationId)),
    )
    requests.sendAndAwaitConversation(request)     // #2213: collector decodes, folds and captures; no return value needed (interface is Unit)
}
```

> Before [#2213](remote-conversation-repository-state-errors-and-handoff.md#state--concurrency-model), this
> body called `requests.sendAndAwaitReply(request)` and then decoded + `upsertConversation`'d the raw reply
> itself, the same shape `rename`/`promote` originally shipped. See
> [Send, create, promote, rename § `createDiscussion`](remote-conversation-repository-send-create-promote-rename.md#creatediscussionworkspace--the-second-mutation-347)
> for why that caller-side re-fold was retired.

- **One shared request DTO for both verbs.** `ArchiveConversationPayloadDto` carries only
  `conversation_id` — archive and restore are a symmetric toggle of one durable flag (same shape, same
  handler, same not-found/reply behaviour), so pyrycode#881 defined one payload server-side for the pair
  and this mirrors that; `ConversationCommands` disambiguates by the `Envelope.type` string passed into
  `sendArchiveToggle`, not by the payload shape.
- **The reply is `conversation_updated`**, the same collector arm `rename`/`promote` already use — no
  `onInbound` change needed. Decoded through the same #318 `ConversationResponseDto`, so a malformed reply
  fails inside that arm before `ConversationListProjection.upsertConversation` runs there, same as every
  other mutation, and `sendAndAwaitConversation` throws its own generic `SerializationException` in this
  caller.
- **The load-bearing change is to the shared decode boundary, not these methods.** Before #549,
  `ConversationResponseDto` didn't decode any archived field and `toConversation()` hardcoded
  `archived = false` — every mutation reply, including this one, would have folded a permanently-unarchived
  conversation. #549 adds a **defaulted** field, `@SerialName("is_archived") val isArchived: Boolean =
  false`, and wires it in (`archived = isArchived`). Defaulted rather than required because pyrycode#881
  extended only the `conversation_updated` payload, not `conversation_created` (#347) — an absent key
  (create/promote replies) still correctly decodes to `false`, so the three pre-existing callers
  (`createDiscussion`, `promote`, `rename`) need no changes. As a side effect this also fixes a **dormant**
  bug in the already-shipped `rename`: since pyrycode#881 its reply carries `is_archived` too, which
  `rename` was silently dropping until this DTO change (undetectable before now — nothing could archive a
  conversation yet).
- **Idempotent.** pyrycode#881 replies `conversation_updated` with the unchanged state on a
  re-archive/re-unarchive; `ConversationListProjection.upsertConversation` replaces the entry with an equal value — a benign re-emit,
  no special-casing needed.
- **Contrast #549's `is_archived` with #720's `workspace_label`.** #549 needed a defaulted field because
  the server genuinely omits `is_archived` from some reply shapes (`conversation_created`/`promote`), so a
  naive required field would have broken those callers. #720 (`workspaceLabel` retention, nullable, on the
  same `ConversationResponseDto`) looked like it could have the identical gap — any of `rename`/`promote`/
  `archive`/`unarchive`/`change_workspace` sharing this mapper and one of them omitting `workspace_label`
  would silently clear a known label through this same complete-record `upsertConversation`. It does not:
  the canonical `../pyrycode/docs/protocol-mobile.md` states the key is present on every frame of both the
  `conversations` and `conversation_created`/`conversation_updated` kinds (nullable, never omitted), so no
  production change was needed here — verify that guarantee against the protocol doc directly for any
  future field added to this DTO, rather than assuming a defaulted field is always compensating for a real
  server gap.
- **No return value** — unlike `rename`/`promote` (which return the server-authoritative `Conversation`),
  the `ConversationRepository` contract's `archive`/`unarchive` return `Unit`, so the decoded conversation
  is folded but discarded.
- **`mutationsSupported` is untouched, stays `false`** — it gates the LIVE UI affordance ([#507](../codebase/507.md))
  and the [#551](https://github.com/pyrycode/pyrycode-mobile/issues/551) e2e; wiring the data path doesn't
  flip it. (At the time of this ticket `changeWorkspace` was still the remaining throwing stub; it was
  wired by [#560](../codebase/560.md).) Surfacing (no crash, no silent no-op) is
  [#550](https://github.com/pyrycode/pyrycode-mobile/issues/550)'s concern.
- **Out of scope, flagged not fixed:** the list-read path (`ConversationsPayload` /
  `ConversationSummaryDto.toConversation()`) also hardcodes `archived = false` and drops the `is_archived`
  pyrycode#880 added to `ConversationSummary` — a conversation archived elsewhere shows as active in a
  fresh list snapshot until a verb reply re-folds it locally. Not part of this slice; see
  [`../codebase/549.md`](../codebase/549.md) § Lessons learned.

## `setMuted(conversationId, muted)` — the mute-flag write ([#1000](https://github.com/pyrycode/pyrycode-mobile/issues/1000))

Sets or clears a conversation's mute flag over v2 `set_conversation_muted` (server pyrycode#2572). The
body lives on `ConversationCommands` (`data/repository/ConversationCommands.kt`, beside `sendArchiveToggle`);
the repository's `override suspend fun setMuted` is a one-line hand-off.

```kotlin
// ConversationCommands
suspend fun setMuted(conversationId: String, muted: Boolean) {
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_SET_CONVERSATION_MUTED, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(SetConversationMutedPayloadDto(conversationId = conversationId, muted = muted)),
    )
    requests.sendAndAwaitConversation(request)
}
```

- **`sendArchiveToggle`'s shape with a payload that carries a value, not just an id.** Same encode → the
  collector decodes the correlated `conversation_updated` through #318's `ConversationResponseDto`, folds it
  and captures it → `sendAndAwaitConversation` returns that capture (#2213); a failure (`error`, not-Open
  session, or a malformed reply) leaves the fold unrun, so `ConversationListProjection` keeps its previous
  value (AC #3).
- **`SetConversationMutedPayloadDto.muted` is a non-nullable `Boolean` with no Kotlin default**, so `false`
  is always on the wire — the daemon refuses a frame with `muted` absent or explicit `null` as
  `protocol.malformed` rather than reading a missing key as `false`. Contrast
  `setSystemPromptPayload` above, which needs a hand-built `buildJsonObject` to force an explicit `null`
  through `MobileJson`'s `explicitNulls = false`; `setMuted` needs no such workaround because neither of
  its two fields is ever meant to be absent.
- **The daemon pushes the same `conversation_updated` record a second time, uncorrelated, to the
  requester** (its ordinary broadcast-on-change behaviour, not special-cased for this verb). That push
  lands through the existing uncorrelated `conversation_updated` fold, not through this method; because
  both frames upsert the same conversation by id, the two folds leave one row holding the new value,
  never a duplicate or a stale second entry.
- **No return value** — like `archive`/`unarchive`, the `ConversationRepository` contract's `setMuted` is
  `Unit`; the decoded conversation is folded but discarded.
- **Errors:** `conversation.not_found` → `IllegalArgumentException`, any other server code →
  `RelayErrorException`, both via the existing `RelayRequests.mapError` — the same mapping `archive`/
  `rename`/`setSystemPrompt` use. The fake throws the same `IllegalArgumentException` for an unknown id, so
  a caller (#1001) can treat both repositories' refusals identically.
- **Testing gotcha carried into `RemoteConversationRepositoryMuteTest` (its own small sibling pump, the
  `RemoteConversationRepositorySystemPromptTest` pattern):** `RemoteConversationRepository` sends its own
  `list_conversations` the first time `observeConversations` is collected. A test that collects the list
  and then asserts `pump.sent.single()` fails with "more than one element" — that first frame is still in
  `pump.sent`. Filter by `Envelope.type` instead of asserting on the whole list, and when a case needs "no
  re-list happened," count matching frames before and after rather than asserting emptiness of the whole
  sent log.

## `setPinned(conversationId, pinned)` — the host-owned pin write (#2214, server pyrycode#3538)

Pins or unpins a conversation over v2 `set_conversation_pinned`, `setMuted`'s round trip with a second
boolean name. The body lives on `ConversationCommands` (beside `setMuted`); the repository's `override
suspend fun setPinned` is a one-line hand-off, and `StableConversationRepository.setPinned` forwards both
arguments verbatim to `live`.

```kotlin
// ConversationCommands
suspend fun setPinned(conversationId: String, pinned: Boolean) {
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_SET_CONVERSATION_PINNED, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(SetConversationPinnedPayloadDto(conversationId = conversationId, pinned = pinned)),
    )
    requests.sendAndAwaitConversation(request)
}
```

- **Writes nothing itself — `setMuted`'s posture, not a new one.** The command only sends and awaits; the
  correlated `conversation_updated` is decoded and folded by the existing collector arm before
  `sendAndAwaitConversation` resumes, and the host's matching uncorrelated push upserts the same record by
  id afterward. Both folds land through `ConversationListProjection.upsertConversation`, so a pending
  request changes nothing, and the two deliveries for one write leave one row holding the host's confirmed
  `Conversation.pinnedAt` (#2213's decode), never a duplicate or a stale second entry. A repeat pin on an
  already-pinned conversation keeps the host's *original* `pinned_at` — the host, not this method, decides
  that; the command does not special-case "already pinned" and does not need to.
- **`SetConversationPinnedPayloadDto.pinned` is a non-nullable `Boolean` with no Kotlin default**, so
  `false` is always on the wire — same discipline as `SetConversationMutedPayloadDto.muted`: the daemon
  refuses an absent or explicit-`null` `pinned` as `protocol.malformed` rather than reading a missing key
  as `false`.
- **No return value** — like `setMuted`, the `ConversationRepository` contract's `setPinned` is `Unit`;
  the decoded conversation is folded but discarded.
- **Errors mirror `setMuted` exactly:** `conversation.not_found` → `IllegalArgumentException`, any other
  server code (an older host's `protocol.unknown_type`, or `conversation.not_promoted`) →
  `RelayErrorException`, both via the existing `RelayRequests.mapError`; a malformed reply is a
  `SerializationException` (⊂ `IllegalArgumentException`) from `sendAndAwaitConversation`'s own decode, and
  no connection is `IllegalStateException` before any frame is sent. Every one of these leaves the prior
  confirmed record — including unrelated fields (mute, archive stamp, label, agent) — untouched; nothing
  about the write is logged (the security review's one SHOULD FIX: no `RelayLog` call here carries the
  conversation id, the requested boolean, or a reply's `pinned_at`).
- **Host-scoped, like every other mutation on this class.** A write against one connection's pump cannot
  touch another host's copy of a conversation sharing the same id — the per-connection
  `RemoteConversationRepository` instance and its own `ConversationListProjection` make this structural, not
  a checked property (`setPinned_onOneHostLeavesTheOtherHostsSameIdUntouched`).
- **No UI controls in this slice** — #2213 supplies the decode and persistence this write relies on; a
  pin/unpin affordance is a later ticket's concern.
- **`RemoteConversationRepositoryPinTest`** extends #2213's pin fixtures: `setPinned` joins `commandNames`/
  `executeCommand` so the shared queued-reply, cancelled-caller and malformed-reply probes
  (`queuedCommandRepliesCannotOverwriteLaterConfirmedPins`, `cancelledCommandsKeepInboundConfirmedPins`,
  `malformedCommandRepliesFailWithoutPublishing`) cover it alongside every other mutation, plus
  `setPinned`-specific cases for the exact wire verb and explicit boolean, the pending-request/no-fold
  window, reply-and-duplicate-push ordering, refusals (`protocol.unknown_type`, `conversation.not_promoted`,
  `conversation.not_found`, a malformed reply) preserved until a fresh snapshot, a disconnected session, and
  host isolation. `StableConversationRepositoryTest.setPinned_whenLive_delegatesVerbatim` and the
  no-live-repository throw cover the facade; `FakeConversationRepositoryTest`'s
  `setPinned_repeatPinKeepsTimestamp_unpinClears` and `setPinned_onUnknownId_throwsAndChangesNothing` cover
  the fake.

## `delete(conversationId)` — the eighth mutation, first REMOVE-shaped one ([#532](../codebase/532.md))

Permanently deletes an existing conversation over v2 `delete_conversation` (server pyrycode#822, PR #884),
replacing the interface-default throw this method carried since #312. Diverges from every prior mutation
in two ways: the reply is a **dedicated** ack (`conversation_deleted`, bare `{id}`), not a reused
`conversation_updated`; and the fold **removes** rather than upserts. The body now lives on
`ConversationCommands` (#914, `data/repository/ConversationCommands.kt`); the repository's `override
suspend fun delete` is a one-line hand-off.

```kotlin
// ConversationCommands
suspend fun delete(conversationId: String) {
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_DELETE_CONVERSATION, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(DeleteConversationPayloadDto(conversationId = conversationId)),
    )
    val reply = try {
        requests.sendAndAwaitReply(request)
    } catch (alreadyGone: IllegalArgumentException) {
        // RelayRequests.mapError maps conversation.not_found → IAE and nothing else — the delete contract
        // is *tolerant* of unknown ids, so already-gone converges as success (the deliberate divergence
        // from rename/archive's IAE-crash-on-not-found). Catch is scoped to the await only.
        removeConversation(conversationId)
        return
    }
    MobileJson.decodeFromJsonElement<ConversationDeletedPayloadDto>(reply)  // #318 shape-validate, discard
    removeConversation(conversationId)                                     // REMOVE, only after a well-formed ack
}
```

- **New reply type, so the inbound demux needed a real change — the load-bearing delta from rename/
  archive.** `rename`/`archive`/`unarchive` all reuse the pre-existing `conversation_updated` success arm.
  Delete's reply is genuinely new (`conversation_deleted`), so `onInbound`'s correlated-reply `when` arm
  (the same one `TYPE_ACK` / `TYPE_CONVERSATION_UPDATED` / `TYPE_SESSION_SETTINGS_UPDATED` share) had to
  add `TYPE_CONVERSATION_DELETED`. Skipping this would leave the ack unrouted (falls to the `else`
  no-op arm), so the pending waiter [`RelayRequests`](remote-conversation-repository-state-errors-and-handoff.md)
  holds would never complete and `RelayRequests.sendAndAwaitReply` would
  suspend until connection teardown — a silent-forever no-op, not a crash.
- **The ack carries `id`, not `conversation_id` — and its value is discarded.** `ConversationDeletedPayloadDto`
  decodes `{id}` purely to validate the reply shape (the `SessionSettingsUpdatedPayloadDto` posture); the
  command removes the id it *sent*, never the id the reply echoes, so a lying relay cannot redirect the
  removal to a different conversation.
- **REMOVE, not upsert — the private `removeConversation` helper clears both remaining projections.**
  Since #913/#912 the list and last-message streams live on `ConversationListProjection` and the thread on
  `ThreadProjection`; `removeConversation` (moved with `delete` as its only caller, #914) calls
  `conversationList.remove(conversationId)` and `threadProjection.remove(conversationId)`. The remote holds
  these as separate projections (unlike the fake's unified `state: Map<id, ConversationRecord>`, where
  removing one map entry empties list/messages/last-message at once), so a list-only removal would leave
  `observeMessages`/`observeLastMessage` still serving a hard-deleted conversation's rows — a contract
  violation of the interface's documented three-stream post-condition. Both projections' removal is
  idempotent on an absent id, so a repeat or already-gone delete re-emits nothing new.
- **`conversation.not_found` converges as success — do not clone rename/archive's IAE-crash path.** The
  `ConversationRepository.delete` contract is explicitly tolerant of unknown ids (unlike archive/rename,
  which throw on unknown ids and rely on the [`#490`](../codebase/490.md) guard's deliberate
  IAE-doesn't-catch crash posture). Delete catches the same `RelayRequests.mapError`-produced `IllegalArgumentException`
  locally and converges by removing the id and returning normally — an already-deleted id is success, not
  a bug signal. The catch is scoped to `RelayRequests.sendAndAwaitReply` only (not the decode line below it), so a
  malformed-ack `SerializationException` (⊂ `IllegalArgumentException`) still propagates and is never
  mis-read as "already gone."
- **No return value** — the `ConversationRepository` contract's `delete` is `Unit`, like archive/unarchive.
- **`mutationsSupported` stays `false`, untouched** — same family posture as archive/unarchive; the
  affordance is dormant-but-ready. The operator-facing rung-3 e2e is
  [#554](https://github.com/pyrycode/pyrycode-mobile/issues/554) (Inbox, blocked by this ticket).

## `changeWorkspace(conversationId, workspace)` — the ninth mutation, last stub filled ([#560](../codebase/560.md))

Changes an existing conversation's workspace over v2 `change_workspace` (server pyrycode#823), replacing
the `UnsupportedOperationException` throw this method carried since #312 — and, per [#549](../codebase/549.md)'s
note naming it "the remaining throwing sibling," the **last** stub on this class. "Workspace" **is** the
conversation's `cwd`; there is no separate workspace-id concept. Byte-for-byte the [`rename`](remote-conversation-repository-send-create-promote-rename.md#renameconversationid-name--the-fourth-mutation-530)
shape with a `cwd` payload instead of `name`. The body now lives on `WorkspaceCommands` (#916,
`data/repository/WorkspaceCommands.kt`, beside `createWorkspaceFolder`/`renameWorkspace`/`archiveWorkspace`
in [the workspace-and-push doc](remote-conversation-repository-workspace-and-push.md)); the repository's
`override suspend fun changeWorkspace` is a one-line hand-off:

```kotlin
// WorkspaceCommands
suspend fun changeWorkspace(conversationId: String, workspace: String): Session {
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_CHANGE_WORKSPACE, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(ChangeWorkspacePayloadDto(conversationId = conversationId, cwd = workspace)),
    )
    requests.sendAndAwaitConversation(request) // #2213: collector decodes, folds and captures; the row itself is unused here
    return Session(id = "", conversationId = conversationId, claudeSessionUuid = "",
        startedAt = Clock.System.now(), endedAt = null)
}
```

- **The reply is `conversation_updated`**, the same collector arm `rename`/`promote`/`archive` already use
  — no `onInbound` change needed (unlike [`delete`](#deleteconversationid--the-eighth-mutation-first-remove-shaped-one-532),
  whose reply type was genuinely new). Decoded through the same #318 `ConversationResponseDto`; a malformed
  reply fails inside that arm before `upsertConversation` runs there, and `sendAndAwaitConversation` throws
  in this caller instead (#2213).
- **The path is forwarded verbatim, untrusted.** The mobile side does not validate, canonicalise, or
  open the path — the daemon confines it to `$HOME` (fail-closed, strict non-creating confiner) *before*
  storing the resolved realpath, and rejects an empty / out-of-`$HOME` / undecodable path with
  `protocol.malformed`, surfaced here as an ordinary `RelayErrorException`. A client-side `$HOME` check
  would be false assurance (the phone cannot know the daemon's `$HOME`).
- **The folded `cwd` is server-authoritative** (the reply's value — the daemon's resolved realpath — not
  the request's), identical to `rename`'s "return the reply's name, not the input" discipline.
- **`conversation.not_found` → `IllegalArgumentException`**, reusing `RelayRequests.mapError` unchanged, same
  reachability profile as `rename`: the call site (`ThreadViewModel.onWorkspacePicked`) always passes the
  currently-open, hence server-known, `conversationId`, and the [`#490`](../codebase/490.md) guard
  deliberately does not catch IAE — unreachable-by-construction from the shipped UI, not silently
  swallowed.
- **Signature stays `: Session` — the load-bearing decision.** The reply carries no session identity
  (`toConversation()` always sets `currentSessionId = ""`), and `change_workspace` performs **no session
  transition** (pyrycode#823 Out-of-Scope) — the new folder only takes effect on the conversation's next
  fresh session spawn, so there is no `session_transition` (#336) fold and no session-boundary delimiter
  here. Rather than cascade the return type to `Conversation`/`Unit` (a 22-site fan-out: interface +
  `FakeConversationRepository` body + facade + 18 test-double overrides), the method returns the same
  vestigial placeholder shape [`startNewSession`](remote-conversation-repository-control-sends.md#startnewsession--the-bare-v2-new_session-control-send-539)
  established — empty `id`/`claudeSessionUuid`, never persisted, never entering `projection`; the sole
  caller discards it.
- **`FakeConversationRepository.changeWorkspace` is left deliberately unaligned** — it mints a fresh
  session (`mintNewSession`), a transition the real daemon does not perform. An evidence-based
  divergence, not a bug: aligning it costs the same 22-site fan-out for no in-scope benefit. See
  [`../codebase/560.md`](../codebase/560.md) § Lessons learned.
- **`mutationsSupported` stays `false`, untouched** — same posture as archive/unarchive/delete; wiring
  the data path doesn't flip the coarse UI-gating flag. The operator-facing rung-3 e2e is
  [#562](https://github.com/pyrycode/pyrycode-mobile/issues/562) (Inbox, family-gated by #537).

## `requestSystemPrompt(conversationId)` / `setSystemPrompt(conversationId, systemPrompt)` — the system-prompt read and write (#823)

Reads and writes the system prompt a conversation stores over v2 `request_system_prompt` /
`set_system_prompt` (server pyrycode#2152). Both name a **conversation**, never a session — the read
works while nothing is running, and the write takes effect at the conversation's next session start;
neither call restarts or resets a running one. No UI ships in this ticket; [`SaveAsChannelDialog`](save-as-channel-dialog.md) (#957),
[`CreateChannelModal`](mobile-modal-callers.md#callers) (#958) and [`EditChannelModal`](mobile-modal-callers.md#callers) (#667) are the consumers —
each calls `SystemPromptLimit`/`setSystemPrompt` directly rather than through #824's shared editing state,
which stays unclaimed (see [System prompt editor](system-prompt-editor.md)). Both bodies
now live on `SessionSettingsCommands` (#916, `data/repository/SessionSettingsCommands.kt`, beside
`setSessionSettings` above); the repository's `override suspend fun requestSystemPrompt` /
`setSystemPrompt` are one-line hand-offs.

```kotlin
// SessionSettingsCommands
suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading {
    check(CAPABILITY_INTERACTIVE in negotiatedCapabilities()) { SYSTEM_PROMPT_READ_NOT_INTERACTIVE }
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_REQUEST_SYSTEM_PROMPT, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(RequestSystemPromptPayloadDto(conversationId = conversationId)),
    )
    return requests.sendAndAwaitReply(request).toSystemPromptReading()
}

suspend fun setSystemPrompt(conversationId: String, systemPrompt: String?) {
    require(systemPrompt == null || SystemPromptLimit.fits(systemPrompt)) { SYSTEM_PROMPT_TOO_LONG }
    val request = Envelope(
        id = requests.nextRequestId(), type = TYPE_SET_SYSTEM_PROMPT, ts = Clock.System.now().toString(),
        payload = setSystemPromptPayload(conversationId, systemPrompt),
    )
    requests.sendAndAwaitConversation(request)
}
```

> Since [#2213](remote-conversation-repository-state-errors-and-handoff.md#state--concurrency-model),
> `setSystemPrompt` no longer takes a `conversationList: ConversationListProjection` constructor parameter
> (it never read it, only wrote through it) — `SessionSettingsCommands` is published by the collector's
> fold, the same as every other conversation-mutation command.

- **The read is gated fail-closed on `interactive`, before any frame is built — the load-bearing
  divergence from every other one-shot read in this class.** The daemon leaves a conn that never
  negotiated `interactive` fully inert on `request_system_prompt` (no reply at all, not even a not-found),
  so an ungated send would suspend until teardown; `check(...)` throws `IllegalStateException` first,
  matching [`observeSessionSettings`](#observesessionsettingsconversationid--refreshsessionsettingsconversationid--the-settings-read-counterpart-to-setsessionsettings-590)'s
  posture for the same daemon behaviour. **The write is not gated** — the daemon answers
  `set_system_prompt` on any connection, interactive or not — so a UI that can write without being able to
  read back is a real, documented asymmetry, not a bug to align away.
- **The three stored states never collapse, in either direction.** `systemPrompt == null` reads and
  writes as "no prompt stored" (wire: key absent on read, an **explicit** JSON `null` on write — see
  [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) for why `setSystemPromptPayload` is
  built by hand with `buildJsonObject` rather than a `@Serializable` DTO: `MobileJson`'s
  `explicitNulls = false` would silently drop a `null` property, turning an intended clear into an
  omitted key the daemon reads as "leave unchanged"). `""` is an explicitly empty prompt, sent and read
  back as itself. Any other string is forwarded and decoded **verbatim** — never trimmed or normalised.
  Reading a value and writing it straight back must not change its state, and a test exercises exactly
  that round trip.
- **The reply is decoded by hand through `toSystemPromptReading()`, never through a DTO — a deliberate
  divergence from every sibling verb in this class, and the one the plan's security review required.** A
  kotlinx decode failure message can quote the offending input, and the input here is untrusted
  operator-authored text; hand-decoding lets every thrown message be a static literal naming only the key
  (`system_prompt` / `session_prompt_status`), never the value, its length, or the conversation id. An
  explicit JSON `null` for `system_prompt` fails the read (the wire publishes "string or absent," so an
  explicit `null` is neither) — the plan's one resolved Open Question. `session_prompt_status` must name
  one of `matches` / `differs` / `no_session`; anything else fails the read alone, with no other state
  touched.
- **`system_prompt` is registered in the shared success-reply arm of `onInbound`, next to
  `TYPE_SESSION_SETTINGS`** — without that entry the read's `RelayRequests.sendAndAwaitReply` would suspend forever,
  since nothing would ever complete its waiter. The reply carries **no `conversation_id`**, so it can only
  complete the `RelayRequests.pendingRequests` entry whose id it answers via `inReplyTo`; a stray, duplicate, or
  unsolicited one has nowhere to land, the same routing posture `session_settings` already has.
- **The write's ack reuses `conversation_updated`** — no new reply type, no `onInbound` change for the
  write half. Decoded through the same #318 `ConversationResponseDto` boundary every other mutation uses
  and confirmed-upserted exactly as [`rename`](remote-conversation-repository-send-create-promote-rename.md)'s
  is; the ack itself carries no prompt, so nothing about the stored value is cached or projected here.
- **The byte limit is checked client-side before any frame is sent, using the one shared helper.**
  `SystemPromptLimit.fits` (co-located with `SystemPromptReading` on
  [`ConversationRepository`](conversation-repository.md)) is the single place the 8192-UTF-8-byte cap and
  its counting logic live. Channel and host setters, host reply validation and editing consumers
  share `fits`/`utf8Bytes` rather than recounting. The boundary is inclusive and multi-byte-aware — exactly 8192 UTF-8 bytes of multi-byte text is
  sent, 8193 is refused — because the daemon counts bytes, not `String.length`.
- **Errors:** `conversation.not_found` → `IllegalArgumentException` and any other server code →
  `RelayErrorException`, both through the existing `RelayRequests.mapError`, same as `rename`. A
  `SerializationException` (⊂ `IllegalArgumentException`) from a malformed read reply shares that
  supertype with the not-found case — a caller that needs to tell an over-limit write apart from a
  not-found one checks `SystemPromptLimit.fits` itself rather than pattern-matching the exception. Not
  connected is `IllegalStateException` via `RelayRequests.sendAndAwaitReply`, same as every sibling verb. No branch of
  either method logs anything: every message it can throw is a static literal.
- **`StableConversationRepository`** delegates both verbatim to `live` — `IllegalStateException` when no
  connection is live, the plain snapshot-or-throw shape every other one-shot uses; no new delegation
  posture. **`FakeConversationRepository`** holds a `MutableStateFlow<Map<String, String>>` of stored
  prompts (a missing key is "no prompt stored," a present value — `""` included — is the stored text) and
  always reports `SessionPromptStatus.NoSession` (demo mode runs no session to compare against); see
  [ConversationRepository — Phase 1 fake implementation](conversation-repository-fake-implementation.md)
  for the two-step, non-atomic write this needed once the conversation records and the stored prompts
  turned out to live in two separate `StateFlow`s.
- **No UI, no e2e (at #823).** Data-layer only then; #957, #958 and #667 render this text now — as plain
  text only, per the plan's security review, since it is operator-authored content that must not be
  trusted as markup or interpreted as instructions. None of the three route through #824's shared editing
  state.

## Host system prompt read and durable save (#1774)

`requestHostSystemPrompt()` and `setHostSystemPrompt(String)` delegate to
`SessionSettingsCommands`, returning `Result<HostSystemPromptReading>`. The wire
source of truth is the daemon's [Daemon-wide host system prompt](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#daemon-wide-host-system-prompt)
and [host prompt declarations](https://github.com/pyrycode/pyrycode/blob/main/internal/protocol/host_system_prompt.go).
Read sends `request_host_system_prompt` with `{}`; write sends
`set_host_system_prompt` with the required `system_prompt` string, including empty
and whitespace. Neither carries conversation/session identifiers or checks the
interactive capability. Both await `host_system_prompt`, returning the validated
current/default pair. Empty clears; resetting writes the returned default. Do not
reuse the nullable channel setter, its session-status reading or its
`conversation_updated` acknowledgement for this host field.

The shared `SystemPromptLimit` rejects oversized writes before sending. The
[manual wire decoder](mobile-protocol-v2-wire-layer-application-payloads.md#host-system-prompt-validation-1774)
requires both strings and checks their inclusive 8192-byte UTF-8 bounds. Malformed
or oversized replies fail the operation without substituting empty text or
truncating the default, which must remain writable unchanged.

`onInbound` routes the reply only through connection-local `RelayRequests`
correlation. It projects no conversation state and retains no host prompt reading.
Registration precedes sending; reordered reads/writes settle their own waiters,
and duplicate or unmatched replies change nothing. Cancellation removes the
waiter and propagates; teardown fails outstanding calls. Even equal request IDs
on two host repositories belong to separate ledgers.

Failures contain no prompt text or original daemon-authored cause.
`protocol.malformed` and `host_system_prompt.unavailable` retain their published
code and retryability with a static message; unknown codes become
`host_system_prompt.failed`. Debug-only diagnostics contain static operation and
classified outcome names, never payloads, prompts, identifiers or raw error text.
The reading's `toString` redacts both strings.

The real-repository pump coverage in `RemoteConversationRepositoryHostSystemPromptTest`
asserts serialized envelopes and authoritative acknowledgements, empty/custom/default
round trips, ASCII/multibyte boundaries, malformed fields, sanitized errors and
logs, cancellation/teardown, reordered/duplicate/unmatched replies and two-host
isolation without an interactive capability. Testing only the decoder would miss
reply registration or an accidental conversation projection. Facade coverage is
in `HostSystemPromptFacadesTest`; see [stable delegation](stable-conversation-repository.md#host-system-prompts--snapshot-or-result-1774),
[cache pass-through](caching-conversation-repository.md#contract) and
[demo state](conversation-repository-fake-implementation.md#host-system-prompt-demo-state-1774).
