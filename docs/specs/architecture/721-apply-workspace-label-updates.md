# 721 — Apply workspace label updates to the owning host

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `onInbound` | The single inbound demux this slice adds two arms to; its collector-survival invariant governs both. |
| ” | `upsertConversation` | The existing dedup-by-id projection fold the ticket names for the unsolicited `conversation_updated` record. |
| ” | `updateCurrentSessionId` | The shape the new `applyWorkspaceLabel` copies: `projection.update { current?.map { … } }`, identity branch as the absent-row guard, `null` projection stays `null`. |
| ” | `projection` (KDoc) | Documents that the list projection has **more than one writer** — the collector plus mutation coroutines — which is why `MutableStateFlow.update` is load-bearing here. |
| ” | `recordReplayCursor` | Type-agnostic and runs before the demux; confirms AC #4's "no replay support" needs no new code (these frames carry no `event_id`). |
| `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` | `ConversationResponseDto`, `toConversation` | #720 already carries `workspace_label`; the unsolicited fold reuses this boundary verbatim. |
| `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` | `ConversationSummaryDto` | The snapshot row already carries `workspace_label`, so AC #4's recovery path is existing behaviour to guard, not to build. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` | `MobileJson` | `explicitNulls = false` + `ignoreUnknownKeys = true` ⇒ an omitted `label` and an explicit `"label":null` both decode to `null`, so the protocol's "clear" state needs no special case. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` | `Envelope.inReplyTo` | Nullable `Long?`, so "unsolicited" is expressible directly and the test helper can default it to `null`. |
| `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` | `Conversation.workspaceLabel` | The nullable field #720 landed; this slice is the first writer other than the snapshot mapper. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` | `FakeSessionPump`, `conversationsEnvelope`, `conversationUpdatedEnvelope`, `promote_uncorrelatedUpdatedReply_isNoOpAndCollectorSurvives` | The harness the new coverage extends — and one existing test whose name and comment assert the very no-op this slice removes. |
| `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md` | "The repository — one projection, cold fan-out" | Records the unsolicited delta-merge as deferred work and the correlated-vs-unsolicited split this slice implements. |
| `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md` | "Open hand-off items" | The entry this slice closes for `conversation_updated` (documentation stage clears it). |
| `../pyrycode/docs/protocol-mobile.md` | Application message types; § Renaming a workspace | Wire SSOT for both frames. Cited, not restated. |

**Design source:** N/A — the diff is confined to `data/`. The user story's visible effect lands in the consuming slices (#722 thread display, #641 tree render), which own the Figma anchor; this slice ships no composable.

## Context

`RemoteConversationRepository.onInbound` drops `workspace_updated` entirely (no arm, no constant) and routes `conversation_updated` through a correlation-only arm that discards any frame without a matching `in_reply_to`. Daemon #2208/#2209/#2210 ship both pushes and #720 landed the nullable `workspaceLabel` on `Conversation` plus `workspace_label` on both DTOs, so the data exists and only inbound dispatch is missing: a label renamed on desktop reaches the phone today only on the next full `conversations` snapshot.

The protocol is explicit that each frame "has **two kinds of producer**, and a client MUST accept both" — a correlated reply and an unsolicited push — and that a client matching on correlation alone drops the pushed frame. That is exactly the present defect.

No ADR is warranted: this slice adds no new architectural seam, it consumes an existing projection through its existing fold.

## Design

Two arms in `onInbound`, one new private fold, one new DTO. No new subscription, screen, navigation, coroutine or scope — the repository instance is already per-connection and therefore per-host, and `HostConversationSource` already observes its `ConversationFilter.All` records.

**New DTO** — `app/src/main/java/de/pyryco/mobile/data/network/WorkspaceUpdatedPayloadDto.kt`:

```kotlin
@Serializable
data class WorkspaceUpdatedPayloadDto(
    val path: String,
    val label: String? = null,
)
```

`path` is required with no default (an absent one is a malformed frame, not an empty path). `label` is nullable *with* a default so the protocol's two spellings of "clear" — key omitted, or explicit `null` — decode identically. No `toDomain` mapper: the payload is two scalars consumed directly by the fold, so adding one would create a second throw site for no gain.

