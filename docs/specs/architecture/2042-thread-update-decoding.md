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

`ThreadItemDto` retains the entire original object and exposes the required fields plus nullable recorded optional facts. `ThreadUpdateDto` retains the entire original payload and envelope type, routing/version, item identity/revision/base revision, and the type-specific item, whole changes object or text suffix. A validated factory admits only the three known envelope types and exact required token shapes. IDs, orders, revisions and versions accept canonical integer tokens from zero through `9007199254740991`; no string/boolean coercion. Optional full-item fields retain absence without inventing attribution. Content is any required JSON value, including null. Unknown item fields and nested content remain inert. Changes retain their JSON object without interpreting or merging values; immutable-field applicability and base-revision checks belong to the store.

`ThreadDecodeOutcome<T>` distinguishes complete values, pending conversation assembly and repair (owning conversation, when recoverable, plus a static code). Decode returns a list because expired conversations may need repair alongside the current frame. Only complete outcomes expose logical DTOs.

`ThreadPayloadAssembler` is generic over the completed value via decoding and routing-metadata callbacks, so #2040 can reuse the byte assembly without introducing page shapes here. A decoder/assembler instance is bound to one caller-supplied host and connection generation; instances never share state. Private identity includes envelope type, conversation, epoch, update ID and repeated logical metadata. Keep one pending assembly per conversation: a conflicting logical identity repairs and discards that conversation rather than replacing its unfinished update. Independent conversations may interleave.

Detect the presence of `continuation` before ordinary decoding, including malformed/null continuation objects. Validate nonempty UTF-8 data with no unpaired surrogate, canonical progress integers, required boolean final, lowercase 64-digit update ID, exact consecutive index/offset, identical repeated metadata and base-revision presence. On final, require exact byte count, SHA-256(type + NUL + payload bytes), valid original JSON/DTO and matching decoded metadata. Data fragments never become item fields or ordering facts. An ordinary frame arriving during pending assembly repairs the interrupted conversation without emitting either update.

Defaults: 8 MiB per logical payload, 16 MiB aggregate buffered bytes, 32 pending conversations, 4096 parts per update, and 30 seconds absolute lifetime from the first part. Limits are injectable for deterministic boundary tests, must be positive, and are enforced before buffering. Count actual buffered UTF-8 bytes, including repeated metadata retained per assembly within the logical byte bound. Completion/failure/expiry releases all charged storage. Missing final is repaired by explicit `expire()` calls or the next decode. `abandon()` clears and permanently closes this connection-scoped instance; a new connection requires a fresh decoder. A fresh repair/catch-up can explicitly discard a conversation's pending assembly.

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

None. Forecast: approximately 1000–1300 written lines including plan and tests, three production files, five internal top-level consumer types, zero existing consumer migrations, four acceptance criteria and eight classified rejection branches. Recount before handoff. No overlapping in-flight branches touch the proposed files.

## Security review

**Verdict:** PASS

- [Trust boundaries] The validated factory and assembler completion are the only admission boundaries. Required token shapes, finite bytes and exact metadata checks prevent coercion or partial authority. Retained digest lookup identifies the original repair owner even when a later part changes or omits its route. Raw retained content remains explicitly inert; rendering and item applicability are later-store work.
- [Tokens, secrets and credentials] No credentials are generated or stored. Host/connection identities and all DTO/outcome content are excluded from diagnostics; string representations redact payloads.
- [Files and storage] No filesystem use, cache writes or daemon-derived paths. Decrypted buffering is ephemeral and discarded on terminal paths.
- [Android attack surface] No new components, intents, providers or WebViews. UI leakage/rendering remains #1988 and its consumers.
- [Cryptography] Standard SHA-256 provides protocol integrity only, not authentication. Existing Noise authentication is unchanged; the digest includes type and NUL exactly as specified.
- [Network and I/O] SHOULD FIX implemented by the design: bound aggregate bytes, assembly count, part count and absolute lifetime before retaining input. Existing transport limits are unchanged; no sockets or TLS changes.
- [Errors, logs and telemetry] Static codes only. Discard parser exceptions unread, redact DTO/outcome string representations, and test the capturing diagnostic sink for content leakage.
- [Concurrency] Synchronous connection-confined state, no jobs or locks. Explicit timer/abandonment integration is later consumer work; tests prove the cleanup API and absolute deadline.
- [Threat model] Relay delay/drop/reorder and hostile authenticated payloads yield bounded pending or repair outcomes. Disk token theft and UI leakage are outside this in-memory codec and remain with existing key storage and #1988 presentation. No new plaintext persistence.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

## Revisions

- 2026-10-10: Adversarial probes `changedRoutingRepairsOriginalOwnerAndDiscardsItsBuffer` and `malformedRoutingStillDiscardsIdentifiableAssembly` initially failed because conversation-keyed lookup repaired the supplied route rather than the pending owner. Resolve a retained update digest to its original conversation before admitting routing or reporting malformed metadata, discard that owner's buffer, and emit no complete update. Different payload digests remain independent across conversations. This tightens the trust-boundary and owning-conversation cleanup findings in the security review.
