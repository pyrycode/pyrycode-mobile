# #2114 — background Agent later-reply diagnostics

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished`, `hostRepository`, `sendFromPhone`, and `awaitPingReplyNamingLayer`; localize the later send/wait, preserving all placement/navigation assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `recorded` and `linkState`; independent authenticated connection observations.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PingReplyDiagnosis.kt`: `PingReplyEvidence`; its completion-count shortcut cannot prove an exact answer.
- `app/src/test/java/de/pyryco/mobile/e2e/PingReplyDiagnosisTest.kt`: fixture patterns for pure diagnostic tests.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `Message` and `AssistantSegment`; main-lane identity and streaming completion.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `observeMessages`, `observeQueue`, and `observeTurnPhase`; read independently of composition.
- `docs/knowledge/features/development-verification.md` and `thread-screen-testing-background-agent-viewport.md`: counted evidence and unchanged placement/navigation coverage.
- `docs/e2e-interactive-stream.md`: rung vocabulary and the documented ping diagnosis limitation.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: authoritative interactive frames, user-message delivery and security model.
- `/tmp/builder-2114/evidence/diagnosis.json`: prior passing run lacks exact peer/repository/composed snapshots; separate clocks differ by approximately 1.64 seconds.

## Context

The historical timeout has no established owner. Add scenario-local diagnostics before considering repair; a passing rerun establishes only its own run. No production change or decision record is proposed.

Shared-file overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2099, #2100, #2102, #2104 and #2112. Inspected branch diffs edit other scenarios except #2112's earlier launch request; preserve it when merged. No required dependency.

## Design

Add test-only `LaterReplyDiagnosis.kt` under sharedTest and JVM regression tests under test. Five internal types: probe, peer reading, repository reading, composition reading, observation. No API migration. The probe holds a pre-send peer-frame count/identity baseline and repository-row baseline. Observations at baseline, after send, and success/failure are emitted immediately as content-free structured Logcat evidence before any subsequent scroll or cleanup.

Peer parsing groups main-lane deltas by turn and sequence, excludes baseline frames and turns, and compares their joined text exactly with the expected answer. Record correlated hashes of the delivered user message and answer turns, each turn's exact flag, length and completion. Include separately labelled host-envelope timestamps; never order these against the phone clock. User delivery and queue observations name the later prompt only by equality and hashed identity. No wire field joins user-message identity to answer turn: record that causal join as unknown rather than claiming it.

Read the owning host's current phone repository directly through the registry, without waiting for repository availability or requesting history. Bound each flow first-reading to one second, retaining null as unknown. Exact assistant rows must be new, main-lane and finished/streaming reported independently; retain hashed row/turn identity. Observe queue/phase independently so one unavailable flow does not erase another reading.

Composition reads exact text matches without scrolling, including global count, count under the thread list, displayed count and repository row's `IndexForKey` membership when available. Reads are one snapshot, not a new polling wait. Preserve partial readings if a semantics query fails. Composition synchronization uses the existing bounded Compose test rule; no new device worker or unowned coroutine.

The wrapper calls the unchanged phone send and original wait once. Failure capture cannot replace the original throwable or make a timeout successful. Local cleanup attempts both fixture releases, preference restoration and peer closure even when one fails; preserve the original scenario failure. Emit only static capture/cleanup error codes, never exception messages.

## State and concurrency model

The probe belongs to one synchronous instrumentation scenario; no background job or production state. Repository reads use cancellable `runBlocking` with `withTimeoutOrNull(1000)` per flow on the instrumentation thread. Snapshots can differ in time: record phone wall/monotonic sampling bounds and host timestamps independently. Unknown readings never become false facts.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Pre-send stale answer or late child answer | `baselineAndChildRepliesDoNotCount` |
| Main turn completes with different text | `completedDifferentAnswerIsNotExact` |
| Exact peer answer absent from repository | `peerAndRepositoryRemainIndependent` |
| Repository exact answer uncomposed or offscreen | `compositionAbsenceAndOffscreenStayDistinct` |
| Missing or malformed readings | `unavailableReadingsStayUnknown` |
| Capture and cleanup fail after timeout | `failureSurvivesCaptureAndCleanupErrors` |
| Successful wait then capture failure | `successfulWaitIsNotChangedByDiagnostics` |

