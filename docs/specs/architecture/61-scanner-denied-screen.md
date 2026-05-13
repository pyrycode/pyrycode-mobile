# Scanner Denied screen — architecture spec (#61)

## Context

Figma node `32:2` defines a Scanner — Denied state variant: the surface a user lands on after revoking or refusing the camera permission. CameraX integration is deferred to Phase 4, so this ticket pre-builds the visual surface so it can be wired into the runtime permission flow later without re-litigating Figma fidelity. Slots into the Phase 1.5 Figma catchup wave alongside #57 (Welcome) and #60 (Scanner main).

Pure UI scaffolding: no data layer, no DI, no NavHost wiring. The screen exposes two lambdas (`onOpenSettings`, `onPasteCode`) and the future caller owns intent construction and navigation.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2

Vertical column on `colorScheme.surface`: a 120dp camera-with-diagonal-strike illustration sits near the top, followed by a centered headline ("Camera permission required") and a 14sp body explainer. The two action affordances are pinned to the bottom of the safe area — a full-width filled `Button` ("Open settings") above a full-width `TextButton` ("Paste code instead"). The strike line is the only `colorScheme.error` accent; everything else binds to `surface` / `onSurface` / `onSurfaceVariant` / `primary` slots.

**Intentional deviation from Figma:** the Figma frame shows a "Pair with pyrycode" `TopAppBar` with a back affordance. This screen does NOT render it — `ScannerScreen.kt` doesn't render one either, and the AC mentions no `onBack` lambda. The top bar is a NavHost-level concern that will arrive when this screen is wired into the permission flow in Phase 4. Echo this rationale in code comments only if a reader would otherwise be surprised — otherwise leave it implicit.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:1-95` — the structural template. Mirror its imports, the `Surface(color=…) { Column(fillMaxSize().systemBarsPadding().padding(…)) { … } }` shell, and both `@Preview` functions (light + dark, `widthDp = 412, heightDp = 892`, both wrapped in `PyrycodeMobileTheme`).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` — second reference for the same `Surface`/`systemBarsPadding`/preview convention; cross-check anything ambiguous against this file.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:257-280` — `PyrycodeMobileTheme` signature; previews wrap content here.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` (full file) — confirms `colorScheme.surface`, `colorScheme.onSurface`, `colorScheme.onSurfaceVariant`, `colorScheme.primary`, `colorScheme.onPrimary`, `colorScheme.error` are all defined for both light and dark schemes.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` (if present) — verify `headlineSmall` and `bodyMedium` resolve to the typography Figma binds (24sp/32sp Regular and 14sp/20sp Regular respectively). If the typography file uses a different name, name-match against M3 defaults.
- `gradle/libs.versions.toml` — confirm `androidx.compose.foundation` (provides `Canvas`) and `androidx.compose.material3` are already on classpath. No new dependency should be needed.

## Design — file & composable surface

**One new file:** `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt`. No other files change.

### Public surface

```kotlin
@Composable
fun ScannerDeniedScreen(
    onOpenSettings: () -> Unit,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- Both lambdas are required; the screen invokes them on tap and does nothing else (no `Intent`, no `Context` usage, no `LaunchedEffect`).
- `modifier` is optional and forwarded to the root `Surface` so callers can constrain the screen in tests / hosts.

### Layout structure

```
Surface(color = colorScheme.surface, modifier = modifier.fillMaxSize())
└── Column(fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp, vertical = 32.dp),
          horizontalAlignment = CenterHorizontally)
    ├── Spacer(height = 32.dp)
    ├── DeniedCameraIllustration(Modifier.size(120.dp))
    ├── Spacer(height = 32.dp)
    ├── Text("Camera permission required", style = headlineSmall, color = onSurface, textAlign = Center)
    ├── Spacer(height = 16.dp)
    ├── Text("Pyrycode needs the camera to read the QR code from your server. You can also paste the pairing code instead.",
    │        style = bodyMedium, color = onSurfaceVariant, textAlign = Center,
    │        modifier = Modifier.widthIn(max = 300.dp))
    ├── Spacer(Modifier.weight(1f))                              ← pushes buttons to bottom
    ├── Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open settings") }
    ├── Spacer(height = 8.dp)
    └── TextButton(onClick = onPasteCode, modifier = Modifier.fillMaxWidth()) { Text("Paste code instead") }
