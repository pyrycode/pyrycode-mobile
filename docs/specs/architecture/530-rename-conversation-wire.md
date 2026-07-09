# Spec — #530 wire rename to the daemon's `rename_conversation` v2 message

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1063-1107` — the shipped `promote` method (kdoc + body). **This is the template.** Rename is `promote` minus the `cwd` resolution.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1376-1382` — the current `rename` stub (`throw UnsupportedOperationException`). This body is what you replace.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:608-668` — `mapError` (:617) + `sendAndAwaitReply` (:659). **Read both.** This is where `conversation.not_found → IllegalArgumentException`, other codes `→ RelayErrorException`, and not-connected `→ IllegalStateException` are produced. Rename inherits all three for free by calling `sendAndAwaitReply` — no per-error catch in the rename body.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:898-915` — `upsertConversation` (:905), the atomic confirmed-fold you reuse verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1395-1543` — the private `companion object` of `TYPE_*` constants. `TYPE_PROMOTE_CONVERSATION` (:1430) and `TYPE_CONVERSATION_UPDATED` (:1436, the reply type — **already present, reused**) live here. Add `TYPE_RENAME_CONVERSATION` adjacent to `TYPE_PROMOTE_CONVERSATION`.
- `app/src/main/java/de/pyryco/mobile/data/network/PromoteConversationPayloadDto.kt` (whole file, 32 lines) — the sibling encode-only DTO to clone the *shape and kdoc register* of. New file `RenameConversationPayloadDto.kt` is this minus the `cwd` field.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt:41-73` — `ConversationResponseDto` + `toConversation()` (:58). The reply decoder. **Reused unchanged** — `conversation_updated` and `promote`'s reply are the *same* payload shape, already fully modelled and tested here. No codec change needed on the decode side.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` (whole file, 43 lines) — the `#490` guard. **Read the kdoc at :24-27 carefully**: `CancellationException` first, then `RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException` swallowed, and **`IllegalArgumentException` deliberately NOT caught** (crashes as a programming-bug signal). This is load-bearing for § Error handling — do not "fix" it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:597-602` — the `RenameSubmit` call site (inside `launchGuardedRepoCall`, passing `state.value.conversationId`). **Unchanged by this ticket** — shown so you can confirm the id passed is always the currently-open conversation.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1035-1300` — the `promote_*` test suite. Your `rename_*` tests mirror these one-for-one (minus the cwd-resolution cases).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:3737-3746` — the `startPromote` test helper. Add a sibling `startRename` (identical, drop the `workspace` param). The `conversationUpdatedEnvelope(inReplyTo, id, name, cwd)` reply-builder and the error-envelope helpers used by `promote`'s tests are reused verbatim.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationResponseDtoTest.kt` (whole file, 107 lines) — the reply-decode codec tests. Already covers `conversation_updated → ConversationResponseDto`. **No new decode test needed** (rename reuses this exact reply DTO); the request-encode assertion lives at the repository level (mirroring `promote_explicitWorkspace_...` at :1035).
- Relevant lessons (Read/grep — codegraph doesn't index markdown): ktlint filename rule (a single-top-level-class file must be named after the class → `RenameConversationPayloadDto.kt`).

## Context

The Rename affordance (`RenameDialog`, Figma `19:14`) is live and wired to `ThreadViewModel` (thread overflow **Rename** item + Channel Info sheet), but the data layer has no wire message: `RemoteConversationRepository.rename` throws `UnsupportedOperationException("rename: no v2 wire message defined …")` (:1380). This ticket replaces that throw with the real round-trip.

The daemon's `rename_conversation` v2 message shipped in **pyrycode/pyrycode#820** (closed 2026-07-09, PR #859):

- **Request** — `rename_conversation` carrying `{conversation_id, name}`. Server SSOT: `internal/protocol/conversations_write.go` `RenameConversationPayload{ConversationID, Name string}` — deliberately *not* a reuse of `PromoteConversationPayload` (promote also carries a required `cwd`, which a rename neither has nor means).
- **Reply** — `conversation_updated` (`ConversationUpdatedPayload`): the bare, updated conversation record, correlated via `in_reply_to`. Identical shape to `promote`'s reply → decodes through the existing `ConversationResponseDto`.
- **Errors** — `conversation.not_found` (unknown id) and `protocol.malformed` (empty/whitespace title), both non-retryable, returned as **fixed static strings** (the daemon never echoes attacker-controlled payload bytes — title/id/decode error are never in the reply).
- **No broadcast** — the daemon replies **only to the requester**; it does *not* push a fresh `conversations` snapshot (explicitly out of scope in #820). So the client must **fold the reply into its own local state** — the list does not auto-refresh from a push.

This is the direct analogue of the already-shipped `promote` path (`RemoteConversationRepository.promote`, :1081), which #820 names as its server-side precedent: encode a payload → `sendAndAwaitReply` → decode the returned record → `upsertConversation` → return the `Conversation`. Rename mirrors it, minus `cwd`.

### Two ticket-body premises corrected against `main` (code-is-truth)

1. **"Tapping Rename crashes today" — FALSE.** The call site (`ThreadViewModel.kt:599`) already runs inside `launchGuardedRepoCall` (#490), which inertly swallows the `UnsupportedOperationException`. Today Rename is a **silent no-op, not a crash**. (The ticket body already carries this correction.)
2. **No visible failure surface.** #490's house posture for *all* one-shot mutations (send / promote / delete / rename) is silent inert-swallow with no UI surface; `RelayErrorException.message` is server-supplied and is **never shown or logged** (the guard's confidentiality invariant, `GuardedRepoLaunch.kt:22`). Adding a rename-specific error surface would contradict #490, be inconsistent with promote, risk leaking server text, and be UI-visible (needing a Figma anchor) — explicitly out of scope, deferred to a hypothetical cross-cutting mutation-feedback ticket.

## Design source

N/A — data-layer wire-up. The existing `RenameDialog` (Figma `19:14`) and all routes are unchanged; no UI is added or modified. Visual-fidelity check intentionally skipped.

## Design

Three changes, all mirroring `promote`:

### 1. New encode-only DTO — `data/network/RenameConversationPayloadDto.kt`

A sibling of `PromoteConversationPayloadDto` with two fields (no `cwd`):

```kotlin
@Serializable
data class RenameConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val name: String,
)
```

- File must be named `RenameConversationPayloadDto.kt` (ktlint single-class-filename rule).
- Both fields non-null/required (no Kotlin defaults) — `MobileJson`'s `explicitNulls=false` never elides a non-null `String`, so both keys are always sent. Wire SSOT field names: `conversation_id`, `name` (snake_case, mapped via `@SerialName`).
- kdoc register mirrors `PromoteConversationPayloadDto.kt`: encode-only (the phone sends it; never decodes it), always encode through `MobileJson`, and cite the server SSOT (`RenameConversationPayload`, #820) noting the deliberate non-reuse of promote's DTO (no `cwd`).

### 2. New companion constant

Add to the private `companion object` (adjacent to `TYPE_PROMOTE_CONVERSATION`, :1430):

```kotlin
const val TYPE_RENAME_CONVERSATION = "rename_conversation"
```

The reply-type constant `TYPE_CONVERSATION_UPDATED` (:1436) already exists and is reused — no second constant. (Note: the send path never references the reply-type constant; it decodes the correlated reply payload regardless of `type`, exactly as `promote` does.)

### 3. Replace the `rename` body (:1380)

Contract (unchanged signature): `override suspend fun rename(conversationId: String, name: String): Conversation`.

Behaviour (mirror `promote` :1091-1106, minus cwd resolution):
1. Build an `Envelope` with `type = TYPE_RENAME_CONVERSATION` and `payload = MobileJson.encodeToJsonElement(RenameConversationPayloadDto(conversationId, name))`. `id = requestId.incrementAndGet()`, `ts = Clock.System.now().toString()`.
2. `val reply = sendAndAwaitReply(request)` — throws on server `error` / not-connected before any state mutation (see § Error handling); the decode + fold below are unreachable on any failure path.
3. `val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()` — the #318 typed decode boundary; a malformed reply throws here, still before the fold.
4. `upsertConversation(conversation)` — atomic confirmed-fold into the read projection, only after the reply decodes.
5. `return conversation` — the **server-authoritative** record (the returned `name` is the daemon's, not the request's).

The `name` is forwarded **verbatim** — the dialog is the sole trim authority (`RenameDialog` emits an already-trimmed name). Do **not** add a second client-side empty/whitespace check: the daemon rejects empty titles server-side (`protocol.malformed`), which the client treats as an ordinary server error (see § Error handling). This is a body-only change — the interface, the `Stable`/`Fake` overrides, and every call site are untouched (zero fan-out).

Update the `rename` kdoc to match `promote`'s (:1063-1080): describe the round-trip, the confirmed-fold, the server-authoritative return, and the three throw types — none of which mutate the projection.

### Data flow

```
ThreadViewModel.RenameSubmit (already wired, unchanged)
  └─ launchGuardedRepoCall { repository.rename(currentId, trimmedName) }   # #490 guard
       └─ RemoteConversationRepository.rename
            ├─ encode RenameConversationPayloadDto ──▶ Envelope(rename_conversation)
            ├─ sendAndAwaitReply ──▶ (pump.send; await correlated reply by in_reply_to)
            │      ├─ not Open        → throw IllegalStateException        (no fold)
            │      └─ server `error`  → mapError → IAE | RelayErrorException (no fold)
            ├─ decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
            │      └─ malformed reply → decode throws                      (no fold)
            └─ upsertConversation(conversation)  ──▶ projection re-emits
                   └─ observeConversations → list re-emits (new name in Channels/Discussions tier)
                   └─ ThreadViewModel.state.displayName re-emits (new name in thread top bar)
