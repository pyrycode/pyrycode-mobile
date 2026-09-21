# Remote conversation repository — the Phase 4 `ConversationRepository` — session settings, archive, delete and workspace change

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `setSessionSettings(sessionId, model, effort, yolo)` — the fifth mutation, first session-scoped ([#543](../codebase/543.md))

Applies the operator's model / effort / YOLO change to a **running session** over v2
`set_session_settings`, and returns only after the daemon's ack. [#543](../codebase/543.md) is `rename`
([#530](../codebase/530.md)) **minus the state fold**: session settings live in the ViewModel (#544's
scope), not this repository's projection, so there is nothing to `upsertConversation`. It is also the
**first session-scoped mutation** — every prior one (`sendMessage`, `createDiscussion`, `promote`,
`rename`, `startNewSession`) keys off `conversationId`; this one takes `sessionId`, sourced by #544 from
`Conversation.currentSessionId`.

```kotlin
override suspend fun setSessionSettings(
    sessionId: String, model: String?, effort: String?, yolo: Boolean?,
) {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = TYPE_SET_SESSION_SETTINGS, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(
            SetSessionSettingsPayloadDto(sessionId = sessionId, model = model, effort = effort, yolo = yolo),
        ),
    )
    val reply = sendAndAwaitReply(request)   // throws on server `error` / not-Open before any decode
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
  `session.not_found`, **not** `conversation.not_found`, so `mapError` routes it through the else-branch
  to a swallowable `RelayErrorException` carrying `.code`, never `IllegalArgumentException`. Every server
  error this verb can produce (`session.not_found`, `protocol.malformed` for an invalid model/effort the
  daemon re-validates, `server.binary_offline`) is a `RelayErrorException`; not-connected is
  `IllegalStateException` via `sendAndAwaitReply`, same as every sibling verb.
- **`model` / `effort` vocabulary is the caller's concern, not this method's.** The strings are forwarded
  **verbatim** (as `rename` forwards the dialog's name); the daemon re-validates
  (`validModel`/`validEffort`) and rejects an invalid value with `protocol.malformed` before persisting.
  #544 maps its `Model`/`Effort` enums to wire strings before calling.

## `archive(conversationId)` / `unarchive(conversationId)` — the sixth and seventh mutations (#549)

Archives or restores an existing conversation over v2 `archive_conversation` / `unarchive_conversation`
(server pyrycode#881), replacing the two `UnsupportedOperationException` throws these methods carried since
\#312. Both overrides delegate to one private helper parameterized by wire type — the mobile mirror of the
server's single handler registered under both verbs:

```kotlin
override suspend fun archive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_ARCHIVE_CONVERSATION)
override suspend fun unarchive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_UNARCHIVE_CONVERSATION)

private suspend fun sendArchiveToggle(conversationId: String, type: String) {
    val request = Envelope(
        id = requestId.incrementAndGet(), type = type, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(ArchiveConversationPayloadDto(conversationId = conversationId)),
    )
    val reply = sendAndAwaitReply(request)              // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    upsertConversation(conversation)                    // confirmed-upsert — ONLY after a successful decode; no return value (interface is Unit)
}
```

- **One shared request DTO for both verbs.** `ArchiveConversationPayloadDto` carries only
  `conversation_id` — archive and restore are a symmetric toggle of one durable flag (same shape, same
  handler, same not-found/reply behaviour), so pyrycode#881 defined one payload server-side for the pair
  and this mirrors that; the repository disambiguates by the `Envelope.type` string passed into
  `sendArchiveToggle`, not by the payload shape.
- **The reply is `conversation_updated`**, the same success arm `rename`/`promote` already use — no
  `onInbound` change needed. Decoded through the same #318 `ConversationResponseDto`, so a malformed reply
  throws the decode exception before `upsertConversation` runs, same as every prior mutation.
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
  re-archive/re-unarchive; `upsertConversation` replaces the entry with an equal value — a benign re-emit,
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
`conversation_updated`; and the fold **removes** rather than upserts.

```kotlin
override suspend fun delete(conversationId: String) {
    val request = Envelope(
        id = requestId.incrementAndGet(), type = TYPE_DELETE_CONVERSATION, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(DeleteConversationPayloadDto(conversationId = conversationId)),
    )
    val reply = try {
        sendAndAwaitReply(request)
    } catch (alreadyGone: IllegalArgumentException) {
        // mapError maps conversation.not_found → IAE and nothing else — the delete contract is
        // *tolerant* of unknown ids, so already-gone converges as success (the deliberate divergence
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
  no-op arm), so the pending `CompletableDeferred` would never complete and `sendAndAwaitReply` would
  suspend until connection teardown — a silent-forever no-op, not a crash.
- **The ack carries `id`, not `conversation_id` — and its value is discarded.** `ConversationDeletedPayloadDto`
  decodes `{id}` purely to validate the reply shape (the `SessionSettingsUpdatedPayloadDto` posture); the
  repository removes the id it *sent*, never the id the reply echoes, so a lying relay cannot redirect the
  removal to a different conversation.
- **REMOVE, not upsert — `removeConversation` clears all three projections.** The contrast to
  `upsertConversation`: filters the id out of `projection` (the list), `threadByConversation`, and
  `lastMessages`. The remote holds these as three separate `StateFlow`s (unlike the fake's unified
  `state: Map<id, ConversationRecord>`, where removing one map entry empties list/messages/last-message at
  once), so a list-only removal would leave `observeMessages`/`observeLastMessage` still serving a
  hard-deleted conversation's rows — a contract violation of the interface's documented three-stream
  post-condition. `List.filterNot` / `Map - missingKey` are element-equal on an absent id, so `StateFlow`
  conflation makes a repeat or already-gone delete a no-op re-emit.
- **`conversation.not_found` converges as success — do not clone rename/archive's IAE-crash path.** The
  `ConversationRepository.delete` contract is explicitly tolerant of unknown ids (unlike archive/rename,
  which throw on unknown ids and rely on the [`#490`](../codebase/490.md) guard's deliberate
  IAE-doesn't-catch crash posture). Delete catches the same `mapError`-produced `IllegalArgumentException`
  locally and converges by removing the id and returning normally — an already-deleted id is success, not
  a bug signal. The catch is scoped to `sendAndAwaitReply` only (not the decode line below it), so a
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
shape with a `cwd` payload instead of `name`:

```kotlin
override suspend fun changeWorkspace(conversationId: String, workspace: String): Session {
    val request = Envelope(
        id = requestId.incrementAndGet(), type = TYPE_CHANGE_WORKSPACE, ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(ChangeWorkspacePayloadDto(conversationId = conversationId, cwd = workspace)),
    )
    val reply = sendAndAwaitReply(request)              // throws on server `error` / not-Open; the decode below is unreachable on failure
    val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
    upsertConversation(conversation)                    // confirmed-upsert — ONLY after a successful decode
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
- **`conversation.not_found` → `IllegalArgumentException`**, reusing `mapError` unchanged, same
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
