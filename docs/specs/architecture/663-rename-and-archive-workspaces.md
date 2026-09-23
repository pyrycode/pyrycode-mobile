# #663 — Rename and archive workspaces on the selected host

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createWorkspaceFolder`, `setSystemPrompt`, `archive` — the throwing-default shape for relay-only verbs, and the archive contract the workspace archive reuses.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (the `TYPE_WORKSPACE_UPDATED` arm, the shared correlated-reply arm, the `TYPE_ERROR` arm), `applyWorkspaceLabel`, `sendAndAwaitReply`, `failAllPending`, `mapError`, `sendArchiveToggle`, `setSystemPrompt`, `projection` — every seam this ticket touches.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `setSystemPrompt`, `archive` — the per-host facade delegation; `AppModule`'s `repository(serverId, …)` wraps one facade per host, so the facade must forward the new verbs or the host-owned caller hits the throwing default.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `CachingConversationRepository` — `: ConversationRepository by delegate`, so new members forward with no edit.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `repositoryFor` — host targeting is the repository instance; no routing to add.
- `app/src/main/java/de/pyryco/mobile/data/network/WorkspaceUpdatedPayloadDto.kt` → `WorkspaceUpdatedPayloadDto` — the reply decode boundary, reused unchanged.
- `app/src/main/java/de/pyryco/mobile/data/network/SystemPromptPayloads.kt` → `RequestSystemPromptPayloadDto` — request DTO shape to mirror.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.cwd`, `Conversation.archived` — archive target selection.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySystemPromptTest.kt` — sibling-test-class shape (own small `FakeSessionPump`, `startWrite` helper) the new repository test mirrors.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → the #721 `workspaceUpdated_*` tests and `conversationsEnvelope` — seeding via real inbound dispatch.
- `../pyrycode/docs/protocol-mobile.md` § Renaming a workspace — wire SSOT for `rename_workspace` / `workspace_updated` and the refusal table; not restated here.
- `docs/knowledge/features/remote-conversation-repository-workspace-and-push.md` — #721's apply-unconditionally lesson; this ticket keeps the push path identical.

## Design source

N/A — data layer only; the ticket draws no UI (#664 owns the modal).

## Context

Desktop has two workspace operations mobile lacks: rename (a daemon verb) and archive (a client-side fan-out of `archive_conversation`). #664's Edit workspace modal consumes both. Each repository instance is one host, reached through `HostConversationSource.repositoryFor(serverId)`, so acting on that instance is the host targeting. No ADR warranted — both are straightforward extensions of shipped patterns.

## Design

### Interface (`ConversationRepository`)

```kotlin
suspend fun renameWorkspace(path: String, label: String?): Unit =
    error("renameWorkspace is not implemented for this ConversationRepository")

suspend fun archiveWorkspace(path: String): Unit =
    error("archiveWorkspace is not implemented for this ConversationRepository")
