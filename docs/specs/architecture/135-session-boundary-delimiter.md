# 135 — `SessionBoundaryDelimiter` composable

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:120-153` — `ThreadItem` sealed interface, `SessionBoundary` data class, `BoundaryReason` enum. **Do not modify**; this is the input contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt` — closest sibling component: small Material 3 surface, single-state copy table, light+dark previews. Mirror its file shape (constants → public `@Composable` → private content `@Composable` → preview matrix → preview wrappers).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:69-95` — project's established `LocalUriHandler.current` → private worker pattern. Adopt the same shape so the URL-opening side effect is testable via `CompositionLocalProvider(LocalUriHandler provides ...)`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatar.kt` and `app/src/test/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatarTest.kt` — project convention: pure helpers exposed as `internal` and JVM-tested under `src/test/`, composable behaviour tested under `src/androidTest/` with `createComposeRule()`. Apply the same split.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerTest.kt` — ComposeRule + `PyrycodeMobileTheme` test idiom. Mirror the setup.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — confirm `MaterialTheme.colorScheme.outlineVariant` (divider) and `onSurfaceVariant` (secondary text) tokens are present; do not introduce new theme tokens.
- `gradle/libs.versions.toml` — `kotlinx-datetime` is already a dependency. No new deps needed; the time formatter uses `java.time.format.DateTimeFormatter` from the JDK + the existing `Instant.toLocalDateTime(TimeZone)` interop in `kotlinx-datetime`.

## Context

`ThreadItem.SessionBoundary` markers are produced by `ConversationRepository.observeMessages` whenever Claude's context resets mid-thread (`/clear`, workspace change, idle eviction). The thread `LazyColumn` will render these markers inline; this ticket ships the **rendering** of one marker as a standalone composable. Wiring the composable into `ThreadScreen`'s `LazyColumn` is out of scope (a future ticket — the current `ThreadScreen` body still has `items(emptyList<Unit>())` as a placeholder).

The `[Install]` affordance opens a placeholder docs URL through `LocalUriHandler` in Phase 1–2. The button itself ships now; replacing the URL with a Phase 3+ deep link is a single-line constant swap in the future.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-36

The referenced node is only the centered label text (`Workspace changed to ~/Workspace/Projects/KitchenClaw — 2 hours ago`), rendered in Roboto Mono 12sp / 16sp line-height / `Schemes/on-surface-variant` (`#42474e` light). Map to: `MaterialTheme.typography.labelSmall` (or equivalent 12sp body style) in `MaterialTheme.colorScheme.onSurfaceVariant`. The horizontal rule, the explanatory line below it, and the `[Install]` `TextButton` have no separate Figma nodes; implement them per the textual spec below.

**Important divergence from the Figma sample text:** the Figma label shows relative time (`— 2 hours ago`); the AC requires **locale short time** (`— 14:32`). The AC is canonical — use the time format described in the **Time formatting** section below, not relative time. Do not copy `formatRelativeTime` from `RelativeTime.kt`.

## Design

### File layout

Create one new production file:

```
app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt
```

Public surface:

```kotlin
@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
)
```

Internal (visible-for-test) surface in the same file:

```kotlin
internal const val MEMORY_PLUGIN_DOCS_URL: String = "https://pyryco.de/docs/memory-plugins"

internal fun boundaryLabel(
    boundary: ThreadItem.SessionBoundary,
    timeZone: TimeZone,
    locale: Locale,
): String

internal fun formatShortTime(
    instant: Instant,
    timeZone: TimeZone,
    locale: Locale,
): String

@Composable
internal fun SessionBoundaryDelimiterContent(
    boundary: ThreadItem.SessionBoundary,
    uriHandler: UriHandler,
    modifier: Modifier = Modifier,
)
```

The public composable resolves `TimeZone.currentSystemDefault()`, default `Locale`, and `LocalUriHandler.current`, then delegates to `SessionBoundaryDelimiterContent`. Keeping the worker injectable mirrors `MarkdownText` (`uriHandler` parameter on private composables) and makes the click → URL side effect assertable without `LocalUriHandler` mocking gymnastics in tests.

### Layout

A `Column` filling the available width, vertically padded (use the same vertical rhythm as `MessageRowVerticalSpacing` in `MessageBubble.kt` — `12.dp` top + bottom feels right; pick by reading the file):

