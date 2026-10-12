# Attention peer daemon prerequisite (#2121)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_otherConversationAttentionPills_waitingAndFinished` fails at `peerStep("open attention peer")` before any attention assertion; keep the entire scenario unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open` uses an interactive-only reconnecting peer, exposing retained-snapshot delivery during handshake settlement.
- `scripts/e2e-emulator.sh`: `require_host_prompt_daemon` and the revision-reporting/startup boundary supply the existing prerequisite-check pattern.
- `scripts/test_e2e_emulator_gradle.py`: `HostPromptDaemonPrerequisiteTest` exercises the real shell guard with a private Git history.
- `docs/knowledge/features/thread-top-overlay.md`: aggregation and virtual-clock expiry are separate mechanisms already repaired by #1905 and #1735.
- `docs/e2e-interactive-stream.md`: “Other-conversation attention pills” and “Attention-pill first-Waiting isolation repair” establish the assertions to preserve.
- `docs/specs/architecture/1905-attention-pill-live-synchronization.md`: pairing isolation/restoration must remain intact.
- Sibling pyrycode `internal/relay/readiness_reconnect_capacity_test.go`, `v2session_hosted_capacity_test.go`, and `v2session_hosted.go`: #3214's permanent controlled regression and capacity repair, merged by PR #3232 at `47d00822c`.
- `/tmp/builder-2121/evidence.md` and digest-matched retained HP artifacts: historical failure/rerun and paired transport controls from the previous builder run.

## Context

Recovered HP evidence places the failure before B's turn: the attention peer cannot settle within 30 seconds after accepted handshakes. Mobile `187cd0e2139da0c510be4a08efe0c9561c550de9` used daemon `7a1daa57bae0c88a0c96b2b4e62c990fa76f47ca`, Claude 2.1.280. Full run: 65 executed, 27 passed, 38 failed, zero errors/skips; same-tree rerun: 38 executed, 11 passed, 27 failed, zero errors/skips, with this method passing. Original phone XML/logcat is missing; the precise historical close cause is unproved. A later `4410` record does not explain earlier attempts.

Identical controlled real-transport probes establish an owning daemon defect: 64 legal retained model snapshots with transport acknowledgement held overflow legacy reconciliation, closing with `4413` before snapshot zero. Merge-base `5d931a3b` fails both the permanent fence regression and the task-local wire probe; repair `8eabb607` passes both. #3214 is closed and its merged production source matches the tested repair. This is a demonstrated prerequisite, not proof of the lost historical phone close code.

One deliverable: admit this live scenario only with the shipped capacity repair, retain counted evidence, and establish fresh execution. Forecast under 400 written lines, zero production Android changes, zero new exported production types, one shell call site, three acceptance criteria, and at most four prerequisite reject branches. Overlapping #1766, #2088, #2090, #2095, #2104 and #2114 touch other harness blocks; keep this addition local. No dependency remains open. No decision record is needed.

## Design

Add `require_attention_peer_daemon` beside the existing host-prompt guard. For a full live run or a selected attention-pill method, require a versioned daemon whose revision descends from the #3214 merge. Validate the revision shape, require both commits in `PYRYCODE_SRC`, and use Git ancestry. Fail explicitly before daemon startup when unverifiable or old; never skip or retry the scenario. Unrelated focused selections and non-live runs do not acquire this prerequisite.

Build an isolated versioned daemon at the merged repair for fresh focused evidence. The repair and its mechanism regression stay in their owning repository. Retain content-free historical counts, hashes, controlled results and fresh method results under `scripts/e2e-fixtures/attention-peer-2121/`. Leave permission/navigation/completion/expiry assertions and Mobile product behavior unchanged.

## State and concurrency model

The shell guard is synchronous and read-only. No application coroutine, flow, pairing or permission lifecycle changes. Existing peer close and pairing restoration own cleanup. The controlled owner probes hold transport acknowledgement and verify ordered delivery/fence completion without Claude calls.

## State transitions and identity reuse

None: the admission check holds no lifecycle or identity state. A new binary revision is evaluated independently on every invocation. Existing live coverage continues to navigate A to B and Back with the same outstanding permission, resolve only through the peer, observe real completion and Finished, advance five virtual seconds, and prove no replay on reopening A.

## Error handling

An old, missing, malformed or unverifiable revision is an explicit environment prerequisite failure naming pyrycode #3214 and rebuild guidance. Do not print pairing outputs, credentials, host frames or message text. A failed live invocation remains failed and its fresh counted evidence is retained.

## Testing strategy

