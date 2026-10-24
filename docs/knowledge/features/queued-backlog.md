# Queued backlog — supplied pending and queued content

## Source and identity

Queued rows come from admitted authoritative `user_message` facts and local pending
feedback. Daemon status, device/message identity, order and numeric queue target
remain supplied facts. `observeQueue`, `QueueProjection`, raw queue-state settlement,
echo matching and inferred placement were removed in #2367. Equal text or a peer
client-id collision cannot match a phone pending send. A delivered fact cannot prove
that a send is still queued. Omission cannot prove delivery or loss.

Local pending feedback registers before asynchronous identity lookup/send and stays
until exact daemon acceptance. An ack proves receipt only. Pending feedback survives
reconnect/reset in memory, without resend or disk persistence. Canonical facts retain
their newest revisions and held positions across overlapping queries/pages.

## Queue actions and presentation

Drop and Send now use the current presentation owner's `AuthoritativeQueueTarget`.
The owning route checks source/generation/revision and sends the exact supplied
conversation/queued-message id. A control send never optimistically settles content.
A stale target, retired owner or unsupported route cannot select another connection.
See [control sends](remote-conversation-repository-control-sends.md).

Queued display, gesture behavior, tool grouping, Agent pinning and scrolling remain.
Literal component fixtures can supply presentation rows; they cannot certify a queue
state or replace the authoritative regression. The retained queued-follow-up stream
method binds text and phone identity to one current-owner queued fact and checks
reply continuity through completion. It runs independently beside the ordinary
stream method in managed `scripted stream` and `scripted-all`.
See [counted execution](../../e2e-interactive-stream.md#authoritative-runtime-removal-2367).

## Security and evidence boundaries

Queue text is operator-authored data. Never log it, item payloads, queue targets or
request identifiers. Detection capabilities are not daemon authorization. Runtime
content accepts no raw queue or roster reconstruction; independent producer peer
diagnostics remain test-only and cannot populate or certify the phone store.

## Historical placement evidence

The following pre-removal proof describes the retired runtime and retains its
original source attribution. Its algorithm is not a compatibility instruction.

### Verification evidence

[PR #1790](https://github.com/pyrycode/pyrycode-mobile/pull/1790) records the builder's
red-before/green-after history controls (3 executed, 2 failed, 0 skipped; then the pre-live-end
controls, 2 executed, 2 failed, 0 skipped), followed by 635 affected tests passed with
0 failed/errors/skipped and 4,577 full unit/shared tests passed with 0 failed/errors/skipped.
The final verifier report on `f27ed366` records 4,598 unit/shared tests passed, including
635 affected tests, with 0 failed/errors/skipped. Codec, legacy, ownership, duplicate-entry,
delayed-snapshot, history-before/after-push and fresh-connection probes are included.
Collectors advance and assert after **each** delivery, snapshot, delta and end: batching
before collection lets StateFlow conflate a transient order reversal or reply split away.
An overlap page must contain the delivered user row **and answering delta 0** on both sides
of the first push; a user-only overlap page misses lost placement eligibility.

The dispatcher gate report for that commit records UI coverage of 192 executed/passed,
0 failed and 1 skipped, and the full `scripted-all` suite of 19 executed/passed, 0 failed
and 0 skipped. The named
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_sendQueuedNow_placesAfterToolResult`
executed and passed in that full scripted suite. This is full-suite evidence; the PR separately
records the builder's focused `scripted send-now` pass (1 executed/passed, 0 failed/errors/skipped).
No real-Claude run or new ladder scenario is claimed. Existing peer-queue and held-tool
Send now coverage remains in [the real-Claude ladder](../../e2e-interactive-stream.md).