1. `HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)` (M3 divider — accept the default `thickness` of `1.dp` and default horizontal insets).
2. Centered `Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`, where `label` is the output of `boundaryLabel(...)`. Apply a small top spacer (`8.dp`) above the label.
3. `FlowRow` (or `Row` with `Arrangement.Center`) containing the explanatory text **and** the `[Install]` `TextButton` on the same flow line so the button reads as inline within the sentence:
   - `Text("Claude doesn't remember messages above this line. Install a memory plugin to preserve context. ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`
   - `TextButton(onClick = { uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) }, contentPadding = PaddingValues(0.dp)) { Text("Install") }`

Note on the wrap behaviour: a plain `Row` does not wrap. Use `androidx.compose.foundation.layout.FlowRow` with `horizontalArrangement = Arrangement.Center` so the long explanatory sentence wraps cleanly on narrow widths and the `Install` button reflows with the trailing text. If the developer finds the inline placement awkward at preview time, the acceptable fallback is a `Column` with the `TextButton` centered on its own line below the explanatory `Text` — record the chosen variant by ensuring the preview matrix renders both narrow (`widthDp = 320`) and standard (`widthDp = 412`) widths without overflow.

### Reason → label mapping

`boundaryLabel(boundary, timeZone, locale)` computes:

| `boundary.reason`               | Label                                                    |
| ------------------------------- | -------------------------------------------------------- |
| `BoundaryReason.Clear`          | `"New session — ${time}"`                                |
| `BoundaryReason.WorkspaceChange`| `"Workspace changed to ${boundary.workspaceCwd} — ${time}"` |
| `BoundaryReason.IdleEvict`      | `"Idle session ended — ${time}"`                         |

where `time = formatShortTime(boundary.occurredAt, timeZone, locale)`.

The `WorkspaceChange` branch treats `boundary.workspaceCwd!!` as guaranteed non-null per the repository invariant (see `ConversationRepository.kt:135-142`). Use the not-null assertion (`!!`) — do not silently fall back to an empty string, because that would mask repository bugs.

### Time formatting

```kotlin
internal fun formatShortTime(
    instant: Instant,
    timeZone: TimeZone,
    locale: Locale,
): String {
    val localTime = instant.toLocalDateTime(timeZone).time.toJavaLocalTime()
    return java.time.format.DateTimeFormatter
        .ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        .withLocale(locale)
        .format(localTime)
}
```

