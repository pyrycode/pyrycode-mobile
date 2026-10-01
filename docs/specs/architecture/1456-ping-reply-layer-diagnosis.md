# #1456 — name the layer that lost the post-interrupt ping reply

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` —
  `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` (step 5 is the flaking wait),
  `peerStep` (the layer-naming precedent from #1036), `hostConversationIds` (the registry read this
  mirrors for the phone repository).
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` — `recorded`, `awaitFrame`: the
  peer's frame log, a snapshot of frames naming the conversation.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/PingReplyAssertions.kt` —
  `pingReplyMatcher`, `awaitDisplayedPingReply`. Compose's `checkIsDisplayed` (ui-test 1.10.4) returns
  false for zero matches and throws for several, so the observed `ComposeTimeoutException` means zero or
  one non-displayed node for the whole 90 s.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt` — the live-connection read
  helpers; the diagnosis reads `RelayConnectionRegistry.connectionFor(serverId).coordinator.currentRepository`
  once, at the deadline, rather than following a redial.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadFold.kt` — `ThreadFold.reduce` /
  `render`, the VM-side live fold (candidate B).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadListFollow.kt` — `followStep`,
  `FollowNewestEnd`, `pinToNewest` (candidate C).
- `../pyrycode/cmd/pyry/interactive_turn_v2.go` — `startTurnIfNeeded` mints a fresh `turn_id` and resets
  `seq` per turn; `endTurn` clears both (candidate A).
- `docs/knowledge/features/streaming-assistant-turns.md`,
  `docs/knowledge/features/interrupt-send-path.md`,
  `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`
  (§ the `(turnId, seq)` dedupe, § `endedTurns`) — the post-interrupt behaviour the code reading checks.

## Context

One live occurrence: the Stop step passed (peer saw `turn_end` `cancelled`, phone showed Interrupted), the
host produced Claude's `ping` reply within a second, the phone's connection stayed up, and the phone never
displayed the reply bubble within 90 s. No frame log or logcat survived, so the three candidates —
(A) the daemon never sent the follow-up turn to the phone, (B) the phone dropped it in its live-event
reduction, (C) the bubble was composed but never displayed — cannot be told apart from the evidence.

This ticket makes the next occurrence say which layer lost the reply, and records a code reading of each
candidate on the ticket. No decision record is needed: the change is test-only diagnosis.

## Design

A pure diagnosis in shared test code, reduced to counts and booleans so the failure message never carries
claude-authored text, and a thin live wrapper around the existing wait.

`app/src/sharedTest/java/de/pyryco/mobile/e2e/PingReplyDiagnosis.kt` (shared so the JVM test sees it):

- `PingReplyEvidence(peerTurnEnds, peerSawReply, repositoryHoldsReply: Boolean?, replyNodes, replyDisplayed)`
  — what each layer held at the deadline.
- `PingReplyEvidence.failure(expectedTurnEnds, cause): AssertionError` — names the first layer that did
  not hold the reply, in order: host (the peer recorded neither the ping turn's `turn_end` nor its reply),
  phone repository (unreadable, or the peer got it and the repository holds none), thread screen (no
  matching bubble composed, or several), thread list (composed, not displayed). The message also lists
  every raw reading, so a reader can disagree with the verdict.
- `followUpPingReplyRecorded(frames, afterTurnEnds): Boolean` — the peer read: after the
  `afterTurnEnds`th `turn_end`, one turn's `assistant_delta` text joined in `seq` order, or an assistant
  `message`, reads `ping`.
- `holdsPingReply(items: List<ThreadItem>): Boolean` — the repository read: an assistant row reading `ping`.

`InteractiveStreamE2ETest`:

- `awaitPingReplyNamingLayer(peer, serverId, conversationId, priorTurnEnds)` — calls
  `awaitDisplayedPingReply(REPLY_TIMEOUT_MS)`; on `ComposeTimeoutException` gathers the evidence and throws
  `failure(priorTurnEnds + 1, cause = e)`, as `peerStep` wraps a peer timeout.
- `liveRepositoryHoldsPingReply(serverId, conversationId): Boolean?` — the current live repository's
  `observeMessages(conversationId).first()` within `THREAD_TIMEOUT_MS`, null when absent or silent.
- Step 5 of the flaking test calls the wrapper with `priorTurnEnds = 1` in place of the bare wait. The step
  order is unchanged: the peer's own `turn_end #2` wait still follows the phone's wait.

