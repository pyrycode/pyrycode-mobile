# StatusSheet — hosting, tests and edge cases

Split out of [StatusSheet](status-sheet.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [StatusSheet](status-sheet.md); see that document for the rest.

## Hosting in `ThreadScreen`

The footer opener sets `sheetVisible`; `ThreadScreen` mounts the shared modal and forwards one existing settings action per Model, Effort or Permission selection before dismissing. Done, Close and Back only clear visibility. Agent-switch confirmation and the ViewModel's pending and rejection paths stay with the host. The modal's body scrolls while Done remains pinned.

The sheet is mounted inside [`ThreadScreen`](thread-screen.md) at the screen-root level, as a `Scaffold` sibling (not inside the `Scaffold` content slot — `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order). The screen owns a `rememberSaveable`-hoisted visibility flag and forwards the model selection to the VM. **[#807](../codebase/807.md) re-sourced every argument below off the daemon's own readings** — the shape shown is the current one:

```kotlin
@Composable
fun ThreadScreen(
    // ...existing params...
    onModelSelected: (String) -> Unit = {},   // (Model) -> Unit through #807
    // ...existing params...
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    Box(/* ... */) {                        // wraps the Scaffold since #808, for the options-overlay layer
        Scaffold(
            // ...
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ThreadStatusArea(/* ... */)
                    ThreadInputBar(onSend = onSendMessage)
                    ThreadComposerFooter(
                        runConfig = state.runConfig,
                        onOpen = { openControl = it },
                        onStatusClick = { sheetVisible = true },   // the sheet's opener since #808
                        onAnchorChanged = { control, bounds -> footerAnchors[control] = bounds },
                    )
                }
            },
        ) { /* content */ }
        /* openMenu?.let { … OptionsOverlay(...) … } — see Thread composer footer for the full block */
    }
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
            onDismiss = { sheetVisible = false },
            running = state.runConfig.running,   // #891: what claude announced, never derived from the selection above
            contextPercent = state.runConfig.contextPercent,   // #946: Claude's reported context percentage, verbatim
        )
    }
}
```

(`tokenPercent` / `tokensUsed` / `tokensTotal` args removed at this call site in [#601](../codebase/601.md); `onDismiss` is now the trailing argument. [#603](../codebase/603.md) has since deleted the fields from `ThreadUiState` entirely. **[#807](../codebase/807.md) deleted `selectedModel: Model` / `selectedEffort: Effort` sourced from `AppPreferences`** and replaced every model/effort argument with one read off `state.runConfig: ThreadRunConfig` — see [status-sheet.md § Shape](status-sheet.md#shape) and [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing). **[#808](../codebase/808.md) retired `ThreadStatusRow`** and its `onExpandClick` in favour of [`ThreadComposerFooter`](thread-composer-footer.md)'s trailing `onStatusClick`; the `StatusSheet(...)` call itself (its arguments, above) is unchanged by #808 — only its opener moved. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed the `yoloEnabled` / `onYoloToggled` arguments outright**, with no successor argument at this call site — the permission control this pair drove is now [`ThreadComposerFooter`](thread-composer-footer.md#permission-mode-650)'s, wired separately in the `bottomBar` block above, not here. **[#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) added `contextPercent = state.runConfig.contextPercent`** beside `running` — the identical field the [composer footer's `Cxt:` segment](thread-composer-footer.md#context-usage-segment-946) reads, so the two surfaces cannot disagree.)

Four things to notice:

- **`var sheetVisible by rememberSaveable { mutableStateOf(false) }`** lives on the screen, not on `ThreadViewModel`. UI-local presentation state with no business meaning belongs at the screen layer; pushing it into the VM would dirty the VM contract with screen-presentation concerns. `rememberSaveable` (over bare `remember`) means the sheet survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md).
- **Auto-close on selection.** `onModelSelected` and `onEffortSelected` both wrap in `{ value -> upstream(value); sheetVisible = false }` — a radio/chip pick is a complete action, M3 modal-bottom-sheet convention ([#229](../codebase/229.md) resolution to the #254 open question, [#230](../codebase/230.md) extension: discrete-value picker → auto-close, slider/toggle/display → keep open). The Context window section has no callback at all (read-only display); taps inside it do nothing. Through [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) this sheet also hosted `onYoloToggled`, which passed straight through with no auto-close, since a `Switch` toggle is a state-change the user may want to immediately reverse; #650 removed that row (and the asymmetry with it) along with the toggle itself. The [composer footer](thread-composer-footer.md)'s `OptionsOverlay`-based permission menu that replaced it auto-closes on selection, same as Model and Effort.
- **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects.
- **`ModalBottomSheet`'s hide animation runs on a coroutine the framework owns** — no `sheetState.hide()` call needed before flipping `sheetVisible = false`.

The `ThreadScreen`-side `onModelSelected: (String) -> Unit = {}` and `onEffortSelected: (String) -> Unit = {}` (retyped off `Model` / `Effort` by [#807](../codebase/807.md)) are defaulted so previews and other call sites keep compiling unchanged; `MainActivity` binds them at the `CONVERSATION_THREAD` destination:

```kotlin
ThreadScreen(
    // ...
    onModelSelected = vm::onModelSelected,
    onEffortSelected = vm::onEffortSelected,
    // ...
)
```

Both are method references into [`ThreadViewModel`](thread-screen.md#viewmodel); no edit was needed at `MainActivity` for #807 — references re-adapt to the retyped VM functions automatically. Through [#229](../codebase/229.md)/[#253](../codebase/253.md) they were synchronous `MutableStateFlow.value` writes only — an operator's tap moved the control locally and nothing else happened. [#544](../codebase/544.md) wired them (plus `onYoloToggled`, since retired) to the daemon: each wrote its override flow first (optimistic move — `onModelSelected`/`onEffortSelected` updated the override layer over the matching `AppPreferences.default*` flow), then sent only the changed field to `Conversation.currentSessionId` via `ConversationRepository.setSessionSettings` ([#543](../codebase/543.md)) and reverted the write on a daemon error or a not-connected `IllegalStateException`, surfacing the standard transient snackbar (`sessionSettingsErrors`, wired at [`ThreadScreen`](thread-screen.md) and `MainActivity`).

**[#807](../codebase/807.md) kept #544's send/revert shape but replaced what it sends and where it sends to.** `onModelSelected(value: String)` / `onEffortSelected(level: String)` now mark a `pendingModel` / `pendingEffort` tap (no `AppPreferences` override layer at all — that sourcing is gone) and address the write to `SessionSettings.sessionId` from the current settings reading, never `Conversation.currentSessionId`. An empty session id means the daemon has no session to address, so the tap is dropped (logged content-free, no error surfaced — a read-only session is not a failure) rather than sent and refused server-side. See [`../codebase/544.md`](../codebase/544.md) for the original wiring detail and [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing) for the current one.

**[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed `onYoloToggled` from this binding and from `ThreadScreen`'s parameter list entirely** — it had shared `sendSessionSettings` with `onModelSelected` / `onEffortSelected` (inheriting the same session-id source and read-only gate, a deliberate #807-era scope-bleed). Its replacement, `onPermissionModeSelected: (String) -> Unit`, binds at the same `MainActivity` call site (`onPermissionModeSelected = vm::onPermissionModeSelected`) but reaches `ThreadComposerFooter`'s overlay, not `StatusSheet` — the sheet has no permission parameter to bind. It also does not reuse `sendSessionSettings`: a permission write needs the ack-then-settle rule and the `yolo`/`permission_mode` exclusivity `sendSessionSettings` doesn't have, so it has its own path, `ThreadViewModel.sendPermissionMode` — see [thread-composer-footer.md § Sourcing — Permission mode](thread-composer-footer.md#permission-mode-650).

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
    running: ThreadRunningModel = PreviewRunning,   // #891
    darkTheme: Boolean = false,
)
```

