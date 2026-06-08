# #378 — Literal-screen snapshot ViewModel + request state

**Size:** S · **Security-sensitive:** yes · **Figma:** N/A (no UI in this slice)
**Split from #372.** Sibling #379 (`blockedBy #378`) renders the surface + the thread entry-point action and consumes this ViewModel's state.

## Design source

N/A — state-management half only; no Compose, no wire types, no navigation. The render surface and its visual design land in #379.

## Context

The "show the literal screen" feature gives the user a parser-independent view of the current claude screen — the always-available floor of the Phase-2 degrade strategy (pyrycode#596, ADR 025 § Safe degradation). The data path shipped in #375: `ConversationRepository.requestScreenSnapshot(conversationId): String` is on `main`.

This slice is the **state-management half**: a ViewModel that orchestrates one snapshot request against that repository read and exposes the result as hoisted MVI state (`StateFlow<UiState>` + `onEvent`). The snapshot text is **server-originated and may contain sensitive screen content** — it must be carried verbatim and never logged. That is the whole reason this slice is `security-sensitive`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:120-137` — the `requestScreenSnapshot` contract: verbatim-text guarantee + the **three thrown exception types** this VM maps (`IllegalArgumentException` = unknown conversation, `RelayErrorException` = server error, `IllegalStateException` = not connected).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt:9-115` — the **canonical shape for this VM**: pure event-driven `MutableStateFlow<UiState>` + `asStateFlow()` + `onEvent(Event)`, sealed `UiState`/`Event` co-located, and the **redacting `toString()` precedent** (`ScannerUiState.Decoded.toString` / `QrDecoded.toString`) you will mirror on the `Content` state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:95-115, 201-210` — the thread-scoped VM pattern: `conversationId` read from `SavedStateHandle.get<String>("conversationId")`, and `viewModelScope.launch` driving a `suspend` repo call. Co-locates many public types in one file with no ktlint complaint (confirms the co-location is fine).
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:21-110` — a `Loading`/`Loaded`/`Error` sealed `UiState` next door; the `Error(message)` shape and the `viewModelScope.launch` + failure-handling idiom.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:95-109` — `RelayErrorException(code, retryable, message)`: what it carries, and the doc note that `message` never carries user content. Import path: `de.pyryco.mobile.data.network.RelayErrorException`.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:220-289` — `RecordingConversationRepository`: the **exact test-double pattern** to copy — implement the abstract methods, stub the out-of-scope ones with `UnsupportedOperationException`, record calls, configurable result. Your double extends this with a configurable *throwing* outcome.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:298` — the fake's `requestScreenSnapshot`: returns a canned string for a known id, throws `IllegalArgumentException` for an unknown one. Covers success + unknown-conversation in tests; the other two failure types need the controllable double below.
- Memory `ktlint-filename-rule-single-class` — co-locating multiple public top-level types in one `.kt` is fine (the rule only fires for a *single* public type whose name ≠ filename). All four public types live in `LiteralScreenViewModel.kt`.
- Memory `gradle-single-test-class-task` — run one class with `./gradlew testDebugUnitTest --tests "<FQCN>"` (`test --tests` fails).

## Design

One new production file, one new test file. **No `AppModule` / Koin change, no Compose, no navigation** — registering the VM and obtaining it via `koinViewModel()` is #379's job (it owns the surface). This slice's ACs are satisfied by the VM + unit tests that construct it directly.

### File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenViewModel.kt`

Package `de.pyryco.mobile.ui.conversations.thread`. Four public top-level types co-located (mirrors `ThreadViewModel.kt`):

**`LiteralScreenUiState`** — sealed interface, single source of state:

```kotlin
sealed interface LiteralScreenUiState {
    data object Loading : LiteralScreenUiState
    data class Content(val text: String) : LiteralScreenUiState {
        // Security net (AC#4): the verbatim screen text must never reach a log/crash
        // dump via an accidental "$state". Mirrors ScannerUiState.Decoded.toString.
        // Does not touch equals/hashCode — assertEquals(Content("x"), …) still holds.
        override fun toString(): String = "Content(text=<redacted ${text.length} chars>)"
    }
    data class Error(val reason: LiteralScreenError) : LiteralScreenUiState
}
```

`text` is held **verbatim** — the VM passes the repository string straight into `Content` with no `trim`/parse/sanitize (AC#4). Initial state is `Loading` (the surface opens straight into a spinner and fires `Request`; see surface contract below).

**`LiteralScreenError`** — closed enum, one value per failure path (AC#3). Carrying a closed enum rather than the raw exception/message keeps the error state free of any server-supplied string and makes the test matrix exact:

```kotlin
enum class LiteralScreenError { UnknownConversation, ServerError, NotConnected }
```

All three are retryable (the surface always offers Retry), so no per-reason `retryable` flag is needed — "retryable" is expressed by the `Retry` event existing, not by a boolean.

**`LiteralScreenEvent`** — sealed interface, two events (AC#1). Behaviorally identical (both invoke the internal load); the distinction is semantic for the surface, so the developer should not give them divergent logic:

```kotlin
sealed interface LiteralScreenEvent {
    data object Request : LiteralScreenEvent   // initial fetch, fired when the surface opens
    data object Retry : LiteralScreenEvent     // re-fetch after an error
}
```

**`LiteralScreenViewModel`** — constructor `(savedStateHandle: SavedStateHandle, repository: ConversationRepository)`, mirroring `ThreadViewModel`:

- `conversationId = savedStateHandle.get<String>("conversationId").orEmpty()` — fixed for the VM lifetime, so `Retry` re-invokes with the same id (no remembered-id state needed).
- `private val _state = MutableStateFlow<LiteralScreenUiState>(Loading)`; expose `val state: StateFlow<LiteralScreenUiState> = _state.asStateFlow()`. **Single source of state** — no parallel mutable state.
- `fun onEvent(event: LiteralScreenEvent)` — both `Request` and `Retry` call `load()`.
- `private fun load()` — contract (≤15 lines; see invariants below, asserted by the tests):
  - cancel the previous in-flight job, then `loadJob = viewModelScope.launch { … }` (latest-request-wins; keeps the VM correct under any event ordering without the surface having to serialize).
  - set `_state.value = Loading`.
  - `try { _state.value = Content(repository.requestScreenSnapshot(conversationId)) }`
  - **`catch (e: CancellationException) { throw e }`** — must precede the generic catch, or cancelling the prior job is swallowed and structured cancellation breaks. This is the load-bearing footgun; do not use a bare `runCatching` / catch-all here.
  - `catch (e: Exception) { _state.value = Error(reasonFor(e)) }`
- `private fun reasonFor(e: Exception): LiteralScreenError` — `when (e) { is IllegalArgumentException -> UnknownConversation; is RelayErrorException -> ServerError; is IllegalStateException -> NotConnected; else -> ServerError }`. The three types are disjoint siblings (order among them is irrelevant); `else` is a defensive fallback for any unexpected throwable.

**No `Log.*`/`println` anywhere in the file** (AC#4). The text never leaves the VM except through `Content`, whose `toString()` is redacted.

### State + concurrency model

- One `viewModelScope`-scoped job per request, tracked in `loadJob`, cancelled before each relaunch. Dispatcher: default (`viewModelScope` → `Dispatchers.Main.immediate`); the `suspend` repository call moves itself off-Main where it needs to (the VM does no manual dispatcher switching — the IO boundary is the repository's).
- `StateFlow` is hot and conflated — the surface collects via `collectAsStateWithLifecycle` (in #379). On screen exit the VM is cleared, `viewModelScope` cancels, and any in-flight `requestScreenSnapshot` is cancelled cooperatively.
- **The snapshot text lives only in the in-memory `MutableStateFlow`.** Do **not** write `Content`/`text` into `SavedStateHandle` (and #379 must not hoist it via `rememberSaveable`) — saved-state is serialized into the disk-backed instance-state bundle, which would persist sensitive screen content across process death. `SavedStateHandle` is read for `conversationId` only (a non-sensitive route arg). The snapshot is re-fetched on the next open, never restored.
- **Surface contract (for #379, not implemented here):** fire `LiteralScreenEvent.Request` exactly once when the snapshot surface opens — `LaunchedEffect(Unit) { onEvent(Request) }`. The VM does not auto-fire in `init` (keeps it purely event-driven and matches `ScannerViewModel`).

### Error handling

| Failure (from `requestScreenSnapshot`) | Thrown type | → UiState |
|---|---|---|
| Unknown conversation | `IllegalArgumentException` | `Error(UnknownConversation)` |
| Server error | `RelayErrorException` | `Error(ServerError)` |
| Not connected | `IllegalStateException` | `Error(NotConnected)` |
| Unexpected | any other `Exception` | `Error(ServerError)` (fallback) |

Every error is retryable via the `Retry` event. No error state carries any server-supplied string — only the closed enum reason — so there is no leak path through the error branch (the snapshot text only ever returns on success). User-facing copy per reason is #379's concern.

## Testing strategy

Unit only — `./gradlew testDebugUnitTest` (no instrumented test; no Compose). Drive coroutines with `kotlinx-coroutines-test`: `Dispatchers.setMain(StandardTestDispatcher())` in `@Before` / `Dispatchers.resetMain()` in `@After`, bodies in `runTest`.

**Test double** — a small local repository fake modeled on `RecordingConversationRepository` (StableConversationRepositoryTest:226-289): implement the abstract `ConversationRepository` members as `UnsupportedOperationException` stubs, override `requestScreenSnapshot` with a configurable outcome and a call counter. One double covers every scenario:

- ctor takes a `suspend (String) -> String` outcome lambda; success tests return a string, failure tests `throw` the specific exception, the loading-transition test awaits a `CompletableDeferred`.
- record `calls: Int` / `lastConversationId: String?` to assert invocation and the id passed.

(The ticket's "no new test double" note holds only for success + unknown-conversation via `FakeConversationRepository`; the `RelayErrorException` / `IllegalStateException` paths and the retry call-count need this controllable double. It is ~one override + ~11 trivial stubs.)

Scenarios (inputs → expected; developer writes the bodies in the project idiom):

- **Success → Content (verbatim):** double returns a fixed string containing leading/trailing whitespace + internal newlines/control chars → after `onEvent(Request)` + `advanceUntilIdle()`, state is `Content` and `text` **equals the input byte-for-byte** (asserts no trim/sanitize, AC#4).
- **`Content.toString()` redaction:** `Content("super-secret-screen").toString()` does **not** contain `"super-secret-screen"` and does contain `"redacted"` (deterministic "never logged" net, AC#4).
- **Unknown conversation → `Error(UnknownConversation)`:** double throws `IllegalArgumentException`.
- **Server error → `Error(ServerError)`:** double throws `RelayErrorException(code, retryable, message)`.
- **Not connected → `Error(NotConnected)`:** double throws `IllegalStateException`.
- **Request invokes the read with the route's id:** after `onEvent(Request)`, `calls == 1` and `lastConversationId` equals the `SavedStateHandle("conversationId")` value.
- **Retry re-invokes (AC#5) + loading→content transition (AC#2):** drive to `Error` (first outcome throws), then configure a *suspending* success outcome and `onEvent(Retry)`; with `StandardTestDispatcher` assert state is `Loading` while the deferred is uncompleted (`runCurrent()`), then complete it, `advanceUntilIdle()`, assert `Content` and `calls == 2`. This single test exercises both the real `Error → Loading → Content` transition and "retry re-invokes the read".

`./gradlew test`, `./gradlew lint`, `./gradlew spotlessCheck` must pass (AC#5).

## Open questions

- **Koin registration is deferred to #379.** #378's ACs need only direct construction in tests. #379 registers `viewModelOf(::LiteralScreenViewModel)` in `AppModule` and obtains it via `koinViewModel()` within the thread back-stack-entry scope (where `SavedStateHandle` carries `conversationId`). Flagged here so #379's architect picks it up; keeps #378 from touching `AppModule` (and avoids any cross-branch file overlap there).
- **Error copy / server `message` passthrough deferred.** The `Error` state carries only the enum reason. If #379 later wants to show `RelayErrorException.message` for `ServerError`, that is an additive change owned by the surface slice — not added now (no observed need; keeps the error state free of server strings).
- **Security obligations that propagate to #379 (the surface, itself `security-sensitive`):** (a) scope this VM **per-conversation** — to the thread's nav back-stack entry, **never** a process/activity singleton — or one conversation's `Content.text` bleeds into the next; (b) render `Content.text` without logging it and without `rememberSaveable`; (c) set `FLAG_SECURE` (or equivalent) on the snapshot window to block screenshots / screen-recording / overlay capture of the sensitive screen text. These are out of scope here (no window/Compose in this slice) and land in #379's own security-review pass.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. The snapshot text crosses from sensitive-server-data → process state at exactly one point — the `load()` try-block wrapping `requestScreenSnapshot` into `Content`. The concern is **confidentiality, not integrity**: the VM never parses, interprets, or builds a path/command from the text (it is held verbatim by contract), so there is no injection surface — only a leak surface, addressed under Logging below. The carry-verbatim/never-log obligation propagates to the #379 consumer (noted in Open questions).
- **[Tokens, secrets, credentials]** N/A — this slice generates, stores, and compares no tokens. The snapshot text may *contain* on-screen secrets, but it is treated wholesale as sensitive (see Logging); there is no credential lifecycle here.
- **[File / storage operations]** No MUST FIX. The text is in-memory only; no disk write, no path construction, no cache file. Concrete guard added to the spec (State + concurrency model): the text must **not** be placed in `SavedStateHandle`/`rememberSaveable`, which would serialize it to the disk-backed instance-state bundle. Only the non-sensitive `conversationId` is read from `SavedStateHandle`.
- **[Inter-process / Android attack surface]** N/A — no exported component, intent-filter, deep link, content provider, or WebView in this slice. The VM is internal and reached only by its (single, blocked) consumer.
- **[Cryptographic primitives]** N/A — no RNG, hashing, or key handling introduced.
- **[Network & I/O]** No MUST FIX here; owned upstream. The VM issues no network call directly — `requestScreenSnapshot` runs over the Noise/WS transport built in prior slices (frame caps, TLS, timeouts live there). VM-level residual: a transport that never returns leaves a permanent `Loading` (availability, not confidentiality), bounded in practice by `viewModelScope` cancellation on screen exit. SHOULD FIX upstream (a `sendAndAwaitReply` call deadline) — noted, not gated on this slice.
- **[Error messages, logs, telemetry]** No MUST FIX — this is the load-bearing category and is covered by two layers (belt-and-suspenders, different fabric): the stochastic rule "no `Log.*`/`println` in the VM" **plus** the deterministic `Content.toString()` redaction (mirrors `ScannerUiState.Decoded.toString`), which defeats an accidental `Log.d("$state")` and prevents crash-reporter capture of the text via `toString`. The `Error` state carries only a closed enum — no server string, no snapshot text — so the failure path has no leak. A unit test asserts the `toString` redaction (the deterministic net is test-pinned).
- **[Concurrency]** No MUST FIX. Single `viewModelScope`-owned `loadJob`, cancelled before each relaunch (latest-request-wins; no stale slow response can overwrite newer state). `catch (CancellationException) { throw e }` precedes the generic catch, so job-cancellation and scope-cancellation are never mis-mapped into an `Error` state. `onEvent` is main-thread (UI) per the codebase convention (`ScannerViewModel`/`ThreadViewModel`), so the non-atomic `loadJob` swap is not raced. Per-collector leak across screens is avoided by per-conversation VM scoping — an obligation flagged to #379.
- **[Threat model alignment]** Mobile-specific leakage of the rendered text — screenshots, screen recording, overlay/accessibility capture — is real for this sensitive surface but has **no surface in this slice** (no window/Compose). OUT OF SCOPE → #379 (FLAG_SECURE on the snapshot window), captured in Open questions; #379 is itself `security-sensitive` and runs its own pass.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
