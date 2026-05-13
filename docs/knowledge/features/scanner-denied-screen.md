# Scanner Denied screen

State surface for the camera-permission-denied branch of the pairing flow. Pre-built ahead of CameraX integration so visual fidelity to Figma node `32:2` is settled before runtime permission routing arrives in Phase 4.

## What it does

- Renders a vertical column on `colorScheme.surface`: a 120dp camera-with-diagonal-strike `Canvas` illustration near the top, a centered headline ("Camera permission required"), and a `bodyMedium` explainer paragraph capped at 300dp width.
- Pins two full-width actions to the bottom of the safe area: filled `Button` ("Open settings") above `TextButton` ("Paste code instead"). Both invoke caller-owned lambdas.
- The screen itself launches no intents and performs no navigation. The future caller owns `ACTION_APPLICATION_DETAILS_SETTINGS` construction and the back-stack hop to the paste-code destination.

## How it works

Stateless Composable; no `ViewModel`, no `remember`, no `LaunchedEffect`, no `Context` usage.

```kotlin
@Composable
fun ScannerDeniedScreen(
    onOpenSettings: () -> Unit,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Composition shape mirrors `ScannerScreen.kt` and `WelcomeScreen.kt`: outer `Surface(color = colorScheme.surface, fillMaxSize)` wraps an inner `Column(fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = CenterHorizontally)`. Vertical distribution is `Spacer(32.dp) → illustration → Spacer(32.dp) → headline → Spacer(16.dp) → body → Spacer(weight = 1f) → primary Button → Spacer(8.dp) → TextButton`. The `weight(1f)` spacer is what pushes the action stack to the bottom and reads more obviously than `Arrangement.SpaceBetween` for two top-clustered + two bottom-pinned children.

### Illustration — private `DeniedCameraIllustration`

Drawn with `Canvas`, not an `ImageVector` drawable. The illustration is small (rect + 2 ellipses + viewfinder bridge path + diagonal line) and self-contained, so a few `drawRoundRect` / `drawCircle` / `drawPath` / `drawLine` calls cost less than adding a vector under `res/drawable/`. Coordinates are expressed as fractions of the canvas `size`, so the silhouette stays proportional at any `Modifier.size(...)`.

Draw order within the 120dp square (`ScannerDeniedScreen.kt:90-145`):

1. **Camera body** — `drawRoundRect`, stroked, centered vertically (`10%–90%` width band, `30%–75%` height band), corner radius `8.dp`.
2. **Viewfinder bridge** — `Path` of three `lineTo` segments (the implicit `close` happens via the closing `lineTo` back to the body's top edge), narrower at the top, sitting on top of the camera body.
3. **Outer lens** — stroked `drawCircle` centered on the body, radius ~13% of canvas width.
4. **Inner lens** — *filled* `drawCircle` (no `style` argument), radius ~4.5% of canvas width. A filled dot reads cleaner than a stroked circle at this scale; matches the Figma render.
5. **Strike line** — `drawLine` from top-right (~82%, 18%) to bottom-left (~18%, 86%), `StrokeCap.Round`, `colorScheme.error`. Crosses the lens center.

Stroke width is `2.dp.toPx()` everywhere except the filled inner dot. All outline strokes share a single `Stroke(width = strokePx, cap = StrokeCap.Round)` instance.

## Color binding

| Element | Slot |
|---|---|
| Root `Surface` background | `colorScheme.surface` |
| Camera outline (rect, ellipses, bridge), inner-lens fill | `colorScheme.onSurfaceVariant` |
| Strike line | `colorScheme.error` |
| Headline | `colorScheme.onSurface` |
| Body | `colorScheme.onSurfaceVariant` |
| Filled `Button` container / label | M3 default (`primary` / `onPrimary`) — not overridden |
| `TextButton` label | M3 default (`primary`) — not overridden |

No `Color(0x…)` literals anywhere in the file. Typography binds to M3 roles (`headlineSmall`, `bodyMedium`); button labels use M3 defaults — no `TextStyle(...)` overrides.

## Configuration / usage

Not yet wired into `MainActivity`'s `NavHost`. The composable is in place for Phase 4: when CameraX integration lands and the runtime permission flow needs a denied-state destination, the wiring is a `composable(Routes.ScannerDenied) { ScannerDeniedScreen(onOpenSettings = { ... }, onPasteCode = { ... }) }` block where the caller resolves a `Context` (e.g. `LocalContext.current`) for the `ACTION_APPLICATION_DETAILS_SETTINGS` intent and a `NavController` for the paste-code hop.

`modifier` is forwarded to the root `Surface` so a host can constrain the screen in tests.

## Why no top bar

The Figma frame shows a "Pair with pyrycode" `TopAppBar` with a back affordance. The screen intentionally omits it — `ScannerScreen.kt` doesn't render one either, and the top bar is a NavHost-level concern that arrives with Phase 4's permission flow wiring. The AC mentions no `onBack` lambda.

## State + concurrency

None. Pure function from `(onOpenSettings, onPasteCode)` to UI. The screen owns no state and produces no side effects beyond invoking the two caller-supplied lambdas.

## Error handling

N/A. The screen *is* the camera-permission-denied error state; there is no I/O, no permission API call, and no parse step to fail. Recovery is delegated to the two lambdas, both caller-owned.

## Edge cases / limitations

- **Phase 4 walk-back.** The screen survives Phase 4 wholesale — its sole role is the visual surface for the denied state, which CameraX integration consumes as-is. The only future addition is the `TopAppBar` / `onBack` lambda once the screen is hosted inside the pairing nav graph.
- **Pixel-perfect not required.** Canvas coordinates are tuned by visual side-by-side against the Figma screenshot, not measured. The silhouette must read as "camera with a strike through it"; sub-pixel fidelity is explicitly out of scope.
- **Two `@Preview` composables only.** No `androidTest`, no unit test, no snapshot test — matches the Welcome / Scanner bar. Previews are the visual-fidelity check.

## Related

- Issue: https://github.com/pyrycode/pyrycode-mobile/issues/61
- Spec: `docs/specs/architecture/61-scanner-denied-screen.md`
- Ticket notes: `../codebase/61.md`
- Figma node: `32:2`
- Sibling docs: [Scanner screen](scanner-screen.md), [Welcome screen](welcome-screen.md)
- Downstream: Phase 4 CameraX integration wires this into the runtime permission flow and adds the `TopAppBar`.
