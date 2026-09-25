# #1043 — Task count pill in the thread status band

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadStatusArea` is the band's single `when` and gets the pill. `ThreadScreen` owns `backgroundTasksOpen`, the flag the Actions row sets to open `BackgroundTaskPanel`, and passes `state.backgroundTaskCount` into it. `ComposerGutter` and `ComposerStatusGutter` set the band's insets.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` is Figma `347:6617`, the component this pill instantiates. Its shadow is the Top overlay's drop shadow, and the in-band pill (`568:3162`) has none.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: `ThreadTopOverlay` is the other `NoticePill` caller and keeps its shadow through the default.
- `app/src/main/res/values/strings.xml`: the existing `plurals` entries (`thread_attachments_too_large`) are the shape for the new one.
- `app/src/sharedTest/.../thread/BackgroundTaskPanelTest.kt` and `ThreadFrameTest`: these are the patterns for hosting `ThreadScreen` and for asserting bounds with `getUnclippedBoundsInRoot`.

In-flight overlap: `feature/1021`, `feature/1041` and `feature/878` also add entries to `strings.xml`. These are additive entries, so I will build through the overlap.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-3139 (pill component: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6617)

The Status area (`111:3525`) is a 372dp row that is 24dp tall. The reading ("Thinking…") sits at its left end. The Task count pill (`568:3162`) is right-aligned so it ends on the 20dp content gutter. The pill is the Default `Pill`: `primaryContainer` fill, `onPrimaryContainer` bodySmall text, 6dp radius and 8/4 padding. It has no X and, unlike the Top overlay pills, no drop shadow.

## Change

`ThreadStatusArea` gains `taskCount: Int` and `onTasksClick: () -> Unit`. The existing `when` moves unchanged into a private `StatusReading(..., modifier)`, keeping its order and its arms. When `taskCount <= 0`, the band calls `StatusReading` with today's `slot` modifier. It emits exactly what it emits today, so a band with no live reading still contributes no node and the composer's gap still collapses. When `taskCount > 0`, the band is a `Row` that fills the width, with start padding `ComposerStatusGutter` and end padding `ComposerGutter`, `Arrangement.End` and centred vertically. The `Row` holds `StatusReading(modifier = Modifier.weight(1f))` and then the pill. When the reading emits nothing, the weight goes with it, and `Arrangement.End` keeps the pill alone at the right end. The pill is `NoticePill(text = pluralStringResource(R.plurals.thread_task_count, n, n), isError = false, onClick = onTasksClick, shadowElevation = 0.dp)`. `NoticePill` gains a `shadowElevation: Dp = PillShadow` parameter, so the overlay's pills are unchanged. `ThreadScreen` passes `state.backgroundTaskCount` and `{ backgroundTasksOpen = true }`, which is the same flag the Actions row sets. No ViewModel, state or store changes. The copy is a client-owned plural resource. `one` reads "%1$d task running" and `other` reads "%1$d tasks running".

## Testing strategy

A new Robolectric screen test, `TaskCountPillTest`, goes under `app/src/sharedTest/.../thread/` and hosts `ThreadScreen`:
- Count 2 while thinking: the pill reads "2 tasks running", the thinking reading still shows, and the pill sits to the right of the reading.
- Count 3 with no reading: the pill shows at the band's right end. Its right edge is the root width minus the 20dp gutter.
- Count 1 reads "1 task running".
- Count 0 shows no pill text. The input field's top is measured at count 0, then at count 2 (the band rises), then back at 0, where it must match the first measurement. This proves the band collapses exactly as it does with no pill.
- Tapping the pill opens `BackgroundTaskPanel`, which shows its title and the roster's task.

Existing `ThreadTopOverlayTest` covers the overlay pills, which keep the default shadow.

## Documentation handoff

The ticket names none.

## Revisions

**During the build: the touch target and the collapse measurement.** The pill is a clickable `Surface`, so M3 laid it out at its 48dp minimum interactive size, and the band grew to 48dp instead of Figma's 24dp. `ThreadStatusArea` now provides `LocalMinimumInteractiveComponentSize = Dp.Unspecified` around the pill. Layout stays at 24dp, and Compose's hit test still extends the pill's touch bounds to the 48dp minimum. The zero-count test measures the newest message's bottom rather than the input field's top, because the bottom bar is anchored at the bottom and only its top edge moves. It asserts the exact 32dp rise (the 24dp pill plus the 8dp gap) and the exact return. The test class runs under `@GraphicsMode(NATIVE)`, because in legacy mode the label measures almost no width and the pill wraps.
