# Scanner Denied screen

Camera-permission-denied surface inside the [Scanner screen](scanner-screen.md).
It follows [Figma node 32:2](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2)
at 412×892 dp. The header, Back callback and exported illustration were restored
in #1151; the denied state uses the existing scanner route.

## What it does

- Shows “Pair with pyrycode” and a Back arrow announced as “Back”, with an explicit
  48×48 dp target that returns to the invoking screen without saving a pairing.
- Shows the reference camera illustration, “Camera permission required” heading
  and centered explanation of camera access and the manual pairing alternative.
- Offers “Open settings” for this app's Android settings and “Paste code instead”
  for the existing full-page [pair-with-code form](paste-code-dialog.md).
  Cancelling the untouched form returns to the denied screen without saving.

## How it works

`ScannerDeniedScreen` is stateless. The required callbacks are forwarded by
`ScannerScreen`'s `Denied` branch:

```kotlin
@Composable
fun ScannerDeniedScreen(
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
)
```

The root `Surface` receives `modifier`. Its column draws the shared
`scannerAtmosphere` blue radial center from dark theme roles before it applies
`systemBarsPadding()`
and 4 dp top padding, then a 64 dp header row with 4 dp start padding. The 48 dp
Back button is vertically centered; the `titleLarge` title starts at x=52 dp.
The header belongs to this state surface: the host supplies navigation callbacks,
not an additional top bar.

The body has 32 dp side margins. A 64 dp gap below the header precedes the 120 dp
illustration; 32 dp separates it from the `headlineSmall` heading, then 16 dp
leads to the `bodyMedium` explanation capped at 300 dp width. A weighted spacer
pins the full-width actions toward the bottom.

The filled action is 48 dp high. The text action is visibly 40 dp high, centered
in a 48 dp slot to preserve its accessible touch area. A 4 dp spacer before that
slot gives an 8 dp visible gap between actions; 80 dp bottom padding gives an
84 dp margin below the visible text action. Measure visible and touch bounds
separately when changing this layout.

The activity applies and consumes Scaffold insets before the screen, so its
`systemBarsPadding()` adds only any remaining insets. When hosted alone, the
screen handles them itself. See [navigation insets](navigation.md#configuration).

### Illustration — private `DeniedCameraIllustration`

The packaged [SVG source](../../../app/src/main/res/raw/scanner_denied_source.svg)
is the export of node `32:8`. Its geometry is mechanically translated into
[outline](../../../app/src/main/res/drawable/scanner_denied_outline.xml) and
[strike](../../../app/src/main/res/drawable/scanner_denied_strike.xml) vector
layers, overlaid in a 120 dp square. Both icons are decorative
(`contentDescription = null`); the heading conveys the meaning.

Keep the exported curves, coordinates and stroke widths. The outline uses a
2.5-unit stroke; the 3.5-unit rounded strike runs from `(20,20)` to `(100,100)`,
upper-left to lower-right. The former fractional-coordinate Canvas approximation
made the camera too wide and reversed the strike. Matching the general camera
silhouette alone is insufficient for this reference asset.

The layers are split only for independent theme tints. White paint in the vector
resources is a tint mask; the SVG's exported colors do not set the runtime palette.

## Color binding

| Element | Slot |
| --- | --- |
| Root background | `colorScheme.surface` with `scannerAtmosphere(primaryContainer, surfaceContainerLowest, surface)` behind content |
| Header title, Back arrow and heading | `colorScheme.onSurface` |
| Camera outline and inner-lens fill | `colorScheme.onSurfaceVariant` |
| Strike | `colorScheme.error` |
| Explanation | `colorScheme.onSurfaceVariant` |
| Filled action container / label | M3 defaults: `primary` / `onPrimary` |
| Text action label | M3 default: `primary` |

Production uses the fixed dark theme. The 412×892 dp dark reference is the
visual target; compact width and 1.5× text checks keep the actions reachable.

## Configuration / usage

`PyryNavHost` owns all three destinations: Back calls `popBackStack()`, settings
uses `ACTION_APPLICATION_DETAILS_SETTINGS` with
`Uri.fromParts("package", context.packageName, null)`, and paste navigates to
`Routes.PAIR_CODE`. The screen performs no permission request, parsing or
persistence. Debug builds log only static action names (`back`, `settings`,
`paste`) before invoking the callback.

Permission state remains in `ScannerViewModel`. Its existing process-death and
return-from-settings limitations are described in
[Scanner screen edge cases](scanner-screen-edge-cases-and-testing.md#edge-cases--limitations); restoring
the header does not add an on-resume permission check.

## Testing

The shared tests live under `app/src/sharedTest/.../ui/onboarding/`.
`ScannerScreenTest.denied_backReturnsToCaller` renders `ScannerScreen(Denied)`,
asserts the title, Back description and minimum 48×48 dp bounds, then verifies
exactly one Back callback. Testing only `ScannerDeniedScreen` would miss a
callback dropped by its parent renderer.

`ScannerDeniedScreenTest` checks the heading and independently clicks settings
and paste, asserting that only the corresponding callback fires. It checks the
filled action's 48 dp height and the text action's 40 dp visible / 48 dp touch
height. Merely asserting `hasClickAction()` with no-op callbacks cannot prove
correct dispatch.

`ScannerDeniedRouteDeviceTest.realDenial_backSettingsAndPaste_preserveUnpairedState`
uses a fresh unpaired `MainActivity` on full API 35+, enters from Welcome and
denies the real Android camera request. It captures the dark surface, opens
app-specific settings, returns, opens and cancels the untouched manual form,
then uses Back to reach Welcome. Store assertions prove no pairing was saved.
The test runs settings and paste/cancel before leaving the denied route; it does
not depend on Android showing a second permission prompt after reentry.

[Retained evidence and reproduction command](../../../app/src/androidTest/assets/scanner-denied-1151/README.md)
include the actual activity screenshot, reference, build/device/density/inset
context and one executed test with no failures or skips. Both images are
412×892 at density 1; the actual display has 24 dp top and bottom bars. Compare
top-anchored content shifted down 24 dp and bottom actions shifted up 24 dp,
accounting for bars once. A forced denied-state preview cannot prove the real
permission route, and an API 33 ATD skip cannot substitute for this API 35 run.
See [Compose evidence](development-verification.md#compose-evidence).

The [#1213 retained comparison](../../../app/src/androidTest/assets/scanner-1213/README.md)
places current Figma node 32:2 beside nonblank API 35 pixels and labels the
difference. The design was inspected 2026-09-29; a last-modified date was
unavailable. The existing node 32:8 illustration already matched, so its
asset remained intact. Real system bars account for the remaining edge offsets.
Compact width and enlarged text showed no clipping or overlap; keyboard and
menu states do not apply to this surface.

## Related

- [Restoration plan and revisions](../../specs/architecture/1151-scanner-denied-surface.md)
- [Scanner screen](scanner-screen.md), [pair with code](paste-code-dialog.md),
  [navigation](navigation.md) and [Welcome](welcome-screen.md)