No lifecycle state survives a scenario, reconnect, process death or identifier reuse. Baselines exclude earlier turn and row identities; the peer link reading exposes reconnect limitations without repairing them.

## Error handling

Each diagnostic read is isolated and unavailable observations remain unknown. Retain the exact original throwable. On successful scenarios cleanup failures still fail the scenario. Raw peer frames and repository content are compared only in memory and never serialized.

## Testing strategy

Write failing JVM tests first for the pure parser and wrapper; run `LaterReplyDiagnosisTest` and existing `PingReplyDiagnosisTest`. The device-only reason is real authenticated sockets, real Claude/daemon execution, IME send and lazy-list semantics on an actual device. Run one instrumented focused real-Claude background Agent method through the credential-owning launcher. Retain counted XML, content-free phone observations and private host history/transcript; correlate identities across clock domains. Existing rung-4 placement twin remains unchanged; diagnostics do not claim historical reproduction or causal repair. Run scoped lint/build/Android-test compilation and forced Spotless, then final `assembleDebug` and `pre-verify.py --gradle` after main merge. Full live gate is dispatcher-owned after a demonstrated repair and verifier pass.

## Open Questions

- Does the instrumented run demonstrate a localized harness cause? If not, retain and push diagnostics/evidence, then return the unresolved boundary for refinement; do not claim reliability repaired.

## Security review

**Verdict:** PASS

- Trust boundaries: peer payloads and daemon-authored text are untrusted; compare in memory only. Hash all identifiers; normalize timestamps by parsing them. Do not log raw payload fields or peer link error strings.
- Tokens/credentials: existing credential-owning launcher and peer retain ownership. No new generation, persistence or credential access.
- Files/storage: raw captures stay in private temporary/build directories, outside committed and APK assets. Published evidence contains counts, hashes, timestamps and static flags only; no daemon-authored content.
- Android attack surface: test sources only, no component, intent, provider or manifest changes.
- Cryptography: reuse SHA-256 for diagnostic identity labels; no Noise or key/nonce changes.
- Network/I/O: one-second repository reads; no history request, retries or new network endpoint. Existing frame limits remain. Relay delay/loss is recorded as unknown causality.
- Errors/logs: SHOULD FIX implemented by static unknown/capture-error labels; never serialize exception messages, arbitrary link descriptions, authored text or keys.
- Concurrency: synchronous scenario owner, bounded cancellable flow reads, no worker outliving cleanup. No production locks/state changes.
- Threat model: relay dropping/delaying frames remains possible; diagnostics cannot assert delivery from completion counts. Rooted-device token theft and accessibility/screenshot leakage retain existing storage/UI ownership; no new secret sink. Hostile/malformed frames yield unknown facts rather than logged content.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-12

## Revisions

- 2026-10-12: preserve the original send-to-wait deadline by subtracting after-send capture elapsed time from the existing wait budget. Record send-return separately; sender acknowledgement is unknown because the repository does not expose it. Baseline turn hashes and baseline exact-row hashes make exclusion reviewable. Malformed/conflicting/missing delta sequences cannot establish either an exact or completed-different answer.

- 2026-10-12: final negative-control review found malformed parent metadata was read as empty/main; `malformedParentCannotBeAssumedToBeMainLane` failed before the repair. Require the protocol's explicit parent/text/turn fields and mark the peer reading unknown on malformed deltas. Make row-derived repository fields nullable so an unavailable message flow retains independently available queue and phase readings (`unreadableRowsDoNotEraseObservableQueueAndPhase`). Split long content-free observations into numbered 3000-character Logcat chunks. These diagnostic-contract repairs do not establish the historical timeout's cause.


- 2026-10-12: read `currentRepository` afresh in every capture rather than retaining the pre-send repository object, so a connection replacement is sampled from the owning host's current repository. Registry lookup errors remain unknown and cannot prevent the send/wait.

## Focused capture and continuation

