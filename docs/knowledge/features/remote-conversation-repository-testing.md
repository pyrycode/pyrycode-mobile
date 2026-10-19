# Remote conversation repository — testing

Testing the [remote repository](remote-conversation-repository.md) and its read-evidence contract.

## Testing

JVM unit only (`app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt`, `./gradlew test`),
JUnit4 + `runTest` + a hand-written **fake `SessionPump`** — `inbound` backed by a
`Channel<Envelope>(UNLIMITED).receiveAsFlow()` (so test pushes are not lost before the collector attaches);
`send` records each envelope and returns `true`; a `push` helper feeds inbound. The repository is
constructed with `backgroundScope` so its collector auto-cancels at test end. `conversations` payloads are
built from the same object-wrapped-array fixture shape as `ConversationsPayloadTest` via
`MobileJson.parseToJsonElement(raw)`.

`AuthoritativeSendRepositoryTest` negotiates both thread and interactive capabilities
with an ack-only fake pump for typed reset commands (#2308). Check pending sends,
projected Sending rows and legacy snapshot rows both before and after ack: checking
only pending state would miss an accidental legacy fallback bubble. The matrix
covers both aliases, all 25 daemon whitespace characters and arguments, with case,
longer-name, later-token, zero-width-space and JVM-only separator nonmatches.
Repeated resets must retain unrelated pending feedback. A subsequent ordinary send
must stay Sending after ack until its matching authoritative item arrives, then
appear once as the last row even on duplicate delivery. Clearing all feedback on
ack or on reset would incorrectly satisfy a reset-only assertion. See
[send ownership and classification](remote-conversation-repository-send-create-promote-rename.md#sendmessageconversationid-text--the-first-mutation-346).

> **Test idiom (reusable across the sibling slices #313/#314/#329):** drive the push→demux→project→emit
> cascade with **`runCurrent()`, not `advanceUntilIdle()`**. With a `Channel.receiveAsFlow()` inbound
> feeding a single repository-internal collector on `backgroundScope`, `advanceUntilIdle()` does not
> deliver the buffered channel item to the background collector (there are no timers to elapse), leaving
> projection-dependent assertions empty; `runCurrent()` drains the whole current-time cascade
> deterministically. See [[remote-repo-test-runcurrent-not-advanceuntilidle]] and
> [`codebase/312.md`](../codebase/312.md) § Lessons learned.

`RemoteConversationRepositoryReplySuggestionTest` uses the real decoder/repository and
channel-backed fake pumps for 13 invariant probes. Coverage includes late subscription
after set and clear, every arrival order with duplicate/decreasing revisions, retained
clear watermarks and reconciled null, independent conversations/sessions/hosts, malformed
high revisions followed by lower valid updates, exact ASCII/multibyte byte limits and the
full unsigned revision range. It also probes non-interactive/default absence, connection
termination, [facade replacement](stable-conversation-repository.md#testing), cache
passthrough without storage access, and diagnostic redaction. The no-thread/turn probe
feeds both an event-id-bearing suggestion and a history entry: checking text alone would
miss accidental replay-cursor advancement, thread rows or live turn events.

`ConversationReadMarksTest` probes list/push arrival permutations, stale and duplicate
facts, empty/excluding snapshots, unsigned bounds, malformed input followed by valid
input, host/generation isolation, exact outbound integer JSON and confirmed-write
failures. Its two session-rotation regressions exercise repeated `startNewSession` and
`changeWorkspace` calls: assert retained old history ids, an unchanged latest id at
rotation, and new entries above the checkpoint that can be marked. Clamping tests
without a session boundary cannot catch a history reset.

The watermark regressions drive both live/replay and correlated history for every
excluded type, status-only pages, duplicates, reordered/overlapping pages and a
history reload after reconnect, while retaining original entries and IDs. They also
require later non-excluded content, including unknown types, to raise latest.
`ThreadReadClaimsTest.understoodUsageWindowReceiptsExtendPresentationButMalformedWindowsRemainBarriers`
separately guards decoded clearing edges versus malformed/non-interactive evidence;
an unread-only assertion would miss a valid receipt that still blocks phone reads.

`ThreadReadRuntimeReceiptsTest` decodes the serialized real-store examples from
[daemon PR #3034](https://github.com/pyrycode/pyrycode/pull/3034) in
`app/src/test/resources/daemon-contract/runtime-read-{enabled,disabled}.json` through
the real DTO, ordered reducer and projection. Its probes are
`runtimeReceiptRepairsTheDiagnosedHoleWithoutChangingContent`,
`invariantOnlyUnderstoodIdentifiedReceiptsAccountForTheHole`,
`invariantReplayAndOverlappingHistoryPreserveExactClaims` and
`invariantMissingLiveIdentityNeedsHistoryAndFreshConnectionHasNoSight`.
They retain omitted-ID, unknown/malformed, stored-gap, exact-version, replay,
missing-identity and fresh-connection barriers. Reordering moves the nonvisual receipt;
arbitrarily reversing content lifecycle entries is not an equivalent history page.
`ConversationReadMarksTest.runtimeReceiptHistoryRequiresCorrelatedDaemonReadConfirmation`
exercises both controls through the repository: no command before presentation, no
confirmation from sending or unrelated replies, clamped read/latest agreement, and
host/conversation isolation. A checkpoint-only assertion would miss the clamp defect.

`ScriptedPingReadEvidenceTest` retains the diagnosed queued-send hole through the real
projection. `deliveredReceiptHoleBlocksTheBoundSettledReplyAcrossRepeatedObservation`
requires the settled reply's claims 9/10 to survive repeated observation while missing
receipt 8 still blocks its checkpoint. `onlyUnderstoodReceiptCanCloseTheHoleWithoutGrantingAnUnseenVersion`
keeps unknown/malformed receipt and changed-version controls across duplicate and
overlapping history.

`repairedDaemonQueuedReceiptsQualifyOnlyTheExactRepresentedReply` decodes
`app/src/test/resources/daemon-contract/queued-read-repaired.json`, exported from daemon
[#3167](https://github.com/pyrycode/pyrycode/issues/3167)'s real-store completed-turn setup.
Acceptance/delivery info receipts 1/3 complete the sequence; reply claims 5/6 plus idle
receipt 7 yield checkpoint 7. Omitting delivery receipt 3 produced the retained controlled
failure (expected 7, actual null); restoring it passed. Receipt-only arrival has no reply
claim, and duplicate/overlapping pages cannot bind a changed unseen reply version.
These probes complement the runtime-receipt, read-claims, marks, retry and subscription
controls; a binding count or zero unknowns cannot substitute for checking numeric continuity.
See [counted unit and ordered-pair evidence](../../e2e-interactive-stream.md#scripted-ping-receipt-continuity-2070).
