# #2137 — Generation-scoped e2e daemon readiness

## Files read

- `scripts/e2e-daemon-fault.py`: `start`, `await_relay_ready`, `stop`, and `Handler.do_POST` currently verify registration only on a new child.
- `scripts/test_e2e_daemon_fault.py`: `DaemonFaultTest` provides the owned-process HTTP fixture; extend it with generation lifecycle probes.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DaemonFaultControl.kt`: `request` discards HTTP phase evidence; its socket timeout coincides with the registration timeout.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/DaemonFaultEvidence.kt` and `app/src/test/java/de/pyryco/mobile/e2e/DaemonFaultEvidenceTest.kt`: share the bounded socket exchange with the device consumer so JVM coverage can drive the real controller and evidence wrapper.
- `InteractiveStreamE2ETest.kt`: `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies` preserves the outage, null repository, visible pill, deadline, identity and new reply checks.
- `DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_offlineRetryRestoresScriptedReply` is the deterministic recovery proof.
- `scripts/e2e-emulator.sh`: `cleanup` deletes successful daemon/controller captures; retain an allowlisted private capture before disposal when explicitly configured.
- `docs/knowledge/features/relay-reconnect-supervisor.md`: actual capped-backoff observation and the producer-anchored deadline must remain intact.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: raw XML/logcat and correlated daemon evidence need durable private storage and a verified manifest.
- `docs/e2e-interactive-stream.md`: “Offline Retry proof” owns the unchanged timing contract and rung vocabulary.
- Sibling `pyrycode/docs/protocol-mobile.md`: “Security model” owns the relay/Noise threat contract; this repair changes no frames.

## Context

The concrete controller bug is independently reproducible: a readiness timeout leaves a living child, and the next start succeeds without registration. Initial startup also leaves an unverified living child. Historical branch recovery timeouts and the base start failure identify different boundaries; they do not establish a common cause. The original dispatcher reports and newest raw captures are unavailable on this Mac. Fresh comparisons must keep that uncertainty explicit. No decision record is needed.

One deliverable, five acceptance criteria, approximately 750 written lines, no exported production types and no production API migration. Overlaps: #2052 adds process-descriptor ownership/termination and provenance controls; #2091 (PR #2107) adds scenario setup and phase/restoration diagnostics. These are compatible local changes. Preserve them when main integrates them; do not duplicate #2107's scenario diagnostics.

## Design

Keep the single owned `Popen` in the controller. Record a monotonically increasing generation and its log byte offset before each spawn. Readiness belongs to that living generation only. Every start checks registration, including the initially living child and a child retained after a timeout. A registered living child remains idempotent. No-token mode succeeds on process liveness alone. Stop and observed exit invalidate readiness. The timeout remains 15 seconds by default; a bounded explicit timeout option lets portable regressions execute quickly.

Scan the current generation's log from its boundary incrementally in bounded chunks, preserving token overlap across chunks. Never print log contents. HTTP outcomes expose only fixed action/status, generation, readiness/phase and elapsed milliseconds. Controller transition records use the same static vocabulary. Client diagnostics read bounded HTTP/JSON evidence, allowlist phase fields, log no response/error text, and rethrow original transport exceptions without replacement. HTTP failures carry the safe controller evidence.

An opt-in private evidence destination in the existing emulator harness retains only daemon/controller/relay logs and static input revision/binary hashes before cleanup, with owner-only permissions. Pairing files, homes and transcripts are excluded. Unit tests exercise retention without a daemon or credential. XML and phone logcat are copied from fresh gate artifacts into the persistent agents evidence directory with a verified SHA-256 manifest. Reviewed counted summaries may be committed under test resources; raw captures stay private.

## State and concurrency model

