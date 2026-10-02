# #1499 — Offline · Retry pill hugs its text

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: the `showOffline` branch of `ThreadTopOverlay`, where the `offline_retry_target` box holds the `NoticePill`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` already hugs its label (8/4 padding) unless its caller stretches it.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlayTest.kt`: `offlineRetry_hasA48dpTarget_belowAUsagePill_withoutStealingDismissTaps`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910

The `Top overlay` (`627:5141`) is a right-aligned column at the top of the message area. Its only child is an Error `Pill` (`627:5143`): `errorContainer` fill, `error` text in `bodySmall`, 8/4 padding, 6dp radius, as wide as "Offline · Retry" plus padding and drawn at the right edge.

## Change

In the offline branch of `ThreadTopOverlay`, drop `Modifier.fillMaxWidth()` from the `NoticePill`. The 144 x 48 dp `offline_retry_target` box from #1283 stays as the invisible touch target and its `Alignment.TopEnd` places the now text-wide pill at its top-right corner, which is the overlay's right edge because the box sits in the `Alignment.End` column. Nothing else moves: the box's size, its clickable, and the 12dp gap below the usage pill are unchanged.

## Testing strategy

New Robolectric test in `ThreadTopOverlayTest` (`@GraphicsMode(NATIVE)` for real text measurement): offline, the drawn pill (content description "Offline · Retry") is narrower than `offline_retry_target`, its right edge matches the target's right edge, its top matches the target's top, and its text is not truncated (pill width ≥ text width plus 16dp padding).

The existing `offlineRetry_hasA48dpTarget_belowAUsagePill_withoutStealingDismissTaps` keeps every touch, gap and tap assertion. Its `retryPillBounds.width >= 100.dp` legibility floor was only true because the pill filled the 144dp box; if the hugged pill measures below it, that one line becomes the same untruncated-label check, which is what "legible" meant.
