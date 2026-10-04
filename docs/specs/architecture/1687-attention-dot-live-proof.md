# #1687 — Confirm the repaired attention-dot live scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn`, `answerHostPeer`, and `peerStep` establish the existing two-real-turn proof and setup boundary.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink`, and `close` retain the paired static identity while owning fresh sessions and cancelling peer work.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: `identityFor` is the current live peer's process-scoped identity registry, wired by #1686 after #1698.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceKeyStoreTest.kt`: continuity, destructive-copy, isolation, wrong-host and concurrent-creation regressions exercise the store the live peer actually consumes.
- `scripts/e2e-emulator.sh`: the curated live selector includes the attention method; the answer-host pairing is reused across scenarios.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`: Attention dot explains the content-description contract and existing real-turn coverage.
- `docs/knowledge/features/development-verification-test-scheduling.md`: independent retained key registries mean the older store's tests alone cannot prove the current live wiring.
- `docs/knowledge/features/development-verification-emulator-evidence.md` and `docs/e2e-interactive-stream.md`: full live evidence requires executed counts and named testcase results; dispatcher owns credentialed execution.
- Sibling `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Static keys — mobile side and Security model define token-to-first-key binding and the unchanged threats.

## Design source

N/A: evidence-only confirmation of an existing scenario, with no UI design or implementation change.

## Change

#1698 merged as PR #1701, resolving the prerequisite; #1686 subsequently wired the live peer to `PeerDeviceKeyStore`. Confirm the inherited opening failure is resolved using counted full-suite dispatcher evidence on the repaired source. Preserve the scenario, assertions, timeouts, daemon key binding and curated membership without an artificial code change. A's completed peer turn must mark only A Unread, opening A must return it to Idle, B's unanswered permission must remain Waiting across the settle while A stays Idle, and approval plus completion must make B Unread. Continue reading dot content descriptions without a transient Running assertion. Only this plan and its evidence record will be written unless fresh evidence establishes a residual scenario/harness defect.

One deliverable; forecast under 120 written lines, zero exported declarations, zero consumer updates, three acceptance criteria, and no new reject branches. Recount before commit remains within every sizing limit. Remote feature branches have no overlap with this plan path; no implementation files are planned.

## Testing strategy

Inspect retained dispatcher XML and its adjacent diagnostic report, require the full suite to have nonzero execution and zero failures/errors/skips, and explicitly confirm `de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_attentionDot_followsARealTurn` has a passing testcase. Record the command, tested branch/base, counts, named result and source equivalence. An earlier #1698 suite with an unrelated failure plus a focused rerun is supporting evidence only, never a full-suite pass.

The fresh #1683 full run tested `feature/1683` at `b10c5a6fe02b89f4d55e0da14b4e0c0360e9390b` merged with `d54d7d9cad237c68290333f233180fadf635a192`. The subsequent recorded merge `160057a22565329a67255c97c7d189e1fa3440d6` has those exact parents; its app, tests, scripts and build sources match this checkout at `4badff912b35ecc1b8f798887ee464b2b0725555`. Keep the dispatcher handoff requesting `all` for #1687 before documentation/merge; retained equivalent-source evidence is not a claim of a new #1687 run.

