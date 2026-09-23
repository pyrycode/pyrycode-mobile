# #898 — Observe attachment offers in a conversation on its owning host

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`, the `TYPE_*` constants in its companion, the projection fields (`compactingProjection`, `announcedModelProjection`) — where the new arm and projection field go.
- `app/src/main/java/de/pyryco/mobile/data/repository/CompactingProjection.kt`, `AnnouncedModelProjection.kt` → the per-conversation projection shape (`MutableStateFlow` map, `apply`, `observe` with `distinctUntilChanged`, decode-or-null) the new projection mirrors.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeCompacting`, `observeAnnouncedModel` and the `AnnouncedModel` domain type: defaulted interface member plus a small `data class` next to its siblings.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`: the facade that binds a thread to its owner host's live repository and drops the previous connection's projection on a switch.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → implements the interface `by delegate`, so it forwards the new member with no edit.
- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `AttachmentChunkPayloadDto` (id-and-position-only `toString`), `ATTACHMENT_TEXT_MAX_BYTES`, `truncateUtf8`.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentUpload.kt` → `AttachmentUploadTransfer.accept` only claims `attachment_stored` / `error`, so `routeAttachmentUpload` never swallows an offer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt`, `UsageLimitIndicator.kt` → the character classes to drop (`isISOControl`, `Character.FORMAT`).
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → debug-gated, content-free logging.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryRunReadingsTest.kt` → the `FakeSessionPump` + `probe` harness the new repository test copies.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt` → `RecordingConversationRepository` and the #596 whileAbsent / no-leak-across-switch pair.
- `../pyrycode/docs/protocol-mobile.md` § Attachments → `attachment_offered` and § The `attachment_id` shape — the wire contract, cited not restated.

## Design source

**Figma:** N/A — data-layer only; rendering an offer in the thread is #672.

## Context

The daemon broadcasts `attachment_offered` when `claude` produces a file; mobile ignores it today. This ticket decodes it into a per-conversation list a thread can observe on its owner host's repository. Fetching bytes is a sibling ticket; rendering is #672. `filename` is the first `claude`-authored string on this wire that later reaches a file name and a share sheet, so it is cleaned at decode.

## Design

**Domain type** (in `ConversationRepository.kt`, beside `AnnouncedModel`):
`data class AttachmentOffer(val attachmentId: String, val displayName: String)` — `attachmentId` is a validated lowercase UUIDv4; `displayName` is the cleaned name. `toString` names only the id, so an accidental log of the type cannot leak the name.

**Interface member:** `fun observeAttachmentOffers(conversationId: String): Flow<List<AttachmentOffer>> = flowOf(emptyList())`. Defaulted so the fake and every test double compile unchanged.

**Wire helpers** (in `AttachmentPayloads.kt`):
- `@Serializable data class AttachmentOfferedPayloadDto(conversation_id, attachment_id, filename)` — all three required (protocol: always present); `toString` omits `filename`.
- `internal fun isAttachmentIdShape(value: String): Boolean` — exactly the published lowercase-UUIDv4 rule: 36 chars, `-` at 8/13/18/23, `4` at 14, one of `89ab` at 19, lowercase hex elsewhere. Applied to both ids, since the protocol states conversation ids share the shape.
- `internal fun attachmentDisplayName(raw: String): String` — drops every code point that is ISO control, `Character.FORMAT` (bidi overrides and isolates, zero-width joiners, tag characters in the supplementary plane), a line or paragraph separator (U+2028 / U+2029, which Compose renders as a line break yet are neither control nor format), or an unpaired surrogate, then `truncateUtf8(…, ATTACHMENT_TEXT_MAX_BYTES)`. Iterates by code point, not `Char`, because a supplementary-plane format character is two surrogate `Char`s whose own type is `SURROGATE`. Dropping rather than replacing with a space: this is a file name, not prose, and a space-substituted escape sequence leaves the same visible residue either way. An empty result is kept — the id is what fetches the file; #672 chooses the fallback label.

**Projection** — new `data/repository/AttachmentOfferProjection.kt`, `internal class AttachmentOfferProjection`, one per repository (so per connection, per host):
- State: `MutableStateFlow<Map<String, List<AttachmentOffer>>>`, written only from the single inbound collector.
- `fun apply(envelope: Envelope)` — decode; drop on a decode failure or a non-conforming id; otherwise append to the conversation's list unless that conversation already holds the attachment id (first arrival wins: a re-announcement neither moves nor renames the entry).
- `fun observe(conversationId: String): Flow<List<AttachmentOffer>>` — `map { it[conversationId].orEmpty() }.distinctUntilChanged()`.

**Routing** — `RemoteConversationRepository.onInbound` gains `TYPE_ATTACHMENT_OFFERED -> attachmentOfferProjection.apply(envelope)` and a `TYPE_ATTACHMENT_OFFERED = "attachment_offered"` constant; `observeAttachmentOffers` overrides to the projection. **Not gated on `interactive`**, unlike the status events: the protocol delivers the frame to every attached client and publishes it outside the interactive family, the same posture as the upload leg's `attachment_stored`. Filtering on `conversation_id` happens in `observe`, as the protocol requires of the consumer.

**Facade** — `StableConversationRepository.observeAttachmentOffers` = `switchToLive(emptyList()) { it.observeAttachmentOffers(conversationId) }`, so a reconnect or host switch drops the previous connection's offers (live-only, matching the protocol).

**Host isolation** is structural: each host has its own coordinator, its own connection-scoped `RemoteConversationRepository` and its own facade, so an offer on H's pump only ever reaches H's projection.

## State + concurrency model

One `MutableStateFlow` per projection, mutated with `update` from the repository's single inbound collector coroutine (no second writer). Cold `observe` flows over it; no new jobs, no new scopes, no dispatcher. Lifetime is the connection: a fresh repository per connection starts empty. Persisting offers across reconnect or process death is out of scope (#672).

## Error handling

Every failure is a silent per-envelope drop that leaves the collector running: kotlinx `SerializationException` (⊂ `IllegalArgumentException`) is caught and discarded without its message, which can quote input. A non-conforming id is dropped. No failure reaches UI state.

Logging: after both ids validate, `RelayLog.d { "event=attachment_offered id=<attachmentId>" }`. A drop logs a static `event=attachment_offered outcome=dropped` with no id (an unvalidated id is not loggable). The filename and conversation id are never logged.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`):
- `AttachmentPayloadsTest` (extend): id shape accepts the protocol's example and rejects uppercase, a v1 version nibble, a wrong variant nibble, 35/37 lengths, misplaced hyphens, a path-traversal string and `""`; display name drops an `ESC[31m…` sequence's `ESC`, a right-to-left override U+202E, bidi isolates, a zero-width joiner, a supplementary tag character and an unpaired surrogate; a 300-byte multi-byte name is cut to ≤255 UTF-8 bytes at a code-point boundary.
- New `RemoteConversationRepositoryAttachmentOfferTest` (FakeSessionPump harness): empty until a frame; offers in arrival order; a duplicate id keeps one entry at its first position; another conversation's observer sees nothing; a second repository instance (another host) sees nothing; an invalid conversation id, invalid attachment id, missing field and wrong-typed field are each dropped and a following valid offer still lands; a following `compacting` frame is still applied (drop affects no other inbound frame); a hostile name with an escape sequence and a right-to-left override is exposed cleaned.
- `StableConversationRepositoryTest` (extend): emits empty while absent; delegates to the live repository and does not leak across a switch.