`HTTPServer` serializes all requests and owns one child. No threads, coroutines, locks or app flows are added. Startup spawns once and publishes the control port without claiming readiness. Stop terminates and joins only its owned process. A readiness timeout retains that process for later verification rather than spawning a second child. Client socket work keeps its existing instrumentation ownership and timeout; no scenario recovery deadline is restarted or extended.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Initial living child has no token | `test_initial_unready_child_cannot_succeed` |
| Stop, restart times out, repeated start | `test_timeout_then_start_remains_unready_without_duplicate_child` |
| Token arrives after timeout in same generation | `test_late_readiness_qualifies_only_the_same_living_generation` |
| Registered child receives repeated starts | `test_ready_start_is_idempotent` |
| Registered generation stops and successor has no token | `test_stopped_generation_token_cannot_qualify_successor` |
| Registered generation exits and is replaced | `test_exited_generation_token_cannot_qualify_successor` |
| Child exits before readiness | `test_early_exit_reports_exit` |
| New generation cannot spawn after its predecessor stops | `test_spawn_failure_is_not_an_exit_of_the_new_generation`, `DaemonFaultEvidenceTest.spawnFailureKeepsItsDistinctPhaseWithoutPublishingErrorText` |
| Child exits between registration observation and response | `test_success_snapshot_cannot_report_an_exited_or_unready_child` |
| No-token mode and unrelated living process | `test_no_token_mode_preserves_owned_process_only` |
| Registration token spans a scan boundary | `test_registration_token_across_chunks` |
| Controller HTTP failure vs client I/O exception | Client evidence tests and existing restoration diagnostics when #2107 lands |
| Default registration timeout reaches the client after dispatch lag | `DaemonFaultEvidenceTest.defaultRegistrationTimeoutReachesTheClientOverTheRealSocket` |
| Retry window is observed late or expires | Existing `OfflineRetryWindowTest` and passive-redial `RelayConnectionSupervisorTest` coverage |
| Real outage, restore, exact-host Retry and new reply | Focused scripted `offline-retry`; historical focused live comparisons; dispatcher final full-live gate |

## Error handling

Use fixed spawn-failed, registration-timeout, child-exited, stop-timeout and control-failure codes. A generation whose spawn fails has no child; refresh cannot attribute its predecessor's exit to it. Failed transitions return HTTP 503 with safe generation/phase evidence. Unknown controls stay 404. Arbitrary request paths, exception messages and daemon log bytes never enter diagnostics. Original client exceptions remain the same throwable, so #2107's restoration helper can retain its primary and suppressed failure evidence.

## Testing strategy

Write portable synthetic-process probes first and observe the existing false success. Run `python3 -m unittest discover -s scripts -p test_e2e_daemon_fault.py` and focused evidence/parser tests. Device-only source is necessary because the client controls a real host-loopback daemon over a device socket; pure parsing coverage belongs in JVM tests/shared source where practical.

Run the existing deadline/passive-recovery unit coverage, lint, debug assembly, Android-test compilation, Spotless apply and forced check. Run the focused scripted `offline-retry` through the normal gate and inspect actual XML counts. Historical branch/base comparisons use separate temporary checkouts, pinned test-only daemon source/binary, builder-only gate authentication and the named live method; retain phase diagnostics before disposal. A changed daemon revision or passing rerun does not prove the historical cause. If an evidenced required repair belongs elsewhere, link its owner issue rather than broadening this ticket.

Push the coherent candidate before final verification. Merge current main, preserve #2107 if it has landed, then run `assembleDebug` and `scripts/pre-verify.py --gradle` with the concrete PR body. Dispatcher owns full `scripted-all` and final full-live acceptance, including the exact named method and suite counts; pending dispatcher execution is explicit handoff.

## Open Questions

