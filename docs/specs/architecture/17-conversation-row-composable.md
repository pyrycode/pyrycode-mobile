# Spec — ConversationRow Composable (#17)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — confirms field set; spec consumes `name: String?`, `isPromoted: Boolean`, `lastUsedAt: Instant`. Other fields (`cwd`, `currentSessionId`, `sessionHistory`, `id`) are out of scope for this row.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — confirms `content: String` is the field rendered in the preview slot. `role`, `timestamp`, `isStreaming` are not consumed by this ticket.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` — canonical pattern in this repo for a stateless composable + dual `@Preview` (light/dark, both wrapped in `PyrycodeMobileTheme`). New file mirrors the preview shape exactly (lines 104–124).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt:257-279` — `PyrycodeMobileTheme` signature; both previews wrap here.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography = Typography()` is the bare M3 default; all `MaterialTheme.typography.*` slots resolve to Material 3 defaults. Do not hardcode font sizes/weights/families — rely entirely on typography slots.
- `gradle/libs.versions.toml` — Compose BOM `2026.02.01`, Material 3 (`androidx-compose-material3`), preview tooling, and `kotlinx-datetime` `0.6.2` are already wired. **No dependency changes needed** for this ticket.
- `docs/specs/architecture/7-welcome-screen-scaffold.md` — sibling spec the developer agent has already implemented; same package conventions, same preview shape, same "import only M3 + foundation + runtime + ui + theme" discipline.
- `docs/specs/architecture/2-conversation-session-message-data-classes.md` — context on the model the row consumes (background; not a hard prerequisite read).

## Context

First UI primitive consuming the `Conversation` + `Message` model from #2. The row is the shared building block for the Channel list (#18) and the Discussions drilldown (later). Its shape sets the pattern for sibling decorations (workspace label #19, sleeping-session indicator #20), so the spec is deliberately conservative — show exactly what the AC demands and leave deliberate seams for the follow-ons.

This ticket wires no consumers; the row stands alone in its file, exercised only by two `@Preview` composables and `./gradlew assembleDebug`. #18 will be the first consumer.

## Design

### File

Create one file: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt`.

The directory chain `ui/conversations/components/` does not yet exist — let the package declaration create it; the build picks up new files under `app/src/main/java/` automatically.

**Do not** create a separate `RelativeTime.kt` or extract the timestamp formatter into its own module-internal helper. Inline as a `private` top-level function in the same file. Rationale: YAGNI — #18 reuses `ConversationRow` itself, not the formatter; if a later ticket needs the formatter for an unrelated surface (e.g. thread message timestamps), that ticket extracts. Keeping it private here also keeps the file count at 1 and avoids drifting into a 2-file ticket.

### Public surface

```kotlin
@Composable
fun ConversationRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Matches the technical-notes suggestion verbatim. The `modifier: Modifier = Modifier` parameter is **required** here (unlike `WelcomeScreen`, where it was rejected) — this is a reusable row that will sit inside a `LazyColumn` in #18, and consumers will pass `Modifier.fillMaxWidth()` and/or `Modifier.animateItemPlacement()`. Compose convention for reusable list rows is to accept a `modifier`.

### Layout — use Material 3 `ListItem`

```kotlin
ListItem(
    modifier = modifier.clickable(onClick = onClick),
    headlineContent = { Text(displayName) },
    supportingContent = lastMessage?.let { msg ->
        {
            Text(
                text = previewText(msg.content),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    },
    trailingContent = {
        Text(text = formatRelativeTime(conversation.lastUsedAt))
    },
)
```

Why `ListItem` (`androidx.compose.material3.ListItem`):

- Built-in M3 typography hierarchy: `headlineContent` resolves to `titleMedium`-equivalent emphasis, `supportingContent` to `bodyMedium`, `trailingContent` to `labelSmall` — exactly the visual hierarchy this row wants. **Do not override** with explicit `style =` arguments; the M3 defaults are the contract.
- Built-in M3 color tokens: headline uses `onSurface`, supporting + trailing use `onSurfaceVariant`. **Do not pass a `colors =` argument** — defaults are correct.
- Built-in height behavior: when `supportingContent` is `null`, `ListItem` collapses to its 1-line height (~56dp) instead of the 2-line height (~72dp). This satisfies AC3 ("the preview slot collapses … no placeholder text, no layout shift relative to the with-message case beyond the missing preview line") **for free** — do not add `Spacer`, do not reserve vertical space, do not branch on null with a custom `Row`/`Column` tree.
- Built-in accessibility: `Modifier.clickable` sets `Role.Button` and a default ripple; `ListItem` adds the appropriate touch target sizing. No `Modifier.semantics { ... }` override is needed; the rendered `Text` children are read aloud naturally by TalkBack.

