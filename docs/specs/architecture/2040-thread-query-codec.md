# #2040: correlated thread catch-up and page codec

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt`: `Envelope`, `ErrorPayload`; preserve existing consumers and correlate errors without a conversation.
- `app/src/main/java/de/pyryco/mobile/data/network/SessionProvenance.kt`: raw omitted/null/session presence must survive routing comparisons.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadUpdatePayloads.kt`: `ThreadItemDto`, `boundedThreadJsonSize`, `parseThreadJson`, `validThreadJson`; reuse full-item validation and stack-safe admission.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadPayloadAssembler.kt`: `accept`, `expire`, `abandon`; retain combined metadata/fragment budgets and strict original JSON/digest validation.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadUpdateDecoder.kt`: live decoder API and continuation rejection stay unchanged.
- `app/src/test/java/de/pyryco/mobile/data/network/ThreadUpdateDecoderTest.kt`: existing admission, unknown JSON and lifecycle regressions remain in focused checks.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: primitive token checks, iterative size/parsing and non-sliding assembly lifetime are load-bearing lessons.
- Daemon `docs/protocol-mobile.md`: “Thread query messages”, “Full thread item”, “Bounded thread encoding and assembly” and “Security model” are authoritative.
- Daemon `internal/protocol/thread_query.go`, `thread_query_test.go`: request normalization, separate terminal markers, unsigned checkpoints and variable envelope metadata fixture precedents.
- Daemon `docs/knowledge/decisions/042-daemon-built-thread.md`: daemon supplies facts; clients own presentation and query scheduling.

## Context

Supply fixture-proven encoding and correlated reply decoding for #1984. Scheduling, retries, merging, cache writes and checkpoint application belong to #1984; advertisement, activation and live proof belong to #1988. No UI changes, new dependency or decision record. No in-flight branch overlaps the planned files.

## Design

Add `ThreadQueryPayloads.kt` and `ThreadQueryDecoder.kt`. Three new top-level internal consumer types: sealed request, sealed reply fact and decoder; coverage and concrete outcomes are nested. Make `validThreadJson` internal for shared strict tree admission; change no existing consumer API.

Requests distinguish catch-up (epoch/version) from page (exclusive before-order). Encode canonical unsigned 64-bit JSON numbers without passing through signed Long or Double. Preserve optional limit presence, normalize positive values to 256, retain zero as default-50 and reject negatives even for nonzero catch-up. Expose window row limit semantics without imposing any item-count cutoff.

Reply facts carry raw payload, captured epoch/version and complete ordered `ThreadItemDto` lists, with separate success coverage, reset and existing `ErrorPayload` failure variants. Preserve unknown JSON and field presence. Validate error optional fields manually and retain raw fields, without broadening the shared error model. Only successful empty markers expose coverage: exact requested nonzero from-version with no page fields, or all four numeric/boolean page fields with equal continuation/lower and lower <= upper. Full items and out-of-range parents cannot affect coverage.

Use one decoder per host, connection generation, request id and typed request; consumers dispatch envelopes to that instance and serialize calls. Public attribution is immutable and applies to all returned outcomes. First valid reply captures envelope conversation/session/clear metadata and checkpoint, compared on every subsequent batch/part/marker. Payload conversation must equal request; catch-up epoch must match except terminal reset. A changed/missing correlation poisons that instance rather than allowing later success. Envelope metadata must be primitive, and is included in assembly metadata and its combined storage charge. No envelope id/timestamp equality requirement.

Object continuation goes through existing assembler callbacks; numeric continuation is admitted only by the separately validated empty success-marker path. Multipart metadata verifies sole item identity/revision after decoding the complete logical batch. Markers require no pending assembly. No batches are accumulated in the decoder, so total replies can exceed limits without retaining full query contents. Expose batches as facts, never request completion.

## State and concurrency model

Synchronous request-confined mutable state: captured routing/checkpoint, pending-assembly flag, sticky terminal flag and reusable assembler. No jobs, dispatchers or flows are created. #1984 owns serialized delivery, idle expiry ticks and abandon on connection closure/background. A new connection/retry creates a new instance; instances share no buffers. Abandon/discard permanently retires this request; no reset-to-success API. Per-instance assembler defaults remain 8 MiB combined storage, 16 MiB aggregate, 32 assemblies, 4096 parts and 30-second absolute lifetime; owning scheduler bounds concurrent requests.

## State transitions and identity reuse

