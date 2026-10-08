# #1900 — Repair the colliding-host attachment live flake

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`, `heldName`, `hostRepository`, `assertPeerAnswers`, `peerStep` and `pairHostByCode` define the bounded operations and retained isolation checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `history` and `retrieveAttachment` use the redialing peer.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`: `withTimeoutDiagnostic` already adds content-free operation context without changing deadlines.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `connectionFor` owns each host independently of selection.
- `docs/knowledge/features/attachment-retrieval.md`: retrieval is connection-scoped; rejection must wake the stall wait immediately.
- `docs/knowledge/features/development-verification.md` and `docs/e2e-interactive-stream.md`: fresh raw XML and counted execution are required; historical passing reruns establish no repair.
- `docs/specs/architecture/1017-live-interrupted-attachment-transfer.md`, `1059-peer-waits-fail-on-close.md` and `1369-own-file-row-survives-push.md`: preserve the seeded bound session and named attachment row; existing timeout wrappers may be reused.
- `scripts/android-test-gate.py`: focused live execution retains XML and logcat before reporting.

## Context

The historical #1731 full run failed this method with an anonymous 30-second coroutine timeout, then its rerun passed. The actual harness reported mobile `a7a4b484d7260c4dc7418b85df9c10f2b7245398`, daemon `6019328b378cad587f69b7bc94de37febbdf8556`, Claude `2.1.280`; dispatcher input was feature `f3728e7752` and main `e4ecbb099d`. Historical raw XML/logcat no longer exist in the removed worktree. Daemon logs remain under the run's `pyry-e2e.GeWqYT` directory, but cannot identify the timed-out operation by themselves. Capture a fresh failure rather than attributing the timeout to a guessed operation. No decision record is needed.

Shared-file overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1725, #1766, #1879, #1888 and #1905 edit other scenario blocks; no dependent restructuring was found. Keep edits local to this method and any directly implicated helper.

## Design

First wrap this method's suspending operations with existing `withTimeoutDiagnostic` / `peerStep`, using static operation names and peer link state only. Distinguish opening the peer, waiting for the phone connection, reading A/B names, peer history, retrieving A's bytes and checking B's rejection. Do not change waits, retry operations or add sleeps.

Use the fresh failure and correlated transport/daemon events to select the causal repair, recorded in Revisions before implementation. If the cause is in a sibling repository, file/link its issue and stop dependent work. Keep one deliverable: reliable host-isolation proof. Forecast approximately 450 written lines, no new exported API, three criteria, no new state-machine rejects or simultaneous consumer migration.

All scenario assertions remain: pending file absent on B and retained on A after switching; exactly one A user message with one attachment; retrieved fixture bytes match; B has neither tile/row nor cached attachment id and returns NotFound. Keep the method in the curated suite.

## State and concurrency model

Test operations remain bounded, sequential instrumentation calls. Peer jobs remain owned by its scope and closed in finally. Do not change product host selection, connection ownership or foreground/background behavior without evidence and a plan revision. Existing monitors, fixture cleanup and removal of B remain in finally.

## Error handling

Timeout diagnostics name static steps only, never pairing codes, tokens, keys, filenames, message contents, attachment bytes or daemon-authored text. Preserve causes and normal operation failures. Diagnostic improvement alone is not completion.

## Testing strategy

