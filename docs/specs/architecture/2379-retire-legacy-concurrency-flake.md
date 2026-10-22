# #2379 — retire the legacy concurrent-acceptance flake

## Files read

- `data/repository/CachingConversationRepository.kt`: `ThreadWriter`, `CacheCandidate` and `observeAuthoritativeThread`; merged removal of legacy snapshots and accepted-row persistence.
- `di/AppModule.kt`: `ThreadDestinationFactory.repository` still wraps the negotiated host repository in the authoritative cache decorator.
- `data/repository/AuthoritativeThreadCacheTest.kt` (unit tests): retained restore, held-I/O, cancellation and older-final-flush assertions exercise the current runtime.
- `docs/knowledge/features/caching-conversation-repository.md`: dormant serialized legacy storage belongs to #2364; it is not a surviving runtime consumer.
- #2379 refinement/recovery comments and #2334 verifier bundle: preserve failure attribution separately from retirement.

## Change

Record retirement only. [#2367 / PR #2378](https://github.com/pyrycode/pyrycode-mobile/pull/2378) merged as [`af6721b314e4414034a7a8b58c5630ad0e42eecb`](https://github.com/pyrycode/pyrycode-mobile/commit/af6721b314e4414034a7a8b58c5630ad0e42eecb): its diff deletes the entire 655-line `OwnedLegacyCoverageConcurrencyTest` and removes `DrawnThread`, `AcceptedLegacyRow`, `acceptedRows`, legacy `ThreadWriter.accept`, `threadSnapshots`, and history-position persistence from `CachingConversationRepository`. The current writer accepts authoritative snapshots only. Historical XML and dormant storage remain historical/storage evidence. No production or test change is needed; a removed method is retired, never passed. One evidence deliverable, about 35 written lines, no exported symbols, consumer changes, new state or error branches. No in-flight branch overlaps this new plan.

## Historical evidence

- Earlier sighting: #2325, build-fractal-2, `2026-10-20T13:42:18.790Z`, retained in [the refinement record](https://github.com/pyrycode/pyrycode-mobile/issues/2379#issuecomment-6319833356).
- #2334: Linux/build-fractal-2, `2026-10-21T14:58:06.071Z`, revision `75868c31b0ffc803bce34e1d0fb3c48abe1f9d65`. JVM gate: 6442 executed, 6441 passed, 1 failed, 0 errors/skipped. The named method exceeded 180 seconds, was interrupted, and failed with `InterruptedException`.
- Separate named rerun: 1 executed/passed, 0 failed/errors/skipped; testcase 4.765 seconds. Fail-then-pass does not prove the timeout's root cause; it remains unproved.
- Durable fleet evidence: #2334 run `d42695f68700eefed16f1424a020c82707bc0cc5f7ee7505081f663b1ef9e641`, bundle digest `78a863889e97c448fff34f2b820d67bc50aaf5510709aeeb4890935ddb29526c`; all 133 `SHA256SUMS` entries verified during this builder run. Raw failure: `logs/verifier-gate__2334_4.stderr.log.failure-1792594664113.log`; raw rerun XML: `logs/2026-10-21T14-57-44-333Z_verifier-gate-rerun__2334.log`; counted verdict: `handoff/verifier-2334.md`. Retrieve with the fleet evidence command for that ticket and run.

## Testing strategy

Run the existing `de.pyryco.mobile.data.repository.AuthoritativeThreadCacheTest` class on Linux and retain its fresh XML counts in the PR. No new logic exists for a red/green regression. Run the required formatting, lint, assembly and final pre-verification checks. No device, scripted or live scenario changes.

Pending dispatcher/verifier acceptance: retain this ticket's fresh Linux JVM-gate raw output/XML, exact candidate revision, executed/passed/failed/errors/skipped counts, and nonzero authoritative cache class execution. Historical #2367 acceptance and focused builder execution do not replace that full gate. Confirm the retired class has no executable cases. macOS is outside this ticket's verification scope.

## Builder evidence — 2026-10-22

Linux focused run at `f486601cf08de2a5834d9cc112ea57a7345578a1`: `python3 scripts/gradle-gate.py testDebugUnitTest --tests de.pyryco.mobile.data.repository.AuthoritativeThreadCacheTest` executed/passed 23, failed/errors/skipped 0. Fresh raw report: `app/build/test-results/testDebugUnitTest/TEST-de.pyryco.mobile.data.repository.AuthoritativeThreadCacheTest.xml`, SHA-256 `e4c8c359c48d0034436885206ea1df89860f41c88115240f5030da26dbc19613`; retained scratch copy `/tmp/builder-2379/authoritative-cache.xml`. `lint assembleDebug spotlessApply` also passed through `scripts/gradle-gate.py`. This is focused builder evidence; full dispatcher acceptance above remains pending.