```

`Arrangement.SpaceBetween` would also work, but the explicit `Spacer(weight=1f)` reads more obviously and matches the Figma vertical distribution (illustration + text cluster top, actions bottom).

### Illustration — private composable

```kotlin
@Composable
private fun DeniedCameraIllustration(modifier: Modifier = Modifier)
```

Drawn with `Canvas`, not an `ImageVector` or PNG drawable. AC enumerates the primitives directly (rect + 2 ellipses + viewfinder bridge path + error-color strike line); a small Compose `Canvas` is the lowest-friction match and avoids adding files under `res/drawable/`.

Strokes use `Stroke(width = 2.dp.toPx())`. Colors: `colorScheme.onSurfaceVariant` for the camera outline, `colorScheme.error` for the strike line. Camera primitives, in draw order within a 120dp square (coordinates expressed as fractions of the canvas size — the developer picks exact dp values that visually match the Figma screenshot):

1. **Viewfinder bridge** — a small trapezoidal `Path` (4 line segments closing back to start) sitting on top center of the camera body, ~25% canvas width × ~10% canvas height. Stroked.
2. **Camera body rect** — `RoundedRect`, stroked, occupies the center band of the canvas (~80% width, ~55% height), corner radius ~8dp.
3. **Outer lens ellipse** — circle centered on the body, radius ~22% of canvas width. Stroked.
4. **Inner lens ellipse** — circle, same center, radius ~10% of canvas width. Stroked (or filled with `onSurfaceVariant` — match the Figma screenshot; the rendered design shows a small filled dot, but a stroked circle is acceptable if filled looks heavier than the Figma).
5. **Strike line** — diagonal `drawLine` from approximately (top-right area) to (bottom-left area), stroke width 2dp, color `colorScheme.error`, `StrokeCap.Round`. Should visually cross the lens center.

The exact coordinate math is implementation; verify by running the dark preview and side-by-side comparing to the Figma screenshot (downloadable via the design context tool). Iterate the coordinates until it matches — pixel-perfect isn't required, but the silhouette should read as "camera with a strike through it" at a glance.

### Typography binding (verify, do not guess)

- Headline: `MaterialTheme.typography.headlineSmall` — Figma binds 24sp/32sp Roboto Regular, which matches M3 `headlineSmall` defaults.
- Body: `MaterialTheme.typography.bodyMedium` — Figma binds 14sp/20sp Roboto Regular with 0.25 tracking, which matches M3 `bodyMedium`.
- Button labels use M3 defaults (`labelLarge`, 14sp Medium, 0.1 tracking). Don't override `TextStyle` on the `Text` inside `Button` / `TextButton`.

If the project's `Type.kt` overrides one of these to a divergent value, prefer the named role over inlining a `TextStyle` — the AC forbids hardcoded `TextStyle`. If a divergence forces a choice between fidelity and the AC, follow the AC (named role) and flag the divergence in a PR comment.

### Color binding summary

| Element | Slot |
|---|---|
| Root Surface background | `colorScheme.surface` |
| Camera outline (rect, ellipses, viewfinder bridge) | `colorScheme.onSurfaceVariant` |
| Strike line | `colorScheme.error` |
| Headline | `colorScheme.onSurface` |
| Body | `colorScheme.onSurfaceVariant` |
| Filled Button container / label | M3 default (`primary` / `onPrimary`) — don't override |
| TextButton label | M3 default (`primary`) — don't override |

No hardcoded color literals. No `Color(0xFF…)` anywhere in the file.

## State + concurrency model

None. The screen is stateless and side-effect-free:

- No `remember` (no local state — both action handlers are caller-owned).
- No `LaunchedEffect`, `DisposableEffect`, or coroutine usage.
- No `ViewModel`.
- No `Context.LocalContext` usage (the caller, not the screen, will resolve `Context` for the future `ACTION_APPLICATION_DETAILS_SETTINGS` intent).

This matches `ScannerScreen.kt` and the Phase 0 "fake data only" stance — the screen is a pure function from `(onOpenSettings, onPasteCode)` to UI.

## Error handling

N/A. There is no I/O, no permission API call, and no parse step in this screen. The screen is the error-state surface for camera-permission-denied; recovery is delegated to the two lambdas, both of which the caller owns.

## Testing strategy

The two existing Figma-anchor screens (`WelcomeScreen.kt`, `ScannerScreen.kt`) ship with no unit or instrumented tests beyond the `@Preview` composables — the previews function as the visual-fidelity check, and the AC explicitly calls them out. Match that bar exactly.

**Required:**

- `@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)` → `PyrycodeMobileTheme(darkTheme = false) { ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {}) }`
- `@Preview(name = "Dark", …, uiMode = Configuration.UI_MODE_NIGHT_YES)` → `PyrycodeMobileTheme(darkTheme = true) { ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {}) }`
- Both `private`, both at the bottom of the file, both matching the dimensions / kwarg ordering used by `ScannerScreen.kt`.

**Not required (do not add):**

- No `androidTest` `ComposeTestRule` test. Existing onboarding screens have none; adding one here is scope creep.
- No unit test. There's no logic to assert.
- No screenshot/snapshot test. The project hasn't adopted a snapshot testing library; introducing one in this ticket is out of scope.

**Manual verification (developer's responsibility before opening the PR):**

1. `./gradlew assembleDebug` — must compile clean.
2. `./gradlew lint` — must pass without new findings on this file.
3. Open the file in Android Studio Preview pane and visually compare both light and dark previews against the Figma screenshot (downloadable via the Figma design context tool at node `32:2`). Iterate Canvas coordinates until the camera silhouette reads correctly at preview scale.

## Open questions

- **Inner lens — stroked or filled?** Figma renders a small dot. A 2dp-stroked circle and a filled circle look nearly identical at this scale. Pick whichever matches the Figma screenshot more closely after a side-by-side; either is acceptable.
- **Button corner radius.** M3 `Button` defaults to fully rounded (pill / `CircleShape`). Figma shows pill-shaped buttons (`rounded-[24px]` on a 48dp-tall pill = fully rounded). Use the M3 default — don't override `shape`.
