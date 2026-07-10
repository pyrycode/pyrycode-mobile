# Spec — #532 wire delete to the daemon's `delete_conversation` v2 message

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1384-1421` — the shipped `sendArchiveToggle` (`archive`/`unarchive` delegate). **This is the closest template**: like delete, it returns `Unit`, encodes an id-only request, `sendAndAwaitReply`, decodes the reply through a typed boundary, then folds. Delete differs in three ways (new reply type, REMOVE not upsert, not-found converges) — see § Design.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1442-1462` — the `rename` delegate (the request/reply/decode/fold spine, with a return value).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1487-1507` — `setSessionSettings`. **The ack-decode posture to copy**: `MobileJson.decodeFromJsonElement<…>(reply)` on its own line to shape-validate the ack, result discarded (#543 / #318). Delete's `conversation_deleted` ack is the same "validate then discard" — the repo already knows the id it sent.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:279-356` — the `onInbound` demux. **Load-bearing.** Line **335** is the `when` arm that routes correlated success replies to the pending deferred; line **474** is `else -> Unit` (any unrouted type is silently dropped). `conversation_deleted` is a **new** reply type and MUST be added to the :335 arm (see § Design ③), else the ack falls through to :474, the deferred never completes, and the await hangs until teardown.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:623-635` — `mapError`. Note :630: `conversation.not_found → IllegalArgumentException`; every other code → `RelayErrorException`; a malformed error reply → fallback `RelayErrorException`. **IAE is produced here for not-found and nowhere else** — that is what makes the tight `catch (IllegalArgumentException)` in § Design ⑤ capture exactly the not-found case.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:665-674` — `sendAndAwaitReply` (register-before-send, `IllegalStateException` if pump not Open, `finally`-remove). Delete inherits its three throw types unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:911-921` — `upsertConversation`, the **contrast**. Delete removes rather than upserts; § Design ④ adds a sibling `removeConversation`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:159,173,988-1012` — the three read projections: `lastMessages` (:159), `threadByConversation` (:173), and their cold views `observeMessages` (:988 → `threadProjection` :1002) / `observeLastMessage` (:1012). These read their per-conversation slot **independently of `projection`** — why a list-only removal would leave stale streams (§ Design ④).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:81-98` — the `delete` interface contract + the throwing default you are overriding. Read the KDoc: delete is **tolerant of unknown ids** (converges on the post-condition, does NOT throw IAE like archive), and its post-condition names **all three** streams (`observeConversations`, `observeMessages → emptyList()`, `observeLastMessage → null`).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:62-96,231-233` — the fake's unified `state: Map<id, ConversationRecord>` (record holds the conversation AND its messages) and `delete` = `state - conversationId`. Removing the whole record empties all three fake streams at once; the remote must clear its three separate projections to match (§ Design ④).
- `app/src/main/java/de/pyryco/mobile/data/network/ArchiveConversationPayloadDto.kt` (whole file, ~35 lines) — the id-only encode-only request DTO to clone the shape + KDoc register of.
- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` (whole file) — the **precedent for bundling** a verb's request DTO + its reply-ack DTO in one file (two top-level classes → the ktlint single-class-filename rule does not fire). `DeleteConversationPayloads.kt` follows this shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` (whole file, ~43 lines) — the #490 guard. Read the KDoc at :24-27: `CancellationException` first, then `RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException` swallowed, and **`IllegalArgumentException` deliberately NOT caught** (crashes). This is why delete must not let `conversation.not_found` reach the guard as IAE (§ Error handling).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` (the `DeleteConfirm` handler, ~:629) — the call site, **unchanged by this ticket**: `launchGuardedRepoCall { repository.delete(state.value.conversationId); …PopBack }`. Shown so you can confirm the id passed is always the currently-open conversation.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1487-1727` — the `archive_*` / `unarchive_*` suite (9 tests). **Your `delete_*` tests mirror these one-for-one**, with the three delete divergences called out in § Testing strategy.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:4538-4600` — the reply/error envelope builders: `conversationUpdatedEnvelope` (:4538), `sessionSettingsUpdatedEnvelope` (:4563, builds `{session_id}` — the sibling to clone for a new `conversationDeletedEnvelope` that builds `{id}`), `ackEnvelope` (:4576), `errorEnvelope` (:4581).
- Daemon SSOT (already verified for this spec against merged `pyrycode/pyrycode#822` / PR #884): `pyrycode/internal/protocol/conversations_write.go:59-78` (structs) + `codes.go:69,75` (type strings) + `internal/relay/handlers/delete_conversation.go:58-105` (handler). Field shapes inlined below — you do not need the pyrycode checkout.
- Relevant lessons (Read/grep — codegraph doesn't index markdown): `catch(IllegalStateException) swallows CancellationException` (JVM class hierarchy — confirms `catch(IllegalArgumentException)` does NOT catch `CancellationException`, which is an `ISE` subtype); `ktlint filename rule` (bundling two DTOs in `DeleteConversationPayloads.kt` sidesteps it).

## Context

The Delete affordance (thread overflow menu, Channel Info sheet) is live and its ViewModel wiring already shipped: `ThreadEvent.DeleteConfirm` runs `launchGuardedRepoCall { repository.delete(conversationId); …PopBack }` (#490 guard, `ThreadViewModel` ~:629). The gap is the **data layer**: `RemoteConversationRepository` does not override `delete`, so it inherits the interface default `error("delete is not implemented …")` (`ConversationRepository.kt:98`), which throws `IllegalStateException` — inert-swallowed by the guard (#490). So tapping Delete against the relay today is a **silent no-op, not a crash** (the ticket body already carries this correction). This ticket replaces the inherited throw with the real round-trip so the action actually deletes.

This is the delete twin of the shipped write-verbs `rename` (#530, server #820) and `archive`/`unarchive` (#549, server #881). The daemon side shipped in **pyrycode/pyrycode#822** (PR #884, merged 2026-07-09). Consistent with the family, the Delete affordance in the relay path is currently hidden behind the `mutationsSupported` gate (`RemoteConversationRepository.mutationsSupported = false`, :1378; `ThreadOverflowMenu.kt:49`, `ChannelInfoSheet.kt:118`), so this override is **dormant-but-ready** — built and unit-tested now, live once the per-mutation reachability gate lands. The operator-facing rung-3 e2e is a separate follow-up (**#554**, Inbox, blocked by this ticket — same family gate as #537 / #551).

### Verified daemon SSOT (merged Go, pyrycode#822 / PR #884)

- **Request** — type `delete_conversation`; `DeleteConversationPayload{ ConversationID string \`json:"conversation_id"\` }` (`conversations_write.go:65`). One required id.
- **Success reply** — type `conversation_deleted`, correlated via `in_reply_to`; `ConversationDeletedPayload{ ID string \`json:"id"\` }` (`conversations_write.go:76`). **The ack field is `id`, NOT `conversation_id`** — the record is gone, so it carries only the deleted id, and deliberately is **not** a `conversation_updated` (which would need name/cwd/last_used_at the deleted record can't project). The `id` echoes the requested id.
- **Errors** (handler `delete_conversation.go:58-105`, all non-retryable, all **fixed static strings** — the daemon logs `conn_id` only on the malformed branch and never echoes attacker payload bytes back to the phone): `conversation.not_found` (unknown / already-deleted id — the handler is idempotent-on-miss server-side but still replies `error`, not `conversation_deleted`); `protocol.malformed` (undecodable payload).
- **No broadcast** — the handler `c.Reply(...)`s the requester only; it does not push a fresh `conversations` snapshot. Other connected clients pick up the deletion on their next `list_conversations` (deferred, as in #820/#881).

## Design source

N/A — data-layer wire-up. No UI is added or modified: the Delete affordance and the `DeleteConfirm` → guard → `PopBack` path already exist and are untouched. Visual-fidelity check intentionally skipped.

## Design

Four changes. Three mirror the `archive`/`rename` spine; two are the **do-not-blind-clone** deltas the ticket flags (new reply type + demux registration; REMOVE not upsert; not-found converges).

### ① New DTO file — `data/network/DeleteConversationPayloads.kt`

Bundle both DTOs in one file (the `SessionSettingsPayloads.kt` precedent — two top-level classes, so the ktlint single-class-filename rule does not fire). Contract shapes only:

```kotlin
@Serializable
data class DeleteConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,   // encode-only request; SSOT DeleteConversationPayload
)

@Serializable
data class ConversationDeletedPayloadDto(
    val id: String,   // decode-only ack; SSOT ConversationDeletedPayload — field is `id`, no @SerialName needed
)
```

- Request DTO: encode-only, one required non-null field → always sent (`MobileJson`'s `explicitNulls=false` never elides a non-null `String`). KDoc register mirrors `ArchiveConversationPayloadDto`: encode-only, always encode through `MobileJson`, cite the server SSOT (`DeleteConversationPayload`, #822).
- Ack DTO: decode-only, one required field `id` (no `@SerialName` — the JSON key is already `id`). KDoc mirrors `SessionSettingsUpdatedPayloadDto`: the decode's **sole purpose is reply-shape validation** — the value is discarded (the repo already holds the id it sent); correlation rides `Envelope.inReplyTo`, not this field.

### ② Two new companion constants

Add to the private `companion object` (adjacent to the existing verb constants, ~:1592-1616):

```kotlin
const val TYPE_DELETE_CONVERSATION = "delete_conversation"   // request type — used by the delete Envelope
const val TYPE_CONVERSATION_DELETED = "conversation_deleted" // reply type — used by the :335 demux arm
```

Unlike `rename`/`archive` (which reuse the pre-existing `TYPE_CONVERSATION_UPDATED`), delete's reply type is new, so **both** constants are referenced — no dead constant.

### ③ Register `conversation_deleted` in the inbound demux (LOAD-BEARING)

Add `TYPE_CONVERSATION_DELETED` to the correlated-success-reply `when` arm at **:335**:

```kotlin
TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED, TYPE_CONVERSATION_DELETED,
TYPE_SCREEN_SNAPSHOT, TYPE_SESSION_SETTINGS_UPDATED ->
    envelope.inReplyTo?.let { id -> pendingRequests[id]?.complete(envelope.payload) }
```

Extend the arm's explanatory comment with one clause: *a `conversation_deleted` (#532) carries the bare `{id}` ack the `delete` waiter decodes for reply-shape validation; like `screen_snapshot` / `session_settings_updated` it is always a correlated reply (never broadcast), so an unmatched one is a harmless no-op.*

**Why this is load-bearing.** An unregistered reply type falls through to `else -> Unit` (:474) and is silently dropped; the pending `CompletableDeferred` never completes, so `sendAndAwaitReply` suspends until the connection tears down (`failAllPending` → `IllegalStateException`, swallowed by the guard) — the delete would silently no-op forever. `rename`/`archive` did not need this step because `conversation_updated` was already registered; delete does. This is the single most important line in the ticket.

### ④ New `removeConversation` helper — the REMOVE fold (contrast `upsertConversation`)

```kotlin
private fun removeConversation(conversationId: String) {
    projection.update { current -> current?.filterNot { it.id == conversationId } }  // list (AC #1)
    threadByConversation.update { it - conversationId }                              // observeMessages → emptyList()
    lastMessages.update { it - conversationId }                                      // observeLastMessage → null
}
```

**Clears all three read projections, not just the list.** The interface contract (`ConversationRepository.kt:89-91`) documents delete's post-condition across all three streams, and the fake achieves it by removing its **unified** record (`state - conversationId` empties list, messages, and last-message at once). The remote holds three **separate** `StateFlow`s (`projection`, `threadByConversation`, `lastMessages`) read independently by `observeMessages`/`observeLastMessage`, so a list-only removal would leave those streams emitting a hard-deleted conversation's rows — a fake≠remote divergence and a contract violation. Clearing all three is *completing* the delete, not scope creep; it is the faithful mirror of the fake's whole-record removal.

Conflation is idempotent by construction: `List.filterNot` returns an element-equal list when the id is absent (`StateFlow` conflates by `equals` → no re-emit), and `Map - missingKey` returns an equals-identical map (the fake relies on the same property, `FakeConversationRepository.kt:230`). So deleting an already-absent id re-emits nothing on any of the three.

### ⑤ New `delete` override — replace the inherited throw

Contract (interface signature unchanged): `override suspend fun delete(conversationId: String)`. The control-flow shape is load-bearing (the catch scope and the decode-before-remove ordering are what make AC #2 and AC #4 hold), so it is sketched — not the surrounding boilerplate:

```kotlin
override suspend fun delete(conversationId: String) {
    val request = Envelope(
        id = requestId.incrementAndGet(),
        type = TYPE_DELETE_CONVERSATION,
        ts = Clock.System.now().toString(),
        payload = MobileJson.encodeToJsonElement(DeleteConversationPayloadDto(conversationId)),
    )
    val reply = try {
        sendAndAwaitReply(request)            // ISE (not-Open) / RelayError (other codes) propagate → guard swallows
    } catch (alreadyGone: IllegalArgumentException) {
        // mapError:630 maps conversation.not_found → IAE and nothing else → this catch is exactly not-found.
        // Delete's post-condition is "absent", so already-gone is SUCCESS (AC #4): converge and return.
        removeConversation(conversationId)
        return
    }
    // #318 boundary: a malformed ack throws HERE (SerializationException ⊂ IllegalArgumentException),
    // BEFORE removeConversation, so a bad ack mutates nothing (AC #2). Shape-validated, then discarded.
    MobileJson.decodeFromJsonElement<ConversationDeletedPayloadDto>(reply)
    removeConversation(conversationId)         // REMOVE (AC #1), only after a well-formed ack
}
```

Load-bearing details:

- **The `catch` wraps only `sendAndAwaitReply`.** It must NOT enclose the decode line — otherwise a malformed-ack `SerializationException` (an IAE subtype) would be caught and mis-read as "already gone", and `removeConversation` would run on a bad ack, violating AC #2. Scoping the catch to the await keeps the decode's IAE propagating (→ crash-as-hostile/bug signal, no mutation).
- **`catch (IllegalArgumentException)` captures exactly `conversation.not_found`.** `mapError` produces IAE only at :630; `sendAndAwaitReply`'s other exits are `IllegalStateException` (not-connected / teardown) and `RelayErrorException` (other codes) — neither is an IAE, so both propagate to the guard untouched. `CancellationException` is an `IllegalStateException` subtype (not IAE), so caller cancellation is **not** swallowed by this catch (structured-concurrency-safe).
- **Ordering.** Every failure path (not-Open, other server error, malformed ack) is reached strictly **before** any `removeConversation`, so a failed delete never mutates a projection (AC #2, AC #3). The not-found path is the one deliberate exception — it *does* converge (removes locally), because for delete not-found *is* the success post-condition.
- Add a KDoc mirroring `sendArchiveToggle`'s (:1384-1403), documenting: the round-trip, the `{id}`-ack validate-then-discard, the all-three-projection removal, the not-found→converge divergence from archive, and the never-log discipline (id and reply stay off the log — `security-sensitive`).

### Data flow

```
ThreadViewModel.DeleteConfirm (already wired, unchanged)
  └─ launchGuardedRepoCall { repository.delete(state.value.conversationId); …PopBack }   # #490 guard
       └─ RemoteConversationRepository.delete
            ├─ encode DeleteConversationPayloadDto ──▶ Envelope(delete_conversation)
            ├─ sendAndAwaitReply ──▶ (pump.send; await correlated reply by in_reply_to)
            │      ├─ not Open              → throw IllegalStateException          (no removal → guard swallows)
            │      ├─ server other error    → mapError → RelayErrorException       (no removal → guard swallows)
            │      └─ server not_found      → mapError → IAE → CAUGHT → removeConversation; return  (AC #4)
            ├─ decodeFromJsonElement<ConversationDeletedPayloadDto>(reply)   # #318 shape-validate, discard
            │      └─ malformed ack         → decode throws                        (no removal, AC #2)
            └─ removeConversation(conversationId)
                   ├─ projection            ──▶ observeConversations re-emits without it  (AC #1)
                   ├─ threadByConversation  ──▶ observeMessages(id) → emptyList()
                   └─ lastMessages          ──▶ observeLastMessage(id) → null
       (#490 guard: DeleteConfirm's own PopBack navigates back to the list after delete returns)
```

## State + concurrency model

- **No new coroutine / scope.** `delete` is a plain `suspend` method called from the existing `launchGuardedRepoCall` (`viewModelScope`, #490). The request↔reply await runs on the shared single inbound collector via `sendAndAwaitReply` — **no second pump subscription**, correlated by `Envelope.id` ↔ reply `in_reply_to`.
- **Single source of state.** Each of the three removals is an atomic `MutableStateFlow.update {}` (CAS). No parallel mutable state; no check-then-mutate. A concurrent authoritative `conversations` snapshot retry-merges; a re-delivered `conversation_deleted` is idempotent (all three updates are element-equal no-ops the second time).
- **Ordering.** `sendAndAwaitReply` → (not-found converge, or) decode → three-projection remove. Every non-not-found failure is unreachable-before-mutation, so a failed delete never mutates a projection (AC #2/#3).
- **Cancellation / teardown.** Inherited from `sendAndAwaitReply`: registers the deferred before sending, removes it in `finally` (success / error / caller cancellation), and #488's `failAllPending` completes a mid-await deferred with `IllegalStateException` on teardown — so a delete in flight when the connection drops surfaces as ISE (swallowed by the guard), never hangs. The `catch (IllegalArgumentException)` does not intercept this ISE.

## Error handling

All error mapping is **centralized** in `mapError` (:623) + `sendAndAwaitReply`'s `check(pump.send(...))` (:669). The `delete` body adds exactly one per-verb branch — the not-found convergence — and no other catch:

| Failure | Produced by | Type at `delete` | Handling | Result |
|---|---|---|---|---|
| Not connected (pump not Open) | `sendAndAwaitReply` `check(...)` | `IllegalStateException` | propagates → #490 guard **swallows** | silent no-op (AC #3) |
| Teardown mid-await | `failAllPending` (#488) | `IllegalStateException` | propagates → guard **swallows** | silent no-op |
| Server `protocol.malformed` | `mapError` else-branch | `RelayErrorException` | propagates → guard **swallows** | silent no-op (AC #3) |
| Server `conversation.not_found` | `mapError` (:630) | `IllegalArgumentException` | **caught** in `delete`; `removeConversation`; return | **success** — converges on post-condition (AC #4) |
| Malformed / undecodable ack | `ConversationDeletedPayloadDto` decode | `SerializationException` (⊂ IAE) | propagates (catch is scoped to the await) → guard does **not** catch IAE → crashes | no projection mutation (AC #2) |
| Caller cancellation | `viewModelScope` cancel | `CancellationException` (⊂ ISE) | not caught by the IAE catch; guard rethrows Cancellation-first | cooperative cancel |

**The not-found divergence is the heart of this ticket — do NOT clone archive's IAE-on-not-found.** `archive`/`rename` let `conversation.not_found → IllegalArgumentException` propagate, and the #490 guard **deliberately does not catch IAE** (`GuardedRepoLaunch.kt:26-27`) — it treats an unknown-conversation IAE as an impossible-by-construction programming bug and lets it **crash**. That is correct for archive/rename (whose contract *throws* on unknown ids) but wrong for delete (whose contract is *tolerant* of unknown ids — `ConversationRepository.kt:82-87`). So delete catches the not-found IAE in its own body and converges. From the shipped UI both are moot (the call site always passes `state.value.conversationId` — the currently-open, hence server-known, conversation), but the contract divergence is real and must be honored: a delete of an already-gone id is success, never a crash.

**No server-supplied error text is shown or logged** anywhere on the delete path. `RelayErrorException.message` is server-supplied and is never surfaced (the #490 guard's confidentiality invariant); the DTO / decode / removal / not-found-catch add no `Log.*`. The daemon itself returns only fixed static error strings and logs `conn_id`-only on the malformed branch (handler `delete_conversation.go:62-68`), so no attacker payload bytes flow back.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`); no instrumented test. Everything below reuses existing fixtures/helpers in `RemoteConversationRepositoryTest.kt`. Add a `startDelete` helper (sibling of the archive/rename starters) and a `conversationDeletedEnvelope(inReplyTo, id)` reply builder (clone `sessionSettingsUpdatedEnvelope` :4563; payload `{id}` instead of `{session_id}`). Mirror the `archive_*` suite (:1487-1727), with these tests:

- **Codec / request encode.** Drive `delete`; assert `pump.sent.single { it.type == "delete_conversation" }` has payload `{"conversation_id":"…"}` (exactly one key, no `name`/`cwd`). (Mirrors `archive_sendsArchiveConversationWithConversationId` :1487.)
- **Success removes from all three streams.** Seed the projection (and a thread row + last-message for the target via the existing inbound helpers) → `delete` → push `conversationDeletedEnvelope(inReplyTo = sent.id, id = target)` → assert: a concurrently-collected `observeConversations` no longer contains the id (AC #1); `observeMessages(id)` emits `emptyList()`; `observeLastMessage(id)` emits `null`. **This is the delete-specific divergence** from archive (which folds, not removes, and asserts nothing about the message streams).
- **Ack field is `id`, not `conversation_id`.** Feed a well-formed `{"id":"…"}` ack and assert the removal succeeds; (recommended) feed an ack whose only key is `conversation_id` and assert it is treated as a **malformed ack** (decode throws, no removal) — pins the field-name SSOT.
- **Not connected → `IllegalStateException`, all three streams unchanged.** Pump never Open (`FakeSessionPump` default) → `startDelete` result is `Result.failure(IllegalStateException)`; projection/thread/last-message untouched.
- **`conversation.not_found` → converges as SUCCESS, id removed.** Push an error envelope with `code = "conversation.not_found"`; assert `startDelete` result is `Result.success(Unit)` (NOT a failure), and the id is absent from `observeConversations`. **This is the not-found divergence** — the archive equivalent (`archive_onConversationNotFound_throwsIllegalArgumentAndLeavesListUnchanged` :1656) asserts an IAE failure; delete must assert the opposite.
- **Not-found on an already-absent id is a no-op success.** Delete an id not in the projection; push `conversation.not_found`; assert success and no stream re-emits (conflation no-op).
- **Other server error → `RelayErrorException`, all three streams unchanged.** Push an error envelope with an arbitrary code (e.g. `"protocol.malformed"`); result is `RelayErrorException` carrying the code; no removal.
- **Malformed ack → decode throws, all three streams unchanged (AC #2).** Push a `conversation_deleted` reply with a missing/mistyped `id`; assert `startDelete` result is a decode failure (`SerializationException`/`IllegalArgumentException`) and nothing was removed — proves the decode-before-remove ordering.

**ViewModel check — already covered, add nothing.** The AC's "a failing delete does not crash / no error surface" is already asserted by `guardedRepoCalls_whenRepositoryThrowsEachHandledType_areSwallowedWithoutCrashing` (`ThreadViewModelTest.kt:840`), which drives `DeleteConfirm` through the guard for `IllegalStateException` / `RelayErrorException` / `UnsupportedOperationException`. Do **not** add a duplicate VM test and do **not** touch `ThreadViewModelTest.kt` (also avoids cross-branch overlap). Note IAE is correctly absent from that test's swallowed list — and delete's not-found IAE never reaches the guard anyway (caught in the repo).

## Scope

Production source files (Kotlin, excluding tests/md/spec): **2** — `DeleteConversationPayloads.kt` (new) and `RemoteConversationRepository.kt` (modified). Two new exported types (`DeleteConversationPayloadDto`, `ConversationDeletedPayloadDto`). Zero consumer fan-out — `delete` already exists on the interface (with a default) and is already consumed by `ThreadViewModel` / `StableConversationRepository` / the fakes; this adds an override, changing no signature and no call site. One new reject branch (the not-found catch). ~35 production LOC + ~170 test LOC ≈ ~205 total. Every red line clears with margin (files ≤3, total LOC ≤600, exported types ≤5, call sites 0, reject branches 1, ACs 5) → **size S, no split**. PO's `size:s` is confirmed, not overridden. Branch-overlap check (`git branch -r` vs `origin/main`, 2026-07-10): no in-flight feature branch touches `RemoteConversationRepository.kt`, the new DTO file, or the repo test file — no blocker to wire.

## Open questions

None blocking. Deliberate deferrals, all named:

- **Live fan-out to other connected clients** — out of scope in #822 (the daemon replies to the requester only); other clients pick up the deletion on their next `list_conversations`.
- **Visible failure feedback** — deliberately absent (#490 silent-swallow posture, family-consistent with rename/archive/send). If ever wanted, a cross-cutting mutation-feedback ticket with its own Figma anchor, not a per-verb bolt-on.
- **Operator-facing rung-3 real-claude delete e2e** — filed separately as **#554** (Inbox, blocked by this ticket), rides the #421 harness (#481/#482 shape); parked pending the per-mutation `mutationsSupported` reachability gate (family gate, #537 / #551).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The one untrusted→trusted crossing is the `conversation_deleted` reply, decoded at a single explicit `ConversationDeletedPayloadDto` boundary — a malformed ack throws at decode, **before** `removeConversation`, so no untrusted bytes reach any projection (AC #2). The decoded `id` is discarded (never used to key the removal — the repo removes the id it *sent*, not the id the reply echoes), so a hostile relay cannot redirect the removal to a different conversation by lying in the ack. The outbound `DeleteConversationPayloadDto` carries only the caller-supplied `conversationId` (the currently-open id); the daemon re-validates it (`conversation.not_found`), so the client is not the delete authority.
- **[Tokens, secrets, credentials]** N/A — this path handles no tokens/keys/credentials. The Noise_IK transport (`data/network/`, `data/crypto/`) carrying the frame is untouched; delete rides the already-established encrypted session. `Envelope.id` is the existing `requestId.incrementAndGet()` monotonic correlation counter (not a secret).
- **[File / storage operations]** N/A — no filesystem access on the mobile side. The `conversationId` is never concatenated into a path; it is a wire field and an in-memory `StateFlow`/`Map` key only. (Server-side durability — `reg.Save` — is the daemon's concern, out of scope here.)
- **[Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`Receiver`/deep-link/`PendingIntent`/provider/WebView. Purely an internal repository method behind the DI-wired `ConversationRepository`; the affordance is additionally `mutationsSupported`-gated (dormant).
- **[Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparisons introduced. No `==`/`equals` compare against a secret (the id is not a secret; `filterNot { it.id == conversationId }` is an ordinary identity match on a non-sensitive key).
- **[Network & I/O]** No findings. Delete reuses `sendAndAwaitReply` over the existing `SessionPump` / OkHttp WebSocket transport — no new socket, no timeout/TLS/frame-size config touched. #488's `failAllPending` guarantees a delete in flight at teardown fails promptly (ISE) rather than hanging — and specifically **the demux registration (§ Design ③) is what prevents a hang on a *well-formed* reply**: without it the ack is dropped and the await would suspend until teardown.
- **[Error messages, logs, telemetry]** No findings — **and this is the security-relevant core.** The daemon returns fixed static error strings and logs `conn_id`-only on the malformed branch (never `err` or `conversation_id`, both attacker-influenced on a decode failure — `delete_conversation.go:62-68`). On the client, `RelayErrorException.message` is **never logged and never surfaced** (the #490 guard's confidentiality invariant), and this spec adds **no `Log.*`** on any delete path (encode, decode, removal, not-found catch). Forbidding a delete-specific error surface (§ Open questions) is what keeps this intact.
- **[Concurrency]** No findings. No new coroutine/scope; each of the three removals is an atomic `MutableStateFlow.update {}` CAS (no check-then-mutate TOCTOU); every non-not-found throw path is ordered strictly before the removal, so a failed delete cannot leave partial state (AC #2). The `catch (IllegalArgumentException)` is scoped to the await and cannot swallow `CancellationException` (an `ISE` subtype) — no structured-concurrency violation.
- **[Threat model alignment]** The relevant mobile-wire threat — a malicious/compromised relay returning a crafted `conversation_deleted` or `error` frame — is contained: a crafted ack either fails the typed decode (no removal) or, if well-formed, only removes the id the client itself chose to delete (the echoed `id` is discarded). A crafted `conversation.not_found` converges the client on the delete post-condition (removes the client's own target) — which is the intended tolerant-of-unknown-ids behavior, not an exploit (the relay cannot force removal of an *arbitrary* conversation this way, only of the one the client is actively deleting). No text leak on any branch. Out of scope (named): live cross-client fan-out (#822 defers it); the operator-run delete e2e (#554).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10
