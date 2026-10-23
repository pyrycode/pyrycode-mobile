# Development verification — durable-gap receipt evidence

Part of [Emulator and real evidence](development-verification-emulator-evidence.md#exact-durable-gap-recovery-receipts-2515).

### Durable-gap ordering and counted Linux evidence (#2515)

The [plan's retained ordering](../../specs/architecture/2515-exact-recovery-query-receipts.md#revisions)
and [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2520#issuecomment-6373884811)
separate three native failures. Each original `scripted ping` pair executed 2,
passed 1 and failed durable-gap, with 0 errors/skips; ordinary ping passed:

| Capture | Observed boundary |
| --- | --- |
| `diagnostic-red` | Ordinal 1 certified version 495 at 17:05:57.375 UTC; ordinal 2 was accepted at 17:05:57.386 after the initial readiness poll. The separately read request list failed the saved-tail assertion and `end` stopped observation. Production processed the follow-up terminal at 17:05:57.492–57.653. |
| `production-boundary-red`, Mobile `3c86f06b5e74bd068c4814a2572aa199bd8c0382` | Passive certification at 17:25:59.493 preceded production completion at 17:25:59.606; the content snapshot still held checkpoint 2. Exact receipts alone did not satisfy the unchanged source-certification assertion. |
| `render-red`, Mobile `4a99f8ef5597315922196a7009edc4a45e823d5f` | Admissions at 17:40:18.821 and .866 saw checkpoint 495, but the LazyColumn still held one cached row; production completion followed at .910. No persistent rendering defect was established. |

These captures were retained under
`$AGENTS_REPO_PATH/logs/evidence/2515/builder-linux-20261023/`, with their own
source/composition, identity and cleanup records. The diagnostic harness was
`logs/evidence/2515/harness/fault-85000f3ef1e53f1f2890ddc5b5fdebf0/`.
No early send-return delivery was observed there; its controlled negative test
remains necessary because the transport supports that ordering.

The fresh rework reports under
`$AGENTS_REPO_PATH/logs/evidence/2515/rework-linux-20261023/` were read as raw
JUnit XML during documentation. All rows below have zero errors/skips:

| Focused run | Executed | Passed | Failed |
| --- | ---: | ---: | ---: |
| `accounting-red`: three advancing-version/reset admission probes before repair | 16 | 13 | 3 |
| `owned-reply-red`: follow-up completion before repair | 42 | 41 | 1 |
| `accounting-green`: `RecoveryQueryAccountingTest` | 16 | 16 | 0 |
| `owned-reply-green`: `OwnedReplyObservationTest` | 42 | 42 | 0 |
| `thread-evidence-green`: `ThreadEvidenceTest` | 17 | 17 | 0 |
| `native-green`: original Linux managed `scripted ping` pair | 2 | 2 | 0 |

The accounting class explicitly passed `laterAcceptedQueryNeedsItsOwnTerminal`,
`oneSuccessCannotCompleteAnotherRequest`, `earlyDeliveryWaitsForItsOwnAcceptance`,
`incompleteWorkCannotBorrowCompletion`, `retirementRetainsAcceptedAccounting`,
`endingObservationRetainsAcceptanceReturningAfterEnd`,
`resetKeepsOldRequestsAndRequiresReplacementCertification`,
`replacementEpochCertificationBelongsToItsNewRequest`,
`wrongEpochCannotSupplyAnotherRequestsMissingReceipt`, `staleSourceCannotSupplyReceipt`,
`staleGenerationCannotRetainEarlierReadiness`,
`coherentAdmissionRejectsAQueryAcceptedAfterContentRead`,
`passiveTerminalWaitsForProductionCertification`,
`validCapturedTerminalSurvivesNewerLiveFactsAndFollowUp`,
`replacementEpochAdmissionIgnoresRetiredCertificateVersion` and
`reusedEpochAdmissionIgnoresRetiredGenerationVersion`. The owned-reply class passed
`capturedTerminalOvertakenByLiveReplyRetainsReceiptForFollowUp`. These controlled
tests force the later-query/advancing/reset orderings; the fresh native capture
accepted one recovery query and certified it before both admissions at checkpoint
and completed version 495.

The successful native command was `python3 scripts/android-test-gate.py scripted ping`
on Linux managed `pixel2Api33Atd`. Raw `native/dispatcher.xml` confirms both
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_durableGapCatchUp`
and `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` passed.
It composed Mobile `e58581b368ea4ec57a41dc9d1b741691f4192829` with the app-only patch
from #2513 producer `c2a165f10af099c73d17b9ad253d5a0fb2034792`; all five composed
files matched that candidate byte for byte and were excluded from this repair.
`native-green/composition.patch` and `identities-cleanup.json` retain the exact diff,
file hashes and source/build attribution. Effective clean Core source was
`cb556b7697fa6f10685a630fb6a73139ed82cf9c`; the freshly built daemon lacks an embedded
VCS stamp, so attribution relies on retained build/source metadata plus its hash.
Relay reports clean revision `2fd9338dd1662dbd68f3710a33e6a8cc4e7a0bc1`.

| Effective artifact | SHA-256 |
| --- | --- |
| Daemon | `e165615b3f437c2110962099ba9370d7a6b572429d9ae33824d2a7a85fce9992` |
| Relay | `79c5e14b4b52c10c6b5523ca56f8d3354b2d6bbcb2672be68588f199a1b61973` |
| App APK | `8a511ddfdba2c07281a4dced39a2452458dfbef9a55f065bf7f8b8c526dacd30` |
| Test APK | `662d6846081f355beb2be4c60dd19f51608ac8d46d4c07486819ecccb0e80e92` |

`native-green/` retained raw XML, full phone logs, phases, verified manifests and
completed custody cleanup. Matching daemon/control/relay evidence is
`logs/evidence/2515/harness/fault-0722d3a63f053046c40ad52fb943e729/`, with harness
exit 0; copied harness inputs record retention complete. These are private paths
at review time, not guaranteed permanent storage or removed gate-worktree paths.

### Configured acceptance and historical failures (#2515)

The focused composition does not replace configured acceptance. The accepted
verifier stage `de25f45050169b8363825bc41e4b243c27b4e3c5851b332e786e85d2abce45ed`
reviews repair `e58581b368ea4ec57a41dc9d1b741691f4192829`. Its counted XML is retained
in agents `logs/verifier-gate_#2515_{4,5,6}.log`; documentation read those reports.
All following runs have zero errors/skips:

| Configured run | Executed | Passed | Failed |
| --- | ---: | ---: | ---: |
| `python3 scripts/jvm-test-gate.py --with-build-checks` | 5449 | 5449 | 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` | 249 | 248 | 1 |
| Exact UI-method rerun, as recorded by verifier | 1 | 1 | 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all` | 24 | 19 | 5 |

Gate-worktree `build/dispatcher-tests/` paths in the review describe original
execution locations, not surviving raw captures. The retained agents logs above
preserve counted XML; focused phone/harness capture locations are listed separately.

The JVM report includes the named receipt/admission probes above as passed.
The UI failure was
`SavedThreadFirstDrawDeviceTest.authoritativeFileCompatibility_preservesExactFacts_offlineAndHeldNewest_firstOpenAndReopen`;
its exact rerun passed and remains tracked as flaky on #2508. All five scripted
failures repeated in the exact selection and on main
`9a22b5ae0d1fe7bc585d6941187dbbb81af6cb09`, accepted by the dispatcher/verifier as
inherited: `interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog` (#2353),
`interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`,
`interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` and
`interactiveTurn_seededChannel_durableGapCatchUp` (#2521), and
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` (#2469).
They remain failed executions, including both configured ping methods. The accepted
comparison makes no claim that the configured suite passed every test.

Keep #2513's earlier failures separate: producer candidate `905ef6338` failed
producer 4414 ownership and its subsequent ordinary ping failed reply rendering;
candidate `c2a165f10af099c73d17b9ad253d5a0fb2034792` with Core
`5ba6b8dacc0916533df8b8d9c60090190a9e6f4d` and the same relay executed 2, passed 1,
failed durable-gap 1, with zero errors/skips. Its final 2-accepted/1-receipt trace
cannot retrospectively establish an exact missing wire ID. Original evidence was
retained under agents `logs/evidence/2513/builder-linux-20261023/native-replacement/`
and `logs/evidence/2513/harness/fault-ba40fb0e28ccc21b05a38e20d825625f/`, with adjacent
identity/cleanup records and uploaded run
`3c85b8f4dc5117c9f8eabcd11c2cb287c89877cadc759751fad391bfc880d47b`.
Neither the composed passes nor inherited-failure comparisons reclassify those
captures. Linux is the verification OS; other systems are outside scope and zero
real-Claude turns were required. Producer migration (#2513), failure restoration
(#2514) and ordinary-ping final-read work (#2494) retain their own scopes.
