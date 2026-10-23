# #2456 — full-file compatibility benchmark scope

## Files read

- `docs/knowledge/INDEX.md`: identifies the authoritative paging topic.
- `docs/knowledge/features/authoritative-reader-paging.md`: approved DB/file acceptance scope and historical failures.
- `docs/knowledge/features/authoritative-reader-native-benchmarks.md`: selected methods, supported invocations, counts, frame timings and runtime receipts.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: `measureAuthoritativeFiles`, `Probe.assertBound` and the bounded DB opening/control tests.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: `newestAuthoritativeWindow` and `importSource` distinguish bounded reads from the import transaction.
- `app/src/main/java/de/pyryco/mobile/data/cache/AuthoritativeRoomStorage.kt`: `importThread` owns the separate full import.
- Ticket #2456, PR #2497 and verifier stage `812003306b9394ca268e9d5dd104d70288870d094f46edfcbac36f99a1d85154`: authorized scope, original chronology and the missing-plan finding.

## Change

Record the authorized measurement/documentation scope without adding production behavior.
Normal opening uses the migration-aware Room cache for about 200 display rows; the direct
file fixture measures full-file compatibility, and neither measures the entire one-time
file-to-Room import transaction. Preserve both separately selected
`authoritativeFileCompatibility_preservesExactFacts_offlineAndHeldNewest_firstOpenAndReopen`
and `authoritativeFileCompatibility_exposesNoRetiredReadApis_firstOpenAndReopen` methods:
the ordinary control, 36000 facts / 18000 order islands / 18000 visible messages,
offline/held-newest first/reopen arms, exact facts/order/revisions/unknown fields,
numeric fidelity, ownership, immutable values, version validation, retirement,
cancellation and read/request assertions remain binding. Keep file correctness in normal
verification and the optional `requireFullFileLatencyBound=true` assertion at its historical
1000 ms target / 2000 ms SwiftShader bound. Report timing misses as failed benchmark
assertions; achieving that target is not required to complete #2456 and cannot block DB
acceptance. DB opening/demand/retry/reopen bounds remain unchanged. No production
optimization, new import study, UI change or live-Claude work belongs to this repair.

## Chronology and evidence provenance

This plan is added on 2026-10-23 in response to the FAIL on `f2e35ecae7dd`.
It follows the existing documentation commit `26383720bb26d9707bcf2d6365faed480c654959`;
it was not committed before that work. Preserve that history without backdating or rewriting it.

The companion reports measured source `45b86ff05dd71c8904a006c8b8c8a7c6489616c7`:
Linux `native/ui-3f992hvw` executed 2/pass 2/fail 0/error 0/skip 0, with all 16 cases;
`optin/ui-26l2julw` executed 1/pass 0/fail 1/error 0/skip 0 after all eight correctness cases,
failing at 2290 ms versus 2000 ms. API 33 Google ATD x86_64 revision 1 used ANGLE/Vulkan
SwiftShader. Linux receipts are referenced at agents `logs/evidence/2456/manual-20261023/`,
manifest SHA-256 `84c53109353211e907bab94dc08dc5b8798848230252f7571bb9722a7876cc5c`.
The macOS ARM64 run executed 2/pass 0/fail 2/error 0/skip 0 at CPU readiness, before any
compatibility frame. Its `mac/` subdirectory has the retained XML/phone/runtime receipts,
manifest SHA-256 `9f823b5cfad6bd8bb2a8fbd36ec195ecda03c010dd2ee64fa301feb6074da49d`.
The verifier authenticated the Mac bundle; raw Linux receipts remain pending independent
authentication. These revision-specific results do not certify the later merged head or
establish a cross-host optimization comparison. Historical failures retain their outcomes.

## Testing strategy

Review this plan against the ticket, source and retained receipts; preserve the companion's
exact default/opt-in invocations and measurements. This prose-only repair adds no executable
logic requiring a new regression or native measurement. Run documentation/whitespace checks,
formatting, lint, assembly and final `scripts/pre-verify.py --gradle` on macOS.
The earlier combined JVM gate timed out as infrastructure failure, and subsequent UI/scripted
gates did not run. Complete configured acceptance remains dispatcher/verifier-owned and
pending; builder checks do not replace it. New Linux execution is outside this run's scope.

## Documentation handoff

Pending documentation-stage review: `docs/knowledge/features/authoritative-reader-paging.md`,
“Current DB and file acceptance scope” and “Independent opt-in file benchmark,” with the
linked `authoritative-reader-native-benchmarks.md` receipts. Preserve supported invocations,
source/runtime identities, historical evidence and the unmeasured actual import boundary;
retain the independent Linux evidence-authentication limitation until receipts are reviewed.

## Revisions

- 2026-10-23: verifier finding 1 on `4cce5dbd8a17` requires diagnosis of the
  configured `stream` failure in
  `interactiveTurn_seededChannel_queuedPromptDoesNotSplitHeldReply` at the held-prefix
  checkpoint. Preserve verifier bundle `d08876824504c4d0936c943b15e14c5f8bc50b49972df6f8bb4c953e4ca190de`
  (digest `3ef00b5512947781e6c073c22ea01935247d12445a4852e1ddcd67cab82bfac1`):
  24 executed, 23 passed, 1 failed, no errors/skips. The rejected method selections
  executed no comparison and establish no inherited-failure exemption. Main now
  includes #2486's negotiated-route observer and current-source recovery assertions;
  its separate accepted evidence does not prove the cause of this earlier failure.
  Run the supported `python3 scripts/android-test-gate.py scripted stream` on this
  macOS candidate, retain counted XML and phone/fixture/runtime receipts, and record
  the outcome here and in the PR. Keep complete configured acceptance pending with
  the dispatcher/verifier; request a wider sequence investigation if focused proof
  cannot resolve the original acceptance result. No production change is planned.
