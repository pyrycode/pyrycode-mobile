# Welcome screen

First screen of the onboarding flow. Shown to new users before any pairing state exists.

## What it does

Greets the user, names the app, and offers two next steps:

- **"I already have pyrycode"** — primary CTA, invokes `onPaired`. Eventually opens the QR scanner (#14).
- **"Set up pyrycode first"** — secondary CTA, invokes `onSetup`. Eventually opens the pyrycode setup docs in an external browser (#14).

A footer line ("Pyrycode is open source.") sits at the bottom.

## How it works

Pure stateless Composable. No `ViewModel`, no `remember`, no I/O.

```kotlin
@Composable
fun WelcomeScreen(
    onPaired: () -> Unit,
    onSetup: () -> Unit,
)
```

Layout is a `Surface` over `Column(SpaceBetween)` with three children: hero block (centered via inner `Spacer(weight(1f))` pair), CTA stack (filled `Button` over `OutlinedButton`), footer `Text`. All colors come from `MaterialTheme.colorScheme.*` and all type from `MaterialTheme.typography.*` — no hardcoded `Color(0x...)` literals, font sizes, weights, or families. Edge-to-edge insets honored via `Modifier.systemBarsPadding()`.

## Why callbacks instead of a NavController

The screen is consumed in two follow-up tickets that can land in either order:

- **#8** adds a `NavHost` and mounts this screen at the `welcome` route.
- **#14** supplies the real `onPaired` / `onSetup` destinations (QR scanner route + external browser `Intent`).

Keeping `WelcomeScreen.kt` free of `NavController` and `Intent` references is what makes that parallelism work. The signature is **fixed**: don't add a `modifier` parameter, don't broaden the callbacks.

## Configuration / usage

Once #8 lands, mount at the `welcome` route:

```kotlin
composable("welcome") {
    WelcomeScreen(
        onPaired = { /* nav to scanner — #14 */ },
        onSetup  = { /* launch browser — #14 */ },
    )
}
```

No DI wiring needed for this screen.

## Edge cases / limitations

- Logo is a plain colored `Box` placeholder; real branding asset is unticketed.
- Subtitle and footer copy are unconstrained by the AC — currently "Your pyrycode CLI, in your pocket." and "Pyrycode is open source." Substitute equivalent short phrasings if better copy is found.
- Previews render at the Figma 412×892 baseline. The screen itself adapts via `fillMaxSize` + `systemBarsPadding`, so other form factors work — but visual review on tablets / foldables hasn't happened.
- Pairing-state-conditional start destination is **not** implemented here; that's #13. Right now the screen always shows on app launch (once #8 mounts it).

## Related

- Spec: `docs/specs/architecture/7-welcome-screen-scaffold.md`
- Ticket notes: `docs/knowledge/codebase/7.md`
- Figma node: `6:32` (412×892 baseline)
- Follow-ups: #8 (routing), #13 (conditional start), #14 (CTA destinations)
