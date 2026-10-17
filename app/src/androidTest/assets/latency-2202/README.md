# Retained fragmented first-draw latency (#2202)

**Disposition:** historical failure retained; cause unresolved; not reproduced under
the recorded repaired-runner conditions.

The unchanged-production **configured complete regular UI** baseline on Linux
passed **334 executed / 334 passed / 0 failed / 0 errors / 1 skipped**, exit 0.
Each of the four `SavedThreadFirstDrawDeviceTest` methods executed and passed once,
with zero failures, errors or skips. The existing unrelated skip is
`RenameDialogCaptureTest.renameAtFigmaViewport`. No acceptance retry was run.

This is builder investigation evidence. Fresh complete **dispatcher** UI evidence
remains required before verifier PASS, including all four methods, sixteen positive
openings and the negative control. Documentation records those results later.

## Source and conditions

- Mobile `33b1353bf5d8929f81690acf376ba47e51b82043`, containing only the committed
  plan over main `a0c7db693245cce59d67377f672d2bec4c7dca65`; agents
  `49cd6dd07c163a7d0e9583850aafb28378ec0b3c`, after #2178.
- Linux/build-hp-1, container `d334f32a9191`, Intel i7-8700, 12 logical CPUs,
  32626980 KiB host memory; OpenJDK 21.0.12.1. The managed profile is Pixel 2 API 33
  Google ATD x86_64, one shard. `baseline.json` retains the image fingerprint and
  identity hashes; private metadata retains CPU, memory and display configuration.
- `UI_GATE_FULL=1 ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`;
  `UI_DEVICE_ALL` and `UI_SHARDS` unset. Shared admission waited 229 seconds;
  preparation took 80.77 seconds and the device command took 1472.03 seconds,
  including startup and the entire selection. This was no in-depth main sweep.
- Owned foreground Gradle JVM, in-process Kotlin, 2048 MiB heap. The instrumentation
  requested disabled animations; a mid-run read confirmed all three scales at zero.
  Initial pre-instrumentation scales are retained separately. The observer made
  read-only host/device samples approximately every five seconds and added overhead.

All eight measurement-source hashes match the historical source and both #2177
focused comparisons, including its latest resumption. The wider trees differ:
current main includes read-confirmation, DI/push and recorded-client changes; the
historical unbound Room backend is absent here. The measured path remains
`FileConversationCache` / `CachingConversationRepository` / `ThreadViewModel` /
`ThreadScreen`. No Mobile production or test logic changed in this ticket.

The complete selection includes 73 additional recorded-client cases compared with
the historical gate. #2177's 4/4 plus 4/4 comparisons selected only the focused class
on build-fractal-2 with agents `800e719515a91bf2040d9d26b0e0a802e931bca3`.
Its original baseline fragmented offline/held-newest draws were 676/609 ms normally
and 841/678 ms with projection held. Its latest 4/3/1 focused result failed startup
readiness before the slow-control Probe; #2209 retains that separate failure.
This complete pass does not clear that report or replace #2177's gate handoff.

## Every opening and observed limits

Tuples are cumulative **restore / snapshot / complete content / committed newest
frame** monotonic milliseconds from the existing Probe. They are not isolated task
durations. Offline and held-newest modes retain fresh first construction and
repository reuse with a fresh ViewModel/composition on reopen.

| Fixture / mode / opening | Normal projection | 1100 ms held projection |
| --- | --- | --- |
| Ordinary / offline / first | 40/46/50/98 | 25/30/35/82 |
| Ordinary / offline / reopen | 15/20/38/81 | 16/20/39/83 |
| Ordinary / held newest / first | 30/36/53/92 | 14/19/24/72 |
| Ordinary / held newest / reopen | 37/39/56/98 | 16/23/27/75 |
| Fragmented / offline / first | 329/329/351/431 | 326/327/336/424 |
| Fragmented / offline / reopen | 44/45/71/138 | 46/46/60/123 |
| Fragmented / held newest / first | 312/313/331/404 | 341/342/353/433 |
| Fragmented / held newest / reopen | 42/43/69/129 | 42/43/63/125 |

All sixteen positive draws met the unchanged 1000 ms bound; the maximum was 433 ms
(567 ms headroom). Positive cumulative restore/snapshot/content ranges were
14–341 / 19–342 / 24–353 ms. The 3000 ms slow-restore control recorded
**3015/3052/3080/3123 ms** and passed by rejecting the identical 1000 ms assertion.
The held-pipe cancellation/descriptor-closure control also passed.

Every one of the seventeen readiness barriers succeeded after exactly four
250 ms windows: **68 windows, 82–100% idle**, acquisition **0–6 ms**, reading
**10–33 ms**. No readiness exhaustion, held sample or draw miss reproduced.
Persisted ordinary/fragmented fixtures, exact rows/markers/request counts, cold
timed construction, projection hold and exact committed-frame endpoint remain
unchanged. No added skip, warmup, acceptance retry or threshold relaxation occurred.

The observer retained 355 host and 289 guest samples plus 158 worker events.
Device-phase host intervals range 54.54–98.98% idle; guest intervals across boot and
all methods range 3.30–99.60% idle and 0–4.80% steal. These coarse intervals include
other work and cannot attribute CPU load to an individual subsecond draw. Neither
the observed headroom nor readiness establishes a general reliability guarantee.

## Historical failure and evidence boundary

The original #2163 bundle retains **261/260/1/0/1** counts and the delayed-projection
draws **260/357/258/207/1434/478/1436/506 ms**, in the table's case order. The two
fragmented first opens remain failures. It identifies Mobile
`23176249515918b2a776c4b3429d8ce112d363e0` and build-fractal-2, plus a dispatcher
revision. Its exact measurement agents revision, contemporaneous host/device CPU
and load, detailed image/host configuration, raw readiness/worker logs and raw
restore/snapshot/content/frame phases are **unavailable in the retrieved bundle**;
`pointers.json` is empty. Final failed draw values survive in stderr.

These missing measurements prevent historical attribution. The source match,
runner remediation and complete passing comparison establish no historical cause
or repair. No speculative production repair or Room activation is proposed.

## Retention and handoff

`baseline.json` records counts, revisions, configuration, source/XML/manifest hashes,
condition differences and limits. `openings.jsonl` retains all seventeen readiness
streaks and cumulative phases. An independent audit verified the raw manifest,
raw/aggregated XML identities and counts, four selected methods, ordered phases,
fixture row/durable/span counts, bounds and the eight source comparisons.

Private raw XML, all 335 phone logs (including worker/readiness events), CPU samples,
configuration, observer script and checked manifest are retained under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2202/baseline-20261017/`.
The retrieved historical bundle is preserved under `historical-2163/`; analysis
outputs and the audit script are under `audit-baseline-20261017/` in the same root.
The raw baseline's 348-file manifest SHA-256 is
`314c33855de229498ec0e1cab06f0e2af16a76477bb5ac22fce56dbc0d105c55`.

Pending documentation: add this complete-gate disposition to
`docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, under
“Readiness and sampling cancellation (#2143)”, after folding #2177's readiness
history into that section. Preserve both histories and record actual later
dispatcher results. Linux is the tested OS; other operating systems are outside
this ticket's verification scope.
