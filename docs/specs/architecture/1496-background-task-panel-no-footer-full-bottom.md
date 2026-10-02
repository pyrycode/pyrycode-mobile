# #1496 — Background-task panel: no footer Close, sheet to the bottom edge

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` — `MobileReadOnlyModal` (only caller is the panel) and `MobileModalShell`, whose `Surface` takes `windowInsetsPadding(WindowInsets.safeDrawing)` and whose last row is the footer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` — `BackgroundTaskPanel`, the `MobileReadOnlyModal` call passing `closeLabel`.
- `app/src/main/res/values/strings.xml` — `background_tasks_close`, used only by the panel and tests.
- Tests that close through the footer: `BackgroundTaskPanelTest.closing_sendsNothing`, `TaskCountPillTest` (the pill test's `background_tasks_close` click), `BackgroundTaskPanelLayoutTest.assertReachable`, `BackgroundTaskPanelCaptureTest.compactLargeTextKeepsScrolledContentAndBothCloseRoutesReachable`, and `InteractiveStreamE2ETest`'s `inBackgroundPanel` and `closeBackgroundTasks`.
- Pattern for a real inset in a Robolectric screen test: `PairCodeScreenInsetsTest.applyBars`.

No in-flight feature branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-876

Frames Populated `568:877`, Capped `568:932`, Empty `568:981`, Never reported `568:997`: the `Modal` frame fills the whole 412×892 screen (`modalContainer` fill, 44 dp corners that coincide with the device corners), header at 28/24, content `Content (scrolls)` from y 85 to 868, so 24 dp below it and no footer row. The header close glyph is the only visible close control. The frames draw no status bar, so the top safe-drawing inset stays; only the bottom changes.

## Change

`MobileModalShell` gains two switches used only by `MobileReadOnlyModal`: `footer` becomes nullable (null draws no footer row), and `extendToBottom: Boolean = false`. When `extendToBottom` is set, the `Surface` pads by `safeDrawing` on top and horizontal sides only and drops its own `imePadding`, so it reaches the screen's bottom edge; the inner shell column instead pads by `safeDrawing.only(Bottom)` inside the sheet, before its 24 dp bottom padding, so the scrolling content viewport ends above the navigation bar (and the keyboard, though the panel hosts no field). `MobileReadOnlyModal` drops its `closeLabel` parameter and passes `footer = null`, `extendToBottom = true`, `bottomPadding = 24.dp`. `BackgroundTaskPanel` stops passing the label and `background_tasks_close` is deleted. Back still dismisses (`dismissOnBackPress = !gate`), outside taps still do not (`dismissOnClickOutside = false`), and dismissal still only calls `onDismiss`. `MobileModal`, `MobileGateModal` and `MobileDismissModal` keep the defaults, so their rendering is unchanged.

## Testing strategy

- New `BackgroundTaskPanelInsetsTest` (sharedTest, `@Config(qualifiers = "w412dp-h892dp")`): dispatches a 40 dp status and 48 dp navigation inset to the dialog's own view (captured through the panel's `modifier`, which runs inside the dialog), asserts Compose read a non-zero navigation inset, the pane's bottom equals the dialog root's bottom, and the last task card of a long roster scrolled into view, the partial notice, and the empty and never-reported support text each end at or above root bottom minus the navigation inset. Also asserts no clickable "Close" text exists in any of the four readings.
- `BackgroundTaskPanelTest.closing_sendsNothing`: closes via the header glyph and via `Espresso.pressBack()`, nothing sent.
- `TaskCountPillTest`, `BackgroundTaskPanelLayoutTest.assertReachable` and `BackgroundTaskPanelCaptureTest` close via the header glyph / Back instead of the footer.
- Live helpers: `inBackgroundPanel` anchors on the dialog holding the panel title (`hasAnyAncestor(isDialog())` and a descendant with the title text); `closeBackgroundTasks` clicks the `Close` content description inside the dialog. Both live methods are listed under `## Live tests` for the gate; the builder does not run the live suite.
