# #2366 — authoritative shared thread fixtures

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt`: scripted operations, pump and real screen wiring.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: negotiated source ownership and authoritative projection.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/UnrecognizedRowSentinel.kt`: destination-only recording decorator.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PassiveThreadObservation.kt`: passive authoritative observation without additional queries.
- `app/src/test/java/de/pyryco/mobile/e2e/FinishedSelectionReplyTest.kt`: reply rejection and replacement-source assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelRefusalOfferTest.kt`: live refusal controls and retained refusal rows.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTurnRecoveryTest.kt`: recovery, sends and session-boundary assertions.
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt`: non-content fail-if-used doubles.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt`: non-content stub.
- `app/src/main/java/de/pyryco/mobile/data/repository/NegotiatedThread.kt`: destination-owned synchronization and passive observation contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: negotiated presentation and canonical effects.
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueriesTest.kt`: correlated batches and final window markers.
- `docs/knowledge/features/thread-screen.md`: supplied content owns placement; local sends require both device and message identity.
- `docs/knowledge/features/conversation-repository-shape.md`: repository delegation and unrelated controls.
- `docs/knowledge/features/development-verification-gates.md`: focused tests and shared screen placement.
- Core `docs/knowledge/decisions/042-daemon-built-thread.md` and `docs/protocol-mobile.md`: authoritative item and query contracts; these remain the wire source of truth.

## Design source

No visual change: existing rendering, navigation and supported assertions remain the design source. This changes verification inputs only.

## Context

The shared harness still supplies legacy content while production requires daemon-built threads. Its supported assertions must remain meaningful after #2367 removes the legacy runtime. No production changes, cache changes or DurableGapProof removal belong here. No decision record is needed.

This is one shared verification migration, with its retained consumers and non-vacuity checks. Parentage is #2167 → #2363 → #2366. Forecast is approximately 2100 written lines, at least 12 retained fixture sites, at most two new shared helper types and no production reject branches. The recorded `needs-human:sizing` exception and final split depth require continuing through the ceiling without weakening verification. There are four acceptance criteria. Recount before implementation retains that same exception.

Overlaps: #1947 adds a harness context accessor already present here; #2175's source-selection changes are already present. Keep shared changes local.

## Design

Keep scenario-facing harness operations, translating their supplied content into explicit authoritative facts with stable item IDs, revision, epoch, session and order. The pump completes actual repository catch-up/page requests using correlated `thread_items` batches and final markers. The ViewModel receives an exact-host negotiated route over that real repository. Live state and commands keep their supported wire paths; retired content frames cannot furnish rows.

Use a small shared authoritative fixture for retained repository doubles. Its source owns a store and negotiated support; tests provide facts rather than successful empty legacy observations. Non-content settings/archive doubles keep fail-if-used semantics. Selection expectations compare projected identity and content, since projection creates messages rather than retaining literal Message object identity.

The tapping decorator records parser-gap notices from authoritative destination observation and forwards passive observation, query leases, progress and controls. Preserve its legacy delegation for dedicated legacy tests until #2367. Existing passive helpers own source replacement and admission checks; do not add extra reads to establish observations.

Audit all remaining `observeMessages`, `threadSnapshots` and `ThreadSnapshotSource` uses: retain literal presentation inputs and dedicated legacy algorithm tests for their owning removal ticket; migrate supported-flow assertions and non-content stubs. Record the classification and inventories in the PR.

## State and concurrency model

The real repository retains its connection-scoped collector. Harness lifetime owns its IO scope and cancellation. Stable fixture identities and revision progression are test-local; pump sends and updates must be serialized. Destination queries belong to the negotiated route's lease. Passive taps neither query nor create independent collectors. Source replacement changes the supported connection identity and retires evidence from the old owner.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Initial query and final completion | Harness authoritative route test checks rendered rows, certified snapshot and no legacy content request. |
| Duplicate/reordered revision | Harness/fixture invariant probes retain one newest item and daemon order. |
| Text, tool, terminal and divider updates | Existing ScriptedThreadRenderTest, ScriptedToolRowTest, ScriptedSessionBoundaryTest and ScriptedTurnOutcomeTest assertions remain. |
| Pending send, acknowledgement and matching acceptance | ScriptedLocalSendTest retains feedback until exact device/message acceptance and rejects peer collision. |
| Conversation navigation | Existing ScriptedTurnPhaseTest and ScriptedResettingTest keep isolation assertions. |
| Disconnect and replacement source | FinishedSelectionReplyTest and passive authoritative observation checks reject retired evidence. |
| Recovery and new versus historical divider | ThreadViewModelTurnRecoveryTest retains baseline, older-page and new-boundary checks. |
| Parser gap observed by the destination | ScriptedUnrecognizedMessageTest retains non-vacuous recorder checks on the authoritative route. |

## Error handling

Malformed or stale authoritative facts use existing decoder/store rejection. Unsupported legacy content access fails in migrated content doubles. Non-content doubles continue to fail if content is unexpectedly used. Existing send/control failures and refusal/recovery errors remain asserted; no production recovery is added for test support.

## Testing strategy

Write a harness route regression first and observe its failure before migration. Run all affected unit classes and shared harness client classes with focused `testDebugUnitTest --tests` selections. Preserve invariant probes for supplied ordering/revisions, owner retirement and local acceptance identity. Run the focused existing device-only `ScriptedUnrecognizedMessageTest` class (its original real-device assertion needs remain) and the scripted `tool` scenario on Linux. Inventory selected methods and retain executed/passed/failed/skipped counts, XML, logs and exact revisions. No recording refresh is anticipated because supported assertions are unchanged. Complete configured unit, UI, recordings and scripted gates remain dispatcher-owned.

Run lint, APK assembly, instrumented Kotlin compilation, Spotless, then merge main, push and run final assembly and `scripts/pre-verify.py --gradle`. Preserve device evidence on the persistent agents volume.

## Documentation handoff

Pending documentation stage: update shared-harness/evidence descriptions in `docs/e2e-interactive-stream.md`, `docs/recorded-tests.md` and their linked testing topics. Record unchanged scenario coverage, method/class inventories and any inventory changes. Preserve frozen archives, past specs and accepted/failed evidence.

## Open Questions

None at initial planning. Implementation findings that change contracts will be recorded under Revisions.

## Revisions

- 2026-10-21: The scenario adapter retains the existing operation vocabulary but sends only supplied item facts and session-tagged live readings across the pump. It answers actual catch-up requests with a separate correlated batch and final marker. This keeps all construction sites stable without letting legacy reduction supply rendered content.
- 2026-10-21: Authoritative messages have scoped item keys and no client-reduced delta metadata. Reply-order positives and duplicate/order negatives now use `ThreadEvidence`; the three old delta-sequence rejection checks retain explicit literal presentation inputs that never enter the repository or screen. Agent assertions use scoped daemon identities and the actual authoritative presentation, including its separate lifecycle markers.
- 2026-10-21: Recovery session replacement is exercised with its supplied divider and the supported `SessionReplaced` live control. The historical/older-divider check supplies facts without that control and must retain the recovery notice. Its existing clear assertion now checks the canonical `session` reason rather than the legacy `session_boundary` reason; no recovery outcome is relaxed.
- 2026-10-21: The managed test selector requires one exact class per invocation. A wildcard and a multi-class selection were refused before execution; run the affected class inventory individually. The initial route regression executed once and failed because no thread query was sent, then executed once and passed after migration, with no skips.
