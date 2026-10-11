# #2042: supplied thread updates and continuation assembly

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt`: `Envelope`, `HelloClientPayload`, `HelloAckPayload`; generic capabilities already retain new tokens.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt`: `MobileJson`; keep existing serialization behavior unchanged.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt`: `RelayLog`; diagnostics must contain static codes and lengths only.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt`: existing capability, envelope and codec regression coverage.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: bounded buffering must use a running byte counter rather than repeated summation.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md`: tree decoding can coerce strings/numbers, so validate primitive token shapes explicitly; preserve presence using raw JSON.
- Daemon `docs/protocol-mobile.md`, “Daemon thread updates (v2, supplied delivery contract)”, “Full thread item”, “Bounded thread encoding and assembly” and the three update subsections: authoritative contract. The sibling checkout predates this section; read the merged main version from GitHub.
- Daemon `internal/protocol/thread_test.go`, `thread_encoding_test.go`, `thread_assembly_test.go`: ordinary fixtures, escaped Unicode and rejection scenarios to mirror.
- Daemon `docs/knowledge/decisions/042-daemon-built-thread.md`: attribution and application belong to authoritative producers/stores, not this codec.

## Context

Supply one reusable decode boundary for complete live thread facts, ready for the later authoritative store and #2040 page codec. Production negotiation and daemon proof remain #1988. No repository dispatch, rendering, persistence, requests, page DTOs or reset protocol changes. The existing ADR covers the design; no new decision record is needed.

## Design

Add three production files under `data/network/`: `ThreadUpdatePayloads.kt`, `ThreadPayloadAssembler.kt` and `ThreadUpdateDecoder.kt`. All new declarations are internal. Five top-level consumer types: full item, logical update, generic sealed decode outcome, assembler and decoder; continuation bookkeeping stays private. Add `CAPABILITY_THREAD` beside the new payload declarations without changing hello defaults or hello-ack constructors. No new dependencies or existing signature migrations.

`ThreadItemDto` retains the entire original object and exposes the required fields plus nullable recorded optional facts. `ThreadUpdateDto` retains the entire original payload and envelope type, routing/version, item identity/revision/base revision, and the type-specific item, whole changes object or text suffix. A validated factory admits only the three known envelope types and exact required token shapes. Validate numeric/literal JSON grammar throughout the retained object without coercing unknown values; tree parsing alone admits non-JSON tokens. IDs, orders, revisions and versions accept canonical integer tokens from zero through `9007199254740991`; no string/boolean coercion. Optional full-item fields retain absence without inventing attribution. Content is any required JSON value, including null. Unknown item fields and nested content remain inert. Changes retain their JSON object without interpreting or merging values; immutable-field applicability and base-revision checks belong to the store.

`ThreadDecodeOutcome<T>` distinguishes complete values, pending conversation assembly and repair (owning conversation, when recoverable, plus a static code). Decode returns a list because expired conversations may need repair alongside the current frame. Only complete outcomes expose logical DTOs.

`ThreadPayloadAssembler` is generic over the completed value via decoding and routing-metadata callbacks, so #2040 can reuse the byte assembly without introducing page shapes here. A decoder/assembler instance is bound to one caller-supplied host and connection generation; instances never share state. Private identity includes envelope type, conversation, epoch, update ID and repeated logical metadata. Keep one pending assembly per conversation: a conflicting logical identity repairs and discards that conversation rather than replacing its unfinished update. Independent conversations may interleave.

Detect the presence of `continuation` before ordinary decoding, including malformed/null continuation objects. Ordinary payloads undergo bounded iterative compact-JSON byte counting before the metadata/DTO callback; never recursively serialize an untrusted tree to enforce its limit. Validate nonempty UTF-8 data with no unpaired surrogate, canonical progress integers, required boolean final, lowercase 64-digit update ID, exact consecutive index/offset, identical repeated metadata and base-revision presence. On final, require exact byte count, SHA-256(type + NUL + payload bytes), stack-safe strict original JSON parsing, valid DTO and matching decoded metadata. `parseThreadJson` builds containers iteratively and delegates only already-validated primitive leaves to `MobileJson`; nested containers never enter its recursive parser. Reject unescaped controls, malformed escapes, invalid literals/numbers and structural grammar errors without losing legitimate escaped controls. Data fragments never become item fields or ordering facts. An ordinary frame arriving during pending assembly repairs the interrupted conversation without emitting either update.

Defaults: 8 MiB per logical payload, 16 MiB aggregate buffered bytes, 32 pending conversations, 4096 parts per update, and 30 seconds absolute lifetime from the first part. Limits are injectable for deterministic boundary tests, must be positive, and are enforced before buffering. Count actual buffered UTF-8 bytes, including repeated metadata retained per assembly within the logical byte bound: metadata plus existing bytes plus the next fragment must fit the inclusive per-assembly limit before retaining the fragment. Completion/failure/expiry releases all charged storage. Missing final is repaired by explicit `expire()` calls or the next decode. `abandon()` clears and permanently closes this connection-scoped instance; a new connection requires a fresh decoder. A fresh repair/catch-up can explicitly discard a conversation's pending assembly.

## State and concurrency model

No jobs, flows, UI state or I/O. Synchronous calls are confined to the owning connection collector, with no suspension points. An injected monotonic millisecond supplier enables deterministic lifetime testing. The later consumer must call expiry on a timer while idle and abandonment when the socket closes; those wiring changes are outside this ticket. Process death drops transient buffers naturally.

## State transitions and identity reuse

| Event | Expected result and test |
| --- | --- |
| First part and consecutive continuation | Pending until exactly one complete update; `multipartRecoversOriginalPayload` |
| Duplicate, reordered or missing middle part | Discard, repair, no logical value; `sequenceFailuresNeverComplete` |
| Missing final / slow trickle | Absolute deadline repairs, reclaim budget; `missingFinalExpiresWithoutSlidingDeadline` |
| Reused item/update ID on another host or connection | Separate decoder state; `sourcesNeverCombine` |
| Reused identity after completion | New complete fact may decode; store owns replay/base-revision application; `completedReplayIsDecodedWithoutApplication` |
| Metadata, epoch, type or base-presence changes | Discard and repair; `repeatedMetadataMustMatch` |
| Ordinary update interrupts pending | Repair without partial/second update; `ordinaryDuringAssemblyRepairs` |
| Expiry of another conversation during decode | Report both outcomes with correct owners; `expiryReportsOwningConversation` |
| Connection abandonment and reactivation | Clear all, reject late parts; new instance starts empty; `abandonmentCannotReviveBuffers` |
| Fresh catch-up discards pending | Release only that conversation; `discardReleasesOnlyOwner` |

## Error handling

Eight static rejection categories: malformed, metadata, sequence, length, capacity, integrity, timeout and abandoned. Unsupported envelope types are malformed. Malformed JSON/DTO exceptions are discarded unread and never attached to outcomes. Repair outcomes contain no logical update/revision/version. Debug-only structured lifecycle/error diagnostics use `RelayLog` with static event/code and byte counts; no host strings, conversation text, data, summaries, JSON or exception messages. DTO/outcome string representations redact their contents.

## Testing strategy

Write failing JVM tests first, then implement. Adapt the merged daemon ordinary fixtures (open kinds/status, optional attribution, unknown nested JSON, explicit clears and empty append) into `ThreadUpdateDecoderTest`. Construct independently hashed multipart fixtures at UTF-8 boundaries with escaped Unicode/NUL, retaining the original serialized payload for exact JSON equality. Test all three types, required fields/token types/ranges, metadata disagreement including final decoded metadata, malformed continuation, digest/JSON corruption, sequence/offset faults, nonempty data, truncation/overflow, payload/aggregate/count/part/deadline limits, release after each terminal path, interleaved conversations and cross-source isolation. Probe each issue invariant with production decoder calls, including empty and one-part payloads, duplicate/replayed parts, reconnect between parts and colliding identities. Keep application out of these tests: the decoder never mutates a store.

Run focused `ThreadUpdateDecoderTest` and existing `MobileWireCodecTest`, plus lint, assembleDebug, Spotless and the final pre-verify compile gate. No screen/device/live scenario: supplied-input data-layer work only, production activation/live proof belongs to #1988.

## Open Questions

None. Rework forecast: approximately 1450–1550 written lines including plan and tests, three production files, five internal top-level consumer types, zero existing consumer migrations, four acceptance criteria and eight classified rejection branches. Recount before handoff. No overlapping in-flight branches touch the proposed files.

## Security review

**Verdict:** PASS

- [Trust boundaries] The validated factory and assembler completion are the only admission boundaries. Ordinary admission first counts compact JSON bytes iteratively with early exit, no encoded copy and storage proportional to nesting depth; deeply nested objects/arrays cannot overflow recursive serialization before bounds. Continuation completion validates original serialized grammar and builds containers iteratively, before primitive-leaf `MobileJson` decoding can erase unescaped-control evidence; recursive nested-array parsing is excluded. Legal escaped controls and unknown numeric tokens remain exact. Required token shapes, whole-tree JSON literal grammar and decoded metadata checks prevent coercion or partial authority. Retained digest lookup identifies the original repair owner even when a later part changes or omits its route. Raw retained content remains explicitly inert; rendering and item applicability are later-store work.
- [Tokens, secrets and credentials] No credentials are generated or stored. Host/connection identities and all DTO/outcome content are excluded from diagnostics; string representations redact payloads.
- [Files and storage] No filesystem use, cache writes or daemon-derived paths. Decrypted buffering is ephemeral and discarded on terminal paths.
- [Android attack surface] No new components, intents, providers or WebViews. UI leakage/rendering remains #1988 and its consumers.
- [Cryptography] Standard SHA-256 provides protocol integrity only, not authentication. Existing Noise authentication is unchanged; the digest includes type and NUL exactly as specified.
- [Network and I/O] Bound aggregate bytes, assembly count, part count and absolute lifetime before retaining input. Enforce the combined metadata-plus-fragments per-assembly budget, rather than independent allowances; inclusive-bound and failure-cleanup probes pin the charged storage contract. Strict original-syntax work only runs after bounded buffering and digest completion. Existing transport limits are unchanged; no sockets or TLS changes.
- [Errors, logs and telemetry] Static codes only. Discard parser exceptions unread, redact DTO/outcome string representations, and test the capturing diagnostic sink for content leakage.
- [Concurrency] Synchronous connection-confined state, no jobs or locks. Explicit timer/abandonment integration is later consumer work; tests prove the cleanup API and absolute deadline.
- [Threat model] Relay delay/drop/reorder and hostile authenticated payloads yield bounded pending or repair outcomes. Disk token theft and UI leakage are outside this in-memory codec and remain with existing key storage and #1988 presentation. No new plaintext persistence.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

## Revisions

- 2026-10-10: Adversarial probes `changedRoutingRepairsOriginalOwnerAndDiscardsItsBuffer` and `malformedRoutingStillDiscardsIdentifiableAssembly` initially failed because conversation-keyed lookup repaired the supplied route rather than the pending owner. Resolve a retained update digest to its original conversation before admitting routing or reporting malformed metadata, discard that owner's buffer, and emit no complete update. Different payload digests remain independent across conversations. This tightens the trust-boundary and owning-conversation cleanup findings in the security review.

- 2026-10-10: `invalidJsonNumbersInsideUnknownContentAreRejected` and `validDigestCannotAdmitInvalidOriginalJsonNumbers` exposed that `MobileJson` accepts numeric token `00` inside unknown content, even with a matching multipart digest. Validate JSON numeric/literal grammar throughout the retained tree before DTO admission. Valid unknown numeric tokens, including exponents beyond floating-point range, remain verbatim; `validUnknownJsonNumbersRetainTheirOriginalTokens` pins this. No global `MobileJson` behavior changes.

- 2026-10-10 (verifier findings 1–3): `hostileNestingNeverUsesRecursiveSerialization` reproduced `StackOverflowError` on 6000 nested objects; `correctlyHashedUnescapedControlsNeverComplete` admitted raw controls; `combinedMetadataAndPayloadLimitIsInclusiveAndReclaimed` admitted independently bounded storage exceeding its combined limit. All three failed before repair. Replace ordinary recursive serialization with bounded iterative byte counting before metadata/DTO admission, validate original multipart syntax iteratively before tree parsing, and enforce combined retained metadata/fragment bytes before writes. `ordinaryByteBoundCountsEscapesAndUtf8Exactly` and `legalEscapedControlsRetainExactContent` guard valid inclusive bounds and unchanged escaped data. Security review above audits hostile depth, pre-bound admission work, original syntax and combined retained capacity.

- 2026-10-10 (nesting follow-through): `multipartNestingUsesStackSafeAdmission` found another `StackOverflowError` in `MobileJson`'s nested-array parser after strict syntax validation. Build JSON containers iteratively in the same strict parser and use the configured codec only for primitive leaves. This preserves valid 6000-level objects and arrays without adding a depth restriction; the probe and `originalJsonGrammarIsStrictWithoutCoercingUnknownValues` pin nesting, structural grammar and verbatim unknown number tokens. Ordinary fixtures construct their trees iteratively so fixture parsing cannot hide the decoder regression.

- 2026-10-10 (verifier finding 4): The unchanged focused first-draw device method failed on both this branch and clean `origin/main` at `d3ecf8775`, one executed/one failed/no errors or skips each. Main fragmented first-open committed draws were 1732 ms offline and 1632 ms held-newest against 1000 ms; fresh XML/logcat are preserved under `/tmp/builder-2042/device-baseline/`. Existing open #2039 / PR #2041 owns the diagnosed main gate failure. Preserve the bound and fixtures; this supplied-input decoder ticket does not repair the unrelated restore/render path.

- 2026-10-10 (HP recovery, verifier finding 4): Escalated host KVM probe passed. After merging #2039, the unchanged focused first-draw method still failed on feature `b15ed69cc` (2024/1721 ms fragmented first opens) and clean main `c88a07d67` (1630/1388 ms); each run executed one test, failed one, and skipped none. Fresh XML/logcat, commands, revisions and checksums are retained under `/tmp/builder-2042/recovery-20261010/`. Open #2050 owns this residual main failure and blocks #2042. Preserve the 1000 ms assertion and supplied-input scope; the 59 decoder/wire regressions pass. The initial D8 heap-exhaustion attempt executed zero tests; counted comparisons use a command-scoped 4096 MiB Gradle heap and two workers. No product/build configuration or decoder design changed.

- 2026-10-10 (post-#2050 rework verification): Main repair #2050 / PR #2066 is merged at `b19246aee`. The unchanged named first-draw device method now executes once and passes once, with zero failures/errors/skips; all eight committed draws meet 1000 ms (202–708 ms), including fragmented first opens at 708 ms offline and 590 ms held-newest. Fresh decoder/wire coverage passes 59/59, including each of the four named admission regressions. FIFO-coordinated escalated device command, XML, full logcat, counts and checksums are retained under `/tmp/builder-2042/resume-20261010/` and the publishing evidence folder `builder-2042/evidence/resume-20261010/`. No decoder design, production code, test fixture or assertion changed; full dispatcher UI/scripted gates and final review remain pending.

- 2026-10-10 (scripted rework findings 1–2): Fresh escalated FIFO-coordinated `scripted ping` fails both unchanged methods on feature `65252ff1e` and clean main `427d0594e`, with daemon `b75aea794`: each run exits 1 with 2 executed, 2 failed and zero errors/skips. `DurableGapProof.catchUp` reaches fixture recovery then times out waiting for marker closure; the original ping renders then lacks a read checkpoint. The isolated seed histories contain `send_delivered` at durable ID 8 between user ID 7 and reply ID 9. Daemon `queuedSendHistory` appends these facts, but `legacyHistoryType` and `legacyRuntimeReceipt` exclude them from legacy live/page accounting, leaving receipt holes that mobile correctly refuses to cross. Open daemon [pyrycode/pyrycode#3167](https://github.com/pyrycode/pyrycode/issues/3167) owns this compatibility repair and is linked as #2042's blocker. Preserve all assertions and decoder scope. Fresh XML, logcat, exact commands, revisions, counts, checksums and content-free stored-ID diagnosis are retained under `/tmp/builder-2042/ping-rework-attempt2-20261010/` and publishing `builder-2042/evidence/ping-rework-attempt2-20261010/`; decoder/wire coverage remains 59/59. No decoder design or production code changed; both scripted findings remain unfixed pending the daemon repair, then focused and full dispatcher gates must run again.

- 2026-10-11 (post-#3167 rework verification): Daemon repair `b06a78b6` / PR #3168 is merged at `a3ca005f`. Fresh escalated FIFO-coordinated `scripted ping` on Mobile `2e74f51af` with that daemon and relay `a7a05d1f` exits 0: both `interactiveTurn_seededChannel_durableGapCatchUp` and `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` execute and pass, 2/2 with no failures/errors/skips. Fresh focused first-draw coverage also passes 1/1; all eight unchanged 1000 ms bounds pass (164–682 ms). Decoder/wire coverage passes 59/59, including each of the four admission regressions. XML, full logcat, exact commands, revisions, counts and checksums are retained under `/tmp/builder-2042/resume-20261011/` and publishing `builder-2042/evidence/resume-20261011/`; numbered answers identify the pushed daemon fix. No Mobile implementation, fixture, assertion or decoder design changed. Fresh full dispatcher UI/scripted gates and final review remain pending and must use daemon `a3ca005f` or a descendant containing #3167; the repaired isolated checkout is `/tmp/builder-2042/daemon-3167-20261011`, while the live source checkout remains at `d49b8392`.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`, supplied thread updates/assembly guidance: describe retained JSON/patch presence, complete/pending/repair outcomes, combined byte limits and strict stack-safe admission. Record the original-syntax and metadata-budget lessons.
- Pending documentation stage: same topic, consumer lifecycle guidance: tick expiry while idle, abandon on connection teardown, discard pending buffers on fresh catch-up; application/base-revision checks belong to the store, page integration to #2040, activation/live proof to #1988.
