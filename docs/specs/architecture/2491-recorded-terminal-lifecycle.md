# Recorded terminal lifecycle (#2491)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt`: recorded rule nesting currently destroys the Activity before session cleanup.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforePhoneTeardown`, `checkpoint`, and `finish` own replay preparation and restoration.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedActivityRule.kt`: successful pre-destruction callback; failures bypass preparation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveSessionCleanupRule.kt`: begin/end ownership and replacement Activity cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: strict control sends, delivery barriers, close consumption fence.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: observation fences, required requests, terminal readiness.
- `app/src/test/java/de/pyryco/mobile/recording/RecordedActivityRuleTest.kt`: controlled Activity lifetime regression pattern.
- `app/src/test/java/de/pyryco/mobile/e2e/LiveSessionCleanupRuleTest.kt`: original-failure and mandatory-cleanup contracts.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `backgroundAgentReplyProof` preserves child attribution, visibility, uniqueness, and separate main reply.
- `docs/recorded-tests.md`, Complete native suite / Observation checkpoints: test-end and scenario-complete are distinct; no work may cross either boundary.
- `docs/knowledge/features/lifecycle-connection-driver.md`: delayed process stop closes connections; changing production lifecycle would hide the harness defect.

## Context

One behaviour: finish a successful recorded scenario's required terminal work before destroying its request-producing Activity. The focused main failure at `a48e9ced0e9bc7e9193445d576afb6fb498a7b99` first reports an early phone close at matched step 32, with test-end and phone queries pending. Delivery errors from outer finish are secondary. The source-matched #2462 verifier bundle is `18ddeeede50af3312b066b5571ec64d526f549729171adc788b23a62599b240c`.

The committed Agent recording has test-end, additional phone updates, a catch-up/reset, list query, fresh catch-up/replies, scenario-complete, then phone close. Keeping the Activity only through test-end cannot satisfy it. No production, visual, fixture, or fresh provider-contract change is intended; no decision record is needed.

Overlaps: #2311 and #2351 touch other native adapter functions; #2325 and #2462 change separate tape matching; #2360 adds the pre-test-end foreground fence. Preserve that fence when main incorporates it. None supplies a required dependency for this terminal-boundary repair.

## Design

Keep begin outside Activity launch. Add an explicit successful early-end entry point to `LiveSessionCleanupRule`, so recorded preparation can invoke the real end action once while the Activity remains alive. Its ordinary outer cleanup still runs end after failures and always releases replacement Activities. No duplicate recorded end is suppressed by the tape.

The recorded Activity callback first retains existing foreground preparation, then runs session end, then prepares the recorded terminal prefix. When scenario-complete precedes phone closure, emit that exact checkpoint while the Activity remains alive, after all required earlier work drains. When phone closure precedes scenario-complete, retain that order: drain the pre-close prefix while alive and leave checkpoint emission to outer finish. Native session state records only a successfully emitted early checkpoint so finish does not send it twice. Captures and older recordings retain their existing paths.

No request, reply, owner, payload, correlation, deadline, or compatibility policy changes. The existing tape decides readiness and validates all actual controls. Terminal preparation cannot manufacture queries, consume required repeats, or move replies across observations.

## State and concurrency model

JUnit's instrumentation thread sequences successful preparation before Activity destruction. Existing hub coroutine scope and delivery queue continue routing replies; request-producing application jobs remain owned by their original scopes. All new waits use the existing 30-second terminal budget. Native replay state is session-local and restored/disposed by existing finish cleanup.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Successful return with delayed queries after test-end | Controlled terminal regression keeps Activity alive through actual requests, correlated replies, and scenario-complete. |
| Activity destruction followed by delayed process stop | Controlled regression closes only after the terminal prefix; the old rule order fails early-close matching. |
| Close recorded before final checkpoint | Regression retains close-before-checkpoint ordering and leaves checkpoint to finish. |
| Repeated query payload | Both captured requests and their separate replies remain required; no duplicate or foreign-owner substitution. |
| Body/end/preparation failure | Original failure remains primary, end executes once, every owned Activity is released; failed preparation cannot mark completion. |
| Repeated evaluation/new session | Cleanup state resets on begin; no prior terminal completion can satisfy a later scenario. |

## Error handling

Strict tape mismatch and timeout remain failures. Cleanup preserves the first scenario/preparation failure and attaches later cleanup failures. Successful preparation is recorded only after the actual checkpoint send; failures still run ordinary graph restoration and disposal.

## Testing strategy

Write controlled JVM regressions first using the real tape, hub, Activity rule, and session-cleanup rule, with deterministic gates for terminal queries and process-stop timing. Retain a red result before implementing. Probe missing/repeated work, observation fences and owner/correlation checks with the affected existing tape/hub suites. Run affected cleanup/rule/consumption classes, lint, APK assembly, instrumented compilation and forced formatting checks on macOS.

Run the exact unchanged recorded Agent method using `scripts/android-test-gate.py ui --tests`; device execution is necessary for real Activity destruction, process lifecycle and background dispatcher delivery. Retain counted XML, phone/replay logs, source/APK hashes, exit status and cleanup receipts in durable private evidence. Full configured UI execution remains dispatcher-owned and pending before verifier PASS. No Linux run or new Claude capture is required.

## Open Questions

The controlled probe and focused native run must verify the boundary and whether end/close ordering needs any adjustment. Record any design change under Revisions. Wider sequence or load investigation is needed only if narrow controlled and native evidence cannot explain the failure.

## Scope assessment

One independently useful repair with its regressions; forecast about 650 written lines across test helpers, regressions and this plan, zero production exports, fewer than ten updated consumers, three acceptance criteria and fewer than ten new failure branches. This remains below the 1600-line ceiling. Recount after implementation.