Write an admission regression first and watch the old harness accept a pre-repair daemon. Exercise the real guard against private Git histories: old/unrelated commits rejected; merge and descendants accepted; unknown/malformed revisions or missing source rejected; comma-separated selected method checked; unrelated subset/non-live runs unaffected; guard invoked before daemon startup. Re-run the existing host-prompt prerequisite tests for adjacency compatibility.

Reconfirm the existing identical pre/post #3214 transport controls and run its permanent regression at the merged revision. Run the unchanged named live method with the versioned isolated daemon and read fresh executed/passed/failed/error/skipped counts. Device execution is required because this existing scenario uses a real relay, daemon, peer and Claude. No second E2E suite or deterministic stream twin is introduced. Full passing live acceptance remains dispatcher-owned after verification; list `all` in the PR because this ticket explicitly requires the full live gate.

Run focused harness tests, lint, assembly, formatting and final assembly plus `scripts/pre-verify.py --gradle` after merging main. No Android-test source changes require an additional compile command beyond the final compile gate.

## Open Questions

- Resolved: the daemon repair has merged; proceed with its versioned isolated binary rather than waiting on a closed blocker.
- Historical phone closure attribution remains unavailable. Preserve that evidence limit and distinguish the controlled capacity defect from prior Waiting isolation and expiry defects.
- Fresh peer settlement and all scenario assertions must execute successfully; record the result under Revisions.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “Other-conversation attention pills” and the relevant “Verification status” entry with the established peer-open stage, evidence limits, #3214 controlled regression, admission coverage, tested Mobile/daemon revisions and final full-live executed/failed/error/skipped counts plus the named-method result. Keep the earlier expiry and host-isolation repairs distinct.

## Security review

**Verdict:** PASS

- [Trust boundaries] The guard accepts only a hex VCS revision and quotes all Git arguments; it does not parse daemon frames or pairing payloads.
- [Tokens] No token generation/storage changes. Test binaries and retained fixtures contain no login, pairing token, key or decrypted content; the existing live launcher owns credential loading.
- [Files and storage] Private scratch Git histories and versioned test binaries live under `/tmp/builder-2121/`; evidence fixtures contain only counts/static stages/hashes. No app storage, cache, backup or credential-bearing HOME changes.
- [Android attack surface] No manifest, component, intent, provider, push or rendering changes.
- [Cryptography] `NoiseIkSession` and vendored Noise remain unchanged; the guard never handles handshake keys/nonces.
- [Network and I/O] The check makes no network call and preserves TLS, frame limits and reconnect backoff. Test sockets and daemons are isolated; production daemons are never replaced.
- [Errors and logs] SHOULD FIX: retain only allowlisted metadata and count-derived XML; raw daemon/phone logs may contain content and remain private. Error messages use static prerequisite/rebuild wording.
- [Concurrency] No new jobs or mutable application state. Existing `withAttentionHostIsolation` finally restoration and peer close remain intact.
- [Threat model] A dropping/delaying relay still fails the existing bounded scenario; the guard cannot hide it. Rooted-device theft, hostile daemon parsing and UI-side exposure are unaffected because no production trust boundary changes.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

- 2026-10-12: fresh identical control probes reconfirmed the owning defect: `5d931a3b` executed 2/failed 2 with wire closure `4413` before snapshot 0/64; `8eabb607` executed 2/passed 2, zero errors/skips. The permanent regression also executed/passed 1/1 on merged `47d00822`; all repaired production files match that merge. A scratch invocation with zero selected tests was excluded from evidence.
- 2026-10-12: admission regression `test_full_live_rejects_pre_repair_daemon` executed 1/failed 1 before implementation because the old daemon was admitted. The repaired guard and existing host-prompt coverage executed/passed 12/12. The guard is local to the existing revision/startup boundary; no scenario/helper or product behavior changed.
- 2026-10-12: fresh baseline `build/dispatcher-tests/live-jmxknsdm/dispatcher.xml` and repaired `build/dispatcher-tests/live-ajyw3ysg/dispatcher.xml` each executed/passed the named live method 1/1 with zero failures/errors/skips and exit 0. Mobile HEAD `8200bfb47` (repaired run includes the prerequisite working-tree changes), daemon `47d00822cc702c896d6234b0447226096edab210`, Claude 2.1.280, `pixel2Api33Atd`. Retained count-derived evidence is under `scripts/e2e-fixtures/attention-peer-2121/`. Full dispatcher live acceptance remains pending, with `dispatcher-full-counted.xml` and final metadata to commit on the builder's return.
- 2026-10-12: both focused harness test files executed/passed 39/39 with loopback fixture access; lint and debug assembly passed. Retaining all historical case identities plus the reproducible wire probe expands final written work to approximately 720 lines, above the initial forecast but below every hard sizing boundary; zero Android production changes or exported production types, one added shell call site and four prerequisite reject branches.
