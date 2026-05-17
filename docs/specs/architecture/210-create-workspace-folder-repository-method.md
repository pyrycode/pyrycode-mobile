# 210 — `createWorkspaceFolder()` on `ConversationRepository`

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-82` — the interface this ticket extends. Note the existing default-impl pattern on `recentWorkspaces()` (lines 70-81) established by #209 — the new method mirrors it.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:33` — the `recents: MutableStateFlow<List<String>>` storage already in place.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:261-264` — the existing `bumpWorkspace(cwd)` private helper. The new method delegates to it; do NOT change its body.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:266` — `unknown(id)` helper, only for reference — `createWorkspaceFolder` uses `require(...)` for input validation, not this helper.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:21` — `DEFAULT_SCRATCH_CWD = "~/.pyrycode/scratch"`. Relevant only because `bumpWorkspace` filters it; the spec path prefix `pyry-workspace/` cannot collide.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:625-700` — the three `recentWorkspaces_*` tests added by #209. The two new tests for this ticket go directly after them, before the closing brace, following the same `runBlocking` / `Flow.first()` / JUnit 4 conventions.
- `docs/specs/architecture/209-recent-workspaces-repository-method.md` — the spec this one extends. The "default implementation to avoid the 11-fake cascade" decision is explained at length there; this spec re-uses the same rationale without re-litigating it.

## Context

This ticket adds the **write side** of the workspace-picker data surface. #209 (merged in 2f94d62) shipped the read side: `recentWorkspaces(): Flow<List<String>>`, plus the `recents: MutableStateFlow<List<String>>` storage and the private `bumpWorkspace(cwd)` helper on `FakeConversationRepository`.

The Workspace Picker's **Other → Create new folder…** flow (Figma `19:44` reached via `20:2`) needs to create a folder and surface it in the recents stream so the picker can select the returned path. Phase 0 is fake-backed; no filesystem I/O. Phase 4 will satisfy the same contract against the mobile API.

The natural shape: a new `suspend fun createWorkspaceFolder(name: String): String` on the interface, with the fake's implementation built as `bumpWorkspace("pyry-workspace/$name")` after validation. The `recents` storage is already wired; this ticket plugs into it without touching the existing storage shape.

## Design

### Interface change

Add one method to `ConversationRepository`, immediately after `recentWorkspaces()`:

```kotlin
/**
 * Creates a new workspace folder under the `pyry-workspace/` prefix and
 * registers it in the recents stream. Returns the created path string
 * (e.g. `"pyry-workspace/scratch-1"`).
 *
 * Phase 0 implementations operate in-memory only; no filesystem I/O.
 * Phase 4 implementations will create the folder server-side.
 *
 * Throws [IllegalArgumentException] if [name] is blank or whitespace-only.
 * Trimming and basename normalization are caller concerns.
 *
 * Default throws — implementations that do not support folder creation
 * (e.g. test fakes that ignore this surface) inherit the default. The
 * Workspace Picker is the only production consumer; test fakes never
 * invoke this method, so the throwing default is unreachable in tests.
 */
suspend fun createWorkspaceFolder(name: String): String =
    error("createWorkspaceFolder is not implemented for this ConversationRepository")
```

**Why a default implementation, again.** Same rationale as `recentWorkspaces()` in #209's spec: making this method abstract would force a stub into each of the 11 anonymous `object : ConversationRepository` fakes (verified post-#209: still 11, none override `recentWorkspaces`, see `grep -rn "object : ConversationRepository" app/src/test/`). That crosses the architect-side red line of > 10 simultaneous-update consumers and would normally split the ticket. The cascade is structurally unsplittable in Kotlin — interface evolution is all-or-nothing for abstract methods. The escape is a default. This is now the established pattern on this interface (one method, `recentWorkspaces`, already uses it); applying it consistently here preserves the property that "test fakes don't carry stubs for write-side picker methods."

