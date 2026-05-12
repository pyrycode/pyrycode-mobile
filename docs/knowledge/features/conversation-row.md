# ConversationRow

Shared list row for both Phase 0 conversation surfaces — the Channel list (#18) and the Discussions drilldown (later). First UI primitive consuming `Conversation` + `Message` from #2.

## What it does

Renders one conversation as a single Material 3 `ListItem` with three slots:

- **Headline** — `Conversation.name`, falling back to a deterministic placeholder when null / empty / whitespace-only.
- **Supporting** — the `Message.content` preview, whitespace-normalized, capped at 2 lines with ellipsis. Slot is `null` (not an empty lambda) when `lastMessage == null`, which collapses `ListItem` to its 1-line height — no manual layout branching needed.
- **Trailing** — relative timestamp derived from `Conversation.lastUsedAt`.

Tapping anywhere on the row invokes `onClick` exactly once via `Modifier.clickable`.

## Public surface

```kotlin
@Composable
fun ConversationRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Stateless and pure: no `remember`, no `ViewModel`, no I/O. All parameters are Compose-stable; lambda stability is the caller's responsibility (#18 will `remember`-wrap per-id).

## How it works

### Auto-name fallback

```kotlin
val displayName = conversation.name?.takeIf { it.isNotBlank() }
    ?: if (conversation.isPromoted) "Untitled channel" else "Untitled discussion"
```

Branches on `isPromoted` so the placeholder matches the CLAUDE.md vocabulary (channels = persistent, discussions = throwaway). Depends only on stable fields — does **not** read `lastUsedAt` (which mutates) or `cwd` (owned by the workspace decoration in #19) or `id` (UUID-shaped strings render badly).

### Preview normalization

`previewText(content)` collapses any whitespace run (including embedded newlines) to a single space and trims. Without this, a message starting with blank lines or a markdown heading burns its 2-line budget on whitespace before any text renders. `maxLines = 2` + `TextOverflow.Ellipsis` are the M3 idiom for "1–2 lines with ellipsis".

### Relative time bucketing

Inline `private` helper `formatRelativeTime(instant, now, timeZone)` — default `now = Clock.System.now()` and `timeZone = TimeZone.currentSystemDefault()` so previews and any future test can inject deterministic values without exposing the parameter publicly. First-match wins:

| Condition | Output |
|---|---|
| `(now - instant).isNegative()` or `< 1 minute` | `"just now"` |
| `< 1 hour` | `"{N}m ago"` |
| `< 24 hours` | `"{N}h ago"` |
| `< 48 hours` | `"Yesterday"` |
| `< 7 days` | `"{N}d ago"` |
| same calendar year (in `timeZone`) | `"May 5"` |
| otherwise | `"May 5, 2025"` |

"Yesterday" uses a 48-hour **duration** threshold, not a calendar-day comparison. Calendar-day "yesterday" flips at midnight and produces "Yesterday" 31 seconds after a timestamp 30 seconds before midnight — Slack and Discord both use the duration shape for the same reason.

The displayed string is **frozen at composition time** — it does not tick forward as wall-clock time advances. A self-updating "live" timestamp via `produceState` + `delay` is an explicit non-goal; if a later ticket needs it, it goes in #18 or a dedicated decoration, not here.

Uses `kotlinx.datetime` end-to-end (per [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md)) — `Clock.System`, `Instant`, `LocalDate.Format` DSL, `MonthNames.ENGLISH_ABBREVIATED`. No `java.time.*`, no `SimpleDateFormat`, no `Locale`.

### Typography & color

`ListItem` resolves slot styling to M3 defaults: `headlineContent` → `titleMedium`-equivalent on `onSurface`, `supportingContent` → `bodyMedium` on `onSurfaceVariant`, `trailingContent` → `labelSmall` on `onSurfaceVariant`. The composable passes **no** `style =` or `colors =` overrides — the M3 defaults are the contract.

## Configuration / usage

Not yet wired into any screen. #18 (Channel list) is the first consumer:

```kotlin
LazyColumn { items(channels, key = { it.id }) { channel ->
    ConversationRow(
        conversation = channel,
        lastMessage = previewsByConversation[channel.id],
        onClick = remember(channel.id) {
            { navController.navigate("conversation_thread/${channel.id}") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}}
```

`modifier` is part of the public surface specifically because list consumers need it — `Modifier.fillMaxWidth()`, `Modifier.animateItemPlacement()`, etc. Don't strip it.

## Edge cases / limitations

- `name = null` / `""` / whitespace → `"Untitled channel"` / `"Untitled discussion"`.
- `lastMessage = null` → supporting slot collapses, row shrinks to ~56dp; no placeholder text, no reserved vertical space.
- `lastMessage.content` with embedded newlines or leading whitespace → normalized by `previewText`.
- `lastUsedAt` in the future (clock skew, bad seed data) → `"just now"`.
- Very long `displayName` → headline ellipsizes at 1 line via M3 default; not overridden.
- Strings are English literals. Localization is **out of scope** for Phase 0 — no `stringResource(...)`.

## Out of scope (deliberate)

- Workspace label / cwd decoration on the row — #19 owns this surface.
- Sleeping-session indicator — #20.
- Role-based preview decoration (e.g. `"You: …"` for `Role.User`) — not in AC; future ticket if needed.
- Extracting `formatRelativeTime` or `previewText` to a shared module — both are `private` to this file. If a later ticket (e.g. thread message timestamps) needs the formatter, that ticket extracts.
- `ComposeTestRule` tests — `androidx-compose-ui-test-junit4` is in the catalog but has zero consumers; introducing it here would expand scope beyond the AC.

## Related

- Spec: `docs/specs/architecture/17-conversation-row-composable.md`
- Ticket notes: `docs/knowledge/codebase/17.md`
- Upstream: #2 ([`Conversation` + `Message`](data-model.md)), [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md) (`kotlinx-datetime`)
- Downstream: #18 (Channel list — first consumer), Discussions drilldown (later), #19 (workspace decoration), #20 (sleeping-session indicator)
