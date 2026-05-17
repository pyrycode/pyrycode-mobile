# SessionBoundaryDelimiter

Stateless composable (#135) that renders a single [`ThreadItem.SessionBoundary`](./conversation-repository.md) marker as a horizontal-rule delimiter inside the thread `LazyColumn`. Surfaces the **reason** the session reset (`Clear` / `WorkspaceChange` / `IdleEvict`, defined in #3 and authored on the marker since #192) and an inline `Install` affordance pointing at the memory-plugin docs URL via `LocalUriHandler`. The boundary marker itself is produced by `ConversationRepository.observeMessages` whenever Claude's context resets mid-thread; this component is the rendering half.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt`). Sibling of [`MessageBubble`](./message-bubble.md), [`ToolCallRow`](./tool-call-row.md), [`ConnectionBanner`](./connection-banner.md). Figma reference: [`16:36`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-36) — the centered label only; the rule, explanation, and `Install` button have no separate Figma nodes and are implemented per the textual spec.

## Shape

```kotlin
@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
)
```

Two params, no defaults on the load-bearing one. Stateless — no `remember`, no `LaunchedEffect`, no coroutine scope. The public composable resolves `TimeZone.currentSystemDefault()`, default `Locale`, and `LocalUriHandler.current`, then delegates to the file-internal `SessionBoundaryDelimiterContent(boundary, uriHandler, modifier)` worker. The injectable-`UriHandler` split mirrors [`MarkdownText`](./markdown-text.md)'s pattern and lets the androidTest exercise the click → URL side effect without `LocalUriHandler` mocking gymnastics.

## What it does

A `Column(fillMaxWidth().padding(vertical = DelimiterVerticalPadding = 12.dp))` containing, in order:

1. **`HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)`** — M3 divider at default `1.dp` thickness, no horizontal inset.
2. **8.dp `Spacer` + centered `Text(label, labelSmall, onSurfaceVariant)`** — the reason-specific label, padded `horizontal = 16.dp`. `label` is the output of the file-internal `boundaryLabel(boundary, timeZone, locale)` helper (see [Reason → label mapping](#reason--label-mapping) below).
3. **4.dp `Spacer` + `FlowRow(horizontalArrangement = Arrangement.Center)`** — explanatory sentence and the `Install` `TextButton` on the same flow line so the button reads as inline within the sentence; `FlowRow` (not `Row`) is the load-bearing choice so the sentence wraps cleanly at narrow widths and the button reflows with the trailing text. The text is `"Claude doesn't remember messages above this line. Install a memory plugin to preserve context. "` (`bodySmall` / `onSurfaceVariant` / centered), trailed by `TextButton(onClick = { uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) }, contentPadding = PaddingValues(0.dp)) { Text("Install") }`.

The above-delimiter opacity treatment (de-emphasizing messages above the line) is **not** in this component — it's the surface responsibility of #136.

### Reason → label mapping

`internal fun boundaryLabel(boundary, timeZone, locale): String` returns one of:

| `boundary.reason`               | Label                                                    |
| ------------------------------- | -------------------------------------------------------- |
| `BoundaryReason.Clear`          | `"New session — $time"`                                  |
| `BoundaryReason.WorkspaceChange`| `"Workspace changed to ${boundary.workspaceCwd!!} — $time"` |
| `BoundaryReason.IdleEvict`      | `"Idle session ended — $time"`                           |

where `$time = formatShortTime(boundary.occurredAt, timeZone, locale)`. The em-dash is the literal U+2014 character, not `--`.

**`workspaceCwd!!` is intentional, not a bug.** The repository invariant (`ConversationRepository.kt:135-142`, asserted in `FakeConversationRepositoryTest` since #192) guarantees `workspaceCwd != null` iff `reason == WorkspaceChange`. The not-null assertion fail-fasts if that invariant is ever violated — a silent empty-string fallback would mask repository regressions. JVM unit test `boundaryLabel fails fast when WorkspaceChange has null cwd` pins the contract (`@Test(expected = NullPointerException::class)`).

### Time formatting

`internal fun formatShortTime(instant, timeZone, locale): String` uses `kotlinx-datetime` for the timezone conversion and `java.time` for the locale-aware formatter:

```kotlin
val localTime = instant.toLocalDateTime(timeZone).time.toJavaLocalTime()
return DateTimeFormatter
    .ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(locale)
    .format(localTime)
```

`FormatStyle.SHORT` renders `"16:32"` on 24-hour locales (`de_DE`, `en_GB`) and `"4:32 PM"` on 12-hour locales (`en_US`). The AC's `"14:32"` example is illustrative — either form is correct per the device locale. JDK builds differ on the AM/PM separator (regular space vs. narrow no-break space U+202F), so the JVM unit test asserts `result.contains("4:32") && result.contains("PM")` separately rather than chasing a literal glyph. **Don't import `formatRelativeTime` from `RelativeTime.kt`** — the Figma sample shows relative time (`— 2 hours ago`) but the AC is canonical and calls for locale short time.

`Min SDK 33` (per `CLAUDE.md`) means `java.time` is on the platform classpath without desugaring; no `coreLibraryDesugaring` dependency needed.

## Memory-plugin docs URL

```kotlin
internal const val MEMORY_PLUGIN_DOCS_URL: String = "https://pyryco.de/docs/memory-plugins"
```

File-internal `const val`, visible to tests so the androidTest can assert the exact URL is forwarded to `UriHandler.openUri`. **Placeholder for Phase 1–2** — `pyryco.de` is the host already used in `MARKDOWN_PREVIEW_FIXTURE` (`MessageBubble.kt`), so the choice is consistent with existing fixtures without committing to a real published page. Phase 3+ will replace this with a deep link into the plugin install flow; that's a one-line constant swap and does **not** change this component's shape or tests (other than the literal in the URL-forwarding assertion).

## Spacing constants

Four file-private `val`s at the top of `SessionBoundaryDelimiter.kt`:

```kotlin
private val DelimiterVerticalPadding = 12.dp
private val DelimiterHorizontalPadding = 16.dp
private val LabelTopSpacing = 8.dp
private val ExplanationTopSpacing = 4.dp
```

No raw `.dp` literal at any call site inside the worker. Same named-constants posture as [`MessageBubble`](./message-bubble.md) / [`ToolCallRow`](./tool-call-row.md) / [`ConnectionBanner`](./connection-banner.md). The 12.dp vertical rhythm mirrors `MessageRowVerticalSpacing` in `MessageBubble.kt`. No cross-file dedup yet — these are layout-specific to the delimiter; promote to a shared `Spacing.kt` peer file only when a third component declares the same value.

## Recomposition / stability

- `ThreadItem.SessionBoundary` is a `data class` with `kotlinx.datetime.Instant` + nullable `String` fields — all stable.
- `UriHandler` is a Compose-platform interface; the public composable reads it from `LocalUriHandler.current` at composition time. No `remember` needed — the local is stable across recompositions.
- No internal mutable state, no side-effecting handlers beyond the synchronous `uriHandler.openUri` call inside `TextButton.onClick`.

## Configuration

- **No new dependencies.** `kotlinx-datetime` was already on the classpath (its `.toJavaLocalTime()` interop extension is what reaches `java.time`); `java.time.format.DateTimeFormatter` / `FormatStyle` ship with the JDK on min SDK 33.
- **No new string resources.** Literals only — same posture as the rest of `ui/conversations/components/` today.
- **No theme tokens added.** Uses existing `MaterialTheme.colorScheme.outlineVariant` (divider) and `onSurfaceVariant` (label + explanation), and existing `MaterialTheme.typography.labelSmall` / `bodySmall` slots.

## Preview

Three `@Preview` entries, all delegating to a shared private `SessionBoundaryDelimiterPreviewMatrix()` that stacks the three boundary fixtures (`Clear` / `WorkspaceChange` / `IdleEvict`) in a `Column(Arrangement.spacedBy(16.dp))` wrapped in `PyrycodeMobileTheme { Surface { ... } }`:

- `"SessionBoundaryDelimiter — Light"` — `widthDp = 412`, light theme.
- `"SessionBoundaryDelimiter — Dark"` — `widthDp = 412`, `uiMode = UI_MODE_NIGHT_YES`.
- `"SessionBoundaryDelimiter — Narrow"` — `widthDp = 320`, light theme. Exists specifically to verify `FlowRow` wraps cleanly at narrow widths without overflow.

Fixtures use a fixed `Instant.parse("2026-05-17T14:32:00Z")` so previews are deterministic. The `WorkspaceChange` fixture uses `workspaceCwd = "~/Workspace/Projects/KitchenClaw"` to match the Figma example for visual parity.

## Edge cases / limitations

- **Not wired into `ThreadScreen` yet.** The current `ThreadScreen` `LazyColumn` body is still `items(emptyList<Unit>())` — integration with `ThreadViewModel`'s `Flow<List<ThreadItem>>` is its own ticket. This component ships ready-to-drop-in; a future ticket adds the `items(items = threadItems) { item -> when (item) { is ThreadItem.MessageItem -> MessageBubble(item.message); is ThreadItem.SessionBoundary -> SessionBoundaryDelimiter(item) } }` switch.
- **`WorkspaceChange` with `null` `workspaceCwd` throws `NullPointerException`.** Intentional fail-fast on a repository invariant violation; see [Reason → label mapping](#reason--label-mapping). Pinned by JVM unit test.
- **Time format varies by device locale.** `FormatStyle.SHORT` is locale-aware; rely on `boundary.occurredAt` and the device's `TimeZone.currentSystemDefault()` / default `Locale`. Tests assert label prefix substrings (`"New session — "` etc.) rather than the exact rendered time, because the emulator and the dev's host JDK may format the same instant slightly differently.
- **`Install` button is always present, on every variant.** Even when the explanatory copy could feel redundant (e.g. user just did `/clear` deliberately), the AC requires the line under every variant. Showing/hiding by reason was considered and explicitly out of scope.
- **No animation.** The delimiter appears/disappears with the underlying list update; no `AnimatedVisibility`. Acceptable for Phase 0.
- **No a11y review.** The `Install` button inherits `TextButton`'s default semantics (`role = Role.Button`); no `onClickLabel` is set on the `TextButton`. Same open thread as [`ConnectionBanner`](./connection-banner.md)'s retry affordance — tracked there.

## Related

- Ticket notes: [`../codebase/135.md`](../codebase/135.md)
- Spec: `docs/specs/architecture/135-session-boundary-delimiter.md`
- Upstream:
  - [`#3`](../codebase/3.md) — `ThreadItem` / `SessionBoundary` / `BoundaryReason` definitions; the input contract this component consumes.
  - [`#9`](../codebase/9.md) — `buildThreadItems` projection that emits `SessionBoundary` markers between session-id deltas.
  - [`#192`](../codebase/192.md) — authored `reason` + `workspaceCwd` on the marker; without this ticket the delimiter could not distinguish the three variants. The seed channels now collectively exercise all three reasons so previews and integration tests have realistic data to render against.
- Sibling component patterns: [`MarkdownText`](./markdown-text.md) (injectable-`UriHandler` worker pattern for testable URL side effects), [`ConnectionBanner`](./connection-banner.md) (stateless row primitive in the same package, file shape `constants → public @Composable → private content → preview matrix → preview wrappers`), [`MessageBubble`](./message-bubble.md) / [`ToolCallRow`](./tool-call-row.md) (file-private spacing constants).
- Downstream / follow-ups:
  - Open: wire into `ThreadScreen`'s `LazyColumn` once `ThreadViewModel` exposes `Flow<List<ThreadItem>>`. The integration is a single `when`-arm over `ThreadItem`.
  - Open: above-delimiter opacity treatment ([#136](https://github.com/pyrycode/pyrycode-mobile/issues/136)). Renders messages above the line at reduced alpha so the visual hierarchy matches the "Claude doesn't remember" message. Coordinates with this component but lives at the surface (`ThreadScreen` / `LazyColumn` item modifiers), not in this file.
  - Open: replace `MEMORY_PLUGIN_DOCS_URL` with the Phase 3+ deep link into the plugin install flow. One-line constant swap.
  - Open: a11y review on the `Install` affordance (`role = Role.Button` is default; `onClickLabel = "Open memory plugin docs"` is the obvious hook).
