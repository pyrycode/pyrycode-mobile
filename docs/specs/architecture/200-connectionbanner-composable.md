# Spec — #200: ConnectionBanner composable

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionState.kt:1-13` — the sealed model the banner consumes. Four variants: `Connected` (object), `Connecting` (object), `Reconnecting(secondsRemaining: Int)` (data class), `Offline` (object). Already shipped in #196.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:1-291` — closest sibling pattern. Mirror its structure exactly: top-level public composable, private `*Content` composable that takes plain state + lambdas (so previews can drive it without `rememberSaveable`), private layout constants block, private preview data, `*PreviewMatrix` composable, and the paired Light + Dark `@Preview` entries wired through `PyrycodeMobileTheme(darkTheme = ...)` + `Surface`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` (first ~80 lines) — second reference for the components/ idiom; confirms `clickable` usage and `Modifier.fillMaxWidth()` row shape.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — confirm which Material 3 color slots are exposed on `MaterialTheme.colorScheme` (we rely only on standard M3 slots; `WarningColors.kt` is intentionally out of scope for this slice).
- `docs/specs/architecture/196-connectionstate-model-stub-source.md` — sibling spec for the data-layer slice; cross-reference the model contract that this banner renders.

## Design source

N/A — per ticket body, the Figma file has no dedicated `ConnectionBanner` state nodes yet. Visual treatment for the three disconnected states is implementation-decided (M3 tokens noted in § Design below) until a future design pass adds explicit banner nodes. The thread-frame placement (`16:8`) belongs to the follow-up wiring slice, not this one.

## Context

#196 landed the `ConnectionState` sealed model and a stub source. This slice (#200) ships the pure UI surface that renders that state with the four agreed product copy strings. No `ThreadViewModel` or `ConnectionStateSource` wiring happens here — the consumer in `ThreadScreen` (and the source binding) lands in the follow-up slice split from #197.

The goal is a small, drop-in composable a future caller can place at the top of any screen by passing `(state, onRetry)`. Keeping it stateless and event-driven means the follow-up only has to lift state via `collectAsStateWithLifecycle` and pipe a lambda — no refactor of this file.

## Design

### Public API

Single new file: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt`.

Top-level composable signature (the contract — do not widen it):

```kotlin
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
)
```

### Internal shape

Mirror `ToolCallRow.kt`'s split:

- `ConnectionBanner(...)` — public entry; performs the `Connected` short-circuit (return early with no composition), then delegates to a private `ConnectionBannerContent` for the visible states. No `remember*` state lives here — the banner is stateless; the caller owns `state`.
- `private fun ConnectionBannerContent(state: ConnectionState, onClick: () -> Unit, modifier: Modifier)` — the visible row. Takes the already-resolved tap handler so the preview matrix can drive it directly without needing to thread `onRetry` through preview data.
- Layout primitive: a single `Surface` filling width, with the chosen container color, wrapping a `Row` (or `Box`) that holds a single `Text` line with the product copy. Vertical padding ~12.dp, horizontal ~16.dp — pick concrete values via private `val` constants at the top of the file (e.g. `BannerVerticalPadding`, `BannerHorizontalPadding`) matching the constants-block idiom in `ToolCallRow.kt:40-47`.
- `Modifier.clickable(...)`: applied unconditionally on the `Surface`, but the `onClick` lambda the parent passes is itself either `onRetry` (for `Offline`) or a no-op `{}` (for `Connecting` / `Reconnecting`). The contract is "row ignores taps in `Connecting` / `Reconnecting`"; routing that through a no-op lambda is simpler than conditionally attaching `Modifier.clickable`, keeps the `Surface` shape uniform, and matches how `ToolCallRow.kt:82` always attaches `clickable`.

### State → copy + color mapping

Resolved inside `ConnectionBanner` (the public entry) before delegating to `ConnectionBannerContent`:

| `ConnectionState`              | Visible? | Copy string                              | Container color (M3 slot)               | Tap handler |
|--------------------------------|----------|------------------------------------------|------------------------------------------|-------------|
| `Connected`                    | No       | — (return early; zero height)            | —                                        | —           |
| `Connecting`                   | Yes      | `"Connecting…"`                          | `colorScheme.surfaceContainerHigh`       | `{}` (no-op)|
| `Reconnecting(secondsRemaining)` | Yes    | `"Reconnecting in ${secondsRemaining}s"` | `colorScheme.surfaceContainerHigh`       | `{}` (no-op)|
| `Offline`                      | Yes      | `"Offline — tap to retry"`               | `colorScheme.errorContainer`             | `onRetry`   |

