# Spec — #560 wire change_workspace to the daemon's `change_workspace` v2 message

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1517-1544` — the shipped `rename` override (kdoc + body). **This is the template.** `changeWorkspace` is `rename` with a two-field `{conversation_id, cwd}` payload instead of `{conversation_id, name}`, plus a vestigial `Session` return (see § Design ④).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1604-1616` — the shipped `startNewSession` override + its kdoc at :1592-1603. **The precedent for the vestigial `Session` return**: "the interface forces a `Session` return, but [this frame] yields no session identity … the returned placeholder's identity fields are explicitly unassigned (empty strings, not a fabricated UUID); it is never persisted, never enters `projection`, and the consumer discards it." `changeWorkspace` returns the same shape, for the same reason (no session transition — AC #4).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1627-1630` — the current `changeWorkspace` stub (`throw UnsupportedOperationException`). This body is what you replace.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:628-640` — `mapError`. `conversation.not_found → IllegalArgumentException` (:635-636); every other code → `RelayErrorException`; a malformed error reply → fallback `RelayErrorException` (:633). `changeWorkspace` inherits all three unchanged by calling `sendAndAwaitReply` — **no per-error catch in the body**.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:670-690` — `sendAndAwaitReply` (register-before-send, `IllegalStateException` if pump not `Open`, `finally`-remove). `changeWorkspace` inherits its three throw types unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:916-930` — `upsertConversation`, the atomic confirmed-fold reused verbatim (the `cwd` change becomes visible through it — AC #2).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:337,355` — the inbound demux `when` arm. `TYPE_CONVERSATION_UPDATED` is **already registered** here (:337 routes correlated success replies to the pending deferred at :355). `change_workspace` reuses `conversation_updated`, so — **unlike delete (#532) — there is NO demux change** (the reply type is already routed).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1667-1694` — the private `companion object` write-verb constants: `TYPE_PROMOTE_CONVERSATION` (:1667), `TYPE_RENAME_CONVERSATION` (:1670), `TYPE_DELETE_CONVERSATION` (:1679), and the reply-type `TYPE_CONVERSATION_UPDATED` (:1694, **already present, reused**). Add `TYPE_CHANGE_WORKSPACE` adjacent to the other request-verb constants.
- `app/src/main/java/de/pyryco/mobile/data/network/RenameConversationPayloadDto.kt` (whole file, ~32 lines) — the sibling two-field encode-only DTO to clone the shape + kdoc register of. `ChangeWorkspacePayloadDto` is this with `name` renamed to `cwd`.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` (whole file, 81 lines) — the reply decoder + `toConversation()` (:63). **Reused unchanged**: `conversation_updated` is the exact payload `rename`/`promote`/`archive` already decode. Note `toConversation()` sets `currentSessionId = ""` (:76) — the update reply carries **no** session identity, which is why the returned `Session` cannot be a real session (§ Design ④).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:137-140` — the `changeWorkspace(conversationId, workspace): Session` interface contract (no kdoc today). **Signature is UNCHANGED by this ticket** (see § Design ④ for why — changing the return type cascades across 22 sites).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:273-276,323-360` — the fake's `changeWorkspace` (delegates to `mintNewSession`). **Unchanged.** Read only to confirm it is left alone (§ Design ④, § Open questions).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` (whole file, ~43 lines) — the `#490` guard. Read the kdoc at :24-27: `CancellationException` first, then `RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException` swallowed, and **`IllegalArgumentException` deliberately NOT caught** (crashes). Load-bearing for § Error handling — do not "fix" it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:452-457` — the sole call site, `onWorkspacePicked` (inside `launchGuardedRepoCall`, passing `conversationId`). **Unchanged.** Confirms the id passed is always the currently-open conversation, and that the `Session` return is **discarded**.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:245-255` — `stubMethods_throwUnsupportedOperationNamingTheFollowUp`. Its **only** assertion is `assertUnsupported { repo.changeWorkspace(...) }` (:254). Once wired, `changeWorkspace` no longer throws `UnsupportedOperationException`, so this test **must be deleted in full** (it becomes empty — `changeWorkspace` was the last no-wire stub; see § Testing strategy).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — the `rename_*` suite and the shared reply/error envelope builders (`conversationUpdatedEnvelope`, error-envelope helpers, and the `startRename` starter). **Your `changeWorkspace_*` tests mirror `rename_*` one-for-one.** grep the file for `fun rename_` and `startRename` / `conversationUpdatedEnvelope` to find the exact anchors on `main`.
- Daemon SSOT (already verified for this spec against merged `pyrycode/pyrycode#823`): `pyrycode/internal/protocol/conversations_write.go:107-110` (`ChangeWorkspacePayload{ConversationID, Cwd}`), `codes.go:103` (`TypeChangeWorkspace = "change_workspace"`), `internal/relay/handlers/change_workspace.go` (handler + `$HOME` confinement + static error strings). Field shapes inlined below — you do not need the pyrycode checkout.
- Relevant lessons (Read/grep — codegraph doesn't index markdown): `ktlint filename rule` (a single-top-level-class file must be named after the class → `ChangeWorkspacePayloadDto.kt`); `two-folds-repo-wins-vm-dormant` (the repo's `upsertConversation` is canonical; do not add a VM fold).

## Context

The Workspace Picker affordance is live — the workspace chip (`onWorkspaceChipTapped`) and the "Change workspace…" overflow both open the picker, whose selection calls `ThreadViewModel.onWorkspacePicked(path)` → `launchGuardedRepoCall { repository.changeWorkspace(conversationId, path) }` (`ThreadViewModel.kt:452`). But the data layer has no wire message: `RemoteConversationRepository.changeWorkspace` throws `UnsupportedOperationException("changeWorkspace: no v2 wire message defined …")` (:1630), which the `#490` guard inert-swallows. So picking a workspace against the relay today is a **silent no-op, not a crash** (the ticket body already carries this correction). This ticket replaces that throw with the real round-trip.

`change_workspace` is the fourth conversation write-verb, cloning the family shape after `rename_conversation` (#530, server #820), `archive`/`unarchive` (#549, server #881), and `delete_conversation` (#532, server #822). **"Workspace" IS the conversation's `cwd`** — this codebase has no separate workspace-id concept; the target is a filesystem path (mirroring create/promote `cwd`), not an id.

### Verified daemon SSOT (merged Go, `pyrycode/pyrycode#823`)

- **Request** — type `change_workspace` (`codes.go:103`); `ChangeWorkspacePayload{ ConversationID string \`json:"conversation_id"\`; Cwd string \`json:"cwd"\` }` (`conversations_write.go:107-110`). **Both fields required; the path field is `cwd`, NOT `workspace`** — this resolves AC #1's field-name reconciliation directly against merged Go (no "one-line tag change at integration" left open).
- **Success reply** — type `conversation_updated`, correlated via `in_reply_to` — the daemon "replies with the reused `conversation_updated` record reflecting the new workspace" (`codes.go:95-96`, handler `change_workspace.go:213`). Identical shape to `rename`/`promote`'s reply → decodes through the existing `ConversationResponseDto`. **No new reply type.**
- **Errors** (handler, all non-retryable, all **fixed static strings** — the daemon logs `conn_id` + `conversation_id` only, and **never** echoes the path or the confine error back to the phone):
  - `conversation.not_found` — unknown `conversation_id` (`msgChangeWorkspaceNotFound`).
  - `protocol.malformed` — three sub-cases collapse to this one code: an undecodable payload, an empty path, **and a path rejected by `$HOME` confinement** (escapes `$HOME` after symlink resolution, or is unresolvable — `msgChangeWorkspaceRejected`). The mobile client treats all three identically as an ordinary server error (§ Error handling) — **no special path-rejection handling on the client**.
- **`$HOME` confinement is the daemon's job.** The handler confines the untrusted target path to `$HOME` (fail-closed, strict non-creating confiner) **before** storing it, and stores the *resolved realpath* — the value that was security-confined. The mobile side sends the path and never touches the filesystem with it (§ Security review).
- **No session transition.** `change_workspace` updates the *recorded* `cwd` only; it does **not** re-spawn the live session (explicitly Out-of-Scope in #823). The new folder takes effect on the conversation's **next fresh session spawn**. So there is no `session_transition` (#336) and no session-boundary delimiter (AC #4).
- **No broadcast** — the handler `c.Reply`s the requester only; other connected clients pick up the new `cwd` on their next `list_conversations` (deferred, as in #820/#881/#822).

## Design source

N/A — data-layer wire-up. The Workspace Picker, the workspace chip, the "Change workspace…" overflow, and the `onWorkspacePicked` → guard path already exist and are untouched; no UI is added or modified. Visual-fidelity check intentionally skipped.

## Design

Three changes, all mirroring `rename` (#530) — plus the one family-divergence this verb carries: the return type is `Session`, not `Conversation`.

### ① New encode-only DTO — `data/network/ChangeWorkspacePayloadDto.kt`

A sibling of `RenameConversationPayloadDto` with `name` replaced by `cwd`:

```kotlin
@Serializable
data class ChangeWorkspacePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val cwd: String,
)
```

- File must be named `ChangeWorkspacePayloadDto.kt` (ktlint single-class-filename rule).
- Both fields non-null/required (no Kotlin defaults) — `MobileJson`'s `explicitNulls=false` never elides a non-null `String`, so both keys are always sent. Wire SSOT field names: `conversation_id`, `cwd` (snake_case; `cwd` needs no `@SerialName` — the JSON key is already `cwd`).
- kdoc register mirrors `RenameConversationPayloadDto.kt`: encode-only (the phone sends it; never decodes it), always encode through `MobileJson`, and cite the server SSOT (`ChangeWorkspacePayload`, #823). Note the path field is the conversation's `cwd` (no separate workspace-id concept) and is untrusted → `$HOME`-confined **server-side** (the phone does not validate or canonicalise it — § Security review).

### ② New companion constant

Add to the private `companion object`, adjacent to the other write-verb request constants (near :1667-1682):

```kotlin
const val TYPE_CHANGE_WORKSPACE = "change_workspace"
```

The reply-type constant `TYPE_CONVERSATION_UPDATED` (:1694) already exists and is reused — no second constant, and (unlike delete) **no demux registration** (`conversation_updated` is already routed at :337).

### ③ Replace the `changeWorkspace` body (:1627-1630)

Contract (**signature unchanged**): `override suspend fun changeWorkspace(conversationId: String, workspace: String): Session`.

Behaviour — the `rename` spine (:1520-1544) with a `cwd` payload and a vestigial `Session` return:
1. Build an `Envelope` with `type = TYPE_CHANGE_WORKSPACE` and `payload = MobileJson.encodeToJsonElement(ChangeWorkspacePayloadDto(conversationId = conversationId, cwd = workspace))`. `id = requestId.incrementAndGet()`, `ts = Clock.System.now().toString()`.
2. `val reply = sendAndAwaitReply(request)` — throws on server `error` / not-connected before any state mutation (§ Error handling); the decode + fold below are unreachable on any failure path.
3. `val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()` — the #318 typed decode boundary; a malformed reply throws here, still before the fold.
4. `upsertConversation(conversation)` — atomic confirmed-fold into the read projection, only after the reply decodes. **This is what makes the new `cwd` visible** in the chip / list (AC #2).
5. `return` the vestigial placeholder `Session` — see § Design ④.

The `workspace` path is forwarded **verbatim** — do **not** add any client-side path validation, canonicalisation, or `$HOME` check: confinement is the daemon's job, and the daemon re-validates and rejects out-of-`$HOME` paths server-side (`protocol.malformed`), which the client treats as an ordinary server error. This is a body-only change — the interface, the `Stable`/`Fake` overrides, and every call site (and all 18 test-double overrides) are untouched (zero fan-out).

Add a `changeWorkspace` kdoc mirroring `rename`'s (:1498-1516): describe the round-trip, the `cwd` payload, the confirmed-fold, the daemon-owned `$HOME` confinement, the three throw types (none of which mutate the projection), the **no-session-transition** posture, and the vestigial-`Session`-return rationale.

### ④ Return type — keep `: Session`, return a vestigial placeholder (the load-bearing decision)

The interface declares `changeWorkspace(…): Session`, but the reply is a `conversation_updated` (a `Conversation`, no session), and `toConversation()` sets `currentSessionId = ""` — **the reply carries no session identity at all.** The ticket body delegates the reconciliation to the architect: "Whether to return the conversation's current session or adjust the signature is the architect's call — do **not** invent a session-transition fold."

**Decision: keep the `: Session` signature; return a placeholder `Session` with explicitly-unassigned identity fields**, mirroring `startNewSession` (:1609-1615):

```kotlin
return Session(
    id = "",
    conversationId = conversationId,
    claudeSessionUuid = "",
    startedAt = Clock.System.now(),
    endedAt = null,
)
```

Rationale — **why not change the signature to `Conversation`/`Unit`** (the "more elegant" shape):

- The reply carries no session (`currentSessionId = ""`) and there **is** no session transition (AC #4) — so returning any real/looked-up session would be a fabrication implying a transition that does not happen. The empty-`id` placeholder is honest ("explicitly unassigned, not a fabricated-to-look-real UUID") and matches the blessed `startNewSession` precedent for exactly this "interface forces a `Session` but there is no session identity" situation.
- Changing the return type is a **22-site cascade that cannot be split away**: the interface (1) + `FakeConversationRepository` (1, **body too** — `mintNewSession` returns a `Session`) + `StableConversationRepository` (1) + **18 test-double `override … : Session` sites** across 8 test files (`ThrowingConversationRepository`, `ArchivedDiscussionsViewModelTest` ×6, `DiscussionListViewModelTest` ×4, `SettingsViewModelTest`, `ThreadViewModelTest` ×2, `LiteralScreenViewModelTest`, `ChannelListViewModelTest` ×2, `StableConversationRepositoryTest`) + it would break `FakeConversationRepositoryTest.kt:572` (`changeWorkspace_returnsFreshSession_andUpdatesCwd`). That fan-out (well past the 10-call-site red line) is **inherent to the signature change**, not to the wire logic — splitting the wire work would not reduce it. Keeping `: Session` makes this a pure body-only change (like rename), zero fan-out, S.
- The caller discards the return (`onWorkspacePicked`, `ThreadViewModel.kt:454-455`), so the placeholder is genuinely vestigial — it is never persisted, never enters `projection` (the `upsertConversation` fold in step 4 is the *only* state effect), and the consumer never reads it.

### Data flow

```
ThreadViewModel.onWorkspacePicked(path) (already wired, unchanged)
  └─ launchGuardedRepoCall { repository.changeWorkspace(conversationId, path) }   # #490 guard; return discarded
       └─ RemoteConversationRepository.changeWorkspace
            ├─ encode ChangeWorkspacePayloadDto{conversation_id, cwd} ──▶ Envelope(change_workspace)
            ├─ sendAndAwaitReply ──▶ (pump.send; await correlated reply by in_reply_to)
            │      ├─ not Open        → throw IllegalStateException            (no fold → guard swallows)
            │      ├─ server other err → mapError → RelayErrorException         (no fold → guard swallows)
            │      │                     (incl. protocol.malformed = empty / $HOME-rejected path)
            │      └─ server not_found → mapError → IllegalArgumentException    (no fold → guard does NOT catch → crashes)
            ├─ decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()   # new cwd
            │      └─ malformed reply → decode throws                          (no fold, AC #3)
            ├─ upsertConversation(conversation)  ──▶ projection re-emits
            │      └─ observeConversations → list re-emits (new cwd on the chip / row)   (AC #2)
            └─ return placeholder Session(id = "", …)   # vestigial; caller discards
       (no session_transition, no #336 delimiter — AC #4)
```

The chip / list update from the single `upsertConversation` fold — no VM change (the workspace chip reads the conversation projection, already derived; same pattern rename relies on).

## State + concurrency model

- **No new coroutine / scope.** `changeWorkspace` is a plain `suspend` method called from the existing `launchGuardedRepoCall` (`viewModelScope`, #490). The request↔reply await runs on the shared single inbound collector via `sendAndAwaitReply` — **no second pump subscription**, correlated by `Envelope.id` ↔ reply `in_reply_to`.
- **Single source of state.** The fold is `upsertConversation` (:916), an atomic `MutableStateFlow.update {}` CAS upsert (replace-by-id-else-append). A concurrent authoritative `conversations` snapshot retry-merges rather than clobbering; a re-delivered reply is idempotent. No parallel mutable state; the vestigial `Session` return is not stored anywhere.
- **Ordering.** `sendAndAwaitReply` first (throws before any mutation) → decode → fold → return placeholder. Every failure path is unreachable-before-mutation, so a failed `changeWorkspace` never touches the projection (AC #3).
- **Cancellation / teardown.** Inherited from `sendAndAwaitReply`: registers the deferred before sending, removes it in `finally` (success / error / caller cancellation), and #488's `failAllPending` completes a mid-await deferred with `IllegalStateException` on teardown — so a `changeWorkspace` in flight when the connection drops surfaces as ISE (swallowed by the guard), never hangs.

## Error handling

All error mapping is **centralized** in `mapError` (:628) + `sendAndAwaitReply`'s not-`Open` `check`/`IllegalStateException` (:670+). The `changeWorkspace` body adds **no per-error catch** — it inherits, identically to `rename`:

| Failure | Produced by | Type thrown | Guard (`#490`) behaviour |
|---|---|---|---|
| Not connected (pump not Open) | `sendAndAwaitReply` | `IllegalStateException` | **swallowed** → silent no-op (AC #3) |
| Server `protocol.malformed` (empty / `$HOME`-rejected / undecodable path) | `mapError` else-branch (:638) | `RelayErrorException` | **swallowed** → silent no-op (AC #3) |
| Server `conversation.not_found` | `mapError` (:635-636) | `IllegalArgumentException` | **NOT swallowed → crashes** (by design) |
| Malformed / undecodable reply | `ConversationResponseDto` decode | `SerializationException` / `IllegalArgumentException` | (decode IAE) NOT swallowed |

**AC #2 / AC #3 consistency — same as rename.** AC requires `conversation.not_found → IllegalArgumentException` (family-typed), yet a failed `changeWorkspace` "does not swallow" and "is the consumer's job" (the surfacing slice, #561). Both hold because:

- `GuardedRepoLaunch.kt:26-27` **deliberately does not catch `IllegalArgumentException`** — it treats an unknown-conversation IAE as an impossible-by-construction programming-bug signal and lets it crash. Identical to `rename`/`promote`/`archive`, all launched through the same guard. Do **not** clone delete's not-found-converges catch here: `changeWorkspace`'s contract, like rename's, is *not* tolerant of unknown ids.
- The call site (`onWorkspacePicked`, `ThreadViewModel.kt:455`) always passes `conversationId` — the currently-open, hence server-known, conversation. So `conversation.not_found` is unreachable from the shipped UI (it would require the conversation to be deleted server-side between opening the thread and picking a workspace — same reachability profile as rename). Its IAE-crashes posture is therefore intentional and matches rename exactly.
- The daemon errors that *are* reachable are **not-connected** (ISE) and **`protocol.malformed`** (RelayErrorException, e.g. a user picks a path outside `$HOME` and the daemon rejects it) — both currently swallowed by the guard. Surfacing them to the user is **the surfacing slice's job (#561)**, out of scope here.

**Binding requirement for the developer:** mirror `rename` exactly. Throw IAE for `conversation.not_found`; do **not** add a `changeWorkspace`-specific catch, and do **not** widen the guard to swallow IAE. **No server-supplied error text (`RelayErrorException.message`) is ever shown or logged** on the `changeWorkspace` path — the DTO / encode / decode / fold add no `Log.*` (§ Security review).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`); no instrumented test. Everything below reuses existing fixtures/helpers in `RemoteConversationRepositoryTest.kt`. Add a `startChangeWorkspace` helper (sibling of `startRename` — same shape, `cwd` param instead of `name`); reuse `conversationUpdatedEnvelope` and the error-envelope helpers verbatim. Mirror the `rename_*` suite, with these cases:

- **Codec / request encode.** Drive `changeWorkspace`; assert `pump.sent.single { it.type == "change_workspace" }` has payload exactly `{"conversation_id":"…","cwd":"…"}` (exactly two keys, field name **`cwd`** not `workspace`, no `name`). The **reply-decode** side needs no new codec test — `conversation_updated → ConversationResponseDto` is already covered in `ConversationResponseDtoTest.kt`.
- **Success round-trips + folds the new cwd.** `changeWorkspace(id, "/new")`, push `conversationUpdatedEnvelope(inReplyTo = sent.id, id, name, cwd = "/new")`; assert the conversation appears with `cwd == "/new"` in a concurrently-collected `observeConversations` list (proves the `upsertConversation` fold — the AC #2 surface).
- **Server-authoritative cwd.** The folded `cwd` is the reply's value, not the request's (feed a reply whose `cwd` differs from the request — e.g. the daemon's resolved realpath — to prove the client folds the server's value).
- **Return is the vestigial placeholder.** Assert the returned `Session.id == ""` (and `claudeSessionUuid == ""`) — pins the § Design ④ decision (no fabricated session identity, no transition).
- **Not connected → `IllegalStateException`, list unchanged.** Pump never Open (`FakeSessionPump` default) → `startChangeWorkspace` result is `Result.failure(IllegalStateException)`; projection untouched.
- **`conversation.not_found` → `IllegalArgumentException`, list unchanged.** Push an error envelope with `code = "conversation.not_found"`; result is `IllegalArgumentException`; no fold.
- **Other server error → `RelayErrorException`, list unchanged.** Push an error envelope with an arbitrary code (e.g. `"protocol.malformed"` — the reachable "path outside `$HOME`" case); result is `RelayErrorException` carrying the code; no fold. **(Recommended)** assert the exception carries no path/secret text beyond the daemon's static string.
- **(Recommended, mirrors rename)** Malformed `conversation_updated` reply → decode throws, list unchanged.

**Delete the obsolete stub test.** `stubMethods_throwUnsupportedOperationNamingTheFollowUp` (`RemoteConversationRepositoryTest.kt:245-255`) asserts the old `UnsupportedOperationException` throw as its **only** case — `changeWorkspace` was the last no-wire stub. Once wired it no longer throws UOE, so **delete the whole test function** (do not leave an empty body).

**ViewModel check — already covered, add nothing.** The AC's "a failing `changeWorkspace` does not crash" is already asserted by the guard-swallow suite (`ThreadViewModelTest.kt:~932`), which drives `onWorkspacePicked` (alongside `DeleteConfirm` / `RenameSubmit` / `SaveAsChannelSubmit`) through the guard for `IllegalStateException` / `RelayErrorException` / `UnsupportedOperationException`. Do **not** add a duplicate VM test and do **not** touch `ThreadViewModelTest.kt` (also avoids cross-branch overlap). Note IAE is correctly absent from that test's swallowed list — matching the guard's deliberate no-catch of IAE.

## Scope

Production source files (Kotlin, excluding tests/md/spec): **2** — `ChangeWorkspacePayloadDto.kt` (new) and `RemoteConversationRepository.kt` (modified, body-only). One new exported type (`ChangeWorkspacePayloadDto`). **Zero consumer fan-out** — the signature is unchanged (§ Design ④), so the interface, `Stable`/`Fake` overrides, all 18 test-double overrides, and the sole call site are untouched. Zero new reject branches (error mapping is centralized and reused; no demux change — `conversation_updated` is already routed). ~30 production LOC + ~150 test LOC ≈ ~180 total. Every red line clears with margin (files ≤3, total LOC ≤600, exported types ≤5, call sites 0, reject branches 0, ACs 5) → **size S, no split.** PO's `size:s` is confirmed, not overridden. Branch-overlap check (`git fetch --prune` + `git branch -r` vs `origin/main`, 2026-07-10): no in-flight `origin/feature/*` branch touches `RemoteConversationRepository.kt`, the new DTO file, or the repo test file — no blocker to wire.

## Open questions

None blocking. Deliberate deferrals, all named:

- **Visible failure feedback for `change_workspace`** — deliberately absent here (#490 silent-swallow posture, family-consistent with rename/archive/delete). Surfacing the not-connected / `protocol.malformed` (out-of-`$HOME`) failures to the user is the **surfacing slice, #561** (blocked by this ticket — clones #556's shape off a dedicated 2-type catch, per the split plan).
- **Fake models a session transition; the remote does not.** `FakeConversationRepository.changeWorkspace` mints a fresh session (`mintNewSession`), a session transition the real daemon does **not** perform (#823 changes the recorded `cwd` only — AC #4). This is a benign fake≠remote behavioural divergence, left **untouched**: the AC constrains only the remote wire; aligning the fake would cascade across the same 22 sites as a signature change and break `FakeConversationRepositoryTest.kt:572`, for no in-scope benefit. Evidence-based deferral — no observed failure from the divergence (the fake's job is plausible UI behaviour under full-fake runs). If ever wanted, a dedicated fake-fidelity ticket.
- **Live fan-out to other connected clients** — out of scope in #823 (the daemon replies to the requester only); other clients pick up the new `cwd` on their next `list_conversations`.
- **Operator-facing rung-3 real-claude e2e** — filed separately as **#562** (Inbox), rides the #421 harness (#481/#482 shape), parked behind the family per-mutation `mutationsSupported` reachability gate (#537 / #551).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. Two untrusted↔trusted crossings, both contained. **(a) Inbound:** the `conversation_updated` reply is decoded at the single explicit `ConversationResponseDto` boundary (`toConversation()`, :63) — a malformed reply throws at decode, **before** `upsertConversation`, so no untrusted bytes reach the projection (AC #3). **(b) Outbound:** the `workspace` path is an untrusted, network-paired-party-supplied filesystem path. The mobile client is **not** the confinement authority — it forwards the path verbatim as a wire field and **never touches the filesystem with it**; the daemon confines it to `$HOME` (fail-closed, strict non-creating confiner) *before* storing the resolved realpath (`change_workspace.go`). Adding a client-side path check would be false assurance (the phone cannot know the daemon's `$HOME`) and is deliberately omitted (§ Design ③).
- **[Tokens, secrets, credentials]** N/A — this path handles no tokens/keys/credentials. The Noise_IK transport (`data/network/`, `data/crypto/`) carrying the frame is untouched; `change_workspace` rides the already-established encrypted session. `Envelope.id` is the existing `requestId.incrementAndGet()` monotonic correlation counter (not a secret).
- **[File / storage operations]** N/A on the mobile side — **the security-relevant point for this verb.** No mobile filesystem access: the `cwd` path is never opened, `File`-wrapped, canonicalised, or concatenated into a path on the phone; it is a wire string folded into an in-memory `StateFlow` only. Path traversal / TOCTOU / storage-scope are all server-side concerns the daemon owns (and has handled — confine-before-store, static rejection string). No client atomic-write / `allowBackup` surface is introduced.
- **[Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`Receiver`/deep-link/`PendingIntent`/provider/WebView. Purely an internal repository method behind the DI-wired `ConversationRepository`; the affordance is additionally `mutationsSupported`-gated (dormant until #537/#551).
- **[Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparisons introduced. `conversationId` is matched by ordinary identity in the `upsertConversation` fold (a non-sensitive routing key, not a secret) — no `==`-against-secret concern.
- **[Network & I/O]** No findings. Reuses `sendAndAwaitReply` over the existing `SessionPump` / OkHttp WebSocket transport — no new socket, no timeout/TLS/frame-size config touched. #488's `failAllPending` guarantees a `change_workspace` in flight at teardown fails promptly (ISE) rather than hanging; the reply type (`conversation_updated`) is **already** demux-registered (:337), so a well-formed reply never falls through to `else -> Unit` and hangs (the delete-specific hazard does not apply here).
- **[Error messages, logs, telemetry]** No findings — **and this is the security-relevant heart of the ticket, doubly so because the payload is an untrusted path.** The daemon returns **fixed static** error strings (`msgChangeWorkspaceMalformed` / `msgChangeWorkspaceEmpty` / `msgChangeWorkspaceRejected` / `msgChangeWorkspaceNotFound`) and logs `conn_id` + `conversation_id` only — it **never** echoes the path or the confine error back to the phone. On the client, `RelayErrorException.message` (server-supplied) is **never logged and never surfaced** (the #490 guard's confidentiality invariant), and this spec adds **no `Log.*`** on any `change_workspace` path (encode, decode, fold, or error) — so the untrusted path bytes are never logged either. The surfacing slice (#561) inherits this discipline (surface a generic failure, never the server message).
- **[Concurrency]** No findings. No new coroutine/scope; the fold is an atomic `MutableStateFlow.update {}` CAS (no check-then-mutate TOCTOU); every throw path is ordered strictly before the mutation, so a failed `change_workspace` cannot leave partial/corrupt state (AC #3). The vestigial `Session` return is never shared or stored. No new mutex; no hot/cold-flow change.
- **[Threat model alignment]** The relevant mobile-wire threat — a malicious/compromised relay returning a crafted `conversation_updated` or `error` frame — is contained: a crafted reply either fails the typed decode (no fold) or maps to a swallowed/deliberately-crashing exception with no text leak. A crafted reply that folds an *attacker-chosen* `cwd` into the client's projection is a **display-only** effect (the chip shows a path); it grants no filesystem access on the phone (mobile never uses the path) and the *authoritative* workspace write is the daemon's, `$HOME`-confined — so a hostile relay cannot escalate a bogus `cwd` into a traversal on either side. The user picking a path outside `$HOME` is handled as an ordinary server rejection (`protocol.malformed`), not a client-side vulnerability. Out of scope (named): live cross-client fan-out (#823 defers it); the user-visible failure surface (#561); the operator-run e2e (#562).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10
