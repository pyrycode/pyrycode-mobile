# 601 — StatusSheet: render "Context usage unavailable"

Issue: [pyrycode/pyrycode-mobile#601](https://github.com/pyrycode/pyrycode-mobile/issues/601). Size: S.

Split from #584. Sibling: #591 (serve the real figure — blocked on pyrycode PR #1215).

## Context

`StatusSheet`'s `ContextWindowSection` renders `73% used (146K of 200K tokens)` with a `warning`-coloured bar filled to 73% — on every conversation, on every device, always. The values come from `ThreadViewModel`'s `STUB_TOKEN_PERCENT = 73` / `STUB_TOKENS_USED = 146_000` / `STUB_TOKENS_TOTAL = 200_000` (`ThreadViewModel.kt:932-934`), plumbed through `ThreadUiState` into three defaulted `StatusSheet` params by spec #230.

This slice deletes the display of that stub and renders the honest text instead. It is **client-only**: no wire call, no DTO, no repository method, no `ThreadViewModel` edit.

**The key structural fact that makes this shippable alone:** `ThreadUiState` keeps `tokenPercent` / `tokensUsed` / `tokensTotal`, and `ThreadViewModel` keeps populating them. The sheet simply stops *reading* them. Nothing upstream changes, so nothing upstream needs re-testing, and #591 re-wires the same fields when it has real data.

Wording is not invented here. pyrycode#1214 captured the desktop DOM on the same `stream-json` runner mobile ships against:

```
"...Context windowContext usage unavailableWhen full, oldest messages get dropped..."
```

That pins two things: the text is exactly **"Context usage unavailable"**, and desktop **keeps** the caption alongside it. Mobile matches both rather than inventing a third phrasing.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100

**Deliberate, spec'd divergence — verified.** I read node `20:100`'s metadata. Its context-window region is a single frame `20:151` containing exactly three children: text `20:152` = `"73% used (146K of 200K tokens)"`, frame `20:153` holding the 8dp bar `20:154` filled to 277/380 ≈ 73%, and caption `20:155`. **There is no unavailable variant anywhere in the node** — this is the node the stub was copied *from* (see `docs/specs/architecture/230-status-sheet-context-window.md` § Design source, which implemented `20:151` literally).

So this ticket intentionally does not match Figma. What survives from the node: the `"Context window"` header (`20:150`), the caption copy verbatim (`20:155`), and the section's layout, paddings and typography. What goes: the figure (`20:152`) and the bar (`20:153`/`20:154`). #591 restores the Figma-matching populated render once the daemon serves real numbers.

Flagged for Juhana as a Figma-side gap worth filling (an "unavailable" variant on `20:100`) — not blocking, since the desktop transcript pins the wording precisely.

> Unrelated observation, explicitly **out of scope**: node `20:100` also has a "Log data" section (`98:2`) with a "Download" button (`98:16`-`98:19`) that mobile has never implemented. Not this ticket. Mentioned only so a future reader doesn't mistake it for something this slice deleted.

## Files to read first

Line refs below are against `2c247ec` (current `main`) and were re-verified, not copied from the ticket.

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` — the whole file (527 lines), and the only production file with real design work. Specifically: imports `:1-42`; `StatusSheet` signature `:46-59` with the 3 token params at `:56-58`; the pass-through block `:73-75`; `StatusSheetContent` signature `:80-92` with token params at `:89-91`; its call block `:110-114`; the `Spacer` at `:115` (**this is why the `height` import survives**); `ContextWindowSection` `:258-295`; `formatTokens` `:297`; `progressColor` `:299-307`; the 5 previews to keep `:309-422`; the 4 previews to delete `:424-526`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:361-380` — the single production `StatusSheet(` call site (opens at `:362`), token args at **`:376-378`**. ⚠️ The ticket body says `:368-370`; that was correct at `8ce12ab` but #597 (merged as `2c247ec`) shifted this file by 8 lines. Anchor on the `tokenPercent = state.tokenPercent,` argument name, not the line number.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt` — 14 tests. The three affected: `renders_context_window_section_with_header_label_and_caption` `:275-303` (rewrite), `label_format_uses_integer_K_division` `:305-325` (delete), `label_format_handles_zero_values_gracefully` `:327-344` (delete — **see § The compiler will not catch this**). The other 11 tests pass no token args and need no edit at all.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt:7-9,119-128` — the established in-repo idiom for semantics-key assertions: `SemanticsMatcher` + `SemanticsProperties` + `assertCountEquals`, with the exact import lines to mirror. Your no-progress-bar assertion follows this shape.
- `docs/specs/architecture/230-status-sheet-context-window.md` — the spec that built what you're removing. § Design / `progressColor` explains why `progressColor` was kept separate from `ThreadStatusRow.tokenPercentColor`; that reasoning is why deleting `progressColor` does **not** affect `ThreadStatusRow`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:259-265,930-935` — read-only confirmation that the stub constants and the `ThreadUiState` population stay untouched. **Do not edit this file.**

## Design

### Scope boundary (read before editing)

Three files change. Nothing else.

| File | Change |
| --- | --- |
| `ui/conversations/components/StatusSheet.kt` | Signature narrowing, section-body rewrite, 2 helper deletions, 4 preview deletions, 3 import deletions |
| `ui/conversations/thread/ThreadScreen.kt` | Drop 3 args at one call site |
| `androidTest/…/components/StatusSheetTest.kt` | Rewrite 1 test, delete 2, add 3 imports |

**Do not touch** `ThreadViewModel.kt`, `ThreadUiState`, `ThreadStatusRow.kt`, or any `strings.xml`. `ThreadStatusRow` has its own `tokenPercentColor` helper — unrelated to `progressColor` and out of scope.

### `StatusSheet.kt`

**Edit order matters.** Every edit below shifts the line numbers of the ones after it. Work **bottom-up** — previews first, then helpers, then the section body, then signatures, then imports — or anchor every edit on a symbol name rather than a line. Working top-down against the line refs in this spec will desync you around the third edit.

#### Signature narrowing

Remove `tokenPercent: Int = 0`, `tokensUsed: Int = 0`, `tokensTotal: Int = 0` from:

- `StatusSheet` (`:56-58`) — these are the last three params, after `sheetState`
- `StatusSheetContent` (`:89-91`) — the last three params
- `ContextWindowSection` (`:260-262`) — **all** of its params; it becomes `private fun ContextWindowSection()`

And remove the two forwarding blocks that pass them: `StatusSheet` → `StatusSheetContent` (`:73-75`) and `StatusSheetContent` → `ContextWindowSection` (`:110-114`, which collapses to a bare `ContextWindowSection()`).

`ContextWindowSection` stays a private composable rather than being inlined — the `SectionHeader(...)` + section-composable pairing is the structural rhythm of `StatusSheetContent` (Model, Effort, YOLO all follow it), and #591 needs the seam back.

#### `ContextWindowSection` body

Keep the `Column`: same `Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)`, same `verticalArrangement = Arrangement.spacedBy(8.dp)`. Two children, in order:

1. The unavailable `Text` — literal `"Context usage unavailable"`, `style = MaterialTheme.typography.bodyLarge`, `color = MaterialTheme.colorScheme.onSurface`.
2. The existing caption `Text`, **byte-identical to `:287-293`** — same two-part string concatenation, same `bodySmall`, same `onSurfaceVariant`. Do not retype it; leave those lines alone and delete around them.

The `LinearProgressIndicator` (`:276-286`) goes.

**On the colour, so you don't second-guess it:** the AC says "no severity colour", and `onSurface` is correct. Severity was never on this label — it lived on the bar via `progressColor`, and the bar is gone. `onSurface` is the same neutral slot the figure already used, and the AC also says typography and layout "stay as designed". Do **not** de-emphasise to `onSurfaceVariant`; that would be an unrequested visual change and would collide with the caption's tone.

**String literal, not `stringResource`.** The ticket permits either. Use the literal: every other piece of copy in this file is inline (`"Run configuration"`, `"Context window"`, `"Auto-accept tool calls"`, the caption). A lone `stringResource` here would be the odd one out, and it would need a `strings.xml` edit this ticket otherwise doesn't require.

#### Deletions

- `formatTokens` (`:297`) and `progressColor` (`:299-307`). Verified by grep: neither is referenced anywhere outside this file, and after the body rewrite neither is referenced inside it.
- The 4 `StatusSheetContextWindow{20,60,88,97}Preview` blocks (`:424-526`) — **delete the blocks, do not strip their token args.** With the args removed each becomes byte-identical to `StatusSheetOpusPreview` (`:309-330`): same `Model.OPUS_4_7`, same `Effort.HIGH`, same `yoloEnabled = false`. Stripping leaves 4 duplicate previews. This is one contiguous 103-line deletion ending at EOF.
- The 5 previews at `:309-422` **stay**.

#### Imports — asymmetric, check each

Delete exactly three:

| Import | Line | Sole use |
| --- | --- | --- |
| `androidx.compose.material3.LinearProgressIndicator` | `:21` | `:276` |
| `androidx.compose.ui.graphics.Color` | `:33` | `progressColor` return type, `:300` |
| `de.pyryco.mobile.ui.theme.warning` | `:42` | `:304` |

**Keep these two — they look orphaned by a naive grep but are not:**

- `androidx.compose.foundation.layout.height` (`:9`) — used by the bar at `:281`, but **also** by `Spacer(modifier = Modifier.height(24.dp))` at `:115`, which stays.
- `androidx.compose.foundation.layout.Arrangement` (`:3`) — used at `:269` (this section, which keeps its `spacedBy`) and at `:213` in `EffortChipRow`.

`PaddingValues` and `Surface` also stay — the 5 surviving previews use them.

A grep for `Color` in this file returns many hits, but they are substring matches inside `colorScheme` / `contentColor` / `trackColor`; none of them reference the `androidx.compose.ui.graphics.Color` *type*. The import is genuinely orphaned.

### `ThreadScreen.kt`

One edit: delete the three argument lines at `:376-378` (`tokenPercent = state.tokenPercent,` / `tokensUsed = state.tokensUsed,` / `tokensTotal = state.tokensTotal,`) from the `StatusSheet(` call opening at `:362`. `onDismiss = { sheetVisible = false },` becomes the last argument.

`ThreadScreen`'s own previews construct `ThreadUiState(...)` directly and some pass `tokenPercent = 73`. **Those stay valid and must not be touched** — `ThreadUiState` keeps all three fields.

## State + concurrency model

Nothing. No flow added or removed, no `viewModelScope` job, no dispatcher, no `StateFlow` shape change. `ThreadViewModel.state` emits the identical `ThreadUiState` before and after.

Recomposition: strictly reduced. `ContextWindowSection` becomes parameterless and reads only `MaterialTheme` — so it recomposes on theme change alone, where previously it also recomposed on any `tokenPercent` / `tokensUsed` / `tokensTotal` change. Since those are constants today the practical delta is zero, but the direction is correct and there is no new instability: no lambda captures, no unstable types, no `remember` needed.

`ThreadUiState` keeps three now-unread fields. That is deliberate and is what lets this slice ship without a ViewModel edit. Lint does not flag unread `data class` properties, so this produces no warning. #591 reads them again.

## Error handling

No new failure modes; two existing ones are removed. `formatTokens`' integer division and `progressColor`'s `coerceIn(0, 100)` clamp both disappear along with their only call sites, as does the odd-but-non-crashing `"NN% used (XK of 0K tokens)"` render that spec #230 flagged as a Phase 4 concern (`230-…md` § Error handling, § Open questions #2). The section now renders two static strings and cannot fail.

Nothing surfaces to the user as a banner or dialog — this *is* the honest-failure surface, rendered inline.

## Testing strategy

Unit (`./gradlew test`): unaffected. `ThreadViewModelTest`'s existing `tokenPercent` / `tokensUsed` / `tokensTotal` assertions still pass because the ViewModel is untouched — **do not delete them.**

Instrumented (`./gradlew connectedAndroidTest`): `StatusSheetTest` only.

### ⚠️ The compiler will not catch this

`compileDebugAndroidTestKotlin` finds only **two** of the three stale tests. `renders_context_window_section_with_header_label_and_caption` (`:287-289`) and `label_format_uses_integer_K_division` (`:317-319`) pass token args explicitly, so removing the params breaks them at compile time. But `label_format_handles_zero_values_gracefully` (`:327-344`) **passes no token args at all** — it leans on the `= 0` defaults and asserts `"0% used (0K of 0K tokens)"`. With the params gone it still compiles cleanly and fails only at run time, on device.

**Delete it by name.** A green `compileDebugAndroidTestKotlin` is not evidence the test file is finished.

### `StatusSheetTest` changes

**Rewrite** `renders_context_window_section_with_header_label_and_caption` → rename to `renders_context_window_section_as_unavailable_with_header_and_caption`. Drop the 3 token args from its `StatusSheetContent(...)` call; the rest of the setup block is unchanged. Assertions:

- `"Context window"` (the section header) is displayed.
- `"Context usage unavailable"` is displayed.
- The caption is displayed — reuse the existing two-part concatenated string from `:299-301` verbatim.
- **No progress indicator anywhere in the tree.** Match on the semantics key rather than a value: `onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)`. `keyIsDefined` is the right matcher because a value-based `expectValue` would need a `ProgressBarRangeInfo` to compare against — and the point is that no such node exists. Asserting over the whole tree is safe: no other node in `StatusSheetContent` sets that key (`RadioButton`, `FilterChip` and `Switch` do not), so a count of 0 is a true statement about the section.

**Delete** `label_format_uses_integer_K_division` and `label_format_handles_zero_values_gracefully`. Both assert only `formatTokens` output — `"5% used (12K of 200K tokens)"` and `"0% used (0K of 0K tokens)"` respectively — and with the formatter gone neither has anything left to exercise. Do not try to salvage them.

**Add three imports**, mirroring `ThreadScreenModalTest.kt:7-9`:

- `androidx.compose.ui.semantics.SemanticsProperties`
- `androidx.compose.ui.test.SemanticsMatcher`
- `androidx.compose.ui.test.assertCountEquals`

`onNode` / `onAllNodes` / `assertIsDisplayed` are already imported or are members. Keep imports alphabetically ordered — ktlint enforces it, and a misplaced import passes `lint` but fails `spotlessCheck`.

The other 11 tests in the file need no changes.

**No live rung.** There is no daemon interaction to exercise — which is precisely why this ships ahead of the blocked #591.

### Gates

`./gradlew test`, `./gradlew lint`, `./gradlew spotlessCheck`, `./gradlew assembleDebug`, and — because none of those compile the `androidTest` source set — `./gradlew compileDebugAndroidTestKotlin`. Run Gradle with `ANDROID_HOME` exported; `local.properties` is gitignored and absent in a fresh worktree.

## Edit fan-out

Removing a **defaulted** parameter only breaks call sites that pass it explicitly. Counting those:

| Site | Count | Action |
| --- | --- | --- |
| `ThreadScreen.kt` `StatusSheet(` | 1 | drop 3 args |
| `StatusSheet.kt` context-window previews | 4 | deleted (one contiguous block) |
| `StatusSheetTest.kt` tests passing token args | 2 | 1 rewritten, 1 deleted |
| `StatusSheetTest.kt` test relying on defaults | 1 | deleted (compiler-invisible) |

**8 consumer call sites** — under the 10-site red line. The 5 surviving previews and the other 11 tests pass no token args and are untouched, so they are not call sites needing update. This is the mirror image of spec #230's fan-out note: the `= 0` defaults that let #230 add the params cheaply are the same reason removing them is cheap.

Production `*.kt` files touched: **2** (`StatusSheet.kt`, `ThreadScreen.kt`) — under the 5-file split threshold. New files: 0. New exported types / public composables: 0 (net −2 private helpers). Reject branches: 0.

Newly written code is ~15 lines; the diff is otherwise ~150 lines of deletion. Total written work is far under the ~600-line ceiling. Size **S** confirmed — no red line tripped.

## Open questions

- **Figma has no unavailable variant.** Confirmed by reading node `20:100` (see § Design source). This slice ships text pinned by the pyrycode#1214 desktop transcript instead. Worth adding the variant to `20:100` so #591 and any future redesign have a design anchor — flagged for Juhana, not blocking.
- **`ThreadUiState` keeps three unread fields** between this ticket and #591. Intentional: it is what makes this slice compile and ship standalone. If #591 slips far enough that the dead fields start reading as rot, a cleanup ticket can drop them — but do **not** pre-emptively remove them here; that would pull `ThreadViewModel` into scope and break the sibling boundary.
- **`ContextWindowSection` is now parameterless.** #591 reintroduces parameters when it has real data to carry. Per the ticket's Technical Notes, do **not** model a sealed `Unavailable | Known(...)` type now — there is exactly one possible value today, so a variant type is speculative generality and a new public type plus consuming composables would trip the always-split rule for no benefit.