**`TYPE_WORKSPACE_UPDATED` arm.** Decode-or-drop through `MobileJson`, then `applyWorkspaceLabel(path, label)`. Applied **unconditionally**, whether or not `inReplyTo` is set (AC #1) — the record is identical either way. It deliberately does **not** complete a pending request: nothing in this repository sends `rename_workspace` yet, so correlation plumbing here would be speculative. #663 adds the sender and the completion together.

**`TYPE_WORKSPACE_UPDATED` is not capability-gated**, unlike the structured live-session arms. Gating would break the correlated-reply half of AC #1, and the gate buys nothing: a hostile daemon that ignored the negotiated set could achieve the same label change through an ungated `conversations` snapshot.

**`TYPE_CONVERSATION_UPDATED` splits out of the shared correlated-reply arm** into its own arm that branches on whether a waiter is actually registered:

| Condition | Behaviour |
|---|---|
| `inReplyTo` non-null **and** `pendingRequests` holds that id | Complete the waiter verbatim, as today. No fold — the awaiting mutation (`promote` / `rename` / `archive` / `changeWorkspace`) already upserts its own decoded return, so folding here too would be a redundant second write. |
| `inReplyTo` null, **or** it matches no pending entry | Decode-or-drop through `ConversationResponseDto.toConversation()`, then `upsertConversation`. |

`conversation_created` stays on the shared correlated arm untouched, per the ticket.

**New fold** — `applyWorkspaceLabel(path: String, label: String?)`: one `projection.update` mapping every row whose `cwd` equals `path` to `copy(workspaceLabel = label)` and returning every other row by identity. Direct sibling of `updateCurrentSessionId`, and it inherits three properties from that shape:

- **Exact string equality**, no trim, no normalization, no filesystem access. The protocol compares the path as bytes; two paths differing by a trailing separator are distinct workspaces, so normalizing would merge workspaces the daemon keeps apart.
- **A `null` (pre-first-snapshot) projection stays `null`.** A push that arrives before the first snapshot has no rows to label and must not invent one — the frame carries a path, not a conversation.
- **No match is a genuine no-op.** `map` returns an element-equal list, so `StateFlow` conflation suppresses re-emission; no phantom row, no spurious emission to collectors.

Both new arms and the fold are host-scoped by construction: each `RemoteConversationRepository` owns one connection's `projection`, so two hosts holding byte-identical `cwd` strings or conversation ids cannot cross-contaminate. That is architecture, not a check — the two-repository test pins it.

## State + concurrency model

No new job, scope, dispatcher or flow. Every write lands on the existing `projection: MutableStateFlow<List<Conversation>?>`, from the existing single inbound collector launched in `init` on the connection scope, and is cancelled with it.

`MutableStateFlow.update` is **load-bearing, not stylistic**: `projection` has writers beyond the collector (the correlated mutations write from arbitrary caller coroutines via `upsertConversation`), so both new writes are genuine read-modify-writes that must CAS-retry rather than clobber a concurrent snapshot or upsert. A `.value = …` formulation would open a real check-then-mutate window.

One race is analysed and benign: the correlated/unsolicited branch reads `pendingRequests[id]`, and a caller removes its own entry in a `finally` after awaiting. A *duplicate* reply arriving after that removal is therefore treated as unsolicited and folded — an idempotent re-upsert of the same record by the same id, which is exactly what `upsertConversation`'s dedup gives (AC #2's "without duplicate rows").

**Known, pre-existing property carried forward, not introduced:** `ConversationResponseDto.toConversation()` maps the three fields the mutation payload does not carry (`currentSessionId`, `sessionHistory`, `isSleeping`) to placeholders, so `upsertConversation` replaces any live `currentSessionId` that `updateCurrentSessionId` had written. The correlated arm has done this since #348 and the class KDoc documents it. It does not become reachable here: the push's only two producers are host-side `pyry channel new` (a conversation this connection has never seen, so the fold appends) and one-shot auto-naming (which fires on a conversation's first message, before any `session_transition` could have set a live id). Changing `upsertConversation` would be an out-of-scope behaviour change to the correlated path; deferred deliberately.

## Error handling

Every failure mode is "drop this one envelope, keep the collector alive". A throw inside `onInbound` would terminate the single inbound collector and freeze every future update for the connection — that is the invariant the whole demux is built around, and it is what AC #3 asks to prove.