```

Throwing defaults, like `createWorkspaceFolder`: only the relay repository implements them; the Fake and inline test doubles inherit the throw (ticket permits it). KDoc pins: exact-bytes `path`, verbatim `label` (null clears), error types, post-conditions.

### Request payload (new `RenameWorkspacePayloadDto.kt` in `data/network/`)

`@Serializable data class RenameWorkspacePayloadDto(val path: String, val label: String? = null)`, encode-only through `MobileJson`. With `explicitNulls = false` a null label is **omitted**, which the protocol reads as a clear, identical to explicit null. A payload test pins both encodings (`{"path":…,"label":…}` and `{"path":…}`).

### `renameWorkspace` (relay)

Envelope `TYPE_RENAME_WORKSPACE = "rename_workspace"` with the DTO → `sendAndAwaitReply` → validate → return. The inbound arm has already decoded and applied the reply before completing the waiter; the caller re-decodes the completed payload as `WorkspaceUpdatedPayloadDto` and requires `decoded.path == path`, throwing `RelayErrorException(ERROR_MALFORMED_REPLY)` otherwise. That guards the post-condition against a reply of another type correlated to this id (the shared correlated arm completes any waiter with any payload) or a reply naming another path — either would otherwise return success with the requested path unlabelled. Not gated on `interactive` (the protocol does not gate the request; only the fan-out targets interactive conns).

### `TYPE_WORKSPACE_UPDATED` arm in `onInbound`

Order is the load-bearing part:

1. Look up the waiter by `in_reply_to` (may be null — unsolicited push).
2. Decode `WorkspaceUpdatedPayloadDto`. On failure: if a waiter exists, `completeExceptionally(RelayErrorException(ERROR_MALFORMED_REPLY, retryable = false, <static message>))` so a malformed correlated reply fails the caller instead of hanging it; then return with the projection untouched. The kotlinx exception is not forwarded (its message can quote the payload, which holds a path and a label).
3. `applyWorkspaceLabel(path, label)` — unchanged, unconditional, as #721.
4. `waiter?.complete(payload)` — **after** the apply, so the caller observes the label the moment the call returns.

Unsolicited pushes take the same path with no waiter, so their behaviour is unchanged.

### `archiveWorkspace` (relay)

1. Snapshot targets from `projection.value.orEmpty()`: rows with `cwd == path` (exact `String` equality, no trim) and `!archived`; take their ids. A `null` projection (no list yet) yields no targets — the call does not wait for a list.
2. No targets → return without sending anything.
3. For each id, sequentially, `archive(id)` (the existing `sendArchiveToggle`, which upserts each row only on its own confirmed `conversation_updated`). Catch per-row failures, keep the first, continue. `CancellationException` is rethrown immediately (caller cancelled — stop).
4. After the loop, throw the first failure if any.

Sequential rather than concurrent: deterministic ordering, one in-flight request at a time, and a lost connection fails the remaining rows fast (`sendAndAwaitReply`'s not-connected `check`) so "try the rest" costs nothing. A retry re-snapshots, so only still-active rows are sent again. No rename or delete is sent; the label lives in `workspaceLabel` and archive's upsert carries the daemon's stored label on the reply record.

### Facade (`StableConversationRepository`)

Two one-line delegations to `live`, like `setSystemPrompt`; with no live repo they throw `IllegalStateException` via the existing `live` accessor.

## State + concurrency model

No new state or jobs. Rename's apply runs on the single inbound collector; the caller suspends on its `CompletableDeferred` only. Archive runs on the caller's coroutine, reading `projection.value` once (a snapshot) and mutating only through the existing `upsertConversation` CAS. Teardown mid-await is handled by `failAllPending` (both verbs fail with `IllegalStateException`). No dispatcher changes.

## Error handling

| Case | Rename | Archive |
|---|---|---|
| `workspace.not_found` / `protocol.malformed` | `RelayErrorException` with that `code` (`mapError`, unchanged); no row changes | n/a |
| per-row archive refusal | n/a | first error rethrown as-is after trying all rows (`conversation.not_found` → `IllegalArgumentException`, others → `RelayErrorException`) |
| not connected / torn down | `IllegalStateException` (the connection-loss type every relay verb shares) | same, as the first error |
| malformed correlated reply | `RelayErrorException(ERROR_MALFORMED_REPLY)`; no row changes | existing archive decode exception |

Refusals carry a readable `code`; a lost connection is the repository-wide `IllegalStateException`, which is how every relay verb reports it and how #664 tells "the host refused" from "the host was unreachable". Rename never produces the unknown-conversation `IllegalArgumentException` for any documented refusal code (the daemon's rename refusal table has no `conversation.not_found`). Nothing is logged on any branch — path and label never reach Logcat or an exception message.

## Testing strategy

Unit tests only (no UI, no operator-facing flow in this slice; #676 owns the live proof). New sibling class `RemoteConversationRepositoryWorkspaceTest` driving real inbound dispatch with its own `FakeSessionPump`:

- rename sends one `rename_workspace` with exact path and label; the call is still suspended before the reply; after the correlated `workspace_updated` every row at that path (archived included) carries the label, other paths unchanged.
- rename with null clears: encoded payload omits `label`; rows' labels become null.
- rename on host A leaves host B's identical rows unchanged.
- rename refused with `workspace.not_found` and with `protocol.malformed` → `RelayErrorException` with the code, not `IllegalArgumentException`; rows unchanged.
- rename pending when the connection tears down → fails with `IllegalStateException`; rows unchanged.
- malformed correlated reply → `RelayErrorException(ERROR_MALFORMED_REPLY)`; rows unchanged.
- a correlated `workspace_updated` naming another path, or a `conversation_updated` correlated to the rename's id → `RelayErrorException(ERROR_MALFORMED_REPLY)`.
- unsolicited push still applies (regression guard; existing #721 tests also cover it).
- archive sends `archive_conversation` only for active rows whose cwd equals the path byte for byte (a trailing-separator and a trailing-space sibling are left alone), none for archived rows; no `rename_workspace`/`delete_conversation` sent; the label survives.
- archive: a row leaves the active list only after its own reply; the call returns only after all replies.
- archive with no matching active rows sends nothing and returns.
- archive with one refusal among three: all three sent, call fails with the first error, confirmed rows archived, refused row active; a second call sends only the refused row.
- archive when the connection drops mid-loop: remaining rows attempted, failure is the first error.

Payload test `RenameWorkspacePayloadDtoTest`: label present and null-omitted encodings. `StableConversationRepositoryTest`: delegation of both verbs (args verbatim) and `IllegalStateException` with no live repo.

## Open questions

- None blocking. Interpretation recorded above: "an error whose code the caller can read" applies to refusals (`RelayErrorException.code`); connection loss keeps the shared `IllegalStateException` rather than inventing a client-side code.

## Documentation handoff

Pending for the documentation stage (from the ticket):

- add rename and archive sections to `docs/knowledge/features/remote-conversation-repository-workspace-and-push.md`;
- update the `workspace_updated` paragraph under § The repository — one projection, cold fan-out in `remote-conversation-repository-reads-and-thread-store.md` (it still says nothing sends `rename_workspace`);
- correct `channel-list-screen-tree-and-controls.md` § Add controls (#738) and `channel-list-screen.md` § Related — the workspace row's add control belongs to #664, not #663.

## Security review

**Verdict:** PASS (after one revision: the rename caller now validates its reply — see [Trust boundaries])

**Findings:**

- [Trust boundaries] Revised in-plan, was MUST FIX — the first draft returned success as soon as any payload completed the rename waiter. The shared correlated-reply arm completes a waiter with whatever type arrives under its id, so a `conversation_updated` (or a `workspace_updated` for another path) correlated to the rename would have returned success with the requested path unlabelled, breaking AC 1's post-condition. `renameWorkspace` now re-decodes the reply through `WorkspaceUpdatedPayloadDto` and requires the requested path. The inbound boundary itself stays the single `WorkspaceUpdatedPayloadDto` decode in the `TYPE_WORKSPACE_UPDATED` arm; a malformed correlated reply fails its waiter with a static `RelayErrorException` rather than hanging it.
- [Trust boundaries] OUT OF SCOPE — the label is daemon-stored operator text rendered by consumers. This ticket stores it verbatim (as #721 does) and renders nothing; safe rendering and any display bound belong to #664 / #722 / #641.
- [Trust boundaries] No findings — the outbound `label` is sent verbatim; the daemon is the validator (blank and over-128-byte labels refused with `protocol.malformed`), and trimming/clear-on-blank is #664's modal concern per the ticket.
- [Tokens] No findings — no tokens, keys or credentials are created, read or stored.
- [File / storage] No findings — `path` is a lookup key compared with `String` equality against `Conversation.cwd`; it is never resolved, joined, opened or persisted on the phone. No path traversal or TOCTOU surface exists because no filesystem call is made.
- [Android surface] No findings — no intents, deep links, pending intents, providers or WebViews.
- [Crypto] No findings — frames ride the existing Noise session through `SessionPump.send`; nothing new is framed or encrypted here.
- [Network & I/O] No findings — archive sends at most one request per matching active row, sequentially; the count is bounded by this host's own projection. No new socket, timeout or frame-size surface.
- [Logs / errors] No findings — no log call is added. Every exception this ticket creates carries a static message; neither the path nor the label is interpolated. The kotlinx decode exception from a malformed reply is replaced, not forwarded, since its message can quote payload content. Daemon refusal messages reach `RelayErrorException.message` through the existing `mapError`, and the protocol fixes those messages to echo no supplied byte.
- [Concurrency] SHOULD FIX (implement in Phase B) — `CancellationException` extends `IllegalStateException`, so archive's per-row `catch` must test for cancellation **first** and rethrow it, or a cancelled caller would keep sending archives. `failAllPending` completes with a plain `IllegalStateException`, not a cancellation, so teardown still reads as a per-row failure and the loop continues as AC 4 requires. The target list is a one-time snapshot of `projection.value`; rows archived concurrently elsewhere are idempotent on the daemon, and rows added afterwards are picked up by a retry.
- [Threat model] Hostile daemon: it can lie about labels or archive outcomes, which it could equally do through snapshots. The validation above keeps the caller's success signal consistent with the projection. A hostile relay can only drop or delay frames; a dropped reply leaves the call suspended until teardown, which `failAllPending` resolves (the existing posture of every correlated verb).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
