# #549 — Wire `archive_conversation` / `unarchive_conversation` v2 messages

**Ticket:** [#549](https://github.com/pyrycode/pyrycode-mobile/issues/549) · size **s** · `security-sensitive` · split from #531
**Layer:** data only (no UI). Surfacing (#550) and the rung-3 e2e (#551) are separate siblings.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1376-1424` — the `mutationsSupported=false` flag, the two `archive`/`unarchive` throws you replace (:1379/:1382), and the shipped `rename` body (:1404-1424) that is the **exact template**: encode → `sendAndAwaitReply` → decode `ConversationResponseDto` → `upsertConversation`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:664-673` — `sendAndAwaitReply`: the write-verb path (registers deferred before send, throws `IllegalStateException` if the pump is not `Open`).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:613-634` — `mapError`: the **centralized** error mapper (`conversation.not_found`→`IllegalArgumentException`, everything else→`RelayErrorException`). The new bodies add **zero** per-error catch.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:910-919` — `upsertConversation`: the atomic `projection.update {}` fold you reuse verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:334-348` — the `onInbound` correlated-reply demux arm. `TYPE_CONVERSATION_UPDATED` is **already** in it (rename/promote use it) — **no demux change needed**.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1554-1569` — the wire-type-string `const` block; add the two new consts here next to `TYPE_RENAME_CONVERSATION`.
- `app/src/main/java/de/pyryco/mobile/data/network/RenameConversationPayloadDto.kt` — the encode-only request-DTO pattern to clone for the new id-only payload.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` (whole file, 41-73) — the shared decode boundary. **You must extend it** with `is_archived` (see Design) and wire it into `toConversation()` (currently hardcodes `archived = false` at :72).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:178-195` — the fake's `archive`/`unarchive`: `archived = true`/`false`, throw `IllegalArgumentException` (via `unknown(id)`) for an unknown conversation. The remote's not-found→IAE (through `mapError`) matches this contract.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:245-257` — the `stubMethods_throwUnsupportedOperationNamingTheFollowUp` test; the rename precedent (:1298-1470) is the test template. Drop the archive/unarchive stub assertions (see Testing).
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationResponseDtoTest.kt:67-91` — the DTO tests; `absentDomainFields_useDocumentedDefaults` (:68) asserts `archived` false and must be reframed (archived is no longer a "placeholder").
- QMD `pyrycode-docs/knowledge/codebase/881.md` — server SSOT (verbs, shared id-only payload, `is_archived` no-omitempty, idempotency, not-found, security-sensitive logging discipline).

## Design source

N/A — data-layer wire-up, no UI. The archive/restore affordances (Figma `20:48` Channel Info Sheet, `18:2` Archive Screen) are unchanged; surfacing the result lands in #550.

## Context

`RemoteConversationRepository.archive` and `.unarchive` throw `UnsupportedOperationException("no v2 wire message defined")` (:1379/:1382). Both affordances exist in the UI but the data path was never wired. The daemon shipped both verbs in one ticket (pyrycode#881, closed) — same request shape (a conversation id), same handler, same reply. This slice wires the mobile pair.

**Server SSOT (pyrycode#881, verified):**
- Verbs `archive_conversation` / `unarchive_conversation`, both v1 write verbs on the same `dispatch.Route` partition `rename_conversation` uses — i.e. the mobile `sendAndAwaitReply` write-verb path, unchanged.
- One **shared** request payload `ArchiveConversationPayload{ConversationID string}` (`conversation_id`, id-only) serves both verbs.
- Reply reuses `conversation_updated`, now extended with `IsArchived bool json:"is_archived"` — **no `omitempty`**, so it is **always present** on `conversation_updated`. #881 also patched the `rename_conversation` producer to set it, so a rename reply already carries `is_archived` too.
- Idempotent: a re-archive / re-unarchive replies `conversation_updated` with the (unchanged) state still populated — the fold must tolerate an `archived` flag already matching local state.
- Not-found (`conversation.not_found`) and malformed follow the rename precedent.
- Reply goes **only to the requester** (no live fan-out); the client folds it locally.

## Design

Three production files. This is the rename clone plus one required extension to the shared decode boundary.

### 1. New request DTO — `data/network/ArchiveConversationPayloadDto.kt`

Encode-only, id-only, **shared** by both verbs (the ticket and pyrycode#881 both made this call — a symmetric toggle of one flag, not two concerns). Clone `RenameConversationPayloadDto.kt`'s shape and KDoc discipline (encode-only, always through `MobileJson`), minus the `name` field.

```kotlin
@Serializable
data class ArchiveConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)
```

Wire SSOT: server `ArchiveConversationPayload` (pyrycode#881), single required non-pointer `string`. Name it after the pair (server naming; the file-per-class ktlint rule pins the filename to the class). Its KDoc states it serves both `archive_conversation` and `unarchive_conversation`.

### 2. Extend the shared decode boundary — `data/network/ConversationResponseDto.kt`

**This is the one departure from "mirror rename exactly," and it is load-bearing.** Today `ConversationResponseDto` does not decode any archived field and `toConversation()` hardcodes `archived = false` (:72). If left unchanged, folding an `archive` reply would set `archived = false` and the conversation would never leave the main list — the ticket's user story ("the local list reflects the new state") fails, and archive would silently no-op while unarchive accidentally worked.

Add the field, defaulted, and wire it into the mapper:

```kotlin
@SerialName("is_archived") val isArchived: Boolean = false   // after `isPromoted`, mirroring the server field order
```
```kotlin
// in toConversation():  archived = isArchived,               // was: archived = false
```

- **Defaulted (`= false`), not required.** The same DTO decodes `conversation_created` (create, #347) as well as `conversation_updated`. pyrycode#881 extended only `ConversationUpdatedPayload`; whether `conversation_created` carries `is_archived` is not guaranteed. A default keeps create/promote decoding intact (absent → `false`, the correct state for a freshly created / just-promoted conversation) and correctly reflects the real value when present. This is backward-compatible: no existing consumer of `ConversationResponseDto` / `toConversation()` changes signature, so there is **no call-site cascade** — the three existing callers (`createDiscussion`, `promote`, `rename`) are untouched.
- Update the `toConversation()` placeholder comment (:65-72): `archived` is no longer a "mutation-response-tier placeholder" — it is a decoded field defaulting to `false` when absent. `currentSessionId` / `sessionHistory` / `isSleeping` remain placeholders; only `archived` moves.
- **Latent-bug note (not new work):** because #881 made the rename reply carry `is_archived`, the shipped mobile `rename` currently drops it (folds `archived=false`). This is dormant today (nothing archives yet — archive is a throw), and this same DTO change fixes it for free. Do not add a separate rename test for it; it is covered by the DTO-level `is_archived` tests below.

Out of scope: the **list-read** path (`ConversationsPayload` / `ConversationSummaryDto.toConversation()`, which also hardcodes `archived = false`) also drops the `is_archived` pyrycode#880 added to `ConversationSummary`. That governs initial/unsolicited full-list archived state, not the archive/unarchive verb fold, and is a separate concern (mirror-#880-list-read). It is **not** #549/#550/#551 — flag it as an open question, do not touch it here.

### 3. Wire the two methods — `data/repository/RemoteConversationRepository.kt`

- Two type consts in the `:1554-1569` block, next to `TYPE_RENAME_CONVERSATION`:
  `TYPE_ARCHIVE_CONVERSATION = "archive_conversation"`, `TYPE_UNARCHIVE_CONVERSATION = "unarchive_conversation"`.
- Add the `ArchiveConversationPayloadDto` import (alphabetical).
- Replace the two throws (:1379/:1382). Both bodies are byte-identical except the wire type, so factor the common body into one **private helper** and let each override delegate (mirrors the server's "one parameterized factory registered twice"):

```kotlin
override suspend fun archive(conversationId: String) = sendArchiveToggle(conversationId, TYPE_ARCHIVE_CONVERSATION)
override suspend fun unarchive(conversationId: String) = sendArchiveToggle(conversationId, TYPE_UNARCHIVE_CONVERSATION)
```

Helper contract (behaviour, not body — clone `rename`'s :1408-1423): build an `Envelope(id=requestId.incrementAndGet(), type=<passed type>, ts=Clock.System.now().toString(), payload=MobileJson.encodeToJsonElement(ArchiveConversationPayloadDto(conversationId)))`; `sendAndAwaitReply(request)`; `MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()`; `upsertConversation(conversation)`. Interface returns `Unit`, so the decoded conversation is folded but not returned (contrast `rename`, which returns it). The decode + fold are unreachable on any failure path (send-not-Open, server `error`) exactly as in `rename`.

**Add zero logging** to the helper (see Security review). Both overrides remove their `UnsupportedOperationException`.

Data flow:

```
VM (launchGuardedRepoCall)
  └─ repo.archive(id) / repo.unarchive(id)
       └─ sendArchiveToggle(id, TYPE)
            encode ArchiveConversationPayloadDto{conversation_id}
            → sendAndAwaitReply  ──►  pump ──► relay ──► daemon (SetArchived + reply)
            ◄── conversation_updated {…, is_archived}  (correlated by inReplyTo, existing demux arm)
            → ConversationResponseDto decode  (throws here on malformed → no fold)
            → toConversation()  (archived = is_archived)
            → upsertConversation  (projection.update, atomic)
       → observeConversations(All/Channels/Discussions/Archived) re-emits with new tier
```

`mutationsSupported` stays `false` — it gates the LIVE UI affordance (#507) and the #551 e2e; archive/unarchive being wired does not change that flag (still-throwing mutations remain, e.g. `changeWorkspace`). Do not touch it.

## State + concurrency model

- No new coroutine, no new `StateFlow`. The methods are `suspend`, run in the caller's scope (the VM's `launchGuardedRepoCall`, #550's concern), and are cancellation-cooperative through `sendAndAwaitReply` (deferred registered before send, removed in a `finally` covering success/error/cancellation).
- The fold reuses `upsertConversation` → `projection.update { }` — atomic check-then-replace, no TOCTOU. Single source of state (`projection`); the list and thread top bar both derive from it.
- **Idempotency:** a re-archive reply carries the unchanged conversation with `is_archived` already matching; `upsertConversation` replaces the entry with an equal value — a benign no-op re-emit, no special handling.
- Teardown mid-await: `sendAndAwaitReply`'s pending deferred is failed with `IllegalStateException` by the existing `failAllPending` sweep (#488) — same failure mode `archive`/`unarchive` already surface for not-connected.

## Error handling

All error mapping is **centralized** — the helper has zero per-error catch (identical to `rename`):

| Failure | Surface | Mechanism |
|---|---|---|
| Session not connected (pump not `Open`) | `IllegalStateException` | `sendAndAwaitReply`'s `check(pump.send(...))` — AC #3, the `live`-throws path, replaces the old `UnsupportedOperationException` |
| Server `conversation.not_found` | `IllegalArgumentException` | `mapError` (`ERROR_CONVERSATION_NOT_FOUND`); unreachable from shipped UI (call site passes a known, currently-listed id) but pinned for contract parity with the fake |
| Any other server `error` | `RelayErrorException(code, retryable, message)` | `mapError` — server message never shown/logged |
| Malformed `conversation_updated` reply | `SerializationException` / `IllegalArgumentException` | `ConversationResponseDto` decode — **throws before any fold** (AC #4). A wrong-typed `is_archived` also fails here |

None of these mutate `projection`. Downstream swallowing/surfacing is #550's concern; this layer just throws the right type.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`); no instrumented tests (data layer). Fakes (`FakeSessionPump`), not MockK — mirror the rename suite. Run one class via `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"` (bare `test` is an aggregate — see project lessons).

**`RemoteConversationRepositoryTest.kt`** — clone the rename block (`:1298-1470`), add `startArchive`/`startUnarchive` helpers (mirror `startRename`). Scenarios (bullet form; developer writes the bodies):
- Archive request-encode: the sent envelope has `type == "archive_conversation"` and payload `{conversation_id}` only.
- Unarchive request-encode: same, `type == "unarchive_conversation"`.
- Archive reply-fold: a `conversation_updated` with `is_archived: true` → the conversation appears under `ConversationFilter.Archived` and leaves the non-archived filters.
- Unarchive reply-fold: `is_archived: false` → the conversation is back under `Channels`/`Discussions`.
- Idempotent re-archive: a reply whose `is_archived` already matches local state folds without error (benign no-op).
- Disconnected: pump returns `false` from `send` → `IllegalStateException`, projection unchanged (both verbs).
- Not-found: server `conversation.not_found` error reply → `IllegalArgumentException`, projection unchanged.
- Other server error → `RelayErrorException`, projection unchanged.
- Malformed reply (drop a required field) → decode throws, projection unchanged.
- **Edit** `stubMethods_throwUnsupportedOperationNamingTheFollowUp` (:245): remove the `repo.archive` / `repo.unarchive` `assertUnsupported` lines (leave `changeWorkspace`); update the "now all implemented" comment to add archive/unarchive.

**`ConversationResponseDtoTest.kt`** — the `is_archived` decode is the new codec surface:
- Reframe `absentDomainFields_useDocumentedDefaults` (:68): `archived` is no longer a placeholder; keep the absent→`false` assertion but move/relabel it as "defaults to false when the key is absent" (leave `currentSessionId`/`sessionHistory`/`isSleeping` as the placeholder assertions).
- Add: a `conversation_updated` fixture with `is_archived: true` → `toConversation().archived == true`.
- Add: `is_archived: false` explicitly present → `archived == false`.

No ViewModel tests here — the guard/surfacing path is #550. No change to `ThreadViewModelTest.kt` (also avoids overlap).

## Open questions

- **List-read `is_archived` (out of scope, likely a follow-up ticket).** pyrycode#880 also added `is_archived` to `ConversationSummary` (the `conversations` list payload), which the mobile `ConversationSummaryDto.toConversation()` still hardcodes to `false`. So on a fresh connect/list-read, an already-archived conversation shows as active until a verb reply re-folds it. This is not #549/#550/#551. Recommend PO file a "mirror pyrycode#880 list-read `is_archived`" ticket; do not expand this slice to cover it.
- **DTO field placement / name.** Placing `is_archived` right after `is_promoted` mirrors the server struct order (cosmetic — kotlinx decodes by key name). The shared DTO name (`ArchiveConversationPayloadDto`) reads slightly oddly for unarchive; its KDoc resolves the ambiguity. Both are the architect's call, resolved above.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The untrusted→trusted crossing is the single explicit `ConversationResponseDto` decode in the helper (`MobileJson.decodeFromJsonElement<ConversationResponseDto>`). A malformed reply — including a wrong-typed `is_archived` — throws at the boundary **before** `upsertConversation`, so no partial/hostile state is folded (AC #4). Downstream holds a decoded domain `Conversation`, never raw `JsonElement`.
- **[Error messages, logs, telemetry]** No finding — this is the core of the `security-sensitive` label (mirrors pyrycode#881's malformed-logging discipline). The new helper adds **zero** logging: it never logs the reply payload, the decoded conversation, the `conversation_id`, or the server error `message`. This is inherited-by-construction — `mapError` "never logs the payload," and `RelayErrorException.message` (server-supplied) is never shown or logged (the confidentiality invariant in `GuardedRepoLaunch.kt`). The spec **mandates** no logging in `sendArchiveToggle`; code-review must confirm no `Log.*` / `Timber` was added.
- **[Concurrency]** No finding. The fold is atomic (`projection.update {}`); `sendAndAwaitReply` registers the deferred before sending (no lost-reply race) and removes it in a `finally` (cancellation-safe). No new coroutine or scope. Idempotent re-archive folds an equal value — no double-fold hazard.
- **[Network & I/O]** No finding. Rides the existing `SessionPump` write-verb path with its existing frame-size cap and timeout discipline — no new network configuration. The `conversation_id` is JSON-encoded through `MobileJson` (properly escaped, no path/command interpolation) and is not attacker-controlled on the mobile side (it comes from the app's own projection / UI selection); the server re-validates and never reflects it on not-found (pyrycode#881).
- **[Threat model alignment]** OUT OF SCOPE (named, pre-existing posture). Folding the reply's `id` + `is_archived` trusts the authenticated paired relay: a compromised paired server could reply with a mis-correlated `id` or flip visibility via `is_archived`. This confers **no new capability** — an authenticated relay can already drive arbitrary list state through unsolicited `conversations` snapshots and `conversation_updated` broadcasts (the existing rename/promote fold posture). The trust boundary is the Noise_IK pairing (out of scope for this ticket); a compromised paired server is outside #549's threat model.
- **[Tokens/secrets]** N/A — no credentials on this path; the payload is an opaque `conversation_id` only.
- **[File / storage]** N/A — no filesystem operation; pure in-memory `projection` fold. The id is never used as a path.
- **[Inter-process / Android surface]** N/A — no exported component, intent, deep link, or WebView; internal repository code.
- **[Cryptographic primitives]** N/A — no new crypto; rides the existing Noise_IK transport. The request `id` is an `AtomicLong` correlation counter (uniqueness, not secrecy), same as every other verb.

No MUST FIX.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10
