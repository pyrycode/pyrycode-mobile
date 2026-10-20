# Remote conversation repository — the Phase 4 `ConversationRepository`

The **live, server-backed implementation** of the [`ConversationRepository`](conversation-repository.md)
contract — the Phase 4 counterpart to the in-memory `FakeConversationRepository`. It reads real server
state over the [Mobile Protocol v2](mobile-protocol-v2-wire-layer.md) Noise session instead of an
in-process seed store. Normal builds reach it through the Koin-bound
[`StableConversationRepository`](stable-conversation-repository.md) facade, keeping UI consumers
independent of connection churn and repository selection.

Package: `de.pyryco.mobile.data.repository` (`RemoteConversationRepository` + the consumer-defined
`SessionPump` interface), same package as the contract and the Fake. Built **slice by slice**: the
conversation-list read path landed in [#312](../codebase/312.md), the last-message preview in
[#329](../codebase/329.md), and the thread read path in [#313](../codebase/313.md); the **first mutation
path — `sendMessage` — landed in [#346](../codebase/346.md)** (which also introduced the shared
`ack`/`error` request↔reply correlation primitive the remaining mutations reuse). The mutation slice
\#314 was split on a per-method axis into #346 (`sendMessage`) / #347 (`createDiscussion`) / #348
(`promote`), each extending the **same class** as it lands. Portable, `android.*`-free.

> **Constructed per connection; consumed through the stable facade.** The
> [`RelayRepositoryCoordinator`](relay-repository-coordinator.md) constructs one remote repository
> against each connection's pump and child scope, publishing it on `currentRepository` when the
> Noise pump is open. Normal builds select the facade through `conversationRepositoryModule`;
> `-PuseRelayRepository=false` selects `FakeConversationRepository` for a demo. The build flag
> selects the UI repository; pairing, lifecycle and connection readiness determine whether a live
> delegate exists. See [live binding](remote-conversation-repository-state-errors-and-handoff.md#hand-off--the-live-binding)
> and [dependency injection](dependency-injection.md).

## Map

Split on 2026-09-05 to keep this document under the 50000-byte cap the docs guard enforces. Each section named below moved verbatim, heading and anchors intact, into its own document:

- [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md) — `The repository — one projection, cold fan-out`, `observeConversations(filter) — the live method`, `observeLastMessage(conversationId) — the live last-message preview (#329)`, `observeMessages(conversationId) — the live thread read (#313)`, `The unified thread store and the session_transition fold (#336)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — send, create, promote and rename](remote-conversation-repository-send-create-promote-rename.md) — `sendMessage(conversationId, text) — the first mutation (#346)`, `createDiscussion(workspace) — the second mutation (#347)`, `promote(conversationId, name, workspace) — the third mutation (#348)`, `rename(conversationId, name) — the fourth mutation (#530)`
- [Remote repository — session settings and conversation writes](remote-conversation-repository-conversation-writes.md) — agent switching, session-settings reads and writes, archive/unarchive, mute, delete, workspace change, and channel/host system prompts.
- [Remote conversation repository — the Phase 4 `ConversationRepository` — workspace folders, recent workspaces and push registration](remote-conversation-repository-workspace-and-push.md) — `createWorkspaceFolder(name) — the tenth mutation, leanest write-verb, first override of a previously-defaulted read/write pair ([#564](../codebase/564.md))`, `recentWorkspaces() — the fourth read verb, leanest of the family, no fold ([#565](../codebase/565.md))`, `registerPushToken(token) — the device-concern push registration (#359)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — live stream, modal seams and the replay cursor](remote-conversation-repository-live-stream-and-modals.md) — `liveSessionEvents — the v2 structured-stream decode seam (#385)`, `modalEvents — the v2 permission/choice-modal decode seam (#437)`, `answerModal / cancelModal — the v2 modal answer/cancel control-send (#438)`, `recordReplayCursor(envelope) — the replay-cursor side-write (#412)`, `The resync arm — reset the cursor + surface the gap (#417)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — thread-observable states and live tool rows](remote-conversation-repository-thread-observables.md) — `observeStall(conversationId) — the thread-observable stall state (#395)`, `observeQueue(conversationId) — the thread-observable queued backlog (#460)`, `observeApiRetry(conversationId) — the thread-observable API-retry state (#593)`, `observeCompacting(conversationId) — the thread-observable compaction state (#596)`, `observeThinkingProgress(conversationId) — the thread-observable thinking-progress reading (#801)`, `Live tool-call rows — applyToolUse / applyToolResult (#387)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — screen snapshot, dequeue, interrupt and new session](remote-conversation-repository-control-sends.md) — `requestScreenSnapshot(conversationId) — the parser-independent screen-snapshot read (#375)`, `requestHistory(conversationId, cursor, limit) — the on-disk history page read (#623)`, `dropQueuedMessage(conversationId, queuedMessageId) — the dequeue_message outbound send (#466)`, `interrupt(conversationId) — explicitly targeted v2 interrupt`, `startNewSession() — explicitly targeted v2 new_session`, `answerQuestionBatch(questionBatchId, answers) / refuseQuestionBatch(questionBatchId) — the v2 question_answer / question_refused sends (#825)`
- [Remote conversation repository — the Phase 4 `ConversationRepository` — state and concurrency, error handling and the hand-off](remote-conversation-repository-state-errors-and-handoff.md) — `State & concurrency model`, `Error handling`, `Hand-off — the live binding`

- [Remote repository — testing](remote-conversation-repository-testing.md#testing) — fake-pump scheduling and read-evidence invariant probes, split for #2070.

The sections that stay here: `## Where it sits in the Phase 4 stack`, `## Status projections: one file per status event`, `## The repository split (#912–#916): complete`, `## The SessionPump consumed contract`, `## Stubs — none remain; every method is now live`, `## Daemon conversation read marks`, the testing pointer and `## Related`.

## Where it sits in the Phase 4 stack

```
UI ViewModels  ◀── observeConversations(filter): Flow<List<Conversation>>
        ▲
StableConversationRepository   ◀── normal build binding; switches over coordinator.currentRepository
        ▲
RemoteConversationRepository (#312+)   ◀── this doc
        │  send(list_conversations) ; collect inbound conversations snapshots
        ▼  (over the SessionPump interface)
NoiseSessionPump (#309) ─ inbound: Flow<Envelope> / send(Envelope): Boolean
        ▼
RelayTransport (#306) ─ OkHttp WS ─ Noise_IK (#303)
```

The repository consumes the **portable `SessionPump` interface**, without reaching
below it to the raw transport or Noise session. Conversation wire-to-domain mapping
belongs to the [payload mappers](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope).
Authenticated payloads still require validation: the connection-owned
`DebugBundleTransfer` validates diagnostic chunk fields before retaining opaque
bytes. It shares the sole inbound consumer and exposes a separate
[host transfer API](relay-debug-bundle-transfer.md),
outside `ConversationRepository` and screen state. `AttachmentUploadTransfer` (#829) is its upload-leg
sibling — also connection-owned, also routed first in `onInbound`, but **on** the
`ConversationRepository` surface (`uploadAttachment`) rather than beside it, since the destination is a
conversation, not the whole host. See [Attachment upload](attachment-upload.md). `AttachmentRetrievals`
(#899) is the retrieval-leg driver, routed immediately after the upload leg in `onInbound` and ended
beside it in the collector's `finally`; it correlates by the request's own envelope id rather than the
chunk's, since every answering frame — chunk or `error` — names the request. See [Attachment
retrieval](attachment-retrieval.md).

## Status projections: one file per status event

See [status projections](remote-conversation-repository-status-projections.md#status-projections-one-file-per-status-event)
for legacy event ownership and [supplied session admission and retirement](remote-conversation-repository-status-projections.md#supplied-session-ownership-and-family-retirement-2084),
including settings arrivals, prompt ownership and preservation of durable/queue/account state.

## The repository split (#912–#916): complete

Five tickets moved everything out of `RemoteConversationRepository` that was not routing: the thread
store into `ThreadProjection` (#912), the conversation list and last-message previews into
`ConversationListProjection` (#913), conversation-scoped commands (create, promote, rename, archive,
unarchive, delete, new session, interrupt, push-token registration, modal answer/cancel) into
`ConversationCommands` (#914), message and transfer commands (sending, uploads, the screen snapshot,
dequeue, the debug bundle) into `MessageCommands` (#915), and session settings, the system prompt and
workspace management into `SessionSettingsCommands` / `WorkspaceCommands` (#916). Each moved cluster kept
its behaviour, names and KDoc; the repository holds a one-line hand-off for every public method that
moved. No further cluster is scheduled to move.

What the repository still owns, after #916:

- **The single inbound collector and `onInbound`'s demux** — the one `pump.inbound` consumer, routing
  every envelope type to the right projection, command class or waiter. The two routing sites #916 left
  in place: the `session_transition` arm calls `sessionSettingsCommands.bumpSettingsRevision`, and the
  `TYPE_WORKSPACE_UPDATED` arm applies the label through `conversationListProjection.applyWorkspaceLabel`
  and calls `workspaceCommands.malformedWorkspaceReply()` on a decode failure.
- **Every status projection** the thread reads from: `ConversationListProjection`, `ThreadProjection`,
  `StallProjection`, `QueueProjection`, `ApiRetryProjection`, `CompactingProjection`, `UsageLimitProjection`,
  `ThinkingProgressProjection`, `ResettingProjection`, `ModelMenuProjection`, `SlashCommandMenuProjection`,
  `QuestionBatchProjection`,
  `BackgroundTaskProjection`, `AnnouncedModelProjection`, `SessionFactsProjection`,
  `AttachmentOfferProjection` and `McpStatusProjection` (#1343) — each its own small class, constructed once per repository instance, per
  the split described above in § Status projections.
  `SlashCommandMenuProjection` (#882) is the one member with no send and no capabilities supplier of its
  own — the frame it retains declares no inbound verb, so it takes the `ApiRetryProjection` minimal shape,
  not `ModelMenuProjection`'s (which also owns the `request_model_list` ask). `AttachmentOfferProjection`
  (#898) is the one member whose `onInbound` arm ignores the negotiated capabilities entirely — see
  § Status projections above.
- **`RelayRequests`** — the one request-id counter and reply-waiter table every command class, and the
  repository's own remaining reads, share.
- **The reads that fan out directly to a projection, with no command-class indirection**:
  `observeConversations`, `observeReadMarks`, `observeMessages`, `observeLastMessage`, `observeStall`, `observeQueue`,
  `observeApiRetry`, `observeCompacting`, `observeResetting`, `observeUsageLimit`,
  `observeThinkingProgress`, `observeModelMenu`, `observeSlashCommandMenu`, `observeAnnouncedModel`,
  `observeSessionFacts`, `observeAttachmentOffers` (#898) and `observeMcpStatus` (#1343, plus its three
  request sends and two end-wait calls, all on `McpStatusProjection` directly, with no command-class
  indirection).
- **`requestHistory`** — the on-disk history page read; kept here because it folds its page straight into
  `ThreadProjection`, and #916 explicitly left it in place.
- **The v2 structured-stream and modal decode seams** — `liveSessionEvents`, `modalEvents`,
  `answerQuestionBatch` / `refuseQuestionBatch` (fold into `QuestionBatchProjection`), and the replay
  cursor (`recordReplayCursor`, the resync arm).
- **The four command classes, as constructed dependencies, plus a one-line hand-off for each of their
  public methods**: `conversationCommands` (#914), `messageCommands` (#915), and `sessionSettingsCommands`
  / `workspaceCommands` (both #916).

## The `SessionPump` consumed contract

A minimal **consumer-defined** interface — the data layer's view of the Noise session pump:

```kotlin
interface SessionPump {
    val inbound: Flow<Envelope>            // hot, single-consumer, decrypted app envelopes
    fun send(envelope: Envelope): Boolean  // false if the session is not Open; never throws
}
```

It lives in `data/repository/` (next to its consumer), **not** in `data/network/` (where the concrete
`NoiseSessionPump` lives), mirroring the [`ConnectionStateSource`](connection-state.md) precedent: the
consumer defines the contract it needs; the real impl satisfies it a layer down. The two members match
`NoiseSessionPump`'s structurally, so the DI slice makes the concrete class conform with just
`: SessionPump` + two `override`s.

`inbound` is **hot and single-consumer** (the pump runs the one inbound collector regardless of
subscribers and surfaces each decrypted `Envelope` exactly once). `send` returns `false` (no throw) if
the session is not `Open`. Note the pump's consumer-awareness rule: a `false` return on an `Open` session
means the session is **spent** (a nonce was consumed) — do **not** re-send the same envelope on the same
session; the supervisor will reconnect with a fresh handshake. The repository does not buffer or retry —
see [Error handling](remote-conversation-repository-state-errors-and-handoff.md#error-handling).

## Stubs — none remain; every method is now live

The three live read paths plus `sendMessage` (#346) / `createDiscussion` (#347) / `promote` (#348) /
`rename` ([#530](../codebase/530.md)) / `startNewSession` ([#539](../codebase/539.md)) /
`setSessionSettings` ([#543](../codebase/543.md)) / `archive` / `unarchive` ([#549](../codebase/549.md)) /
`delete` ([#532](../codebase/532.md)) / `changeWorkspace` ([#560](../codebase/560.md)) /
`createWorkspaceFolder` ([#564](../codebase/564.md)) / `recentWorkspaces` ([#565](../codebase/565.md)) /
`requestHistory` (#623)
cover every method the interface declares that this repository overrides — `changeWorkspace` was the
**last** `UnsupportedOperationException` stub (#549's doc named it as the "remaining throwing sibling");
`createWorkspaceFolder` and `recentWorkspaces` were separate, interface-default (not throwing-stub)
methods that #564 and #565 respectively later gave live overrides. No method on this class throws an
unimplemented-stub exception, and no method still falls back to an interface default, any more. All
three read paths are **cold flows that defer work to collection** (the eager expression-body `throw`
shape #312's NIT flagged is gone with the last read stub).

`override val mutationsSupported: Boolean = false` (#507) still hardcodes the capability off even though
every mutation is now wired live ([#560](../codebase/560.md) closed the last stub) — flipping it is a
deliberate, separate coarse-flag milestone (the #537 family: "gate cleared ≠ buildable"; each
mutation-consuming affordance needs its own reachability check before the flag can safely flip). A UI
gating consumer reads it (through the [facade](stable-conversation-repository.md)) to hide these actions
until that milestone lands. See [`../codebase/507.md`](../codebase/507.md).

<a id="opt-in-thread-negotiation-2244"></a>

## Required thread negotiation

Production supplies the saved host identity to the coordinator, repository and thread provider.
Hello requests `thread` and `interactive` without a legacy content replay cursor.
The screen binds to that exact provider and suppresses cached content for Missing hosts.
There is no legacy content fallback. Explicit legacy fixtures retain their own capability list.
Ticket #2246 owns the separate genuine restart scenario and named captures.

`ThreadSupport` starts Unverified. Only the current authenticated `PumpState.Open` acknowledgement
grants Supported, and it must grant both `thread` and `interactive`; every other granted set is Missing.
Saved items and previous acknowledgements never grant support. Losing or retiring Supported returns
Unverified, including direct replacement and permanent close. Missing stays through disconnect and
handshaking until a later capable acknowledgement replaces it. The host row receives the derived
`threadMissing` flag; see [its caption and reconnect control](channel-list-screen-tree-and-controls.md#host-row-reconnect-control-840).

The verdict and content requests belong to the exact host and connection. Verdict writes check the
current connection/transport under the coordinator monitor. Cancelling switched collectors alone leaves
a scheduling window for retained synchronous callbacks: the repository capability supplier also checks
the current transport and connection child scope at call entry. `threadSupport.value` checks live
ownership as well. A retired pump's late Open cannot restore support or reopen queries. Host-keyed
stores retain separate items across reconnects even when hosts reuse conversation and item IDs;
negotiation changes no ordering, deduplication, paging, settlement or read semantics.

For a required-thread host, collecting `observeThreadSnapshot` emits the existing legacy projection without
sending backfill. `requestHistory` and `requestAttentionHistory` throw
`IllegalStateException("legacy_content_request_refused")` without sending, before and after negotiation.
Authoritative open/page requests refuse before acknowledgement, for Missing, and after retirement;
only the current supported connection can resume them. Metadata/control traffic remains available.
New diagnostics contain only `event=thread_negotiation verdict=supported|missing|unverified` (one static
verdict per event) or `event=legacy_content_request_refused`, without IDs, epochs, capabilities or content.

## Daemon conversation read marks

`observeReadMarks(conversationId)` exposes default-tolerant
`ConversationReadMarks(readUpTo, latestEntryId, lastShownVersion, threadSupported)`
in the common history-entry number space (#1881, #2085). Confirmed `readUpTo`, legacy
activity `latestEntryId` and current shown availability remain separate. Null is
unavailable; zero is explicit. Thread support chooses `lastShownVersion` as the
unread watermark; legacy connections choose `latestEntryId`. Unavailable thread
metadata withholds an authoritative unread verdict and sight qualification, without
substituting legacy activity, zero or local `ReadPosition`. On legacy connections,
a present `readUpTo` establishes shared read support; otherwise local fallback remains.
Summary receipt never marks content read. Attention prompt/running precedence stays
unchanged; notification coverage also requires usable shown metadata on thread connections.

On legacy connections, `latestEntryId` is the filtered unread watermark, not the highest received durable
id (#1948). Live and replay receipt and fetched history exclude exactly `turn_state`,
`stall`, `api_retry`, `compacting` and `session_transition` when raising it, matching
the daemon's watermark and mark-read clamp. Every other durable type counts,
including unknown types and malformed payloads with valid identities. A status-only
page leaves latest unavailable or preserves the held value; original status entries
and IDs still reach history, replay and read evidence. Daemon list watermarks remain
authoritative merge inputs. Filtering before the monotonic merge matters: once a
status tail has raised latest, a lower daemon watermark cannot undo it. Reconnecting
and fetching history must apply the same filter or the tail returns as unread.

`ConversationListProjection` keeps rows and the conversation-keyed fact ledger in
one atomic state. List snapshots replace rows while confirmed read and legacy latest
merge by unsigned maximum; updates preserve held latest because they supply no
latest id. Thread snapshots/updates replace shown availability: omitted, null or
malformed shown metadata invalidates an older usable watermark. Valid shown values
merge monotonically while available; legacy receipt cannot raise shown metadata.
Empty/excluding snapshots retain the ledger. Session replacement does not reset
confirmed read. Separate remote instances isolate hosts with colliding conversation
ids; a fresh connection starts with fresh facts and support. Read facts remain outside
persistent cache and `HostReadings`.

`markConversationRead(conversationId, upTo: ULong): Result<ULong>` delegates to
`ConversationCommands`. It registers the request identity before sending and waits for
its correlated `conversation_updated`. The command validates the reply type, target
conversation and present valid read mark before folding the update and completing the
waiter. A plain ack, wrong target/type or missing/malformed mark fails without folding.
Unsolicited pushes can update observation but cannot complete the request. The returned
value is the stored monotonic mark after confirmation, which may be clamped below the
requested id or unchanged for a no-op; sending alone never advances the projection.
Server errors, send refusal and transport teardown return sanitized failures; caller
cancellation propagates. The command layer performs no retry; qualified work uses the host-owned retry path below.
See [wire validation and confirmation](mobile-protocol-v2-wire-layer.md#daemon-read-fields-and-confirmation).

**Authoritative foreground evidence (#2085).** The supplied presentation uses the
store-certified complete checkpoint, usable shown summary and carried foreground
sight of scoped row keys/exact revisions above confirmed read. Receipt, highest item
revision and `completedVersion` cannot certify catch-up. The newest row must currently
be visible with reveal complete, and intermediate unread appends need their own sight.
Retired composition, host, epoch, generation and captured connection bindings reject
new qualification. See [foreground checkpoints](thread-screen.md#what-it-does).
Production binds this seam to its exact current connection. Sync/cache and queue
settlement retain their existing daemon-owned facts.

An admitted root turn ending requests attention recovery and conversation-summary
metadata. A child ending refreshes only an already-open destination query and summary.
Summary metadata never certifies a thread revision. Only the correlated terminal marker
does. Query leases are counted on the producing repository and released idempotently.
Model-catalog clear/fresh ownership is separate from current-session ownership, so borrowed
or unresolved vocabulary cannot grant prompt, status or settings authority.

Applying a remembered model to a new chat explicitly requests a fresh session
reading. Observation alone sends no request. The subsequent write stays on the exact
host repository that supplied that reading, before navigation into the new chat.

**Legacy receipt evidence (#1912).** `ThreadSnapshot.readEvidence` binds durable
`history_entry_id` claims to the exact row version they produced, across history pages,
direct live delivery and reconnect replay, including every delta folded into a streaming
row. Envelope ids, replay `event_id`, a list's latest id and fetched rows never prove
sight. A valid non-excluded live id raises the unread watermark without any viewport. Each
entry gets a fact: visible, deliberately nonvisual (state frames, info banners, menus,
MCP and usage reports, plain background-task lifecycle) or unknown. A malformed or
unsupported entry, a hole in received ids or a persisted gap stops the checkpoint before
it. A presented row's checkpoint is its highest claim, extended only through contiguous
nonvisual receipt. Live content without an id is held as an unidentified barrier keyed
by type, normalized timestamp and payload; only an ordinary matching history entry
clears it, and no extra fetch is made to find it. Valid timestamps are canonicalized on
both lanes, since `2026-10-07T00:00:00.1234Z` parses to `.123400Z`.

Temporary permission and question controls are not durable content. On the legacy interactive
lane, a strictly decoded `modal_shown` of class `permission` or `question_shown` without a
durable identity does not create an unidentified history barrier. These controls create no
history fact and grant no read checkpoint. The viewport still refuses sight while the prompt
obscures content. Unknown modal classes, malformed controls and any unexpected durable
control entry retain the conservative barrier. `ThreadReadRuntimeReceiptsTest` covers both
valid controls and the negative cases.

Unread classification and evidence classification are independent. Excluding a
status type from latest grants no sight and bypasses none of the unknown, malformed,
missing-identity or receipt-gap barriers. Conversely, a strictly decoded
`RateLimitedPayloadDto` is understood nonvisual receipt in an interactive session,
including its benign `allowed` clearing edge and opaque future statuses. Its
`toReading()` mapper can return null for a valid clearing edge, so mapper nullability
cannot decide receipt validity. `rate_limited` still raises unread; it extends a
checkpoint only after foreground presentation of a row. Malformed usage windows
and non-interactive receipts remain barriers.

Compatible runtime receipts preserve numeric continuity without creating sight (#1989).
Daemon [#3026](https://github.com/pyrycode/pyrycode/issues/3026) repaired omitted
`main_turn_opened` IDs by projecting known runtime facts as existing empty info-banner
receipts with their original IDs and timestamps. Mobile decodes these as nonvisual evidence without a new runtime-type allowance. Removing receipt ID 2 from
an otherwise valid page still blocks the checkpoint even when stored `gaps` is empty.
Controlled reproduction established daemon ownership; historical live captures
lacked IDs, leaving their exact sequence unproven.

Queued-send facts need the same continuity contract (#2070). The displayed settled
scripted reply retained exact claims 9/10, but received IDs 7 and 9 surrounded omitted
`send_delivered` ID 8. Two observations ten seconds apart retained that binding and
hole with no unknowns, unidentified entries or stored gaps: neither version loss nor
collector timing caused the null checkpoint. A total binding count and zero malformed
counts cannot establish the selected reply's continuity. Daemon
[#3167](https://github.com/pyrycode/pyrycode/issues/3167) repaired live publication and
legacy paging of queued acceptance/delivery as existing empty info-banner receipts.
Mobile's production evidence rules remain unchanged. With receipts 1–12 accounted for,
the represented reply's claims 9/10 extend to checkpoint 12. Unknown/malformed receipts,
unidentified content, numeric holes and stored gaps still block; receipts, overlapping
history and reconnect never grant an unseen version sight. Only foreground presentation
and correlated daemon confirmation qualify the phone read. A peer confirmation may
clear list attention without reopening the phone thread. See the
[controlled probes](remote-conversation-repository-testing.md#testing) and
[ordered-pair evidence](../../e2e-interactive-stream.md#scripted-ping-receipt-continuity-2070).

Receipt accounting, the exact presented version, client latest and correlated
confirmation must agree. Daemon [#3029](https://github.com/pyrycode/pyrycode/issues/3029)
repaired a second mismatch: stored visibility excluded successful `turn_end` from the
clamp while its unchanged wire payload raised mobile latest. Legacy payloads carry no
stored visibility, so mobile cannot infer it to repair that disagreement. The repaired
legacy target uses eligible wire types and validated runtime receipts with mobile’s
five status exclusions. Completed-reply checkpoint/latest/confirmation is 6/5/5 with runtime enabled,
5/4/4 disabled. Fetching history or receiving only a receipt sends no read command, and
sending the qualified request still requires correlated daemon confirmation. See the
[full live evidence](../../e2e-interactive-stream.md#verification-status).

Live evidence must describe the row the projection actually drew. Re-reducing one
envelope in isolation is wrong when the fold depends on earlier state or on a locally
assigned time: the falling `compacting` edge draws a divider only after a rising one,
so live claims start from the conversation's current compaction fold, and a stopped-turn
summary is matched on its displayed fields, not its phone-clock time.

`ReadCheckpointRetries` keeps the highest qualified checkpoint per host connection
source and conversation for the app process. It retries on that host's next connection,
even after the thread closed, and clears only on a confirmed mark at or beyond it. A
failed/malformed reply or clamped/no-op confirmation below the pending target keeps it,
while peer confirmation at or beyond the target clears it. Work never crosses hosts.
Legacy daemons that never report `read_up_to` receive no command and keep local
`ReadPosition`. Thread support with unavailable read/shown facts withholds qualification. The thread
ViewModel shares one snapshot subscription for rows and evidence, because each cold
subscription sends its own `backfill_since`.

The [stable facade](stable-conversation-repository.md) switches observation to the
current live delegate with null while disconnected. A write snapshots its entry
delegate once, returns failure when absent and never retries on a replacement. Cache
wrappers forward both methods without restoring read facts. The fake uses its numbered
conversation history, applying `max(held, min(upTo, latest))` atomically and failing for
unknown conversations. Both `startNewSession` and `changeWorkspace` retain messages,
authored boundaries and the checkpoint: replacing history while retaining the mark
would reset latest ids and put new unread entries behind an already-read checkpoint.

## Testing

See [repository testing and read-evidence invariant probes](remote-conversation-repository-testing.md#testing).

For #2244, [PR #2248's verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2248#issuecomment-6242623814)
records Linux validation of `6eac1ecfa2396762ccffdda69700f3b67d0776df` on 2026-10-17:

| Dispatcher suite | Executed / passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| JVM | 5,881 / 5,881 | 0 | 0 |
| Device UI | 334 / 334 | 0 | 1 |
| Scripted-all | 22 / 22 | 0 | 0 |

The fresh JVM JUnit report contains all 11 `NegotiatedThreadActivationTest` methods, both
`ThreadMissingHostRetryTest` methods and all three `TreeHostRowThreadMissingTest` methods, each passed
with zero failures/errors/skips. These executed within the full JVM suite: capability combinations,
sticky Missing, supported reconnect, retirement and retained callbacks, colliding hosts, zero content
requests for Missing, legacy snapshot/history/attention refusal, supported paging, unchanged production
configuration, static logs, connected host-specific redial, lifecycle fences, folded caption and
app-update priority. The unrelated `RenameDialogCaptureTest.renameAtFigmaViewport` was skipped, not passed.
macOS is outside this Linux ticket's scope; this opt-in slice requires no live capture.

The JVM report was embedded in `$AGENTS_REPO_PATH/logs/verifier-gate_#2244_5.log` at review time;
verifier evidence bundle `4cfe914579e9409b117ae8396de03fbe06c860fef7ecbeb4f475aba8a6dda51f`
retains the verdict and gate-log captures. The builder's baseline remains 2 executed, 2 failed, 0 skipped:
unwanted backfill and a retained query accepted at transport replacement. Those failures motivated the
unconditional legacy refusal and synchronous ownership checks above; they are not passes.

## Related

- Contract + Phase 1 binding: [Conversation repository](conversation-repository.md)
  (`ConversationRepository` interface, `ConversationFilter`, `ThreadItem`; the in-memory
  `FakeConversationRepository` this is the live counterpart to).
- Consumes: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)) — the `inbound` /
  `send` surface, over the `SessionPump` interface. [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md)
  — `Envelope`, `MobileJson`, and the [#316](../codebase/316.md) `ConversationsPayload.toConversations()`
  decode-and-validate boundary. [Data model](data-model.md) — the domain `Conversation` it produces.
- Precedent: [Connection state](connection-state.md) — the consumer-defined-interface-in-`data/repository/`
  pattern `SessionPump` follows.
- Ticket notes: [`../codebase/312.md`](../codebase/312.md) (the list read path) ·
  [`../codebase/329.md`](../codebase/329.md) (the last-message preview) ·
  [`../codebase/313.md`](../codebase/313.md) (the thread read + backfill) ·
  [`../codebase/346.md`](../codebase/346.md) (`sendMessage` + the `ack`/`error` correlation primitive) ·
  [`../codebase/347.md`](../codebase/347.md) (`createDiscussion` + the `upsertConversation` confirmed-insert) ·
  [`../codebase/348.md`](../codebase/348.md) (`promote` + the `cwd`-resolution decision) ·
  [`../codebase/359.md`](../codebase/359.md) (`registerPushToken` — the first non-interface device-concern
  method + the deferred `deviceName` handoff) ·
  [`../codebase/543.md`](../codebase/543.md) (`setSessionSettings` — the first session-scoped mutation,
  no state fold, new reply-type demux arm) ·
  [`../codebase/549.md`](../codebase/549.md) (`archive`/`unarchive` — shared request DTO for a symmetric
  verb pair, the `is_archived` decode-boundary extension, the dormant `rename` fix it carries) —
  files/line refs, patterns, lessons, verification.
- Specs: `docs/specs/architecture/312-remote-conversation-repository-observe-list.md` ·
  `docs/specs/architecture/329-remote-conversation-repository-observe-last-message.md` ·
  `docs/specs/architecture/313-remote-observe-messages.md` ·
  `docs/specs/architecture/346-remote-send-message.md` ·
  `docs/specs/architecture/347-remote-create-discussion.md` ·
  `docs/specs/architecture/348-remote-promote.md` ·
  `docs/specs/architecture/359-register-push-token-wire-sender.md` ·
  `docs/specs/architecture/543-wire-session-settings.md` ·
  `docs/specs/architecture/549-archive-unarchive-conversation-wire.md` ·
  `docs/specs/architecture/623-paged-conversation-history.md`.
- Siblings (extend the same class + `onInbound` `when`): [#329](../codebase/329.md)
  (`observeLastMessage`, **landed** — consumes [#317](../codebase/317.md), rides the live `message`
  stream), [#313](../codebase/313.md) (`observeMessages`, **landed** — consumes #317 + adds the
  `backfill_since` → `message_chunk` thread plumbing; boundaries #336 / streaming #337 de-scoped, both
  blocked on server-side v2 protocol additions), [#346](../codebase/346.md) (`sendMessage`, **landed** —
  the first mutation; added the shared `ack`/`error` correlation primitive), [#347](../codebase/347.md)
  (`createDiscussion`, **landed** — the second mutation; consumes [#318](../codebase/318.md)'s
  `ConversationResponseDto.toConversation()`, reuses #346's correlation primitive, and adds the
  `upsertConversation` confirmed-insert into the list projection), [#348](../codebase/348.md)
  (`promote`, **landed** — the last #314 mutation; consumes #318's `ConversationResponseDto`, reuses
  #346's primitive + #347's `upsertConversation` fold verbatim, adds the `promote_conversation` request
  encoder + the null-`workspace` `cwd` resolution), [#359](../codebase/359.md) (`registerPushToken`,
  **landed** — the **first non-interface** device-concern method; reuses #346's `sendAndAwaitReply` +
  `mapError` verbatim with **no** `onInbound` branch and **no** projection mutation, adds the
  `register_push_token` request encoder + the last/defaulted `deviceName` ctor param), [#365](../codebase/365.md)
  (`registerPushToken`'s **first live caller**, **landed** — the coordinator's connect-time hook re-sends it
  once per connection and threads the live `deviceName`, closing #359's `device_name: ""` defer; live since
  [#361](../codebase/361.md) added the token origin (`PyryMessagingService.onNewToken`) and turned the
  hook into a rotation-aware collector — see [push messaging service](push-messaging-service.md)),
  [#395](../codebase/395.md) (`observeStall`, **landed** —
  the fourth projection `stalledConversations`; a `TYPE_STALL` onset arm + a clearing hook folded into
  #385's live-session arm; **on the interface with a `flowOf(false)` default** so it reaches the thread
  through the facade, the deliberate inverse of #385's concrete-only `liveSessionEvents` — see
  [Stall state](stall-state.md)), [#460](../codebase/460.md) (`observeQueue`, **landed** — the fifth
  projection `queuedByConversation`; a `TYPE_QUEUE_STATE` **full-replace** arm with **no clearing hook**
  (a snapshot is whole-truth state, the simpler counterpart to #395's onset-only model), **on the interface
  with a `flowOf(emptyList())` default** so the backlog reaches the thread through the facade — see
  [Queued backlog](queued-backlog.md)), [#387](../codebase/387.md) (`applyToolUse`/`applyToolResult`,
  **landed** — correlate `tool_use`/`tool_result` into one evolving `Role.Tool` row keyed by
  `toolUseId`; a `when (event)` dispatch folded into #385's live-session arm mutates the existing
  `threadByConversation` so tool rows interleave by arrival order, the inverse choice from #395's
  separate projection — see [Live tool-call](live-tool-call.md)), [#417](../codebase/417.md) (the
  **`resync` arm**, **landed** — `reset()`s the [`ReplayCursor`](replay-cursor.md) #412 records + #416
  advertises and `tryEmit`s a control-derived [`LiveSessionEvent.ReplayGap`](live-session-events.md) on
  the existing `liveSessionEvents`; a payload-less inline marker read structurally, no DTO — see
  [the resync arm](remote-conversation-repository-live-stream-and-modals.md#the-resync-arm--reset-the-cursor--surface-the-gap-417)), [#543](../codebase/543.md)
  (`setSessionSettings`, **landed** — the sixth mutation and the **first session-scoped** one (`sessionId`,
  not `conversationId`); reuses `sendAndAwaitReply` + `mapError` verbatim, adds a **new** reply-type demux
  arm (`session_settings_updated`, not a reused one like `rename`/`promote`), and is the first mutation
  with **no state fold** since `registerPushToken` — its ack carries only an echoed `session_id`, nothing
  to project. Data-layer slice of the #536 split; #544 wires the Status-sheet controls to it and adds the
  `StableConversationRepository` facade delegation), [#549](../codebase/549.md) (`archive`/`unarchive`,
  **landed** — the sixth and seventh mutations; replace their `UnsupportedOperationException` throws,
  reuse `sendAndAwaitReply` + `mapError` + the existing `TYPE_CONVERSATION_UPDATED` demux arm verbatim,
  add one shared `ArchiveConversationPayloadDto` + a private `sendArchiveToggle` helper both overrides
  delegate to, and extend `ConversationResponseDto` with a defaulted `is_archived` field so the fold
  actually moves the conversation between tiers — data-layer slice of the #531 split; #550 wires the
  ViewModel surfacing, #551 the rung-3 e2e), [#532](../codebase/532.md) (`delete`, **landed** — the eighth
  mutation and the **first REMOVE-shaped** one; replaces the interface-default throw, adds a genuinely
  **new** reply type (`conversation_deleted`, requiring an `onInbound` demux registration rename/archive
  didn't need), and folds via a new `removeConversation` helper that clears all three read projections
  rather than upserting one. `conversation.not_found` converges as success — the deliberate divergence
  from rename/archive's IAE-crash-on-not-found. `mutationsSupported` stays `false`; the rung-3 e2e is
  #554, Inbox, blocked by this ticket), [#593](../codebase/593.md) (`observeApiRetry`, **landed** —
  the sixth projection `apiRetryByConversation`; a `TYPE_API_RETRY` **full-replace** arm with **no
  branch on `active`** and **no clearing hook** (`toStatus()` owns edge semantics; unlike #395 this
  arm never touches the live-session arm, so it does not clear a stall), following #460's
  payload-carrying `Map` shape rather than #395's bare `Set` because the wire carries a counter, **on
  the interface with a `flowOf(NotRetrying)` default** so the retry state reaches the thread through
  the facade — see [API-retry status](api-retry-status.md); UI reaction is sibling #594, not shipped),
  [#596](../codebase/596.md) (`observeCompacting`, **landed** — the seventh projection
  `compactingConversations`; a `TYPE_COMPACTING` arm that clones #395's bare `Set` (not #593's `Map`,
  since the wire carries no counter) but — unlike #395 — has an **explicit falling edge** on the wire,
  so `if (active)` branches inside the arm itself rather than a mapper owning it; never touches the
  live-session arm, so it does not clear a stall or fold a thread row, **on the interface with a
  `flowOf(false)` default** — see [Compacting state](compacting-state.md); UI reaction is sibling #597,
  not shipped), #623 (`requestHistory`, **landed** — the on-disk history page read; reuses
  `sendAndAwaitReply` + `mapError` verbatim, adds a **new** reply-type demux arm (`history_page`, not a
  reused one) and, like `registerPushToken`/`setSessionSettings`, folds no projection — a page is
  returned to the caller and folded into the timeline by #645. `at_start` is the **only** end-of-log
  signal, strict-decoded with no default so an absent key fails rather than silently reading as "keep
  walking". No live rung: this slice has no operator-facing surface; scroll-back's live rung is #646),
  [#801](../../specs/architecture/801-thinking-progress-decode.md) (`observeThinkingProgress`,
  **landed** — the eighth projection `thinkingProgressByConversation`; a `TYPE_THINKING_PROGRESS`
  **full-replace** arm with **no branch and no clearing hook of its own** — the wire carries no falling
  edge, so its two clears fold into the existing `TurnEnd` branch of the live-session arm and the
  existing `TYPE_SESSION_TRANSITION` arm instead, following #593's payload-carrying `Map` shape rather
  than #395/#596's bare `Set` because the wire carries a reading, **on the interface with a
  `flowOf(null)` default** — see [Thinking-progress state](thinking-progress-state.md); UI reaction is
  an unfiled sibling of the same #653 split).
- Sibling transfer: [Attachment upload](attachment-upload.md) ([#829](https://github.com/pyrycode/pyrycode-mobile/issues/829),
  **landed**) — `uploadAttachment`, the `AttachmentUploadTransfer` upload-leg sibling of
  `DebugBundleTransfer`, the 8 MB local socket-queue bound, and the `StableConversationRepository`
  snapshot-or-result delegation it needed instead of the usual snapshot-or-throw.
- Sibling transfer: [Attachment retrieval](attachment-retrieval.md) ([#899](https://github.com/pyrycode/pyrycode-mobile/issues/899),
  **landed**) — `fetchAttachment`, the `AttachmentRetrievals`/`AttachmentRetrievalTransfer` retrieval-leg
  driver correlating by the request's own envelope id, one retrieval per connection, the
  512-chunk/23,040,000-byte phone-side bound, and the host-keyed `AttachmentStore` layer above this
  repository that keeps the verified bytes.
- Sibling observable: `observeAttachmentOffers` (#898, **landed**, PR
  [#931](https://github.com/pyrycode/pyrycode-mobile/pull/931)) — the ninth status-family projection,
  `AttachmentOfferProjection`, decoding `attachment_offered` into `AttachmentOffer`; the one arm in the
  family that ignores the `interactive` capability check, matching `attachment_stored`'s delivery
  posture above rather than its status-projection siblings'; standard `switchToLive(emptyList()) { … }`
  facade delegation. Fetching the offered bytes is the sibling ticket `#671`. Turning a first-seen offer
  into a thread row landed in `#983` (see § Status projections above); rendering that row is the
  follow-up ticket `#984`.
- Connection wiring: [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
  ([#351](../codebase/351.md), **landed**) — constructs this repository per live connection against the
  pump + a child scope, made `NoiseSessionPump : ManagedSessionPump : SessionPump`, and publishes the
  live instance on `currentRepository` (consumed by the **#352** facade). The flag-gated Koin binding
  selector that picks the Fake or the facade is **[#350](../codebase/350.md)**; since #631 it defaults
  to the facade, with the fake selected explicitly for demo builds and ordinary instrumentation.
</content>
