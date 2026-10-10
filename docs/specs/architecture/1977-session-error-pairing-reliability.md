# Session-recovery arm and phase diagnostics (#1977)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `run`, `runCase`, `pair`; fixture and saved-host ownership and the anonymous post-confirmation wait.
- `scripts/e2e-session-error.py`: `Case.start`, `Case.action`, `Case.close`; closed-stdin and relay readiness, independent identity and input-count evidence.
- `scripts/test_e2e_session_error.py`: `SessionErrorControlTest`; private fixture contract regressions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SessionErrorDiagnosticsTest.kt`: shared diagnostic failure/cleanup helpers and unit coverage for original cause preservation and snapshot failure.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt`: `persist`, `verify`; save/name/verification ordering.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingVerification.kt`: `verifySavedPairing`; exact-credential verification, terminal errors and bounded deadline.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: pair-code destination; navigation on Complete.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ChannelListScreen`; its arrival marker is present regardless of host rows or connection availability.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `pairingStatus`, `reconcile`; credential-bound status versus compatibility selection.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `pairHostByCode`; existing phone pairing pattern.
- `scripts/android-test-gate.py`, `scripts/e2e-emulator.sh`: focused selectors, isolated daemons and retained counted XML/logcat.
- `docs/knowledge/features/pairing-confirm-gate.md`: saved pairing alone does not prove host authentication.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: name-only reconciliation retains connection identity; cached rows do not prove availability.
- `docs/knowledge/features/development-verification.md` and `docs/e2e-interactive-stream.md`, Session-error recovery (#1731): focused versus dispatcher evidence and both arm invariants.
- Daemon `internal/relay/connection.go`: `Connection.run`; conn-established means the relay has claimed the daemon slot on upgrade.
- Daemon `docs/protocol-mobile.md`, Security model: existing Noise and trust boundaries remain authoritative.

## Context

The #1948 historical stderr records a 40-second timeout in `pair` after Confirm pairing, before recovery assertions; its XML records 65 executed, 3 failed, 0 skipped. The rerun passed this method. Historical per-arm artifacts are unavailable, and the retained controller log is empty, so neither the arm nor a cause can be inferred from them. The maintainer narrowed #1977 on 2026-10-10 to reviewing and verifying the diagnostics only. The original cause-and-repair contract remains open in #2048; closing this ticket makes no repair claim. No decision record or visual change is proposed.

## Design

Keep the shared live/scripted scenario and all recovery assertions enabled. First capture safe arm/action lifecycle observations in the controller log as well as private per-arm evidence. Capture post-confirmation stage failures with the arm, saved-record presence, exact-host connection existence/selection, static relay and encrypted-session status classes, and known UI phase booleans; never serialize pairing state, payloads, exception details or the semantics tree. These distinguish start readiness, credential persistence/host authentication and UI navigation. Start and close observations must survive private HOME cleanup and removed worktrees.

Finish only the evidence boundary: track static pairing/recovery phases and fixture-start readiness, include exact-host connection existence in the pairing snapshot, and keep diagnostic-read and teardown failures from replacing the original exception. Unit checks cover both arms, unchanged causes, safe snapshot fallback and cleanup. Controller observations expose only readiness booleans from already-observed start state, never credentials or private status values. Do not change timeouts, retry, delivery behavior or production code. Preserve retained delivery once without resend, dropped non-delivery and fresh-send recovery, pills/local status, queue/message identities, completed-child counts and daemon/Runner/session identity. Remove each owned pairing and close each owned daemon even on failure; preserve preceding pairings and connection identity.

No new production types, UI state or dependencies are planned. No overlapping remote feature branch touches these files. Forecast: approximately 350–450 total written lines, including the existing checkpoint, plan changes and diagnostic tests; 0 production files, 2 unchanged wrapper consumers, 4 acceptance criteria and fewer than 4 new error paths. This is one independently verifiable diagnostic deliverable within all builder sizing limits.

## State and concurrency model

The synchronous authenticated controller owns each private daemon. Existing bounded polling and phone test-thread waits remain; test-only runBlocking observes production flows. Exact-host status is diagnostic, not a replacement for the phone flow or peer wire assertions. Fixture cleanup stays in finally and peer observers stay in use. No production scope/lifecycle change is planned.

## State transitions and identity reuse

The diagnostic phase/readiness fields reset for each retained/dropped arm; they observe the existing run and do not drive readiness. Unit checks cover both arm labels, failed snapshot reads and cleanup during a primary failure. Both existing recovery methods retain the two arm runs, completed-child input counts and preceding-host connection identity assertions; focused scripted recovery verifies their unchanged flow.

## Error handling

Failures remain failures, with static arm/stage evidence; no skip or automatic replay. Controller logging must not delay or fail teardown. A missing fresh result is unverified. Diagnostic reads use a static unavailable fallback if they fail. Cleanup still runs and its exception is suppressed onto the original failure rather than replacing it. Cause investigation and any reliability repair belong to #2048.

## Testing strategy

Test first: controlled Python tests prove both arms retain readiness/action evidence without publishing phone/peer tokens, authorization, prompts or exception contents. Shared Kotlin unit tests prove the original timeout/exception is retained, snapshot failures cannot replace it, and cleanup preserves a primary failure. Run the focused scripted `session-error` twin and inspect fresh XML counts and per-arm control/input evidence. These existing instrumentation scenarios require real sockets, Keystore pairing and daemon subprocesses; Robolectric cannot execute that path. Run focused relevant unit/shared tests, lint, assembleDebug, androidTest compilation, Spotless and final pre-verify after merging main. Preserve the earlier focused live/historical evidence below; do not repeat passing live prefixes to infer a cause.

Dispatcher owns fresh full scripted-all and live gates, each with the named `interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog` pass, executed/passed/failed/error/skipped counts and retained XML/logcat/per-arm artifact locations recorded separately from focused builder results. Keep `needs-real-claude` on #1977 and `all` in the PR's Live tests section for the full-live handoff. Pending dispatcher acceptance is a handoff, not a blocked builder result.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, Session-error recovery (#1731), with safe arm/phase/readiness evidence and actual fresh full dispatcher scripted-all/live counts, named passes and retained artifact locations. Describe #1977 as diagnostics only; cause and repair remain open in #2048. Historical missing artifacts remain an evidence limitation. No diagnosed cause is required from this ticket.

## Open Questions

- The historical failing arm and cause remain unknown. Resolution: explicitly deferred to #2048 by the maintainer's 2026-10-10 scope change; passing repetitions are not cause evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] The existing authenticated controller action allowlist and production pairing parser remain the boundaries. Diagnostics read only known static stages/status classes and booleans; no inbound daemon text is logged.
- [Tokens] SHOULD FIX: tests must prove controller observations exclude phone/peer pairing tokens, authorization, keys and inherited credential values. Do not format fixture responses or exception details.
- [Files and storage] Evidence uses the existing private fixture folder and build artifact root; no user-derived path is introduced. Existing atomic executable selection and Keystore storage remain unchanged.
- [Android attack surface] No component, intent, provider, permission or WebView change. Existing real phone pairing remains exercised.
- [Cryptography] Existing vendored Noise and Keystore are untouched; no key, nonce or primitive change.
- [Network and I/O] Preserve bounded request/poll deadlines, loopback controller binding and constant-time authorization. Logs do not expose headers or frame contents.
- [Errors, logs and telemetry] SHOULD FIX: use only closed arm/action vocabulary, static error codes and status class names; never stringify an exception or full PairCodeState/semantics tree. Test the publication boundary.
- [Concurrency] Preserve daemon and pairing finally ownership and bounded test waits. Diagnostic observation must not become readiness by elapsed time or change selection.
- [Threat model] Delaying/dropping relay traffic must produce a bounded failure, not success/retry. Rooted-device token theft remains mitigated by unchanged Keystore wrapping. Hostile frames use unchanged production decoding; UI/keyboard token exposure remains the existing explicit test-only paste path, and artifacts must never copy its text.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

## Revisions

- 2026-10-08: the unchanged phone/control behavior with added diagnostics passed focused live recovery (1 executed/passed, 0 failed/errors/skipped; `build/dispatcher-tests/live-ee_lgy4a/dispatcher.xml`), scripted recovery (1 executed/passed, 0 failed/errors/skipped; `build/dispatcher-tests/scripted-wgha84iz/dispatcher.xml`) and the four-method live host/rebuild prefix (4 executed/passed, 0 failed/errors/skipped; `build/dispatcher-tests/live-o7_cqc5a/dispatcher.xml`). Both arms' completed child inputs were 1/0 retained and 0/1 dropped, with unchanged daemon/Runner/session identity. These passing baselines do not identify the historical arm or establish a repair. Replaying all 15 historical predecessors remains the next diagnostic step. The controller observation regression failed because its log was empty before the change; 10 Python tests pass afterward, including a real authenticated failed-start request that logs only arm/action/static phase.
- 2026-10-08: the first exact historical prefix ran 16 methods: 15 passed, deletion failed, no errors/skips (`build/dispatcher-tests/live-gpgvm5_q/dispatcher.xml`). Recovery passed both arms. The deletion timeout matches existing #1888 and is not a demonstrated pairing dependency. Map the historical pairing stack against tested merge `9f160e8dc29b809d31d826cbcf658b5a5e6d8df1`; its daemon revision and Claude version match the fresh runs. Keep known save/name/update-required UI error booleans in the diagnostic snapshot so a saved, connected host with a naming failure cannot be mistaken for a navigation failure. No reliability repair is justified yet. Main `0c22abc99dec` is merged; pre-verify, APK build and the whole cached unit/shared result (4,953 tests, no failures/errors/skips) are green. A second historical-prefix reproduction is queued behind a dispatcher live run.
- 2026-10-08: the second and third exact historical-prefix runs each passed all 16 methods, no failures/errors/skips (`build/dispatcher-tests/live-8xgu_mp7/dispatcher.xml` and `build/dispatcher-tests/live-1yq5a121/dispatcher.xml`). Recovery passed both arms in both runs. Their per-arm evidence (`pyry-e2e.fZuWqK`, `pyry-e2e.MZC9lr`) confirms unchanged daemon/Runner/session identity and completed-child input counts of 1/0 retained and 0/1 dropped. Across the standalone run, four-method prefix and three historical-prefix runs, recovery passed five live executions; the scripted twin passed its focused execution. This is an incomplete diagnostic baseline: the failing arm and cause are still unresolved, no evidenced reliability repair exists, and dispatcher scripted-all/full-live acceptance has not run on this branch. Preserve this checkpoint without claiming the ticket fixed.
- 2026-10-10: resumed without a new occurrence comment. The historical controller log remains empty. Source review confirms that the channel-list arrival marker is unconditional, credential verification is independent of compatibility host selection, and the daemon reloads devices for each handshake. These constraints do not establish which readiness, authentication or UI stage failed historically. Updated the testing strategy to the current builder policy: full unit/shared execution belongs to the dispatcher.
- 2026-10-10: fresh focused scripted recovery passed (1 executed/passed, 0 failures/errors/skips; `build/dispatcher-tests/scripted-dpp9vfin/dispatcher.xml`) and fresh focused live recovery passed (1 executed/passed, 0 failures/errors/skips; `build/dispatcher-tests/live-af2rsool/dispatcher.xml`). Both arms preserved daemon/Runner/session identity and completed-child counts of 1/0 retained and 0/1 dropped. Evidence is under `build/session-error-evidence/pyry-e2e.O3AKqC/` and `build/session-error-evidence/pyry-e2e.LRjzCl/`; the daemon revision is `a536d17b1e182fb5398a5458e3afe6079b37a510`. Ten controller tests and 18 focused unit/shared tests passed, with no failures/errors/skips; lint passed. The initial scripted attempt executed zero tests because device acquisition timed out, then the queued attempt executed normally. No repair is claimed.
- 2026-10-10: the current-tree historical-prefix replay passed all 16 methods, with 0 failures/errors/skips (`build/dispatcher-tests/live-upej05kb/dispatcher.xml`). The XML method order exactly matches the first 16 historical cases. Recovery passed both arms; `build/session-error-evidence/pyry-e2e.76bYDf/` confirms unchanged daemon/Runner/session identity and completed-child input counts of 1/0 retained and 0/1 dropped. Across both builder legs recovery has passed seven live executions and two focused scripted executions. The historical failing arm and cause remain unresolved, with no justified reliability repair or owning-repository dependency. Fresh full dispatcher scripted-all/live acceptance remains pending; this checkpoint is incomplete.

- 2026-10-10: maintainer scope update: #1977 is the reviewed diagnostic checkpoint only; the original diagnosis and evidenced repair remain open in #2048. Review found that snapshot reads and finally cleanup could replace the timeout, and recovery failures lacked a safe phase label. Finish those evidence gaps with cause-preserving helpers and controlled tests, without changing either scenario contract or running more historical prefixes. Fresh full dispatcher scripted-all/live gates remain explicit acceptance handoffs.
