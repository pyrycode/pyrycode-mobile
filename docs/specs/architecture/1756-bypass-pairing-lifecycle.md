# Bypass pairing at scenario entry (#1756)

## Files read

- `scripts/e2e-emulator.sh`: `start_bypass_daemon`, `mint_bypass_pairing`, `phone_pair_code`, `cleanup` and the curated LIVE selection own the isolated bypass fixture.
- `scripts/test_e2e_emulator_gradle.py`: `EmulatorBuildBeforeMintTest` and `EmulatorGradleTest` exercise actual shell blocks without a daemon or emulator.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` and `pairHostByCode` must retain code pairing and all permission assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DaemonFaultControl.kt` and `scripts/e2e-daemon-fault.py`: the existing host-loopback control seam establishes emulator connectivity and harness-owned process cleanup.
- `scripts/android-test-gate.py`: `curated_live_methods` and live report collection require the named method to remain in the full curated invocation.
- `docs/knowledge/features/development-verification-test-scheduling.md`: focused success does not establish full-suite fixture lifetime; diagnostics must distinguish setup failure from permission failure.
- `docs/e2e-interactive-stream.md`: rung-3 harness and operator-bypass coverage contract.

## Context

The #1668 full gate executed 53 tests with one failure, no errors and no skips. The bypass method timed out in `pairHostByCode` before its permission assertions. Its daemon reported `v2.handshake.reject.redemption_window_elapsed`; the same-tree focused rerun executed one test and passed. The ticket's refinement comment retains the log paths and timestamp. #993 moved minting after the build, but preceding suite methods can still exhaust the 15-minute redemption window. No daemon or app behavior change is needed, and no decision record is required.

Sizing: one fixture-lifecycle deliverable, approximately 550 written lines including tests and plan, no exported app types, one instrumentation consumer, four acceptance criteria, fewer than ten failure branches. #993's harness and regression change is the analogue (239 insertions and 91 deletions in its implementation commit). This fits all builder boundaries.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1727, #1735 and #1775 edit other live methods; #1735 and #1775 add curated entries and #1775 adds an unrelated prerequisite guard/test. Those changes do not restructure the bypass fixture. Keep edits local and additive.

## Design

Replace eager `mint_bypass_pairing` with `start_bypass_pairing_fixture`, after APK build and bypass-daemon readiness. A new Python helper `scripts/e2e-bypass-pairing.py` binds an ephemeral port on `127.0.0.1`, publishes its port and a random per-run authorization capability in the private harness directory, and waits without minting. The shell passes only the port/capability and existing outside-workspace witness arguments to instrumentation. `cleanup` terminates the helper before removing the isolated HOME.

The bypass method waits for the initial channel list/connection and grants camera permission, then requests its fixture immediately before `pairHostByCode`. An internal instrumentation helper connects to `10.0.2.2` with bounded socket timeouts, sends one authenticated POST, parses a bounded JSON response, and returns phone code, server id, peer token and server public key. Neither the payload nor capability appears in failure messages.

The Python helper mints one unprivileged phone pairing and one `--allow-remote-permissions` peer pairing through the existing isolated daemon, parses the CLI payload in memory, substitutes the phone relay URL exactly as `phone_pair_code` does, and returns the fixture. The pairings must identify the same host/key. Only the first authenticated request may mint; a repeat fails, including after a failed mint. There is no expiry extension, skip or refresh/retry path. Keep the method in the same full-suite invocation. All same-session mode, acknowledged-write, outside-workspace Read prompt and reply-token assertions remain intact.

## State and concurrency model

The harness owns one fixture-server process per bypass daemon; no additional app state, ViewModel jobs or flows are introduced. A single-threaded server serializes requests and consumes the one-shot mint before doing I/O. Shutdown closes the server and harness cleanup owns process termination. Pair CLI calls have finite subprocess deadlines; the instrumentation socket is closed with `use` on every path. No periodic token renewal or work outlives the harness.

## Error handling

Existing daemon prerequisite failures remain `bypassUnmet` failures. Fixture startup failure is a static unmet code. Unauthorized requests cannot mint; unknown routes fail. Repeated requests fail rather than returning cached credentials. CLI, decode and host mismatch failures return a static failure without child output or exception details. Instrumentation turns transport, status and parsing failures into one content-free assertion. The ordinary phone pairing flow remains responsible for redeeming the fresh code.

## Testing strategy

Test first in `scripts/test_e2e_emulator_gradle.py`: run the real shell setup with a stub CLI and fake clock file, advance that clock beyond 15 minutes after setup but before requesting the fixture, and verify both minted pairings are fresh at redemption time. Old eager minting must fail this regression without a sleep or Claude turn. Verify phone/peer privileges, relay rewrite, host/key agreement, one-shot behavior, authorization and content-free errors. Exercise shell argument forwarding and cleanup ownership. Run all harness regressions in that file and focused helper tests.

The existing device-only live method stays device-only because it pairs through the real UI/Keystore and asserts actual daemon/Claude permission behavior. Compile instrumentation and run that method through the managed live gate during development; run a focused scripted ping for the unchanged general harness path. Run lint, assembleDebug, spotlessApply and forced spotlessCheck. No whole-project unit/check sweep. Dispatcher owns the required fresh full rung-3 suite after verification: it must report executed/failed/skipped counts and retained artifact path with the bypass method executed and passed. Focused success does not satisfy that acceptance criterion.

## Open Questions

None. The host-loopback fixture seam avoids scheduling dependence while preserving the existing full-suite selection.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, operator-bypass coverage in “What rung 3 is made of”, to explain scenario-entry minting across long suites and record the dispatcher's fresh passing full-suite evidence. Do not edit that document during the builder stage.

## Security review

**Verdict:** PASS

- Trust boundaries: the helper accepts only a fixed authenticated `/pair` operation, never client-provided names, paths or commands. CLI payload decoding and host/key validation are explicit; production pairing validation remains unchanged.
- Tokens and credentials: generate the capability with Python `secrets`; compare it with `hmac.compare_digest`. Pairing material exists only in captured child output, the HTTP response and instrumentation memory before ordinary app storage. Do not echo child output or include payloads in exceptions. Preserve unprivileged phone and privileged peer separation.
- Files and storage: the port/capability file lives in the harness's private `mktemp` directory, is mode 0600 and is published only after server binding. No pairing-code persistence is added. Existing isolated HOME and witness cleanup stay harness-owned; no input supplies filesystem paths.
- Android attack surface: no production component, manifest, deep link, provider or WebView change; the new client is instrumentation-only.
- Cryptography: no Noise, key storage or wire change. Loopback HTTP is test-only; production pairing still uses the existing relay and Noise protocol.
- Network and I/O: bind only host loopback, authenticate before invoking CLI, set finite CLI/connect/read deadlines and bound response size. A repeated authenticated request cannot create another pair of credentials.
- Errors and logs: suppress HTTP request logging and server exception detail. Emit only static lifecycle/error codes. Tests assert capability, tokens, keys and pairing payload are absent from output.
- Concurrency: single-request serialization and one-shot state prevent overlapping minting. `cleanup` kills the fixture before deleting the daemon HOME; socket closure is guaranteed on instrumentation failure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05
