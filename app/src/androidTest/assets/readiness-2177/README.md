# Saved first-draw readiness investigation (#2177)

This is development evidence from Linux/build-fractal-2, not a completed
dispatcher UI or scripted-all gate. Raw phone logs remain private. The exact
test/helper and four primary production measurement files have identical hashes
between both fresh comparisons. No Mobile production or test logic changed.

## Cause and repair boundary

`unchanged-baseline.json` preserves the original 4 executed / 1 passed / 3 failed /
0 skipped run: responsive busy windows exhausted readiness before any Probe.
Contemporaneous host evidence demonstrated approximately 95.7% busy CPU and a
compiler consuming approximately six of eight cores, predominantly in GC; a guest
snapshot showed substantial host/steal time. This establishes external resource
pressure, not a held sampler, a counter-parser defect, or the compiler's initiating
ownership/defect. The original 13-file private manifest was reverified on resumption.

[Runner repair #2178](https://github.com/pyrycode/pyrycode-mobile/issues/2178) is
closed after [agents PR #186](https://github.com/pyrycode/pyrycode-mobile-agents/pull/186).
The supported focused gate now owns preparation and execution under shared
admission and runs Kotlin in its owned Gradle process. The owner reported 83
passing controlled runner regressions and removal of the old compiler process.
Those are external-owner results; this ticket supplies the fresh measurement
comparison after deployment at agents `800e719515a91bf2040d9d26b0e0a802e931bca3`.

## Fresh unchanged-versus-current comparison

Both runs used `python3 scripts/android-test-gate.py ui --tests
de.pyryco.mobile.ui.conversations.thread.SavedThreadFirstDrawDeviceTest` through
the sampling wrapper retained with the raw evidence. Each executed all four
methods once: **4 executed, 4 passed, 0 failed/errors, 0 skipped; exit 0**.
The exact baseline is `4b21cef23ef9d395f667694ab2a6df97c380deaf`; current source is
`e8a4508f23f1b99e0c22494656fad1fe4a9ecf7d`. Current includes unrelated main changes;
`comparison.json` records eight matching source hashes instead of claiming the
entire trees are identical.

Each run reached all 17 readiness barriers and installed all 17 Probes (eight
ordinary-projection openings, eight held-projection openings, one slow control).
The pipe control independently passed bounded cancellation and descriptor closure.
Baseline ordinary/twin readiness windows were 87–96% / 83–98% idle; current
ordinary/twin windows were 64–98% / 84–96%. Busy windows still reset the streak.
The slow control required 78/84 windows, including busy startup windows, before
the same four consecutive idle windows succeeded. Maximum acquisition/read times
were 9/32 ms baseline and 24/32 ms current; no sampling failure occurred.

Committed draw milliseconds, offline / held-newest first open:

| Method | Baseline | Current |
| --- | --- | --- |
| Ordinary projection, fragmented fixture | 676 / 609 | 639 / 583 |
| 1100 ms held projection, fragmented fixture | 841 / 678 | 839 / 687 |
| Slow-restore rejection control | 3800 | 3768 |

Both draw methods passed all eight openings, not just these first-open examples.
The slow control passed by rejecting the unchanged 1000 ms assertion after its
3000 ms restore delay. Cold construction, newest committed-frame endpoint, saved
rows/anchors, offline request counts and held-newest assertions remain unchanged.

`baseline-openings.jsonl` and `current-openings.jsonl` retain every readiness
window, acquisition/read duration, installation and cumulative restore/snapshot/
complete-content/draw phase. `method-sampling.jsonl` summarizes all four methods.
`comparison.json` records commands, revisions, XML hashes and private manifest
hashes. The numeric five-second observer does not replace the test's 250 ms
windows. Device-phase host idle averaged 69.08% / 69.07%, including boot and timed
work; guest steal intervals ranged 0–0.60% / 0–0.39%. The baseline observer excluded
one negative-total interval across managed-device restart. These coarse intervals
cannot establish CPU load at an individual draw.

Environment differences from the earlier failed run are deliberate and recorded:
the deployed owned/admitted gate replaces direct overlapping Gradle execution;
the external compiler pressure has been remediated; configured Gradle heap is
2048 MiB rather than the earlier explicit 4 GiB; both use in-process Kotlin,
disabled animations and the same managed Pixel 2 API 33 profile. Baseline build
preparation took 199.28 seconds versus current 99.30 seconds; device-command phases
(including startup) took 185.99 / 118.12 seconds. Neither run queued for admission.
The baseline's extra attempted image-property read during emulator startup failed
with `error: closed`; it supplies no image fingerprint or test result. Numeric CPU
sampling continued, and every per-opening readiness barrier subsequently succeeded.

## Historical latency and later-stage handoff

The #2163 full UI gate's 1434/1436 ms fragmented held-projection first opens remain
historical failures. Fresh valid 841/678 and 839/687 ms results do not attribute or
prove repair of those older measurements. The retrieved original verifier bundle
has counted XML and its verdict, without enough contemporaneous CPU/phase evidence
to establish cause. Separate [latency follow-up #2202](https://github.com/pyrycode/pyrycode-mobile/issues/2202)
is open in Backlog with low priority; it does not block restored readiness here.

Raw XML, four phone logs per run, CPU samples, revisions, gate phases, analysis
scripts and verified manifests survive under
`$AGENTS_REPO_PATH/logs/evidence/2177/post-runner-{baseline,current}-20261016/`.
Downloaded original bundles are in `historical-2163-20261016/` under the same root.
Complete configured UI and scripted-all gates remain dispatcher-owned and pending;
documentation must record their actual counts and confirm all four methods ran.
Documentation handoff: update the “Readiness and sampling cancellation (#2143)”
section of `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`.

## Focused unit verification

`EmulatorCpuReadinessTest` executed **12**, passed **12**, with **0 failures,
errors or skips**, XML timestamp `2026-10-16T21:54:31.865Z`. This includes counter
interpretation, busy-streak reset, deadline enforcement, held acquisition/read,
late-source closure and cancellation. The parser sums the first eight counters,
uses idle plus I/O wait as idle and counts steal as busy; it excludes the duplicate
guest-time fields. No counter interpretation or sampling logic changed.
The same foreground Gradle invocation passed lint, debug assembly and
instrumentation compilation. Unit XML SHA-256:
`b919186695ce234026956c98ded026c7f69666da2acb1f1dd0cbdd5deb6e57dd`.

## Separate scripted validation blocker

The focused `scripted ping` run failed: **2 executed, 1 passed, 1 failed, 0 skipped,
exit 1**. The rendered reply/checkpoint assertions completed, then
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` timed out in
its unchanged 90000 ms `observeReadMarks` wait. The durable-gap method passed.
This matches open [#2198](https://github.com/pyrycode/pyrycode-mobile/issues/2198),
whose retained clean-main comparison already reproduces the same assertion.
No production, deterministic-test or harness logic differs from this branch's
main merge base. The failed focus record follows teardown and does not establish
why read confirmation failed. No retry or skip was used.

`scripted-ping.json` retains counts, revisions, XML hash and the existing tracker.
Raw XML, both phone logs and isolated harness diagnostics are private in
`$AGENTS_REPO_PATH/logs/evidence/2177/scripted-ping-20261016/`.
The native blocked-by link from #2177 to open #2198 was verified. First-draw
measurement is restored; green scripted validation must wait for the owning
read-confirmation repair. Complete dispatcher gates remain pending.
