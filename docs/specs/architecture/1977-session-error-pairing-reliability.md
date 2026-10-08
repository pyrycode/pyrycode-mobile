# Session-error scenario pairing reliability (#1977)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `run`, `runCase`, `pair`; fixture and saved-host ownership and the anonymous post-confirmation wait.
- `scripts/e2e-session-error.py`: `Case.start`, `Case.action`, `Case.close`; closed-stdin and relay readiness, independent identity and input-count evidence.
- `scripts/test_e2e_session_error.py`: `SessionErrorControlTest`; private fixture contract regressions.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt`: `persist`, `verify`; save/name/verification ordering.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingVerification.kt`: `verifySavedPairing`; exact-credential verification, terminal errors and bounded deadline.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: pair-code destination; navigation on Complete.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `pairingStatus`, `reconcile`; credential-bound status versus compatibility selection.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `pairHostByCode`; existing phone pairing pattern.
- `scripts/android-test-gate.py`, `scripts/e2e-emulator.sh`: focused selectors, isolated daemons and retained counted XML/logcat.
- `docs/knowledge/features/pairing-confirm-gate.md`: saved pairing alone does not prove host authentication.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: name-only reconciliation retains connection identity; cached rows do not prove availability.
- `docs/knowledge/features/development-verification.md` and `docs/e2e-interactive-stream.md`, Session-error recovery (#1731): focused versus dispatcher evidence and both arm invariants.
- Daemon `internal/relay/connection.go`: `Connection.run`; conn-established means the relay has claimed the daemon slot on upgrade.
- Daemon `docs/protocol-mobile.md`, Security model: existing Noise and trust boundaries remain authoritative.

## Context

The #1948 historical stderr records a 40-second timeout in `pair` after Confirm pairing, before recovery assertions; its XML records 65 executed, 3 failed, 0 skipped. The rerun passed this method. Historical per-arm artifacts are unavailable, and the retained controller log is empty, so neither the arm nor a cause can be inferred from them. Diagnose the mobile-owned scenario before repairing it; a proven daemon/relay cause must be routed to its owner. No decision record or visual change is proposed.

## Design

Keep the shared live/scripted scenario and all recovery assertions enabled. First capture safe arm/action lifecycle observations in the controller log as well as private per-arm evidence. Capture post-confirmation stage failures with the arm, saved-record presence, exact-host connection existence/selection, static relay and encrypted-session status classes, and known UI phase booleans; never serialize pairing state, payloads, exception details or the semantics tree. These distinguish start readiness, credential persistence/host authentication and UI navigation. Start and close observations must survive private HOME cleanup and removed worktrees.

Run the unchanged deterministic scenario as a baseline. Add controlled regressions for any evidenced ordering/readiness defect before its repair. The smallest mobile fixture/scenario repair will be recorded in Revisions before implementation; do not change timeouts or add retries as a substitute for the missing state observation. Preserve retained delivery once without resend, dropped non-delivery and fresh-send recovery, pills/local status, queue/message identities, completed-child counts and daemon/Runner/session identity. Remove each owned pairing and close each owned daemon even on failure; preserve preceding pairings and connection identity.

No new exported types, production UI state or dependencies are planned. No overlapping remote feature branch touches the initial three-file design surface. Forecast: approximately 300–450 written lines including plan, diagnostic observations and regression tests; 0 exported types, 2 existing wrapper consumers, 3 acceptance criteria, fewer than 4 new reject branches. Recount after diagnosis.

## State and concurrency model

The synchronous authenticated controller owns each private daemon. Existing bounded polling and phone test-thread waits remain; test-only runBlocking observes production flows. Exact-host status is diagnostic, not a replacement for the phone flow or peer wire assertions. Fixture cleanup stays in finally and peer observers stay in use. No production scope/lifecycle change is planned.

## Error handling

Failures remain failures, with static arm/stage evidence; no skip or automatic replay. Controller logging must not delay or fail teardown. A missing fresh result is unverified. If diagnosis changes the repair surface or contract, update Revisions and recheck sizing before implementation.

## Testing strategy

Test first: controlled Python tests prove lifecycle evidence identifies retained/dropped and readiness/action failures without publishing tokens, authorization, prompts or exception contents. Add a controlled regression that fails on the evidenced defect, then repair and rerun it. Run focused `scripted session-error` and named live recovery, inspect fresh XML counts and both arms' control observations/input counts. These existing instrumentation tests require real sockets, Keystore pairing and daemon subprocesses; Robolectric cannot execute that path. Run focused relevant unit/shared tests, lint, assembleDebug, androidTest compilation, Spotless and final whole unit/shared suite plus pre-verify after merging main. Dispatcher owns scripted-all and fresh full live acceptance, including named passes and executed/failed/skipped counts; focused results are not full-gate acceptance.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, Session-error recovery (#1731), with the diagnosed cause, safe arm/phase evidence and actual full dispatcher scripted-all/live counts and named passes. Historical missing artifacts remain an evidence limitation.

## Open Questions

- Which arm fails, and is fixture readiness, exact pairing authentication or UI synchronization responsible? Resolve with fresh per-arm observations and a controlled reproduction; the historical stack alone cannot answer.

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
**Date:** 2026-10-08

## Revisions

- 2026-10-08: the unchanged phone/control behavior with added diagnostics passed focused live recovery (1 executed/passed, 0 failed/errors/skipped; `build/dispatcher-tests/live-ee_lgy4a/dispatcher.xml`), scripted recovery (1 executed/passed, 0 failed/errors/skipped; `build/dispatcher-tests/scripted-wgha84iz/dispatcher.xml`) and the four-method live host/rebuild prefix (4 executed/passed, 0 failed/errors/skipped; `build/dispatcher-tests/live-o7_cqc5a/dispatcher.xml`). Both arms' completed child inputs were 1/0 retained and 0/1 dropped, with unchanged daemon/Runner/session identity. These passing baselines do not identify the historical arm or establish a repair. Replaying all 15 historical predecessors remains the next diagnostic step. The controller observation regression failed because its log was empty before the change; 10 Python tests pass afterward, including a real authenticated failed-start request that logs only arm/action/static phase.
