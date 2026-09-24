# Remote conversation repository — the Phase 4 `ConversationRepository` — session settings, archive, delete and workspace change

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

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

The **read** half `setSessionSettings` never had: a conversation-scoped `request_session_settings` →
`session_settings` round trip, exposed as a cold per-conversation reading rather than a one-shot return —
a **new consumer shape**, not another mutation. `#590` is a net-new read path, not a replacement: mobile
never shipped the bootstrap-scoped read desktop had to retire. `settingsRevision` and this whole read —
`observeSessionSettings`, `sessionSettingsRead`, `readSessionSettings`, `refreshSessionSettings` and
`bumpSettingsRevision` — now live on `SessionSettingsCommands` (#916,
`data/repository/SessionSettingsCommands.kt`); the repository's `override fun observeSessionSettings` /
`refreshSessionSettings` are one-line hand-offs, and its `session_transition` arm calls
`sessionSettingsCommands.bumpSettingsRevision(conversationId)` at the position this used to occupy inline.

```kotlin
// SessionSettingsCommands
fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
    settingsRevision
        .map { it[conversationId] ?: 0L }
        .distinctUntilChanged()
        .flatMapLatest { sessionSettingsRead(conversationId) }
        .onStart { emit(null) }

private fun sessionSettingsRead(conversationId: String): Flow<SessionSettings?> =
    flow {
        emit(if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) readSessionSettings(conversationId) else null)
    }.catch { emit(null) }

fun refreshSessionSettings(conversationId: String) = bumpSettingsRevision(conversationId)

/** Public so the repository's `session_transition` arm can call it (was private). */
fun bumpSettingsRevision(conversationId: String) { /* … */ }
```

- **Nothing is cached on this class — the projection is the trigger, not the reading.** `settingsRevision:
  MutableStateFlow<Map<String, Long>>` holds `conversationId -> ordinal`, not a `SessionSettings`. A bump
  means "re-read", and the read always asks the daemon fresh; there is no `StateFlow<SessionSettings>` for
  a stale value to sit in. This is the structural reason a late reply can never overwrite a current
  reading (see below), not a check anywhere in the code.