Run focused `PeerDeviceKeyStoreTest`, `RedialingLinkTest`, `PeerWaitTest` and `NoiseSessionFactoryTest`, then lint, assembleDebug, spotlessApply and forced spotlessCheck. No new behavior needs a red test, and no device/scenario repair requires a builder device run. Deterministic results supplement, and cannot replace, the existing two-real-turn live proof. Append verified evidence after the plan commit.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, What rung 3 is made of / attention-dot scenario and Verification status: record resolved inherited opening failure, unchanged two-real-turn assertions, full-suite counts, named passing result and repaired-source provenance. Distinguish retained equivalent-source evidence from any new #1687 gate.
- Pending documentation stage: `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, Attention dot: link the counted live confirmation without changing the state or design contract.

## Security review

**Verdict:** PASS

- [Trust boundaries] Evidence does not introduce parsing or authorization paths. `NoiseSessionFactory` / `NoiseSessionPump` remain the authenticated frame boundary; `answerHostPeer` keeps the isolated harness pairing.
- [Tokens] `PeerDeviceKeyStore` retains keys in test-process memory by host/token fingerprint and returns independent arrays. Publish only counts, static diagnostic codes, revisions and testcase names; never pairing tokens, keys or credentials.
- [Files and storage] Only this plan is written. Do not copy private daemon logs, instrumented arguments or credential stores into the repository or PR; retained count-only XML is sufficient.
- [Android attack surface] No manifest, intent, component, permission, provider or UI changes. Existing answer-peer authority is exercised without widening it.
- [Cryptography] Preserve daemon token/key binding and vendored Noise IK. `dialLink` still creates fresh handshake/cipher state; successful setup must not be obtained through a binding bypass or nonce reuse.
- [Network and I/O] No TLS, frame bounds, wire, deadlines or retry changes. A timeout cannot be counted as a pass or masked by extending the wait.
- [Errors and logs] No new runtime logging. Read only static handshake event counts when comparing retained diagnostics; omit daemon-authored content and paths from extracted events.
- [Concurrency] No new jobs or state. `PeerDeviceKeyStore.identityFor` serializes publication; `SecondClientPeer.close` cancels its owned scope and closes the link. Leave both unchanged.
- [Threat model] Malicious-relay denial/delay remains bounded by existing waits and cannot produce a false passing testcase. Rooted-device token theft remains under existing Keystore/revocation controls; no storage changes. Hostile daemon frames still pass existing authenticated defensive decoding. Accessibility/screenshot exposure gains no new UI or secret output. Existing protocol residual threats stay with their protocol owners.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04

## Verification evidence — 2026-10-04

The inherited answer-peer opening failure is resolved on the repaired source: the named method reaches and passes every attention assertion. No remaining scenario or harness defect was observed, so this ticket makes no implementation change.

- Full dispatcher command: `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live`, reported in the [#1683 gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1683#issuecomment-5976372697). Retained XML independently confirms **53 executed, 53 passed, 0 failed/errors, 0 skipped**, and stderr confirms exit 0.
- Named testcase: `de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_attentionDot_followsARealTurn` occurs exactly once, with no failure, error or skipped child: **PASS**. Its unchanged control flow proves peer opening, A-only Unread, opening A to Idle, B's held Waiting state across settle with A Idle, and B Unread after approval/completion.
- XML: `/Users/juhanailmoniemi/Workspace/Projects/pyrycode-mobile-agents/logs/2026-10-04T03-40-35-710Z_real-claude-gate_#1683.log`; diagnostics: adjacent `2026-10-04T03-40-35-710Z_real-claude-gate_#1683.stderr.log`. XML SHA-256: `3f1b9e58cff011372ce34b02d2f02f83dae70862d6bb3ae10d32bff1e73b0e11`.
- Tested-source equivalence: `git diff --name-only 160057a22565329a67255c97c7d189e1fa3440d6 4badff912b35ecc1b8f798887ee464b2b0725555 -- app scripts gradle build.gradle.kts settings.gradle.kts gradle.properties` is empty. The app tree id is `d4839d655cd480351300e4d2c5d8f6b5f8b1eee7` on both; scripts tree id is `add6041860ce9dd36597c3b12fc714126dfd1876` on both. This confirms equivalent repaired source, not a newly executed #1687 suite.
- Independent earlier full #1686 report `2026-10-04T02-57-03-837Z_real-claude-gate_#1686.log` also records 53 executed, 0 failed/errors, 0 skipped and the named method passing. The #1698 report records 53 executed, 1 unrelated failure, 0 skipped with this method passing; do not describe that run plus its one-method rerun as a full-suite pass.
- Builder focused command: `./gradlew testDebugUnitTest --tests de.pyryco.mobile.e2e.PeerDeviceKeyStoreTest --tests de.pyryco.mobile.e2e.RedialingLinkTest --tests de.pyryco.mobile.e2e.PeerWaitTest --tests de.pyryco.mobile.data.network.NoiseSessionFactoryTest lint assembleDebug spotlessApply --console=plain`, with the installed SDK supplied through `ANDROID_HOME`: exit 0. Fresh XML records **28 executed, 0 failed/errors, 0 skipped** (6 key-store, 6 redial, 11 peer-wait, 5 factory tests). Lint, app build and formatting passed; command log retained at `/tmp/builder-1687/checks.log` and counted XML at `/tmp/builder-1687/jvm/`.
- `bash scripts/docs-guard.sh`: exit 0. No implementation, assertion, timeout, suite membership or security behavior changed.

Dispatcher handoff: retain `needs-real-claude` and request `all` in the PR so any fresh #1687 full-suite gate is counted before documentation/merge. Reuse the equivalent-source named proof above where appropriate; record any new run separately. Documentation remains pending for the owning stage.
