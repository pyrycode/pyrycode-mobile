# StatusSheet — hosting, tests and edge cases

Split out of [StatusSheet](status-sheet.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [StatusSheet](status-sheet.md); see that document for the rest.

## Hosting in `ThreadScreen`

The sheet is mounted inside [`ThreadScreen`](thread-screen.md) at the screen-root level, as a `Scaffold` sibling (not inside the `Scaffold` content slot — `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order). The screen owns a `rememberSaveable`-hoisted visibility flag and forwards the model selection to the VM. **[#807](../codebase/807.md) re-sourced every argument below off the daemon's own readings** — the shape shown is the current one:

```kotlin
@Composable
fun ThreadScreen(
    // ...existing params...
    onModelSelected: (String) -> Unit = {},   // (Model) -> Unit through #807
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
                    pending = state.runConfig.pending,   // #807
                )
                ThreadInputBar(onSend = onSendMessage)
            }
        },
    ) { /* content */ }
    WorkspacePicker(/* ... */)              // sibling host from #137
    if (sheetVisible) {
        StatusSheet(
            choices = state.runConfig.choices,
            menuAvailable = state.runConfig.menuAvailable,
            notListedModels = state.runConfig.droppedModels + state.runConfig.hiddenChoices,
            selectedModel = state.runConfig.selectedModel,
            onModelSelected = { value ->
                onModelSelected(value)
                sheetVisible = false
            },
            effortChoices = state.runConfig.effortChoices,
            selectedEffort = state.runConfig.selectedEffort,
            onEffortSelected = { level ->
                onEffortSelected(level)
                sheetVisible = false
            },
            pending = state.runConfig.pending,
            enabled = state.runConfig.writable,   // "" sessionId ⇒ read-only
            yoloEnabled = state.yoloEnabled,
            onYoloToggled = onYoloToggled,
            onDismiss = { sheetVisible = false },
        )
    }
}
```

(`tokenPercent` / `tokensUsed` / `tokensTotal` args removed at this call site in [#601](../codebase/601.md); `onDismiss` is now the trailing argument. [#603](../codebase/603.md) has since deleted the fields from `ThreadUiState` entirely. **[#807](../codebase/807.md) deleted `selectedModel: Model` / `selectedEffort: Effort` sourced from `AppPreferences`** and replaced every model/effort argument with one read off `state.runConfig: ThreadRunConfig` — see [status-sheet.md § Shape](status-sheet.md#shape) and [thread-status-row.md § Sourcing](thread-status-row.md#sourcing).)

Four things to notice:

- **`var sheetVisible by rememberSaveable { mutableStateOf(false) }`** lives on the screen, not on `ThreadViewModel`. UI-local presentation state with no business meaning belongs at the screen layer; pushing it into the VM would dirty the VM contract with screen-presentation concerns. `rememberSaveable` (over bare `remember`) means the sheet survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md).
- **Asymmetric auto-close: single-pick sections close the sheet, toggle/display sections don't** ([#229](../codebase/229.md) resolution to the #254 open question, [#230](../codebase/230.md) extension). `onModelSelected` and `onEffortSelected` both wrap in `{ value -> upstream(value); sheetVisible = false }` — a radio/chip pick is a complete action, M3 modal-bottom-sheet convention. `onYoloToggled` passes straight through with no auto-close — a Switch toggle is a state-change the user may want to immediately reverse. The Context window section has no callback at all (read-only display); taps inside it do nothing. The final rule from [#230](../codebase/230.md): discrete-value picker → auto-close, slider/toggle/display → keep open.
- **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects.
- **`ModalBottomSheet`'s hide animation runs on a coroutine the framework owns** — no `sheetState.hide()` call needed before flipping `sheetVisible = false`.

The `ThreadScreen`-side `onModelSelected: (String) -> Unit = {}`, `onEffortSelected: (String) -> Unit = {}` (retyped off `Model` / `Effort` by [#807](../codebase/807.md)), `onYoloToggled: (Boolean) -> Unit = {}` are all defaulted so previews and other call sites keep compiling unchanged; `MainActivity` binds the three parameters at the `CONVERSATION_THREAD` destination:

```kotlin
ThreadScreen(
    // ...
    onModelSelected = vm::onModelSelected,
    onEffortSelected = vm::onEffortSelected,
    onYoloToggled = vm::onYoloToggled,
    // ...
)
```

All three are method references into [`ThreadViewModel`](thread-screen.md#viewmodel); no edit was needed at `MainActivity` for #807 — references re-adapt to the retyped VM functions automatically. Through [#229](../codebase/229.md)/[#253](../codebase/253.md) they were synchronous `MutableStateFlow.value` writes only — an operator's tap moved the control locally and nothing else happened. [#544](../codebase/544.md) wired all three to the daemon: each wrote its override flow first (optimistic move — `onModelSelected`/`onEffortSelected` updated the override layer over the matching `AppPreferences.default*` flow, `onYoloToggled` wrote the `yoloEnabled` flag directly with no preference layer), then sent only the changed field to `Conversation.currentSessionId` via `ConversationRepository.setSessionSettings` ([#543](../codebase/543.md)) and reverted the write on a daemon error or a not-connected `IllegalStateException`, surfacing the standard transient snackbar (`sessionSettingsErrors`, wired at [`ThreadScreen`](thread-screen.md) and `MainActivity`).

**[#807](../codebase/807.md) kept #544's send/revert shape but replaced what it sends and where it sends to.** `onModelSelected(value: String)` / `onEffortSelected(level: String)` now mark a `pendingModel` / `pendingEffort` tap (no `AppPreferences` override layer at all — that sourcing is gone) and address the write to `SessionSettings.sessionId` from the current settings reading, never `Conversation.currentSessionId`. An empty session id means the daemon has no session to address, so the tap is dropped (logged content-free, no error surfaced — a read-only session is not a failure) rather than sent and refused server-side. `onYoloToggled` shares the same `sendSessionSettings` helper, so it now inherits the same session-id source and the same read-only gate — a deliberate scope-bleed the plan calls out explicitly, since the alternative was keeping `Conversation.currentSessionId` alive for one control. The daemon's ack still does not echo settings; on success the VM now calls `repository.refreshSessionSettings(conversationId)` and the pending tap survives **until that fresh reading lands**, not until the ack — an acknowledgement alone never becomes the confirmed reading. On failure the pending is cleared, which is what restores the previously confirmed value. See [`../codebase/544.md`](../codebase/544.md) for the original wiring detail and [thread-status-row.md § Sourcing](thread-status-row.md#sourcing) for the current one.

## Preview

**[#807](../codebase/807.md) rewrote every preview.** Through #601 there were five, sweeping the three `Model` entries and the `Effort` / `yoloEnabled` combinations directly. #807 deleted all five (the device enums they iterated are gone from this file) and replaced them with seven, built from a shared `PreviewSheet(...)` helper and three file-private `ThreadModelChoice` fixtures (`PreviewOpus` — 4 effort levels, `PreviewSonnet` — 2, `PreviewHaiku` — 0) that stand in for a daemon-published menu:

```kotlin
@Composable
private fun PreviewSheet(
    choices: List<ThreadModelChoice> = PreviewChoices,
    menuAvailable: Boolean = true,
    notListedModels: Int = 0,
    selectedModel: String = PreviewOpus.value,
    selectedEffort: String = "high",
    pending: Boolean = false,
    enabled: Boolean = true,
    yoloEnabled: Boolean = false,
    darkTheme: Boolean = false,
)
```

The seven `@Preview`s, each a one-line `PreviewSheet(...)` call overriding only what it varies: `StatusSheetPublishedPreview` (all defaults — a normal published menu), `StatusSheetPublishedDarkPreview` (`darkTheme = true`), `StatusSheetNoEffortLevelsPreview` (`selectedModel = PreviewHaiku.value` — the no-effort-levels case), `StatusSheetMenuUnavailablePreview` (`choices = emptyList(), menuAvailable = false` — the #601 honest-unavailable idiom extended to Model), `StatusSheetTruncatedMenuPreview` (`notListedModels = 44` — the "N shown · M not listed" caption), `StatusSheetPendingPreview` (`pending = true, yoloEnabled = true` — the `"· applying…"` header suffix), and `StatusSheetReadOnlyPreview` (`enabled = false, selectedModel = ""` — the read-only-session render). All target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme sweep beyond the one pair is deliberately not included; the theme behaviour is identical across themes.

## Tests

**[#807](../codebase/807.md) rewrote `androidTest/.../StatusSheetTest.kt` from twelve tests over the `Model` / `Effort` device enums to nineteen over the daemon-published `choices` / `effortChoices` shape** (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host. The twelve pre-#807 `StatusSheetContent(...)` call sites — one per test, each restating every unvaried parameter — are replaced by one `ComposeContentTestRule.setSheet(...)` extension carrying three file-private `ThreadModelChoice` fixtures (`opus` / `sonnet` / `haiku`, mirroring the production previews' fixtures) plus every parameter defaulted, so each test names only what it varies; `effortChoices` defaults to the selected row's own levels when left `null`, mirroring what the ViewModel derives.

Model section:

- **`renders_the_published_rows_with_their_labels_and_details`** — asserts "Model" header + each row's `label` and `detail` are displayed via `hasText` matchers. Replaces the pre-#807 fixed three-row assertion (`"Opus 4.7"` / `"best for complex work"` / …) — there is no fixed set to assert against any more, only whatever `setSheet`'s default `choices` carries.
- **`tapping_a_row_reports_the_published_value_not_the_label`** — taps a row by its label text, asserts the captured argument is the row's `value` (e.g. `"sonnet"`), not its `label` (`"Sonnet 4.6"`). Pins the write-argument-is-verbatim contract at the sheet boundary — a regression that accidentally sent the label back would still compile.
- **`selected_row_reports_selected_semantics`** — `onNode(isSelectable() and hasAnyDescendant(hasText(...))).assertIsSelected()`, `hasAnyDescendant` required because `selectable` lives on the parent `Row`.
- **`a_saved_value_no_row_publishes_selects_nothing`** — `selectedModel` set to a value absent from `choices`; asserts no row reports selected. AC #1's "an unavailable reading renders as unknown rather than … another conversation's value" has a sheet-side twin: a stale selection matching nothing selects nothing, not the first row.
- **`an_unavailable_menu_says_so_and_offers_no_rows`** — `choices = emptyList(), menuAvailable = false`; asserts the "Model list unavailable" copy and no radio rows.
- **`a_menu_that_published_nothing_is_a_different_statement_from_an_absent_one`** — `choices = emptyList(), menuAvailable = true`; asserts the distinct "This server published no models" copy. The two empty-`choices` readings are deliberately not collapsed into one string.
- **`a_truncated_menu_reports_the_count_it_was_given`** — `notListedModels > 0`; asserts the caption names the exact count passed in, never `choices.size`-derived.

Effort section:

- **`renders_the_selected_rows_effort_levels_in_wire_order`** — asserts the selected row's own `effortChoices`, in order, not a fixed five-entry sweep.
- **`tapping_a_chip_reports_its_level`** — the [#229](../codebase/229.md)-era representative-chip pattern, retargeted at a published level string instead of an `Effort` entry.
- **`selected_effort_chip_reports_selected_semantics`** — positive + negative `assertIsSelected` / `assertIsNotSelected` pair, same shape as [#229](../codebase/229.md)'s original.
- **`an_unset_saved_effort_selects_no_chip_but_still_offers_them`** — `selectedEffort = ""` (the daemon's own "no saved effort" reading); asserts every chip renders and none reports selected. Pins the AC line "an unset saved effort still allows selection when the row does publish levels."
- **`a_row_publishing_no_levels_offers_no_effort_choice`** — the `haiku` fixture (`effortChoices = emptyList()`); asserts the "No effort levels published for this model." line and no chips — never a fallback to the five device entries.

Pending / read-only (new sections, #807):

- **`a_pending_write_is_announced_and_disables_both_controls`** — `pending = true`; asserts both section headers carry `"· applying…"` and both sections' controls report not-enabled (`assertIsNotEnabled`).
- **`a_read_only_session_disables_the_controls_without_claiming_a_write_is_in_flight`** — `enabled = false, pending = false`; asserts the controls disable but the headers carry **no** `"· applying…"` suffix — the two states are visually distinct, not conflated.

YOLO + close + Context window (largely unchanged from [#229](../codebase/229.md)/[#601](../codebase/601.md), retargeted at the new fixture helper): `tapping_close_icon_invokes_onDismiss`, `renders_yolo_section_with_title_and_supporting_text`, `tapping_yolo_row_when_off_invokes_onYoloToggled_with_true`, `tapping_yolo_row_when_on_invokes_onYoloToggled_with_false`, `renders_context_window_section_as_unavailable_with_header_and_caption` (still asserts no `ProgressBarRangeInfo` node exists anywhere in the tree). **None of these five assert anything about `enabled` or `pending` on the YOLO switch** — see [status-sheet.md § `YoloRow`](status-sheet.md#yolorow) for the known gap this leaves untested.

No unit tests under `app/src/test/` for the sheet itself. The sheet is pure presentation; the underlying VM plumbing is pinned by `ThreadViewModelTest`'s `runConfig_*` / `on{Model,Effort,Yolo}Selected_*` / `sessionSettings_*` groups — see [thread-status-row.md § Testing](thread-status-row.md#testing) for representative names.

## Edge cases / limitations

- **Asymmetric auto-close on selection.** Tapping a radio row (Model) or chip (Effort) fires the callback and immediately closes the sheet — even if the user picks the value that was already selected. Tapping the YOLO row (or its switch) fires `onYoloToggled(!previous)` and **leaves the sheet open**. The Context window section has no callback at all — it's read-only. The asymmetry is deliberate: a single-pick is a complete action (M3 modal-bottom-sheet convention), but a Switch is a state-change the user may want to immediately reverse, and a display panel has no action surface to begin with. Resolution to the [#254](../codebase/254.md) open question, extended in [#230](../codebase/230.md) — see [§ Hosting in `ThreadScreen`](#hosting-in-threadscreen).
- **No section abstraction.** The sheet body renders all four sections directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. The [#229](../codebase/229.md) spec flagged [#230](../codebase/230.md) as the re-evaluation point; [#230](../codebase/230.md)'s call was "still not yet — four sections is the threshold but the body divergence is high (radio rows / chip row / toggle row / label+progress+caption block); wait for a fifth occurrence or a real shape divergence". The header + container shape is repeated; the body shape is not. Pre-introducing the abstraction would have multiplied the surface for three tickets of value.
- **`progressColor` no longer exists in this file — deleted whole in [#601](../codebase/601.md).** It shared its `< 50 / < 95 / ≥ 95` boundaries with [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper) (still live, unaffected by #601) but never its colour slots — `progressColor` returned fill chroma (`primary` / `warning` / `error`), `tokenPercentColor` returns text emphasis (`onSurfaceVariant` / `warning` / `error`). That non-sharing decision is now moot for this file (nothing here needs a threshold colour), but `tokenPercentColor` on the always-visible row still carries the same `73%` stub — see [#602](https://github.com/pyrycode/pyrycode-mobile/issues/602), the sibling ticket that removes the row's fabricated segment.
- **No `strings.xml` extraction.** All inline strings (`"Run configuration"`, `"Model"` / `"Effort"` / `"YOLO mode"` / `"Context window"` headers, the [#807](../codebase/807.md) fallback copy `"Model list unavailable"` / `"This server published no models."` / `"No effort levels published for this model."`, the YOLO two-line label, the Context window caption, `"Close"`) are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. The three Figma-derived Model-description literals this bullet used to name (`"best for complex work"` etc.) are gone with [#807](../codebase/807.md)'s deleted `Model.description()` table — see below.
- **`Model.description()` is gone, deleted whole by [#807](../codebase/807.md).** It was a private file-local extension mapping the three `Model` entries to Figma-derived taglines; there is no fixed vocabulary left to map onto. `ThreadModelChoice.detail` (the ViewModel's inert `resolvedModel`) is the replacement, and it is not a per-sheet override of anything Settings could reuse — it comes from the daemon, per conversation, per row.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` / `Switch.onCheckedChange` handler on the inner control.** Passing one in addition to the parent row's `selectable.onClick` (Model) or `toggleable.onValueChange` (YOLO) causes the callback to fire twice. The canonical M3 pattern is `RadioButton(selected, onClick = null)` and `Switch(checked, onCheckedChange = null)` with the parent `Row` owning the click. Pinned by the Model/Effort tapping tests' exact-value assertions ([§ Tests](#tests)). `FilterChip` does **not** follow this pattern — the chip itself owns the click and `selectableGroup()` wraps the row.
- **YOLO's read-only / pending disable is a known gap, not shipped.** `YoloRow` takes only `enabled: Boolean` (meaning the switch's checked state, not "interactive") and `onToggled`; the call site passes neither `pending` nor the sheet's own `enabled` gate into it, unlike `ModelSection` / `EffortChipRow`. On a read-only session or during a pending write the switch still looks live and shows no cue, even though [#807](../codebase/807.md) routed `onYoloToggled` through the same session-id gate as the other two controls. Flagged as a verifier SHOULD FIX on #807's review, not fixed as of this writing — see [status-sheet.md § `YoloRow`](status-sheet.md#yolorow).
- **YOLO ignores `AppPreferences.defaultYolo`.** The dormant `defaultYolo` flow on `AppPreferences` and the non-functional Settings YOLO row at `SettingsScreen.kt:80, 169` are both dead code; this sheet's `yoloEnabled` parameter is sourced from `ThreadViewModel`'s `private val yoloEnabled = MutableStateFlow(false)` — hardcoded `false` initial, no preference read. The architectural single-writer invariant (`onYoloToggled` is the only writer of the field) is enforced by `private` visibility on the ViewModel's flow plus a code-review `git grep` check. See [#229](../codebase/229.md) § Patterns established for the rationale.
- **Context window figure is unavailable by design, not by stub.** Through [#601](../codebase/601.md) `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` still populated from `STUB_*` companion constants (`73`, `146_000`, `200_000`) on `ThreadViewModel` — untouched, since #601 was client-only — while this sheet no longer read any of them, so no stub figure reached the user here. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, so the fields don't exist at all any more. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) (blocked on daemon-side pyrycode PR #1215) reintroduces parameters and a render once the daemon serves a real figure; the ticket's Technical Notes explicitly reject modeling a sealed `Unavailable | Known(...)` type now, since there is exactly one possible value today.