```

Both AC #1 surfaces (conversation list + thread top bar) update from the single `upsertConversation` fold — no VM change: the list observes `observeConversations`, and the top bar's `displayName` is already derived from the conversation projection (see the existing `state_displayName_reemitsOnRename` test, ThreadViewModelTest.kt:1348).

## State + concurrency model

- **No new coroutine / scope.** `rename` is a plain `suspend` method called from the existing `launchGuardedRepoCall` (`viewModelScope`, #490). The request↔reply await runs on the shared single inbound collector via `sendAndAwaitReply` — **no second pump subscription**, correlated by `Envelope.id` ↔ reply `in_reply_to`.
- **Single source of state.** The fold is `upsertConversation` (:905), an atomic `MutableStateFlow.update {}` CAS upsert (replace-by-id-else-append). A concurrent authoritative `conversations` snapshot retry-merges rather than clobbering; a re-delivered reply is idempotent. No parallel mutable state.
- **Ordering.** `sendAndAwaitReply` first (throws before any mutation) → decode → fold. Every failure path is unreachable-before-mutation, so a failed rename never touches the projection (AC #3).
- **Cancellation / teardown.** Inherited from `sendAndAwaitReply`: registers the deferred before sending, removes it in `finally` (success / error / caller cancellation), and #488's `failAllPending` completes a mid-await deferred with `IllegalStateException` on teardown — so a rename in flight when the connection drops surfaces as ISE (swallowed by the guard), never hangs.

## Error handling

All error mapping is **centralized** in `mapError` (:617) + `sendAndAwaitReply`'s `check(pump.send(...))` (:663). The `rename` body adds **no per-error catch** — it inherits, identically to `promote`:

| Failure | Produced by | Type thrown | Guard (`#490`) behaviour |
|---|---|---|---|
| Not connected (pump not Open) | `sendAndAwaitReply` `check(...)` | `IllegalStateException` | **swallowed** → silent no-op |
| Server `protocol.malformed` (empty title) | `mapError` else-branch | `RelayErrorException` | **swallowed** → silent no-op |
| Server `conversation.not_found` | `mapError` (`code == ERROR_CONVERSATION_NOT_FOUND`) | `IllegalArgumentException` | **NOT swallowed → crashes** (by design) |
| Malformed / undecodable reply | `ConversationResponseDto` decode | `SerializationException` / `IllegalArgumentException` | (decode IAE) NOT swallowed |

