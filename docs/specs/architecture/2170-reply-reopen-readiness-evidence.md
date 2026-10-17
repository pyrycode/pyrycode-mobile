# Reply, reopen and readiness evidence migration (#2170)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `ThreadEvidence`, `ThreadEvidenceOwner.matches`, `ThreadEvidenceSource`, `harnessThreadEvidence`, `legacyThreadEvidence`, `authoritativeThreadEvidence`. The shared #2169 evidence this ticket consumes; `harnessThreadEvidence` stays the single legacy binding.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` takes a value only from the still-current source and cancels a replaced one.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PingReplyDiagnosis.kt`: `holdsPingReply` is the list predicate `liveRepositoryHoldsPingReply` uses today; `PingReplyEvidence` keeps its nullable "unreadable" meaning.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowStage.kt`: `sendNowStep` prints `links()` on a timed-out stage.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the ten `observeMessages` sites in `interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately` (capture, `assertStillOpen`), `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` (settled read, final equality), `interactiveTurn_wordReveal_displaysArrivedTextBeforeFinalReply` (capture, at-display, finished), `interactiveTurn_sendQueuedNow_reachesRunningTurn` (`ReadDeliveredRows`), `threadHoldsReply` and `liveRepositoryHoldsPingReply`; plus the send-now local `links`.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt`: recorded wrappers call all four scenario bodies directly, including the two whose originals carry `@Ignore`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` sets `isStreaming` only for an active assistant item and keeps queued acceptances out of delivered rows.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadUpdatePayloads.kt`: `ThreadItemDto` fields.
- `../pyrycode/docs/protocol-mobile.md`, "Full thread item" and "Queue (v2)": an accepted `user_message` carries `content.queued_msg_id`; clients must never infer it from `message_id`, text or item id.
- `docs/specs/architecture/2169-shared-thread-evidence.md`: ownership, settlement and identity rules this ticket keeps.

## Context

Required-thread activation will swap the harness binding once. These four scenarios and two diagnostics still read raw legacy `observeMessages` lists, so after activation they would observe nothing the daemon owns. This ticket moves them onto `harnessThreadEvidence` with predicates that mean the same thing on both sources, and proves the authoritative branch on the JVM. No production code changes and the production binding stays legacy. Measured against the table: about 450 written lines, no new exported types, ten consumer sites, four criteria. Many stale branches touch the suite file; none edits these methods (#2138 adds an unrelated method-local `links`). Edits there stay local to the scoped methods.

## Design

A new sharedTest file `ReplyEvidence.kt` adds extension predicates on `ThreadEvidence` and `ThreadEvidenceRecord`; no new types.

- `ThreadEvidenceRecord.isOpenReply()`: assistant and still receiving. Legacy: `message.isStreaming`. Authoritative: an `assistant_message` fact that is `active`.
- `ThreadEvidenceRecord.isFinishedReply()`: legacy `!isStreaming`; authoritative an `assistant_message` fact, inactive, status `done`/`finished`. A failed or interrupted item is neither open nor finished.
- `ThreadEvidence.openMainReply(accept)`: first open main-thread assistant record (no agent block, no raw/daemon parent) whose rendered content `accept`s; null when not admitted.
- `ThreadEvidence.reply(owner, id)`: the single record with that rendered identity, only when admitted and `owner.matches(this.owner)`. A reconnect, another host, a new epoch or generation, or a repair returns null, so a captured identity can never be satisfied by retired or colliding evidence.
- `ThreadEvidence.holdsAssistantText(token)`: any assistant record whose full daemon text holds the token (the `threadHoldsReply` diagnostic).
- `ThreadEvidence.holdsPingReply()`: a finished main-thread assistant record reading `ping`. An active partial, an Agent child or a user echo cannot qualify.
- `ThreadEvidence.deliveredQueuedUser(messageId, queuedMsgId)`: indices of the delivered queued user record. Legacy matches the app `message_id` as today; authoritative matches only a delivered `user_message` whose `content.queued_msg_id` equals the queue handle, never the message id. `ThreadEvidence.lastToolIndex()` gives the Bash row for the placement check.
- `StateFlow<R?>.liveThreadEvidence(conversation, bind)`: the first evidence from the still-current source via `firstOnLive`.

`ThreadEvidenceSource` gains a `kind` so the send-now `links()` diagnostic names the bound route (`evidence=Legacy` today).

Site migration in `InteractiveStreamE2ETest`, all through `harnessThreadEvidence(repository).observe(conversationId)`:

- Reopen: capture with `openMainReply` (≥ 20 words); `assertStillOpen` requires `reply(captured owner, id)?.isOpenReply()`; turn-phase and rendered caret/prefix checks unchanged.
- Ping: wait for `loadedMainReply() != null`; assert exactly one delivered user echo; `user`/`assistant` come from the evidence records with the same `single` checks; the closing equality compares the record messages of a fresh read.
- Word reveal: capture with `openMainReply`; at display require `reply(owner, id)` open; settlement waits for `reply(owner, id)?.isFinishedReply()`, then idle phase, tool-free records and the rendered final body as before.
- Send now: `ReadDeliveredRows` reads one evidence snapshot and asserts one `deliveredQueuedUser(queued.messageId, queued.queuedMsgId)` after `lastToolIndex()`.
- `threadHoldsReply`: `holdsAssistantText`. `liveRepositoryHoldsPingReply`: `liveThreadEvidence` over the host's `currentRepository`, then `holdsPingReply()`, keeping the null-on-unreadable timeout.

## State and concurrency model

No new jobs or dispatchers. Reads stay `runBlocking` with the same `withTimeout`/`withTimeoutOrNull` deadlines and the same repository each site used. `liveThreadEvidence` relies on `firstOnLive` cancellation of replaced sources.

## State transitions and identity reuse

| Event | Probe |
| --- | --- |
| Same item id from another host (colliding identity) | `capturedReplyIgnoresCollidingAndRetiredEvidence` |
| Reconnect to a new connection token, epoch or generation | `capturedReplyIgnoresCollidingAndRetiredEvidence` |
| Repair or reset while a reply is open | `capturedReplyIgnoresCollidingAndRetiredEvidence` |
| Active partial reply then terminal revision | `openAndFinishedFollowTheItemsOwnRevision` |
| Retired source still holds a ping reply after replacement | `liveEvidenceIgnoresARetiredConnection` |
| Queue handle reused by a later item or message id shared across items | `queuedDeliveryMatchesTheHandleNotTheMessageId` |

## Error handling

Missing or mismatched evidence fails the predicate and the scenario times out or asserts as it did; the diagnostics keep `unread`/null for unreadable sources.

## Testing strategy

JVM `ReplyEvidenceTest` under `app/src/test/.../e2e/`, written first: authoritative and legacy cases for every predicate above, including other-host collision, retired connection, repair, active/incomplete replies, Agent children, user echoes and the queue-handle match. Run `ThreadEvidenceTest` and `PingReplyDiagnosisTest` unchanged. Compile the androidTest set. Recorded native coverage on the managed device: `RecordedMobileSuiteTest` for ping, send-now, reopen and word-reveal, with counts. No new device-only test.

## Open Questions

- Whether the recorded reopen and word-reveal captures hold a reply open long enough for the evidence checks; the predicates read the same legacy repository, so behaviour should match.

## Documentation handoff

- Pending for the documentation stage: `docs/e2e-interactive-stream.md`: record the migrated scope (four scenarios, two diagnostics, send-now `links`), the named recorded results and counts from the PR, and that the exercised route is the legacy harness binding; preparation passes do not prove negotiated production activation.
