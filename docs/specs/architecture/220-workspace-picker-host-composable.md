# 220 — `WorkspacePicker` host composable (Figma 20:2)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt:1-79` — the stateless sheet this host wraps. Note the public `WorkspacePickerSheet(...)` entry that owns the `ModalBottomSheet` chrome and the internal `WorkspacePickerSheetContent(...)` that the previews target. The host renders the public form (not the content seam) because it needs the bottom-sheet shell at runtime.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt:1-103` — the stateless dialog the host overlays on top of the sheet on the "Create new folder" path. Note the public `CreateFolderDialog(onCreate, onDismiss, modifier)` signature; the host calls only that.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:70-101` — the two repository surfaces this host consumes: `recentWorkspaces(): Flow<List<String>>` (read, default empty) and `suspend fun createWorkspaceFolder(name: String): String` (write, default throws). Phase 0 fake implements both; tests use the same fake.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:50-60` — the established Koin-from-composable convention: `import org.koin.compose.koinInject` then `val x = koinInject<T>()`. Mirror this for the repository lookup.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` (skim, ~30 lines) — confirms `single { FakeConversationRepository() } bind ConversationRepository::class` is already registered. No DI changes for this ticket.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialogTest.kt` — sibling androidTest layout to mirror exactly: `@RunWith(AndroidJUnit4::class)`, `createComposeRule()`, `PyrycodeMobileTheme` wrap, `onNodeWithText` / `hasSetTextAction` lookups, `var captured: T? = null` capture pattern, JUnit 4 `assertEquals` / `assertNull`. No MockK, no Turbine.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheetTest.kt` — second androidTest sibling; confirms the `hasContentDescription("Close")` lookup for the sheet's X icon. Useful if a host test wants to dismiss the sheet to assert `onDismiss` plumbing.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:23-65,186` — the fake's `recents: MutableStateFlow<List<String>>` and `createWorkspaceFolder` override. Tests construct `FakeConversationRepository()` directly and pass it to the testing-seam composable; the seam pattern is identical to `CreateFolderDialogInternal` in #213.
- `docs/specs/architecture/212-workspace-picker-sheet.md` — child sheet's design rationale; cross-reference for the "scratch sentinel and dedup are the caller's responsibility" decision. The repo's `recentWorkspaces()` already filters scratch and the empty cwd at the bump-write layer (#209's `bumpWorkspace`), so the host passes the list through unchanged.
- `docs/specs/architecture/213-create-folder-dialog.md` — child dialog's design rationale; confirms the dialog trims internally before invoking `onCreate(trimmedName)`. The host receives an already-trimmed `name` from the dialog callback and can hand it to `createWorkspaceFolder` without re-trimming.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-2