Instrumented `live-0dcir183` executed and passed the enabled, curated `InteractiveStreamE2ETest.interactiveTurn_backgroundAgent_followsBottomUntilFinished`: 1 executed, 1 passed, 0 failed/errors/skipped, exit 0. Mobile `fff043c1cf3d1adc6e6b43d0fa9b5951815bc8b3`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280, `pixel2Api33Atd`, real production relay. The gate waited 540 seconds for the shared device. The final malformed-field, partial-reading and Logcat-chunk refinements followed this capture; their JVM tests and Kotlin compilation were rerun, with no further live attempt.

| Clock domain | Reading |
| --- | --- |
| Phone wall, 2026-10-12 UTC | Baseline 04:48:44.786: 89 peer frames, 35 repository rows, zero exact rows/nodes, Idle. |
| Phone wall | Send returned 04:48:45.003; after-send snapshot held the new user row but no peer delivery/exact nodes. |
| Host envelope UTC | User delivery 04:48:48.077215; different 42-character main answer 04:48:48.077571, completed 04:48:48.078533. |
| Host envelope UTC | Exact nine-character main answer 04:48:49.129132, completed 04:48:49.129358. |
| Phone wall | Success sampling 04:48:48.101–04:48:48.168: peer and repository agree on the exact completed turn, repository Idle, one exact composed/list/displayed match at row index 0. |

Do not sort across these clocks. The delivered message SHA-256 is `9a04e49fd6115813f9eb18a69edb8819ae49eadd7ee1124de6d246426ee40ac2`; the exact peer/repository turn SHA-256 is `bbff21028f1c15d47ab1aaa8a6f13f25c37b62be38d9482bcb5637ff20185230`. Sender acknowledgement and the wire's user-message-to-turn causal join remain unknown. This run establishes delivery, repository folding and on-screen composition for itself only; there is no observed later timeout and no demonstrated historical harness cause.

Original phone/XML artifacts: `build/dispatcher-tests/live-0dcir183/dispatcher.xml`, `0-TEST-pixel2Api33Atd-_app-.xml` and the named-method Logcat file. Retained content-free observations, counted XML, source hashes and `diagnosis.json`: `/tmp/builder-2114/instrumented-evidence/`. Private startup-only host logs: `/tmp/builder-2114/instrumented-host/pyry-e2e.jYhWYp/daemon-answer.log`. The collector missed this run's independent durable history/Claude transcript because the launcher copied an older HOME directory mtime and deleted that HOME on success. Those admission/execution readings are unknown; authenticated peer envelopes provide the host emission readings above. Prior independent host history/transcript remain private under `/tmp/builder-2114/evidence/`, attributed only to `live-zihy6euu`.

The original Linux failing artifacts remain unavailable. Continuation needs that failed-run evidence or a failing instrumented scenario with retained host admission/execution/history/transcript (select the launcher-owned HOME by identity, not directory mtime). No unchanged rerun or reliability fix is claimed. Dispatcher-owned post-verifier full-live acceptance remains pending after a demonstrated causal repair. Final focused JVM tests: 14 diagnostic plus 14 existing ping tests executed/passed, zero failed/errors/skipped. No PR closes the ticket at this diagnostic checkpoint; retain the feature branch and return the unresolved cause for refinement.

## Collector continuation design (2026-10-12)

