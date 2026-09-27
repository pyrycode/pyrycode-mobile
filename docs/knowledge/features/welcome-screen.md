# Welcome screen

First screen of the onboarding flow. Shown to new users before any pairing state exists. Visual treatment is locked to Figma node `6:32` (since #57).

## What it does

Greets the user, names the app, and offers two next steps:

- **"I already have pyrycode"** — primary CTA (filled M3 `Button` with a QR-frame leading icon), invokes `onPaired`. Currently navigates to the `scanner` route (#12); Phase 4 replaces the stub scanner with real CameraX + ML Kit pairing.
- **"Set up pyrycode first"** — secondary CTA (M3 `TextButton`), invokes `onSetup`. Launches the device's default browser at `https://pyryco.de/setup` via `Intent.ACTION_VIEW` (#14).

A centred footer line — `"Open source · github.com/pyrycode/pyrycode-mobile"` — sits below the CTA stack at reduced alpha.

## How it works

Stateless Composable with two callbacks and an optional `modifier`; no ViewModel
or I/O. `remember(logo)` caches only the shape used for the logo shadow.
The supplied modifier threads onto the outermost `Box`.

Layout is a top-level `Box(fillMaxSize)` with three layers stacked back-to-front:

1. **Base** — `Modifier.background(colorScheme.surface)`.
2. **Atmospheric glow** — `drawWithCache` builds a radial shader with Figma's
   affine transform, preserving the tilted ellipse and its independent axes.
   The radius-10 reference uses `matrix(28.4 -2.6 4.3038 47.011 196 265)` at
   412 × 892, scaled to the available drawing area's width and height. Stops
   run from opaque `primaryContainer` at 0 to transparent `onPrimary` at 0.7.
   This returns to the surface near the upper corners; a circular gradient
   with a height-based radius does not reproduce that fade. Drawing consumes
   no layout space.
3. **Content** — a `Column` with `fillMaxSize`, `systemBarsPadding`, 32 dp
   horizontal padding and `Arrangement.SpaceBetween` holds the hero and CTA stack.

The hero starts 168 dp below the safe area and uses 28 dp gaps. Its existing
`ic_pyry_logo` painter retains the `primary` tint and 92 × 104 dp slot. Matching
that slot to the drawable's aspect ratio keeps the visible mark aligned with the
32 dp content edge; a square slot introduces horizontal inset. Two `dropShadow`
modifiers precede `.size(...)`, using a `GenericShape` parsed from the existing
vector's single path and scaled from its viewport to the slot. Both use `scrim`:

- 15% alpha, 8 dp blur radius, 3 dp spread, offset (0, 4 dp).
- 30% alpha, 3 dp blur radius, zero spread, offset (0, 1 dp).

These explicit layers follow the mark without a rectangular plate, replacing the
former rectangular 6 dp elevation shadow. Hero text has no added shadow.
The title remains `headlineLarge` / `onSurface`, the tagline `titleMedium` /
`onSurfaceVariant`, and the body `bodyLarge` / `onSurfaceVariant`. Only the
left-aligned body has `Modifier.width(320.dp)`: incoming constraints shrink it to
available content width, giving 320 dp at a 412 dp display and 296 dp at 360 dp.
Copy and typography are unchanged.

The CTA stack has 16 dp bottom padding and 12 dp gaps. Its full-width, 56 dp
primary `Button` uses `RoundedCornerShape(28.dp)` and the QR-frame icon. The
full-width, 56 dp secondary `TextButton` explicitly uses `onSurface` for
“Set up pyrycode first”. A centred `labelSmall` footer uses
`onSurfaceVariant.copy(alpha = 0.55f)`.

Colors and typography come from `MaterialTheme`. The activity's Scaffold applies
and consumes shared system-bar insets before `PyryNavHost`; Welcome's
`systemBarsPadding()` respects that consumption and must not add the same bars
again. The glow scales within that inset-consumed content area. See
[Navigation](navigation.md) for host ownership.

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

- **`ic_pyry_logo.xml`** — snowflake + center cursor. Decorative, `contentDescription = null` — the title text below carries the screen identity. Since #167 the drawable is a **single `<path>`** with `fillColor="#FFFFFF"` and viewport `91.002 × 103.812` at intrinsic `92dp × 104dp`, mirroring the Figma source-of-truth at node `80:2` "pyrycode-mark 1" (inner Group `80:3`) — one continuous filled outline encoding the six-pointed snowflake plus the centre cursor as one glyph. Replaces the pre-#167 six-arm stroke composition (six `<group>` arm wrappers at 60° rotations + a centre-cursor `<path>`, viewport `104 × 104`) that #57 shipped and #150 patched; the encoding shape changed in Figma after #150 merged, so the Android drawable was rewritten end-to-end rather than back-patched (the per-group rotation surgery #150 used is inapplicable to a single filled outline — see [`codebase/167.md`](../codebase/167.md)). The `Icon(tint = primary)` pathway is preserved across both encodings.
- **`ic_qr_scan_frame.xml`** — four-corner QR scanner frame (Figma node `9:41`, 20dp viewport). Used as the primary CTA leading icon.

Both have a single uniform fill / stroke color in the raw XML so the Compose `Icon(tint = …)` recolors them across light and dark themes without per-theme variants. Don't add `material-icons-extended` for the QR frame — the ticket explicitly bans new icon packages; assets come from the Figma payload only.

## Testing

`WelcomeScreenTest` in `app/src/sharedTest` checks both CTA displays, that each
click invokes only its own callback, the 320/296 dp body widths and the setup
text's `onSurface` role. Width assertions need Robolectric
`@GraphicsMode(GraphicsMode.Mode.NATIVE)`: default graphics reported an
implausible roughly 178 dp body, while native font rendering exposed the old
roughly 348 dp measure. Semantics checks alone do not establish the glow or shadow.

`WelcomeAppearanceDeviceTest` launches real `MainActivity` in dark mode with
wallpaper colors disabled at 412 × 892 and 360 × 800 dp, at 160 dpi. It checks
body bounds and action/footer reachability. Run it on the full `pixel8Api35`
image with `requireRealSystemBars=true` for visual evidence; the ATD path can
pass geometry checks without capturing pixels. See
[Compose evidence](development-verification.md#compose-evidence) and the
[retained captures, metadata and command](../../../app/src/androidTest/assets/welcome-1150/capture-context.txt).

## Edge cases / limitations

- The elliptical glow scales independently with the available width and height.
  The real activity includes system chrome absent from Figma, so its content
  and glow are vertically displaced relative to the chrome-free reference.
  Dark captures confirm the fade and silhouette shadow at 412 × 892 and
  360 × 800 dp; tablets and foldables have not been visually reviewed.
- The shadow shape assumes the drawable's first root child is its single filled
  `VectorPath`. A future asset with groups or multiple paths needs corresponding
  shape handling. A rectangular container shadow is not an acceptable substitute:
  the plate was visible on the dark surface in the device review.
- A 320 dp body measure does not guarantee Figma's line breaks. Retained Android
  captures have four lines versus Figma's five with the existing `bodyLarge`
  metrics. The body shrinks to 296 dp at 360 dp without horizontal overflow,
  and both actions and the footer remain reachable above the real system bars.
- Pairing-state-conditional start destination is owned by `MainActivity`, not
  Welcome. Paired users start at `channel_list`.

## Related

- [Current visual treatment spec](../../specs/architecture/1150-welcome-dark-treatment.md)
- [Original Figma polish spec](../../specs/architecture/57-welcome-screen-figma-polish.md)
- Spec (original scaffold): `docs/specs/architecture/7-welcome-screen-scaffold.md`
- Ticket notes: `../codebase/7.md` (scaffold), `../codebase/14.md` (`onSetup` external-browser wiring), `../codebase/57.md` (Figma polish), `../codebase/149.md` (pill CTA + 168.dp hero top padding refinement), `../codebase/150.md` (logo arm geometry repair under the old `9:2` source), `../codebase/167.md` (full drawable rewrite to single filled path under the new `80:2` source + Icon bounding-box switch to 92×104dp), `../codebase/168.md` (M3 Elevation Level 3 drop-shadow on logo `Icon` via `Modifier.shadow`)
- Figma node: `6:32` (412×892 baseline) — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32
- Sibling: [Scanner screen](scanner-screen.md)