**Load-bearing nuance — resolving the apparent AC #2 / AC #3 tension.** AC #2 requires `conversation.not_found → IllegalArgumentException`; AC #3 says a failed rename "is swallowed by the guard … does not crash … consistent with the other one-shot mutations (promote / delete / send)." These are only consistent because of how the guard treats IAE:

- `GuardedRepoLaunch.kt:26-27` **deliberately does not catch `IllegalArgumentException`** — it treats an unknown-conversation IAE as an impossible-by-construction programming-bug signal and lets it crash. This is *identical* to `promote`, which also throws IAE on `conversation.not_found` and is also launched through the same guard (`SaveAsChannelSubmit`, :609).
- The rename call site (`ThreadViewModel.kt:600`) always passes `state.value.conversationId` — the **currently-open, hence server-known** conversation. So `conversation.not_found` is unreachable from the shipped UI (it would require the conversation to be deleted server-side between opening the thread and renaming — the same reachability profile as promote). Its IAE-crashes posture is therefore intentional and matches promote exactly.
- The daemon errors that *are* reachable and *are* swallowed are **not-connected** (ISE) and **`protocol.malformed`** (RelayErrorException). `protocol.malformed` is itself unreachable from the shipped UI (the dialog trims), but is handled defensively.

**Binding requirement for the developer:** mirror `promote` exactly. Throw IAE for `conversation.not_found` (matching the fake's `unknown()` → `IllegalArgumentException`, FakeConversationRepository.kt:355, and `sendMessage`/`promote`). Do **not** add a rename-specific catch, and do **not** widen the guard to swallow IAE — the crash-as-programming-bug posture is a deliberate, consistent invariant. **No server-supplied error text is shown or logged** anywhere on the rename path (the guard's confidentiality invariant; the DTO/decode/fold never `Log.*`).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`); no instrumented test. Everything below reuses existing fixtures/helpers in `RemoteConversationRepositoryTest.kt`.

**Codec (request encode).** Add a repository-level test mirroring `promote_explicitWorkspace_sendsPromoteConversationWithAllThreeFields` (:1035): drive `rename`, assert `pump.sent.single { it.type == "rename_conversation" }` has payload `{"conversation_id":"…","name":"…"}` (exactly two keys, no `cwd`). The **reply-decode** side needs no new codec test — `conversation_updated → ConversationResponseDto` is already covered in `ConversationResponseDtoTest.kt`.

**Repository path.** Add a `startRename` helper (sibling of `startPromote` at :3737, drop the `workspace` param) and these tests, mirroring the `promote_*` suite:
- **Success round-trips + folds.** Rename, push `conversationUpdatedEnvelope(inReplyTo = sent.id, id, name = "new", cwd = …)`; assert the returned `Conversation.name == "new"` **and** the conversation appears renamed in a concurrently-collected `observeConversations` list (proves the `upsertConversation` fold — the AC #1 list surface).
- **Server-authoritative name.** The returned `name` is the reply's value, not the request's (feed a reply whose `name` differs from the request to prove it).
- **Not connected → `IllegalStateException`, list unchanged.** Pump never Open (`FakeSessionPump` default) → `startRename` result is `Result.failure(IllegalStateException)`; projection untouched.
- **`conversation.not_found` → `IllegalArgumentException`, list unchanged.** Push an error envelope with `code = "conversation.not_found"`; result is `IllegalArgumentException`; no fold.
- **Other server error → `RelayErrorException`, list unchanged.** Push an error envelope with an arbitrary code (e.g. `"protocol.malformed"`); result is `RelayErrorException` carrying the code; no fold.
- **(Recommended, mirrors promote)** Malformed `conversation_updated` reply → decode throws, list unchanged.

**ViewModel check — already covered, add nothing.** The AC's "a failing rename does not crash" is *already* asserted by `guardedRepoCalls_whenRepositoryThrowsEachHandledType_areSwallowedWithoutCrashing` (ThreadViewModelTest.kt:840), which drives `ThreadEvent.RenameSubmit` through the guard for `IllegalStateException`, `RelayErrorException`, and `UnsupportedOperationException`. Do **not** add a duplicate VM test, and do **not** touch `ThreadViewModelTest.kt` — leaving it untouched also avoids any cross-branch file overlap. (Note: IAE is correctly *absent* from that test's failure list, matching the guard's deliberate no-catch of IAE.)

## Scope

Production source files (Kotlin, excluding tests/md/spec): **2** — `RenameConversationPayloadDto.kt` (new) and `RemoteConversationRepository.kt` (modified). One new exported type (`RenameConversationPayloadDto`). Zero consumer fan-out (body-only change; signature unchanged). Zero new reject branches (error mapping is centralized and reused). ~30 production LOC + ~150 test LOC. Every red line clears with margin → **size S, no split**. PO's `size:s` is confirmed, not overridden.

## Open questions

None blocking. Two deliberate deferrals, both already recorded in the ticket body / #820:
- **Live fan-out to other connected clients** — out of scope in #820; other clients pick up the new title on their next `list_conversations`.
- **Visible failure feedback** — deliberately absent (#490 silent-swallow posture); if ever wanted, a cross-cutting mutation-feedback ticket with its own Figma anchor.
- The rename happy-path rung-3 real-claude e2e is filed separately as **#537** (blocked by this ticket), keeping #530 a data-layer wire-up at S.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The one untrusted→trusted crossing is the `conversation_updated` reply, decoded at the single explicit `ConversationResponseDto` boundary (`toConversation()`, :58) — a malformed reply throws at decode, before `upsertConversation`, so no untrusted bytes reach the projection. The outbound `RenameConversationPayloadDto` carries only the caller-supplied `conversationId` (the currently-open id) and the dialog-trimmed `name`; the daemon re-validates both (`conversation.not_found`, `protocol.malformed`), so the client is not the authority for either.
- **[Tokens, secrets, credentials]** N/A — this path handles no tokens/keys/credentials. The Noise_IK transport (`data/network/`, `data/crypto/`) that carries the frame is untouched; rename rides the already-established encrypted session.
- **[File / storage operations]** N/A — no filesystem access. The `name` is never concatenated into a path; it is a wire field folded into an in-memory `StateFlow` only.
- **[Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`Receiver`/deep-link/`PendingIntent`/provider/WebView. Purely an internal repository method behind the existing DI-wired `ConversationRepository`.
- **[Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparisons introduced. `Envelope.id` uses the existing `requestId.incrementAndGet()` monotonic counter (a correlation id, not a secret) — same as every sibling verb.
- **[Network & I/O]** No findings. Rename reuses `sendAndAwaitReply` over the existing `SessionPump` / OkHttp WebSocket transport — no new socket, no timeout/TLS/frame-size config touched. #488's `failAllPending` guarantees a rename in flight at teardown fails promptly (ISE) rather than hanging.
- **[Error messages, logs, telemetry]** No findings — **and this is the security-relevant heart of the ticket.** The daemon returns fixed static error strings, never attacker-controlled bytes (#820). On the client, `RelayErrorException.message` (server-supplied) is **never logged and never surfaced** — the `#490` guard centralizes that confidentiality invariant in exactly one place (`GuardedRepoLaunch.kt:22`, `:36`), and this spec adds no `Log.*` on any rename path (encode, decode, fold, or error). The confidentiality posture is preserved by construction: forbidding a rename-specific error surface (§ Context correction 2) is what keeps it intact.
- **[Concurrency]** No findings. No new coroutine/scope; the fold is an atomic `MutableStateFlow.update {}` CAS (no check-then-mutate TOCTOU); every throw path is ordered strictly before the mutation, so a failed rename cannot leave partial/corrupt state (AC #3). No new mutex; no hot/cold-flow change.
- **[Threat model alignment]** The relevant mobile-wire threat — a malicious/compromised relay returning a crafted `conversation_updated` or `error` frame — is contained: a crafted reply either fails the typed decode (no fold) or maps to a swallowed/deliberately-crashing exception with no text leak. Renaming to an arbitrary attacker-chosen title is a *server-authority* concern (the daemon owns the registry write and re-validates); the client faithfully reflecting the server's authoritative reply is correct, not a vulnerability. Out of scope (named): live cross-client fan-out (#820 defers it) and any title-length cap (deferred in #820 to the registry primitive, evidence-based — no oversized-title failure observed).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-09