- Files read: `scripts/e2e-emulator.sh` (`start_answer_daemon`, `cleanup`, `TEST_TARGET`), `scripts/test_e2e_emulator_cleanup.py` (`EmulatorCleanupTest`), and the private timestamp collector. The launcher owns the exact answer HOME and instance; no discovery by timestamp is needed.
- Add `scripts/later-reply-host-evidence.py` and its focused unittest. Only the live background-Agent scenario invokes collection, synchronously after owned processes stop and before either answer HOME or work-directory removal. Preserve the original exit status even when capture fails; print only a static capture error. Credential cleanup is unchanged.
- Select the scenario conversation from the exact instance's registry by its `e2e1783-agent-` name prefix, requiring one match, then retain only its regular history segments and regular main Claude transcripts for sessions identified in that history/current binding. Do not collect other HOME directories, other instances/conversations/transcripts, registry contents, daemon logs, credentials or pairing/key files. Validate identifiers before using them as path components.
- Retain private evidence outside the checkout/APK in a fresh 0700 run directory with 0600 files and a manifest: launcher/answer instance, source HOME, selected conversation/session identities, launcher-start and capture interval in host clock, original status, source-relative paths, sizes and SHA-256. Raw authored bytes remain private. Read at most 16 MiB per regular file through no-follow directory/file descriptors; reject symlink components and non-regular leaves. No worker outlives cleanup.
- Deterministic tests: an older run-owned HOME beside an unrelated HOME must still be captured; leaf/ancestor symlinks, unrelated transcripts and credentials stay excluded; the actual EXIT cleanup retains evidence on both success and failure before deleting HOME; injected capture failure preserves both original statuses and credential cleanup. The old timestamp guard must fail the older-HOME test before removal.
- This adds no production/exported types or consumers and keeps the five acceptance criteria. Forecast: existing 783 written lines plus approximately 400 collector/test/hook/plan lines, below 1600. Script overlaps #2088/#2090 add generic capture; #2104 adds another scenario's log retention. These can coexist as local hooks and create no required dependency. #2112's earlier launch edit remains preserved when incorporated.
- Focused final-source live capture occurs once after collector tests pass and code is committed. Correlate exact answer and message/turn identities across independent evidence, never clock-order phone against host. If no later-wait cause appears, push this checkpoint and return the precise unresolved historical boundary for refinement without opening a closing PR.

### Collector security review

**Verdict: PASS.** Trust boundary is the launcher-owned HOME plus bounded registry/history parsing. MUST FIX incorporated into design: no-follow opens cover ancestors and leaves, identifiers are UUIDs, output is a fresh private directory outside the checkout, and the allowlist excludes credentials/stores. Tokens, Android components, cryptography, relay behavior and UI exposure are unchanged. Hashes use SHA-256. Static errors prevent exception/content logging; malformed or missing ownership fails capture without changing the run result. Synchronous post-stop capture avoids torn writes and any long-lived job. Rooted-device and relay threats retain the earlier review's boundaries; host evidence is private maintainer material, never shipped or published verbatim. Reviewer: builder self-review, 2026-10-12.

- 2026-10-12 collector ownership revision: `interactiveTurn_backgroundAgent_followsBottomUntilFinished` addresses `ARG_SERVER_ID` and `runningToolPeer`, while `answerChat` creates on its passed server id. This is the primary daemon, not `PYRY_NAME_ANSWER`; the historical private daemon snapshot likewise names the primary test instance. Capture must receive `DAEMON_HOME` and `PYRY_NAME` from the launcher and restrict reads to that exact instance/conversation/session. The real HOME can contain other runs; only this instance's registry/history and its exact session transcript filenames are eligible. The separate credential-bearing answer HOME still undergoes unchanged cleanup. This corrects evidence ownership, not the later-timeout cause. Security PASS retained: no broader file allowlist or credential access.

## Final-source collector capture and blocker (2026-10-12)

The final diagnostic/collector source at Mobile `03fd4c6d8c6f95fc22ea9c4fed4a57a4bb004846` ran once through the credential-owning launcher: `live-8co9q154`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280, real production relay, `pixel2Api33Atd`. Named method `InteractiveStreamE2ETest.interactiveTurn_backgroundAgent_followsBottomUntilFinished`: **1 executed, 0 passed, 1 failed, 0 errors/skipped**, exit 1. It failed at the earlier `allowPromptsUntil` Agent-start wait (90000 ms, zero permissions allowed, peer open on link 2 after one replacement). The later send, baseline, repository, exact composition/list/displayed readings and navigation were not reached. Teardown focus establishes no cause.

Collection succeeded before the launcher's cleanup completed. Private manifest: `/tmp/builder-2114/final-host/e2e-auto-325af148-ci80hgfh/manifest.json`; retained regular conversation history and main Claude transcript sit beside it under `history/` and `transcripts/`. The owning instance is the primary `e2e-auto-325af148`, independently selected by the launcher's exact `DAEMON_HOME`/`PYRY_NAME`, not the isolated answer daemon. The old collector searched scratch HOME directories; the real owning HOME was never an eligible candidate. Its reported answer-HOME age alone did not explain the whole capture gap. No credentials, registry copy, pairing store or symlink target was retained.