- Resolved: the controller's false success is reproduced on the recorded base using an owned unready child. Both historical live revisions pass fresh focused comparisons with pinned daemon source `e810c8a85064d2cd290285095fc16cd8941f3236` and identical binary SHA-256. Original dispatcher reports recovered on HP confirm the distinct start/recovery failures; their missing original controller/daemon captures leave those failures unattributed; no additional causal native blocker was established.
- Resolved: #2107 is still open at `24836461b40c4c2ba6259dc12a66da0c16cad8ec` and absent from the final merged main `b107f97a7`. This repair remains local and compatible with its scenario diagnostics/restoration helper; preserve that work when main integrates it.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Offline Retry proof” and “Verification status”, with generation-scoped registration readiness, established cause and historical limits, durable evidence locations, final scripted/full-live revisions and counts. Preserve the producer-anchored deadline contract. Documentation consumes builder/dispatcher evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] The test-only controller accepts fixed controls; `Handler.do_POST` exposes only allowlisted generation/readiness evidence. Client parsing bounds response bytes and accepts fixed fields/phases. Daemon text is scanned as bytes solely for the configured token and is never echoed.
- [Tokens and credentials] No new credentials, storage or authentication lookup. Never copy homes, pairing output, tokens or login files into evidence. Existing builder-only live launcher owns authentication.
- [Files and storage] SHOULD FIX: successful cleanup currently loses raw evidence. The explicit evidence destination must use private directories/files, a narrow file allowlist and verified hashes; raw evidence remains outside Git.
- [Android attack surface] Only test APK code changes; exported components, intents, providers and production network endpoints do not change.
- [Cryptography] Reuse existing Noise IK and Keystore behavior; no key, nonce, token comparison or wire change.
- [Network and I/O] Bound log-scan memory and response parsing. Registration waits remain bounded at 15 seconds; start responses have a separate 17-second socket read budget for dispatch and delivery. Retain the original producer deadline. HTTP control remains the existing test-only loopback endpoint.
- [Errors/logs] MUST FIX addressed in design: never include arbitrary HTTP/error/daemon text in published diagnostics. Record only static action, numeric HTTP status/generation/timing and fixed phases; preserve original exception privately.
- [Concurrency] Serialized HTTP ownership prevents duplicate live children. Readiness invalidates on stop/exit and offsets prevent stale-generation reuse. Tests drive repeat/exit/restart paths.
- [Threat model] A malicious relay may deny/delay registration; this fails boundedly without weakening Noise or Retry assertions. Rooted-device token theft, hostile application frames and UI-side leakage retain existing protocol mitigations; this test-harness change adds no production surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-13

## Revisions

- 2026-10-13: Re-review of `0ad4d31b4` found that the client's 15-second read timeout races the controller's 15-second registration wait, hiding HTTP 503/generation/registration-timeout evidence as a transport failure. Keep registration at 15 seconds, cap each polling sleep to its remaining wait, and give only `start` a separate 17-second read budget for dispatch and response delivery. Move the existing bounded socket exchange into shared test source and exercise it through `checkedDaemonFaultRequest` against the actual default-configured Python controller and an owned live-but-unready child, with a deterministic 250-ms dispatch delay. Require safe HTTP 503/generation/readiness-timeout evidence and matching controller records; first observe the old 15-second client fail. Existing connect/other-action timeouts, original throwable propagation and the producer-anchored 20-second scenario deadline remain intact; registration, socket delivery, and later recovery consume that existing deadline. Overlaps #2052 and #2091 remain local and independent. This rework adds approximately 130 written lines, no production types or API migration, staying below the ticket limits. Security re-review: the response remains bounded to 4096 characters and uses the existing static evidence allowlist; the test child reads no credentials and cleanup joins only its owned process.

- 2026-10-13: The first passing scripted capture showed that existing phone logs omit the actual capped-backoff observation. Add an optional callback to `OfflineRetryWindow.awaitDeadline` carrying the exact producer snapshot, tested by `immediateDaemonAbsentDials_doNotRequireEveryConnectingEmission`. The device client emits only attempt/observer lag and remaining original budget; deadlines and scenario assertions stay unchanged. This complements #2107's scenario diagnostics without reproducing them.
- 2026-10-13: The gate-built daemon omits VCS metadata. Record configured source revision separately from binary-reported revision and retain its SHA-256 digest. Successful historical cleanup deleted the first base daemon capture; subsequent comparisons retain open handles before disposal. Managed feature-branch harnesses now default to private per-ticket retention, with an explicit destination override for detached comparisons, so the dispatcher full gate preserves boundary logs too.
- 2026-10-13: Use one refreshed snapshot for the start response and matching transition record. If exit is observed after readiness, return 503 rather than a success payload whose running/readiness fields are false. The interrupted worker's tested candidate at `72311218f35c62db6037a7539d61db431cd89049` passes focused scripted and live scenarios, each 1 executed/passed with zero failures/skips. Recovered private XML/logcat, controller/daemon logs, input hashes and verified manifests are under `$AGENTS_REPO_PATH/logs/evidence/2137/recovered-mac-20261013/`; reviewed counted outcomes are under `app/src/test/resources/e2e/offline-retry-2137/`. Full scripted/full-live acceptance remains dispatcher-owned.

