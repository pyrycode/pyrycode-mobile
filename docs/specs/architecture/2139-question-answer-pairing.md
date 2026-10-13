# Question-answer phone pairing reliability (#2139)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `pairAnswerHost`, `pairHostByCode` and `interactiveTurn_questionAnswer_reachesTheAskingConversation`; the failed operation precedes both answer round-trips.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerStage.kt`: timeout annotation currently reports only unopened peer state during pairing.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/AnswerHostSetupTest.kt`: `offlinePrecedingHostDoesNotBlockAnswerHostPairing` substitutes exact-target observation, so it cannot prove real registration/authentication ordering.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt`: `persist` and `verify` retain fingerprint confirmation and exact saved credentials.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingVerification.kt`: `verifySavedPairing` requires both connection legs within 30 seconds.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `pairingStatus`, registration, selection and per-host repository ownership.
- `docs/knowledge/features/pairing-confirm-gate.md`, `navigation.md` and `development-verification-emulator-evidence.md`: exact-target confirmation, navigation and limits of peer-only evidence.
- `docs/specs/architecture/1899-answer-host-setup.md`: earlier preceding-host prerequisite repair is already present.
- PR #2144: explicit-host authenticated readiness changes upload setup/reconnect, not the failing pairing wait.
- `docs/e2e-interactive-stream.md`: existing rung-3 question proof and private evidence constraints.

## Design source

N/A: instrumentation setup and diagnostics only, with no visual product change.

## Context

Recovered #1896 stderr locates the failure at `pairHostByCode`'s final channel-list wait after fingerprint confirmation. The original full gate executed 65 tests, 62 passed, 3 failed, zero errors/skips; a same-tree three-method rerun passed. Mobile was `0c9e9a6f49` merged with main `427640d577`; daemon was `9c9cd5a25c337666bdbed47c29202e695a172733`. Private source captures survive under the agents reliability evidence directory, but original per-method XML and phone logcat do not. Seven accepted daemon handshakes and unopened peer diagnostics cannot identify this phone's verification failure. Historical causal attribution remains unproven. No decision record is needed.

## Design

First expose pairing substages with fixed labels and lazy, addressed-phone snapshots: registered, selected, relay ready, daemon authenticated and repository present. Preserve all operations, deadlines and fingerprint actions; execute each action once. Supply diagnostics only for answer-host setup; unrelated `pairHostByCode` consumers retain their existing drive. Give the question scenario phone evidence independently of peer evidence.

Use the approved managed live launcher to locate a fresh recurrence, or a controlled regression exercising a concrete source defect. Repair only the demonstrated local pairing/setup defect, recording the final contract and evidence under Revisions. Diagnostics alone cannot close this issue. If investigation requires a broader production change or another repository's repair, route it through the ticket's refinement/blocker rules before dependent implementation. Keep both question turns, option and enabled Continue taps, remote/answered dismissal, reply inclusion/exclusion, peer-driven second removal and unconditional peer/pairing cleanup.

Remote overlap in the shared live file: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119 and #2138. Keep edits local and additive. #2138 changes a separate method and does not block pairing investigation.

## State and concurrency model

Diagnostics read existing per-host synchronous values, own no job and never retry or change selection. Existing ViewModel verification owns cancellation and its 30-second deadline; Compose navigation retains its 60-second deadline. The controlled tests use injected dispatchers or the existing native fixture; preserve Koin and pairing cleanup on failure.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Offline preceding host while pairing starts | Existing `AnswerHostSetupTest.offlinePrecedingHostDoesNotBlockAnswerHostPairing` |
| Diagnostic operation completes, fails or is cancelled | New `AnswerHostPairingStageTest`, including one execution, original cause, fixed output and cancellation |
| New host registers/authenticates while another host is selected | Controlled regression selected after diagnosis; record its exact name in Revisions before handoff |
| Same answer host is paired after a predecessor removes it | Compare same-process predecessor execution if fresh evidence supports ordering; no automatic retry |
| First phone answer and second peer answer | Existing named rung-3 method retains both completed turns |

No new process-death or UI lifecycle state is introduced.

## Error handling

Do not include exception messages in diagnostic records. Keep original causes for inspection and propagate caller cancellation unchanged. Diagnostic snapshots contain fixed labels, counts and booleans only: no pairing code, token, key, host identifier or prompt/reply text. No retries, skips or longer deadlines.

## Testing strategy

Write diagnostic contract tests before implementation and observe red. Build a controlled source-defect regression before its repair and observe red/green. Run existing pairing unit tests, the affected native fixture when changed, and the named managed live method. Device-only reason: real permission grant, encrypted on-device store, authenticated relay/daemon and navigation timing are not supplied by Robolectric. Inspect fresh XML counts and retain XML, phone logs, available isolated daemon diagnostics and hashes privately on the persistent agents volume. Commit only reviewed content-free counts/provenance. Run focused tests, lint, assembleDebug, instrumentation compilation and formatting, then final main merge, push and pre-verify. The post-verifier full live gate and exact full-suite evidence return belong to dispatcher acceptance.