Run the focused live method on the isolated harness to capture causal evidence, then provide a regression or controlled reproduction with red/green execution counts for the repair. Unit-level helper/logic coverage belongs under `app/src/test`; the existing device scenario requires real encrypted peer transport, real storage and two live hosts, so remains under `androidTest`. Run affected existing coverage, lint, assembleDebug, Android-test compilation and forced Spotless. After the final merge/push run the entire unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`. The dispatcher owns the fresh full live suite; PR Live tests lists `all` because the ticket explicitly requires it.

## Open Questions

- Which operation timed out, and what caused it? Resolve from fresh diagnostic evidence; do not infer a repair from a passing focused run.
- What controlled regression reproduces that operation's failure? Resolve before shipping.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “What rung 3 is made of” and “Verification status”, with the established cause, preserved host-isolation checks, focused red/green counts and dispatcher full-live counts/revisions. Keep historical observation, reproduction and inference distinct.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] Diagnostic wrappers accept only test-authored labels. Existing wire parsing and attachment validation stay intact; no untrusted text is inserted into diagnostics.
- [Tokens, secrets and credentials] The live gate owns credential lookup; no credential access or storage changes. Pairing material is never printed.
- [Files and storage] Fixture handling/cleanup and host-keyed cache checks remain intact. Diagnostics do not introduce paths derived from wire data or retain decrypted bytes.
- [Android attack surface] Existing instrumentation monitors remain test-only; no component, provider, intent filter or exported API changes.
- [Cryptography] Existing Noise sessions and digest checks remain unchanged; no key/nonce changes.
- [Network and I/O] Existing bounded waits remain, with no timeout increase, retry masking or arbitrary sleep. A relay that drops/delays frames must still fail the scenario.
- [Errors, logs and telemetry] SHOULD FIX: every new diagnostic must use static step names and content-free counts/state only; never exception messages from authentication or wire payloads.
- [Concurrency] Wrappers create no long-lived jobs and preserve cancellation. Peer and host-B teardown remain in finally even after failure.
- [Threat model] Cross-host leakage remains explicitly probed by pending/sent rows, cache identity and remote NotFound. Malicious relay delay remains a bounded failure; hostile frame validation, rooted disk theft and UI-side leakage protections are unchanged because the repair is test-local until causal evidence warrants a separately reviewed revision.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

## Revisions

- 2026-10-08, investigation: the historical merged source lacked the fixture-pairing cleanup added by `61a3a4bb5` on #1731. `SessionErrorRecoveryScenario.run` originally closed each private daemon but left its saved pairing and selection in the shared Application graph. The target's initial `awaitConnected` observes the selected host, although this scenario owns host A explicitly. Reproduce the historical leak by temporarily omitting only #1731's cleanup and restoration assertions, then run the recovery method before the target in one instrumentation process. This is a controlled mutation, never a change to ship. If the target fails at its selected-host readiness wait, replace that wait with `hostRepository(serverIdA)` under the same deadline; retain all attachment checks. Repeat the identical mutation with the repair, then restore the already-merged cleanup and run the final source. Record raw counts and distinguish this controlled attribution from the historical anonymous stack. No sibling dependency is involved: the fixture cleanup is already merged in mobile.

Security review of this revision: PASS. The controlled mutation uses the existing isolated fixture daemons and existing test credentials, introduces no payload or credential logging, and is restored before any implementation commit/push. The final change waits on A's existing exact-host repository and does not route through an unrelated selected host. It changes no production storage, wire parsing or cryptography; all B absence/cache/NotFound probes remain. Temporary leaked entries are confined to the instrumentation test process, which the gate tears down after each run.

- 2026-10-08, controlled setup refinement: restoring the leaked pairings alone passed both methods. The legacy registry `observe` reports the supervisor's relay leg, so a transient relay Connected (or Idle) is insufficient to prove an open Noise repository. For a stable negative control, the target first confirms A's exact-host repository is Open, verifies another host is selected, and waits for that leaked host's relay rejection/absence before entering the unchanged selected-host wait. These condition-based preconditions are temporary and retained in the reproduction patch, not shipped. Timeout diagnostics include only `a_ready`, `selected_is_a` and the static relay-state class. No sleep, deadline increase or retry is introduced.

- 2026-10-08, controlled setup result: the settled leaked-host run failed in the setup itself, with `a_ready=true`, `selected_is_a=false`, `selected_relay=Reconnecting`. It is not red evidence for the initial scenario wait. Closed fixtures repeatedly reconnect and briefly emit relay Connected, so this setup cannot pin the negative state. Restore the merged fixture cleanup. Instead temporarily save B with a deliberately invalid test credential and wait for its terminal PairingRejected state while A remains Open; exercise the unchanged wait, then the exact-host wait under that identical controlled condition. The scenario's normal B pairing replaces this test-only record before its isolation assertions. No mutation ships.

Security review of refined control: PASS. The deliberately invalid credential is a fixed public test string; the control copies existing pairing fields without printing them, uses the harness's test B, and B is removed in finally. It cannot alter A or any production daemon. The actual repair remains exact-host readiness with unchanged deadlines and assertions.

- 2026-10-08, causal red established: `live-7h5phpsk` executed the target once (0 passed, 1 failed, 0 skipped). Its raw XML names `wait for phone connection on A` with `a_ready=true`, `selected_is_a=false`, `selected_relay=PairingRejected`. Tested mobile base `5dbb7c0d2a8b0d75cb78e3bbfe13f569a6983697` plus reproduction patch SHA-256 `d37d00f862db0683baa7342290fa67178ceab2e4e2d87527138429fa85455f78`, daemon `6019328b378cad587f69b7bc94de37febbdf8556`, Claude `2.1.280`. Responsible operation: the initial global `awaitConnected`, which observes the selected host rather than A. Replace only that call with existing `hostRepository(serverIdA)` (same 30-second deadline, Noise-Open gate). This establishes a fresh controlled defect, not the exact historical stack's operation: the historical leaked-fixture trigger remains an inference because its raw device report is unavailable. Resolve the repair's regression proof with the identical control's green run; retain sanitized evidence and reproducible patch under Android-test assets.

- 2026-10-08, controlled green: `live-nbuxonk5` executed/passed the target once, 0 failures/errors/skips, exit 0, under the identical rejected-B precondition. The before/after working trees differ only in the initial readiness call. The method completed all pending-file, one-message/one-attachment, exact-byte, B-cache and NotFound assertions. Sanitized counted XML, reproduction/repair patches and revision/hash manifest are retained in `app/src/test/resources/e2e/host-isolation-1900/` (test resources rather than runtime Android assets). Restore all temporary injections; only the A-specific readiness call and content-free timeout labels/state ship. Both Open Questions are resolved for the controlled defect; the unavailable historical device report prevents stronger historical attribution. Final written work is approximately 260 lines, no exported types, production files, signature migrations or additional state-machine rejects.