## State and concurrency model

Test code only. The live reads run once, on the instrumentation thread, after the wait has already failed:
`runBlocking` with a `withTimeoutOrNull` bound, no scope outlives the call. `peer.recorded` is a snapshot.

## Error handling

The diagnosis never throws over a malformed frame: a non-primitive payload field reads as absent. A
repository that is absent or does not emit reads as `null` and is reported as "unreadable" rather than
hiding the other readings. The original `ComposeTimeoutException` is kept as the cause.

## Testing strategy

- `app/src/test/java/de/pyryco/mobile/e2e/PingReplyDiagnosisTest.kt` (JVM): every verdict branch, the
  cause kept, the peer read ignoring a `ping` before the counted `turn_end`, joining out-of-order deltas,
  counting an assistant `message` but not a user one, rejecting a longer reply; the repository read
  matching only an assistant row.
- The live method is the acceptance run; it is device- and Claude-only by nature. The PR's `## Live tests`
  says `all`, since AC 4 asks for a full live suite run with this method executed and passed.
- `./gradlew compileDebugAndroidTestKotlin` proves the wrapper compiles; no device run of the live class
  from the builder (the gate owns it).

## Open Questions

- Does the code reading find a deterministic reproduction for B or C? If so, a pinning test and the fix
  land here under `## Revisions`; if A, a `pyrycode/pyrycode` ticket blocks this one.

  **Resolved before implementation:** no. The phone has no path that drops a fresh turn's rows once its
  `assistant_delta` and `turn_end` arrive (`HistoryPageReducer.withAssistantDelta` drops only a repeated
  `seq` or a colliding key, which a freshly minted `turn_id` cannot hit; `endedTurns` only settles;
  `ThreadFold.render` never removes a finished row). The follow rule cannot lose the newest end in this
  sequence: Stop, the Interrupted outcome and the composer sit outside the list, and the accepted send
  re-follows through `FollowNewestEnd`'s `sentMessages` effect. The daemon gives every turn a fresh id
  and fans every frame out to every interactive connection, so the code does not point at it either; its
  possible per-connection losses (`pushQueue` dropping `assistant_delta` under pressure, the drain's
  not-active gate) log at Debug only. No pinning test and no `pyrycode/pyrycode` ticket follow; the
  diagnosis is what tells the next occurrence apart, and the per-candidate reading is posted on the ticket.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only untrusted input is daemon-authored frame text and repository
  rows, read in test code by `followUpPingReplyRecorded` and `holdsPingReply`. Each reduces the text to a
  boolean comparison with the literal `ping`; no claude-authored text reaches the failure message, a log or
  the UI. `Envelope.field` in `PingReplyDiagnosis.kt` treats a non-primitive field as absent, so a
  malformed frame cannot throw and mask the verdict.
- [Tokens] No findings. The diagnosis reads no token or key; the peer's credentials stay inside
  `SecondClientPeer`, and `PingReplyEvidence` holds only counts and booleans.
- [Files and storage] No findings. Nothing is written; the repository read is in-memory.
- [Android attack surface] No findings. Test-only code under `sharedTest` and `androidTest`; no
  component, intent filter or manifest change.
- [Cryptography] No findings. No crypto touched.
- [Network and I/O] No findings. No new frame is sent; the diagnosis reads frames the peer already
  recorded and the repository's existing projection.
- [Errors, logs] No findings. The failure message carries counts, booleans and static layer names, never
  payload text, conversation text or ids beyond what the test already prints.
- [Concurrency] No findings. The repository read is one `runBlocking` bounded by `withTimeoutOrNull`,
  after the wait has failed; no coroutine outlives it.
- [Threat model] No findings for the diagnosis. A hostile daemon can at most make the verdict wrong; it
  cannot make the test pass, because the verdict is only built on the failure path.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02
