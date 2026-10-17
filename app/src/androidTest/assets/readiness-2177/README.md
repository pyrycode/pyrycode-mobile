# Saved first-draw readiness investigation (#2177)

**Current status, 2026-10-17:** #2209's managed-image repair is deployed. All 17
readiness barriers now qualify, but fresh measured draw misses require verified
open blocker [#2218](https://github.com/pyrycode/pyrycode-mobile/issues/2218).
The complete focused class is **4 executed / 2 passed / 2 failed / 0 errors /
0 skipped**; both controls pass. Scripted ping passes **2/2**. No acceptance retry
was run. Earlier readiness failures and passing comparisons remain preserved below.

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
was filed in Backlog with low priority. Its later bounded investigation closed
with the historical cause unresolved; the new measured failures belong to #2218.

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

## Historical scripted validation blocker (2026-10-16)

The focused `scripted ping` run failed: **2 executed, 1 passed, 1 failed, 0 skipped,
exit 1**. The rendered reply/checkpoint assertions completed, then
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` timed out in
its unchanged 90000 ms `observeReadMarks` wait. The durable-gap method passed.
This matched then-open [#2198](https://github.com/pyrycode/pyrycode-mobile/issues/2198),
whose retained clean-main comparison already reproduces the same assertion.
No production, deterministic-test or harness logic differs from this branch's
main merge base. The failed focus record follows teardown and does not establish
why read confirmation failed. No retry or skip was used.

`scripted-ping.json` retains counts, revisions, XML hash and the existing tracker.
Raw XML, both phone logs and isolated harness diagnostics are private in
`$AGENTS_REPO_PATH/logs/evidence/2177/scripted-ping-20261016/`.
The native blocked-by link from #2177 to then-open #2198 was verified. Green
scripted validation at that point needed the owning read-confirmation repair.
That repair is now merged; the fresh result follows. Complete dispatcher gates
remain pending.

## Resumption after #2198 (2026-10-17)

On Mobile `5e3e4471733f63e97ccb6c4fc5f054d8107f9445` and agents
`49cd6dd07c163a7d0e9583850aafb28378ec0b3c`, supported focused commands report:

| Run | Executed | Passed | Failed | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Complete first-draw class | 4 | 3 | 1 | 0 | 0 | 1 |
| Isolated slow-control diagnostic | 1 | 0 | 1 | 0 | 0 | 1 |
| Scripted ping | 2 | 2 | 0 | 0 | 0 | 0 |

Both first-draw methods pass all eight openings, and held-pipe cancellation passes.
The class's first executed slow control records **109** responsive windows at
**0–49% idle**, none >=80%; its isolated diagnostic records **110** in the same
range. Both end at `stage=window` before any Probe. Neither measures a slow draw
or the bound's rejection. No acceptance retry, warmup, threshold/assertion change
or skip converts these failures into passes.

Diagnostic five-second intervals overlapping readiness show guest idle
**3.97–37.83%**, steal **0–0.20%**, and host idle **45.28–74.39%**. A fresh one-second
guest `top` screen near the deadline/teardown shows **9% idle**, zero host/steal,
Google Play Services persistent/main **66/23** percentage points of two-core CPU
capacity, system_server **26**, and Mobile **3**. This identifies substantial
guest background work near the failure, not its initiating operation or every
window's cause. The diagnostic adds read-only observation overhead. Its search
process has zero additional CPU ticks; lifetime `ps %CPU` cannot establish active
search load. `dumpsys cpuinfo` repeats a boot snapshot and is not current attribution.

No Mobile logic repair is evidenced. The required agents runner/device-environment
repair is tracked by open native blocker **#2209**, in Backlog at low priority.
Fresh all-four-method acceptance and complete dispatcher gates remain pending.
Fragmented offline/held-newest first draws here are **601/589 ms** normally and
**852/667 ms** with projection held; historical **1434/1436 ms** misses remain
unattributed in #2202.

`resume-validation-20261017.json` retains counts, revisions, source/XML/manifest
hashes and observation limits. `resume-openings-20261017.jsonl` retains all sixteen
valid openings and the failed readiness phase. Raw reports, phone logs, isolated
ping harness diagnostics and CPU records survive in the three `resume-*` evidence
directories named in the JSON. All manifests were checked after copying. Update
the documentation handoff with this renewed guest-load failure as well as actual
later dispatcher gate results; do not replace either earlier failure history.

## Managed-image repair and current latency blocker (2026-10-17)

External #2209 closed after agents PRs #190/#191 deployed at
`9aad4b5db84f2897c5eda3e8ed78a5ec9256ad71`. Gradle's boot-plus-fixed-delay snapshot
could preserve startup work. The runner now prepares an OS-only generation,
requires sustained guest quietness, verifies changed RAM/named disk checkpoints,
and qualifies a read-only restore before installing Mobile. Play Services, the
original signer, admission and owned cleanup remain intact. The initiating guest
operation remains unproved. No Mobile logic repair is evidenced.

The owner's complete 88-file Fractal comparison was independently hash-verified
and retained under this ticket. Unchanged Mobile `5e3e4471733f63e97ccb6c4fc5f054d8107f9445`
with reference agents `49cd6dd07c163a7d0e9583850aafb28378ec0b3c` reports
**4/3/1/0/0** (executed/passed/failed/errors/skipped). Its negative control fails
before a Probe after 109 responsive windows at 0–52% idle. Repaired conditions
report **4/4/0/0/0**, all 17 Probes, and slow-bound rejection at **3630 ms**.
The owner's controlled runner regressions are external evidence, not tests rerun here.

This branch's fresh supported class at `c8060639b8903a65b8e29641b8f812047cfcb9b0`
reports **4/2/2/0/0**, exit 1, XML timestamp **2026-10-17T07:50:49**. Both positive
methods complete all eight openings but fail their aggregate committed-frame
assertions. All **17 readiness barriers** succeed across **98 windows**, each
ending with four qualifying windows; maximum acquisition/read are **22/80 ms**.
All 17 Probes are installed. The slow control rejects the identical 1000 ms bound
at **3928 ms**, and held-pipe cancellation/descriptor closure pass.

Cumulative restore/snapshot/complete-content/committed-frame milliseconds for
fragmented first opens:

| Method | Offline | Held newest |
| --- | --- | --- |
| Original | 791/792/816/**1026** | 451/453/473/619 |
| 1100 ms held projection | 935/936/985/**1314** | 710/713/749/**1032** |

These are valid measured misses. They neither establish the cause of the earlier
1434/1436 ms failures nor supply green acceptance. #2202 retains the historical
disposition; new Backlog/priority:low issue #2218 is natively linked as this ticket's
required latency-repair blocker. No assertions were ignored, and no acceptance
retry, warming, threshold change or renderer/process change was made here.

The current generation is `151a9dbd74aea4118480cc270492d68ef7fd255ce0b03cfacf6db74537c098d8`;
its preparation/restore final 40-window minima are **82.46/83.64% idle** before
Mobile installation. Compared with the owner's generation `07867931…`, only the
recorded launcher identity input differs: current `emulator.hp-native`/`swangle`
records `swiftshader`; owner `emulator`/`auto-no-window` records `lavapipe`.
Both record x86_64, two cores, 2048 MiB, 1080x1920 and density 420. These differences
do not prove a renderer cause. Eight measurement-source hashes still match.

Thirty-six bounded read-only samples add observation overhead. Five-second guest
intervals include preparation and measured work, with up to **16.17% steal**;
they cannot attribute an individual draw. The search process adds **zero CPU ticks**
during observation; its lifetime average is not current-load evidence.

`managed-image-validation-20261017.json` and `managed-image-openings-20261017.jsonl`
retain counts, source/image identities, XML/manifest hashes, environment differences
and all **51** opening records across reference, repaired-owner and current runs.
Fresh ping reports **2/2/0/0/0**, exit 0, XML timestamp **2026-10-17T07:54:33**.
Raw reports, phone/worker logs, CPU samples, OS preparation records and verified
manifests are under `$AGENTS_REPO_PATH/logs/evidence/2177/` in
`managed-image-owner-comparison-20261017/` (88 files),
`repaired-image-class-20261017/` (29 files) and `repaired-image-ping-20261017/`
(9 files, including isolated harness diagnostics). Complete dispatcher UI/scripted-all
gates and documentation remain pending. Resume green acceptance after #2218's repair.
