# Direct Share evidence across connection replacement

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_directShareShortcut_stagesBeforeExplicitSend`, `arriveInSeededThread`, and reconnect controls.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: passive negotiated evidence and connection-scoped evidence ownership.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/StreamingReplyEvidence.kt`: admitted empty-thread and exact delivered user-echo checks.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: existing source-retirement rules.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `repositoryFor` returns current availability, not a retained destination.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/share/SharingShortcuts.kt`: published shortcut intent contains the exact host/conversation pair.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: admitted snapshot and document identity.
- `docs/knowledge/features/navigation.md`: direct routing consumes the published ledger id; selection stages rather than sends.
- `docs/knowledge/features/development-verification-test-scheduling.md`: cleanup-time focus does not identify a failed assertion.

## Context

One behaviour slice: reliable deterministic Direct Share staging and explicit send.
The #2486 failed daemon log records the user send, a producer-gap teardown, replacement handshake,
and mark-read of the reply, followed by roughly the reply timeout before cleanup. Its same-tree
passing rerun has no producer gap. This implicates the final observation pinned to the repository
captured before Send, rather than shortcut publication or activity loss. A controlled native
replacement before the final assertion must reproduce the timeout before the repair.
Historical empty XML failures alone do not establish the failing operation; retain both outcomes.
No product contract or decision record changes.

Overlap: #1766, #2091, #2104, #2175, #2462 and #2486 edit other scenarios or the class rule.
Keep changes local to the Direct Share method and new ticket-specific test support; no dependency.
Forecast: under 450 written lines including plan, tests and diagnostics; zero production files,
zero exported types, one consumer, four acceptance criteria, no new state machine.

## Design

Keep the actual seeded-channel publication, shortcut-id share intent, exact composer text,
absence of picker, rendered scripted reply and single delivered exact user echo.
Resolve the retained destination repository for the shortcut's exact pair. Its negotiated route
follows replacement connections. Admit observations only when their owner is the current host
connection supported by that route and their source snapshot is the store's current snapshot.
Before tapping Send, wait within the existing thread deadline for admitted evidence, then assert
there is no user message. After rendering, force the observed connection-retirement boundary and
require a replacement. Validate the single echo against the fresh admitted observation's owner,
not the pre-send connection owner. Reject stale, unsupported, unadmitted and wrong-target evidence.
Ticket-specific pure evidence predicates receive explicit current source and snapshot inputs.
Bounded stage diagnostics contain static stage names and admission/source booleans only.

## State and concurrency model

No application state changes. Passive route observation follows its existing hot support/store
flows; the screen retains its query lease. Native waits stay inside existing deadlines.
The scenario uses its owning host's supervisor for controlled close/connect; no retry or resend.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Fresh launch, publication and share intent | Existing named native direct-share scenario, three independent invocations |
| Evidence not admitted yet | Focused predicate regression rejects it; native pre-send wait requires admission |
| Connection replaced after Send | Controlled replacement in the named native scenario; old observer fails before repair |
| Same target on new connection | Focused predicate regression accepts only current supported source and exact store snapshot |
| Stale snapshot, wrong host/conversation, unsupported route | Focused predicate rejection regressions |

## Error handling

Keep every assertion and timeout failure. Emit only a static terminal stage on failure and rethrow
the original error. Never treat unadmitted evidence as empty, retry tests, or increase deadlines.

## Testing strategy

First run the unchanged focused scenario and preserve its result. Add controlled replacement to
the existing device method and observe its original final assertion fail. Then repair observation
and run focused JVM predicate regressions plus existing streaming/thread evidence tests. Run three
fresh `scripted direct-share` gates on Linux and retain counted XML, raw instrumentation, phone and
isolated daemon logs and source/binary revisions. This scenario requires real Android shortcut
publication, activity intent delivery and encrypted transport; Robolectric cannot supply that proof.
Run lint, assembly, instrumented compilation, Spotless and final pre-verify. Complete scripted-all
acceptance remains with the dispatcher.

## Open Questions

Confirm the controlled replacement reproduces the historical final-wait failure before repair.
Record the measured outcome and any resulting design change under Revisions.

## Security review

**Verdict:** PASS

- Trust boundaries: use the published shortcut's validated exact target; retain all share-intent and admission guards. Evidence must match current host, conversation, connection and snapshot.
- Tokens: no credential generation, storage or lifecycle changes; never include pairing arguments in diagnostics.
- Files/storage: only test evidence is retained privately; no new app storage or paths derived from authored input.
- Android attack surface: no exported-component or intent-parser changes; the native scenario still exercises the real untrusted intent boundary.
- Cryptography: retain the existing Noise session and test-only daemon identity without changing keys or framing.
- Network/I/O: reuse existing isolated harness and bounded waits; no new requests, retries or deadline changes.
- Logs/errors: static stage codes and booleans only; no shared text, ids, payloads, token material or exception dump in new diagnostics.
- Concurrency: evidence cannot pass from a retired connection or stale snapshot; no new persistent jobs, and existing scenario teardown owns cleanup.
- Threat model: relay delay/drop is exercised by replacement while retaining admission checks. Rooted storage theft, hostile-frame decoding and UI leakage retain existing product protections; this test-only change adds no surface there.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-23
