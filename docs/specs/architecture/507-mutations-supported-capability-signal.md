# 507 — Expose a "mutations supported" capability signal on the conversation repository

**Ticket:** [#507](https://github.com/pyrycode/pyrycode-mobile/issues/507)
**Size:** S (foundation half of #491's 4+4 split; complements the shipped one-shot-guard #490)
**Security-sensitive:** No (client-side implementation property — the fake supports mutations, the remote does not — not untrusted wire input; per ticket body)

## Files to read first

Read these before writing code. Line ranges are anchors verified against `main` (2026-07-04); they drift a little as you edit — grep the symbol if a number is off.

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-55` — the interface header and the `observeStall` (:32-43) / `observeQueue` (:45-55) **default-cascade doc pattern to mirror**. Your new property's KDoc copies this shape (a member with a "supported/no-op" default that the fake and inline test doubles inherit for free).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:69-86` — the `delete` throwing default; same cascade family, second doc model.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1371-1390` — the throwing `archive` / `unarchive` / `rename` / `startNewSession` / `changeWorkspace` overrides. **Place the `false` override alongside these** (it's the same "relay can't do mutations yet" story).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1017-1030` — the `observeStall` / `observeQueue` overrides, for the class's override style.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:46-79` — the facade class, the `live` getter (:61-63, **the exact getter-reads-`.value` shape your override must copy**), and the `observeStall` → `switchToLive(false)` delegation (:74, the conceptual fail-safe-deny model — see § Design for why you cannot literally reuse `switchToLive` here).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:82-101` — the `ThreadUiState` data class (add one trailing field).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:110-149` — the constructor / class header (where to capture the snapshot `val`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:215-255` — the `state` combine's `ThreadUiState(...)` (:224-246) and the `.stateIn(... initialValue = ThreadUiState(...))` (:247-255). **Both** get the new field.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:60` — confirms the fake implements `ConversationRepository`. **Do not touch it** — it inherits `true` (correct: the fake supports every mutation).
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:31-90` — hand-fake (`RecordingConversationRepository`), the null → repoA → null → repoB churn pattern, and the absent-connection assertion shape. Note the file's memory-anchored rule: drive with `runCurrent()`, not `advanceUntilIdle()`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:60-100` — the `makeVm(handle, repo)` helper, `FakeConversationRepository()` (true), the imported `ThrowingConversationRepository` double, and the existing `state_initialValue_…` test (which stays green — see § Testing strategy).

## Context

In relay mode the conversation-mutation methods are not yet implemented: `archive`, `unarchive`, `rename`, `startNewSession`, `changeWorkspace` throw `UnsupportedOperationException` (`RemoteConversationRepository.kt:1371-1390`), and `delete` inherits a throwing default. The fake repository (the default Koin binding) implements every mutation.

Two UI surfaces present these actions — the thread overflow menu and the Channel Info sheet — so a **single capability signal exposed off the repository** (rather than a relay-vs-fake check duplicated inside each composable) is what keeps the eventual gating coherent. **This ticket ships that signal and threads it into thread state only; it touches no composable.** The signal is dormant-but-testable: present in `ThreadUiState`, consumed by nothing. #508 (the consumer, `blockedBy` this ticket) reads it to hide the actions on both surfaces.

## Design

Four production edits, three of them one-liners. The signal is a plain `Boolean` — not a flow — because the mode is static per build config (fake-vs-relay is a Koin module swap, not a runtime toggle), so a **snapshot read at `ThreadViewModel` construction** is sufficient (ticket § Technical Notes).

### 1. Interface member — `ConversationRepository.mutationsSupported`

Add a read-only `Boolean` property with a `true` default, documented in the `observeStall` / `observeQueue` / `delete` default-cascade style.

**Critical form — use a default getter, not an initializer.** Interface properties cannot have backing fields, so `val mutationsSupported: Boolean = true` is a compile error ("Property initializers are not allowed in interfaces"). The correct default is:

```kotlin
/** KDoc mirroring the observeStall/observeQueue/delete cascade — see § below. */
val mutationsSupported: Boolean get() = true
```

Place it near the other default-cascade members (after `observeQueue`, before `createDiscussion`, is a natural home). KDoc content to mirror: state that implementations without relay support (the fake, inline test doubles) inherit `true` and need no override; name the same cascade family (`observeStall` / `observeQueue` / `delete`); state that `RemoteConversationRepository` overrides it to `false` alongside its throwing mutation methods.

The fake and every test double inherit `true` for free — **no cascade** (confirmed: the only production implementers are `FakeConversationRepository`, `StableConversationRepository`, `RemoteConversationRepository`; `RecordingConversationRepository` and `ThrowingConversationRepository` in tests both inherit the default).

### 2. Remote override — `false`

In `RemoteConversationRepository`, alongside the throwing overrides at `:1371-1390`. A class **can** have a backing field, so an initializer is fine here:

```kotlin
override val mutationsSupported: Boolean = false
```

A one-line KDoc pointing at the throwing mutation methods above it is enough (why: relay has no v2 wire message for these mutations yet).

### 3. Facade delegation — fail-safe-deny

In `StableConversationRepository`, delegate to the live repository's value, reporting `false` when no connection is live.

**Critical form — this MUST be a getter, not an initializer.** The facade is a process-lifetime singleton constructed once at app start, when `currentRepository.value` is `null`. An initializer (`override val mutationsSupported = currentRepository.value?.mutationsSupported ?: false`) would evaluate **once at facade construction** → `false` forever, never reflecting a live relay. It must read `.value` live on each access, exactly like the existing `live` getter (:61-63):

```kotlin
override val mutationsSupported: Boolean
    get() = currentRepository.value?.mutationsSupported ?: false
```

`switchToLive(false)` (the `observeStall` delegation at :74) is the **conceptual** model — fail-safe-deny `false` while absent — but it is `Flow`-typed and cannot be reused for a plain `Boolean`; the `?: false` on the `.value` read is the direct analog. A one-line KDoc noting the fail-safe-deny (`false` when no connection is live, consistent with the facade's absent-connection posture) suffices.

### 4. Thread state — carry the snapshot

**`ThreadUiState`** (`ThreadViewModel.kt:82`): add one trailing field, defaulted `true`:

```kotlin
val mutationsSupported: Boolean = true,
```

Trailing + defaulted → every existing `ThreadUiState(...)` construction site (all use named args: previews in `ThreadScreen.kt`, the `androidTest` fixtures, the `ThreadScreenMapperTest` cases) compiles unchanged.

**`ThreadViewModel`**: capture the repository's capability **once** at construction into a private `val`, then use that captured value in **both** the `state` combine's `ThreadUiState(...)` and the `.stateIn` `initialValue`'s `ThreadUiState(...)`:

- Capture (class body, near the top): `private val mutationsSupported: Boolean = repository.mutationsSupported`
- In the combine result (`:224-246`): add `mutationsSupported = mutationsSupported,`
- In `initialValue` (`:251-254`): add `mutationsSupported = mutationsSupported,`

Capturing once (rather than reading `repository.mutationsSupported` twice) guarantees the combine value and the `initialValue` never disagree — the snapshot-at-construction semantics the ticket calls for. Reading through the facade at construction time is exactly where the facade's null-connection → `false` fail-safe-deny takes effect.

### Data flow

```
fake graph (default):    FakeConversationRepository (inherits true)
                              → ThreadViewModel snapshot = true → ThreadUiState.mutationsSupported = true   (end-to-end true, AC #5)

relay graph:             RemoteConversationRepository (false)
                              → StableConversationRepository facade:
                                    connection live  → delegates → false
                                    no connection    → fail-safe-deny → false
                              → ThreadViewModel snapshot = false → ThreadUiState.mutationsSupported = false
```

No composable reads `ThreadUiState.mutationsSupported` in this ticket (dormant-but-testable). #508 consumes it.

## State + concurrency model

Nothing new. The signal is a plain immutable `Boolean` snapshotted at `ThreadViewModel` construction — no `StateFlow`, no `viewModelScope` job, no dispatcher choice. It rides the existing `state: StateFlow<ThreadUiState>` combine as a constant field (the combine already recomputes `ThreadUiState` on every input change; the constant simply travels along). The single source of state per ViewModel is preserved — `mutationsSupported` lives only inside `ThreadUiState`, sourced from one captured `val`.

**Accepted limitation (by design, not an open question):** because the VM captures the snapshot at construction, a relay connection that lands *after* VM construction does not flip the captured value. This is intended — the mode is static per build config (Koin swap), and while no connection is live the fail-safe-deny `false` is the safe default for a gating consumer (a user who isn't connected sees the impossible actions hidden, not offered). #508 owns any future dynamic re-read if one is ever needed; it is out of scope here.

## Error handling

No new failure modes. The property is a pure read:
- Interface default getter: returns `true`, cannot throw.
- Remote override: constant `false`, cannot throw.
- Facade getter: reads `currentRepository.value` (a `StateFlow` snapshot, never throws) and null-coalesces to `false`. Unlike the one-shot mutation methods, it does **not** throw `IllegalStateException` when no connection is live — a capability query must always answer, and the safe answer is `false`.

## Testing strategy

Unit tests only (JVM, compiled by the mandatory `test` gate — **not** `androidTest`). No instrumented tests; no composable is touched. Hand fakes, no MockK, consistent with both existing test files.

**`StableConversationRepositoryTest`** — add one test for capability delegation + fail-safe-deny. Drive a `MutableStateFlow<ConversationRepository?>` directly (no coordinator/pump), `runCurrent()` not `advanceUntilIdle()`:
- With `current.value = null` → `facade.mutationsSupported` is `false` (fail-safe-deny, no connection live).
- With `current.value` = a double reporting `true` (the existing `RecordingConversationRepository` inherits `true`) → `facade.mutationsSupported` is `true`.
- With `current.value` = a double overriding `mutationsSupported = false` → `facade.mutationsSupported` is `false`.
- Churn back to `null` → `false` again (proves the getter re-reads live, not a construction-time snapshot).

For the `false`-reporting double, the cleanest shape is a one-off `ConversationRepository by <delegate>` that overrides only `mutationsSupported = false` (the same delegate-double pattern #490's tests used), or a minimal inline double — developer's choice.

**`ThreadViewModelTest`** — add tests that `state.value.mutationsSupported` reflects the injected repository:
- `makeVm(handle, FakeConversationRepository())` → `vm.state.value.mutationsSupported` is `true` (fake inherits `true`; assert without collecting, reading the `initialValue`, and also after a `collect{}` so both the `initialValue` and combine paths are covered).
- `makeVm(handle, <repo reporting false>)` → `vm.state.value.mutationsSupported` is `false`. Build the `false` repo as a `ConversationRepository by FakeConversationRepository()` overriding `mutationsSupported = false` (keeps all other fake behaviour so `makeVm` works unchanged).

**Existing tests stay green — verify, don't rewrite:** `state_initialValue_isConversationIdPlaceholderBeforeSubscription` (`ThreadViewModelTest.kt:81-91`) asserts `vm.state.value` equals `ThreadUiState(conversationId = "seed-channel-personal", displayName = "seed-channel-personal")`. Both sides now carry `mutationsSupported = true` (the data-class default on the expected side; the fake's inherited `true` on the actual side), so the equality still holds. No change to this test.

## Acceptance criteria (from the ticket)

1. `ConversationRepository.mutationsSupported: Boolean` with default `true`, documented in the `observeStall` / `observeQueue` / `delete` cascade style; fake + inline doubles inherit `true` with no override. *(§ Design 1)*
2. `RemoteConversationRepository` overrides `mutationsSupported` to `false`, alongside its throwing mutation overrides. *(§ Design 2)*
3. `StableConversationRepository` delegates to the live value and reports `false` when no connection is live (fail-safe-deny). *(§ Design 3)*
4. `ThreadUiState.mutationsSupported` (default `true`); `ThreadViewModel` populates it from the injected repository in **both** the state combine and the `initialValue`. *(§ Design 4)*
5. No UI behaviour change; the signal is present in thread state, consumed by no composable; fake mode reports `true` end-to-end. *(§ Data flow, § Testing strategy)*

## Open questions

None blocking. The snapshot-at-construction vs. runtime-reconnect behaviour is resolved above as an accepted, documented design decision (dynamic re-read, if ever needed, is #508's concern).