Pass `supportingContent` as `lastMessage?.let { … {} }` so the parameter is genuinely `null` when there is no message — that's what triggers `ListItem`'s height collapse. **Do not** pass `supportingContent = { if (lastMessage != null) Text(...) else null }` — that's an always-non-null lambda emitting either a `Text` or nothing, which `ListItem` still treats as "supporting content present" and reserves vertical space for, breaking AC3.

The `onClick` lambda is invoked exactly once per tap by `Modifier.clickable` — that's the standard Compose contract and satisfies AC5 with no special handling.

### Display name (AC4 — auto-name fallback)

Compute inside `ConversationRow` (a local `val`, not a separate helper):

```kotlin
val displayName = conversation.name?.takeIf { it.isNotBlank() }
    ?: if (conversation.isPromoted) "Untitled channel" else "Untitled discussion"
```

Architect's call on the fallback string:

- Branching on `isPromoted` discriminates the two UI tiers without leaking any other field. Channels are the persistent tier; discussions are the throwaway tier; this naming matches the CLAUDE.md vocabulary so the placeholder reads correctly to a Pyrycode user.
- Deterministic for a given conversation: depends only on `name` (stable) and `isPromoted` (stable). Does **not** depend on `lastUsedAt`, which mutates as the conversation is used — that's exactly the trap a "Discussion · {date}" fallback would fall into.
- `name?.takeIf { it.isNotBlank() }` treats `null`, `""`, and whitespace-only names equivalently as "unnamed" — the AC says "empty / null", and treating whitespace-only as named would be a silent footgun.
- **Do not** derive the fallback from `cwd` or its last path segment. #19 (workspace decoration) owns the workspace surface; duplicating cwd info in the name would collide with that ticket and create churn when #19 lands.
- **Do not** derive from `conversation.id` (UUID-shaped strings render hideously) or from the first 6/8 chars of the id (same problem, just shorter ugly).

### Preview text normalization

```kotlin
private fun previewText(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()
```

Collapses runs of whitespace (including embedded newlines) to single spaces. Without this, a message starting with `"\n\n# Heading\n\nBody…"` would burn its 2-line budget on blank lines before showing any text. The AC says "truncated to 1–2 lines with ellipsis" — the truncation works correctly only on a normalized single-line string.

`maxLines = 2` and `TextOverflow.Ellipsis` are the standard M3 idiom for the AC's "1–2 lines with ellipsis".

### Relative time helper (`formatRelativeTime`)

Inline as a `private` top-level function in `ConversationRow.kt`. Signature:

```kotlin
private fun formatRelativeTime(
    instant: Instant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String
```

Default `now` + `timeZone` parameters let the previews (and any future unit test) inject deterministic values without exposing the function publicly. Both `Clock.System` and `TimeZone.currentSystemDefault()` come from `kotlinx.datetime` (already in `libs.versions.toml`).

Bucket logic (in order — first match wins):

| Condition | Output |
|---|---|
| `(now - instant).isNegative()` (clock skew / future timestamp) | `"just now"` |
| `< 1 minute` | `"just now"` |
| `< 1 hour` | `"{inWholeMinutes}m ago"` |
| `< 24 hours` | `"{inWholeHours}h ago"` |
| `< 48 hours` | `"Yesterday"` |
| `< 7 days` | `"{inWholeDays}d ago"` |
| same calendar year (in `timeZone`) | `"May 5"` — `MonthNames.ENGLISH_ABBREVIATED` + day-of-month |
| otherwise | `"May 5, 2025"` — abbreviated month + day + year |

Uses kotlinx-datetime's `Duration` arithmetic (`now - instant`) and `LocalDate.Format` DSL for the date buckets:

```kotlin
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val sameYearFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    dayOfMonth()
}
private val crossYearFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    dayOfMonth()
    chars(", ")
    year()
}
```

The "Yesterday" bucket uses a duration threshold (< 48 hours) rather than a calendar-day comparison. This is the right call for relative-time displays — calendar-day "yesterday" produces awkward edge cases at midnight (a timestamp 30 seconds before midnight becomes "Yesterday" 31 seconds later, even though the user perceives it as "just now"). The 48-hour bucket is what e.g. Slack and Discord use in practice.

The function returns a plain `String`. Localization (i18n) of "just now" / "Yesterday" / "m ago" / etc. is **out of scope** for Phase 0 — keep the strings English literals; do not introduce `stringResource(...)` lookups. The CLAUDE.md "no walk-back triggers" rule for the data layer doesn't apply here (this is UI), but Phase 0 is explicitly scaffolding — i18n is a known later concern, not a now concern.

