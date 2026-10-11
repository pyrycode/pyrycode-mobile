# Sharing shortcut device composition scheduling

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/share/SharingShortcutsDeviceTest.kt`: `dynamicTargetHasShareContractAndLauncherIntentOpensWithoutChangingDraft` publishes through the OS and replaces the activity using its persisted Intent.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `onCreate` awaits saved hosts and workspace migration in `produceState` before composing `PyryNavHost`; routing already enters an explicit Main turn after suspending reads.
- `app/src/androidTest/java/de/pyryco/mobile/StartupWorkspaceMigrationTest.kt`: gated startup fixtures and persisted migration assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/notifications/NotificationTapNavigationTest.kt`: existing IO-backed routing and current-target validation regressions.
- `docs/knowledge/features/navigation.md`: startup ownership and shortcut routing contracts must remain intact.
- `docs/knowledge/features/development-verification-test-scheduling.md`: instrumentation shares its application graph across methods; worker completion can resume unconfined continuations inline.
- Installed Compose `ui-test-android` 1.10.4: `AndroidComposeUiTestEnvironment` defaults to an unconfined composition dispatcher and replaces the context interceptor with its frame/apply interceptors. Its test frame clock uses a zero frame delay. A supplied dispatching test dispatcher uses the existing main-clock scheduler.

## Context

The retained #2081 full UI failure reaches `NavHost.setGraph` from a DataStore worker through Compose's test interceptors. It predates external-target navigation, so adding another routing dispatch cannot repair that stack. Baseline full configured and isolated executions on the same tree will be retained. A controlled worker-completion regression will determine whether the test rule's unconfined composition scheduling is responsible; ordinary isolated success alone is insufficient evidence.

Estimated written work is approximately 350–600 lines including regression, evidence and this plan, no production signature changes, no new exported production types, three acceptance criteria and no new reject branches. The in-flight branch scan found no overlap in the intended shortcut test or `MainActivity`. This work does not change UI appearance and requires no real-Claude scenario or decision record.

## Design

If the controlled regression confirms the unconfined resumption, supply `StandardTestDispatcher` as the shortcut rule's composition effect context. Compose's main test clock then drains queued composition work on the UI thread instead of worker completion executing it inline. Keep the change local to this device class and preserve every existing OS publication, identity, category/MIME, label, artwork, persisted-Intent replacement, picker-absence and draft assertion. Do not alter production routing, lifecycle enforcement, deadlines or enablement.

Add a regression using the same rule and a gated startup producer, followed by a real Navigation Compose graph. Start the producer on Main, hold completion, complete it on an owned coroutine worker, and observe the continuation and graph composition Looper before teardown. Assert worker completion really occurred off Main, startup remained held, and resumption and graph composition both occurred on Main. Run it red before changing the rule, then green afterward. Observations use only fixed event codes, Looper classification and virtual clock values.

## State and concurrency model

Production scope and storage ownership remain unchanged. The test's producer belongs to its composition and is cancelled when content is disposed. The worker completion belongs to a bounded `runBlocking` test scope and is joined before assertions. The supplied dispatcher belongs to the Compose rule; the rule owns its scheduler and disposal. Do not install or reset global Main, mutate global Compose flags, or advance the scheduler from a worker.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Startup waits for worker completion, then opens navigation | New `workerCompletionKeepsStartupAndNavigationOnMainThread` regression; pending state and both Looper checks |
| Persisted launcher Intent replaces the original activity under the same target | Existing `dynamicTargetHasShareContractAndLauncherIntentOpensWithoutChangingDraft`; all publication and draft assertions retained |
| Host lookup suspends on IO before external-target navigation | Existing `NotificationTapNavigationTest` focused class |
| Composition is disposed while work exists | Compose rule owns cancellation; completion scope is joined and any held gate released in `finally` |

## Error handling

No production error handling changes. An off-main continuation must fail the regression, with lifecycle main-thread enforcement still enabled. A missing/failed device execution is unverified, never a pass. An unexpected diagnosis requires a dated revision before a different repair.

## Testing strategy

The device regression exercises the installed instrumentation Compose frame/apply interceptors and worker resumption, which a paused Robolectric main clock cannot reproduce faithfully. The existing shortcut method requires real OS `ShortcutManager` publication and persisted task-replacing launcher activation. Retain sanitized XML, exact revisions/dirty patches, commands and executed/passed/failed/error/skipped counts under `app/src/test/resources/verification/shortcut-composition-2094/`.

