# Scanner Denied screen

State surface for the camera-permission-denied branch of the pairing flow. Pre-built (#61) ahead of integration so visual fidelity to Figma node `32:2` was settled first; **wired live in #326** as the `Denied` render of the stateful [Scanner screen](scanner-screen.md)'s `when(state)` — reused as-is, no top bar added.

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

Rendered **in-route**, not at its own route. Since #326 the stateful `ScannerScreen`'s `when(state)` dispatches `ScannerUiState.Denied → ScannerDeniedScreen(onOpenSettings, onPasteCode, modifier)` inside `composable(Routes.SCANNER)` — there is no `Routes.ScannerDenied` (AC2: "no new denied screen is invented"). The route owns both lambdas: `onOpenSettings` fires the `ACTION_APPLICATION_DETAILS_SETTINGS` intent (`context.startActivity` with `Uri.fromParts("package", packageName, null)`), and `onPasteCode` is the shared `stubPairAndNavigate` (the #295 stub-pair persist + navigate), so onboarding still completes from the denied state.

`modifier` is forwarded to the root `Surface` so a host can constrain the screen in tests.

## Why no top bar

The Figma frame shows a "Pair with pyrycode" `TopAppBar` with a back affordance. The screen intentionally omits it — `ScannerScreen.kt` doesn't render one either, and the top bar is a NavHost-level concern that arrives with Phase 4's permission flow wiring. The AC mentions no `onBack` lambda.

## State + concurrency

None. Pure function from `(onOpenSettings, onPasteCode)` to UI. The screen owns no state and produces no side effects beyond invoking the two caller-supplied lambdas.

## Error handling

N/A. The screen *is* the camera-permission-denied error state; there is no I/O, no permission API call, and no parse step to fail. Recovery is delegated to the two lambdas, both caller-owned.

## Edge cases / limitations

- **Hosted as-is, still no top bar.** #326 wired the screen into the runtime permission flow without modifying it — no `TopAppBar` / `onBack` was added (the denied state has no back affordance in the Figma frame either). Its sole role remains the visual surface for the denied state; the camera-engine slice consumes it unchanged. Process-death caveat lives on the host: after process death while `Denied`, the route falls back to the viewport shell (the resolved state lives only in the VM) — see [Scanner screen](scanner-screen.md) Edge cases.
- **Pixel-perfect not required.** Canvas coordinates are tuned by visual side-by-side against the Figma screenshot, not measured. The silhouette must read as "camera with a strike through it"; sub-pixel fidelity is explicitly out of scope.
- **Two `@Preview` composables plus a three-method instrumented test class since #101.** `app/src/androidTest/.../onboarding/ScannerDeniedScreenTest.kt` covers `heading_rendersCameraPermissionRequired` (exact match), `openSettingsButton_hasClickAction` (`"Open settings"` carries a click action), and `pasteCodeButton_hasClickAction` (`"Paste code instead"` carries a click action). Structure only — callback wiring intentionally unasserted; the screen's two lambdas are passed as `{}` no-ops at the test site.

## Related

- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/61 (this screen), https://github.com/pyrycode/pyrycode-mobile/issues/326 (wired into the permission flow)
- Specs: `docs/specs/architecture/61-scanner-denied-screen.md`, `docs/specs/architecture/326-stateful-scanner-permission-flow.md`
- Ticket notes: `../codebase/61.md`, `../codebase/326.md`
- Figma node: `32:2`
- Sibling docs: [Scanner screen](scanner-screen.md), [Welcome screen](welcome-screen.md)
- Consumer: #326 renders this as the `Denied` state inside `composable(Routes.SCANNER)`; the camera-engine slice consumes it unchanged.