## Open Questions

Which source defect explains a controlled failure at the final pairing wait? Investigation must resolve this before claiming a repair; a passing standalone run does not establish historical causation.

## Documentation handoff

Pending for documentation: update `docs/e2e-interactive-stream.md`, question-answer coverage and Verification status, with the demonstrated repair, historical attribution limits, controlled regression evidence, fresh full-live named outcome and suite counts, revisions and artifact locations. Documentation consumes builder/dispatcher evidence; it does not produce live proof.

## Sizing

One deliverable, four acceptance criteria, approximately 650 written lines, at most two new internal test helper types, six unchanged answer-host callers and no planned production refactor. Reassess after diagnosis if a demonstrated repair exceeds this local slice.

## Security review

**Verdict:** PASS

- Trust boundaries: `PairCodeViewModel` keeps parser, fingerprint and saved-record binding; diagnostic input is typed booleans, never raw payloads. Controlled observers cannot affect the live binding.
- Tokens/secrets: existing Keystore-wrapped store retains credentials. Do not print or commit codes, tokens, keys, phone identifiers or conversations. Existing launcher supplies login; no credential wrapper or extraction.
- Files/storage: raw captures go to owner-only fixed directories on the persistent agents volume with verified hash manifests; reviewed resources contain counts and provenance only. No production storage or backup change.
- Android surface: instrumentation-only helper; no new component, intent, provider, deep link or WebView. Existing native permission grant stays in setup.
- Cryptography: fingerprint confirmation and authenticated Noise readiness remain required; no primitive, key or nonce change.
- Network/I/O: keep existing connection, verification and Compose deadlines; no retry or selected-host fallback can manufacture success.
- Errors/logs: fixed stage labels and presence/readiness booleans only. Original causes survive; their messages are not copied into structured logs. Peer state cannot stand in for phone state.
- Concurrency: wrapper executes operations once and owns no scope; caller cancellation propagates. Existing finally cleanup closes peer and removes only the temporary host.
- Threat model: relay delay/drop remains bounded by existing waits and cannot authorize pairing; hostile daemon content is neither parsed nor logged by diagnostics. Rooted-device token theft and input-method exposure stay within existing pairing/store controls, unchanged by test code.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-13

## Revisions

- 2026-10-13: the unchanged focused baseline passed 1/1. The immediate historical predecessor plus the named method passed 2/2, and prior answer-host pairing/removal plus Direct Share plus the named method passed 3/3. All had zero failures/errors/skips, daemon `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f`. Diagnostic contract control executed 3 tests with 2 failures, then the diagnostic and existing pairing classes passed 20/20. These checks establish diagnostic coverage and retained answer proof, not a source-defect repair. Preserve this distinction while checking the historical predecessor prefix.

- 2026-10-13: the exact historical 29-method prefix passed 29/29, including one named-method pass, at diagnostic commit `05d9e194a` and daemon `eb3c757cac`. The XML preserves the recovered ordering. All eight pairing substages passed, with the addressed host registered, selected, authenticated and repository-present on return. The wrapper's expanded cancellation/deadline tests plus existing pairing tests passed 22/22. No demonstrated source defect or pairing red/green repair exists yet. Smaller comparisons used a diagnostic working tree; only the prefix was on a committed diagnostic checkpoint. Retain that provenance distinction.

- 2026-10-13: the three-method answer-host reuse sequence also passed 3/3 against the exact historical daemon `9c9cd5a25c337666bdbed47c29202e695a172733`, built by the standard gate from an unchanged detached temporary clone. Mobile production pairing, registry, lifecycle, network and activity sources are identical between recovered mobile `0c9e9a6f49` and the initial checkout `dc3ac93ab2`. Existing native setup and cleanup classes passed 5/5, including same-process targeted-host and empty-target navigation. This still supplies no pairing source defect; a matching historical-daemon prefix comparison is the remaining bounded diagnosis.

- 2026-10-13 final diagnosis: the historical-daemon 29-method prefix also passed 29/29, named question 1/1, zero failures/errors/skips. Six managed live diagnosis runs executed 67 methods total, all passed, with six named-question passes. This is no controlled reproduction and no source-defect repair. The missing evidence is a failing phone setup occurrence with its pairing substage and addressed-host state; the old per-test phone capture cannot be recovered. Preserve the diagnostic branch and private evidence, leave the source-cause question unresolved, and do not claim completion or post-verifier full live acceptance. Native setup/cleanup passed 5/5; diagnostic and existing pairing units passed 22/22; lint and final assemble/pre-verify passed. Documentation remains pending until a demonstrated repair and dispatcher full-live acceptance exist.
