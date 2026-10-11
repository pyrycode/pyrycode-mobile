# #2009 — Attention read proof across phone and peer

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn`, `awaitRowAttention`; preserve the two read stages, row isolation and held permission checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `readMarks`, `history`, `markRead`, `awaitReadMark`; daemon facts are separate from phone receipt and presentation.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `checkpoint`, `received`; zero stored gaps does not rule out a numeric receipt hole.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `recordReadEnvelope`, `observeSnapshot`; compare the actual presented version, not an independently reduced row.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `applySnapshot`, `upsertConversation`, `recordLatestEntry`; read/latest facts merge monotonically within a connection.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.withReadMarks`, `resolve`; Waiting and Running take precedence over shared unread.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `updateReadMarks`, `updateAttention`; generation fencing and host-qualified projection.
- `app/src/test/java/de/pyryco/mobile/data/repository/ScriptedPingReadEvidenceTest.kt`, `ThreadReadRuntimeReceiptsTest.kt`: existing daemon-exported receipt controls.
- `docs/knowledge/features/remote-conversation-repository.md`: daemon read marks; #1989 and #2070 establish daemon ownership of omitted receipts, without identifying every historical live sequence.
- `docs/knowledge/features/channel-list-viewmodel-projection.md`, `thread-screen-testing-foreground-read-tracking.md`: attention joins and foreground read qualification.
- `docs/e2e-interactive-stream.md`: Phone read-mark proof and Peer read clears phone attention; existing rung-4 scripted ping twin.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: Marking a conversation read, A history entry and Security model are the wire source of truth.
- Sibling `cmd/pyry/queued_legacy_receipts_test.go`, `legacy_runtime_receipts_test.go`: real-store producers and serialized legacy pages for controlled causal comparison.

## Context

The original #1968 failure reached B after daemon confirmation, but retained stderr is unavailable on this runner. The supplied assertion is evidence of row semantics only, not phone receipt. October 10/11 retained stderr instead fails A: three rows/versions, no unknown or unidentified facts and no stored gap, yet no checkpoint reaches the target. October 11 used Mobile `6629a7a538fce2338a083507d8a24ee8a81d252b` and daemon `d49b8392e00b8de4273998393413594d57f30047`. That daemon contains #3026/#3029 but predates #3167. The queued-send omission is a concrete lead requiring reproduction. No decision record is needed.

## Design

Keep production contracts and visuals unchanged unless controlled evidence identifies a Mobile defect. Add scenario-local, content-free stage evidence separating peer daemon confirmation, phone repository read/latest, phone receipt coverage, host projection and merged row semantics. A diagnostic failure must preserve the original assertion and deadline. Add controlled regression coverage using real daemon-exported pages and the production Mobile projection/attention fold, retaining negative controls for missing/unknown receipts and unseen versions. Compare original and repaired daemon revisions in isolated scratch checkouts; never modify the production daemon. If a new daemon defect is established, reuse or file its owning blocker rather than weakening Mobile evidence. Record the proven causal design under Revisions before implementation of any repair that differs from this contract.

No new public type, dependency, wire field, UI state or event is planned. Estimated total written work: 550 lines, one deliverable, four criteria, no consumer migration and no new rejection branches. Overlap branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879 and #1896 touch other live scenarios; edits remain local to the named attention scenario.

## State and concurrency model

Existing repository generations and app-owned HostConversationSource collectors remain unchanged. Test collectors belong to coroutineScope and cancel on failure; peers close in finally. Diagnostics sample the owning host and never infer read from list presence, history receipt or a successful send. Live and history delivery retain original durable identities.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Completed A on list, then foreground read | Named live scenario; controlled phone checkpoint and confirmed-mark regression |
| B permission held then answered | Existing Waiting, settle, A Idle and B Unread live assertions |
| Peer confirms B while phone remains on list | Named live scenario; controlled remote confirmation and host attention regression |
| Duplicate/overlapping receipt, fresh generation, same conversation ID on another host | Existing ThreadReadRuntimeReceiptsTest and HostConversationSourceAttentionTest |
| Missing/unknown receipt or unseen content version | Controlled regression and existing ThreadReadClaimsTest |
| B row assertion fails after the phone repository disconnects | PeerReadProbeTest.unavailableRepositoryKeepsOriginalRowFailureAndIndependentEvidence |

## Error handling

Unknown, malformed, unidentified and numeric-hole barriers stay conservative. Diagnostics do not replace exceptions or retry actions. A daemon confirmation cannot be reported as phone receipt. Failure before B leaves B unresolved.

## Testing strategy