- `Instant`/`toLocalDateTime(TimeZone)`/`.time` come from `kotlinx.datetime`.
- `.toJavaLocalTime()` is the `kotlinx-datetime` ↔ `java.time` interop extension (already on classpath via `kotlinx-datetime`'s JVM target).
- `FormatStyle.SHORT` renders `14:32` for `de_DE` / `en_GB` / 24-hour locales and `2:32 PM` for 12-hour locales. The AC's `14:32` example is illustrative; either form is correct per locale.

Minimum SDK is 33 per `CLAUDE.md`, so `java.time` is available without desugaring.

### State, concurrency, lifecycle

Stateless composable. No `remember`, no `LaunchedEffect`, no coroutine scope. The button click handler is synchronous — `UriHandler.openUri` returns immediately.

### Error handling

None at this layer. The repository invariant guarantees `workspaceCwd != null` for `WorkspaceChange`; `!!` will fail-fast if that invariant is ever violated, which is the correct behaviour (better than a silently-empty label hiding a regression). `UriHandler.openUri` may throw `IllegalArgumentException` for malformed URIs, but `MEMORY_PLUGIN_DOCS_URL` is a compile-time constant — no runtime exposure.

### Previews

A single `@Composable` `SessionBoundaryDelimiterPreviewMatrix` that renders all three variants stacked in a `Column` (each with `Arrangement.spacedBy(16.dp)`), wrapped in `Surface` so background tokens resolve. Drive it from two `@Preview` entries, each calling the matrix:

- `@Preview(name = "SessionBoundaryDelimiter — Light", showBackground = true, widthDp = 412)`
- `@Preview(name = "SessionBoundaryDelimiter — Dark", showBackground = true, widthDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES)`

Fixture boundaries (use a fixed `Instant` like `Instant.parse("2026-05-17T14:32:00Z")` so previews are deterministic):

- `Clear` boundary, `workspaceCwd = null`.
- `WorkspaceChange` boundary, `workspaceCwd = "~/Workspace/Projects/KitchenClaw"` (matches the Figma example for visual parity).
- `IdleEvict` boundary, `workspaceCwd = null`.

## Testing strategy

Two new test files. Each is short.

### JVM unit test (`src/test/`)

`app/src/test/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiterTest.kt`

Covers the pure helpers. Use a fixed `TimeZone` (`TimeZone.of("Europe/Berlin")`) and `Locale.GERMANY` so output is deterministic across CI environments.

Scenarios:

- `boundaryLabel` returns `"New session — 16:32"` for a `Clear` boundary whose `occurredAt` is `2026-05-17T14:32:00Z` (UTC+2 in Berlin → `16:32`).
- `boundaryLabel` returns `"Workspace changed to ~/Workspace/Projects/KitchenClaw — 16:32"` for a `WorkspaceChange` boundary with that `workspaceCwd`.
- `boundaryLabel` returns `"Idle session ended — 16:32"` for an `IdleEvict` boundary.
- `boundaryLabel` throws `NullPointerException` (the `!!` site) when reason is `WorkspaceChange` and `workspaceCwd` is `null` — pin the fail-fast contract so future "let's add a fallback" drift is caught by tests.
- `formatShortTime` with `Locale.US` and the same instant returns `"4:32 PM"` (or `"4:32 PM"` form per the JDK's `FormatStyle.SHORT` for `en_US`) — covers that the locale parameter is honoured. If the developer finds the exact JDK output differs slightly across JDK versions (e.g. with vs. without a thin space, or `PM` vs `p.m.`), assert with a regex like `Regex("""4:32\s?PM""")` rather than chasing a literal.

### ComposeRule test (`src/androidTest/`)

`app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiterTest.kt`

Use `createComposeRule()`, wrap in `PyrycodeMobileTheme`. To exercise the URL side effect, inject a capturing `UriHandler` via `CompositionLocalProvider(LocalUriHandler provides ...)` and call the **public** `SessionBoundaryDelimiter` (this also asserts that the public composable correctly threads `LocalUriHandler` through to the worker):

```kotlin
val opened = mutableListOf<String>()
val capturing = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
composeTestRule.setContent {
    PyrycodeMobileTheme {
        CompositionLocalProvider(LocalUriHandler provides capturing) {
            SessionBoundaryDelimiter(boundary = clearBoundaryFixture)
        }
    }
}
```

Scenarios:

- Renders the explanatory sentence `"Claude doesn't remember messages above this line. Install a memory plugin to preserve context."` for a `Clear` fixture, and an `Install` button.
- Renders a label that starts with `"New session — "` for `Clear`. (Match by `hasText(..., substring = true)`; do not assert the exact time string — the device locale on the emulator may format differently than the dev's machine.)
- Renders a label that starts with `"Workspace changed to ~/Workspace/Projects/KitchenClaw — "` for `WorkspaceChange`.
- Renders a label that starts with `"Idle session ended — "` for `IdleEvict`.
- Tapping the `Install` button appends `MEMORY_PLUGIN_DOCS_URL` to the captured `opened` list. Assert exactly one entry.

Do not write a snapshot/golden test; the project doesn't ship one and Compose preview screenshots are the human-visual check.

## Open questions

- **`Install` button placement.** Spec calls for an inline `FlowRow`-based layout. If preview reveals visual awkwardness at typical phone widths, the spec sanctions a Column fallback. The developer picks at preview time — no second architect round.
- **Placeholder URL value.** `https://pyryco.de/docs/memory-plugins` is chosen because `pyryco.de` is already used as a link host in the `MARKDOWN_PREVIEW_FIXTURE` in `MessageBubble.kt`. If the developer discovers a more canonical placeholder during implementation (e.g. an actual published docs page), substituting it is a one-line constant change and does not require re-spec.

## Out of scope

- Wiring `SessionBoundaryDelimiter` into `ThreadScreen`'s `LazyColumn`. The current `ThreadScreen` still has an `items(emptyList<Unit>())` placeholder; integration with `ThreadViewModel`'s `Flow<List<ThreadItem>>` is its own ticket.
- Above-delimiter opacity treatment (#136).
- Replacing the placeholder URL with a Phase 3+ deep link.