No Compose UI test and no e2e scenario: nothing operator-facing ships here (the render is #672).

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the new `observeAttachmentOffers` reading belongs in `docs/knowledge/features/conversation-repository.md` and the `attachment_offered` arm in `docs/knowledge/features/remote-conversation-repository.md`.

## Open questions

- None blocking. The unbounded per-connection list mirrors the protocol publishing no count bound and the thread store's own posture; revisit only on an observed flood.

## Security review

**Verdict:** PASS (after one revision: line and paragraph separators added to the dropped classes before commit)

**Findings:**

- [Trust boundaries] No findings — one boundary: `AttachmentOfferProjection`'s decode, which runs the DTO through `isAttachmentIdShape` (both ids) and `attachmentDisplayName` before anything is stored. Downstream code only ever holds `AttachmentOffer`. Its KDoc carries a SECURITY note in the `AnnouncedModel` style: `displayName` is `claude`-authored even after cleaning, so it is inert text only — never a path or path component, never a viewer or MIME choice from its extension, never a cache key or log line. The fetch sibling and #672 inherit that rule from the type.
- [Trust boundaries] Revised in-plan — U+2028 / U+2029 are neither ISO control nor `Character.FORMAT` but break a line in Compose; now dropped too. Supplementary-plane format characters (tags) and unpaired surrogates are handled by iterating code points, not `Char`s.
- [Tokens, secrets] No findings — the frame carries no secret; the attachment id is explicitly not a capability (protocol § `attachment_offered`), and nothing here stores, compares or mints a token.
- [File / storage] No findings — nothing touches the filesystem. The name is never joined into a path here, and offers are in-memory and connection-scoped (persistence is #672's decision).
- [Android attack surface] No findings — no intent, deep link, provider, push or WebView is added.
- [Cryptographic primitives] No findings — none used.
- [Network & I/O] No findings — the frame arrives inside the existing Noise session through the existing pump and envelope cap; no new socket, timeout or URL.
- [Network & I/O / hostile daemon] OUT OF SCOPE — a daemon flooding unique offers grows the per-connection list without bound (and `distinctUntilChanged` compares whole lists). The protocol publishes no count bound and the thread store has the same posture; not observed. Revisit with a cap only on an observed flood.
- [Logs] No findings — only a shape-validated attachment id and static codes are logged, via debug-gated `RelayLog`; the conversation id and the filename never are. The DTO's and `AttachmentOffer`'s `toString` omit the name, and a caught decode exception is discarded because kotlinx can quote input in its message.
- [Concurrency] No findings — one writer (the inbound collector) using `MutableStateFlow.update`; no new coroutine or scope. Observers get a cold per-conversation projection, so no cross-conversation leak; the facade's `flatMapLatest` drops a previous host's or connection's offers.
- [Threat model] No findings beyond the above — a hostile relay can drop an offer (live-only, accepted by the protocol) but cannot forge one inside the Noise session; a hostile daemon frame is decoded defensively and exposed as bounded, cleaned text.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