The seven `@Preview`s, each a one-line `PreviewSheet(...)` call overriding only what it varies: `StatusSheetPublishedPreview` (all defaults — a normal published menu), `StatusSheetPublishedDarkPreview` (`darkTheme = true`), `StatusSheetNoEffortLevelsPreview` (`selectedModel = PreviewHaiku.value` — the no-effort-levels case), `StatusSheetMenuUnavailablePreview` (`choices = emptyList(), menuAvailable = false` — the #601 honest-unavailable idiom extended to Model), `StatusSheetTruncatedMenuPreview` (`notListedModels = 44` — the "N shown · M not listed" caption), `StatusSheetPendingPreview` (`pending = true` — the `"· applying…"` header suffix), and `StatusSheetReadOnlyPreview` (`enabled = false, selectedModel = ""` — the read-only-session render). All target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme sweep beyond the one pair is deliberately not included; the theme behaviour is identical across themes. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) dropped `PreviewSheet`'s `yoloEnabled` parameter and `StatusSheetPendingPreview`'s Figma-preview name lost its `", YOLO on"` suffix (`"StatusSheet — applying, YOLO on"` → `"StatusSheet — applying"`) — the preview count stayed at seven; nothing was added or removed, only the one parameter.

**[#891](https://github.com/pyrycode/pyrycode-mobile/issues/891) defaulted `running` to a file-private `PreviewRunning` fixture** (an announced model + build, both `truncated = false`) and added two more previews, taking the count to nine: `StatusSheetRunningUnavailablePreview` (`running = ThreadRunningModel()` — the "Not announced yet" state) and `StatusSheetRunningTruncatedDarkPreview` (a truncated model and build, `darkTheme = true` — proves the italic truncation mark is visible in both themes).

## Tests

\#1195 adds shared-modal dismissal and radio semantics checks to `StatusSheetTest`, including the final published choice at compact width with 1.6× text and pinned Done. `ThreadComposerFooterTest` scrolls to the retained Permission unavailable state and checks that it has no selectable choices; its context assertion expects `Not reported yet` in the modal while the footer still says `Context usage unavailable`. The device capture test compares 412 × 892 emulator pixels with Figma node `600:1694`; see the [labelled overlay](../../../../app/src/androidTest/assets/status-1195/figma-emulator-overlay.png).

The inspected design nodes on 2026-09-28 were `598:1565`, current dark `600:1694`, shared modal `533:2369` and component `489:1942`. Figma omits Permission, pending, rejection, long-list and unavailable-reading states. The overlay accounts for Android's status bar; the system bars and retained Permission section explain the remaining visible differences. The surface has no keyboard field or popup menu; compact published lists were checked for clipping.

**[#807](../codebase/807.md) rewrote `androidTest/.../StatusSheetTest.kt` from twelve tests over the `Model` / `Effort` device enums to nineteen over the daemon-published `choices` / `effortChoices` shape** (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host. The twelve pre-#807 `StatusSheetContent(...)` call sites — one per test, each restating every unvaried parameter — are replaced by one `ComposeContentTestRule.setSheet(...)` extension carrying three file-private `ThreadModelChoice` fixtures (`opus` / `sonnet` / `haiku`, mirroring the production previews' fixtures) plus every parameter defaulted, so each test names only what it varies; `effortChoices` defaults to the selected row's own levels when left `null`, mirroring what the ViewModel derives. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) dropped `setSheet`'s `yoloEnabled` / `onYoloToggled` parameters** along with the three YOLO tests below, replacing them with one negative assertion — seventeen tests total.

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

Running model ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891)):

- **`the_announced_model_and_build_render_apart_from_the_selected_model`** — a differing announced model and build render under the "Running model" header, tagged with `RUNNING_MODEL_TEST_TAG`, while the Model section's selected row is unchanged — pins that the new section never overwrites or is overwritten by the selection.
- **`no_announcement_renders_the_unavailable_state_and_no_build_line`** — `running = ThreadRunningModel()` (the default): asserts "Not announced yet", no tagged node, and no "Claude Code" build line — the unavailable state never falls back to the selected or saved model.
- **`a_truncated_value_carries_a_visible_and_announced_mark`** — both `model.truncated` and `build.truncated` set: asserts the rendered text itself contains the `" (truncated)"` mark (via `hasText`, not a separate icon or `contentDescription`), which is what TalkBack reads.

This took the file's total from eighteen tests to twenty-one. No `app/src/test/` unit tests for the section either — `ThreadViewModelRunningModelTest` (see [thread-screen.md](thread-screen.md)) pins the sourcing; this file pins only the render.

Pending / read-only (new sections, #807):

- **`a_pending_write_is_announced_and_disables_both_controls`** — `pending = true`; asserts both section headers carry `"· applying…"` and both sections' controls report not-enabled (`assertIsNotEnabled`).
- **`a_read_only_session_disables_the_controls_without_claiming_a_write_is_in_flight`** — `enabled = false, pending = false`; asserts the controls disable but the headers carry **no** `"· applying…"` suffix — the two states are visually distinct, not conflated.

Close + Context window (largely unchanged from [#601](../codebase/601.md), retargeted at the new fixture helper): `tapping_close_icon_invokes_onDismiss`, `renders_context_window_section_as_unavailable_with_header_and_caption` (still asserts no `ProgressBarRangeInfo` node exists anywhere in the tree). [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) added `renders_the_reported_context_percentage_in_place_of_the_unavailable_line`: `setSheet(contextPercent = 84)` asserts `"84% used"` displayed, `"Context usage unavailable"` absent, and the caption still displayed — no `ProgressBarRangeInfo` assertion needed here since that case is already pinned by the unavailable-state test above.

**[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) deleted the three [#229](../codebase/229.md)-era YOLO tests** (`renders_yolo_section_with_title_and_supporting_text`, `tapping_yolo_row_when_off_invokes_onYoloToggled_with_true`, `tapping_yolo_row_when_on_invokes_onYoloToggled_with_false`) and added one negative assertion in their place: **`has_no_yolo_switch`** asserts neither `"YOLO mode"` nor `"Auto-accept tool calls"` text exists in the tree at all. This also retires the [known gap](status-sheet.md#yolorow-retired-by-650) the deleted tests left untested — the row's missing `enabled`/`pending` wiring is moot once the row itself is gone. The permission button's own pending/hidden/menu behaviour is covered by [`ThreadComposerFooterTest`](thread-composer-footer-testing.md#testing), not this file.

No unit tests under `app/src/test/` for the sheet itself. The sheet is pure presentation; the underlying VM plumbing is pinned by `ThreadViewModelTest`'s `runConfig_*` / `on{Model,Effort}Selected_*` / `sessionSettings_*` groups, plus, since #650, `ThreadViewModelPermissionTest` for the permission write and settle path — see [thread-composer-footer-testing.md § Testing](thread-composer-footer-testing.md#testing) for representative names.

## Edge cases / limitations

- **Auto-close on selection.** Tapping a radio row (Model) or chip (Effort) fires the callback and immediately closes the sheet — even if the user picks the value that was already selected. The Context window section has no callback at all — it's read-only. A single-pick closing the sheet is deliberate: M3 modal-bottom-sheet convention for a complete action. Resolution to the [#254](../codebase/254.md) open question, extended in [#230](../codebase/230.md) — see [§ Hosting in `ThreadScreen`](#hosting-in-threadscreen). Through [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) this section also covered `YoloRow`'s switch, which fired `onYoloToggled(!previous)` and deliberately left the sheet open (a state-change the user may want to immediately reverse); #650 removed the row along with that asymmetry.
- **No section abstraction.** The sheet body renders all three sections directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. The [#229](../codebase/229.md) spec flagged [#230](../codebase/230.md) as the re-evaluation point; [#230](../codebase/230.md)'s call was "still not yet — four sections is the threshold but the body divergence is high (radio rows / chip row / toggle row / label+progress+caption block); wait for a fifth occurrence or a real shape divergence" — [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) retired the toggle row and took the section count back to three, so that threshold is further off than when [#230](../codebase/230.md) wrote it. The header + container shape is repeated; the body shape is not. Pre-introducing the abstraction would have multiplied the surface for the tickets shipped so far.
- **`progressColor` no longer exists in this file — deleted whole in [#601](../codebase/601.md).** It shared its `< 50 / < 95 / ≥ 95` boundaries with `ThreadStatusRow.tokenPercentColor` (unaffected by #601 at the time) but never its colour slots — `progressColor` returned fill chroma (`primary` / `warning` / `error`), `tokenPercentColor` returned text emphasis (`onSurfaceVariant` / `warning` / `error`). [#602](../codebase/602.md) went on to delete `tokenPercentColor` itself along with the row's fabricated segment, and [#808](../codebase/808.md) retired the row it lived on entirely — see [Thread composer footer](thread-composer-footer.md).
- **No `strings.xml` extraction.** All inline strings (`"Run configuration"`, `"Model"` / `"Effort"` / `"Context window"` headers, the [#807](../codebase/807.md) fallback copy `"Model list unavailable"` / `"This server published no models."` / `"No effort levels published for this model."`, the Context window caption, `"Close"`) are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. The three Figma-derived Model-description literals this bullet used to name (`"best for complex work"` etc.) are gone with [#807](../codebase/807.md)'s deleted `Model.description()` table — see below. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) removed the `"YOLO mode"` header and the YOLO row's two-line label literal with `YoloRow`.
- **`Model.description()` is gone, deleted whole by [#807](../codebase/807.md).** It was a private file-local extension mapping the three `Model` entries to Figma-derived taglines; there is no fixed vocabulary left to map onto. `ThreadModelChoice.detail` (the ViewModel's inert `resolvedModel`) is the replacement, and it is not a per-sheet override of anything Settings could reuse — it comes from the daemon, per conversation, per row.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` handler on the inner control.** Passing one in addition to the parent row's `selectable.onClick` (Model) causes the callback to fire twice. The canonical M3 pattern is `RadioButton(selected, onClick = null)` with the parent `Row` owning the click. Pinned by the Model/Effort tapping tests' exact-value assertions ([§ Tests](#tests)). `FilterChip` does **not** follow this pattern — the chip itself owns the click and `selectableGroup()` wraps the row. Through [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) `YoloRow`'s `Switch(checked, onCheckedChange = null)` followed the same pattern against `toggleable.onValueChange`; both are gone with the row.
- **YOLO's read-only / pending disable and its ignoring `AppPreferences.defaultYolo` — retired by #650, not fixed.** Through [#807](../codebase/807.md), `YoloRow` took only `enabled: Boolean` (meaning the switch's checked state, not "interactive"), with no `pending` or read-only argument wired at all — a known, unfixed verifier SHOULD FIX. [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) deleted `YoloRow` outright rather than fixing that wiring — see [status-sheet.md § `YoloRow` — retired by #650](status-sheet.md#yolorow-retired-by-650). Its replacement, the [composer footer](thread-composer-footer.md#permission-mode-650)'s permission button, has no equivalent gap: `footerControlEnabled(Permission, …)` reads `writable` and `pendingPermission` the same way Model and Effort read `writable`/`pending`. `AppPreferences.defaultYolo` and the dormant Settings YOLO row at `SettingsScreen.kt:80, 169` are untouched by #650 (the ticket explicitly keeps that preference as-is and feeds it into nothing here) — see [status-sheet.md § Related](status-sheet.md#related).
- **The Running model section has no `maxLines`/ellipsis, deliberately ([#891](https://github.com/pyrycode/pyrycode-mobile/issues/891)).** Both `Text`s render an `AnnotatedString` that appends an italic `" (truncated)"` mark when the value was cut; an ellipsis on a long value would clip exactly that mark, showing a cut value as if it were whole. The [`inert()`](thread-composer-footer.md#sourcing) 128-character bound already caps the layout, so no line limit is needed for that reason either.
- **`SessionFacts.permissionMode` is claude's own claim and is never read by this feature.** The Running model section renders only `model` and `claude_code_version`; the claimed permission posture never reaches `ThreadRunConfig.permissionMode`, the footer's permission button, or `pendingPermission` — pinned by a `ThreadViewModelRunningModelTest` case. See [Thread composer footer § Permission mode](thread-composer-footer.md#permission-mode-650) for the one permission reading this sheet is allowed to reflect (the daemon-confirmed one, not claude's claim).
- **Context window figure was unavailable by design, not by stub — now shown when Claude reports one.** Through [#601](../codebase/601.md) `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` still populated from `STUB_*` companion constants (`73`, `146_000`, `200_000`) on `ThreadViewModel` — untouched, since #601 was client-only — while this sheet no longer read any of them, so no stub figure reached the user here. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, so the fields don't exist at all any more. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) split into [#945](https://github.com/pyrycode/pyrycode-mobile/issues/945) (the daemon reading) and [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) (this render): `contextPercent: Int?` is a plain nullable `Int`, not a sealed `Unavailable | Known(...)` type — the ticket's own reasoning for that shape still held once there was a real value to carry, since `null` already says everything a sealed unavailable case would. **Still absent until a turn ends:** [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)'s Rework 1 removed the phone's on-subscription `request_context_usage` ask (it deadlocked a connection mid-turn), so this section shows "unavailable" until the conversation's next turn completes on the current connection — see [Thread composer footer § Context usage segment](thread-composer-footer.md#context-usage-segment-946) for the wire-level reason.
