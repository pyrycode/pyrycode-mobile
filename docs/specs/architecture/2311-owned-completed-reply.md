# Owned completed reply observation (#2311)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/PingReplyAssertions.kt`: `pingReplyMatcher` and the existing text-only wait remain available.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PingReplyDiagnosis.kt`: existing attribution incorrectly permits turn totals to stand in for a reply.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: ownership, admission, full text and rendered identities across both routes.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ReplyEvidence.kt`, `AgentReplyEvidence.kt`, `SettledAssistantReply.kt`: preserve strict family settlement and current-source checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: basic reply, Stop follow-up and offscreen callers; preserve their other assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SettingsReadProbe.kt`: passive pump decoration pattern; its correlated settings reads stay unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`, `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: live and recorded phone pump wiring.
- `docs/knowledge/features/development-verification-gates.md`: strict settlement differs from loaded selection; authoritative IDs must come from the admitted projection.
- `docs/knowledge/features/thread-screen.md`, `development-verification.md`, `docs/recorded-tests.md`: native replay uses original scenarios and exact request contracts.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: source of truth for delivered user echo ordering, assistant lanes and successful `turn_end` outcomes.

## Context

Observe the completed reply to an actual send independently of its wording. This is one shared observation repair with multiple consumers, rather than separate reply and diagnostic behaviours. No production change, recording refresh or decision record is needed. Estimate: approximately 1200–1500 written lines including tests and plan, at most three shared internal types, three migrated scenario/helper sites, four acceptance criteria and fewer than ten observation failure categories. The nearest analogue is merge `72dba418c` (400 insertions and 88 deletions); this change adds producer/send correlation and controlled route coverage beyond that analogue.

Overlapping remote branches: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088–#2092, #2098–#2100, #2102, #2104, #2112, #2114, #2117–#2119, #2138, #2139 and #2175 touch the large native scenario file. Changes stay local and additive. #2032 wraps Stop diagnostics, but needs no observation API redesign; #2175 changes separate Agent scenarios.

## Design

Add a passive test-only phone pump probe alongside, without modifying, `SettingsReadProbe`. Arm one observation before sending; capture the accepted `send_message` identity, then its delivered user echo and the subsequent main assistant turn. Do not add messages, queries, peers or recording checkpoints. The existing capture prompts and fixture bytes remain unchanged.

The target retains host, conversation, initial repository ownership, sent message identity, producer turn and exact producer text. A delivered echo establishes the send boundary; no reply wording or row totals participate. Require a successful matching terminal outcome, complete ordered main-lane text, and an admitted `settledReply` family in the echo's session. Keep all nonblank fragments and their actual rendered IDs and contents. User/tool rows, child lanes, other sessions/turns, unadmitted documents and conflicting source owners cannot substitute.

Add an evidence-taking Compose assertion using `assistant-reply:<message.id>` plus corresponding content. Observe identity counts independently of text and require exactly one displayed identity for every admitted nonblank fragment. Keep the original text-only API's behaviour for unmigrated consumers.

The basic native scenario uses this target and its admitted messages for its existing copy/quote/reply checks. The diagnostic helper arms before its follow-up send and follows the same target through producer completion, current repository admission, composed identity and display. Its timeout has content-free missing, duplicate and offscreen classifications; producer completion cannot imply repository or UI delivery. Preserve Stop and offscreen continuity checks.

## State and concurrency model

The probe is test-scoped and synchronized, with no new coroutine jobs. Its pump observes inbound frames without altering their delivery. Each native observation is detached in `finally`. Repository reads use current ownership and remain inside the original reply deadline. Check ownership again after screen sampling and immediately before returning; source replacement fails explicitly rather than accepting retained evidence from a retired repository. Existing pump scopes and recording cleanup retain lifecycle ownership.

## State transitions and identity reuse

| Event | Required regression |
| --- | --- |
| New accepted send, delivered echo, main deltas, successful end | `OwnedReplyObservationTest` accepts varied prose and multiple fragments on both routes |
| Earlier echo/turn/session or child output collides | `OwnedReplyObservationTest` rejects wrong target and main-lane attribution |
| Failed, interrupted, incomplete or malformed completion | `OwnedReplyObservationTest` rejects unsuccessful and missing terminal evidence |
| Repository/connection, host, epoch or generation replacement | `OwnedReplyObservationTest` rejects retired ownership, including replacement during the final check |
| Repeated or missing rendered identity and non-displayed content | `OwnedReplyRenderingTest` rejects absent, duplicate, wrong identity/content and offscreen nodes |
| Producer completes without repository or screen delivery | independent `OwnedReplyObservationTest` / `OwnedReplyRenderingTest` attribution negatives |

## Error handling

No producer text enters diagnostics. Failure categories distinguish unaccepted send/producer boundary, unsuccessful completion, changed owner, unreadable or missing repository evidence, missing/duplicate composed identity and offscreen/content mismatch. A deadline is never restarted for a new stage. Cancellation propagates and cleanup detaches the observation.

## Testing strategy

Write controlled JVM and shared Compose regressions first, demonstrate the old wording/role boundary's failure, then implement the new boundary. Exercise legacy delta integrity and authoritative item completion/admission independently. Run affected existing evidence, diagnostics, ownership and ping screen classes. Run focused Gradle lint, APK assembly, instrumented compilation and forced Spotless checks. Native tests remain device-only because they use real transport, IME, clipboard and on-device application state.

On the exact committed candidate, run scripted `ping`, the named basic live method and its existing `RecordedMobileSuiteTest` replay on Linux. Retain raw XML/logs privately on the persistent agents volume with counts, revisions, APK hashes and route/configuration identity. Preserve each failure separately. Native/replay currently bind legacy via `harnessThreadEvidence`; controlled fixtures prove authoritative support without activating production. Complete configured suites remain dispatcher-owned. No macOS verification is required.

## Open Questions

None. If current producer evidence invalidates the send/turn correlation design, record the revised contract before implementation departs from this plan.