Run the configured UI gate once for baseline diagnosis, then the fresh isolated original method on that tree. Run the controlled regression red, repair the rule, and rerun the regression and whole affected device class with normal managed-device admission. Run the existing `NotificationTapNavigationTest` on the JVM. Run lint, assemble, instrumentation Kotlin compile, formatting, and final `scripts/pre-verify.py --gradle` after the last merge of main. The dispatcher/verifier owns the fresh repaired full configured UI gate; the PR must name the exact pending method, commit/XML/count evidence requirement and must not claim focused execution satisfies it.

## Open Questions

- Does controlled worker completion reproduce the unconfined startup continuation? Resolve with retained red/green evidence before handoff.
- Does the fresh baseline full suite fail or pass on this host? Either result must be recorded alongside its same-tree isolated control; a pass does not negate the retained original failure.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The proposed edit changes only instrumentation scheduling. `SharePayload.from` and `NotificationTap.target` continue to parse exported Intent data; saved-host and active-row validation remain in `PyryNavHost`.
- [Tokens, secrets and credentials] No credential lifecycle or storage changes. Evidence must omit Intent extras, pairing material and DataStore contents; test observations carry only static codes and Looper/clock classification.
- [Files and storage] Evidence is checked-in test data. No new application storage or caller-controlled paths are introduced; production private storage and backup exclusions remain unchanged.
- [Android attack surface] No exported component, permission, deep link or PendingIntent changes. The original OS activation test remains enabled and uses the published Intent.
- [Cryptography] No cryptographic primitive, key, identity or nonce changes; the coroutine fixture uses synthetic completion signals only.
- [Network and I/O] No network configuration or protocol changes. The controlled gate substitutes only the timing of a completion; real publication/persisted activation remain separately tested.
- [Errors, logs and telemetry] SHOULD FIX: retain bounded content-free observations and sanitized evidence, never raw logs containing unrelated suite data or arbitrary exception payloads.
- [Concurrency] SHOULD FIX: prove completion actually occurs on a worker and composition actually occurs on the main Looper, with the same rule configuration as the launcher test. Do not disable lifecycle enforcement or leak a fixture scope.
- [Threat model] Relay compromise, token theft, hostile daemon frames and token-entry leakage have no new path through the local test-rule change. Existing authentication, decoding, secure storage and protected entry surfaces remain their production controls.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

### 2026-10-11 — controlled apply boundary

The initial controlled worker completion failed the startup Main assertion while
its graph composed on the next Main-clock frame. Holding Main during completion
still exposed off-main startup resumption but left graph delivery deferred. The
fixture now also calls `Snapshot.sendApplyNotifications` on the completion worker,
matching the worker-side apply boundary in the historical and fresh isolated
failure stacks. Main is held only for the bounded, joined worker turn; no scheduler
is advanced from the worker. The contract still requires startup continuation and
all graph compositions on Main, with lifecycle enforcement enabled.

### 2026-10-11 — diagnosis and final fixture

Resolved both Open Questions. At `3918ef87d14d6add0013c9f4d5538c0d22d87c5e`,
the baseline configured device-only gate passed 259 executed tests with one skip;
the fresh isolated original method failed once with the retained main-thread
lifecycle exception. Both initial and task-replacement startup began on Main and
logged readiness on workers in these runs. Suite order and the neighbouring cases
are retained, but prior-method shared state is not required to trigger this failure.
The historical occurrence's exact scheduling interleaving remains unknown.

The minimal controlled producer consistently resumed on its completion worker
with the default rule and failed its Main assertion. Its graph was observed on
Main in that run. The bounded Main-hold and explicit snapshot-apply investigation
variants also failed the startup assertion without forcing off-main graph delivery;
they are retained as diagnostics and removed from the shipped fixture. The final
regression keeps the original minimal fixture, including both startup and graph
Looper assertions. The real launcher method's fresh failure separately supplies
the `NavHost.setGraph` worker-composition evidence.

Supplying `StandardTestDispatcher` changes only the affected rule's composition
effect context. The identical minimal regression then passed once: worker completion
was off Main, startup resumed on Main, and the graph composed on Main. No production
routing, dispatcher globals, test flags, deadlines or lifecycle enforcement changed.
Evidence lives under `app/src/test/resources/verification/shortcut-composition-2094/`;
manifests contain the tested revision and exact test-source dirty patch for every
execution. The fresh repaired full configured UI gate remains dispatcher/verifier
work, including exact shortcut-method execution, commit, XML and all outcome counts.

The final formatted affected class executed both methods and passed 2/2 with no
failures, errors or skips. The existing IO-backed routing class passed 15/15.