Content-free identity correlation: conversation SHA-256 `d49bb1726c9cd2fc4e639bf89cb68d36eee10888bad37281a4195fd39aa920b0`; session `b890627dbcc71fd1518adb9f84ac97c519b746e65b01665b35cb8b37e400360a`; launch user message `9c1651547b83f4edaeec321c115dc943e49c5901cb5e21a32e41cabc8e6b0117`; main turn `476ff02b20f115498e30d2200bcdc66400421dd37e28f4eb400d4e530f8ed4e8`.

| Domain | Evidence |
| --- | --- |
| Phone logcat wall | Local `thread_send_accepted` at 08:15:53.232; transport teardown at 08:16:43.203. No later-reply diagnostic record. Do not order these against host timestamps. |
| Host UTC | Launcher interval starts 05:15:31.538574; capture reads 05:17:26.488960–05:17:29.294992 after owned processes stop, before credential cleanup. Original exit 1 retained. |
| Host history | Admission entry 1 at 05:15:54.173390; delivered message entry 7 at 05:15:56.618797; `send_delivered` entry 8 explicitly joins accepted entry 1 and delivery entry 7. Prompt length 728. |
| Claude transcript | Exact launch prompt at 05:15:55.202; ordinary 445-character response at 05:16:02.230; zero tool calls. Its inability marker is present; no injection classification is inferred. |
| Host emission | Main turn opening at 05:15:57.101251; four main deltas at 05:16:01.574467–05:16:02.291588, completion at 05:16:02.299054. Joined deltas exactly equal transcript response, hash `d7c94fc94e4dad4aa9b6d6344427c500646d66b14c7a55d87e53b86b8fa2cd08`. Zero persisted tool-use or background-task-start events. |
| Unsupported joins | Sender acknowledgement and user-message-to-main-turn causal join remain unknown. Later admission, execution, peer/repository folding and composition are unobserved because the later prompt was never sent. |

Artifacts and SHA-256:

- `build/dispatcher-tests/live-8co9q154/dispatcher.xml`: `edc66c65440eb6195101ba4301ef3f679c4d1e56cb7bf8e75b35aa94d31c509f`.
- Named-method device XML `0-TEST-pixel2Api33Atd-_app-.xml` in that directory: `37b3a78e5952e9b1dc1268daa40800a00372638a545b9726102d59b5b397d412`.
- Named-method phone Logcat in that directory: `0536c617df2f6c508a239b662b82243f269065914c269f53bec8e987af25c38b`.
- Private host history: `185ce785cb19bbf27c4152391534a7afc140de420c783745bc527c72f8907a8b`; private transcript: `dd2760c16313401d26d7f9ba1ba1271400b7baf185ad36c562518bfa7e37a249`; private manifest: `91135874aa707a4c6caf8e0886876748e50e725d1781b0ea8178043393c9b7c1`.
- Counted XML copies, source hashes, content-free phone events and `diagnosis.json` at `/tmp/builder-2114/final-evidence/`; diagnosis hash `cc82201211be7b748ce7f8ccd22981b77240fb3d9e6dd9030ee60da2cd5ba1a3`. Raw authored content remains private and uncommitted.

Focused checks: collector unittest **4 executed/passed** (including both original statuses and capture failures); existing launcher unittest **32 executed/passed**, cleanup unittest **1 executed/passed**. Fresh JVM diagnostic classes **14 + 14 executed/passed**, zero failures/errors/skips, selected task forced with `--rerun`. Lint, debug assembly, Android-test compilation, forced Spotless and `pre-verify.py --gradle --body-file /tmp/builder-2114/pr.md` passed.

Open #2112 / PR #2123 owns the earlier no-Agent-start failure and its launch-request repair; #2114 is now natively blocked by #2112, confirmed through the pipeline relations helper. Preserve that earlier repair when merged, then obtain the required focused later-reply capture; do not retry the unchanged request or duplicate its repair here. No later-wait harness cause was demonstrated and the historical Linux failure remains unavailable. Dispatcher-owned post-verifier full-live acceptance is still pending after a demonstrated later-wait repair. No closing PR is opened for this blocked diagnostic checkpoint.
