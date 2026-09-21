# Retain workspace labels in conversation records (#720)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — `Conversation`: append a nullable default without changing existing positional arguments.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` — `ConversationSummaryDto`, `toConversations`: list rows map independently, including archived rows.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` — `ConversationResponseDto`, `toConversation`: shared create/update response mapping.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — `onInbound`, `createDiscussion`, `changeWorkspace`, `upsertConversation`: snapshots replace records; correlated successes insert or replace complete mapped records.
- `app/src/test/java/de/pyryco/mobile/data/model/ConversationTest.kt` — `sample`: existing constructor compatibility.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationsPayloadTest.kt` — `twoRowFixture`: list order, archive flag and legacy defaults.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationResponseDtoTest.kt` — `createdFixture`, `updatedFixture`: both response shapes and existing field assertions.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — `startCreate`, `startChangeWorkspace`, `collectConversations`: fake-pump correlated-fold proof.
- `docs/knowledge/features/data-model.md` § Types — trailing defaults avoid constructor and fixture cascades.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` § Application payloads — use `MobileJson`; decode is the validation boundary and mappers are pure.
- `docs/knowledge/features/remote-conversation-repository.md` and its reads-and-thread-store / conversation-writes topics — complete-record upsert and server-authoritative destination cwd.
- `docs/knowledge/features/development-verification.md` § Gradle and source checks, JVM logging and formatting, Archive refresh regression — focused checks and archived-list preservation.
- `../pyrycode/docs/protocol-mobile.md` § Application message types and Security model, read from the canonical sibling checkout — upstream #2208/#2210 already supply the field.

## Change

Add trailing `workspaceLabel: String? = null` to `Conversation`, `ConversationSummaryDto` and `ConversationResponseDto`; annotate the DTO fields with `@SerialName("workspace_label")`. Both mappers copy it verbatim alongside the existing cwd. Explicit null and absent legacy fields become null. Other field mappings retain their behavior. This implements one retained-data contract for #721 and later display consumers; it adds no screen, unsolicited routing, outbound verb, dependency or filesystem use.

The existing repository snapshot assignment and successful `upsertConversation` already carry the complete record, including a null that replaces a prior label. They need no production edits. State remains in the connection-owned projection with existing cold observers and scope cancellation; no new coroutine, dispatcher, mutable store or failure branch is introduced. Existing decode failures follow the current repository paths. Pure mapping adds no lifecycle event or classified error to log; labels and cwd must never be added to logs.

## Size and overlap

One deliverable, two acceptance criteria, three production files, about 220 written lines including tests and this plan, zero new exported types, two mapper call sites updated and zero new reject branches. Codegraph context/impact/callers found the types but under-reported constructors; source fallback found 27 production, 20 JVM-test and four device-test constructions, all compatible with the trailing default. Neither DTO has direct constructor callers. The two mapper constructions are the only consumers requiring edits.

The #318 analogue added 180 production/test lines at `0da5ce7` and 328 plan lines at `b774b73`; this slice extends the existing types. After refreshing origin, 17 remote feature branches were checked; none overlaps the seven intended source/test files. These counts remain inside every builder boundary.

## Testing strategy

- RED: add focused assertions before production edits and run the four affected JVM classes. The new domain property must initially be absent; after introducing its default, label-retention assertions must still fail until the mappers retain the wire field.
- Model: an unchanged existing constructor defaults the label to null.
- List mapping: named active and archived rows keep their own exact label/cwd pairs, including whitespace and Unicode; explicit-null and absent legacy fields map to null. Existing field assertions continue to run.
- Response mapping: both created and updated shapes retain opaque strings, empty strings, explicit null and absent fields without changing other mapped fields.
- Repository: a correlated create retains the label in its return and observed record; successive correlated workspace changes replace the label with the destination's and then clear it with explicit null, preserving unrelated rows.
- GREEN: `testDebugUnitTest` scoped to `ConversationTest`, `ConversationsPayloadTest`, `ConversationResponseDtoTest` and `RemoteConversationRepositoryTest`; then `spotlessApply`, `lint`, `assembleDebug`. No androidTest changes or operator-facing flow: device scenarios are unnecessary for this data-only slice. The dispatcher owns full regression gates.

## Documentation handoff

The ticket has no explicit documentation-only acceptance criterion or Documentation handoff section. Pending for the documentation stage: record the nullable verbatim field and legacy default in `docs/knowledge/features/data-model.md` § Types / Conversation and `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` § Application payloads. Shared docs remain documentation-stage owned.

## Open questions

None. The ticket explicitly selects legacy omission compatibility even though current daemon records always carry the nullable key.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `MobileJson` decodes daemon-controlled text into the two DTOs, and their mappers retain it as untrusted opaque data. The field does not authorize an action or become a path, key, URL, markup or display value. Preserve it verbatim; a future display consumer must bound and render text safely.
- **Tokens:** No credentials are generated, read, stored or transmitted by this change. A label could contain sensitive text, so it must never be logged.
- **File/storage operations:** No persistence or filesystem operation is added. `cwd` retains its existing independent value; the label cannot replace it.
- **Android attack surface:** No component, intent, permission, push handler, WebView or UI change.
- **Cryptography:** Existing `Noise_IK_25519_ChaChaPoly_BLAKE2s` transport, authentication and keys are untouched.
- **Network/I/O:** No new request or frame type and no change to transport bounds, timeouts or reconnects. The server's documented label bound is not a sanitization guarantee; this retention contract adds no rendering sink or new arbitrary size cutoff.
- **Errors/logs/telemetry:** Mappers remain total after decode. Existing snapshot failure handling and correlated reply failures are unchanged; no DTO, domain-record or label logging is introduced.
- **Concurrency:** Each label travels in the same immutable record as its cwd. Existing complete-record replacement and `upsertConversation` avoid split-field updates; no new jobs or cancellation paths.
- **Threat alignment:** Relay tampering remains behind existing Noise authentication; malformed object/array values cannot decode as nullable strings. Token theft and UI-side leakage are not widened because storage and UI are unchanged. Live unsolicited label projection is OUT OF SCOPE, owned by #721; display safety belongs to #628's display-consumer slices.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
