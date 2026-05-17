# 204 — Context-aware ThreadOverflowMenu items (discussion vs channel)

## Context

`ThreadOverflowMenu` (#251) currently renders five conversation-type-agnostic items: New session, Rename, Change workspace…, Archive, Channel info. #252 mounted it in `ThreadTopAppBar`. This ticket adds the single type-specific item on each side:

- **Discussions (`isPromoted == false`)** get a leading **Save as channel…** that emits `ThreadEvent.SaveAsChannel` — the promote-from-thread entry point (one of three per the locked Conversations Model design, 2026-05-08).
- **Channels (`isPromoted == true`)** get a trailing **Install memory plugin** that opens the memory-plugin docs URL via `LocalUriHandler.current.openUri(...)` — no ViewModel event; the URL handler is a UI-side concern.

The dispatcher for `SaveAsChannel` is a no-op for this slice; the actual promotion dialog wiring is #142's scope (parallel to the rename dialog wiring #141 just landed).

The `Conversation.isPromoted` flag is already projected to `ThreadUiState.isPromoted` in `ThreadViewModel` (`ThreadViewModel.kt:85`). This ticket only consumes that field; it does not touch the derivation.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The overflow `IconButton` trigger lives at node `16:16` inside the Conversation Thread Screen (`16:8`); the Figma file has no dedicated frame for the open-menu state. The two new items render as stock Material 3 `DropdownMenuItem`s in the same M3 `DropdownMenu` host introduced by #251 — vanilla M3 defaults (surface container, body-large item text, standard insets). No bespoke icons, dividers, or styling. Visual fidelity is inherited from #251/#252; no new design tokens are introduced.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:1-60` — the existing five-item menu. Add `isPromoted: Boolean` parameter and two conditional items (one prepended for discussions, one appended for channels).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:23-39` — sealed `ThreadEvent` hierarchy. Add `data object SaveAsChannel : ThreadEvent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:144-163` — `onOverflowEvent(event)`. Add `ThreadEvent.SaveAsChannel -> Unit` to the existing exhaustive `when`; do **not** add a side effect — promotion-dialog wiring is #142's scope. Co-locate the no-op with the existing `NewSession, ChangeWorkspace, ChannelInfo,` group.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt:21-68` — composable that already plumbs `overflowExpanded`, `onOverflowDismiss`, `onOverflowEvent`. Add an `isPromoted: Boolean` parameter and pass it through to `ThreadOverflowMenu`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:54-82` — the `ThreadTopAppBar(...)` call inside `topBar = { … }`. Pass `isPromoted = state.isPromoted`. No new screen parameter needed; the field already lives on `ThreadUiState`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt:37` — **existing `internal const val MEMORY_PLUGIN_DOCS_URL: String = "https://pyryco.de/docs/memory-plugins"`**. Reuse this constant; do **not** duplicate it. Promote to package-level shared via `internal` (already is) — `ThreadOverflowMenu.kt` is in a sibling package (`thread/` vs `components/`), so an explicit import is required. See § Open questions for the alternative.
- `app/src/main/res/values/strings.xml:11` — existing `save_as_channel_action` = "Save as channel…". **Reuse this key**; do not introduce a `thread_overflow_save_as_channel`.
- `app/src/main/res/values/strings.xml:1-44` — string-resource conventions (snake_case keys, U+2026 ellipsis character for "opens further UI"). Add `thread_overflow_install_memory_plugin` here.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt:1-131` — existing test scaffold (`createComposeRule`, `string()` helper, `PyrycodeMobileTheme` wrapper, `"dismiss"`/`"event:Foo"` log assertion). All six existing tests need their `ThreadOverflowMenu(...)` calls updated to pass `isPromoted = ...` (split into discussion + channel variants for the order test; pick one variant for each existing item test — channel for Rename/Archive/etc. is fine since those items exist in both modes). Add four new tests per § Testing strategy.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt:50-95` — reference implementation of `LocalUriHandler.current` + `openUri(MEMORY_PLUGIN_DOCS_URL)` for the same docs URL, including how it threads `uriHandler` as an injected parameter for test-time substitution via `CompositionLocalProvider`. **Mirror this pattern verbatim** — both the production code shape and the test-time injection.
- `CLAUDE.md` — MVI conventions; stateless composables, single `StateFlow<UiState>` per VM. The new menu remains stateless.

## Design

Five production-source edits + one new string + test additions. No new files, no new types beyond one sealed-case data object.

### 1. `ThreadEvent` — add one case

In `ThreadViewModel.kt`, extend the existing sealed interface:

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data class RenameSubmit(val name: String) : ThreadEvent
    data object RenameDismiss : ThreadEvent
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
    data object SaveAsChannel : ThreadEvent   // NEW
}
```

`data object` (not plain `object`) for the auto-generated `toString` — keeps the existing test log assertion shape `"event:SaveAsChannel"` consistent with the other cases.

No `InstallMemoryPlugin` event. Per AC, the install-memory-plugin item is URL-handler-only and never enters the ViewModel.

### 2. `ThreadViewModel.onOverflowEvent` — add one no-op branch

Append `ThreadEvent.SaveAsChannel` to the existing no-op group at lines 158-161:

```kotlin
ThreadEvent.NewSession,
ThreadEvent.ChangeWorkspace,
ThreadEvent.ChannelInfo,
ThreadEvent.SaveAsChannel,
-> Unit
```

Why a no-op: the promotion dialog mounting + `repository.promote(id, name)` call is #142's responsibility (parallel slice to #141's rename wiring). Keeping the no-op here makes the sealed `when` exhaustive so the compiler enforces the case at #142's edit time. No TODO comment — the empty branch documents itself, mirroring the convention #251 established.

### 3. `ThreadOverflowMenu` — add `isPromoted` parameter + two conditional items

New signature:

```kotlin
@Composable
fun ThreadOverflowMenu(
    expanded: Boolean,
    isPromoted: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

`isPromoted` placement: between `expanded` and `onDismiss` — keeps the "what to render" parameters grouped before the "what to do" callbacks. No default value (matches the `expanded`/`onDismiss`/`onEvent` shape; the caller — `ThreadTopAppBar` — always knows).

Body — `DropdownMenu` with conditional first and last items wrapping the existing five. Final orders:

| `isPromoted == false` (discussion) | `isPromoted == true` (channel) |
|---|---|
| 1. Save as channel…   *(NEW, `R.string.save_as_channel_action`)* | 1. New session |
| 2. New session                                                    | 2. Rename |
| 3. Rename                                                         | 3. Change workspace… |
| 4. Change workspace…                                              | 4. Archive |
| 5. Archive                                                        | 5. Channel info |
| 6. Channel info                                                   | 6. Install memory plugin *(NEW, `R.string.thread_overflow_install_memory_plugin`)* |

The five common items in their existing order are always present. The conditional items wrap them — at most one of the two ever renders, since the two `isPromoted` branches are mutually exclusive (there is no third state).

Signature sketch for the new items (developer writes the body following the existing `DropdownMenuItem` pattern at lines 23-57):

- **Save as channel…** item — render `if (!isPromoted) { DropdownMenuItem(text = … save_as_channel_action, onClick = { onDismiss(); onEvent(ThreadEvent.SaveAsChannel) }) }`. Place **before** the five common items in the `DropdownMenu` body. Dismiss-before-event ordering matches the existing items (load-bearing for the test log assertion).
- **Install memory plugin** item — render `if (isPromoted) { DropdownMenuItem(text = … thread_overflow_install_memory_plugin, onClick = { onDismiss(); uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) }) }`. Place **after** the five common items. No `onEvent` call — the URL handler is the only side effect. `uriHandler` is captured from `LocalUriHandler.current` at composable body top, mirroring `SessionBoundaryDelimiter.kt:74`.

Imports to add: `androidx.compose.ui.platform.LocalUriHandler`, `de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL`.

#### Why `if (isPromoted) { … }` over a `when`/sealed-state

The two branches are mutually exclusive but the *common five* items render unconditionally. A `when (isPromoted)` over the entire menu body would either (a) duplicate the five common items in both branches (~30 lines repeated) or (b) collapse to the same `if` pair around two extra items. The `if` pair is the directly-testable expression of "discussions get one extra item at index 0; channels get one extra item at the end" and matches AC wording 1:1.

### 4. `ThreadTopAppBar` — add `isPromoted` parameter; pass through

Add `isPromoted: Boolean` to the parameter list (placement: between `onOverflowEvent` and `modifier` — keeps the new param next to the other overflow-menu params). Pass it through to the `ThreadOverflowMenu(...)` call inside the actions slot.

No other changes — the icon button, top-bar structure, and back/title slots are untouched.

### 5. `ThreadScreen` — pass `state.isPromoted` to top bar

In the `topBar = { ThreadTopAppBar(...) }` slot (lines 73-81), add `isPromoted = state.isPromoted`. **No new screen parameter** — `ThreadUiState.isPromoted` already flows in via `state: ThreadUiState`.

No `MainActivity` change. No preview changes — existing `@Preview` composables already construct `ThreadUiState(isPromoted = true)` (channel previews); a discussion preview is not required for this slice (the menu has no visual rendering in the Compose previews — it only appears on tap).

### 6. Strings

Add one new string to `app/src/main/res/values/strings.xml`:

```xml
<string name="thread_overflow_install_memory_plugin">Install memory plugin</string>
```

**Reuse `save_as_channel_action`** ("Save as channel…", already at strings.xml:11) for the new menu item — do not introduce a `thread_overflow_save_as_channel`. The string is identical to what other promote-from-X surfaces (e.g. the `DiscussionListScreen` row overflow at `DiscussionListScreen.kt:196-216`) use, and consolidating on one key keeps translation work minimal.

The `thread_overflow_install_memory_plugin` key follows the existing `thread_overflow_*` naming for overflow menu items.

### 7. URL constant — reuse, do not duplicate

The ticket's Technical Notes suggest a `private const val` inside `ThreadOverflowMenu.kt`. **Do not do this.** `MEMORY_PLUGIN_DOCS_URL` already exists as `internal const val` in `SessionBoundaryDelimiter.kt:37` for the same purpose (the empty-thread "install a memory plugin" link). Reuse it via import. Duplicating the URL is a footgun for the Phase 3+ swap-to-install-endpoint task (two call sites to find and replace instead of one). See § Open questions for the placement alternative.

## State + concurrency model

- No new state. No new flows. No coroutines introduced by this ticket.
- `isPromoted` is a read-only `Boolean` flowed through composables — pure UI input.
- `LocalUriHandler.current.openUri(...)` is a synchronous call from the click handler; the platform handles the intent firing.
- `onOverflowEvent(SaveAsChannel)` returns `Unit` immediately; no `viewModelScope.launch`.
- `ThreadOverflowMenu` remains stateless — `expanded`/`onDismiss` are hoisted to `ThreadScreen` (unchanged from #252).

## Error handling

- `LocalUriHandler.openUri(url)` throws `IllegalArgumentException` if the URL is malformed. `MEMORY_PLUGIN_DOCS_URL` is a literal `https://pyryco.de/docs/memory-plugins` — known-well-formed. No try/catch.
- `openUri` may throw `ActivityNotFoundException` on the rare device with no browser installed. The existing `SessionBoundaryDelimiter` uses the same call without a try/catch (`SessionBoundaryDelimiter.kt:51`); this ticket preserves that posture. If the no-browser failure mode is observed in practice, a single fix can cover both call sites.
- `SaveAsChannel` is a no-op in the ViewModel — no failure mode.

No new error UI. No snackbar. No toast.

## Testing strategy

All tests are instrumented Compose (`./gradlew connectedAndroidTest`), in the existing `ThreadOverflowMenuTest.kt`. No unit-test additions needed for the ViewModel — the `SaveAsChannel` branch is a no-op with no observable side effect to assert (the compiler-enforced exhaustive `when` is the contract).

### Updates to existing tests

The six existing tests in `ThreadOverflowMenuTest.kt:23-130` must add `isPromoted = …` to each `ThreadOverflowMenu(...)` call site (the parameter has no default). Pick the variant that makes the test continue to assert what it already asserts:

- `menu_items_render_in_documented_order_when_expanded` — split into two tests (one per `isPromoted` variant) per § New tests below, OR rewrite to assert only the five common items by reusing one variant (e.g. `isPromoted = true`) and let the new tests cover the conditional items. **Preferred: split.** Keeps each test asserting one variant's full surface.
- `tapping_new_session_dismisses_then_dispatches_event` and the four sibling per-item tests — pass `isPromoted = true` (channel). All five common items exist in both modes; channel mode is a single, consistent choice across the existing tests. No behavioral change to assert.

### New tests

| Test | Setup | Action | Assertion |
|---|---|---|---|
| **`discussion_menu_shows_save_as_channel_first_and_hides_install_memory_plugin`** | `isPromoted = false`; `expanded = true`; no event/dismiss handlers required (use `{}`) | none | `save_as_channel_action` displayed; `thread_overflow_install_memory_plugin` `assertDoesNotExist()`; all five common items also displayed. Position: see § Position assertion below. |
| **`channel_menu_shows_install_memory_plugin_last_and_hides_save_as_channel`** | `isPromoted = true`; `expanded = true` | none | `thread_overflow_install_memory_plugin` displayed; `save_as_channel_action` `assertDoesNotExist()`; all five common items also displayed. Position: see § Position assertion. |
| **`tapping_save_as_channel_dismisses_then_dispatches_event`** | `isPromoted = false`; `log = mutableListOf<String>()`; `onDismiss = { log.add("dismiss") }`, `onEvent = { log.add("event:$it") }` | `composeTestRule.onNodeWithText(string(R.string.save_as_channel_action)).performClick()` | `assertEquals(listOf("dismiss", "event:SaveAsChannel"), log)`. Same shape as the existing five per-item tests. |
| **`tapping_install_memory_plugin_dismisses_and_opens_docs_url`** | `isPromoted = true`; `dismissed = 0`; injected fake URI handler (see § Fake URI handler below) | `composeTestRule.onNodeWithText(string(R.string.thread_overflow_install_memory_plugin)).performClick()` | `dismissed == 1`; `fakeHandler.openedUris == listOf("https://pyryco.de/docs/memory-plugins")` (or `listOf(MEMORY_PLUGIN_DOCS_URL)` via import). `onEvent` is never invoked — assert via a counter `eventCount == 0` or a `mutableListOf<ThreadEvent>()` that stays empty. |

#### Position assertion

For the two visibility tests, the AC says "Save as channel… at index 0" and "Install memory plugin at the last index". Compose-test ordering matchers are awkward. Use either:

- **Preferred:** `onAllNodesWithText(...)` or `onAllNodes(hasAnyAncestor(isPopup()))` and assert the in-order text sequence via `assertAll(...)` or by collecting `fetchSemanticsNodes()` and reading the `Text` semantics of each. The resulting sequence equality assertion is the directly-testable expression of position AC.
- **Acceptable fallback:** assert visibility only, and add a per-position sentinel — `tapping_save_as_channel_dismisses_then_dispatches_event` (already covered above) plus the existing per-item tap tests, which collectively verify that every documented label resolves to a distinct, tappable item. Order-by-tap-coverage is weaker than order-by-sequence but matches what the existing `menu_items_render_in_documented_order_when_expanded` test does.

Either choice is acceptable; the developer picks whichever the Compose-test toolkit version makes ergonomic.

#### Fake URI handler

Mirror `SessionBoundaryDelimiter.kt`'s injection seam: inject the URI handler via `CompositionLocalProvider(LocalUriHandler provides fakeHandler) { ThreadOverflowMenu(...) }`.

Define a minimal fake at the top of `ThreadOverflowMenuTest.kt`:

- A class implementing `androidx.compose.ui.platform.UriHandler` with a `val openedUris = mutableListOf<String>()` and `override fun openUri(uri: String) { openedUris += uri }`.
- Instantiate once per test and inject via `CompositionLocalProvider`.

Do not use Mockito or MockK — the fake is ~6 lines and stays local to the test file, consistent with the project's existing test-double conventions (`RecordingRepo`, `FakeConnectionStateSource`).

### Out of scope for tests

- No `ThreadViewModel` unit test for `SaveAsChannel` — empty branch, no observable effect, compiler enforces case existence.
- No `ThreadScreen` integration test — the `isPromoted = state.isPromoted` plumbing is one assignment; the existing `ThreadScreenOverflowTest` already verifies menu items render on tap, and the new menu tests cover the conditional rendering.
- No `ThreadTopAppBar` unit test — pure prop-passing.
- No test for the four no-op event branches' interaction with anything — they have no observable state effect.

## Open questions

- **`MEMORY_PLUGIN_DOCS_URL` placement.** Currently `internal const val` at the top of `SessionBoundaryDelimiter.kt`. Two callers will soon exist (this ticket + the boundary delimiter). The cleaner placement is a shared module-level file (e.g. `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MemoryPlugin.kt` or just `Urls.kt`). **Spec recommends keeping it where it is** for this slice — moving it is a one-line refactor that's better paired with the Phase 3+ swap-to-install-endpoint work, and the existing `internal` scope already permits cross-package import from the same module. If the developer prefers to move it as part of this slice, that's acceptable; flag in the PR description.
- **Discussion-variant `@Preview` for `ThreadScreen`.** All four existing previews are `isPromoted = true`. Not required by this ticket (the menu only appears on tap, not in Compose previews), but a developer adding a quick visual-sanity pass might want a fifth preview with `isPromoted = false`. Out of scope; mention in PR if added.
- **Order assertion ergonomics.** § Position assertion above offers two options. The Compose-test toolkit's `fetchSemanticsNodes()` works but produces verbose assertions. If both options end up awkward, the per-tap coverage in `tapping_save_as_channel_*` and the existing per-item tap tests (after the `isPromoted = true` update) collectively verify positional integrity by exhaustion.