- 2026-10-13: Resume the interrupted worker from the verified operator handoff archive at commit `72311218f35c62db6037a7539d61db431cd89049`, preserving its unfinished patch and reviewed summaries. Restore all 56 raw evidence files privately with a new verified manifest; portable controller/retention probes and focused JVM coverage pass on HP. The recovered retention change makes every newly created evidence ancestor owner-only. Main remains `b107f97a7`; additional harness overlaps #1766, #2088, #2090, #2097, #2104, #2114 and #2121 leave this local cleanup addition independent.

- 2026-10-13: Fresh HP scripted and focused live checks on `97e51941c44af3a2d364075d0bbca853fc94ae5a`, with main `b107f97a7` merged, each execute/pass 1 test with zero failures/errors/skips and successful cleanup. Daemon source/reported revision `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f`, binary SHA-256 `8e94ff43007ee7637ba04730f350bf9ec014b0525605fc800cbe1f21be1542e0`. Resumed private captures and verified manifests are under `$AGENTS_REPO_PATH/logs/evidence/2137/resumed-hp-20261013/`. Original HP reports are recovered and confirm the previously reported counts/phases; all their temporary capture paths are gone. The original full gate reports daemon `126d7ca662a38fdd399cacd2ea09bcb12304c892`, while its rerun/base report `e810c8a85064d2cd290285095fc16cd8941f3236`; these version differences and new passes establish no historical cause. PR #2107 remains open. Dispatcher owns final configured scripted-all/full-live acceptance; documentation remains pending.

- 2026-10-13: Rework findings 1–4 require managed default retention only after `REPO_ROOT` exists, with extracted cleanup preserving exit status and owned-process disposal under nounset. Exercise both real retention modes under nounset with managed environment enabled. Detach the predecessor before spawn and retain `spawn_failed` in controller/client evidence. Freeze the launch executable resolved through PATH and hash that selection. Replace the short-lived exit probe with a trace at the registered response snapshot, joining the owned child before refresh; require readiness observation, HTTP 503 and identical transition evidence, including a negative control with the final-snapshot guard removed. These local changes remain compatible with #2052 ownership changes and #2107 scenario diagnostics; no Retry deadline or assertion changes.

- 2026-10-13: Rework candidate `2c249ab1cea9321d7bc0543a1a24f3867a0f7843`, with main `dc3ac93ab2f42dce9056b328ce05e39b935b6202` merged, passes 111 script tests, the 26-test focused Python selection and 46 focused JVM tests. Fresh scripted Offline Retry and the named focused live method each execute/pass 1 test, with zero failures/errors/skips and gate exit 0; daemon source/reported revision and binary digest remain `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f` / `8e94ff43007ee7637ba04730f350bf9ec014b0525605fc800cbe1f21be1542e0`. Generation 2 registration takes 77/101 ms; capped attempt 6 is observed with 13/10 ms lag and 19403/19435 ms of the original budget remaining. Reviewed summaries sit under `app/src/test/resources/e2e/offline-retry-2137/`; private raw captures and verified manifest are under `$AGENTS_REPO_PATH/logs/evidence/2137/rework-1b353f-hp-20261013/`. Historical causation stays unresolved. PR #2107 remains open; final full scripted/live gates and documentation stay with the dispatcher/documentation stage.

- 2026-10-13: Timeout repair `9c89eccfd99faf7d492c3ddc16b1f73a0325bcf5`, with main `dc3ac93ab2f42dce9056b328ce05e39b935b6202` merged, passes 19 controller and 47 focused JVM tests after the actual socket regression first failed with `SocketTimeoutException` (1 executed/failed, no skips). Fresh scripted Offline Retry and the named focused live method each execute/pass 1 test, with zero failures/errors/skips and gate exit 0. Daemon source/reported revision and digest remain `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f` / `8e94ff43007ee7637ba04730f350bf9ec014b0525605fc800cbe1f21be1542e0`. Generation-2 registration takes 50/101 ms; capped attempt 6 has 0/1-ms observer lag and 19444/19413 ms remaining on the original deadline. Private captures under `$AGENTS_REPO_PATH/logs/evidence/2137/rework-timeout-hp-20261013/` retain 20 raw/input files with 36 verified manifest entries. Reviewed summaries are under `app/src/test/resources/e2e/offline-retry-2137/`. PR #2107 remains open; historical causation remains unresolved. Final full scripted/live gates and documentation remain dispatcher/documentation handoffs.