This host renders **no new visual surface of its own** — it composes the already-implemented `WorkspacePickerSheet` (Figma `20:2`, ticket #212) and `CreateFolderDialog` (Figma `19:44`, ticket #213). The host's responsibility is sequencing and repository binding, not pixel layout. Visual fidelity to Figma is the children's contract and was validated when those tickets landed; this ticket adds no chrome, no extra Compose nodes, no styling overrides.

## Context

Three near-term entry points need to open the same workspace-picker flow (Channel List FAB long-press, `#137`'s empty-thread chip, `#208`'s thread overflow "Change workspace…" item). Each one would otherwise duplicate the same wiring: hold a "show sheet" flag, collect `recentWorkspaces()`, hold a "show create dialog" flag, call `createWorkspaceFolder(name)` on submit, route the picked path back to its own ViewModel. That's ~30 lines of identical glue per consumer.

This ticket factors that glue into one reusable host composable: `WorkspacePicker(visible, onPicked, onDismiss, modifier)`. Consumers contribute one `Boolean` to their `UiState` (the established `pendingPromotion`-style pattern from `DiscussionListViewModel`) and one callback to forward the picked path. Everything else — repository observation, sheet ↔ dialog sequencing, write-side invocation, single-call guarantees — lives inside the host.

This ticket lands the host plus its androidTest. It does NOT wire any consumer screen. The first consumer (Channel List FAB long-press) is a separate slice (next ticket after this one) and will edit `ChannelListScreen` + `ChannelListViewModel`; it depends on this ticket landing first.

## Design

### Public composable signature

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt`

```kotlin
@Composable
fun WorkspacePicker(
    visible: Boolean,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Notes on the signature:

- **Visibility `public` (no `internal`).** Unlike the two children (`internal`), this host is the package's public contract — consumer screens in sibling packages (`ui/conversations/list/`, `ui/conversations/thread/`) call it directly. Kotlin's default top-level visibility is `public`; do not add `internal`.
- **`visible: Boolean` is a hoisted prop.** Per ticket Technical Notes, each consumer owns its own visibility flag (a `combine` arm on the screen's `UiState`, mirroring `DiscussionListViewModel.pendingPromotion` at line 26/48/53). When `visible == false` the host renders nothing (early return, no `remember`, no `LaunchedEffect`); the M3 `ModalBottomSheet` and `AlertDialog` exit animations are owned by their respective children — the consumer setting `visible = false` is what kicks off the sheet's dismiss animation.
- **`onPicked: (String) -> Unit`.** Single callback for both "user tapped a recent row" and "user created a new folder and we got back a path." The consumer cannot distinguish; both deliver a workspace path the screen should bind. This is intentional — for the three consumers listed in Context, the next action (apply the workspace to a conversation, or seed a new discussion's workspace) is the same regardless of whether the path was recent or freshly created.
- **`onDismiss: () -> Unit`.** Fires only when the user dismisses the **sheet** (close icon, scrim tap, back-press while the dialog is not open, or sheet drag-down). Cancelling the create-folder dialog does NOT fire `onDismiss` — the sheet stays visible, the dialog just closes. This is explicit per AC #2 and #4 below.
- **`modifier: Modifier = Modifier`.** Standard Compose hygiene. Forwarded to the underlying `WorkspacePickerSheet`; allows consumers to attach semantics or test tags. Has no effect when the host is invisible.
- **No `repository` parameter on the public signature.** The host obtains it via Koin (AC #2). A `private` testing-seam composable accepts the repository directly — see § Internal seam below.

### Internal seam for testability

The androidTest cannot easily bootstrap Koin (no existing androidTest in this repo uses Koin; doing so would require a `KoinTestRule` and per-test `startKoin`/`stopKoin` plumbing that doesn't exist elsewhere). The established pattern in this codebase, set by #213's `CreateFolderDialogInternal`, is a private testing-seam composable that takes the dependency as a parameter; the public form delegates to the seam with the Koin-injected dependency.

```kotlin
@Composable
fun WorkspacePicker(
    visible: Boolean,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val repository = koinInject<ConversationRepository>()
    WorkspacePickerInternal(
        repository = repository,
        onPicked = onPicked,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
internal fun WorkspacePickerInternal(
    repository: ConversationRepository,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) { /* body — see § Body below */ }
```

Notes:

- The seam is `internal` (not `private`) because the androidTest sits in the same package (`de.pyryco.mobile.ui.conversations.components`) but a different source set; `internal` is the minimum visibility that crosses source sets within the same module.
- The seam is the testing-seam shape, not a real API. Consumer screens MUST call the public `WorkspacePicker`; the seam exists only to bypass Koin in tests.
- The early-return `if (!visible) return` lives in the public composable, NOT in the seam. The seam is always-visible — testing it independent of the visibility prop is correct because the visibility flag is trivially-correct conditional rendering and doesn't need a test.

### Body

Inside `WorkspacePickerInternal`, three pieces of state:

```kotlin
val recents by repository.recentWorkspaces().collectAsStateWithLifecycle(initialValue = emptyList())
var showCreateDialog by rememberSaveable { mutableStateOf(false) }
val scope = rememberCoroutineScope()
```

And the layout:

```kotlin
WorkspacePickerSheet(
    recent = recents,
    onPick = onPicked,
    onCreateNew = { showCreateDialog = true },
    onDismiss = onDismiss,
    modifier = modifier,
)
if (showCreateDialog) {
    CreateFolderDialog(
        onCreate = { name ->
            showCreateDialog = false
            scope.launch {
                val path = repository.createWorkspaceFolder(name)
                onPicked(path)
            }
        },
        onDismiss = { showCreateDialog = false },
    )
}
```

Behavior derivation, AC by AC:

- **AC #2a "host obtains the repository via Koin."** `koinInject<ConversationRepository>()` in the public composable. No constructor injection (composables don't have constructors); same shape as `MainActivity.kt:59`.
- **AC #2b "collects `recentWorkspaces(): Flow<List<String>>` as the sheet's recent list."** `collectAsStateWithLifecycle(initialValue = emptyList())` matches the project's established pattern (`MainActivity.kt:61,63`). The `initialValue = emptyList()` means the sheet renders without a Recent header on first composition until the flow's first emission arrives — but since `FakeConversationRepository.recents` is a `MutableStateFlow` with three seeded entries, the first emission is synchronous; the empty-initial path is exercised only by an empty fake (covered by an androidTest below).
- **AC #2c "tapping a recent row invokes `onPicked(path)`."** Passed directly: `onPick = onPicked`. No wrapping, no transformation. The sheet emits `onPick(path)` exactly when the user taps a row.
- **AC #2d "tapping Create-new opens the existing `CreateFolderDialog`."** `onCreateNew = { showCreateDialog = true }`. The dialog renders below the sheet block; both are composed simultaneously when `showCreateDialog == true` because `AlertDialog` lives in its own window above the `ModalBottomSheet`, so they don't conflict visually or composition-wise.
- **AC #2e "submitting the dialog calls `repository.createWorkspaceFolder(name)` exactly once and forwards the returned path to `onPicked(returnedPath)`."** The submit path flips `showCreateDialog = false` synchronously *before* launching the coroutine. Once flipped, the dialog leaves composition, so a second `onCreate` invocation from the same dialog instance is structurally impossible — the user can't double-tap-submit. The `scope.launch { … }` runs in the host's `rememberCoroutineScope`, which is tied to the host's composition (not the dialog's), so it survives the dialog's dismissal. Inside the launch: await `createWorkspaceFolder(name)`, then call `onPicked(path)` synchronously. Phase 0's fake `createWorkspaceFolder` is non-suspending in practice (a `MutableStateFlow.update` then return); the `suspend` modifier is for Phase 4's network call. The `name` passed in is already trimmed by `CreateFolderDialog` (see #213 § Internal state — the dialog trims before invoking `onCreate`); the host does not re-trim.
- **AC #2f "cancelling the dialog from within the picker leaves the sheet visible and does NOT invoke `onDismiss`."** `onDismiss = { showCreateDialog = false }` flips only the dialog flag. The sheet is unaffected. The host's outer `onDismiss` is not called. (The M3 `AlertDialog`'s `onDismissRequest` covers Cancel, scrim tap, and back-press, all routing to this same flag-flip.)
- **AC #2g "dismissing the sheet itself invokes `onDismiss`."** `onDismiss = onDismiss` on the sheet — close-icon tap, scrim tap, drag-down, or back-press while the dialog is not open. The consumer's outer `onDismiss` fires; the consumer typically flips its `visible` flag to false, the host re-renders with `visible == false`, returns nothing, and the sheet's exit animation runs as `ModalBottomSheet` leaves composition.
- **AC #3 "when `visible` is false the host renders nothing."** `if (!visible) return` in the public composable. Returning before any `remember`/`koinInject`/`LaunchedEffect` means no work is done at all when the host is invisible — no flow subscription, no Koin lookup, no coroutine scope allocation. This is intentional: hosts that consumers leave permanently mounted (e.g. as a sibling to a screen body) should cost zero when invisible.

### Single-invocation guarantee

AC #2e demands `createWorkspaceFolder(name)` is called **exactly once** per submit. Two failure modes to rule out:

1. **User rapidly taps the dialog's Create button.** Eliminated by flipping `showCreateDialog = false` BEFORE launching the coroutine. The dialog unmounts; subsequent taps cannot reach the now-unmounted button. Verified by AC #2f: the cancel-dialog path also flips the flag, so the dialog's lifecycle is fully owned by `showCreateDialog`.
2. **Recomposition fires `onCreate` twice for the same Click.** Compose's click handling is debounced internally to one invocation per click — the framework already gives us this. Documented for completeness.

What the host does NOT guarantee: idempotence of `createWorkspaceFolder` itself. If the user dismisses the dialog and immediately re-opens it and submits the same name, `createWorkspaceFolder` is called twice (once per submit) — that's two separate writes, both correct per the fake's semantics (`bumpWorkspace` dedups, so position 0 is updated). That's not a "double-invocation" of a single submit; it's two distinct submits.

### Composition order: sheet first, dialog second

The dialog block sits AFTER the sheet block in the composable body. M3 `AlertDialog` and `ModalBottomSheet` each manage their own `Popup`/`Window`, so source order does not affect Z-order (the dialog's window is always above the sheet's window). The order is chosen for readability: sheet is the persistent surface; dialog is the conditional overlay.

### Cancellation behavior on consumer-driven dismiss mid-write

If the consumer flips `visible = false` while a `createWorkspaceFolder` coroutine is in flight (e.g. user dismisses the sheet via scrim tap before the Phase 4 network call returns), `rememberCoroutineScope` cancels the in-flight job when the host leaves composition. `onPicked` is NOT called. In Phase 0 this is unreachable in practice (the fake is non-suspending); in Phase 4, this is the documented behavior — the host treats consumer-driven dismiss as a cancel signal. If a future Phase-4 ticket needs at-least-once semantics for the write, the scope choice changes (e.g. `applicationScope`) — flag as out of scope here.

## State + concurrency model

- **`viewModelScope` jobs:** none. The host is not a ViewModel.
- **`StateFlow`s exposed by the host:** none. The host exposes nothing; it consumes `repository.recentWorkspaces()` and renders.
- **Flow choice:** cold collect via `collectAsStateWithLifecycle(initialValue = emptyList())`. Hot would be inappropriate — the host's lifetime is `visible == true`; subscribing while invisible has no consumer.
- **Dispatcher:** Main (Compose default). `repository.createWorkspaceFolder` is `suspend` but Phase 0's implementation is non-blocking; `Dispatchers.IO` is not requested. Phase 4 will revisit.
- **Coroutine scope:** `rememberCoroutineScope()` for the on-submit launch. Tied to the host's composition; cancels when the host leaves composition (i.e. when `visible` flips false). Acceptable for Phase 0.
- **Shutdown / cancellation on screen exit:** when the consumer's screen leaves composition (e.g. navigation pop), all composables in its tree exit — including the host (whether visible or not). The flow collection stops; the coroutine scope cancels. Standard Compose teardown.
- **`rememberSaveable` for `showCreateDialog`:** survives configuration changes (rotation) within a single host instance, which preserves "user opened the create-folder dialog, then rotated" UX. When `visible` flips false → true again, the host has been re-mounted, so the saveable state is fresh (dialog starts closed). This is desired.

## Error handling

- **`repository.recentWorkspaces()` failure modes.** None in Phase 0 (the fake's `MutableStateFlow` cannot fail). The cold flow's `collect` is the boundary; if Phase 4's flow ever emits a terminal error, `collectAsStateWithLifecycle` swallows it silently — the sheet would render the last good list. That's acceptable for Phase 0; Phase 4 will wire a `catch { }` operator if surfacing errors is needed.
- **`repository.createWorkspaceFolder` failure modes.**
  - `IllegalArgumentException` from blank-name validation: structurally unreachable. The dialog's Create button is disabled while the trimmed name is blank (see #213 § Internal state); the dialog never invokes `onCreate("")`. The host does not catch this — if it ever fires, the app should crash loudly (it would indicate a contract violation in the dialog).
  - Phase 4 failures (network, server-side collision): out of scope. The host today does not wrap the call in `try { … } catch { … }`. When Phase 4 lands, the host gains a snackbar/error-state surface; until then, the throwing default on the repository interface (line 99-100) and Phase 0's non-throwing fake mean uncaught throws cannot occur.
- **Coroutine cancellation:** see § Cancellation behavior on consumer-driven dismiss mid-write above. Cancellation is silent — no callback is invoked. Acceptable.

## Testing strategy

Three new androidTest cases at `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerTest.kt`, following the established conventions of `CreateFolderDialogTest.kt` and `WorkspacePickerSheetTest.kt`: `@RunWith(AndroidJUnit4::class)`, `createComposeRule()`, `PyrycodeMobileTheme` wrap, `onNodeWithText` / `hasContentDescription` lookups, `var captured: T? = null` capture pattern, JUnit 4 assertions. Tests target `WorkspacePickerInternal(repository = FakeConversationRepository(), …)` directly — the testing-seam pattern, mirroring #213.

The three required cases (AC #5) and the suggested test names:

- **`dialog_submit_calls_createWorkspaceFolder_exactly_once_and_forwards_returned_path_to_onPicked`** (AC #5a)
  - Inputs: real `FakeConversationRepository()` (Phase 0 fake; not a mock).
  - Setup: render the seam with `onPicked = { picked.add(it) }`, capture `picked: MutableList<String>`.
  - Acts: tap "Create new folder under pyry-workspace…" → enter `"my-workspace"` in the dialog → tap "Create".
  - Asserts:
    1. `picked.size == 1` (exactly one invocation).
    2. `picked.single() == "pyry-workspace/my-workspace"` (the path returned by `FakeConversationRepository.createWorkspaceFolder` — see #210's prefix convention).
    3. `repo.recentWorkspaces().first().first() == "pyry-workspace/my-workspace"` (the bump landed at position 0; this re-asserts the repo contract from the host's vantage and validates wiring end-to-end).
  - Notes: to await the coroutine's completion, use `composeTestRule.waitForIdle()` after tapping Create. The fake completes synchronously, so `waitForIdle` is a defense against future suspending implementations rather than strictly required for Phase 0.

- **`cancelling_create_dialog_keeps_sheet_visible_and_does_not_invoke_host_onDismiss`** (AC #5b)
  - Inputs: real `FakeConversationRepository()`.
  - Setup: render the seam with `onDismiss = { dismissed++ }`, capture `dismissed: Int = 0`.
  - Acts: tap "Create new folder under pyry-workspace…" → tap "Cancel" in the dialog.
  - Asserts:
    1. `dismissed == 0` (host's outer `onDismiss` was NOT invoked).
    2. The sheet is still rendering: `composeTestRule.onNode(hasText("Choose workspace")).assertIsDisplayed()` — re-using the sheet's title text from #212.
    3. The dialog is gone: `composeTestRule.onNode(hasText("Create workspace")).assertDoesNotExist()` — re-using the dialog's title text from #213.
  - Notes: this is the load-bearing test for AC #4. It distinguishes "dialog dismissed" from "host dismissed" — two flag-flips that share the surface verb "dismiss" but route differently.

- **`tapping_a_recent_row_invokes_onPicked_with_that_rows_path`** (AC #5c)
  - Inputs: real `FakeConversationRepository()` seeded with at least one workspace path. The fake's seeding lives in its `init` block; the test can either rely on default seeds (already non-empty per #209) or bump one explicitly via `runBlocking { repo.changeWorkspace(…) }` before `setContent` to be deterministic. Recommended: bump one explicit path like `"/tmp/test-ws"` for a stable assertion target.
  - Setup: render the seam with `onPicked = { picked.add(it) }`.
  - Acts: `composeTestRule.onNode(hasText("/tmp/test-ws")).performClick()`.
  - Asserts: `picked == listOf("/tmp/test-ws")`.
  - Notes: this is the trivially-correct path — `onPick = onPicked` is a direct pass-through. The test exists per AC #5c to lock the contract.

Tests NOT required by AC but worth noting for the developer's choice:

- **An empty-recents test (covers AC #3's behavior when the flow's initial value is empty).** Not required; the empty branch is already exercised by `WorkspacePickerSheetTest.recent_header_is_omitted_when_recent_is_empty` at the sheet layer. Skip.
- **Sheet dismiss invokes host `onDismiss`.** Not required by AC #5. The wiring is `onDismiss = onDismiss` (one-line pass-through); reading the source documents it. Skip.
- **`visible == false` renders nothing.** Not required by AC #5 and the seam doesn't have a `visible` parameter to exercise it. The early-return guard lives only in the public `WorkspacePicker`; the trivially-correct conditional doesn't need a test. Skip.

Unit tests under `app/src/test/`: none. The host has no logic outside Compose's purview.

`./gradlew test` is unaffected by this ticket (no new unit tests). `./gradlew connectedAndroidTest` gains one new test class with three cases.

### Why test against `FakeConversationRepository` and not a hand-rolled mock

`FakeConversationRepository` is the production-grade Phase 0 implementation; its `recents` and `createWorkspaceFolder` already have full coverage in `FakeConversationRepositoryTest`. Using it in androidTest gives end-to-end fidelity for the host ↔ repo wiring without re-mocking the repository surface in each test. The alternative — a hand-rolled `object : ConversationRepository { override … }` — would force per-test stubs for `recentWorkspaces` and `createWorkspaceFolder` and risk drift between the test's stub behavior and the fake's real behavior. The codebase has no MockK use today (confirmed via codegraph search); do not introduce it.

## Open questions

None. All AC items map to concrete decisions above. Two design decisions worth flagging for code review attention:

1. **Internal testing seam (`WorkspacePickerInternal`).** Established pattern from #213; documented above. If a reviewer prefers a Koin-based test setup, the alternative is explicit in § Internal seam — declining it is the architect's call to match codebase conventions.
2. **Cancellation mid-write on consumer dismiss.** Phase 0: unreachable. Phase 4: documented as silent cancellation. Flagging for future Phase 4 ticket: if at-least-once semantics matter, the scope choice will change.

## Out of scope

- **Wiring any consumer screen.** Channel List FAB long-press, `#137`'s empty-thread chip, `#208`'s thread overflow item — all separate tickets that depend on this one landing first.
- **Per-screen visibility flag plumbing.** Each consumer adds its own `pendingWorkspacePicker: Boolean` field (or similar) to its `UiState` in its own ticket.
- **Phase 4 error UI for `createWorkspaceFolder`.** Snackbar/error state for network or server-side failures lands when the real backend ships.
- **`onPicked` distinguishing "recent tap" from "newly-created."** Consumers cannot tell which path produced the result. By design — both flows produce a workspace path the screen should bind, and the next action is identical.
- **Idempotence of repeated submits across separate dialog opens.** The host guarantees single-invocation *per submit*, not deduplication across consecutive submits of the same name. Two opens-of-the-dialog → two write calls is the correct behavior.
