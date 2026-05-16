# ConnectionBanner

Stateless row primitive (#200) that renders the four-case [`ConnectionState`](./connection-state.md) as a banner with the agreed product copy. Drop-in for any screen that needs to surface connection status — invoked as `ConnectionBanner(state, onRetry)`. The `Connected` case returns early with no composition (zero height); the three visible cases render a single line of text inside a tonal `Surface` row that's always `clickable` but only invokes `onRetry` in the `Offline` case.

Pure UI — no DI, no ViewModel, no flow collection. The consumer that places the banner inside `ThreadScreen` and connects it to `ConnectionStateSource.observe()` / `retry()` lands in the follow-up wiring slice split from #197.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ConnectionBanner.kt`.

## Shape

```kotlin
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Three params, no defaults on the load-bearing two. `onRetry` is non-optional even though it's only invoked in `Offline` — the call site already owns the lambda, and making it nullable would push a `null`-vs-`{}` decision onto every caller; passing `{}` for screens that never expect to reach `Offline` is the cheaper convention.

## What it does

The public `ConnectionBanner` resolves the per-state quartet (visible flag, copy, container colour, content colour, tap handler) in a single `when` block and either returns (for `Connected`) or delegates to a private stateless `ConnectionBannerContent(text, containerColor, contentColor, onClick, modifier)` that renders the `Surface` + `Text`. The split mirrors the [stateful-wrapper / stateless-content pattern](./tool-call-row.md) from #131 / #184: previews drive `ConnectionBannerContent` indirectly by calling the public `ConnectionBanner` with each state value, so AC-4's "preview renders all four states" doubles as the regression artifact for the public-entry short-circuit on `Connected`.

### State → copy + colour mapping

| `ConnectionState`              | Visible? | Copy string                              | Container slot                | Content slot              | Tap handler |
|--------------------------------|----------|------------------------------------------|-------------------------------|---------------------------|-------------|
| `Connected`                    | No       | — (return early; zero height)            | —                             | —                         | —           |
| `Connecting`                   | Yes      | `"Connecting…"`                          | `surfaceContainerHigh`        | `onSurface`               | `{}` (no-op)|
| `Reconnecting(secondsRemaining)` | Yes    | `"Reconnecting in ${secondsRemaining}s"` | `surfaceContainerHigh`        | `onSurface`               | `{}` (no-op)|
| `Offline`                      | Yes      | `"Offline — tap to retry"`               | `errorContainer`              | `onErrorContainer`        | `onRetry`   |

Copy strings are Kotlin literals — no `stringResource` indirection. Same posture as the rest of `ui/conversations/components/` today (`ToolCallRow`'s `"Input"` / `"Output"` labels, `MessageBubble`'s preview fixture). The em-dash in `"Offline — tap to retry"` is the actual U+2014 character, not `--`. The `Reconnecting` interpolation uses the lowercase `s` suffix with no space (`"Reconnecting in 12s"`).

Text style is `MaterialTheme.typography.bodyMedium` for all three visible cases — same body slot `ToolCallRow` uses for its summary line.

### Tap routing — always-clickable Surface

`Modifier.clickable` is attached **unconditionally** on the `Surface`; the `onClick` argument is either `onRetry` (for `Offline`) or a no-op `{}` (for `Connecting` / `Reconnecting`). The contract is "the row ignores taps in `Connecting` / `Reconnecting`" — routing that through a no-op lambda is simpler than conditionally attaching the modifier and keeps the `Surface` shape uniform across all three visible states. Same idiom as `ToolCallRow.kt` which unconditionally attaches `clickable` and folds the gating into the lambda body.

The M3 default ripple paints in all three visible cases — including `Connecting` / `Reconnecting`, where the lambda is a no-op. Acceptable: the ripple is the visual confirmation that the tap registered; the *behaviour* of "nothing happens" is the contract. If a future a11y or design review wants to suppress the ripple in those cases, the swap is to either (a) `Modifier.clickable(enabled = state is Offline, ...)`, or (b) route the clickable conditionally inside `ConnectionBannerContent`. Don't pre-pay.

### Layout

`Surface(modifier.fillMaxWidth().clickable(...), color = containerColor, contentColor = contentColor)` wrapping a single `Text` with `Modifier.padding(horizontal = BannerHorizontalPadding = 16.dp, vertical = BannerVerticalPadding = 12.dp)`. No `Row`, no leading icon, no trailing affordance — the banner is a one-line surface. `Surface.contentColor` is set explicitly so `Text` inherits the right `onContainer` slot without a per-call `color = …` argument (M3 contract: `Text` defaults to `LocalContentColor.current`, which `Surface` provides via its `contentColor`).

### Spacing constants

Two file-private `val`s at the top of `ConnectionBanner.kt`:

```kotlin
private val BannerVerticalPadding = 12.dp
private val BannerHorizontalPadding = 16.dp
```

No raw `.dp` literal at any call site. Same named-constants posture as [`MessageBubble`](./message-bubble.md) / [`ToolCallRow`](./tool-call-row.md). No cross-file dedup yet — these are layout-specific to the banner and don't share intent with the message-row constants. Promote to a shared `Spacing.kt` peer file only when a third component declares the same value.

## Recomposition / stability

- [`ConnectionState`](./connection-state.md) is a sealed class with `data object` and `data class` cases — all stable, safe to pass directly.
- `onRetry` is a lambda parameter; if the caller passes a non-`remember`ed lambda Compose will recompose on every parent recomposition. The banner doesn't defend against this — the caller is responsible. The convention in this codebase is to declare event lambdas at the destination block (`MainActivity.PyryNavHost`) as plain literals; lifting to `remember` is reserved for hot recomposition sites.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`. Pure projection of `ConnectionState` to a rendered row.

## Configuration

- **No new dependencies.** All imports are existing Compose Material 3 + the [`ConnectionState`](./connection-state.md) model from #196.
- **No new string resources.** Literals only.
- **No `gradle/libs.versions.toml` edits.**

## Preview

Two `@Preview`s, one per theme, both `widthDp = 412` and `showBackground = true` — the dark variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`. Both delegate to a shared private `ConnectionBannerPreviewMatrix()` that stacks all four states in a `Column`:

1. `state = ConnectionState.Connected` — establishes the zero-height baseline; visually nothing renders between the column's top and the `Connecting` row, which is the AC-1 assertion.
2. `state = ConnectionState.Connecting`
3. `state = ConnectionState.Reconnecting(secondsRemaining = 12)` — representative `Int`.
4. `state = ConnectionState.Offline`

Each entry invokes the **public** `ConnectionBanner(state, onRetry = {})` (not `ConnectionBannerContent`) — the preview is the regression artifact for the `Connected` short-circuit, so it must exercise the public surface. Wrapped in `PyrycodeMobileTheme(darkTheme = ...) { Surface { ConnectionBannerPreviewMatrix() } }` so each row paints against the theme's `surface` slot.

## Edge cases / limitations

- **`Reconnecting(secondsRemaining = 0)` renders `"Reconnecting in 0s"`** — no special-case to "Reconnecting now…". The data layer is responsible for transitioning to `Connecting` / `Connected` when the countdown elapses; the banner trusts the value it's handed.
- **Negative `secondsRemaining` renders verbatim** (e.g. `"Reconnecting in -1s"`). The model deliberately omits `require(secondsRemaining >= 0)` — see [Connection state](./connection-state.md). If Phase 4's real source ever emits negative values, the banner will display them; consider clamping at the model boundary, not here.
- **Ripple fires in `Connecting` / `Reconnecting`** even though the tap is a no-op. See [Tap routing](#tap-routing--always-clickable-surface) above.
- **No icon, no chevron, no progress indicator.** Per AC-2 the visible cases are text-only. A spinner during `Connecting` / `Reconnecting` is a possible follow-up if reviewer feedback or product asks for richer affordance; today the copy is the only signal.
- **No animation between states.** State changes swap the banner in/out instantly — no `AnimatedVisibility`, no cross-fade. Acceptable for Phase 0; the wiring slice may layer animation at the consumer side.

## Related

- Ticket notes: [`../codebase/200.md`](../codebase/200.md)
- Spec: `docs/specs/architecture/200-connectionbanner-composable.md`
- Parent: split from [#197](https://github.com/pyrycode/pyrycode-mobile/issues/197) (itself split from #134).
- Upstream: [Connection state](./connection-state.md) — the sealed model + source contract the banner consumes (#196).
- Sibling component patterns: [`ToolCallRow`](./tool-call-row.md) (stateful-wrapper / stateless-content split, always-`clickable` `Surface`, file-private spacing `val`s), [`MessageBubble`](./message-bubble.md) (file-private spacing constants posture, role-dispatch via `when`), [`MarkdownText`](./markdown-text.md) (`LocalContentColor` inheritance via `Surface.contentColor`).
- Downstream / follow-ups:
  - Follow-up wiring slice (open, split from #197): place the banner at the top of `ThreadScreen`, collect `ConnectionStateSource.observe()` via `collectAsStateWithLifecycle()` in `ThreadViewModel`, pipe `onRetry = vm::onRetryRequested` through to `source.retry()`. Spec name pending.
  - Open: animate the show/hide transition with `AnimatedVisibility` on the consumer side once the wiring lands and a designer signs off on the cross-fade duration.
  - Open: localise the three copy strings. Out of scope for #200; deferred until the codebase grows its first `<plurals>` / non-English resource pass.
  - Open: a11y review on the `Offline` retry affordance — `clickable(role = Role.Button, onClickLabel = "Retry connection")` is the obvious hook; not in AC.