| Failure | Handling |
|---|---|
| `workspace_updated` payload missing `path`, wrong-typed, or not an object | `catch (e: IllegalArgumentException)` → `return`. `SerializationException` ⊂ `IllegalArgumentException`, so one catch covers both. Projection untouched; a later valid frame applies. |
| Unsolicited `conversation_updated` payload malformed (missing field, bad `last_used_at`) | Same decode-or-drop, before any mutation — no partial fold. |
| Correlated `conversation_updated` payload malformed | Unchanged: handed verbatim to the waiter, which decodes **in the caller's coroutine**, so it never throws inside the collector. |
| `label` present but blank or over 128 bytes | Stored verbatim. The daemon refuses both; the client does not re-enforce a daemon-side rule, and truncating would display a different name than the operator typed. See Security review. |
| No row matches `path` | No-op by conflation; not an error. |

Nothing in either arm logs — not the path, not the label, not the decode error. The path is a filesystem location on the daemon's host and the label is operator-authored text; the protocol keeps both out of daemon logs and this slice is symmetric. This matches the existing arms' documented silent-drop idiom rather than introducing the file's first `Log` call.

## Testing strategy

JVM unit tests only (`./gradlew testDebugUnitTest`), `runTest` + the existing channel-backed `FakeSessionPump`. No device test: the diff adds no composable. AC #3 mandates that coverage drive **real inbound dispatch** (`pump.push(envelope)`), never a direct projection mutation — every test below pushes a wire frame.

`RemoteConversationRepositoryTest` — two new helpers (`workspaceUpdatedEnvelope`; `conversationUpdatedEnvelope` gains a nullable `inReplyTo` defaulting to `null` and a `workspaceLabel`) and these scenarios:

- **AC #1** — an unsolicited `workspace_updated` labels *every* row whose `cwd` equals `path` and leaves other paths untouched; the same frame carrying an `in_reply_to` applies identically; a `null` label clears a stored one; an archived row is labelled (read back through `ConversationFilter.Archived`); **two repositories over two pumps**, seeded with byte-identical ids and cwds, prove a push to one host leaves the other unchanged.
- **AC #2** — an unsolicited `conversation_updated` (`inReplyTo = null`) folds its record and label in place with no duplicate row; one whose `inReplyTo` matches no pending request folds the same way; a frame moving the conversation to a differently-labelled workspace lands the destination `cwd` *and* the destination label; a correlated `rename` round-trip still returns its record with the label preserved and leaves exactly one row for that id.
- **AC #3** — a `workspace_updated` missing `path` changes nothing and emits nothing, and a subsequent valid frame applies, proving the collector survived.
- **AC #4** — a pushed label followed by a `conversations` snapshot carrying a different label leaves the snapshot's value in place, pinning the snapshot as authoritative.

`WorkspaceUpdatedPayloadDtoTest` (new, matching the per-payload convention of `ConversationResponseDtoTest` and `RecentWorkspacesPayloadsTest`) pins the decode contract the fold depends on: a present label decodes verbatim, an explicit `"label":null` and an omitted `label` key both decode to `null`, and a payload without `path` throws.

One existing test, `promote_uncorrelatedUpdatedReply_isNoOpAndCollectorSurvives`, is renamed and its comment rewritten: it documents the no-op this slice deliberately removes. Its round-trip assertion survives as the correlated-path regression guard.

#676 owns the cross-client rung-3 scenario for this family; this slice adds none, and the change is not operator-facing on its own (no rendered surface until #722/#641).

## Open questions

1. Should `applyWorkspaceLabel` reject a blank `path`? **Resolved in design:** no. The protocol refuses a path matching no conversation daemon-side, and an exact-equality fold over a projection is already inert for a path no row carries. A blank-path guard would be a client-side re-enforcement of a daemon rule with no observed failure behind it.
2. Should the `workspace_updated` arm also complete a pending request? **Resolved in design:** no — #663 owns the sender and adds the completion with it.

## Documentation handoff

Pending for the documentation stage; **not** edited by this ticket.

- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` and `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: record nullable workspace labels, the accepted push *and* reply forms of both frames, host/path matching, and reconnect recovery via the next snapshot. Remove the claim that unsolicited conversation updates are harmless no-ops (present in the latter's inbound-dispatch table and its "Correlated reply vs unsolicited delta" note).
- `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md`: clear the `conversation_updated` entry from **Open hand-off items**, leaving the `conversation_created` and session-enrichment items in place.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** The boundary is explicit and single: `MobileJson.decodeFromJsonElement<…>` inside each `onInbound` arm. Downstream holds typed values only — `WorkspaceUpdatedPayloadDto` and `Conversation`, never a raw `JsonElement`. Two untrusted values newly reach app state. `path` crosses as a **lookup key and nothing else**: it is compared with `==` against `Conversation.cwd` and never resolved, joined, stat'd, opened, logged or rendered. `label` crosses into `Conversation.workspaceLabel`, a model field with **no renderer in this diff** — #722 and #641 own the render path and the length/control-character handling there, per the ticket's own split.
- **[Trust boundaries]** OUT OF SCOPE — no client-side bound or sanitization of `label`. The daemon's 128-byte cap is a size limit and not a safety property (protocol § Renaming a workspace says so explicitly), and a hostile daemon can exceed it or embed newlines/ANSI. Deliberately not re-enforced here: truncating would show a different name than the operator typed, and a blank `""` normalized to `null` would erase the protocol's distinction between "labelled blank" and "unlabelled". Storage is verbatim; render-time bounding belongs to **#722 and #641**. Memory is not a vector — the frame is already capped by the transport, and N matching rows share one `String` reference, so the fold does not amplify.
- **[File / storage]** No findings, and the usual advice inverts here: canonicalising `path` would be **wrong**, not defensive. Nothing in this slice opens, resolves, stats or creates a file; `path` names a folder on the *daemon's* host, which has no meaning on the phone's filesystem. Canonicalising would merge workspaces the daemon keeps byte-distinct. No TOCTOU surface, because there is no filesystem access to race.
- **[Tokens, secrets, credentials]** No findings — no token, key or credential is read, written, compared or derived. `path` is not a secret but *is* host-revealing, which is why it never reaches a log (below).
- **[Cryptographic primitives]** No findings — no RNG, no key schedule, no AEAD, no handshake. The `cwd == path` comparison is attacker-influenced on both sides but neither side is a secret, so ordinary `String.equals` is correct; `MessageDigest.isEqual` here would be cargo cult.
- **[Network & I/O]** No findings — no new socket, timeout, TLS setting or frame. The one real property is collector survival: an uncaught throw in either new arm would kill the single inbound collector and freeze the whole connection's updates. Both arms are decode-or-drop and `projection.update` is a pure, throw-free fold; AC #3's test proves a later frame still applies.
- **[Error messages, logs, telemetry]** No findings — neither arm logs anything, so `path` and `label` reach no log on any branch, including the reject branch. This is symmetric with the daemon, which records only a `conn_id` and an event name for this verb, and consistent with the file's existing documented silent-drop idiom. No new telemetry.
- **[Inter-process / Android attack surface]** Not applicable — the diff is confined to `data/`: no `Activity`, `Service`, `BroadcastReceiver`, intent filter, deep link, `PendingIntent`, content provider or `WebView`. The FCM wake path is untouched.
- **[Concurrency]** No findings, one analysed race. Both writes use `MutableStateFlow.update`, which is load-bearing because `projection` has writers on other coroutines (`upsertConversation` from mutation callers); a `.value =` read-modify-write would open a real check-then-mutate window. The correlated/unsolicited branch reads `pendingRequests[id]` after the caller may have removed its entry, so a duplicate reply folds as unsolicited — an idempotent re-upsert by the same id, benign by construction. No new coroutine, so no new scope, cancellation path or leak. Process death mid-fold loses connection-scoped in-memory state that the next snapshot rebuilds — the existing posture.
- **[Threat model alignment]** *Malicious relay:* content-blind and on-path; it can drop or delay a push, degrading to a stale label that AC #4's snapshot path repairs, and it cannot reorder within the session (the Noise transport sequences frames per direction). It cannot forge one — these arrive inside the authenticated session. *Hostile daemon frame:* malformed is dropped with the collector intact; oversized is capped by the transport; a `path` naming a workspace the phone holds is within the daemon's own authority over its conversations. *Cross-host leakage:* prevented structurally — one repository instance per connection owns one projection — and pinned by the two-repository test, not by a runtime check. *Forged `in_reply_to` to suppress a fold:* possible in principle, but a daemon that can do that can already complete that waiter with an arbitrary record, so this slice grants no new capability. *UI-side leakage* (screenshots, accessibility, overlays): no UI in this diff; belongs with #722/#641.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
