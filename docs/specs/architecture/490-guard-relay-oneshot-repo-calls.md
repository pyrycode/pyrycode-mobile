# #490 — Guard every one-shot repository call in the relay-mode view-models

**Size:** S · **Security-sensitive:** yes (see § Security review) · **Design source:** N/A — no UI-visible change; pure ViewModel guard logic.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:484-577` — the **pattern to mirror**: `sendAnswer` / `sendCancel` (3-arm catch, surfaces an error channel) and `sendInterrupt` / `onDropQueued` (4-arm-shaped, **empty** catch bodies = inert swallow). The nine sites want the **inert-swallow** posture of `onDropQueued`, not the error-channel posture of `sendAnswer`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:424-444, 591-637` — the six ThreadVM launch sites to guard (`sendMessage`, `onWorkspacePicked`, and the `Archive` / `DeleteConfirm` / `RenameSubmit` / `SaveAsChannelSubmit` arms of `onOverflowEvent`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:128-152` — the two `onEvent` launch sites (`CreateDiscussionTapped`, `WorkspacePicked`), both with a follow-on `navigationChannel.send`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt:100-110` — the one `confirmPromotion` launch site.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:78-88, 176-195` — the interface **defaults** for `delete` / `dropQueuedMessage` throw `error(...)` ⇒ `IllegalStateException`; confirms the not-wired throw type for those.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1371-1390` — `archive` / `rename` / `changeWorkspace` throw `UnsupportedOperationException`; `createDiscussion` / `promote` / `sendMessage` are wired (`:1047`, `:1081`, `:1121`).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:37-40, 61-63` — the facade the VMs actually hold: its `live` getter throws `IllegalStateException(NOT_CONNECTED)` when no connection is live, and it propagates the wired throws **verbatim** (adds nothing).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:147` — `RelayErrorException(code, retryable, message)` extends `Exception` (not RuntimeException / ISE); carries the server-supplied `message` that must **never** be logged.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:729-908` — the existing `onInterrupt` / `onDropQueued` swallow + scope-cancellation tests: the **exact harness** to mirror (default-uncaught-exception-handler capture; loop over a `failWith` list; `ViewModelStore().clear()` for the teardown path).
- Memory lesson `catch-illegalstate-swallows-cancellation` — on the JVM `j.u.c.CancellationException extends IllegalStateException`; the `catch (CancellationException) { throw e }` arm **MUST** be first. This is the #451 rework that this ticket must not repeat.

## Context

The nine listed conversation actions launch one-shot repository calls in `viewModelScope` with no `try/catch`. Under `FakeConversationRepository` (today) these never throw, so the gap is invisible. Under the relay repository (behind `USE_RELAY_REPOSITORY`, the flip this ticket unblocks) each can throw:

- **Server error** → `RelayErrorException` (a crafted `error` frame).
- **Not connected** → `IllegalStateException` (the `StableConversationRepository.live` getter).
- **Not-yet-wired method** → `UnsupportedOperationException` (`archive` / `rename` / `changeWorkspace`) or `IllegalStateException` (the `delete` / `dropQueuedMessage` interface-default `error(...)`).

An uncaught throw in `viewModelScope` reaches the default uncaught-exception handler and kills the process. The modal-send paths (`sendAnswer` / `sendCancel` / `onDropQueued` / `sendInterrupt`) already model the correct discipline; these older one-shots predate it. This is the **deterministic** safety net paired with the separate "hide unimplemented actions" UI ticket — different fabric: hiding the affordance is a product decision; guarding the call must hold even if an action slips the UI filter.

## Design

### The shared guard helper (the DRY decision)

The nine catch blocks are byte-identical. Inlining a 4-arm `try/catch` at each site is ~72 lines of production duplication and forces the cancellation-ordering + no-log invariants to be re-proven per site. Instead, introduce **one** module-internal extension function; all nine sites call it. This is the architect's DRY call (the ticket leaves it open) and it is load-bearing for keeping the ticket at S: it collapses ~72 lines to ~15 and lets the cancellation-ordering and confidentiality invariants be verified **once** against the helper.

**New file** `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` (package `de.pyryco.mobile.ui.conversations` — the shared parent of `thread/` and `list/`). It contains **only** this top-level function (no class), so the ktlint single-class-filename rule does not apply:

```kotlin
/**
 * Launches [block] in [viewModelScope] with the relay-mode one-shot guard: rethrow structured
 * cancellation, then inertly swallow the three failure types a relay repository can produce.
 * Never logs a caught exception — RelayErrorException.message is server-supplied (security-sensitive).
 */
internal fun ViewModel.launchGuardedRepoCall(block: suspend () -> Unit) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // MUST be first: j.u.c.CancellationException extends IllegalStateException on the JVM
        } catch (e: RelayErrorException) {
            // Inert: server error swallowed. Never log e.message.
        } catch (e: IllegalStateException) {
            // Inert: not-connected / not-wired-default swallowed.
        } catch (e: UnsupportedOperationException) {
            // Inert: not-yet-wired remote method swallowed.
        }
    }
}
```

Ordering contract (load-bearing, not cosmetic): `CancellationException` first (it is an `IllegalStateException` subclass on the JVM — a bare `catch (ISE)` would swallow `viewModelScope` teardown, the #451 rework). `RelayErrorException`, `IllegalStateException`, `UnsupportedOperationException` are mutually independent (none subclasses another), so their relative order is free; keep the above order for readability parity with `sendAnswer`.

### The nine call-site edits

Each site's edit is mechanical: replace `viewModelScope.launch {` with `launchGuardedRepoCall {`, leaving the block body unchanged. Add `import de.pyryco.mobile.ui.conversations.launchGuardedRepoCall` to each VM. For the four side-effect sites the follow-on `navigationChannel.send(...)` **stays inside** the block, immediately after the repo call — so when the repo call throws, control jumps to the catch and the send is skipped (AC #4). Do **not** hoist the send out of the guard.

| # | File · site | Repo method | Not-connected | Server error | Not-wired | Follow-on side effect |
|---|-------------|-------------|:---:|:---:|:---:|---|
| 1 | ThreadVM `sendMessage` (~424) | `sendMessage` | ISE | RelayError | — (wired) | none |
| 2 | ThreadVM `onWorkspacePicked` (~439) | `changeWorkspace` | ISE | — | **UnsupportedOp** | none |
| 3 | ThreadVM `onOverflowEvent` Archive (~595) | `archive` | ISE | — | **UnsupportedOp** | `navigationChannel.send(PopBack)` |
| 4 | ThreadVM `onOverflowEvent` DeleteConfirm (~604) | `delete` | ISE | — | **ISE** (default `error()`) | `navigationChannel.send(PopBack)` |
| 5 | ThreadVM `onOverflowEvent` RenameSubmit (~613) | `rename` | ISE | — | **UnsupportedOp** | none |
| 6 | ThreadVM `onOverflowEvent` SaveAsChannelSubmit (~623) | `promote` | ISE | RelayError | — (wired) | none |
| 7 | ChannelListVM `CreateDiscussionTapped` (~131) | `createDiscussion` | ISE | RelayError | — (wired) | `navigationChannel.send(ToThread(conv.id))` |
| 8 | ChannelListVM `WorkspacePicked` (~140) | `createDiscussion` | ISE | RelayError | — (wired) | `navigationChannel.send(ToThread(conv.id))` |
| 9 | DiscussionListVM `confirmPromotion` (~103) | `promote` | ISE | RelayError | — (wired) | none |

The "not-wired" column shows which type each site produces on the real path *today*; the guard catches all three at every site regardless, so a later wiring change (e.g. `changeWorkspace` gaining a wire) needs no guard edit.

### Out of scope (scope-discipline guards)

- `ThreadViewModel.retry()` (~432) launches `connectionStateSource.retry()` — **not** a repository call. Leave it untouched.
- `DiscussionListViewModel` `RowTapped` (~83) launches only `navigationChannel.send(...)` — no repo call. Leave it untouched.
- Do **not** add a user-facing error surface (snackbar / error channel) to any of the nine. The ticket requires "fail quietly," and none of these sites has an error surface today; adding one is unrequested scope. Inert swallow is the intended behavior (evidence-based: no observed need for a per-action error toast on these paths).

## State + concurrency model

No change to the state model. Each guarded call remains a `viewModelScope`-scoped one-shot coroutine; the helper adds only a `try/catch` with no suspension point, so scheduling and `advanceUntilIdle()` behavior are identical to today. No new `StateFlow`, no new dispatcher, no shutdown-behavior change. The single-`StateFlow<UiState>`-per-VM invariant is preserved (no state is written from the catch arms).

## Error handling

- **Caught (inert swallow):** `RelayErrorException`, `IllegalStateException`, `UnsupportedOperationException`. Nothing is logged; no state is mutated; no navigation fires.
- **Rethrown:** `CancellationException` — structured cancellation must survive (a cancelled launch, e.g. `viewModelScope` teardown, must not enter a swallow arm). Ordering makes this correct.
- **Deliberately NOT caught (fail-fast):** `IllegalArgumentException`. The interface documents it for *unknown ids*; the nine sites always pass the VM's own current `conversationId`, so an `IllegalArgumentException` here signals a programming bug, not a relay failure — it should crash, not be masked. It is a sibling of `IllegalStateException` (both extend `RuntimeException`), so the ISE arm does **not** catch it. Any other unexpected type (IO, serialization) is likewise uncaught by design — the ticket scopes the guard to the three documented relay failure modes only.
- **Confidentiality:** the catch bodies must not read or log `RelayErrorException.message` (server-supplied). Centralizing this in the helper means the invariant is asserted in exactly one place.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`); no instrumented tests. All three VM test files exist; add to them. Mirror the harness at `ThreadViewModelTest.kt:729-908`.

### Shared throwing double (one new test file)

`app/src/test/java/de/pyryco/mobile/ui/conversations/ThrowingConversationRepository.kt` — a decorator that delegates reads to a real fake and throws a configured `Throwable` from the seven mutation methods the nine sites call:

```kotlin
class ThrowingConversationRepository(
    private val failWith: Throwable,
    private val delegate: FakeConversationRepository = FakeConversationRepository(),
) : ConversationRepository by delegate {
    override suspend fun sendMessage(conversationId: String, text: String) = throw failWith
    override suspend fun changeWorkspace(conversationId: String, workspace: String) = throw failWith
    override suspend fun archive(conversationId: String): Unit = throw failWith
    override suspend fun delete(conversationId: String): Unit = throw failWith
    override suspend fun rename(conversationId: String, name: String) = throw failWith
    override suspend fun promote(conversationId: String, name: String, workspace: String?) = throw failWith
    override suspend fun createDiscussion(workspace: String?) = throw failWith
}
```

Interface delegation (`by delegate`) forwards every `observe*` read to the fake's seeded data so each VM's `state` flow still assembles; only the mutations throw. A `failWith`-only constructor lets tests loop over the three types. (If the developer prefers a per-VM inline double, that is fine — the decorator is the DRY option and keeps the double in one place.)

### Test scenarios (bullets, not code — write in the project idiom)

- **AC #2 swallow — one test per VM**, mirroring `onDropQueued_whenDropFailsInert_isSwallowedWithoutCrashing`:
  - Capture uncaught throws via `Thread.setDefaultUncaughtExceptionHandler` (restore in `finally`).
  - Loop `failWith` over `[IllegalStateException(...), RelayErrorException(code, false, "no"), UnsupportedOperationException(...)]`.
  - For each: build the VM on a `ThrowingConversationRepository(failWith)`, invoke every guarded path for that VM (ThreadVM: all six; ChannelListVM: `CreateDiscussionTapped` + `WorkspacePicked`; DiscussionListVM: `PromoteConfirmed` after opening the promotion dialog), `advanceUntilIdle()`.
  - Assert `uncaught.isEmpty()` — the only proof the typed catch ran (a throw escaping the launched coroutine does **not** fail `runTest`; `viewModelScope` is a separate `SupervisorJob`).
- **AC #4 side-effect skipped** (four side-effect sites): with a `ThrowingConversationRepository`, launch a collector on `navigationEvents`, invoke the site, `advanceUntilIdle()`, assert **no** navigation was emitted (ThreadVM Archive/Delete ⇒ no `PopBack`; ChannelListVM `CreateDiscussionTapped`/`WorkspacePicked` ⇒ no `ToThread`). One combined test per VM is fine.
- **AC #3 cancellation propagates** — one representative test (the guard is shared, so one path proves the ordering for all): gate a mutation (`FakeConversationRepository`-backed double whose overridden method `await()`s a never-completing `CompletableDeferred`) so the launch suspends in-flight; host the VM in a `ViewModelStore`; `store.clear()` to cancel `viewModelScope`; assert no crash and no swallow side effect (mirrors `onDropQueued_scopeCancellationMidSend_propagatesCancellationInert`).
- **AC #5 fake mode unchanged** — no new test; the existing VM suites already exercise these paths against `FakeConversationRepository` and must stay green (the guard is transparent when nothing throws).

## Open questions

- **Helper name.** `launchGuardedRepoCall` is the proposed name; a shorter `launchGuardedRepoCall` → `launchRepoAction` is acceptable if it reads better in situ. Not load-bearing.
- **Test-double placement.** Decorator in a shared test file vs. per-VM inline doubles — the decorator is specified for DRY; the developer may inline if the shared file feels heavier than the duplication it removes.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX — the untrusted datum is `RelayErrorException.message` (server-supplied via a crafted `error` frame). It crosses into exactly one place: the `catch (RelayErrorException)` arm of `launchGuardedRepoCall` (`GuardedRepoLaunch.kt`), which is empty and never reads `e.message`. The boundary is explicit and single (one function, not scattered per site) — the whole point of the DRY decision from a security lens. Nothing downstream holds the exception: no state write, no emitted event, no log. The repo *return* values used by the side-effect paths (`createDiscussion` / `promote` → `Conversation.id` → nav route) are also server-supplied, but that id-handling is pre-existing (#347/#348) and unchanged here; it is a string route key (never a filesystem path / SQL), so a crafted id at worst yields an empty thread — noted, not introduced by this ticket.
- **[Tokens, secrets, credentials]** N/A — the change touches no token generation, storage, logging, or lifecycle. Purely a `try/catch` around existing repo calls.
- **[File / storage operations]** N/A — no filesystem paths, no reads/writes, no path concatenation.
- **[Inter-process / Android attack surface]** N/A — no Intents, deep links, exported components, PendingIntents, ContentProviders, or WebView. VM-internal control flow only.
- **[Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparison of attacker-controlled values.
- **[Network & I/O]** No findings — the guard sits *above* the transport (it catches an already-parsed exception); it adds no socket, frame, timeout, or TLS surface. Adversarial angle checked: the inert swallow performs **no retry**, so a hostile relay spamming `error` frames cannot amplify into a retry storm or token-exhaustion loop — each guarded call is a one-shot per user gesture.
- **[Error messages, logs, telemetry]** No MUST FIX — the core confidentiality requirement (never log `RelayErrorException.message`) is satisfied by the empty, no-log catch bodies centralized in one helper, matching the deliberate no-log posture of `sendAnswer` / `onDropQueued`. **OUT OF SCOPE (named for the record):** the pre-existing `state` cold-read `.catch { emit(Error(e.message ?: …)) }` in `ChannelListViewModel.kt:111-118` and `DiscussionListViewModel.kt:64-71` surfaces a raw exception message to the UI — on the remote path that could be a server-supplied `RelayErrorException.message`. That is the *read* flow, not one of the nine one-shot mutation launches this ticket guards, and it predates this change. Recommend a follow-up ticket to sanitize those two `.catch` arms (generic copy only). Not gated on here; do **not** expand #490 to touch it (scope discipline).
- **[Concurrency]** No findings — a strength: the guard *improves* cancellation safety. Every guarded call stays in `viewModelScope` (dies with the VM); the `catch (CancellationException) { throw e }`-first ordering preserves structured cancellation (the load-bearing #451 invariant). No new shared state, mutex, or check-then-mutate; no catch arm writes a `StateFlow`. Mid-send process death cancels the coroutine → cancellation rethrown → the side-effect `send` is skipped and no partial state remains (the one-shots hold no optimistic local mutation to roll back).
- **[Threat model alignment]** Aligned — this ticket *is* an availability hardening against the untrusted-relay threat (protocol-mobile.md § Security model): it removes a remote-triggerable process-crash (uncaught throw in `viewModelScope`). No mobile-specific surface (screenshot / overlay / accessibility / deep link) is opened, since the swallow is inert and produces no UI change.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-03
