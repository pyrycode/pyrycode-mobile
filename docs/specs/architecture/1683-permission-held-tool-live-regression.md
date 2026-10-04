# #1683 — Protect the permission-held running-tool live scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`, `holdToolOnPermission`, `runningToolPeer`, `peerStep`, and the Stop scenario's prior-peer regression.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `awaitPermissionModal`, `allowOnce`, `dialLink`, and `awaiting`; permission approval remains a real privileged-peer round-trip.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt`: process-scoped exact host/token identity retention from merged #1698.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`: `sequentialFactoriesPresentSameBoundIdentityInFreshNoiseHandshakes` authenticates successive initiator identities without reusing session state.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` and `RedialingLink.kt`: bounded, cancellation-aware waits and content-free link diagnostics.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/development-verification.md`, and `development-verification-test-scheduling.md`: standalone scenarios must exercise a previously bound token; fresh counted evidence is required.
- `docs/e2e-interactive-stream.md`: permission-held running-tool contract and retained #1698/#1696 full live evidence.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Static keys — mobile side and Security model are the protocol authority.

## Design source

N/A: test-harness restoration of existing permission/status behavior. No UI, visual design, or wire-contract change.

## Context

The original #1631 and #1637 branch/base stderr reports contain coroutine timeouts, not status assertion failures. The retained daemon logs for #1631 (`pyry-e2e.AOLbIc` / `pyry-e2e.JKsJv3`) contain 102/84 static-key mismatch rejections; #1637 (`pyry-e2e.Cf5gl9` / `pyry-e2e.tWgsL4`) contain 102/90. Sequential peer instances rotated the static key for an already-bound token. Merged #1698 repairs that custody through `PeerDeviceStaticKeyStore`. The repeated 30-second failures are consistent with peer opening; the #1631 base 90-second timeout matches `awaitPermissionModal` in this scenario, but its underlying delivery/turn cause is not established by the retained evidence. We must not claim a status-rendering defect or invent that missing cause.

The named testcase has no failure/error/skip in both retained full #1698 and #1696 runs (each 53 executed, 1 unrelated failure, 0 skipped). The #1698 retained daemon log has zero static-key mismatch rejections. These are supporting historical evidence, not this candidate's live acceptance. This ticket protects the landed repair within the specific permission scenario and makes a recurrence diagnosable. No decision record is needed.

Sizing: about 100 written lines including this plan and a local test edit, zero new exported types, zero signature/consumer migrations, three acceptance criteria, no new reject branches. Shared-file overlaps with #1631, #1642, #1686, #1690–#1695 edit other methods; #1694 also refactors `peerStep` compatibly. No real dependency remains and edits stay local.

## Change

Before the named scenario creates its held turn, open and close a prior `runningToolPeer` with the same pairing. Both peer opens must meet the existing handshake/probe readiness contract; neither requires another Claude turn. This makes reverting to per-instance key rotation fail even when the method runs alone. Reuse `peerStep` around prior/current peer opening, permission-modal arrival, approval/dismissal, and turn completion. Preserve the held ten-second command, every timeout, all three status observations, peer cleanup, method enablement, and curated selection. `holdToolOnPermission` also serves the ignored elapsed scenario; its only change is diagnostics around existing waits.

## Testing strategy

The existing authenticated successive-handshake JVM test is the deterministic regression for the underlying repaired identity custody. Before implementation, temporarily restore per-instance identity retention as a negative control and run that method, requiring one executed test failing at the token-bound identity assertion; undo the control by an ordinary file edit and rerun affected peer-store, Noise factory, redial and wait tests. No negative control is committed. This checks the actual failure mechanism without spending a live turn or duplicating #1696's regression.

Compile instrumentation, run lint and assembleDebug, apply formatting and force spotlessCheck. Run one focused deterministic `tool` scenario to confirm unchanged status rendering; this supplements rather than substitutes for the live permission proof. The changed live method needs actual relay/daemon permission and Android execution, so it remains device-only. Dispatcher owns a fresh full `python3 scripts/android-test-gate.py live` run on this candidate; no separate focused live run is required. Require executed/failed/skipped counts and an explicit passing testcase for the named method. Pending live evidence is a handoff, not a pass.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, What rung 3 is made of, permission-held running-tool scenario: record prior-peer identity coverage and labelled peer waits, preserving the real permission/status contract.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, Verification status: record the fresh dispatcher-owned full live command, candidate commit, retained reports, executed/failed/skipped counts, and explicit confirmation that `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` ran and passed. Distinguish original setup/modal-arrival timeouts from status assertions and historical evidence from candidate evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] `SecondClientPeer` continues to use parsed harness pairing records and existing authenticated transport. The new prior peer never sends a message or approves a permission. Existing `awaitPermissionModal` and `allowOnce` validate permission options and remote dismissal.
- [Tokens, secrets and credentials] Both instances reuse the existing process-retained exact host/token static identity, independently of app credentials. No credentials are minted, copied into diagnostics, or persisted by this change.
- [Files and storage] No storage paths, files, backup rules, or persistent keys change. Negative-control evidence contains counts and static assertions only.
- [Android attack surface] Test APK only; no component, intent, provider, UI or privilege changes. The phone remains unprivileged.
- [Cryptography] Vendored Noise IK stays unchanged. Each open creates fresh ephemeral/cipher state while retaining the token-bound static key; daemon rejection of other keys remains enforced.
- [Network and I/O] Existing bounded opens, reconnect backoff and waits are retained. No timeout increases, retries around assertions, or new frame handling.
- [Errors, logs and telemetry] `peerStep` labels are static operation names and `linkState` contains counts/categories only. Never include pairing records, tokens, key bytes, modal ids, prompts, decrypted frames or daemon-authored text. No new logging or telemetry.
- [Concurrency] Prior peer uses `use` for guaranteed close; observing peer retains its `finally`. Both existing scopes cancel on close; no new jobs or flows. Key retention is independent of socket ownership.
- [Threat model] Protocol Security model: relay impersonation, MITM and replay still use authenticated Noise with fresh session state; delayed/dropped frames remain bounded and diagnosed. Disk token theft gains no storage target; hostile frames keep existing decoding/rendering. UI leakage and prompt injection surfaces are unchanged. In-process extraction remains bounded to disposable test credentials.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04
