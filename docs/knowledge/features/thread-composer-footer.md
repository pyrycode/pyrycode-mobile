# Thread composer footer

Always-visible row at the bottom of [`ThreadScreen`](thread-screen.md), stacked below the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. **[#808](../codebase/808.md) replaced [the retired `ThreadStatusRow`](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145)** — one monospace `model · effort` string with a single expand affordance — with two independent buttons, one for model and one for effort, each opening the design's [Options overlay](options-overlay.md) directly above it. A trailing icon keeps the [`StatusSheet`](status-sheet.md) one tap away, since it remains the only home of the YOLO toggle.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`). Figma reference: the `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494) inside `Input area` [`533:1957`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), parent [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Renders one small button per selectable control — today `Model` and `Effort` — each showing that control's current value with a trailing up-chevron, followed by a `Tune` icon that opens the Status sheet. Tapping a button opens an [`OptionsOverlay`](options-overlay.md) listing only that control's own published choices; the chevron disappears from a button that has nothing to offer, since there is no menu to promise. Choosing a row forwards the value to the thread's existing `onModelSelected` / `onEffortSelected` handlers and closes the overlay; tapping outside the overlay dismisses it without selecting anything.

## Sourcing

The footer's two values come from `ThreadUiState.runConfig: ThreadRunConfig` (`ThreadViewModel.kt`) — the same daemon-sourced state the [`StatusSheet`](status-sheet.md) reads, so the two surfaces agree by construction. `ThreadRunConfig` folds `ConversationRepository.observeSessionSettings(conversationId)` (the saved `model` / `effort` plus the `sessionId` a write must address) and `observeModelMenu(conversationId)` (the models this conversation's daemon published, each with its own `effortLevels`) together with two independent pending-write flags, `pendingModel: String?` and `pendingEffort: String?` (`null` when no write is outstanding for that control).

Two computed properties resolve what each button shows:

