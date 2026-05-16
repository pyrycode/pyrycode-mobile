# Welcome screen

First screen of the onboarding flow. Shown to new users before any pairing state exists. Visual treatment is locked to Figma node `6:32` (since #57).

## What it does

Greets the user, names the app, and offers two next steps:

- **"I already have pyrycode"** — primary CTA (filled M3 `Button` with a QR-frame leading icon), invokes `onPaired`. Currently navigates to the `scanner` route (#12); Phase 4 replaces the stub scanner with real CameraX + ML Kit pairing.
- **"Set up pyrycode first"** — secondary CTA (M3 `TextButton`), invokes `onSetup`. Launches the device's default browser at `https://pyryco.de/setup` via `Intent.ACTION_VIEW` (#14).

A centred footer line — `"Open source · github.com/pyrycode/pyrycode-mobile"` — sits below the CTA stack at reduced alpha.

## How it works

Pure stateless Composable. No `ViewModel`, no `remember`, no I/O.

```kotlin
@Composable
fun WelcomeScreen(
    onPaired: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
)
```

The `modifier` parameter landed in #84 to satisfy compose-lints' `ComposeModifierMissing` rule. It threads onto the outermost `Box` (`Box(modifier = modifier.fillMaxSize().background(…).drawBehind { … })`); the NavHost call site passes nothing and the default keeps positioning unchanged.

Layout is a top-level `Box(fillMaxSize)` with three layers stacked back-to-front:

1. **Base** — `Modifier.background(colorScheme.surface)`.
2. **Atmospheric glow** (since #57) — `Modifier.drawBehind` paints a `Brush.radialGradient` centered at roughly (48% width, 30% height), radius ~53% of height, fading from `colorScheme.primaryContainer.copy(alpha = 0.6f)` to `Color.Transparent`. Sits behind content without consuming layout space.
3. **Content** — `Column(fillMaxSize, systemBarsPadding, horizontalPadding = 32.dp, SpaceBetween)`:
   - Top-anchored **hero** (`padding(top = 168.dp)` since #149, `spacedBy(28.dp)`): `Icon(R.drawable.ic_pyry_logo, tint = colorScheme.primary, size = 104.dp)`, then `"Pyrycode Mobile"` (`headlineLarge` / `onSurface`) stacked over `"Control Claude sessions on your phone."` (`titleMedium` / `onSurfaceVariant`), then a `bodyLarge` / `onSurfaceVariant` body paragraph explaining the local-first model. The 168.dp value is Figma node `6:32`'s `pt-[168px]` read as "below the safe area" — applied after `systemBarsPadding()` rather than with inset math (project-wide convention; see [`codebase/149.md`](../codebase/149.md)).
   - Bottom-anchored **CTA stack** (`padding(bottom = 16.dp)`, `spacedBy(12.dp)`): full-width 56dp-tall filled `Button` with `shape = RoundedCornerShape(28.dp)` since #149 (corner = height / 2 ⇒ pill, matching Figma node `6:41`'s `rounded-[28px]`; `ic_qr_scan_frame` leading icon + label), full-width 56dp-tall `TextButton` (no shape override — M3 text-button surface has no fill, so corner radius is visually irrelevant even though Figma node `6:43` also declares `rounded-[28px]`), centred `labelSmall` footer at `onSurfaceVariant.copy(alpha = 0.55f)`.

All colors come from `MaterialTheme.colorScheme.*` and all typography from `MaterialTheme.typography.*` — no hardcoded `Color(0x…)` literals, no custom `TextStyle(...)`. `Color.Transparent` (the terminal radial-gradient stop) is the only non-token `Color` and is permitted because it's a constant, not a hex literal. Edge-to-edge insets honored via `Modifier.systemBarsPadding()`.

## Why callbacks instead of a NavController

The screen is consumed in follow-up tickets that landed in any order:

- **#8** added a `NavHost` and mounts this screen at the `welcome` route.
- **#12** wired `onPaired` to the `scanner` stub route; Phase 4 replaces the scanner body itself.
- **#14** wired `onSetup` to an `Intent.ACTION_VIEW` launch at `https://pyryco.de/setup`.

Keeping `WelcomeScreen.kt` free of `NavController` and `Intent` references is what made that parallelism work, and it keeps preview / future ComposeTestRule callers able to pass `onSetup = {}` no-ops without a real `Context`. Callback surface stays narrow — don't broaden `onPaired` / `onSetup` into a single `(state, onEvent)` MVI shape (the screen has no state to manage). The `modifier` trailer added by #84 is the upstream-mandated exception — compose-lints' `ComposeModifierMissing` rule applies project-wide.

## Configuration / usage

Mounted at the `welcome` route in `PyryNavHost` (see `MainActivity.kt`):

```kotlin
composable(Routes.Welcome) {
    val context = LocalContext.current
    WelcomeScreen(
        onPaired = { navController.navigate(Routes.Scanner) },
        onSetup  = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SetupUrl)),
            )
        },
    )
}
```

`SetupUrl` is a file-scope `private const val` in `MainActivity.kt` set to `https://pyryco.de/setup`. The URL currently redirects to the pyrycode repo README; a real landing page is a future, unticketed change.

No DI wiring needed for this screen.

## Assets

Two vector drawables under `app/src/main/res/drawable/`, both rendered via `Icon(painter = painterResource(R.drawable.<id>), tint = colorScheme.primary)`:

- **`ic_pyry_logo.xml`** — snowflake + center cursor (Figma node `9:2`, 104dp viewport). Decorative, `contentDescription = null` — the title text below carries the screen identity.
- **`ic_qr_scan_frame.xml`** — four-corner QR scanner frame (Figma node `9:41`, 20dp viewport). Used as the primary CTA leading icon.

Both have a single uniform fill / stroke color in the raw XML so the Compose `Icon(tint = …)` recolors them across light and dark themes without per-theme variants. Don't add `material-icons-extended` for the QR frame — the ticket explicitly bans new icon packages; assets come from the Figma payload only.

## Edge cases / limitations

- Previews render at the Figma 412×892 baseline. The screen itself adapts via `fillMaxSize` + `systemBarsPadding` and the radial-gradient center/radius are fractions of canvas size, so other form factors work — but visual review on tablets / foldables hasn't happened.
- Glow alpha is `0.6f` (within the spec-suggested 0.4–0.8 range). Tune against the dark preview if a future ticket changes the brand palette.
- Pairing-state-conditional start destination is **not** implemented here; that's #13. Once the user is paired, the start destination is `channel_list` and this screen no longer renders.

## Related

- Spec (current): `docs/specs/architecture/57-welcome-screen-figma-polish.md`
- Spec (original scaffold): `docs/specs/architecture/7-welcome-screen-scaffold.md`
- Ticket notes: `../codebase/7.md` (scaffold), `../codebase/14.md` (`onSetup` external-browser wiring), `../codebase/57.md` (Figma polish), `../codebase/149.md` (pill CTA + 168.dp hero top padding refinement)
- Figma node: `6:32` (412×892 baseline) — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32
- Sibling: [Scanner screen](scanner-screen.md)
