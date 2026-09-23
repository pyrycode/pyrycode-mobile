# Options overlay

Compact popup of option rows that opens above the control that anchors it. Landed in [#808](../codebase/808.md) as the selection surface for the [thread composer footer](thread-composer-footer.md)'s model and effort buttons; built to be reused by future footer controls (permission mode, #650; Actions, #655).

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt`). Figma reference: [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958), the `Options overlay` frame nested under the thread frame's `Input area` (`533:1957`).

## What it does

Renders a small, rounded, vertically-scrolling column of selectable rows above an anchor rectangle, with a full-size transparent scrim behind it that dismisses on any outside tap. It draws as a plain layer in the caller's own window — **not** a `Popup`. A focusable `Popup` would steal focus from the composer's text field and drop the soft keyboard; a non-focusable one lets the dismissing tap fall through to whatever sits underneath it instead of being consumed by the scrim. Drawing the scrim as the topmost node in the caller's own `Box` keeps the tap from reaching the composer or anything else, without touching focus.

## Shape

```kotlin
data class OptionsOverlayOption(val value: String, val label: String)

@Composable
fun OptionsOverlay(
    options: List<OptionsOverlayOption>,
    selectedValue: String,
    notListed: Int,
    anchor: Rect,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`OptionsOverlayOption.value` is handed back to `onSelect` verbatim and never rendered; `label` is the only text drawn. `anchor` is a `Rect` in the **caller's own layer coordinates**, not window coordinates — the caller (`ThreadScreen`) converts a control's live window bounds by subtracting the layer's own window origin before passing it in (see [thread composer footer § Wiring in `ThreadScreen`](thread-composer-footer.md#wiring-in-threadscreen)). `notListed` adds a trailing, non-interactive caption row when greater than zero, so a cut list never reads as complete; it is a plain count the caller computes, never derived here from `options.size`.

## How it works

### Scrim: a `pointerInput` tap detector under a `semantics` block, not a `clickable` modifier

```kotlin
Box(
    modifier = Modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures(onTap = { currentOnDismiss() }) }
        .semantics {
            contentDescription = dismissDescription
            onClick { currentOnDismiss(); true }
        },
)
```

`detectTapGestures` (not `clickable`) avoids drawing a ripple across the whole screen on every dismiss tap. The `semantics` block adds an explicit `onClick` action and a content description (`cd_options_overlay_dismiss`) so a TalkBack user has a way to dismiss the overlay that doesn't depend on finding empty space to tap. `onDismiss` is captured through `rememberUpdatedState` so the `pointerInput(Unit)` block (keyed once, for gesture-detector stability) always calls the current lambda, not the one captured at first composition. `BackHandler(onBack = { currentOnDismiss() })` gives the system back gesture/button the same effect.

### Placement: a custom `Layout`, not `Popup`'s `PopupPositionProvider`

`AnchoredAbove` is a private single-child `Layout` that measures its content against `constraints.maxHeight` minus the space between the layer's top edge and the anchor (so the column can never grow taller than the room above the anchor, and doesn't need to know about the software keyboard specifically — it only needs the anchor's position, which already reflects the keyboard's lift). It places the child so:

- the child's **bottom** sits `OverlayAnchorGap` (4dp) above `anchor.top`;
- the child's **start** aligns with the anchor's own left edge, offset back by the row's own horizontal inset (`OptionHorizontalPadding`, 12dp) so the row *text* lines up with the anchor's text, not the row's padded box;
- both axes clamp inside `OverlayEdgeMargin` (8dp) from the layer's edges.

Because `anchor` is read from the anchoring control's live `boundsInWindow()` every frame (see the footer doc), the overlay re-places itself as the IME lifts or the anchor otherwise moves — there is no separate keyboard-visibility branch in this file.

### The column: `IntrinsicSize.Max` before `verticalScroll`

```kotlin
Column(
    modifier = Modifier
        .width(IntrinsicSize.Max)
        .widthIn(min = OverlayMinWidth, max = OverlayMaxWidth)  // 80dp .. 240dp
        .verticalScroll(rememberScrollState())
        .padding(vertical = OverlayVerticalPadding)
        .selectableGroup(),
)
```

`verticalScroll` alone would let the column's width collapse to whatever the *first visible* row measures, since a scrolling container doesn't force its children to agree on a width the way a non-scrolling `Column` does. `Modifier.width(IntrinsicSize.Max)` forces the column to measure every child's intrinsic width and takes the largest, so every row (including ones scrolled out of view) renders at one consistent width. This was the plan's one open question, resolved during implementation (see [#808](../codebase/808.md)'s spec Revisions) rather than falling back to a fixed width.

Each row is `Modifier.selectable(selected, role = RadioButton, onClick = { onSelect(option.value) })` inside a `Modifier.selectableGroup()` — TalkBack announces the set as a mutually-exclusive group and each row as "Radio button, selected/not selected", matching the [Status sheet](status-sheet.md)'s own radio-group rows. The selected row's background is `colorScheme.primaryContainer`; every row's text is `colorScheme.primary`.

### Colour deviation from the design

Figma `533:1958` fills unselected rows with `Schemes/On Primary Fixed` and the selected row with `Schemes/On Primary`. `Theme.kt` defines no `*Fixed` roles (confirmed absent at audit baseline `1028cd6`), so this component maps to roles the theme actually defines: the surface is `colorScheme.surfaceContainerLowest` (dark in the dark theme, close to the design's unselected dark fill; white in light), the selected row is `primaryContainer`, and a `shadowElevation = 3.dp` on the surrounding `Surface` separates the popup from the light-theme background beneath it. The design's "selected row is visually lighter than its neighbours" reading survives in both themes.

### Row height deviation

Rows use `OptionVerticalPadding = 10.dp` rather than the design's 6dp, making each row 36dp tall instead of 28dp — the same thumb-target reasoning as the [footer buttons'](thread-composer-footer.md) `heightIn(min = 32.dp)`.

## Trust boundary

Every `OptionsOverlayOption.label` may be daemon-authored (a model's `displayName`, an effort level string). The caller is responsible for making the text inert before it reaches this component — see [`ThreadRunConfig`'s `inert()` fold](thread-composer-footer.md#sourcing) — but this component adds its own floor: labels render through plain `Text` only, `maxLines = 1`, ellipsized, never interpolated into a content description, a semantics key, a log line, or `rememberSaveable`. `value` is passed back to `onSelect` verbatim and is never rendered at all, so a hostile `value` string (as opposed to `label`) cannot reach the screen through this component regardless.

## State + concurrency

No internal state beyond the `rememberScrollState()` the column's own scroll position needs and the `rememberUpdatedState(onDismiss)` wrapper. No coroutines are launched beyond what `detectTapGestures` and `verticalScroll` already run internally. The component reacts to `anchor`, `options`, `selectedValue` and `notListed` changing on every recomposition — it holds no memory of a previous selection or a previous anchor.

## Testing

Covered indirectly through `ThreadComposerFooterTest` (`app/src/androidTest/.../thread/ThreadComposerFooterTest.kt`), hosted on `ThreadScreen` rather than in isolation, since the overlay's anchor comes from a real footer button's live bounds. See [thread composer footer § Testing](thread-composer-footer.md#testing) for the covered cases (row visibility and width, selection, outside-tap dismissal, the not-listed caption, and the overlay's position relative to its anchor).

## Previews

Two `@Preview`s, `OptionsOverlayDarkPreview` / `OptionsOverlayLightPreview`, both `widthDp = 200, heightDp = 260`, showing five effort-style rows (`low`/`medium`/`high`/`xhigh`/`max`) with `high` selected, against a fixed `anchor = Rect(left = 40f, top = 600f, right = 80f, bottom = 640f)`.

## Related

- [Thread composer footer](thread-composer-footer.md) — the current caller; `footerMenu` builds the `List<OptionsOverlayOption>` this component renders, and `ThreadScreen` owns the anchor-tracking and layer-origin state this component depends on.
- [Status sheet](status-sheet.md) — the retained, non-overlay selection surface for the same model/effort choices (plus YOLO), reachable from the footer's trailing icon. The two surfaces read the same `ThreadRunConfig` and so cannot disagree.
- Spec: `docs/specs/architecture/808-composer-footer-model-effort-buttons.md`.
- Figma: [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958).