Text color: pair with the container per M3 — `onSurface` on top of `surfaceContainerHigh`, `onErrorContainer` on top of `errorContainer`. Style: `MaterialTheme.typography.bodyMedium` (same body style ToolCallRow uses for its inline text).

Copy strings are hard-coded `String` literals for this slice (no `stringResource` indirection) — consistent with how other components in `ui/conversations/components/` carry inline strings today (e.g. ToolCallRow's `"Input"` / `"Output"` labels at `ToolCallRow.kt:131-132`). When the project introduces a string-resources pass, the banner will move alongside the rest; doing it ad-hoc here would be an out-of-scope refactor.

The em-dash in `"Offline — tap to retry"` is the actual character `—` (U+2014), not `--`. The Reconnecting interpolation uses the lowercase `s` suffix with no space (e.g. `"Reconnecting in 12s"`).

### Recomposition / stability

- `ConnectionState` is a sealed class with `data object` and `data class` cases — all stable, safe to pass directly.
- `onRetry` is a lambda parameter; if the caller passes a non-`remember`ed lambda, Compose will recompose on every parent recomposition. The banner doesn't need to defend against this (the caller is responsible) — but document via no special handling here; the contract is "stable lambda preferred."
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`. Pure projection.

### Concurrency

None. This slice does not touch coroutines, flows, dispatchers, or `viewModelScope`. The future wiring slice will collect the source's `StateFlow<ConnectionState>` via `collectAsStateWithLifecycle` and pass the resulting `State<ConnectionState>` value into this banner.

### Error handling

None at this layer. The banner *displays* error-shaped UI for `Offline` but does not handle errors itself; the retry action is delegated to the caller via `onRetry`.

## Testing strategy

This slice ships **previews only**, no unit or instrumented tests.

Rationale:
- The component's logic is a 4-way `when` over a sealed type producing two `String` constants, one interpolated string, and one M3 color selection. There's no branching, async behaviour, or state machine to assert.
- Sibling components in `ui/conversations/components/` (ToolCallRow, ConversationRow, MessageBubble, DiscussionPreviewRow, ArchiveRow) ship preview-only as well — the visual variants are the contract; the Light + Dark preview matrix is the regression artifact.
- The `onRetry` gating is structural (the no-op lambda for non-Offline states is set in the same `when` block as the copy resolution); a unit test would be asserting the implementation against itself.

The follow-up wiring slice (the one that places the banner in `ThreadScreen`) will own the integration test that asserts "tapping the banner in `Offline` triggers retry"; pre-staging that test here would couple this slice to a not-yet-existing consumer.

### Preview matrix (the deliverable per AC-4)

A `private @Composable ConnectionBannerPreviewMatrix()` stacks all four states in a `Column`, then two `@Preview`-annotated wrappers render it in Light and Dark via `PyrycodeMobileTheme(darkTheme = ...)` + `Surface`. Same shape as `ToolCallRow.kt:232-291`. The four entries:

1. `state = ConnectionState.Connected` — establishes the zero-height baseline; visually nothing renders between the previous and next entry, which is the AC-1 assertion.
2. `state = ConnectionState.Connecting`
3. `state = ConnectionState.Reconnecting(secondsRemaining = 12)` — pick a representative `Int`; 12 is fine.
4. `state = ConnectionState.Offline`

Each entry calls the **public** `ConnectionBanner(state, onRetry = {})` (not `ConnectionBannerContent`) — the preview is the regression artifact for the public-entry short-circuit on `Connected`, so it must exercise the public surface.

`@Preview` annotation parameters: `widthDp = 412`, `showBackground = true`, and `uiMode = Configuration.UI_MODE_NIGHT_YES` on the dark variant — copy the exact annotation shape from `ToolCallRow.kt:268-283`.

## Acceptance check — spec → AC mapping

- AC-1 (file location, signature, zero height when `Connected`) — public composable in `ui/conversations/components/ConnectionBanner.kt` with the signature in § Public API; § Design says the public entry returns early on `Connected` with no composition (zero height).
- AC-2 (exact product copy for `Connecting` / `Reconnecting` / `Offline`, interpolation of `secondsRemaining`) — § Design table fixes the literal strings and the interpolation shape (`"Reconnecting in ${secondsRemaining}s"`).
- AC-3 (clickable; `onRetry` invoked only on `Offline`; `Connecting` / `Reconnecting` ignore taps) — § Design routes either `onRetry` or `{}` into `ConnectionBannerContent`'s `onClick`; the `Surface` is always `clickable`.
- AC-4 (preview file renders all four states) — § Testing strategy → Preview matrix enumerates the four entries with their concrete state values.

## Open questions

None. The four product copy strings, the model contract, and the layout idiom are all fixed by the ticket body, #196, and the existing `ui/conversations/components/` files.
