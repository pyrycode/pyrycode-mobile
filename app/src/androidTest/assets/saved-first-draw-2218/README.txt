#2218 — saved-thread first-draw misses after managed-image repair, 2026-10-17

Content-free checked summaries for SavedThreadFirstDrawDeviceTest. #2218 changes
no production or test source. The renderer-selected bound came from #2228 on
main after blocker #2222 closed. Phase tuples are cumulative restore/snapshot/
complete-content/committed-frame milliseconds from Probe start (cold cache,
repository and ViewModel construction inside the timer). Counts are
executed/passed/failed/errors/skipped from inspected XML.

Files
- fractal-2-swangle-failing.json: the #2177 failing focused class on
  Linux/build-fractal-2, generation 151a9dbd…, HP SwANGLE launcher
  (emulator.hp-native, -gpu swangle, swiftshader). 4/2/2/0/0. Per-opening
  phases, FileConversationCache row/history stage logs and app GC overlap.
- fractal-2-native-passing.json: the unchanged-Mobile owner comparison on the
  same host and runner, generation 07867931…, native launcher (emulator,
  auto-no-window, lavapipe). 4/4/0/0/0. Per-opening phases only.
- build-hp-1-focused.json: one fresh supported focused class on Linux/build-hp-1,
  unchanged main, same generation identity 151a9dbd… (same launcher wrapper and
  swiftshader). Same summary shape as the failing run. 4/4/0/0/0, exit 0, XML
  2026-10-17T09:30:11. Fragmented first draws 555/433/407/420 ms (cold decode
  249–291 ms, history metadata 362–522 ms); ordinary draws 129–241 ms; slow
  control rejected the bound at 3494 ms; 17 barriers, 85 windows, max
  acquire/read 14/35 ms.
- build-hp-1-software-bound.json: fresh acceptance on Linux/build-hp-1 at
  feature/2218 e3c1afaa (main 51954f1d, including #2228), agents c8edc37e, same
  generation 151a9dbd…, same wrapper. 4/4/0/0/0, exit 0, XML
  2026-10-17T10:42:22. This was the first device run of #2228. Every class logged
  event=first_draw_bound bound_ms=2000 with the SwiftShader renderer. The projection
  twin held for 2100 ms. Fragmented first draws were 614/441/415/416 ms
  (cold decode 247–318 ms), ordinary draws 118–258 ms, and every opening was also
  within 1000 ms. The slow control drew at 3473 ms and was rejected. Readiness
  took 17 barriers and 83 windows, with max acquire/read of 10/37 ms. Two
  measurement sources differ from the earlier runs: the test, which gained the
  #2228 bound, and FileConversationCache, where #2213 added pinnedAt to the
  conversation-list record and left the thread decode unchanged.

Fragmented first opens, committed-frame ms (offline/held-newest per method)
- fractal-2 SwANGLE, #2177, bound 1000: projection 1314/1032, original
  1026/619. Result 4/2/2/0/0, which stays failed.
- build-hp-1, earlier, bound 1000: 555/433, 407/420. Result 4/4/0/0/0.
- build-hp-1, this run, bound 2000: 614/441, 415/416. Result 4/4/0/0/0.

Localized cost, fractal-2 failing run
- Cold row decode in readDecodedThread: 624–789 ms of 710–935 ms restore in
  the failing first opens; read 5–14 ms, validate 33–64 ms. Identical bytes
  decoded in 395 ms in the passing first open of the same process.
- Content to committed frame: 210–329 ms in failing first opens; 138–232 ms
  even for the 20-row ordinary fixture.
- The lazy history decode (metadata 1069–1465 ms, proofs 626–929 ms) runs after
  the row decode, overlapping the first draw. Two app GCs overlapped
  content-to-frame in each failing first open and none in the passing one.
  This is a measured contributor; reopens without it still took 144–258 ms.

Attribution limits
- Same-host, same-runner comparison: only the launcher identity input differs,
  yet fragmented first draws went from 582–816 ms to 619–1314 ms, with guest
  steal up to 16.17%. This localizes the regression to fractal-2's execution
  environment. Two runs are not a repeated A/B, so the renderer is not proven.
- A pass on build-hp-1 with the same generation shows the host matters. It
  does not attribute or clear the fractal-2 miss.
- Operator decision on #2222: the SwANGLE wrapper is intended on both hosts,
  and the cause is Fractal's slower processor under software rendering. #2228's
  2000 ms software bound is the repair, so no Mobile change follows. The
  fractal-2 draws fall under 2000 ms only by arithmetic on retained values.
  build-fractal-2 was not run under the new bound by this ticket.
- Readiness passed for every opening in all runs; these are elapsed-draw
  results, not readiness failures. Closed #2202's historical 1434/1436 ms
  disposition is separate and unchanged.

Raw XML, phone logs and manifests: /work/Projects/pyrycode-mobile-agents/logs/evidence/2218/
(hp1-focused-20261017/, hp1-software-bound-20261017/).
Failing originals: #2177 bundle run 0ad15d3ecd76afa6fdb7df76c2d873f197352b0fa42649e2a683fdcdaaf7c7da.
