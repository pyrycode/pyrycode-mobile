# Saved-thread initial snapshot evidence (#2153)

The reported `1fb9259` sweep executed 1609 tests, passed 1608 and failed the
projection twin at 1048 ms. `original-report.json` preserves that supplied result.
Original raw sweep logs are absent here, so its historical cause remains unresolved.
No harness defect or causal regression commit is established.

Fresh unchanged and repaired comparisons both use
`UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui`: two
Pixel 2 API 33 ATD shards, non-e2e device/shared tests and disabled animations.
Baseline revision is `1fb92599b2df99b0380f48c1be8156bf0ffdfc75`; repaired source is
`6eb138928073c30ef41c5a064854bef7776b5dd5`. Each exited 0 with **1609 executed/passed,
zero failures/errors and two existing skips**. All four saved-thread methods
executed once and passed in each comparison. Source hashes are in the summaries.
`verified-source.json` ties the focused pre-commit device run to that pushed source.

Every tuple below is cumulative **restore/snapshot/content/committed-draw**,
monotonic milliseconds. The original timer, 1000 ms bound, 1100 ms projection hold,
cold construction, fresh ViewModel on reopen, persisted fixtures, exact rows and
marker anchors, offline/held-newest request assertions remain unchanged.

| Case | Baseline original | Baseline twin | Repaired original | Repaired twin |
| --- | --- | --- | --- | --- |
| Ordinary offline first | 6/31/47/105 | 22/29/45/85 | 19/24/29/82 | 34/43/57/121 |
| Ordinary offline reopen | 21/38/57/126 | 2/29/44/104 | 16/21/26/75 | 20/26/43/108 |
| Ordinary held newest first | 17/23/40/99 | 23/30/46/105 | 37/40/57/97 | 7/34/50/116 |
| Ordinary held newest reopen | 28/33/38/87 | 22/28/44/92 | 18/24/41/87 | 15/21/26/74 |
| Fragmented offline first | 310/402/414/494 | 369/522/538/641 | 311/312/324/406 | 419/420/444/538 |
| Fragmented offline reopen | 61/107/129/191 | 51/126/145/222 | 39/63/80/171 | 44/60/73/154 |
| Fragmented held newest first | 358/512/523/586 | 316/385/409/487 | 305/306/320/403 | 294/295/309/399 |
| Fragmented held newest reopen | 43/112/133/208 | 44/201/228/318 | 58/73/86/155 | 46/56/86/158 |

For fragmented cases, the baseline twin spends 69–157 ms from row restoration to snapshot;
repaired intervals are 1–16 ms. Its first offline worker used 161 wall / 103 CPU /
0 queue ms before repair; the direct initial publication worker takes 0 ms afterward.
The separate 3000 ms slow-restore control rejects the identical 1000 ms assertion:
baseline **3005/3026/3039/3063**, repaired **3009/3035/3052/3080 ms**.
The held CPU-pipe cancellation control also passed.

The targeted traversal/recollection comparison executed two tests and failed both
before repair; the large cache had 200000 accesses before delivery. After repair,
first delivery requires zero accesses and preserves the cache list and metadata.
The affected nine-class selection passed 148/148; the final worker class passed
10/10 with cancellation for empty and nonempty first snapshots and nonempty order
metadata on recollection. The initial candidate unit result (145 passed / 3 failed)
is retained: the new diagnostic event reached the Android logging stub until the
plain JVM fixture installed/restored the existing test logger sink.

The focused complete device class passed 4/4, zero failures/errors/skips; original
and twin draw maxima were 430/578 ms, and its slow control rejected at 3480 ms.
Raw XML, all phone logs, bounded phase/worker/readiness selections and verified
copy manifests survive under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2153/`: `baseline/`,
`suite-green/`, `class-green/`, and the named unit comparison directories.
Failures remain alongside passes. Container scratch/build paths alone are not evidence.

The configured regular UI gate, subsequent post-merge main sweep and knowledge
documentation are dispatcher/documentation handoffs; no live-Claude scenario is needed.
