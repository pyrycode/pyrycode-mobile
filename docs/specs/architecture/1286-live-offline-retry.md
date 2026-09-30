# #1286 — Live Offline Retry proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`, `setHostLink`, `awaitConnected`, `sendFromPhone` — live thread and host identity patterns; `setHostLink` is an intentional close and cannot prove Offline.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` → `arriveInSeededThread`, `typeAndSend` — scripted thread and fixture conventions.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `runLoop`, `backoff`, `retry` — daemon absence produces actual Offline state; Retry collapses its wait.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt` → `ThreadTopOverlay` — the visible Retry pill has `offline_retry_target` semantics.
- `scripts/e2e-emulator.sh` → primary daemon launch, cleanup, deterministic scenario and live selector — owns the isolated first daemon identity and instrumentation arguments.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, `SCENARIOS` — executed-count gates.
- `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list` — selector/floor regression check.
- `docs/knowledge/features/thread-screen.md` and `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md` → connection placement and exact-host Retry wiring.
- `docs/knowledge/features/relay-reconnect-supervisor.md` → daemon-absent and capped-backoff behavior.
- `docs/knowledge/features/development-verification.md` and `docs/e2e-interactive-stream.md` → instrumented evidence and rung-3/rung-4 harness rules.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910

The dark thread keeps its messages and composer in place while a small red `Offline · Retry` pill floats at the top-right of the message region. The test identifies the existing clickable pill and checks that it clears on reconnection; this ticket changes no visual production code.

## Context

The prior offline-read scenario invokes `close()`, which deliberately projects idle and hides the Retry pill. This test must fail the harness-owned daemon while the phone remains foregrounded and in its current thread, then prove the exact-host UI action restores the conversation. No production interface or wire contract changes.

## Design

Add a test-only host fault controller under `scripts/` that launches the harness's first daemon command, exposes local `stop` and `start` controls to the emulator, and always reuses its command, environment, instance name and persistent pairing material. The controller owns only that child and cleans it up on harness exit. The harness passes the controller's ephemeral port as an instrumentation argument only to the offline-retry methods; it writes no credential to a fixture or report.

The live method creates a fresh chat and stays inside its thread. It requests a daemon stop, waits for the first host's `RelayConnectionSupervisor.relayStatus` to reach daemon-absent/Offline and for the visible `offline_retry_target` to appear, and keeps observing until the supervisor reaches the capped Offline backoff. It then requests restart, asserts the same host's Retry pill is still displayed, taps that actual pill, and requires the host repository and pill-cleared state within a deadline shorter than the capped passive backoff. It sends a new phone ping and waits for a rendered real-Claude reply in the same thread. A `finally` block restarts the owned daemon if a test assertion fails.

The deterministic twin uses the same controller and seeded channel with the existing `ping` fixture. This is possible because daemon process control is independent of the scripted Claude producer. Add `offline-retry` to its scenario selector and `SCENARIOS`, then add the live method to the curated selector and raise `LIVE_MINIMUM` by one. Test the controller's process lifecycle and the selectors with host-side unit tests. No production source or API changes; no edit fan-out.

## State and concurrency model

The app keeps its foreground lifecycle, selected host and thread destination. The controller serializes stop/start requests and waits for the old child to exit before restarting it. The supervisor alone emits the connection state; tests observe it and never inject state or invoke `close()`. The daemon-absent retry loop eventually reaches the 30-second cap, giving the test a bounded window to restart then tap; the test requires recovery well before that passive window expires. Controller termination closes the child during harness cleanup.

## Error handling

Controller requests fail with a clear non-success response if process stop/start fails; the test fails on that response or on missing state, pill, reconnection or reply. The harness aborts on controller startup failure. The test's cleanup makes a best-effort restart and preserves the original assertion failure. No credential, token, prompt or decrypted payload is logged by the controller.

## Testing strategy

- RED: add host-side tests for stop/start ownership and selector/floor behavior, plus the instrumented methods, then observe the focused scripted scenario fail before implementing its controller and harness selection.
- GREEN: run the host-side tests and `python3 scripts/android-test-gate.py scripted offline-retry`; inspect its fresh XML for one executed, unskipped passing named method. Run `compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`, and the forced Spotless check.
- The dispatcher owns the full scripted suite and the post-verifier `python3 scripts/android-test-gate.py live`; require its fresh XML to include the new live method with nonzero executed count and zero failures/skips before the ticket is accepted. The builder does not claim that live gate passed.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md` in the rung-3 live and rung-4 deterministic coverage sections with the new Offline Retry scenarios and their evidence boundary; update `docs/knowledge/features/thread-screen.md` connection overlay coverage with the live proof. No shared documentation is edited in this builder stage.

## Open questions

- Does stopping the first daemon cause the relay to report daemon absence to the still-open phone, or only a generic drop? The focused deterministic run will resolve this; if generic, the test can still wait for the supervisor's capped Offline state without changing production behavior.
- Can the seeded channel answer a second prompt after its daemon restarts? The focused deterministic run will resolve this through the rendered scripted reply.
