# #2449 — authoritative file validation cost

## Problem and retained failure

The approved #2447 mapping exposed a real full-file first-draw miss in ui-pej4ek4w on c2f6bcf804f4fec8e68ae53b12379c3a0861e182: six methods executed, four passed, two failed, zero errors/skips. The complete 36000-fact/18000-two-order-island/18000-visible-message fixture drew offline in 5094–5387ms; read2756–2876ms, snapshot4457–4719ms. Connected arms timed out. Ordinary20-fact arms passed; the raw native reports/APKs/phone/source remain under logs/evidence/2447/manual-20261022/third, manifest52d0b1ddc9e573b491c00d6fe0444daedd198f49162e20f9980122e769037b91. New child2449 blocks2447/2437/2334; no acceptance floor changes.

## Source diagnosis and bounded first repair

FileConversationCache.decodeAuthoritative validates saved facts, and repository application validates again; online acquire also restores the file. Each SavedAuthoritativeThread.toSnapshot invokes validThreadJson and ThreadItemDto.decodeReplacement for each fact. The current replacement decoder filters every raw map, constructs an eight-key set for every top-level entry, then allocates a copied map even when no optional null clear exists. validThreadJson separately constructs a three-value set for every non-string primitive. This multiplies allocation across cold validation/application.

Start with removing these redundant per-field allocations while retaining every validation and decode. Shared private immutable optional-key membership and a direct no-clear path can preserve the original JsonObject for typed optional access. Only construct the filtered optional view when a known top-level optional field is explicitly JsonNull. Unknown nulls, all raw presence and raw identity stay unchanged. Numeric lexical validation retains exactly the same accepted literals/regex; literal comparisons need no per-value set. No decoded-result memoization, new cache lifetime, trust shortcut, schema, wire, paging or concurrency state is introduced. If this small causal change is insufficient, measure again before choosing another bounded step; never reduce fixture or increase timing bounds.

## Test first and validation

Add a deterministic decoder regression using a map whose entry enumeration is counted. Ordinary replacement decoding must read named typed fields without enumerating/copying unrelated raw extension entries; typed values and original raw identity must remain exact. Record its actual failure before the production change. Preserve null-clear behavior and malformed-known-field/unknown-number tests. Run affected ThreadUpdateDecoderTest/ThreadQueryDecoderTest and cache/repository tests through normal admission, then the unchanged six mapped native methods with full36000facts. Record all raw evidence and current source/APK/daemon/image identities; do not confuse a JVM allocation-work regression with timing acceptance. Full independent configured Linux JVM/UI/scripted review and actual documentation/merge/Done remain required.

## Security review

**Verdict: PASS** — builder self-review, 2026-10-22. Keep validThreadJson traversal, strict integer/boolean/string parsing, every known optional-field type check and all raw unknown fields. The fast path is chosen only from direct presence/value tests for the existing eight optional keys; it grants no validity and caches no result. Explicit null clear filtering has the same whitelist; unknown nulls remain raw. There is no mutation, global content memoization, file ownership change, capability/state transition, logging, endpoint, credential, crypto or Android component change. Existing owner/generation/reader/cancellation and retirement gates remain untouched. Independent review must verify equivalent accepted/rejected inputs and current native evidence.

## Documentation handoff

Record actual causal regression, exact fixture timings/counts and final accepted-candidate evidence in authoritative-reader-paging.md. Keep approved mapping and historical misses visible. Reviewed child targets feature/2447, then2447/2437/2334 each complete their own required acceptance/documentation/workflow.

## Confirmed causal red

Source6659a0a9c7b9d552ac3966f8e6ac490f69433dae ran the one selected regression against unchanged production: 1 executed, 0 passed, 1 failed, zero errors/skips; expected zero raw entry enumerations but observed one. Actual fresh XML/full Gradle log/test/production source are retained in logs/evidence/2449/manual-20261022/red-6659, manifestc5dc6017c69f09a5e4c7feb597518dba3837fdb99f7b0616ef080354d30017d1. The production repair only removes per-field set/copy allocation in ThreadUpdatePayloads.kt. It preserves the full strict validation traversal and all lexical checks and adds no mutable state or decoded-result cache. Passing focused/native evidence remains pending.