Write and run the causal regression against the original fixture before substituting the repaired fixture. Run focused unit classes for read claims, runtime/queued receipts, read marks, retry ownership and host attention. Run the existing scripted ping twin after repair. The real-device scenario needs actual Noise connections, background dispatchers and daemon history; Robolectric cannot provide those. The ticket assigns fresh full rung-3 proof to the dispatcher; do not claim a focused pass as acceptance. Run focused live only if needed for causal diagnosis. Run lint, assembly, Android test compilation, formatting and final pre-verification. Retain counted results and Mobile/daemon revisions.

## Open Questions

- Does #3167 explain the retained A condition under the original daemon? Resolve with controlled real-store pages, not aggregate counts.
- Does B have a separate receipt/watermark or projection failure? Capture daemon and phone facts separately; A-only failure/pass cannot settle this.

## Documentation handoff

Pending for documentation stage: update `docs/e2e-interactive-stream.md`, Peer read clears phone attention (#1883), with diagnosed cause, repair, actual full gate counts/revisions and named method result. Preserve phone-derived presentation versus history-backed peer read distinctions.

## Security review

**Verdict:** PASS

- [Trust boundaries] Use existing strict wire DTOs and exact-version projection; unknown or malformed receipts never become sight. Daemon list facts remain assertion targets only.
- [Tokens] No credential access or new storage. The live gate owns credential lookup; diagnostics never include pairing records or tokens.
- [Files/storage] Synthetic exported fixtures and counted evidence only under app/src; scratch output outside the worktree. No daemon/user payloads, paths or identities in retained diagnostics.
- [Android attack surface] No exported component, intent, provider or rendering change. Existing row content descriptions remain the observable contract.
- [Cryptography] Existing Noise IK and transport remain unchanged; no keys or decrypted payload bytes enter evidence.
- [Network/I/O] Existing frame limits, timeouts and cancellation apply. No new resend, retry or deadline extension.
- [Errors/logs] Counts, static stage labels and booleans only; do not log authored text, exception payloads or credentials.
- [Concurrency] All diagnostic child work stays in the scenario scope, peers close in finally, and observations retain host/generation identity.
- [Threat model] Delayed/dropped relay frames must remain failures, never inferred reads. Rooted-device theft and UI leakage receive no new surface; existing Keystore storage and text rendering remain. Hostile frames retain strict decode and evidence barriers.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-11

## Revisions

### 2026-10-11 — Controlled queued-receipt comparison

The original daemon real-store export at `d49b8392e00b8de4273998393413594d57f30047` appends raw types send_accepted/message/send_delivered/main_turn_opened/turn_state/assistant_delta/turn_end/turn_state at IDs 1–8. Its pager serves only 2,4,5,6,7,8: delivery ID 3 is unaccounted between received facts. The same producer setup on `f570f2f871ca50ab1f1739cc0f142f375682d05e`, containing merged daemon #3167 (`a3ca005f05e95650d10b7e07cb5d190252acbd1a`), serves all IDs with queued facts projected as existing empty info banners. Raw facts and their identities stay unchanged.

`AttentionReadReceiptTest.completedReplyCanQualifyForTheDaemonWatermarkWithAccountedDelivery` ran against the original exported page: 1 executed, 1 failed, 0 skipped, expected checkpoint 8 versus null. Substituting only the repaired real-store page makes it pass. The original-page negative control retains the bound settled reply, known facts and empty stored gaps while rejecting the numeric hole. Preserve the exporter, synthetic normalized fixtures and counted XML. No Mobile production workaround is warranted.

`HostConversationSourceAttentionTest.accountedPhoneReadThenPeerReadClearsOnlyItsRowWithoutASecondView` drives the real remote repository and host source: A's exact reply checkpoint requires correlated daemon confirmation; B's held prompt stays Waiting through its turn, becomes Unread after dismissal and clears only when the phone receives the peer's daemon-confirmed read. A remains Idle and a second host's identically named B stays Unread. No viewing handle is opened for B.

The original B assertion does not retain the daemon revision, phone read/latest or attention projection, and its stderr is not on this host. This comparison diagnoses A's receipt condition, not the historical B cause. Keep B's existing live assertions and distinguish confirmation, phone receipt and projection on failure. A-only runs cannot declare B cleared. The dispatcher must run the full live gate through both stages against a daemon containing #3167, record actual counts and retain the named result.

Security re-review: PASS. Fixture contents are synthetic and identifiers/timestamps normalized; raw payloads and credentials are never retained. The strict receipt and presentation barriers, cancellation and existing timeouts remain unchanged. Total written work remains below 800 lines, with no public types, migrations or new production branches.

### 2026-10-11 — Focused live causal reproduction and repair check

The named scenario ran on this worktree (production base `11ed225d27026e9e59abe634adc49c338dd41c51`, plan head `1b71d5ef92d421b98040edcd99d02442b3168e45`, scenario-local diagnostic changes) against both isolated daemon exports. Original `d49b8392e00b8de4273998393413594d57f30047`: 1 executed, 0 passed, 1 failed, 0 skipped, at A with rows=3, versions=3, no malformed/unidentified/stored gaps, numeric_holes=1, received_target=true, peer_entries_missing_on_phone=0, checkpoint/read_reaches_target=false. This independently matches the retained October 11 signature and confirms omission rather than a peer-only versus phone-only delivery difference.

Repaired `f570f2f871ca50ab1f1739cc0f142f375682d05e`: 1 executed, 1 passed, 0 failed, 0 skipped. Retained stage evidence confirms A's phone-derived read was daemon-confirmed; B's peer confirmation reached the phone and covered its latest fact; B and A were Idle while the phone stayed on the list. All original held-permission and isolation assertions executed. The configured scripted ping twin also passed: 2 executed, 2 passed, 0 failed, 0 skipped, daemon `63aabec70dad3948c916332a139eb22533b8f4b8`. Final affected unit run: 80 executed, 80 passed, 0 failed/errors/skipped.

The closed owning daemon issue #3167 is now linked as a cross-repository blocker and was read back. No new daemon issue or Mobile production repair is needed for the evidenced A cause. B's historical cause remains unestablished because its retained daemon/phone projection facts are absent; the focused pass demonstrates the current B contract, not a retrospective cause. Its independent confirmation/receipt/projection assertions remain required in the fresh dispatcher full live gate. Retained counted artifacts and invocation provenance live under `app/src/androidTest/assets/read-2009/`. Full live counts/result and the requested documentation section remain explicitly pending for their owning stages.

Exact-repair attribution: the first parent of daemon merge `a3ca005f05e95650d10b7e07cb5d190252acbd1a` is the failing `d49b8392e00b8de4273998393413594d57f30047`. Running the same real-store exporter at that exact merge passed (1 executed/1 passed), restored all eight receipt IDs and checkpoint 8, and matched the retained repaired fixture after the documented synthetic normalization. Thus later daemon commits are unnecessary for the evidenced A repair.

### 2026-10-11 — Final main merge verification

Merged main `a6840a356e8d5f02e62f888e5d1961b273d93475` as Mobile `f56b32d3398d13e48a34c6d693cf4b8e11930724`. Its authoritative thread presenter changes the foreground read path, so the named live method was rerun against the same repaired daemon archive `f570f2f871ca50ab1f1739cc0f142f375682d05e`: 1 executed, 1 passed, 0 failed/errors/skipped. Fresh retained stage booleans confirm both A's phone acknowledgment and B's confirmed read received by the phone, with both rows Idle and phone on list. The archive-built binary lacks embedded VCS metadata; its archive revision and actual binary/source hashes are recorded in invocations.json. This is focused post-merge evidence; full dispatcher live acceptance and documentation remain pending.

The same seven focused unit classes were rerun after this merge: 80 executed, 80 passed, 0 failures/errors/skips. No implementation or assertion changed after the paired live comparison, apart from formatting; subsequent edits retain counted evidence and record provenance.

### 2026-10-11 — Preserve B's assertion when failure evidence is unavailable

Verifier finding 1 on `e922846c7954` identifies an evidence-loss bug: the B failure handler requires a live repository even though `HostConversationSource.repositoryFor` legitimately returns null during disconnect. Extract only that handler into the shared test-only `peerReadProbeFailure` helper. Sample repository availability, phone marks, daemon marks and host projection independently; unavailable samples are explicit and cannot replace the caught row assertion, which remains the cause carrying the original stage and observed semantics. Preserve coroutine cancellation and every existing action, assertion and deadline. No production or visual behavior changes.

Add `PeerReadProbeTest` under unit tests, with the helper under sharedTest like `SendNowStage`: first reproduce the null-repository failure using the extracted original handler, then repair it. Cover an unavailable repository, a failed phone read, failed diagnostic suppliers, cancellation, and available facts that distinguish daemon confirmation from phone receipt and projection. Run this class and the existing read/attention classes; full live acceptance remains dispatcher-owned. The shared live file also overlaps #2057, whose edits concern another scenario and need no dependency.

Security re-review: PASS. Diagnostic failures expose only static availability labels, booleans and existing bounded comparisons; exception messages, payloads, credentials and routing identifiers never enter the probe. The original assertion remains its cause. No new storage, Android surface, network request, wire contract or crypto change is introduced; existing scope cancellation remains intact. Total written work remains below 1100 lines, with no exported types or consumer migration.

The null-repository regression ran against the extracted original handler: 1 executed, 1 failed, 0 errors/skips because the row assertion was lost. After repair, all five `PeerReadProbeTest` methods passed; the eight affected unit classes totaled 85 executed/passed, 0 failed/errors/skipped. Counted red/green XML and invocation provenance are retained under `app/src/androidTest/assets/read-2009/`. The scripted ping twin passed: 2 executed/passed, 0 failed/errors/skipped, evidence `build/dispatcher-tests/scripted-ce7zyw12/dispatcher.xml`. Lint, assembly and Android test compilation passed. Fresh full live acceptance and the documentation update remain pending for their owning stages.

### 2026-10-11 — Unresolved UI readiness finding on fd5d7dc61

Read `SavedThreadFirstDrawDeviceTest.awaitDeviceReadiness`, `measureSavedThreads`, `openAndMeasure`, `EmulatorCpuReadiness.awaitEmulatorCpuIdle`, the configured UI gate and the saved-thread feature overview. The reported 30000 ms coroutine timeout belongs to the CPU-readiness precondition before a Probe, rather than the 1000 ms committed-draw assertion. The retained dispatcher report has 258 executed, 257 passed, 1 failed, 0 errors, 1 skipped. Its detailed original method logcat is unavailable; the retained stderr cannot distinguish continuous busy CPU windows from a delayed synchronous shell sample. Neither the destroyed-activity snapshot nor #2087's independent projection repair establishes the readiness cause.

Compared exact source archives of merge base `ea3ab23e7e14aac63e59a3d05d28473e3905323f` and reviewed head `fd5d7dc61f4965f05202c3c69f5f92b14fa111e8`. Both production trees have Git tree identity `19c9911283aa464938a60b307e50efc89217bf6a`; the saved-thread test and CPU-readiness helper have identical SHA-256 hashes, and both gates select the same 63 device-only classes. Each archive ran `UI_GATE_FULL=1 ANDROID_GATE_WAIT_SECONDS=2700 timeout 2400 python3 scripts/android-test-gate.py ui`, using the configured single-shard Android 13 managed device and disabled animations. `UI_GATE_FULL` only prevents the archive's missing branch metadata from skipping the regular selection. Archive builds lack embedded Git metadata; archive revisions and concrete source hashes identify them.

Each full comparison exited 0: 258 executed/passed, 0 failed/errors, 1 skipped. The named method and its slow-restore negative control each executed and passed. Main's eight committed draws were 99/86/84/86/637/527/464/420 ms; the reviewed head's were 98/84/94/87/610/546/491/449 ms. Each named method recorded 32 CPU windows, eight readiness completions and eight installed/cleared openings. The controls rejected the original bound at 3065/3066 ms. No source, assertion, retry, threshold or deadline changed in either comparison.

These passing comparisons confirm an intermittent failure on the reviewed head; they do not diagnose or repair the historical timeout, or establish a matching failure on the merge base. Verifier finding 1 remains **not fixed**. No speculative change to the saved-thread test, production code or gate is warranted. Original and comparison counted XML, content-free CPU/worker/opening logs and provenance are retained under `app/src/androidTest/assets/read-2009/ui-rework/`. Window timestamps include delay and shell sampling; they are not separately measured shell-call durations. A causal repair requires a recurrence with stage evidence or the missing original detailed evidence.

Current Mobile `e5adaf6737cc4249855fb72b5d0e2618f8e643cb` already merges main `b44067bbd991b7d68e0418f88cc2a9e721671c61`, including #2087 and #2085. Its eight affected unit classes passed 85/85, 0 failures/errors/skips. The first unit attempt failed compilation with GC overhead and executed no tests; the successful invocation used the temporary compile property `-Pkotlin.daemon.jvmargs=-Xmx3072m`, without changing repository configuration. Lint, assembly and Android-test compilation passed. Fresh dispatcher UI, scripted-all and full live acceptance on the final head remain unclaimed; documentation remains pending for its owning stage.

Security re-review: PASS. Retained UI evidence contains only static test/case names, CPU percentages, durations, counts, source hashes and exception classification. No authored content, credentials, wire data or production behavior is added. The original read contract, coroutine cancellation and every readiness/draw assertion remain unchanged.
