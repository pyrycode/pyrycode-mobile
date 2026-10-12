# #2102 — channel prompt live recurrence

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_createEditArchiveChannel_readsPromptBack`, editor helpers, `hostRepository` and `hostConversationIds`; preserve the scenario's entire contract.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/EmptyHostChannelSetup.kt`: `createChannelFromEmptyHost`; keep #1897's loaded, empty, host-scoped readiness.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/StopHoldEvidence.kt`: mirror content-free, pre-cleanup diagnostic emission.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelEditorController.kt`: `open`, `submit`, `archive`; existing structured lifecycle logs distinguish read/write/closure failures.
- `docs/knowledge/features/system-prompt-editor.md`: connection-scoped repositories can retire; construction-time binding is not reconnect-safe.
- `docs/knowledge/features/development-verification-test-scheduling.md`: cleanup focus and a coroutine timeout alone cannot diagnose an earlier operation.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: preserve fresh XML/logcat and use a distinct second reply after Reset session.
- `docs/e2e-interactive-stream.md`, “Empty-host channel setup (#1897)”: the earlier repaired global wait is not this recurrence's diagnosis.
- `app/src/androidTest/assets/channel-setup-1897/README.md`: earlier controlled and historical evidence remains separate.
- `../pyrycode/docs/protocol-mobile.md`: single wire/security contract; no protocol changes planned.

## Context

The Linux #2009 full gate failed this method and its failed-only rerun passed. Original diagnostic files are unavailable on this Mac. A fresh unmodified focused run on Mobile `cfc5911723bad305dcf2486283521915dce91b84`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, passed 1/1 with no skips. This does not diagnose the recurrence. Initial work makes fresh failures attributable to a precise operation before fixture cleanup. A behavioural repair requires causal evidence and a dated revision here before implementation; diagnostics alone do not complete the ticket.

In-flight overlap: #2100, #2099, #2092, #2089, #2057, #2009 and #1869 touch the same large scenario file but different methods/helpers. Keep all edits local to this method; no demonstrated dependency exists.

## Design

Add a test-only `ChannelScenarioStage` with fixed operation codes and a synchronous `channelScenarioStep` wrapper in shared test sources. Emit start, complete or failure with timestamp and a content-free target/selected connection snapshot. Rethrow the original assertion, exception or cancellation without retrying. Capture failure before the outer `finally`, then label each existing deletion/restoration attempt independently. Do not change shared editor helpers, waits, assertions, timeouts or scenario selection.

Wrap each existing operation separately: list/connection setup, fixture archive, empty repository wait, default-folder probe, creation drive/identity, first reply, original prompt read, rename/prompt save, edited read, reset boundary, distinct second reply, matching prompt status, clear write/null read, empty Channel info, archive membership and restore membership. Snapshot only graph/host presence, selection equality, repository presence and static status class names; never emit names, prompts, credentials, paths or raw errors.

## State and concurrency model

The wrapper is synchronous and owns no jobs or flows. Existing Compose waits and bounded `runBlocking` repository calls retain their cancellation and deadlines. Snapshots are non-atomic observations of the current graph, not proof of a shared failure mechanism. Scenario cleanup remains guaranteed by the existing outer `finally`.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Successful operation | `successfulOperationReturnsOnceAndReportsCompletion` |
| Assertion or exception before cleanup | `failureIsRecordedBeforeCleanupAndTheOriginalFailureSurvives` |
| Cancellation | `cancellationIsRecordedAndRethrownWithoutRetry` |
| Repeated operation/cleanup stages | `repeatedStagesRunOnceEachWithoutRetry` |
| Diagnostic formatting | `failureMessagesNeverEnterEvidence` |
| Graph closes during diagnostic snapshot | `snapshotFailureDoesNotChangeScenarioOutcome` |

These tests prove diagnostics, not the intermittent live cause. Existing live identity, reset, prompt and fixture assertions remain enabled.

## Error handling

No retries, skipping or relaxed conditions. Log the fixed stage/outcome and static snapshot, then preserve the original failure. Existing cleanup failure handlers continue attempting all remaining fixtures without masking the scenario failure.

## Testing strategy

Write failing JVM tests for the diagnostic wrapper, then implement it. Run the wrapper tests and existing `EmptyHostChannelSetupTest` controls. The changed live method stays device-only because it requires a real daemon, authenticated transport and Claude replies. Obtain a fresh operation-labelled failure if historical stderr remains inaccessible; use the demonstrated cause to write a red-before/green-after behavioural regression before claiming repair. Builder checks include lint, APK assembly, Android-test compilation and forced Spotless. The dispatcher owns fresh passing full-live proof, including this named method, tested revisions, XML/logcat and executed/failed/skipped counts; no focused pass substitutes for it.

## Open Questions

- Which operation failed in the historical occurrence? Unresolved; original stderr is unavailable and the first focused run passed.
- What evidenced repair is needed, and which repository owns it? Unresolved; do not infer a connection fault from other methods' failures. Link a native owning blocker if required.

## Documentation handoff

Pending for documentation stage: update `docs/e2e-interactive-stream.md`, “Empty-host channel setup (#1897)”, with the new diagnosis, repair and retained counted full-live evidence, explicitly separating this recurrence from #1897. Documentation cannot produce missing live proof.

## Security review

**Verdict:** PASS for diagnostic design; repeat this review if the demonstrated repair changes an authenticated boundary.

- [Trust boundaries] Fixed enum stage codes and static class names only. No daemon text is promoted to a log, filename or URL.
- [Tokens] No credentials are added, read or persisted by the wrapper. The approved gate owns the login and strips account credentials from test children.
- [Files/storage] No new runtime persistence. Retained evidence must exclude tokens, keys, pairing payloads and user content.
- [Android attack surface] Test-only functions add no components, intents or exported entry points.
- [Cryptography] No handshake/key/nonce changes; the existing Noise implementation remains authoritative.
- [Network/I/O] All existing operation deadlines stay bounded; diagnostics never send requests or redial.
- [Errors/logs] Failure messages and throwable representations never enter emitted evidence. Static status labels and presence booleans carry no secrets.
- [Concurrency] Synchronous wrapper has no new coroutine scope. Cancellation is rethrown and outer cleanup remains intact.
- [Threat model] A dropping/delaying relay still fails the original bounded operation; hostile frames retain existing decoding/rendering limits. Keystore storage, rooted-device theft and UI token-entry protections are unchanged because diagnostic code never handles tokens.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-12

## Revisions

2026-10-12: the diagnostic wrapper must also tolerate an unavailable graph snapshot without replacing the operation's failure. The new controlled regression failed before this guard (1 executed/failed, no skips); snapshot exceptions now emit only `snapshot=unavailable`. The first five diagnostics regressions failed against a no-evidence stub (5 executed/failed), then passed with evidence enabled. This is diagnostic red/green proof, not reproduction of the live flake or a behavioural repair.
