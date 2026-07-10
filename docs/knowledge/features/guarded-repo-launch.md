# Guarded repo launch (`launchGuardedRepoCall`)

The shared safety net for one-shot conversation-action repository calls launched from a `ViewModel`. Introduced
in [#490](../codebase/490.md) so that under the relay repository a failed action (send, create-discussion,
archive/rename/delete/promote) **fails quietly** instead of crashing the process. `change-workspace` dropped out
of this list in [#561](../codebase/561.md) — see § Callers below; `archive` is documented here as still-listed
but has been off the guard since [#556](../codebase/556.md) (pre-existing doc/code debt both tickets deliberately
left alone — [[clone-pattern-default-trap]]).

Package: `de.pyryco.mobile.ui.conversations` (the shared parent of `thread/` and `list/`). File:
`GuardedRepoLaunch.kt` — a single top-level `internal` extension function, no class (so ktlint's
single-class-filename rule doesn't apply — see [[ktlint-filename-rule-single-class]]).

## What it does

```kotlin
internal fun ViewModel.launchGuardedRepoCall(block: suspend () -> Unit) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // MUST be first: j.u.c.CancellationException extends IllegalStateException on the JVM
        } catch (e: RelayErrorException) {
            // Inert: server error swallowed. Never log e.message (server-supplied).
        } catch (e: IllegalStateException) {
            // Inert: not-connected / not-wired interface-default swallowed.
        } catch (e: UnsupportedOperationException) {
            // Inert: not-yet-wired remote method swallowed.
        }
    }
}
```

Launches `block` in `viewModelScope`, rethrows structured cancellation, then **inertly swallows** the three
failure types a relay-backed [`ConversationRepository`](conversation-repository.md) can produce for these older
one-shot actions. Nothing is logged, no state is mutated, no navigation fires from a catch arm. The posture
mirrors `ThreadViewModel.onDropQueued` / `sendInterrupt` (empty catch bodies), **not** the error-channel
posture of `sendAnswer` / `sendCancel` — these sites have no error surface and the ticket requires "fail
quietly."

## Why it exists

Before #490 the nine sites were bare `viewModelScope.launch { repository.<verb>(…) }` with no `try/catch`.
Under [`FakeConversationRepository`](conversation-repository.md) (Phase 0/2) these never throw, so the gap was
invisible. Under the relay repository (behind `USE_RELAY_REPOSITORY`) each can throw:

| Failure | Type | Source |
| --- | --- | --- |
| Server error | `RelayErrorException` | a crafted `error` frame ([`MobileWireModels.kt`](mobile-protocol-v2-wire-layer.md)) — extends `Exception`, carries the server-supplied `message` |
| Not connected | `IllegalStateException` | [`StableConversationRepository`](stable-conversation-repository.md)'s `live` getter throws `NOT_CONNECTED` |
| Not-yet-wired method | `UnsupportedOperationException` | historical only as of [#561](../codebase/561.md) — every method the remaining seven guarded sites call (`sendMessage`, `delete`, `rename`, `promote`, `createDiscussion`) is live-wired; `archive` ([#549](../codebase/549.md)) and `changeWorkspace` ([#560](../codebase/560.md)) are wired too but left the guard entirely (`archive` in [#556](../codebase/556.md), `changeWorkspace` in [#561](../codebase/561.md)) before their stubs could matter here. Kept in the guard's catch set for defense-in-depth, not because any current caller can still produce it |
| Not-wired interface default | `IllegalStateException` | the `delete` / `dropQueuedMessage` interface-default `error(…)` |

An uncaught throw in `viewModelScope` reaches the default uncaught-exception handler and **kills the process**.
The guard is the **deterministic** half of a belt-and-suspenders pair with the separate "hide unimplemented
actions" UI ticket — hiding the affordance is a product decision; the guard must hold even if an action slips
the UI filter (different fabric).

## The two load-bearing invariants

- **`CancellationException` first.** On the JVM `java.util.concurrent.CancellationException extends
  IllegalStateException`, so a bare `catch (IllegalStateException)` before it would swallow `viewModelScope`
  teardown and break structured cancellation (the [#451](../codebase/451.md) rework). Rethrowing it first is
  mandatory; the other three arms are mutually independent (none subclasses another) so their relative order is
  free. See [[catch-illegalstate-swallows-cancellation]].
- **Never log the caught exception.** `RelayErrorException.message` is server-supplied; centralizing the catch
  here means the confidentiality invariant lives in **one** place instead of nine. The catch bodies are empty
  by design.

**`IllegalArgumentException` is deliberately not caught.** The interface documents it for *unknown ids*, but the
call sites always pass their own current `conversationId` — so an IAE signals a programming bug, not a relay
failure, and should crash (fail-fast). It's a `RuntimeException` sibling of ISE, so the ISE arm doesn't catch
it. Any other unexpected type (IO, serialization) is likewise uncaught by design — the guard is scoped to the
three documented relay failure modes only.

## Callers (originally nine one-shot sites, now seven)

Each edit was mechanical: `viewModelScope.launch {` → `launchGuardedRepoCall {`, body unchanged. Two sites have
since left the guard for their own surfacing path (own coroutine, own catch set, own one-shot error channel —
see [Thread overflow menu](thread-overflow-menu.md) § ViewModel dispatcher): `onOverflowEvent` Archive →
`sendArchive()` / `archiveErrors` ([#556](../codebase/556.md)), and `onWorkspacePicked` → `sendChangeWorkspace()` /
`changeWorkspaceErrors` ([#561](../codebase/561.md)). Both are omitted from the table below; they no longer call
`launchGuardedRepoCall`.

| VM | Site | Repo method | Follow-on side effect (inside the guard) |
| --- | --- | --- | --- |
| [ThreadViewModel](thread-input-bar.md) | `sendMessage` | `sendMessage` | none |
| [ThreadViewModel](thread-overflow-menu.md) | `onOverflowEvent` DeleteConfirm | `delete` | `send(PopBack)` |
| [ThreadViewModel](thread-overflow-menu.md) | `onOverflowEvent` RenameSubmit | `rename` | none |
| [ThreadViewModel](thread-overflow-menu.md) | `onOverflowEvent` SaveAsChannelSubmit | `promote` | none |
| [ChannelListViewModel](channel-list-viewmodel.md) | `CreateDiscussionTapped` | `createDiscussion` | `send(ToThread(id))` |
| [ChannelListViewModel](channel-list-viewmodel.md) | `WorkspacePicked` | `createDiscussion` | `send(ToThread(id))` |
| [DiscussionListViewModel](discussion-list-viewmodel.md) | `confirmPromotion` | `promote` | none |

For the three remaining **side-effect** sites the follow-on `navigationChannel.send(...)` stays **inside** the
block, after the repo call — so a throwing repo call jumps to the catch and the send is skipped (a failed delete
does not pop back; a failed create does not navigate to a thread). Do **not** hoist the send out of the guard.

The guard catches all three types at every site regardless of which one that site's real path produces *today*,
so a later wiring change (e.g. `delete` moving off the interface default) needs no guard edit.

**Not routed through the guard** (scope-discipline): `onWorkspacePicked` → `changeWorkspace` and `onOverflowEvent`
Archive → `archive` — see § Callers above, both left for a dedicated surfacing path rather than the shared
silent swallow. `ThreadViewModel.retry()` launches
`connectionStateSource.retry()` (not a repo call — see [Thread screen](thread-screen.md)); `DiscussionListViewModel`
`RowTapped` launches only `navigationChannel.send(...)` (no repo call). Both left as bare `viewModelScope.launch`.

## Related & limitations

- **This guards the mutation *write* path, not the *read* path.** The `state` cold-flow
  `.catch { emit(Error(e.message ?: …)) }` in [`ChannelListViewModel`](channel-list-viewmodel.md) /
  [`DiscussionListViewModel`](discussion-list-viewmodel.md) surfaces a raw exception message to the UI — on the
  remote path that could be a server-supplied `RelayErrorException.message`. #490 deliberately did **not** touch
  it (a separate read flow); sanitizing those two `.catch` arms to generic copy is a **recommended follow-up**.
- **Inert swallow, no error surface.** None of the seven remaining guarded sites shows the user a per-action
  error today; adding a snackbar / error channel is a later, evidence-driven decision, not implied by the guard.
  `archive` ([#556](../codebase/556.md)) and `changeWorkspace` ([#561](../codebase/561.md)) are the two sites
  that graduated off this posture — see § Callers above.
- Adding a **new** relay-mode one-shot conversation action? Launch it via `launchGuardedRepoCall`, keep any
  follow-on navigation inside the block, and don't log the caught exception. If the action needs a user-visible
  failure surface instead of a silent swallow, clone `sendChangeWorkspace`/`changeWorkspaceErrors`
  ([#561](../codebase/561.md)) or `sendArchive`/`archiveErrors` ([#556](../codebase/556.md)) instead — see
  [Thread overflow menu](thread-overflow-menu.md) § ViewModel dispatcher.
- Ticket note: [`../codebase/490.md`](../codebase/490.md). Sibling relay hardening: [#488](../codebase/488.md).
