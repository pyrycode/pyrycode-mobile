# #1669 — 24 dp single-colour notification small icon

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt` — the `setSmallIcon` call in `AttentionNotifier.post`'s builder chain.
- `app/src/main/res/drawable/ic_pyry_logo.xml` — the 92 x 104 dp source mark; its single `pathData` is copied, not referenced.
- `app/src/main/res/drawable/ic_splash_logo.xml`, `ic_launcher_foreground.xml` — both draw the mark flipped vertically relative to `ic_pyry_logo`'s raw path, as Figma draws it.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt` — the Robolectric notifier tests and their `posted()` helper.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=701-5005

The launch splash icon spec: the pyrycode snowflake mark (frame 701:5007, 92 x 104) centred on a 288 dp canvas. The notification icon reuses that mark in Figma's orientation, white on transparent, since Android tints a small icon from its alpha channel alone. There is no separate Figma frame for the 24 dp status bar size.

## Change

Add `res/drawable/ic_notification.xml`: a 24 x 24 dp vector, viewport 24 x 24, with one group that scales `ic_pyry_logo`'s path by 20 / 103.812 so the mark is 20 dp tall inside the standard 2 dp padding, flips it vertically (`scaleY` negative, `translateY = 22`) to match Figma, the splash and the launcher, and centres it horizontally (`translateX = (24 - 91.002 * scale) / 2`). Its one path is filled `#FFFFFF`. `AttentionNotifier.post` switches `setSmallIcon` from `ic_pyry_logo` to `ic_notification`. The welcome screen, splash and launcher keep their drawables, so nothing else moves.

## Testing strategy

In `AttentionNotifierTest`, a new test posts `TURN` and asserts the posted notification's `smallIcon.resId` is `R.drawable.ic_notification`, that the drawable's intrinsic size is 24 x 24 dp, and that every colour attribute in its XML is opaque white. It fails against the current `ic_pyry_logo` call. On-device confirmation on the OnePlus CPH2415 follows with the next Play build, as the ticket says; the emulator cannot reproduce OxygenOS's icon-area filter.