- **`modelLabel`** — `"unknown"` when no settings reading is available yet; `"default"` when the reading is available but the saved value is `""` (the daemon's own "no override, inherited default"); otherwise the matching published row's `displayName`, made inert, or — when the menu names no matching row — the saved value itself, made inert.
- **`effortLabel`** — the same three-state rule for `savedEffort` / `pendingEffort`, with no menu lookup: an effort level is its own label.

Both read `pendingModel` / `pendingEffort` first and the saved reading second (`selectedModel = pendingModel ?: savedModel`, and the equivalent for effort) — a tap that has been sent but not yet confirmed by a fresh settings reading renders immediately, and the acknowledgement of the write does **not** by itself clear the pending flag; only an arriving `observeSessionSettings` emission does. A refused write needs no footer-side handling: the ViewModel clears the relevant `pending*` flow, and the label falls back to the saved reading on the next recomposition.

Every daemon-authored string reaching `ThreadRunConfig` (`displayName`, the saved-value fallback) is passed through a file-private `String.inert()` in `ThreadViewModel.kt` before it lands — dropping `Char.isISOControl()` characters and bounding length — because `ModelMenuRow` / `SessionSettings` text crosses the subprocess trust boundary unsanitized. The write argument (`ThreadModelChoice.value` / `ThreadEffortChoice.value`) skips that treatment and stays byte-identical, since it is sent back, never rendered. See [Options overlay § Trust boundary](options-overlay.md#trust-boundary) for the overlay's own floor on the same data.

## Shape

```kotlin
enum class FooterControl { Model, Effort }

data class FooterMenu(
    val options: List<OptionsOverlayOption>,
    val selectedValue: String,
    val notListed: Int,
)

internal fun footerMenu(control: FooterControl, runConfig: ThreadRunConfig): FooterMenu?

internal fun footerControlEnabled(control: FooterControl, runConfig: ThreadRunConfig): Boolean

@Composable
fun ThreadComposerFooter(
    runConfig: ThreadRunConfig,
    onOpen: (FooterControl) -> Unit,
    onStatusClick: () -> Unit,
    onAnchorChanged: (FooterControl, Rect) -> Unit,
    modifier: Modifier = Modifier,
)
```

`FooterControl` is the extension point for the two controls this ticket left out: the permission-mode control (#650) and the Actions control (#655) each add an entry here and a branch in `footerMenu`; nothing else about the shape needs to change for them.

`footerMenu(control, runConfig)` is a pure function returning `null` when the control has nothing to offer:

- **Model** — `null` unless `runConfig.menuAvailable && runConfig.choices.isNotEmpty()`. Options are `choices` mapped to `(value, label)` in daemon order; `selectedValue = runConfig.selectedModel`; `notListed = runConfig.droppedModels + runConfig.hiddenChoices` — the producer's own cut (`droppedModels`) plus this client's render cap (`hiddenChoices`, from `MAX_RENDERED_MODEL_CHOICES = 32`), summed for display exactly as the [`StatusSheet`](status-sheet.md) sums them. Neither figure is ever recomputed from `choices.size`.
- **Effort** — `null` when `runConfig.effortChoices` is empty (no row is selected, or the selected row publishes no levels). `selectedValue = runConfig.selectedEffort`; `notListed = 0` always, since effort levels have no producer-side cut or client-side cap.

`footerControlEnabled(control, runConfig)` is `runConfig.writable && !runConfig.pending && footerMenu(control, runConfig) != null` — the [`StatusSheet`](status-sheet.md)'s own `enabled && !pending` rule, plus the one rule specific to this surface: a control with nothing to offer does not open an empty overlay. Note that `runConfig.pending` covers **either** control having an outstanding write (`pendingModel != null || pendingEffort != null`), so a write in flight on one control disables both buttons, matching the sheet's own single `pending` gate. `footerMenu` and `footerControlEnabled` are the one place this rule is written; `ThreadScreen`'s stale-overlay close (below) reads the same function so the two surfaces cannot disagree.

## How it works

### `FooterButton` — one per control, plus a trailing icon for the sheet

Each button is a `Row` at least 32dp tall (Figma's own buttons are 16dp; this grows the touch target for a thumb) showing the resolved label (`bodySmall`, one line, ellipsized, width-capped at 140dp) followed by a 14dp up-chevron — the chevron is omitted when the button is neither enabled nor pending, since a disabled button with no pending write has nothing to promise. The button reports its own window bounds via `Modifier.onGloballyPositioned { onBounds(it.boundsInWindow()) }`; `ThreadScreen` uses this to place the overlay (below). A pending button (`pendingModel != null` for the model button, `pendingEffort != null` for the effort button — read independently, not through `runConfig.pending`) renders at `0.55f` alpha and carries a `stateDescription` of `thread_footer_pending` ("Applying"), so a screen reader distinguishes "applying" from a plain disabled control. The trailing `Tune` icon is a 32dp tap target around a 16dp glyph with the existing `cd_thread_status_expand` content description — the design has no footer affordance for the Status sheet, but AC#1 requires the sheet to stay one tap away since it is the only home of the YOLO toggle.

### Wiring in `ThreadScreen`

```kotlin
var openControl by remember(state.conversationId) { mutableStateOf<FooterControl?>(null) }
val footerAnchors = remember { mutableStateMapOf<FooterControl, Rect>() }
var layerOrigin by remember { mutableStateOf(Offset.Zero) }
val openMenu =
    openControl
        ?.takeIf { footerControlEnabled(it, state.runConfig) }
        ?.let { control -> footerMenu(control, state.runConfig)?.let { control to it } }
LaunchedEffect(openControl, openMenu == null) {
    if (openMenu == null) openControl = null
}
Box(modifier = modifier.onGloballyPositioned { layerOrigin = it.positionInWindow() }) {
    Scaffold(modifier = Modifier.fillMaxSize()) { /* … topBar, bottomBar, body … */ }
    openMenu?.let { (control, menu) ->
        footerAnchors[control]?.let { anchor ->
            OptionsOverlay(
                options = menu.options,
                selectedValue = menu.selectedValue,
                notListed = menu.notListed,
                anchor = anchor.translate(-layerOrigin),
                onSelect = { value ->
                    when (control) {
                        FooterControl.Model -> onModelSelected(value)
                        FooterControl.Effort -> onEffortSelected(value)
                    }
                    openControl = null
                },
                onDismiss = { openControl = null },
            )
        }
    }
}
```

`Scaffold` moved inside a `Box` that records its own window origin (`onGloballyPositioned { layerOrigin = it.positionInWindow() }`), because [`OptionsOverlay`](options-overlay.md) draws in the screen's own window rather than a separate `Popup` window and needs its anchor in the `Box`'s own coordinates, not the device window's. `footerAnchors` is fed by the footer's `onAnchorChanged` callback and holds each control's **live window bounds**; the overlay call translates a control's window-space `Rect` into the `Box`'s layer-space `Rect` by subtracting `layerOrigin`. Because both `footerAnchors[control]` and `layerOrigin` are read every frame from live layout callbacks, the overlay follows the composer as it lifts with `imePadding()` — there is no separate "is the keyboard open" branch.

`openControl` is a **plain `remember`, deliberately not `rememberSaveable`**, keyed on `state.conversationId`. Two things follow from that: switching conversations within one composition drops any open overlay (the `remember` key changes), and a process death / config change never restores an overlay a fresh screen instance never opened. `openMenu` re-derives `footerMenu(...)` on every composition from the **current** `state.runConfig`, gated by `footerControlEnabled`, rather than caching the menu captured at open time — so if a write goes pending, a fresh reading drops the selected model's effort levels, or the session's `sessionId` empties out while the overlay is showing, `openMenu` becomes `null` and the `LaunchedEffect(openControl, openMenu == null)` clears `openControl` on the next frame, closing the overlay without an explicit "is my menu still valid" check anywhere else.

`onSelect` dispatches straight through the existing `onModelSelected: (String) -> Unit` / `onEffortSelected: (String) -> Unit` parameters — unchanged since [#807](../codebase/807.md) — then closes the overlay; `onDismiss` only closes it. Neither adds a new write path: both selection handlers still round-trip through `ConversationRepository.setSessionSettings`, addressed to `SessionSettings.sessionId`, exactly as the sheet's own rows do.

## State + concurrency model

No new coroutines, flows or ViewModel state. `openControl`, `footerAnchors` and `layerOrigin` are all screen-local Compose `remember` state, written from layout callbacks (`onGloballyPositioned`) and read only by the currently-open overlay. Selection goes through the existing handlers; the ViewModel owns pending, confirmation and revert exactly as it did for the retired row and the sheet.

## Error handling

No new failure modes. A refused write surfaces as it always has: the ViewModel clears the relevant `pending*` flow and `sessionSettingsErrors` drives the existing snackbar; the footer re-renders the saved label with no footer-side branch for the failure. A control with nothing to offer is disabled and does not open an empty overlay — see `footerControlEnabled` above.

## Testing

- **Unit** (`app/src/test/.../thread/FooterMenuTest.kt`) exercises `footerMenu` against constructed `ThreadRunConfig`s: no menu / an empty published menu / a selected row with no effort levels / no matching row all resolve to `null`; a real menu resolves to options in daemon order with verbatim `value`s; `notListed` is asserted to be `droppedModels + hiddenChoices` even in cases where `choices.size` would suggest a different number, guarding the "never recomputed from the row count" rule.
- **Compose** (`app/src/androidTest/.../thread/ThreadComposerFooterTest.kt`, replacing the deleted `ThreadStatusRowTest`), hosted on `ThreadScreen` with mutable state: both buttons show the current labels and the trailing icon opens the Status sheet in one tap; tapping the model button lists only the published model labels and choosing one calls `onModelSelected` exactly once and closes the overlay; the effort overlay lists only the selected row's levels, and a row with no levels leaves the effort button disabled with nothing opening on tap; tapping the input field while an overlay is open dismisses it, selects nothing, and leaves the field unfocused; the pending state renders its `stateDescription` and clears back to the saved label once a confirming reading lands; every option row is asserted `isDisplayed()` (pinning the `IntrinsicSize.Max` width fix — see [Options overlay](options-overlay.md#the-column-intrinsicsizemax-before-verticalscroll)); a non-zero `droppedModels` renders the not-listed caption; and switching `conversationId` while an overlay is open closes it.
- No rung-3 real-daemon scenario landed in this ticket; live behaviour is covered by #679.

## Previews

Two `@Preview`s in `ThreadComposerFooter.kt` — `ThreadComposerFooterDarkPreview` (both buttons resolved, dark theme) and `ThreadComposerFooterLightPendingPreview` (the effort button pending, light theme) — both `widthDp = 372`.

## Edge cases / limitations

- **The design has no Status-sheet affordance in the footer**, but the sheet is the only home of the YOLO toggle (AC#1), so a trailing `Tune` icon carries the existing `cd_thread_status_expand` description. The design's `Actions` (#655), permission-mode (#650), `Cxt:` (#591) and attachment segments are all out of scope for this file.
- **`effortLevels` has no count cap**, unlike the model menu's `MAX_RENDERED_MODEL_CHOICES = 32` / `hiddenChoices`. [Options overlay](options-overlay.md) scrolls, so an unbounded effort menu degrades to a tall scrolling list rather than a layout break, but it composes every row. Flagged for triage on the #808 PR as an out-of-scope hostile-daemon-frame finding; a cap would belong beside `MAX_RENDERED_MODEL_CHOICES` in `ThreadViewModel.kt`.
- **No device test exercises the software keyboard actually showing.** The overlay's placement depends on the anchoring button's live bounds inside the `imePadding()` column reflecting the IME lift; this is exercised only through the anchor-tracking mechanism's own correctness, not an emulator run with the keyboard open. Per AC#5, #679 covers live behaviour.

## Related

- [Options overlay](options-overlay.md) — the popup this footer opens; owns placement, the scrim, and the row rendering.
- [Status sheet](status-sheet.md) — the retained expanded surface, reachable from the trailing icon, that also hosts the YOLO toggle. Reads the same `ThreadRunConfig`.
- [Thread screen — the list, chip, empty state and status row](thread-screen-how-it-works-list-and-status-row.md#status-row-wiring-post-145) — the historical `bottomBar` wiring narrative through [#145](../codebase/145.md)–[#807](../codebase/807.md), before this ticket's replacement.
- [Thread input bar](thread-input-bar.md) — the composer this footer stacks below, inside the same `bottomBar` column.
- Ticket: [#808](../codebase/808.md). Spec: `docs/specs/architecture/808-composer-footer-model-effort-buttons.md`.
- Figma: `Input footer` [`110:3494`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494), overlay [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958).
