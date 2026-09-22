# StatusSheet — hosting, tests and edge cases

Split out of [StatusSheet](status-sheet.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [StatusSheet](status-sheet.md); see that document for the rest.

## Hosting in `ThreadScreen`

The sheet is mounted inside [`ThreadScreen`](thread-screen.md) at the screen-root level, as a `Scaffold` sibling (not inside the `Scaffold` content slot — `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order). The screen owns a `rememberSaveable`-hoisted visibility flag and forwards the model selection to the VM:

```kotlin
@Composable
fun ThreadScreen(
    // ...existing params...
    onModelSelected: (Model) -> Unit = {},
    // ...existing params...
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        // ...
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ThreadStatusRow(
                    // ...
                    onExpandClick = { sheetVisible = true },
                )
                ThreadInputBar(onSend = onSendMessage)
            }
        },
    ) { /* content */ }
    WorkspacePicker(/* ... */)              // sibling host from #137
    if (sheetVisible) {
        StatusSheet(
            selectedModel = state.selectedModel,
            onModelSelected = { model ->
                onModelSelected(model)
                sheetVisible = false
            },
            selectedEffort = state.selectedEffort,
            onEffortSelected = { effort ->
                onEffortSelected(effort)
                sheetVisible = false
            },
            yoloEnabled = state.yoloEnabled,
            onYoloToggled = onYoloToggled,
            onDismiss = { sheetVisible = false },
        )
    }
}
```

(`tokenPercent` / `tokensUsed` / `tokensTotal` args removed at this call site in [#601](../codebase/601.md); `onDismiss` is now the trailing argument. [#603](../codebase/603.md) has since deleted the fields from `ThreadUiState` entirely, so there is no longer anything to omit here — the call site's shape is now permanent, not a transitional state.)

Four things to notice:

- **`var sheetVisible by rememberSaveable { mutableStateOf(false) }`** lives on the screen, not on `ThreadViewModel`. UI-local presentation state with no business meaning belongs at the screen layer; pushing it into the VM would dirty the VM contract with screen-presentation concerns. `rememberSaveable` (over bare `remember`) means the sheet survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md).
- **Asymmetric auto-close: single-pick sections close the sheet, toggle/display sections don't** ([#229](../codebase/229.md) resolution to the #254 open question, [#230](../codebase/230.md) extension). `onModelSelected` and `onEffortSelected` both wrap in `{ value -> upstream(value); sheetVisible = false }` — a radio/chip pick is a complete action, M3 modal-bottom-sheet convention. `onYoloToggled` passes straight through with no auto-close — a Switch toggle is a state-change the user may want to immediately reverse. The Context window section has no callback at all (read-only display); taps inside it do nothing. The final rule from [#230](../codebase/230.md): discrete-value picker → auto-close, slider/toggle/display → keep open.
- **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects.
- **`ModalBottomSheet`'s hide animation runs on a coroutine the framework owns** — no `sheetState.hide()` call needed before flipping `sheetVisible = false`.

The `ThreadScreen`-side `onModelSelected: (Model) -> Unit = {}`, `onEffortSelected: (Effort) -> Unit = {}`, `onYoloToggled: (Boolean) -> Unit = {}` are all defaulted so previews and other call sites keep compiling unchanged; `MainActivity` binds the three parameters at the `CONVERSATION_THREAD` destination:

```kotlin
ThreadScreen(
    // ...
    onModelSelected = vm::onModelSelected,
    onEffortSelected = vm::onEffortSelected,
    onYoloToggled = vm::onYoloToggled,
    // ...
)
```

All three are method references into [`ThreadViewModel`](thread-screen.md#viewmodel). Through [#229](../codebase/229.md)/[#253](../codebase/253.md) they were synchronous `MutableStateFlow.value` writes only — an operator's tap moved the control locally and nothing else happened. **[#544](../codebase/544.md) wired all three to the daemon**: each still writes its override flow first (optimistic move — `onModelSelected`/`onEffortSelected` update the override layer over the matching `AppPreferences.default*` flow, `onYoloToggled` writes the `yoloEnabled` flag directly with no preference layer, preserving the architectural single-writer invariant), but now also sends only the changed field to `Conversation.currentSessionId` via `ConversationRepository.setSessionSettings` ([#543](../codebase/543.md)) and reverts the write on a daemon error or a not-connected `IllegalStateException`, surfacing the standard transient snackbar (`sessionSettingsErrors`, wired at [`ThreadScreen`](thread-screen.md) and `MainActivity`). The daemon's ack does not echo settings, so on success the optimistic value simply stays — "confirmed" means "sent and acked", not "read back". None of the three mutate `AppPreferences`. See [`../codebase/544.md`](../codebase/544.md) for the wiring detail (send helper, catch-triad, revert semantics).

## Preview

Five `@Preview` composables in `StatusSheet.kt` (nine before [#601](../codebase/601.md) deleted the four context-window-threshold previews below), all light-mode + `widthDp = 412` + `showBackground = true`. Each follows the canonical sheet-preview wrap that simulates the `ModalBottomSheet`'s default container colour + drag-handle gap:

```kotlin
PyrycodeMobileTheme(darkTheme = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
            StatusSheetContent(
                selectedModel = <model>,
                onModelSelected = {},
                selectedEffort = <effort>,
                onEffortSelected = {},
                yoloEnabled = <bool>,
                onYoloToggled = {},
                onDismiss = {},
            )
        }
    }
}
```

- **Model sweep** ([#254](../codebase/254.md)): `StatusSheetOpusPreview` (`Model.OPUS_4_7`), `StatusSheetSonnetPreview` (`Model.SONNET_4_6`), `StatusSheetHaikuPreview` (`Model.HAIKU_4_5`) — all with `selectedEffort = Effort.HIGH, yoloEnabled = false`.
- **Effort/YOLO sweep** ([#229](../codebase/229.md)): `StatusSheetEffortLowYoloOffPreview` (`Effort.LOW`, `yoloEnabled = false`) and `StatusSheetEffortMaxYoloOnPreview` (`Effort.MAX`, `yoloEnabled = true`) — both fix `Model.OPUS_4_7`, exercising the chip selection contrast and the switch on/off rendering. Satisfies the [#229](../codebase/229.md) AC line "both Effort variations (e.g. low and max) and YOLO on/off".
- **Context window threshold sweep — deleted in [#601](../codebase/601.md).** [#230](../codebase/230.md) had added four previews (`StatusSheetContextWindow20/60/88/97Preview`) varying the three now-removed context-window params to show the `primary` / `warning` / `error` fill bands. With the params gone, each would have been byte-identical to `StatusSheetOpusPreview` — #601 deleted the blocks rather than stripping their args, to avoid four duplicate previews. The section they exercised no longer has a threshold to sweep.

All five target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme sweep is deliberately not included; the theme behaviour is identical across themes. If a future ticket wants a dark sweep, mirror the [`WorkspacePickerSheet`](workspace-picker-sheet.md) `Preview + DarkPreview` pair.

## Tests

Twelve Compose UI tests in `androidTest/.../StatusSheetTest.kt` (fourteen before [#601](../codebase/601.md) rewrote one and deleted two — see below) (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host:

Model section ([#254](../codebase/254.md)):

- **`renders_model_section_with_all_three_rows_and_descriptions`** — `selectedModel = Model.OPUS_4_7`. Asserts "Model" header + each of `"Opus 4.7"` / `"best for complex work"` / `"Sonnet 4.6"` / `"faster, cheaper"` / `"Haiku 4.5"` / `"fastest"` are displayed via `hasText` matchers.
- **`tapping_sonnet_row_invokes_onModelSelected_with_sonnet`** — `selectedModel = Model.OPUS_4_7`, `onModelSelected = picks::add` capture. `onNode(hasText("Sonnet 4.6")).performClick()`. Asserts `picks == listOf(Model.SONNET_4_6)` (exact list equality — pins the "exactly one invocation" contract; a regression that fires the click twice would surface as `[SONNET_4_6, SONNET_4_6]`).
- **`tapping_haiku_row_invokes_onModelSelected_with_haiku`** — same shape, asserts `picks == listOf(Model.HAIKU_4_5)`.
- **`tapping_close_icon_invokes_onDismiss`** — `var invoked = 0`, `onNode(hasContentDescription("Close")).performClick()`, asserts `invoked == 1`. Pins the close-icon wiring + the exactly-one-invocation guarantee.
- **`selected_row_reports_selected_semantics`** — `selectedModel = Model.SONNET_4_6`. Matcher is `onNode(isSelectable() and hasAnyDescendant(hasText("Sonnet 4.6"))).assertIsSelected()` — `hasAnyDescendant` is required because the `selectable` modifier lives on the parent `Row`, not on the `Text` node; matching on the descendant text disambiguates among three selectable rows.

Effort + YOLO sections ([#229](../codebase/229.md)):

- **`renders_effort_section_with_all_five_chips`** — asserts "Effort" header + each of `"low"` / `"medium"` / `"high"` / `"xhigh"` / `"max"` is displayed.
- **`tapping_low_chip_invokes_onEffortSelected_with_low`** — `selectedEffort = Effort.HIGH`, capture into `mutableListOf<Effort>()`, `onNode(hasText("low")).performClick()`, asserts `[Effort.LOW]`. One representative chip is enough; the LOW/MEDIUM/HIGH/XHIGH/MAX mapping is exhaustively covered by the `Effort.entries.forEach` iteration in the production code.
- **`selected_effort_chip_reports_selected_semantics`** — `selectedEffort = Effort.MAX`. Positive assertion: `onNode(isSelectable() and hasAnyDescendant(hasText("max"))).assertIsSelected()`. Negative sister: `onNode(isSelectable() and hasAnyDescendant(hasText("low"))).assertIsNotSelected()` — pinning both halves of the contract guards against an over-broad `selected = true` regression on all chips that the positive-only test would miss.
- **`renders_yolo_section_with_title_and_supporting_text`** — asserts "YOLO mode" header + `"Auto-accept tool calls"` + `"Claude runs commands without asking for confirmation. Use carefully."` are all displayed.
- **`tapping_yolo_row_when_off_invokes_onYoloToggled_with_true`** — `yoloEnabled = false`, capture, `onNode(hasText("Auto-accept tool calls")).performClick()` (the `toggleable` modifier owns the whole row, so any in-row text node's click fires the callback), asserts `[true]`.
- **`tapping_yolo_row_when_on_invokes_onYoloToggled_with_false`** — `yoloEnabled = true`, click the row, asserts `[false]`. Pins the bidirectional toggle contract.

Context window section ([#230](../codebase/230.md); rewritten in [#601](../codebase/601.md)):

- **`renders_context_window_section_as_unavailable_with_header_and_caption`** — renamed from `renders_context_window_section_with_header_label_and_caption` in #601, which also dropped the 3 token args from the `StatusSheetContent(...)` call. Asserts `"Context window"` header + `"Context usage unavailable"` + the full caption are all displayed, **and** that no progress indicator exists anywhere in the tree: `onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)`. `keyIsDefined` (not a value-based `expectValue`) is the right matcher because the point is that no `ProgressBarRangeInfo` node exists at all. Safe to assert over the whole tree — no other node in `StatusSheetContent` (`RadioButton`, `FilterChip`, `Switch`) sets that key.
- **`label_format_uses_integer_K_division` — deleted in #601.** Asserted only `formatTokens` output (`"5% used (12K of 200K tokens)"`); with the formatter gone there was nothing left to exercise.
- **`label_format_handles_zero_values_gracefully` — deleted in #601, compiler-invisible.** Passed **no** token args at all, relying on the `= 0` parameter defaults to assert `"0% used (0K of 0K tokens)"` — so removing the params left it compiling clean and failing only at run time on device. `compileDebugAndroidTestKotlin` going green is not proof this test file is finished when defaulted params are the thing being removed; it had to be found and deleted by name.

No unit tests under `app/src/test/` for the sheet itself. The sheet is pure presentation; the underlying VM plumbing is pinned by [`ThreadViewModelTest`](thread-screen-testing.md#testing) — `selectedModel` via [#253](../codebase/253.md), `selectedEffort` + `yoloEnabled` via [#229](../codebase/229.md). The `tokenPercent` / `tokensUsed` / `tokensTotal` `ThreadViewModelTest` assertions added in [#230](../codebase/230.md) were untouched by #601 (`ThreadViewModel` was not edited); [#603](../codebase/603.md) has since dropped those trailing assertions and renamed the two affected test methods — see [thread-status-row.md § Testing](thread-status-row.md#testing) for the current names.

## Edge cases / limitations

- **Asymmetric auto-close on selection.** Tapping a radio row (Model) or chip (Effort) fires the callback and immediately closes the sheet — even if the user picks the value that was already selected. Tapping the YOLO row (or its switch) fires `onYoloToggled(!previous)` and **leaves the sheet open**. The Context window section has no callback at all — it's read-only. The asymmetry is deliberate: a single-pick is a complete action (M3 modal-bottom-sheet convention), but a Switch is a state-change the user may want to immediately reverse, and a display panel has no action surface to begin with. Resolution to the [#254](../codebase/254.md) open question, extended in [#230](../codebase/230.md) — see [§ Hosting in `ThreadScreen`](#hosting-in-threadscreen).
- **No section abstraction.** The sheet body renders all four sections directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. The [#229](../codebase/229.md) spec flagged [#230](../codebase/230.md) as the re-evaluation point; [#230](../codebase/230.md)'s call was "still not yet — four sections is the threshold but the body divergence is high (radio rows / chip row / toggle row / label+progress+caption block); wait for a fifth occurrence or a real shape divergence". The header + container shape is repeated; the body shape is not. Pre-introducing the abstraction would have multiplied the surface for three tickets of value.
- **`progressColor` no longer exists in this file — deleted whole in [#601](../codebase/601.md).** It shared its `< 50 / < 95 / ≥ 95` boundaries with [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper) (still live, unaffected by #601) but never its colour slots — `progressColor` returned fill chroma (`primary` / `warning` / `error`), `tokenPercentColor` returns text emphasis (`onSurfaceVariant` / `warning` / `error`). That non-sharing decision is now moot for this file (nothing here needs a threshold colour), but `tokenPercentColor` on the always-visible row still carries the same `73%` stub — see [#602](https://github.com/pyrycode/pyrycode-mobile/issues/602), the sibling ticket that removes the row's fabricated segment.
- **No `strings.xml` extraction.** All inline strings (`"Run configuration"`, `"Model"` / `"Effort"` / `"YOLO mode"` / `"Context window"` headers, the three Model descriptions, the YOLO two-line label, the Context window caption, `"Close"`) are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. Product-vendor strings like model descriptions may rewrite at that point — extracting them now would mix layers without lock-in value.
- **`Model.description()` is private to this file.** A future Settings model-picker (still pending per [`app-preferences`](app-preferences.md) gap) cannot import it. That is intentional — the descriptions are framed for the per-conversation override flow, not a global default. If Settings wants descriptions, it declares its own (potentially with different copy).
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` / `Switch.onCheckedChange` handler on the inner control.** Passing one in addition to the parent row's `selectable.onClick` (Model) or `toggleable.onValueChange` (YOLO) causes the callback to fire twice. The canonical M3 pattern is `RadioButton(selected, onClick = null)` and `Switch(checked, onCheckedChange = null)` with the parent `Row` owning the click. Pinned by the `tapping_*_invokes_*` tests' exact-list assertions ([§ Tests](#tests)). `FilterChip` does **not** follow this pattern — the chip itself owns the click and `selectableGroup()` wraps the row.
- **YOLO ignores `AppPreferences.defaultYolo`.** The dormant `defaultYolo` flow on `AppPreferences` and the non-functional Settings YOLO row at `SettingsScreen.kt:80, 169` are both dead code; this sheet's `yoloEnabled` parameter is sourced from `ThreadViewModel`'s `private val yoloEnabled = MutableStateFlow(false)` — hardcoded `false` initial, no preference read. The architectural single-writer invariant (`onYoloToggled` is the only writer of the field) is enforced by `private` visibility on the ViewModel's flow plus a code-review `git grep` check; the AC test `yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault` verifies the dormant preference does not leak. See [#229](../codebase/229.md) § Patterns established for the rationale.
- **Context window figure is unavailable by design, not by stub.** Through [#601](../codebase/601.md) `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` still populated from `STUB_*` companion constants (`73`, `146_000`, `200_000`) on `ThreadViewModel` — untouched, since #601 was client-only — while this sheet no longer read any of them, so no stub figure reached the user here. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, so the fields don't exist at all any more. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) (blocked on daemon-side pyrycode PR #1215) reintroduces parameters and a render once the daemon serves a real figure; the ticket's Technical Notes explicitly reject modeling a sealed `Unavailable | Known(...)` type now, since there is exactly one possible value today.