**Why `error(...)` instead of `flowOf(emptyList())`.** `recentWorkspaces()`'s `flowOf(emptyList())` default was semantically meaningful — a repository that does not track workspace history legitimately has no recents. `createWorkspaceFolder` returns a path String; there is no semantically-meaningful zero value. A throw is the correct shape. `error("…")` (which throws `IllegalStateException`) is preferred over `throw NotImplementedError()` because it produces a clearer message at the call site and is consistent with the project's existing `error(...)` use in surrounding code. The throw is unreachable in tests — no test fake ever calls `createWorkspaceFolder`. (If a future test does, it will fail loudly with a clear message; that's the correct failure mode.)

**Validation contract lives on the interface KDoc, enforcement lives in the override.** The KDoc above documents the precondition. The fake enforces it via `require(...)`. A future `RemoteConversationRepository` MUST enforce the same precondition — that's the contract.

### `FakeConversationRepository` override

Add one override, naturally placed after `sendMessage` and before `mintNewSession`:

```kotlin
override suspend fun createWorkspaceFolder(name: String): String {
    require(name.isNotBlank()) { "name must not be blank" }
    val path = "pyry-workspace/$name"
    bumpWorkspace(path)
    return path
}
```

Three behaviors, in order:

1. **Validate.** `require(name.isNotBlank())` throws `IllegalArgumentException` for `""`, `"   "`, `"\t\n"`, etc. Per AC, trimming and basename normalization are NOT this method's job — the caller is responsible for cleaning input before invocation. A name containing a `/` or other unusual characters is accepted (caller's contract).
2. **Construct the path.** Literal concatenation `"pyry-workspace/$name"`. No trimming. The path prefix `pyry-workspace/` is the design convention from the picker spec (Figma `19:44`). The constructed path is guaranteed non-empty (prefix is non-empty) and is guaranteed not to equal `DEFAULT_SCRATCH_CWD` (`"~/.pyrycode/scratch"` has a `~/.pyrycode` prefix; the literal `pyry-workspace/` prefix cannot match). Both invariants matter only because `bumpWorkspace` filters those two cwds — neither filter will fire here.
3. **Bump.** `bumpWorkspace(path)` prepends the path to `recents` with dedup. Same atomic-update semantics as the cwd-touching mutators added by #209 (`createDiscussion`, `promote`, `changeWorkspace`, `startNewSession`). No new storage needed.

The method does NOT need to touch `state: MutableStateFlow<Map<String, ConversationRecord>>` — there is no `Conversation` to create. A workspace folder exists independently of any conversation; the persistence story for "user created a folder but no conversation uses it yet" is the recents-list-only Phase 0 design (see ticket body Technical Notes). Phase 4 will reconcile this with server-side storage.

### Concurrency

Same as #209's bump path. `MutableStateFlow.update { }` is atomic. Concurrent `createWorkspaceFolder` calls serialize through the StateFlow update mechanism; the later write wins position 0. No `Dispatchers` change needed — the method is `suspend` for interface consistency (Phase 4 will be I/O-bound) but the Phase 0 body is non-blocking.

### Error handling

- `IllegalArgumentException` from `require(name.isNotBlank())` — propagates to caller. The Workspace Picker's create-folder dialog is responsible for client-side validation; this is a defense-in-depth check at the data layer.
- No other failure modes in Phase 0. No filesystem; no network.

### Why not `Result<String>`

The codebase convention (per `sendMessage`, `promote`, `createDiscussion`, etc.) is to throw on bad input and return the affected entity directly. A `Result<String>` would deviate. Stay with the throw.

## Testing strategy

Two new unit tests in `FakeConversationRepositoryTest.kt` (JUnit 4 + `runBlocking` + `Flow.first()`, matching existing conventions). Add them at the end of the file, after the three `recentWorkspaces_*` tests added by #209, before the closing brace.

**Test 1 — `createWorkspaceFolder_appearsAtPositionZeroOfRecents`**

- Construct `FakeConversationRepository()`.
- Capture initial `recents = repo.recentWorkspaces().first()` (3 seeded entries from #209).
- Call `val path = repo.createWorkspaceFolder("scratch-1")`.
- Assert `path == "pyry-workspace/scratch-1"` (the exact prefix convention).
- Assert `repo.recentWorkspaces().first().first() == "pyry-workspace/scratch-1"` (position 0).
- Assert new list size is `initial.size + 1` (path is new — no dedup collapse).

**Test 2 — `createWorkspaceFolder_blankName_throwsIllegalArgumentException`**

- Construct `FakeConversationRepository()`.
- Use JUnit 4's `assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.createWorkspaceFolder("  ") } }` — or the equivalent `try { … fail() } catch (e: IllegalArgumentException) { … }` shape already used elsewhere in the file (developer's choice; match what's adjacent).
- Cover whitespace explicitly: `"  "` is the AC-named case. The empty string `""` is also blank; one assertion suffices because `String.isNotBlank()` collapses both cases.

No instrumented tests; pure data-layer logic. No `connectedAndroidTest` impact.

## Open questions

None. All AC items map to concrete decisions above.

## Out of scope

- **Real filesystem creation.** Phase 4. Phase 0 is in-memory only.
- **Conversation creation alongside the folder.** Out of scope. `createWorkspaceFolder` returns a path; binding a conversation to it is the picker's job (subsequent `createDiscussion(workspace = path)` or `changeWorkspace(conversationId, path)` call, both of which already bump recents through the existing wiring).
- **Caller-side trimming / basename normalization.** AC explicitly defers this to the caller. The Workspace Picker's dialog will handle it before invoking this method.
- **Persistence across process death.** Phase 0's in-memory recents list resets on process restart. Persistence is a Phase 4 concern.