- **Four refresh triggers, one mechanism each, no fifth mechanism needed.** *Thread entry* is plain
  subscription — the revision `StateFlow` always has a value, so a fresh collector reads immediately (the
  `onStart { emit(null) }` resets to *unavailable* first, so a host handoff never shows the **previous**
  host's values while the new read is in flight). *The owning host's reconnect* falls out of
  [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) minting a **fresh repository** per
  connection plus the [facade](stable-conversation-repository.md)'s `flatMapLatest` re-subscribing under
  it — host isolation is structural, not a checked property, because a reply from another host cannot
  arrive at this instance at all. *That conversation's `session_transition`* is a third write on the
  existing `TYPE_SESSION_TRANSITION` arm, beside `appendSessionBoundary` and `updateCurrentSessionId` —
  `bumpSettingsRevision(conversationId)`, gated by the same decoded conversation id so a transition cannot
  invalidate another conversation's reading. *A caller's `refreshSessionSettings`* — the moment a settled
  `setSessionSettings` write should surface a fresh reading — is the same bump, called directly; it is
  **fire-and-forget, non-suspending, non-throwing**, because an invalidation with no live connection is a
  no-op the caller has nothing to recover from (the next connection re-reads on subscription regardless).
- **`flatMapLatest` is what makes a superseded reply harmless, not a correlation check.** A new trigger
  cancels the in-flight read before starting the next, so a reply that arrives late has no collector to
  reach; `RelayRequests.sendAndAwaitReply`'s `finally` has already deregistered its pending deferred, so the reply
  correlates with nothing and is dropped at the demux — the same idempotent-`complete`-on-no-match posture
  every correlated reply already has. `session_settings` carries **no `conversation_id` of its own**, so a
  reading is routed strictly by the id the caller asked with; a hostile or confused daemon cannot steer one
  into a conversation the phone never asked about.
- **Fails closed to `null`, never to the caller.** `sessionSettingsRead`'s `flow { }.catch { emit(null) }`
  is scoped to the **inner** read flow, not the outer `observeSessionSettings` — a `.catch` on the outer
  flow would be terminal and end the conversation's re-reads after one failure. The caught throwable is
  **discarded, never logged**: only the `effective_effort` decode failure below is this repository's own
  and content-free; a structural decode failure is authored by kotlinx-serialization, whose message can
  quote the offending input, so dropping it (not just writing a clean message) is what keeps "logs no
  payload content" true. A well-meaning `.catch { Log.w(TAG, it) }` added later would leak daemon payload
  content to Logcat in one line — the drop is load-bearing, flagged in a comment at the site.
- **Gated fail-closed on `interactive`, before any frame is sent.** The daemon leaves a conn that never
  negotiated `interactive` fully inert on this verb — no reply at all, not even a not-found — so an
  ungated send would suspend until teardown rather than erroring. Not sending is what keeps "the read sends
  `request_session_settings` and nothing else" true even in the degenerate case: no claude child starts, no
  model turn begins, no `set_session_settings` rides along, and nothing is written to `AppPreferences`.
- **`readSessionSettings` is the repository's `requestHistory` body minus the fold** — encode
  `RequestSessionSettingsPayloadDto(conversationId)`, `requests.sendAndAwaitReply`, decode the reply through
  [`toSessionSettings()`](mobile-protocol-v2-wire-layer-application-payloads.md#the-session-settings-read-exchange-590).
  It throws rather than converting to `null` itself — `sessionSettingsRead` owns that conversion — so every
  failure mode (pump not `Open`, a server `error`, a malformed reply) stays distinguishable at the seam that
  needs to distinguish them. The verb publishes **no reject codes of its own** (it always answers, even for
  an unhosted/unbound/dormant conversation — the all-zero reply is a successful read of "nothing resolved",
  not a failure), so a server `error` here can only be a generic transport-level one the existing
  `RelayRequests.mapError` arm already handles.
- **`effort` is the saved choice; `effectiveEffort` is the applied reading — neither substitutes for the
  other,** and `effectiveEffort` keeps `session_settings`' one optional wire key's three states apart:
  **key omitted** → `EffectiveEffort.Unavailable` (unsupported, or an older daemon that predates the field
  — such a reply still decodes successfully, it just reads `Unavailable`); **explicit `null`** →
  `EffectiveEffort.NotReported` (Claude reported no effort parameter this turn); **a string** →
  `EffectiveEffort.Applied(value)`, retained verbatim including `""` and a level this build does not
  recognise. See [the decode](mobile-protocol-v2-wire-layer-application-payloads.md#the-session-settings-read-exchange-590)
  for how the two-step decode keeps these apart under `explicitNulls = false`.
- **An empty `permissionMode` means *unavailable*, never Manual approval — and `yolo: false` alone is not
  evidence approvals are enforced.** Both accompany a live child that hasn't confirmed yet, or a dormant
  session with no child. Nothing in this read path manufactures a default for either field; `permissionMode`
  stays an open `String` (not an enum) precisely because a closed set would have to mint a member for "no
  confirmation" a consumer could mistake for a posture, and this read accepts `bypassPermissions` even
  though `setSessionSettings`'s write half refuses that spelling.
- **Token counts (`usedTokens`/`windowTokens`) are `Long`, carried but not consumed here** — the
  pyrycode#720 64-bit-Go-`int` width trap. `windowTokens: 0` means the usage reader is unwired, not an
  empty window; a dormant reply reports `0` in both fields as a pair, never independently. No consumer
  reads them until a later context-figure ticket; carried now because the all-zero/dormant reply naming
  their zero state as decodable is part of this ticket's acceptance.
- **`StableConversationRepository`** delegates `observeSessionSettings` through `switchToLive<SessionSettings?>(null)`
  (the [`observeLastMessage`](remote-conversation-repository-reads-and-thread-store.md#observelastmessageconversationid--the-live-last-message-preview-329)
  shape) — here the connection switch **is** the host-isolation mechanism, not just plumbing:
  `flatMapLatest` drops the previous connection's read the instant the connection changes. `refreshSessionSettings`
  is routed through `currentRepository.value?.refreshSessionSettings(id)`, deliberately **not** through the
  throwing `live` helper — invalidation with no connection is a no-op, not an `IllegalStateException`; it is
  the one facade method on this pair that must not throw.
- **`FakeConversationRepository`** seeds a `MutableStateFlow<Map<String, SessionSettings>>` **empty on
  purpose** — an unseeded conversation reads `null`, the same "unavailable" a live repository reports
  before its first reply, so a consumer's unavailable path is the Fake's default rather than a case a test
  has to arrange. A `setSessionSettingsReading(conversationId, reading)` seam sets or clears one entry (the
  test/preview seam for a populated composer); `refreshSessionSettings` records the ask into
  `sessionSettingsRefreshes` (mirroring `setSessionSettingsCalls`) rather than re-emitting anything — the
  Fake has no wire to re-read, so a seeded reading is already current. This is why `observeSessionSettings`
  /`refreshSessionSettings` join `createWorkspaceFolder`/`delete`/`requestScreenSnapshot`/`requestHistory`
  as the group **all three** impls override, unlike `observeStall`/`observeQueue`/`observeApiRetry`/
  `observeCompacting`, which the Fake deliberately inherits — see
  [`conversation-repository.md`](conversation-repository.md) for the full override-set accounting.
- **No composable or screen change** — data layer only. #649 wires the composer controls to `effort`, #650
  projects `permissionMode`/`yolo` into confirmed permissions, and #651 projects `effectiveEffort`; all
  three name this reading as their source. No live rung: nothing operator-facing changes until #649.

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
    val reply = requests.sendAndAwaitReply(request)     // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    conversationList.upsertConversation(conversation)   // confirmed-upsert — ONLY after a successful decode; no return value (interface is Unit)
}
```

- **One shared request DTO for both verbs.** `ArchiveConversationPayloadDto` carries only
  `conversation_id` — archive and restore are a symmetric toggle of one durable flag (same shape, same
  handler, same not-found/reply behaviour), so pyrycode#881 defined one payload server-side for the pair
  and this mirrors that; `ConversationCommands` disambiguates by the `Envelope.type` string passed into
  `sendArchiveToggle`, not by the payload shape.
- **The reply is `conversation_updated`**, the same success arm `rename`/`promote` already use — no
  `onInbound` change needed. Decoded through the same #318 `ConversationResponseDto`, so a malformed reply
  throws the decode exception before `ConversationListProjection.upsertConversation` runs, same as every prior mutation.
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
    val reply = requests.sendAndAwaitReply(request) // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    conversationList.upsertConversation(conversation) // confirmed-upsert — ONLY after a successful decode
    return Session(id = "", conversationId = conversationId, claudeSessionUuid = "",
        startedAt = Clock.System.now(), endedAt = null)
}
```

- **The reply is `conversation_updated`**, the same success arm `rename`/`promote`/`archive` already use
  — no `onInbound` change needed (unlike [`delete`](#deleteconversationid--the-eighth-mutation-first-remove-shaped-one-532),
  whose reply type was genuinely new). Decoded through the same #318 `ConversationResponseDto`; a
  malformed reply throws before `upsertConversation` runs.
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
[`CreateChannelModal`](mobile-modal.md#callers) (#958) and [`EditChannelModal`](mobile-modal.md#callers) (#667) are the consumers —
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
    val reply = requests.sendAndAwaitReply(request)
    conversationList.upsertConversation(MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation())
}
```

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
  its counting logic live; `setSystemPrompt`'s `require(...)` is its only production caller today, and
  the editing state (#824) and channel modals are meant to call `fits`/`utf8Bytes` themselves rather than
  recount. The boundary is inclusive and multi-byte-aware — exactly 8192 UTF-8 bytes of multi-byte text is
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
