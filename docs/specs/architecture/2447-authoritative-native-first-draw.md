# #2447 — authoritative native first-draw acceptance

## Approved scope

Juhana approved proceeding with the authoritative-cache replacement on 2026-10-22 in the manual #2334 chat ("Go on" after the explicit mapping proposal). Reviewed main #2367/#2379/#2385 removed the legacy runtime, unsigned multi-span coverage and optional legacy projection consumer. Do not restore those production paths or claim that unused dispatcher delay tests them. This amendment applies to #2334 and #2437 acceptance through this blocking child. Historical failures and measurements remain intact.

## Representation and acceptance mapping

- Full-file compatibility now means the supported FileConversationCache authoritative envelope, decoded through the production cache/repository/ViewModel destination. It does not mean runtime import of retired legacy files.
- Retain the complete workload: 36000 actual unique saved facts in 18000 disjoint two-order islands (orders 4n+1 and 4n+2), with one visible message and one hidden forward-compatible fact per island. Assert cardinalities from decoded data, every raw fact including revision/unknown fields, and exact 18000 visible messages in chronological order. These are order islands, explicitly not legacy unsigned coverage spans. Coverage remains null, never falsely certifying holes. Use 20 ordinary facts as the small control.
- Retain offline and connected/held-newest arms, first open and fresh destination/cache reopen from disk. Each timed opening constructs fresh FileConversationCache and production SavedAuthoritativeDestination, restoring and committing an actual placed/drawn viewport. Held requests remain unanswered; assert exact request counts and no unexpected older demand.
- Replace the removed delayedOptionalProjection method with authoritativeSavedThreads_firstDrawDoesNotConsultRetiredLegacyReads_firstOpenAndReopen. Guard actual legacy cache readThread/readHistoryPosition calls with counted failures, assert zero, and execute the complete authoritative arms. The legacy optional projection requirement is explicitly retired; this is current-path exclusion proof, not a fake delayed projection.
- Preserve the existing 1000ms product target and renderer firstDrawBoundMs (2000ms SwiftShader). Keep the three other native methods: database cold opening, upward demand/retry/cold reopen, and descriptor cancellation. Restore slowRestore_negativeControlRejectsTheSameFirstDrawBound by delaying the actual authoritative disk read 3000ms and requiring rejection by the same bound.

## Implementation and concurrency

Only the native fixture and this plan/evidence change. No production behavior, schema, wire, paging or dispatcher configuration changes. Use existing real production host registry/coordinator/destination and controlled transport. Each opening owns a ViewModelStore, destination and cache wrapper; clear the screen, cancel/join the ViewModel job, close destination and await Compose idle before deleting app-private fixture directories. Counters are AtomicInteger; probes are per opening. No timing starts during fixture construction.

## Test strategy

Retain the baseline inventory showing only three methods and the named missing probes. Compile and force format, then run all six native methods through the normal Linux admission and Android UI gate. Record exact source/APK/daemon/image identity, selected/executed/pass/fail/error/skip counts, raw XML/phone records, computed cardinalities, raw frame times and hash manifest before cleanup. If timing/content fails, inspect the real finding; do not reduce workload or relax bounds. Independent complete JVM/UI/scripted gates remain mandatory for the child and final accepted parent candidate.

## Security review

**Verdict: PASS** — builder self-review, 2026-10-22. Synthetic facts only; app-private fixture paths contain generated static names, never daemon identifiers. No credentials, network endpoint changes, raw user content logging or exported components. The existing controlled authenticated production destination remains the only runtime route; unknown fields stay raw facts and hidden facts do not grant coverage. Test-only slow reads obey coroutine cancellation. Per-opening cleanup owns only its ViewModel/destination; fixture files are deleted after cancellation/join. Trust, crypto, lifecycle fences and database behavior are unchanged. Independent review remains required.

## Documentation and completion

Record final current-candidate measurements in authoritative-reader-paging.md and link the explicit mapping from the required parent topics. Complete the actual documentation stage, merge the reviewed child into feature/2437, then independently accept #2437 and the final #2334 candidate. No closure or historical green result substitutes for final acceptance.
