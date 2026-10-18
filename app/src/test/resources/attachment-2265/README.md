# #2265 Linux reliability evidence

Acceptance source revision: `4ab23d2c24babf00ed072697bd51331a612dc157` (Linux x86_64).
Later evidence/plan commits do not change the tested Kotlin source.

The default Compose rule resumed an effect directly on an IO worker after a held
`withContext(Dispatchers.IO)` completed. `red-io-return.xml` records the missing main
looper. At regression revision `72acc7c96b84e32a5535c87f4ea407488403b485`,
`red-layout.xml` records the matching `CalledFromWrongThreadException` from a view
layout request on `DefaultDispatcher-worker-5`. The first diagnostic was the same
probe before adding that layout request. `green-regression.xml` records its first
pass after supplying `StandardTestDispatcher`, before final formatting.

| Execution | Executed | Failed | Skipped | Evidence |
| --- | ---: | ---: | ---: | --- |
| Original method, unmodified rule | 1 | 0 | 0 | `baseline.xml` |
| Held IO return, unmodified rule | 1 | 1 | 0 | `red-io-return.xml` |
| Held IO return and layout, unmodified rule | 1 | 1 | 0 | `red-layout.xml` |
| Controlled regression with queued dispatcher | 1 | 0 | 0 | `green-regression.xml` |
| Affected class at acceptance revision | 18 | 0 | 0 | `class.log` |
| Fresh exact method 1 | 1 | 0 | 0 | `exact-1.log` |
| Fresh exact method 2 | 1 | 0 | 0 | `exact-2.log` |
| Fresh exact method 3 | 1 | 0 | 0 | `exact-3.log` |
| Fresh exact method 4 | 1 | 0 | 0 | `exact-4.log` |
| Fresh exact method 5 | 1 | 0 | 0 | `exact-5.log` |

Each log retains the raw Gradle output and counted XML. `invocations.json` retains
the exact commands and fresh-execution metadata. Acceptance commands were
`python3 scripts/jvm-test-gate.py --classes de.pyryco.mobile.ui.conversations.thread.ComposerAttachmentStripTest`
and five separate invocations of
`python3 scripts/jvm-test-gate.py --tests-json '["de.pyryco.mobile.ui.conversations.thread.ComposerAttachmentStripTest#ownedJpegThumbnail_appliesExifRotationWithoutTheOriginalProvider"]'`.
Both failed diagnostics remain failed; no attempts were discarded.

Historical #2254 evidence is retained by fleet run
`04be36a2235825e3b4fa3fa0f220373508d90a089903b3694a2957507cd80861`, bundle digest
`0ababba42d2c931780389d3f4c3d15fb2c53030e19e3d71889d73238d7e1cb18`, under
`logs/verifier-gate__2254_5.stderr.log.failure-1792318354394.log`.
That summary has no causal stack; the controlled regression supplies the local
mechanism. Complete JVM and remaining configured gates belong to the dispatcher.
