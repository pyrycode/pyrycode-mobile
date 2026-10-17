# Replies follow their connection's earlier requests (#2219, second cause)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `drain` releases a reply as soon as its own request is consumed; `eligible` lets a captured `close` through only after `precedingRequestsComplete`; `fail` makes the first failure sticky; `progress` is the teardown diagnostic.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `RecordedTransport.close` stores a rejected close lifecycle in the hub and still closes; `ReplayPump.send` refuses locally once closed. Both are unchanged by this ticket.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`, `cutLinkOn`, `LinkCut` and `setHostLink`. The cut closes the supervisor from inside the `RelayLog` sink, on the thread that logs the retrieval request.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentRetrieval.kt` (`retrieve`) and `app/src/main/java/de/pyryco/mobile/data/cache/AttachmentStore.kt` (`lead`): the request log line runs on the caller's dispatcher, which can be the thread `ViewModel`'s main dispatcher, and a refused send is absorbed as `Unavailable`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: the open's context, MCP and newest-history asks are independent coroutines (`askForContextUsage`, the `requestMcpStatus` collector, `launchNewestPageSideAsk`), so their order is not fixed.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `requestContextUsage` absorbs a send failure; `observeConversations` sends `list_conversations` on each subscription.
- `app/src/androidTest/assets/recorded/suite/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.json`: generation `phone/host-0/1` records `list_conversations`, `mcp_status_request` and `request_context_usage` before `request_history`. Its `history_page` draws the peer's file row, whose retrieval triggers the cut: close, refused `request_attachment`, `inbound_end`.
- `docs/specs/architecture/2219-atomic-replay-close.md`: the first cause and its regressions (`aRacingCloseCannotHideTheCapturedRefusedAttachmentRequest`, `aCapturedRefusalStillRejectsAChangedAttachmentAfterClose`), which stay.
- `docs/recorded-tests.md`, "Replay isolation" and the replay scheduling sections; `docs/knowledge/features/development-verification-test-scheduling.md`: force the scheduling condition rather than infer it from a pass.

## Context

The #2173 macOS UI gate ran on `00d9118fd3`, which already contains #2220's atomic close. It failed in step 4: `setHostLink(up = true)` timed out with the repository absent. The same-tree rerun passed. The retained output has no tape progress and no phone logcat, so the first tape failure was not observed directly.

Evidence about the stall:

- Steps 2 and 3 passed. The cut fired, `beforeHostLinkClose` returned, and the row showed Retry, so the transport was already locally closed.
- The next generation's `connect` never produced a repository. `RecordedSuiteTape.lifecycle` rethrows a sticky failure, so any tape failure in generation 1 stalls the reconnect in exactly this way.

The reachable cause is replay scheduling. The tape releases a reply as soon as its own request is matched. On the thread's open, the history ask can overtake the context or MCP ask (or the second list subscription). The page is then delivered without the 422 ms context reply ahead of it. The app draws the row and starts the retrieval, and the cut closes the link before the late ask is sent. `eligible` refuses that close, because a request recorded before it is still unconsumed. `RecordedTransport.close` stores the mismatch, the app absorbs the refused retrieval as Unavailable, and the next `connect` rethrows the stored failure. A JVM probe on the committed recording reproduced this exactly: in recorded order the tape completes, and with the context ask late the first failure is `Recorded suite lifecycle mismatch … phone/host-0/1:close, expected phone/host-0/1:request_context_usage …` and `connect phone/host-0/2` fails with it.

During capture, the connection had made every earlier request before the answered one, so a reply delivered ahead of them is not something the capture ever showed. Holding the reply restores the captured causality. Making the cut wait for the prefix would block the main thread inside the log sink while the late ask may itself be queued on main.

The interrupted-upload `step 17` mismatch in the same run is not explained by this cause. There, the close was already eligible and a third chunk reached the tape before any close. That scenario belongs to #2138; the finding is reported there.

No decision record is needed.

## Design

`RecordedSuiteTape` gains one private index, `requestSteps`, from `(owner, recorded request id)` to that request's normalized step index. It is built in `init` after the read-receipt, polled-read and optional-model normalizations. In `drain`, a daemon step with `inReplyTo` also waits until `precedingRequestsComplete(answered, owner)` holds, the same predicate captured terminals and closes already use. Normalized reads, polled reads and optional model replies keep their own paths. Request matching, payload comparison, refusals, counts and completion are unchanged.

`progress` appends `first-failure=<sticky failure>`. The finish path already attaches progress to a failed scenario, so a recurrence names its first tape failure. Failure texts carry owners, types, indices and counts only.

There is no production, recording or scenario change, and no timeout or retry change.

## State and concurrency model

Unchanged. `drain` runs under the tape monitor and is re-entered on every request and lifecycle event. A held reply is reconsidered on the next request that completes its prefix, so the late ask itself releases the reply in tape order. The delivery queue's cadence and the hub's lock are untouched.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| A reply's request overtakes an earlier ask on the same connection | `aReplyWaitsForEveryRequestItsConnectionMadeBeforeTheAnsweredOne` |
| A late thread ask, then the row's retrieval cut, close, refused request and the next generation's connect on the committed recording | `aLateThreadAskCannotLetTheInterruptedRetrievalCutBeforeItsRecordedClose` |
| Racing close, idempotent close and the refused request (#2220) | Existing `aRacingCloseCannotHideTheCapturedRefusedAttachmentRequest`, `aCapturedRefusalStillRejectsAChangedAttachmentAfterClose` |
| Restart, cut, Retry on the next generation and final teardown | The original recorded method, five consecutive focused executions |

## Error handling

A reply that is held forever leaves the scenario's next observation or the final checkpoint pending, and `progress` names it, as before. A late ask that never comes now fails at an observation or teardown with the held reply pending, instead of passing on a reply the capture never ordered.

## Testing strategy

JVM tests in `RecordedSuiteTapeTest`. The synthetic test proves the ordering contract. The committed-recording test plays the app's role: it defers the context ask, and only once the history page is delivered does it draw, mark read and cut. Without the hold it fails with the close lifecycle mismatch; with the hold the late ask is required first and the tape completes. Both tests run before the fix, and their failing results are retained. Then the tape and connection classes run again.

The named native method stays device-only because it needs Activity restart, real storage and background collectors. It gets five consecutive focused executions with retained XML and output. The dispatcher's configured UI gate runs the full recorded suite, which the shared drain change affects.

## Open Questions

- Does a normal device run show the history ask overtaking an earlier thread ask? A temporary, uncommitted trace answers this. Record the answer in Revisions.

## Documentation handoff

Pending documentation stage: `docs/recorded-tests.md`, replay scheduling: a reply waits for every request its connection recorded before the answered one. Also record the first-failure field in teardown progress. Link this plan's regressions and the five focused runs.
