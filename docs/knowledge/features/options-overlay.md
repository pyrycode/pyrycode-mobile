# Options overlay

Compact popup of option rows that opens above the control that anchors it by default, or below a header control (#1665). Landed in [#808](../codebase/808.md) as the selection surface for the [thread composer footer](thread-composer-footer.md)'s model and effort buttons, built to be reused by future footer controls. **[#650](https://github.com/pyrycode/pyrycode-mobile/issues/650) was the first of those** — the permission button's six-mode menu renders through this same component, unchanged. **[#884](https://github.com/pyrycode/pyrycode-mobile/issues/884) is the second** — the composer's Actions menu, and the first caller whose rows are not a mutually exclusive choice, so the component grew a disabled-row state and a button row mode; see [§ Row modes](#row-modes-radio-vs-button-884). **[#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) is the third** — the [slash-command type-ahead](slash-command-type-ahead.md), and the first caller whose rows carry a second line of text, so the component grew an optional `detail`; see [§ Optional detail line](#optional-detail-line-885).

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt`). Figma reference: [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958), the `Options overlay` frame nested under the thread frame's `Input area` (`533:1957`).

## What it does

Renders a small, rounded, vertically-scrolling column of option rows above or below an anchor rectangle, with a full-size transparent scrim behind it that dismisses on any outside tap. It draws as a plain layer in the caller's own window — **not** a `Popup`. A focusable `Popup` would steal focus from the composer's text field and drop the soft keyboard; a non-focusable one lets the dismissing tap fall through to whatever sits underneath it instead of being consumed by the scrim. Drawing the scrim as the topmost node in the caller's own `Box` keeps the tap from reaching the composer or anything else, without touching focus.

## Shape

```kotlin
enum class OptionsOverlayPlacement { Above, Below }

data class OptionsOverlayOption(val value: String, val label: String, val enabled: Boolean = true, val detail: String? = null)

@Composable
fun OptionsOverlay(
    options: List<OptionsOverlayOption>,
    selectedValue: String,
    notListed: Int,
    anchor: Rect,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: Boolean = false,
    placement: OptionsOverlayPlacement = OptionsOverlayPlacement.Above,
)
```

`OptionsOverlayOption.value` is handed back to `onSelect` verbatim and never rendered; `label` is the only text drawn on every pre-#885 caller. `enabled` (#884) defaults to `true`, so every pre-#884 caller is unaffected; a `false` row is greyed out and inert to both touch and TalkBack — see [§ Row modes](#row-modes-radio-vs-button-884). `detail` (#885) defaults to `null`, so every pre-#885 caller is unaffected; a non-null `detail` renders a bounded second line below the label — see [§ Optional detail line](#optional-detail-line-885). `anchor` is a `Rect` in the **caller's own layer coordinates**, not window coordinates — the caller (`ThreadScreen` or `ChannelListScreen`) converts a control's live window bounds by subtracting the layer's own window origin before passing it in (see [thread composer footer § Wiring in `ThreadScreen`](thread-composer-footer.md#wiring-in-threadscreen)). `notListed` adds a trailing, non-interactive caption row when greater than zero, so a cut list never reads as complete; it is a plain count the caller computes, never derived here from `options.size`. `actions` (#884), also defaulted `false`, switches every row from the radio behaviour below to a button — see [§ Row modes](#row-modes-radio-vs-button-884).

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

`AnchoredOptions` is a private single-child `Layout`. `placement` defaults to
`OptionsOverlayPlacement.Above`, preserving the composer and slash-command callers' existing geometry,
height limits and dismissal. Above measures only the room from the layer's 8dp top margin to
`anchor.top - 4dp`, placing the column's bottom there. `Below`, used by the
[channel-list header menu](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737), places the top at
`anchor.bottom + 4dp` and measures only the room from there to the layer's bottom minus 8dp.
Available height is bounded at zero; a longer column scrolls within that space.
Both placements align the column's left edge at `anchor.left - 12dp` (the row text inset), clamped to
8dp from either horizontal edge. Below also clamps its top to the layer's bounds and top margin.

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

Each row is `Modifier.selectable(selected, role = RadioButton, onClick = { onSelect(option.value) })` inside a `Modifier.selectableGroup()` — TalkBack announces the set as a mutually-exclusive group and each row as "Radio button, selected/not selected", matching the [Status sheet](status-sheet.md)'s own radio-group rows. The selected-row fill depends on the theme path below; every row's text is `colorScheme.primary`.

### Colour and surface

In the app's fixed dark theme, Figma `533:1958`'s `Schemes/On Primary` (`#003355`) fills the outer surface and shows through the selected row, while `Schemes/On Primary Fixed` (`#001D34`) fills unselected rows. Text uses `primary` (`#9DCBFC`). This relies on the static-dark color mappings in `Theme.kt`. The `Surface` has no shadow. Other theme paths retain `surfaceContainerLowest`, transparent idle rows and `primaryContainer` selection; those paths are outside the app's current dark-only product scope. Applying the fixed navy to light rows produced poor label and detail contrast during review, so the Figma roles are conditional on `LocalStaticDarkPalette`.

### Row geometry

Rows use the design's 12dp horizontal and 6dp vertical insets, with a 28dp minimum height. The minimum matters because the test device measures the `bodySmall` text line at 14dp; enlarged text can grow the row rather than clip it. The outer column has a 2dp vertical inset and 6dp radius; the anchor gap remains 4dp. Width and available-height limits keep the scrollable options reachable above the composer or below a header on compact screens.

### Row modes: radio vs. button (#884)

`actions = false` (the default) is the original radio group: rows sit in a `Modifier.selectableGroup()`, and each is `Modifier.selectable(selected, enabled = option.enabled, role = RadioButton, onClick)` with the theme-dependent selected fill above. `actions = true` — the [composer footer](thread-composer-footer.md)'s [Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884), the first caller with no selected value — drops `selectableGroup()` and draws each row `Modifier.clickable(enabled = option.enabled, role = Button, onClick)` instead: no row is ever highlighted, and TalkBack announces a button, not a member of a mutually-exclusive choice. `selected` is forced to `false` in this mode regardless of `selectedValue`, since an action menu has none to compare against.

A disabled `OptionsOverlayOption` (`enabled = false`, either mode) renders its label in `colorScheme.onSurface` at Material 3's `0.38` disabled alpha (`DISABLED_ALPHA`) instead of `colorScheme.primary`, and its `selectable` / `clickable` modifier carries the same `enabled = false`, so the row is inert to both touch and TalkBack. The row still renders — a disabled option is greyed out, never removed from the list, so its position among the other rows is stable.

### Optional detail line (#885)

`detail: String? = null` on `OptionsOverlayOption` is the [slash-command type-ahead](slash-command-type-ahead.md)'s first use of a second line of row text — the command description alongside `/name`. When `detail` is non-null the row becomes a `Column` holding the existing label (still one ellipsized line) above the detail, rendered `bodySmall` / `onSurfaceVariant` with `maxLines = 2` and an ellipsis; the `selectable` / `clickable` modifier moves from the row's outer container to that `Column`, so tap targets are unchanged. A `null` detail (every pre-#885 caller) renders exactly as before — no `Column`, no reserved space for a second line. The two-line cap bounds row height regardless of how long the source description is; `slash-command-type-ahead.md` covers the 240-character/control-character sanitization the caller applies before the string reaches this component.

## Trust boundary

Every `OptionsOverlayOption.label` may be daemon-authored (a model's `displayName`, an effort level string). The caller is responsible for making the text inert before it reaches this component — see [`ThreadRunConfig`'s `inert()` fold](thread-composer-footer.md#sourcing) — but this component adds its own floor: labels render through plain `Text` only, `maxLines = 1`, ellipsized, never interpolated into a content description, a semantics key, a log line, or `rememberSaveable`. `value` is passed back to `onSelect` verbatim and is never rendered at all, so a hostile `value` string (as opposed to `label`) cannot reach the screen through this component regardless. `detail` (#885) is held to the same floor: plain `Text` only, `maxLines = 2`, ellipsized, never used outside the row's own rendering — see [§ Optional detail line](#optional-detail-line-885).

**Permission mode's own menu ([#650](https://github.com/pyrycode/pyrycode-mobile/issues/650)) is the one caller whose labels are *not* daemon-authored.** `footerMenu(FooterControl.Permission, …)` builds every `OptionsOverlayOption` from the client-owned `PermissionModeOption` table — six fixed `(wire, label)` pairs — never from `SessionSettings.permissionMode` directly; an unrecognised reading is never offered as an option at all (see [thread-composer-footer.md § Sourcing — Permission mode](thread-composer-footer.md#permission-mode-650)). So this component's trust-boundary floor is defense in depth for that menu, not the only thing keeping a hostile daemon string off the overlay's option rows the way it is for Model and Effort.

**The Actions menu ([#884](https://github.com/pyrycode/pyrycode-mobile/issues/884)) is the same.** Every `OptionsOverlayOption` here comes from the client-owned `ComposerAction` table; nothing the workspace publishes is ever a `value` or a `label`. The workspace's published slash-command menu ([#882](https://github.com/pyrycode/pyrycode-mobile/issues/882)) is read only to decide `enabled` — `ThreadViewModel.absentComposerActions` compares its `name` / `aliases` / `truncatedFields` against the fixed command strings and returns a `Set<ComposerAction>`, and that verdict is all that reaches this component; since [#1111](https://github.com/pyrycode/pyrycode-mobile/issues/1111) a session's `SessionSettings.capabilities.slashCommands` flag can add to the same set, compared only as a boolean, never rendered. A hostile menu or daemon can therefore only grey out or un-grey a row; it cannot rename one, add one, or change what a row sends. See [thread composer footer § Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884).

**The [slash-command type-ahead](slash-command-type-ahead.md) ([#885](https://github.com/pyrycode/pyrycode-mobile/issues/885)) is the first caller whose `label` and `detail` genuinely are daemon-authored** — a command's `/name` + `argumentHint` and its `description`. Both pass through this component's floor unchanged; the caller additionally sanitizes before handing them over (`inert()` for the label, a control-character strip + 240-char cap for the detail — see `slash-command-type-ahead.md`'s Trust boundary section for the full pass), and `value` is the row's index, never `name`, so a hostile or duplicate command name cannot collide in `onSelect`'s dispatch.

## State + concurrency

No internal state beyond the `rememberScrollState()` the column's own scroll position needs and the `rememberUpdatedState(onDismiss)` wrapper. No coroutines are launched beyond what `detectTapGestures` and `verticalScroll` already run internally. The component reacts to `anchor`, `options` (including each option's own `enabled`), `selectedValue`, `notListed`, `actions` and `placement` changing on every recomposition — it holds no memory of a previous selection or a previous anchor.

## Testing

`OptionsOverlayColoursTest` also covers Below placement following a live anchor, both horizontal edge
clamps, short-space scrolling and action-row palettes in light and static dark, alongside the retained
default Above height and placement checks (#1665).

`OptionsOverlayColoursTest` draws the static-dark menu and samples selected and unselected row pixels, catching a role mapping that can look approximately right in a preview. `ThreadComposerFooterTest` (`app/src/sharedTest/.../thread/ThreadComposerFooterTest.kt`) hosts the overlay on `ThreadScreen`, where its anchor comes from a real footer button's live bounds. See [Thread composer footer — testing](thread-composer-footer-testing.md#testing) for the covered cases (row visibility and width, selection, outside-tap dismissal, the not-listed caption, the overlay's position relative to its anchor, and, since [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884), the Actions menu's button-mode rows and its disabled/greyed row). The `detail` line (#885) is covered by `SlashCommandTypeAheadScreenTest.kt`, also hosted on `ThreadScreen` — see [Slash-command type-ahead § Testing](slash-command-type-ahead.md#testing), including the bounded-height case for a 1,500-character description.

`OptionsOverlayCaptureTest` provides managed-device captures of Actions and slash menus at 412 × 892, plus slash suggestions at 280 × 400 with 1.6× text. [Visual evidence](../../../app/src/androidTest/assets/options-1257/comparison-412x892.png) pairs the 412 × 892 emulator views with the available Figma viewport and idle/selected option renders; [capture context](../../../app/src/androidTest/assets/options-1257/capture-context.txt) records the limits. The direct open-overlay render for `533:1958` returned 1 × 1 and the thread viewport has the menu closed, so a full open-menu pixel match remains unverified. The inspected nodes also have no disabled-option or slash-detail state; their existing semantic appearance and bounded detail are not claimed as exact Figma matches. On this managed device, `uiAutomation.takeScreenshot()` returned black frames; drawing the root view produced usable app captures.

## Previews

Two `@Preview`s, `OptionsOverlayDarkPreview` / `OptionsOverlayLightPreview`, both `widthDp = 200, heightDp = 260`, show five effort-style rows (`low`/`medium`/`high`/`xhigh`/`max`) with `high` selected, against a fixed `anchor = Rect(left = 40f, top = 600f, right = 80f, bottom = 640f)`. The dark preview uses the supported palette; neither preview replaces the managed-device captures for placement or visual comparison.

## Related

- [Channel-list screen](channel-list-screen.md) — client-owned Settings/Archive action rows, the first Below caller (#1665).
- [Thread composer footer](thread-composer-footer.md) — an Above caller; `footerMenu` builds the `List<OptionsOverlayOption>` this component renders (for Model, Effort, [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650)'s Permission, and [#884](https://github.com/pyrycode/pyrycode-mobile/issues/884)'s Actions), and `ThreadScreen` owns the anchor-tracking and layer-origin state this component depends on. See [§ Actions menu](thread-composer-footer.md#actions-menu-884) for the fourth caller's own table and absence proof.
- [Slash-command type-ahead](slash-command-type-ahead.md) — the fifth caller ([#885](https://github.com/pyrycode/pyrycode-mobile/issues/885)), and the first to use `detail`; anchors on [Thread input bar](thread-input-bar.md)'s live bounds via `ThreadScreen`'s `inputAnchor`, gated closed whenever a footer menu is open so the two overlays never stack.
- [Status sheet](status-sheet.md) — the retained, non-overlay selection surface for the model/effort choices, reachable from the footer's trailing icon. The two surfaces read the same `ThreadRunConfig` and so cannot disagree. Its former YOLO toggle was retired outright by [#650](https://github.com/pyrycode/pyrycode-mobile/issues/650), not moved to this component — the permission menu is a new caller, not a relocation.
- [Thread overflow menu](thread-overflow-menu.md) — hosts the Reset session item the Actions menu's own Reset row dispatches through; this component never renders that dispatch, since Reset session carries no command.
- Specs: `docs/specs/architecture/808-composer-footer-model-effort-buttons.md`, `docs/specs/architecture/650-composer-permission-mode.md`, `docs/specs/architecture/884-composer-actions-control.md`.
- Figma: [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958).
