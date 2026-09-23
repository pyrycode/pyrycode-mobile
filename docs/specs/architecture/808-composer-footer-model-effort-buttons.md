# #808 — Model and effort buttons in the composer footer

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` (the `bottomBar` column that mounts the footer inside `imePadding()`, `sheetVisible`, the `StatusSheet` mount) — the only production caller of the footer line, and where the overlay layer goes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt` → `ThreadStatusRow` — today's footer line (one monospace `model · effort` string, the sole opener of the Status sheet). Retired by this ticket.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadRunConfig`, `ThreadModelChoice`, `ThreadEffortChoice`, `runConfig`, `ModelMenuRow.toChoice`, `inert`, `MAX_RENDERED_MODEL_CHOICES` — the state the footer renders. Labels are already `inert()` (ISO controls stripped, 128-char cap); `value` is the verbatim write argument and is never rendered. `modelLabel` / `effortLabel` already resolve pending → saved → unknown/default.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet`, `sectionTitle` — the retained sheet disables its controls while `pending` or `!enabled`, and shows `N shown · M not listed` from the summed `notListedModels`. The footer mirrors both rules so the two surfaces agree.
- `app/src/androidTest/.../thread/ThreadStatusRowTest.kt` — the suite for the retired row; replaced.
- `app/src/androidTest/.../thread/ThreadFrameTest.kt` — hosts `ThreadScreen`; asserts no footer text, so it is unaffected.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt`, `Color.kt` — no `*Fixed` roles defined (confirmed); the overlay maps to defined roles below.
- `docs/knowledge/features/thread-status-row.md` — history of the row (#145, #254, #602, #807); becomes stale (see Documentation handoff).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (footer `110:3494` inside `Input area` `533:1957`); overlay https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958

The footer is a start-aligned row (16dp inset inside the composer's 20dp gutter, 4dp top padding, 16dp gap between items) of `Input footer button`s: each is an M3 `body-small` label in `Schemes/Primary` followed by a small up-chevron (8×4) at a 4dp gap. The `Options overlay` is an ~81dp-wide, 6dp-rounded column (2dp vertical padding) of `Option button` rows, each 12dp × 6dp padded `body-small` `Schemes/Primary` text; the selected row takes the container's `Schemes/On Primary` fill while the others take `Schemes/On Primary Fixed`.

Deviations (recorded in code comments and the PR):
- **Colours.** `Theme.kt` defines no `*Fixed` roles. The overlay surface is `surfaceContainerLowest` (dark `#0B0E12`, close to the design's `#001D34` unselected fill; light white), the selected row is `primaryContainer`, text is `primary`, and the surface carries a small shadow so it separates from the light-theme surface underneath. The design's selected-row-lighter reading is preserved in both themes.
- **Touch target.** The design's buttons are 16dp tall; each footer button gets `heightIn(min = 32.dp)` so a thumb can hit it, growing the footer by ~12dp.
- **Status-sheet opener.** The design has no footer affordance for the Status sheet, but the sheet is the only home of the YOLO toggle (AC#1), so a trailing `Icons.Outlined.Tune` icon with the existing `cd_thread_status_expand` description sits at the row's end. The design's `Actions` (#655), permission-mode (#650), `Cxt:` (#591) and attachment segments stay out.

## Context

#807 put the published model menu, the saved reading and the pending tap on `ThreadUiState.runConfig`, and wired `onModelSelected` / `onEffortSelected` into `ThreadScreen`. Today the only selection path is the Status sheet. This ticket gives model and effort their own footer buttons and the design's compact overlay, and keeps the sheet one tap away. No ADR is needed.

## Design

### `ThreadComposerFooter.kt` (new, `ui/conversations/thread/`)

- `enum class FooterControl { Model, Effort }`. The two future controls (#650 permission mode, #655 Actions) add entries here and a branch in `footerMenu`; nothing else changes shape.
- `data class FooterMenu(val options: List<OptionsOverlayOption>, val selectedValue: String, val notListed: Int)`.
- `internal fun footerMenu(control: FooterControl, runConfig: ThreadRunConfig): FooterMenu?`: pure. The menu the control offers, or `null` when it has nothing to offer:
  - Model: `null` unless `menuAvailable && choices.isNotEmpty()`. Options = `choices` → `(value, label)` in daemon order, `selectedValue = selectedModel`, `notListed = droppedModels + hiddenChoices`. Both fields are read, never recomputed from `choices.size`, and they are summed for display only, as in the sheet.
  - Effort: `null` when `effortChoices` is empty (the selected row publishes no levels, or no row is selected). `selectedValue = selectedEffort`, `notListed = 0`.
- `@Composable fun ThreadComposerFooter(runConfig, onOpen: (FooterControl) -> Unit, onStatusClick: () -> Unit, onAnchorChanged: (FooterControl, Rect) -> Unit, modifier)`: stateless. One private `FooterButton` per control shows `modelLabel` / `effortLabel` with the chevron. A button is **enabled** only when `runConfig.writable && !runConfig.pending && footerMenu(...) != null`, which is the sheet's `enabled && !pending` rule plus "nothing to offer". A disabled button still shows its value. It reports its window bounds through `onAnchorChanged` from `onGloballyPositioned`. The pending state renders at reduced alpha and carries a `stateDescription` (`thread_footer_pending`, "Applying"). A refused write needs no footer code: the VM clears `pendingModel`, so `modelLabel` falls back to the saved reading. Labels are `maxLines = 1`, ellipsized and width-capped.

### `OptionsOverlay.kt` (new, `ui/conversations/components/`)

- `data class OptionsOverlayOption(val value: String, val label: String)`.
- `@Composable fun OptionsOverlay(options, selectedValue, notListed: Int, anchor: Rect, onSelect: (String) -> Unit, onDismiss: () -> Unit, modifier)`: a full-size layer, **not** a `Popup` (a focusable popup steals the input field's focus; a non-focusable one lets the dismissing tap through). The layer has:
  - A transparent scrim over the whole layer. Its `pointerInput` tap calls `onDismiss`. Being the topmost hit node, it keeps the tap from reaching the composer or anything else. It has semantics `onClick` plus a content description (`cd_options_overlay_dismiss`) so accessibility users can dismiss it. `BackHandler` also dismisses.
  - The option column, placed by a custom `Layout`. Its bottom sits a small gap above `anchor.top`, and its start aligns the row text with the button text, clamped inside an 8dp margin. Max height is the space above the anchor, and the column scrolls vertically when it is taller. The anchor moves with the IME lift, so the overlay stays above the keyboard.
  - Rows are `selectable(selected, role = RadioButton)` → `onSelect(value)`. When `notListed > 0`, a trailing non-interactive caption row (`thread_options_not_listed`, "%1$d more not listed") marks the list as a subset. Labels render through plain `Text` only.
- `anchor` is given in the layer's own coordinates. The caller converts the button's window bounds by subtracting the layer's window origin.

### `ThreadScreen.kt` (modified)

- `Scaffold` moves inside a `Box(modifier)` that records its window origin (`onGloballyPositioned`). `Scaffold` takes `Modifier.fillMaxSize()`.
- `var openControl by remember(state.conversationId) { mutableStateOf<FooterControl?>(null) }`. It uses plain `remember`, deliberately not `rememberSaveable`: a back-stack return or another conversation never restores an open overlay (AC#4). The key drops it on a conversation switch within one composition.
- `val anchors = remember { mutableStateMapOf<FooterControl, Rect>() }`, fed by `onAnchorChanged`.
- `val openMenu = openControl?.let { footerMenu(it, state.runConfig) }` gated by the same enablement rule. If the menu vanishes while open (a reading lands, pending starts, the session drops), a `LaunchedEffect` clears `openControl`.
- `ThreadStatusRow(...)` in the bottomBar becomes `ThreadComposerFooter(runConfig = state.runConfig, onOpen = { openControl = it }, onStatusClick = { sheetVisible = true }, ...)` with the same gutter padding.
- The overlay mounts as the Box's last child: `onSelect` → `onModelSelected` / `onEffortSelected` (the value verbatim), then `openControl = null`. `onDismiss` → `openControl = null`.

### `ThreadStatusRow.kt` (deleted)

It has one caller (codegraph), and `ThreadComposerFooter` replaces it. `cd_thread_status_expand` is kept for the sheet opener.

## State + concurrency model

No new coroutines, flows or ViewModel state. Overlay state is screen-local UI state (`remember`), keyed on `conversationId`. Anchor bounds are snapshot state written from layout callbacks and read only by the open overlay. Selection goes through the existing handlers, and the VM owns pending, confirmation and revert.

## Error handling

No new failure modes. A refused write surfaces as it does today: the VM clears pending and `sessionSettingsErrors` shows the snackbar. The footer re-renders the saved label. A control with nothing to offer is disabled, and it does not open an empty overlay.

## Testing strategy

- **Unit (`app/src/test/.../thread/FooterMenuTest.kt`)**, `footerMenu` against constructed `ThreadRunConfig`s:
  - model: no menu → null; published-but-empty → null; options in daemon order with verbatim values; `notListed` is `droppedModels + hiddenChoices` even when `choices.size` would suggest otherwise.
  - effort: selected row with levels → options; selected row with none → null; no matching row → null; selectedValue follows the pending tap.
- **Compose (`app/src/androidTest/.../thread/ThreadComposerFooterTest.kt`)**, replacing `ThreadStatusRowTest`, hosted on `ThreadScreen` with a mutable state:
  - both buttons show the current labels; the status opener opens the Status sheet in one tap.
  - tapping model lists only the published model labels; choosing one calls `onModelSelected(value)` once and closes the overlay.
  - effort overlay lists only the selected row's levels; a row with no levels leaves the effort button disabled, and tapping it opens nothing.
  - tapping outside (on the input field) dismisses the overlay, selects nothing, and leaves the field unfocused.
  - pending shows the pending `stateDescription`; clearing pending returns the button to the saved label.
  - the overlay sits above the footer button (bounds), and a non-zero `droppedModels` shows the not-listed caption.
  - switching `conversationId` while open closes the overlay (AC#4).
- No rung-3 scenario in this change. AC#5 assigns live verification to #679.

## Documentation handoff

Pending for the documentation stage. The ticket names none. `docs/knowledge/features/thread-status-row.md` describes the retired `ThreadStatusRow` and needs a rewrite or replacement for the footer and overlay. `thread-screen-how-it-works-list-and-status-row.md` and `status-sheet.md` name the row as the sheet's opener.

## Open questions

- Whether `verticalScroll` inside an intrinsic-width column measures correctly for the overlay's width. Resolve in implementation; fall back to a fixed max width if not.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Daemon-authored text reaches the footer and overlay only as `ThreadModelChoice.label` / `ThreadEffortChoice.label` / `modelLabel` / `effortLabel`, all produced by `inert()` in the VM fold (`ModelMenuRow.toChoice`, `ThreadRunConfig.label`): ISO controls stripped, 128-char cap. The new code renders them only through `Text`, with `maxLines = 1` and ellipsis. No label reaches a WebView, attribute, URL, filename, cache key, log line, semantics key or `rememberSaveable`. `value` is never rendered; it is only passed back to the existing handler verbatim, as the sheet already does.
- [Trust boundaries] No findings on the subset signal. `notListed` is read from `droppedModels` and `hiddenChoices`, never recomputed from the row count, so a hostile menu cannot make a subset look complete.
- [Tokens / secrets] Not applicable. The change touches no credential, key or token path.
- [File / storage] Not applicable. Nothing is persisted, and the overlay state is deliberately non-saveable, so no daemon text reaches saved-instance state.
- [Android surface] No findings. No intents, deep links, providers or WebViews. The overlay draws in the screen's own window, not a separate `Popup` window. The scrim swallows the dismissing tap, so a stray tap cannot reach the composer.
- [Crypto / network] Not applicable. There is no wire change, and the selection uses the existing `setSessionSettings` path.
- [Logs] No findings. The new UI code logs nothing, so no label or value can reach Logcat.
- [Concurrency] No findings. No coroutines are launched. The `LaunchedEffect` that closes a stale overlay only writes screen-local state.
- [Hostile daemon frame] OUT OF SCOPE. `effortLevels` has no count cap in the VM fold, unlike the 32-row model cap. The overlay scrolls, but it composes every row, exactly as the Status sheet already does. A count cap belongs in `runConfig` / `toChoice` beside `MAX_RENDERED_MODEL_CHOICES`, which is outside this ticket's diff. It is noted in the PR for triage.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