| Event | Contract / fixture test |
| --- | --- |
| Empty/multiple ordinary batches, duplicate/reordered items | Preserve supplied sequence; `suppliedOrderIdentityAndUnknownJsonSurvive`, `emptyAndMultibatchRepliesNeedMarker`. |
| Fragment final | Batch only, not coverage; `multipartRetainsLogicalPayloadWithoutCompletingRequest`. |
| Missing middle/final then marker | Sticky repair; `interruptedAssemblyCannotBeRescuedByMarker`. |
| Malformed/digest/identity/metadata failure then later frames | Never success; `failedPartsCannotBeRescuedByLaterFrames`. |
| Correlation/routing/session/checkpoint changes | Retire request; `changedAttributionCannotCompleteReply`. |
| Explicit error/reset then later success | Distinct terminal outcomes; `errorWithoutConversationRemainsAttributed`, `resetNeverCertifiesCoverage`. |
| Expiry during decode / idle expiry / slow trickle | Absolute deadline and sticky failure; `expiryCannotBeRescuedByMarker`. |
| Socket close / background / retry / reused request id on new connection | Abandon old instance, fresh isolated decoder; `abandonmentAndCrossConnectionIsolation`. |
| Success then duplicate marker/batch | Retired, no repeated certification; `terminalSuccessIsEmittedOnce`. |
| Process death/configuration change/recollection | Codec owns no durable/lifecycle state; scheduler creates a fresh request instance; same isolation test. |

## Error handling

Content-free `ThreadDecodeOutcome.Repair` for malformed, attribution, coverage, sequence, capacity, length, integrity, timeout, abandoned or terminal frames; no parser exception text. Errors and resets are complete decoded terminal facts but contain no successful coverage. No UI error, checkpoint or cache changes here. All terminal paths discard bytes; error details remain inert, bounded payload data.

## Testing strategy

Write failing unit fixtures first under `app/src/test/java/de/pyryco/mobile/data/network/ThreadQueryDecoderTest.kt`, trace fixtures to daemon #3233 / `thread_query_test.go`. Cover both request shapes, default/clamped/invalid limits, unsigned zero/maximum and invalid tokens, strict required fields and coverage presence, unknown item JSON and supplied duplicate/reordered arrays, complete multipart payload, malformed indices/offsets/digests/identity/metadata, changed routing/correlation, reset/error, expiry/abandonment and later-frame rescue attempts. Probe all four ticket invariants with production decoder calls, including parents outside coverage and interleaved isolated decoders. Exercise 6000-level ordinary/multipart nesting and combined metadata/fragment budgets. Run query tests plus existing `ThreadUpdateDecoderTest` and `MobileWireCodecTest`, lint, assembleDebug, Spotless and final pre-verify Kotlin checks; record fresh XML executed/failed/skipped counts. No shared/device tests or live scenario: this codec has no production wiring; #1988 owns activation/proof.

## Open Questions

None. One request per instance is the correlation boundary; scheduler concurrency is outside this additive codec.

## Security review

**Verdict:** PASS

- [Trust boundaries] `ThreadQueryDecoder.decode` admits bounded strict JSON into structurally validated facts. MUST FIX addressed in design: poison request on interruption/mismatch so later marker cannot certify partial results. Unknown strings/content stay inert and are never interpreted.
- [Tokens] No token generation/storage/change. Decoder and reply/request string representations redact data; existing `ErrorPayload` is exposed only as inert typed data, never logged.
- [Files/storage] No persistence, paths or filenames. Cache writes and their at-rest contract belong to #1984.
- [Android attack surface] No components, intents, providers, WebViews or UI introduced.
- [Cryptography] Reuse assembler SHA-256(type + NUL + bytes) for public integrity, not authentication; Noise remains unchanged. No secret comparison or key material.
- [Network/I/O] Existing transport cap unchanged. MUST FIX addressed: bounded iterative admission before decoding, envelope metadata charged together with fragments; absolute expiry and abandonment cannot be revived. Concurrent request scheduling/bounds belong to #1984.
- [Errors/logs] Static lifecycle/reject codes only, no routing text, error message, item payload or parser exception. Redacted string representations on all new types.
- [Concurrency] Serialized synchronous API and immutable attribution; no shared buffers or jobs. Calls from different hosts/connections use distinct instances, old instance abandoned permanently.
- [Threat model] Relay drop/delay/reordering yields no certified partial reply. Hostile daemon syntax/depth/size/attribution fails closed. Token theft and UI screenshot/accessibility/keyboard exposure are outside this codec; existing Keystore/pairing and later rendering stages own them. Capability/authorization/production activation remain #1988.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12