### Imports — what's allowed

- M3: `androidx.compose.material3.{ListItem, MaterialTheme}` (`MaterialTheme` is not strictly needed since we don't override colors/typography, but `import` it if you reference any `MaterialTheme.*` for the previews — otherwise omit).
- Foundation: `androidx.compose.foundation.clickable`.
- Runtime: `androidx.compose.runtime.Composable`.
- UI: `androidx.compose.ui.Modifier`, `androidx.compose.ui.text.style.TextOverflow`, `androidx.compose.ui.tooling.preview.Preview`, `androidx.compose.ui.unit.dp` (only if you use `dp` for the preview width/height).
- Data model: `de.pyryco.mobile.data.model.{Conversation, Message, Role}` (`Role` only in the previews, to construct the seed `Message`).
- kotlinx-datetime: the imports listed in the helper block above.
- Theme: `de.pyryco.mobile.ui.theme.PyrycodeMobileTheme` (previews only).
- Android (previews only): `android.content.res.Configuration` for the dark-mode preview.

**Forbidden:**

- `androidx.navigation.*` — this row knows nothing about routes. The consumer (#18) attaches navigation to `onClick`.
- `androidx.lifecycle.*`, `viewmodel`, `flow` — no state, no observation here.
- Direct `Color(0x...)` literals or imports from `ui.theme.Color.kt` — consume colors only via M3 default semantics on `ListItem` (which is exactly zero direct color references in this file).
- Hardcoded font sizes / weights / families — typography is the M3 default via `ListItem`'s built-in slot styling.
- `Locale`, `SimpleDateFormat`, `java.time.*` — use kotlinx-datetime, which is already wired and is what the rest of the data layer uses (per ADR `docs/knowledge/decisions/0001-kotlinx-datetime-for-data-layer.md`).

After writing, verify with `grep -E 'NavController|androidx\.navigation|java\.time|SimpleDateFormat|Color\(0x' app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` — must return empty.

## State + concurrency model

None. `ConversationRow` is pure: `(Conversation, Message?, () -> Unit, Modifier) → composition`. No `remember`, no `LaunchedEffect`, no `ViewModel`, no coroutines.

`formatRelativeTime` is a pure function (with default-injected `now`/`timeZone` for testability). It is **called during composition** at each recomposition of the row — this is fine for Phase 0 (no list yet, two previews only); when #18 wraps `ConversationRow` in a `LazyColumn`, recomposition triggers will be data-driven (new `lastUsedAt`, new `name`) and the formatter cost is trivial relative to text layout.

The displayed "{N}m ago" string is **frozen at composition time** — it does not update as wall-clock time advances. This is consistent with Slack/Discord/iMessage row behavior and is the correct trade-off for Phase 0. A self-updating "live" timestamp (via `produceState` + `delay`) is an explicit non-goal; if a future ticket needs it, it goes in #18 (the list container) or a dedicated decoration, not here.

Recomposition correctness: all parameters are stable. `Conversation` and `Message` are `data class`es over `String`/`Boolean`/`Instant` (all stable to Compose); `() -> Unit` is a lambda — callers in #18 will pass `remember`-stable lambdas (e.g. `{ navController.navigate(…) }` captured with `remember(id) { … }`). No action needed here.

## Error handling

N/A. The row consumes already-parsed model objects; there are no I/O, network, or parse paths.

Edge cases handled inline:

- `name = null` / `""` / whitespace → fallback to "Untitled {channel|discussion}".
- `lastMessage = null` → supporting slot collapses; row height shrinks.
- `lastMessage.content` with embedded newlines / leading whitespace → normalized to single spaces by `previewText`.
- `lastUsedAt` in the future (clock skew, bad seed data) → "just now" via the `isNegative()` guard.
- Very long `lastMessage.content` → `maxLines = 2` + `TextOverflow.Ellipsis` truncates.
- Very long `displayName` → `headlineContent` is a `Text` with no `maxLines` override; M3 `ListItem` default lets headline ellipsize at 1 line. **Do not** override; the default is correct and the AC does not pin headline truncation behavior.

## Testing strategy

The AC's only build gate is `./gradlew assembleDebug` (AC #7). The AC does not require unit or instrumented tests, and CLAUDE.md's "test-first" convention is bounded by the AC contract — for a pure visual component whose helper is `private` and whose behavior is fully observable through the two required previews, the previews are the test surface.

**Do not add:**

- A `ConversationRowTest.kt` using `ComposeTestRule`. `androidx-compose-ui-test-junit4` is in `libs.versions.toml` but has zero consumers in the repo; introducing it here would expand scope beyond the AC and is the kind of "adjacent refactor while you're there" CLAUDE.md explicitly bans.
- A `RelativeTimeTest.kt`. The helper is `private`; testing it would require changing visibility to `internal` (architectural smell — exposes a private implementation detail for testing alone). If a future ticket extracts the helper for reuse, that ticket adds the test.

### Required previews (AC6)

Both live at the bottom of `ConversationRow.kt`, marked `private`:

```kotlin
@Preview(name = "With message — Light", showBackground = true, widthDp = 412)
@Composable
private fun ConversationRowWithMessagePreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ConversationRow(
            conversation = previewConversation(name = "pyrycode-mobile", isPromoted = true),
            lastMessage = previewMessage("Refactored the conversation repository to expose a Flow."),
            onClick = {},
        )
    }
}

@Preview(
    name = "Without message — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConversationRowWithoutMessagePreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ConversationRow(
            conversation = previewConversation(name = null, isPromoted = false),
            lastMessage = null,
            onClick = {},
        )
    }
}

private fun previewConversation(name: String?, isPromoted: Boolean): Conversation =
    Conversation(
        id = "preview-1",
        name = name,
        cwd = "/Users/dev/projects/pyrycode-mobile",
        currentSessionId = "session-1",
        sessionHistory = emptyList(),
        isPromoted = isPromoted,
        lastUsedAt = Instant.parse("2026-05-12T10:00:00Z") - 2.hours, // → "2h ago" in the preview
    )

private fun previewMessage(content: String): Message =
    Message(
        id = "msg-1",
        sessionId = "session-1",
        role = Role.User,
        content = content,
        timestamp = Instant.parse("2026-05-12T08:00:00Z"),
        isStreaming = false,
    )
```

The AC requires "one with a `Message`, one with `null`" (AC #6). The light/dark split is a bonus consistent with `WelcomeScreen`'s pattern — exercises both color schemes without doubling the preview count. The `name = null` case in the second preview also exercises the auto-name fallback (AC #4), giving free coverage.

`previewConversation`'s `lastUsedAt` is computed by subtracting from a fixed `Instant.parse(...)` so the rendered "2h ago" string is stable across IDE preview runs — but note that **`formatRelativeTime`'s `now` defaults to `Clock.System.now()`**, so the displayed text will actually be "however long since 2026-05-12 08:00:00 UTC" at preview time. This is intentional — the preview demonstrates the live formatter; tightening determinism would require exposing the `now` parameter through `ConversationRow`'s public surface, which is scope creep. Developer should pick a `Conversation.lastUsedAt` close enough to "now-ish" relative to today's date (currently 2026-05-12) that the preview reads sensibly; `now - 2.hours` is a safe choice that will continue to read as a recent timestamp regardless of when the preview is opened.

The two preview helper functions (`previewConversation`, `previewMessage`) live in the same file as `private` top-level functions. **Do not** move them into a separate `PreviewData.kt` or test-fixtures module — they're 25 lines, used only by the two previews here, and #18 will define its own seeds.

Mark both `@Preview` functions `private`.

## Open questions

- **Auto-name fallback wording.** Spec picks "Untitled channel" / "Untitled discussion" branched on `isPromoted`. If the developer reads the codebase and discovers a stronger convention (e.g. a Pyrycode CLI placeholder string already used in `FakeConversationRepository` seeds), they may substitute it as long as it remains deterministic per conversation. The AC pins determinism, not the exact string.
- **Preview density.** Spec specifies two previews per AC #6 (one with-message, one without). A third "long content" preview (1000-char message exercising the maxLines=2 ellipsis) would be nice but is not required and would tip the file slightly above the 80-line estimate. Developer's call — add it only if doing so keeps the file under ~120 lines total.

## Out of scope (do not implement)

- The list container, scrolling, or `LazyColumn` — #18.
- Workspace label / cwd decoration on the row — #19.
- Sleeping-session indicator — #20.
- Tapping behavior beyond invoking `onClick` (no navigation, no `Intent`) — consumers wire this.
- A `ConversationRowState` or `ConversationRowViewModel` — pure stateless, per AC and CLAUDE.md.
- Localization of the relative-time strings — Phase 0 scaffolding only.
- Live-updating timestamps via `produceState` + `delay` — frozen at composition time, as called out in "State + concurrency model".
- Extracting `formatRelativeTime` or `previewText` into a separate file or module-internal helper — inline as `private` per "File" section above.
- Adding `ComposeTestRule` interaction tests — explicit non-goal, per "Testing strategy".
- Role-based message preview decoration (e.g. `"You: …"` for `Role.User`) — not in AC; future decoration ticket if needed.
