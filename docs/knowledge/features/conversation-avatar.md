# ConversationAvatar

Shared 40dp leading avatar bubble for `Conversation`-backed list rows. First introduced as the `leadingContent` of `ConversationRow` in #68 (Channel list Figma polish, node `15:8`). Stateless, derives both its initials and its container palette from the `Conversation` alone — keeping the call site a one-liner.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatar.kt`).

## What it does

Renders a 40dp circle filled with one of three M3 `*-container` colours, centred over a 2-letter lowercase initials `Text` in the matching `on*-container` colour at `MaterialTheme.typography.labelLarge`. The container palette is selected deterministically from the conversation's name (or id when the name is null/blank); the initials are derived from the name with a deterministic fallback chain back to the id.

The avatar is **decorative** — no `contentDescription`. The row's headline already carries the channel name; TalkBack reads the row as one node and the avatar adds no information beyond what the headline says.

## Public surface

```kotlin
@Composable
fun ConversationAvatar(
    conversation: Conversation,
    modifier: Modifier = Modifier,
)
```

Single-argument by design — both the initials and the palette derive from the `Conversation`, so the call site (`ConversationRow`'s `leadingContent`) stays a one-liner: `leadingContent = { ConversationAvatar(conversation) }`.

## How it works

### Initials derivation

`internal fun deriveInitials(name: String?, fallback: String): String` — pure, non-`@Composable`, unit-tested by `app/src/test/java/.../ConversationAvatarTest.kt`. Always returns exactly two characters.

Algorithm:

1. If `name` is non-null and non-blank, split on `Regex("[^\\p{L}\\p{N}]+")` (any run of non-letter / non-digit — whitespace, hyphens, underscores, punctuation), drop empty pieces.
2. **Two or more words remain** → take `words[0].first()` + `words[1].first()`, `.lowercase()`.
3. **Exactly one word remains** → take its first two characters, `.lowercase()`. If the word is one letter, pad the second slot with `'?'`.
4. **Otherwise** (or if `name` was null/blank) → take the first two characters of `fallback`, `.lowercase()`. If the fallback is also shorter than two characters, pad with `'?'`.

| `name` | `fallback` | Initials |
|---|---|---|
| `"kitchen-claw"` | _any_ | `"kc"` |
| `"leaky-faucet"` | _any_ | `"lf"` |
| `"pyrycode discord integration"` | _any_ | `"pd"` |
| `"rocd-thinking"` | _any_ | `"rt"` |
| `"scratch"` | _any_ | `"sc"` |
| `"a"` | _any_ | `"a?"` |
| `"  spaced  out  name  "` | _any_ | `"so"` |
| `null` | `"abc-123"` | `"ab"` |
| `"   "` | `"abc-123"` | `"ab"` |
| `"  "` | `"x"` | `"x?"` |
| `null` | `""` | `"??"` |

The regex splits on hyphens, which is what makes `"kitchen-claw"` → `"kc"` rather than `"ki"`. This is the load-bearing case for the Figma reference rows.

`remember(conversation.name, conversation.id) { deriveInitials(...) }` wraps the call in the composable so the regex split runs once per stable identity, not on every recomposition.

### Palette derivation

```kotlin
internal data class AvatarPalette(val container: Color, val onContainer: Color)
internal fun paletteIndexFor(key: String): Int = Math.floorMod(key.hashCode(), 3)

@Composable
internal fun avatarPaletteFor(conversation: Conversation): AvatarPalette
```

Hash key: `conversation.name ?: conversation.id`. Index → palette:

| Index | Container | Foreground |
|---|---|---|
| 0 | `colorScheme.primaryContainer` | `colorScheme.onPrimaryContainer` |
| 1 | `colorScheme.secondaryContainer` | `colorScheme.onSecondaryContainer` |
| 2 | `colorScheme.tertiaryContainer` | `colorScheme.onTertiaryContainer` |

`Math.floorMod`, not `Math.abs(...) % 3` — `abs(Int.MIN_VALUE)` overflows and produces a negative modulus for the (rare but legitimate) keys whose `hashCode()` happens to be `Int.MIN_VALUE`. The unit test `palette index handles negative hashCode without crashing` pins the contract.

`paletteIndexFor` is intentionally non-`@Composable` so it's plain-JUnit-testable; the `@Composable avatarPaletteFor` wrapper only adds the `MaterialTheme.colorScheme` lookup. This is the project shape for "pure logic + Compose veneer".

Hash collisions are acceptable: two adjacent channels with the same `name.hashCode() % 3` will share a palette. Figma's reference renders row 2 and row 4 in the same colour for the same reason.

### Composable shape

```kotlin
Box(
    modifier = modifier.size(40.dp).clip(CircleShape).background(palette.container),
    contentAlignment = Alignment.Center,
) {
    Text(initials, style = labelLarge, color = palette.onContainer)
}
```

No `Surface` — `Box` + `clip(CircleShape)` + `background(...)` is the lighter-weight primitive for a single-colour pill with no elevation, and `Surface`'s built-in interaction state isn't needed (the avatar is non-interactive).

## Edge cases / limitations

- Always returns a 2-character initials string. There is no "1-character initial" rendering path; `"a"` → `"a?"` is the documented shape, and the unit test pins it.
- The fallback for a null/blank name is the conversation id, which is UUID-shaped in production seeds — `deriveInitials(null, "abc-123-…")` → `"ab"`. That's intentionally legible (it's the leading characters of the id) rather than aesthetic; if the entry has no name *and* the id reads poorly, treat the avatar as a placeholder until product seeds a name.
- The palette is deterministic per name. Renaming a conversation will change the palette — Phase 0's fake repo doesn't surface a rename action, so this hasn't been exercised.
- Decorative — no `contentDescription`. If a future accessibility pass argues TalkBack users would benefit from "kc avatar" announcements, add `Modifier.semantics { contentDescription = ... }`; not in scope yet.
- No size override. The 40dp dimension is hardcoded — caller cannot scale. If a thread-screen header or settings avatar needs a different size, the architect call is to add a `size: Dp = 40.dp` argument *then*, not pre-emptively.

## Preview

Two `@Preview`s, both wrapped in `PyrycodeMobileTheme`, both `widthDp = 240`:

- `ConversationAvatarLightPreview` (`darkTheme = false`) — column of three avatars whose names hash to palette indices 0, 1, and 2 respectively (`"kitchenclaw refactor"`, `"pyrycode discord integration"`, `"rocd-thinking"`). Renders the full M3 `primary` / `secondary` / `tertiary` container variety in one screenshot.
- `ConversationAvatarDarkPreview` (`darkTheme = true`, `uiMode = Configuration.UI_MODE_NIGHT_YES`) — same three avatars in the dark scheme.

The `previewAvatarConversation(id, name)` factory inlines a minimal `Conversation` (defaults for cwd/session/history/promoted/lastUsedAt). Unit tests assert on `deriveInitials` / `paletteIndexFor` directly; previews verify the rendered palette / typography end-to-end.

## Related

- Ticket notes: [`../codebase/68.md`](../codebase/68.md)
- Spec: `docs/specs/architecture/68-channel-list-figma-polish.md`
- Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8 (nodes `15:60`–`15:80`)
- Upstream: [`Conversation`](./data-model.md) (consumed verbatim — name + id are the only fields read)
- Consumers: [`ConversationRow`](./conversation-row.md) (`leadingContent` slot — every row on the channel list and discussions drilldown)
- Pattern siblings: hash-mod-N selection over M3 palette pairs — first project use; reach for `Math.floorMod` not `Math.abs(...) %`.
