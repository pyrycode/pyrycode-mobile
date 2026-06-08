# LiteralScreenViewModel

The **state-management half** of the manual "show the literal screen" feature ([#378](../codebase/378.md)).
A thread-scoped ViewModel that orchestrates one [`ConversationRepository.requestScreenSnapshot`](conversation-repository.md)
read and hoists the result as MVI state. The render surface and the thread entry-point action are the
sibling slice **#379** (`blockedBy #378`), which consumes this state and owns Koin registration.

`security-sensitive`: the snapshot text is server-originated and may carry sensitive on-screen content,
so it is held **verbatim** and **never logged** — see [Confidentiality](#confidentiality-the-whole-point).

## What it does

The screen snapshot is the **always-available, parser-independent floor** of pyrycode ADR 025's
safe-degradation strategy (pyrycode#596): a one-shot text picture of the current claude screen, rendered
server-side, depending on no screen parser. The data path shipped in [#375](../codebase/375.md)
(`requestScreenSnapshot(conversationId): String` on the [repository](remote-conversation-repository.md)).

`LiteralScreenViewModel` issues that read for the current conversation, tracks the in-flight state, and
surfaces success or a retryable failure — so #379's surface can present the literal screen as hoisted
`state: StateFlow<LiteralScreenUiState>` + `onEvent(LiteralScreenEvent)`.

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenViewModel.kt`. Four public
top-level types co-located in one file (mirrors [`ThreadViewModel`](thread-screen.md) /
[`ScannerViewModel`](scanner-screen.md) — the ktlint single-public-class filename rule does not fire when
*several* public types share a file; see [[ktlint-filename-rule-single-class]]).

## Shape

```kotlin
sealed interface LiteralScreenUiState {
    data object Loading : LiteralScreenUiState           // in flight; also the initial state
    data class Content(val text: String) : … {           // the rendered screen text, verbatim
        override fun toString() = "Content(text=<redacted ${text.length} chars>)"
    }
    data class Error(val reason: LiteralScreenError) : … // closed enum reason, no server string
}

enum class LiteralScreenError { UnknownConversation, ServerError, NotConnected }

sealed interface LiteralScreenEvent {
    data object Request : LiteralScreenEvent             // initial fetch, fired once on open
    data object Retry : LiteralScreenEvent               // re-fetch after an error
}

class LiteralScreenViewModel(savedStateHandle: SavedStateHandle, repository: ConversationRepository)
```

- **Initial state is `Loading`** — the surface opens straight into a spinner and fires `Request`. The VM
  does **not** auto-fire in `init` (kept purely event-driven, matching `ScannerViewModel`); the surface
  contract is `LaunchedEffect(Unit) { onEvent(Request) }`.
- **`conversationId`** is read once from `savedStateHandle.get<String>("conversationId").orEmpty()` and
  fixed for the VM lifetime, so `Retry` re-issues against the same id (no remembered-id state). Only this
  non-sensitive route arg is read from `SavedStateHandle`.
- **Both events drive one private `load()`** — `Request` and `Retry` are behaviorally identical; the
  distinction is purely semantic for the surface.

## How it works

### Latest-request-wins

`load()` cancels the previous `loadJob` before launching a new one in `viewModelScope`, so a stale slow
read can never overwrite newer state. The VM does no manual dispatcher switching — `viewModelScope` runs
on `Dispatchers.Main.immediate` and the suspend repository call moves itself off-Main where the IO
boundary needs to (that boundary lives in the repository).

### Cancellation footgun

```kotlin
try {
    _state.value = Content(repository.requestScreenSnapshot(conversationId))
} catch (e: CancellationException) {
    throw e            // MUST precede the generic catch — propagate structured cancellation
} catch (e: Exception) {
    _state.value = Error(reasonFor(e))
}
```

The `CancellationException` re-throw **must come first**. If the generic `catch (Exception)` caught it,
cancelling the prior `loadJob` (or scope teardown on screen exit) would be mis-mapped into a spurious
`Error` state. This is the load-bearing concurrency invariant; a bare `runCatching`/catch-all would break
it.

### Error mapping

`reasonFor(e)` maps each failure path of `requestScreenSnapshot` to a closed enum:

| Failure | Thrown type | → state |
|---|---|---|
| Unknown conversation | `IllegalArgumentException` | `Error(UnknownConversation)` |
| Server error | `RelayErrorException` | `Error(ServerError)` |
| Not connected | `IllegalStateException` | `Error(NotConnected)` |
| Unexpected | any other `Exception` | `Error(ServerError)` (defensive fallback) |

The three mapped types are disjoint siblings — `RelayErrorException` extends `Exception` directly, not
`RuntimeException` (`MobileWireModels.kt:105`), so the `when` ordering cannot mis-classify. Every reason is
retryable; "retryable" is expressed by the `Retry` event *existing*, not by a per-reason boolean. The error
branch carries **only** the enum — never the server-supplied `RelayErrorException.message` — so there is no
leak path through failure.

### Confidentiality (the whole point)

The snapshot text crosses from sensitive-server-data → process state at exactly one point: the `load()`
try-block wrapping the read into `Content`. The concern is **confidentiality, not integrity** — the text is
held verbatim and never parsed/interpreted, so there is no injection surface, only a leak surface. Two
layers guard it (belt-and-suspenders, different fabric):

- **Stochastic:** no `Log.*` / `println` / `Timber` anywhere in the file.
- **Deterministic:** `Content.toString()` is redacted (`<redacted N chars>`), mirroring
  [`ScannerUiState.Decoded.toString`](scanner-screen.md). This defeats an accidental `Log.d("$state")` and
  prevents a crash reporter from capturing the text via `toString`. It overrides only `toString` — not
  `equals`/`hashCode` — so `assertEquals(Content("x"), …)` still holds. The redaction is test-pinned.

The text lives **only** in the in-memory `MutableStateFlow`. It is never written to `SavedStateHandle`,
which would serialize it to the disk-backed instance-state bundle. The snapshot is re-fetched on the next
open, never restored across process death.

## Wiring

- **Constructed directly in unit tests** for #378. **Koin registration is #379's job** — it will
  `viewModelOf(::LiteralScreenViewModel)` in `AppModule` and obtain it via `koinViewModel()` within the
  thread back-stack-entry scope (where `SavedStateHandle` carries `conversationId`). #378 deliberately does
  not touch `AppModule`, avoiding cross-branch file overlap with #379.
- **Security obligations propagated to #379** (itself `security-sensitive`): (a) scope the VM
  **per-conversation** — to the thread's nav back-stack entry, never a process/activity singleton — or one
  conversation's `Content.text` bleeds into the next; (b) render `Content.text` without logging it and
  **without `rememberSaveable`**; (c) set `FLAG_SECURE` on the snapshot window to block
  screenshot / screen-recording / overlay capture.

## Testing

Unit only (`./gradlew testDebugUnitTest` — no Compose, no device). Coroutines driven with
`Dispatchers.setMain(StandardTestDispatcher())` + `runTest`. A small controllable `FakeSnapshotRepository`
(modeled on `RecordingConversationRepository`) overrides only `requestScreenSnapshot` with a configurable
`suspend (String) -> String` outcome and records `calls` / `lastConversationId`; the other 11 abstract
members are `UnsupportedOperationException` stubs. Scenarios: success → `Content` (byte-for-byte verbatim,
with embedded whitespace/newlines), `toString` redaction, each failure type → its `Error` reason,
`Request` invokes the read with the route id (`calls == 1`), and a real `Error → Loading → Content` retry
transition gated on a `CompletableDeferred` (`calls == 2`).

## Edge cases / limitations

- **Permanent `Loading` if the transport never returns.** The VM imposes no call deadline; an in-flight
  read is only bounded by `viewModelScope` cancellation on screen exit. A `sendAndAwaitReply` deadline is a
  SHOULD-FIX flagged upstream (transport layer), not gated on this slice — availability, not
  confidentiality.
- **No error copy here.** `Error` carries only the enum reason; user-facing strings (and any future
  `RelayErrorException.message` passthrough for `ServerError`) are #379's concern — an additive change owned
  by the surface, deliberately not added now to keep the error state free of server strings.
- **Not gated on the `interactive` capability** (pyrycode#369) — read-only screen viewing sits outside the
  per-device permission gate (ADR 025 § Security model), matching the [#375](../codebase/375.md) read.

## Related

- [#378 implementation notes](../codebase/378.md) · spec `docs/specs/architecture/378-literal-screen-snapshot-viewmodel.md`
- [Conversation repository](conversation-repository.md) / [Remote conversation repository](remote-conversation-repository.md) — `requestScreenSnapshot`, the consumed read ([#375](../codebase/375.md))
- [Scanner screen](scanner-screen.md) — `ScannerViewModel` / `ScannerUiState.Decoded.toString` redaction precedent
- Sibling surface slice **#379** — renders this state + the thread entry-point action (blocked on #378)
- pyrycode ADR 025 § Safe degradation / Security model · pyrycode#596 (Phase 2 structured streaming) · pyrycode#618 (daemon snapshot handler)
