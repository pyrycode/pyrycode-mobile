# Spec — Channel List: TopAppBar logo, channel avatars, Channels section header (#68)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

A dark-surface 412×892 home. The `TopAppBar` carries the Pyrycode logo (28dp icon in a 40dp leading frame, tinted to `colorScheme.primary`) next to the "Pyrycode" title, with the existing settings icon retained at the trailing edge. Below the bar, a "Channels" label (M3 label-large, `onSurfaceVariant` at 85% alpha) heads the list; each channel row leads with a 40dp circular avatar — solid `*-container` fill, 2-letter lowercase initials in the matching `on*-container` colour, M3 label-large — followed by the title (title-medium), an optional 8dp sleeping dot, and a trailing relative timestamp (body-small, `onSurfaceVariant` at 75% alpha). The FAB is a 56dp primary-container square-rounded (16dp) `+` button — M3 defaults already match; no override.

The Figma node also renders the "Recent discussions" section and the "See all discussions (N)" pill — both out of scope for this ticket (see #69). Leave the existing `RecentDiscussionsPill` placement untouched.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:44-117` — the screen being edited. Public surface `ChannelListScreen(state, onEvent, modifier)`, the `Scaffold(topBar=..., floatingActionButton=...)` shape, and the four `UiState` branches (Loading / Empty / Error / Loaded) are all frozen. Only the `TopAppBar` body, the `Loaded` column's prefix, and the existing `Loading`/`Empty`/`Error` Previews change.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:38-104` — the row being edited. Public signature `ConversationRow(conversation, lastMessage, onClick, modifier, onLongClick)` is frozen. The `ListItem` body is restructured: the avatar moves into `leadingContent`; the `headlineContent` Row keeps the sleeping dot + name + workspace label exactly as today; `trailingContent` is unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` (all of it; specifically the dark scheme around line 112+) — confirm the six tokens this spec relies on already exist: `primaryContainer` / `onPrimaryContainer`, `secondaryContainer` / `onSecondaryContainer`, `tertiaryContainer` / `onTertiaryContainer`. They do, light + dark + medium + high contrast. No `Color.kt` edits.
- `app/src/main/res/drawable/ic_pyry_logo.xml` — the existing logo asset. The internal `fillColor`/`strokeColor` are `#FFFFFF`; Compose's `Icon(painter, tint = …)` applies a `SrcIn` ColorFilter across the painter so the logo will render in `colorScheme.primary` regardless of the drawable's own fills. No drawable edits.
- `app/src/main/res/values/strings.xml` — three new string resources needed (see § Strings). Existing `app_name` and `cd_open_settings` are reused unchanged.
- `docs/specs/architecture/57-welcome-screen-figma-polish.md` and `docs/specs/architecture/60-scanner-screen-figma-polish.md` — the precedent Phase 1.5 Figma-polish specs. Mirror their conventions for the "no hardcoded literal" reading (the AC's intent is "no `Color(0x…)` constructors outside the palette" and "no `TextStyle(...)` constructors outside `MaterialTheme.typography`"; dimension literals like `16.dp` for padding/spacing are normal because M3 does not expose Compose-side padding tokens, and the precedent specs use raw `dp` throughout).
- `gradle/libs.versions.toml` and `app/build.gradle.kts` — no new dependencies. Everything is already on the classpath: `androidx-compose-material3` (provides `FloatingActionButton` defaults, `ListItem`, `TopAppBar`), `androidx-compose-ui` (provides `painterResource`), `androidx-compose-material-icons-core` (`Icons.Default.Settings`, `Icons.Default.Add`). No catalog edits.

## Context

#46 shipped a working `ChannelListScreen` with functional `TopAppBar`, `LazyColumn`, `FloatingActionButton`, and a `ConversationRow` carrying name + workspace label + sleeping dot + relative timestamp. The composition is Material 3 "out of the box" — visually generic. The canonical Figma `15:8` introduces three brand-recognition decorations: a Pyrycode logo + title in the bar, a 2-letter avatar bubble in front of each channel name (deterministically coloured across three `*-container` palettes), and a "Channels" section header above the list. This slice does only that — the "Recent discussions" section restructure (replacing the bottom-of-screen `RecentDiscussionsPill` with an inline section + see-all pill) is split into #69.

The avatar bubble is the load-bearing new affordance. The Figma renders four rows with distinct container colours; the architect's read is that designer wants visual variety, not a fixed per-channel mapping. The spec picks **deterministic hash-based palette selection** (stable per channel, three palettes cycled by `abs(name.hashCode()) % 3`) — that gives the Figma's variety, survives recomposition without flicker, and avoids storing presentation state in the data model. The initials algorithm comes from the ticket: first letter of first two words, lowercase; one-word fallback uses first two letters; null/blank fallback uses the conversation id.

## Design

### File scope

- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`
- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt`
- **Create:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatar.kt`
- **Modify:** `app/src/main/res/values/strings.xml` (three additions)

No changes to `ChannelListViewModel`, `ChannelListUiState`, `Conversation`, `MainActivity`, `Theme.kt`, `Color.kt`, `Type.kt`, build files, or any other file.

### Public surface (frozen)

The exported signatures of `ChannelListScreen` and `ConversationRow` do not change. The new `ConversationAvatar` is the only new export:

```kotlin
@Composable
fun ConversationAvatar(
    conversation: Conversation,
    modifier: Modifier = Modifier,
)
```

A single argument because the avatar derives both its initials and its container palette from the conversation alone — keeping the call site (`ConversationRow`'s `leadingContent`) a one-liner.

### Initials derivation

Pure function in `ConversationAvatar.kt`, internal visibility, no Compose annotation — testable as a plain Kotlin function via `./gradlew test`.

```kotlin
internal fun deriveInitials(name: String?, fallback: String): String
```

Algorithm:
1. If `name` is non-null and non-blank, split it on the regex `[^\p{L}\p{N}]+` (any run of non-letter/non-digit; covers whitespace, hyphens, underscores, punctuation), drop empty pieces.
2. If two or more words remain → take the first character of word 0 and word 1, concatenate, `lowercase()`.
3. If exactly one word remains → take its first two characters (or one + the fallback char `'?'` if the word is one letter), `lowercase()`.
4. Otherwise (or if `name` was null/blank) → take the first two characters of `fallback` (the conversation id), `lowercase()`. If the fallback is also < 2 characters, pad with `'?'`.

The return is always exactly 2 characters. The caller passes `conversation.id` as fallback.

### Palette derivation

Three `*-container` pairs cycled by hash:

```kotlin
internal data class AvatarPalette(
    val container: Color,
    val onContainer: Color,
)

@Composable
internal fun avatarPaletteFor(conversation: Conversation): AvatarPalette
```

Implementation:
- Hash key: `(conversation.name ?: conversation.id)`. Use Kotlin's built-in `String.hashCode()` then `Math.floorMod(hash, 3)` (avoids negative-modulo edge case from `abs(Int.MIN_VALUE)`).
- Index 0 → `colorScheme.primaryContainer` / `colorScheme.onPrimaryContainer`
- Index 1 → `colorScheme.secondaryContainer` / `colorScheme.onSecondaryContainer`
- Index 2 → `colorScheme.tertiaryContainer` / `colorScheme.onTertiaryContainer`

No `Color(0x…)` literals; everything routes through `MaterialTheme.colorScheme`.

### ConversationAvatar composable

40dp circle, container-coloured fill, centred initials in `MaterialTheme.typography.labelLarge`, `onContainer` text colour. Sketch:

```kotlin
@Composable
fun ConversationAvatar(
    conversation: Conversation,
    modifier: Modifier = Modifier,
) {
    val palette = avatarPaletteFor(conversation)
    val initials = remember(conversation.name, conversation.id) {
        deriveInitials(conversation.name, conversation.id)
    }
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape).background(palette.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelLarge,
            color = palette.onContainer,
        )
    }
}
```

A `contentDescription` is not needed because the row's headline already carries the channel name — the avatar is decorative. Wrap it in a `Modifier.semantics { contentDescription = ... }` only if accessibility testing flags it; default is decorative.

### ConversationRow restructure

`ListItem` gains a `leadingContent` slot. The `headlineContent` Row is preserved verbatim (sleeping dot + name + workspace label). Sketch:

```kotlin
ListItem(
    modifier = gestureModifier,
    leadingContent = { ConversationAvatar(conversation) },
    headlineContent = { /* existing Row — unchanged */ },
    supportingContent = lastMessage?.let { ... /* unchanged */ },
    trailingContent = { Text(text = formatRelativeTime(conversation.lastUsedAt)) },
)
```

The sleeping dot's position (currently rendered *before* the title text inside `headlineContent`) is preserved exactly per AC1.5. Figma's row 2 happens to render the dot after the title; that is a pre-existing inconsistency between Figma and the implementation and is **not in scope for this ticket** — AC1.5 explicitly demands the row decorations be "preserved unchanged".

### ChannelListScreen edits

**TopAppBar:** add a `navigationIcon` slot rendering the logo at 28dp inside a 40dp Box. The icon uses `painterResource(R.drawable.ic_pyry_logo)` with `tint = MaterialTheme.colorScheme.primary`. The title and settings action are unchanged. Sketch:

```kotlin
TopAppBar(
    navigationIcon = {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_pyry_logo),
                contentDescription = stringResource(R.string.cd_pyrycode_logo),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    },
    title = { Text(stringResource(R.string.app_name)) },
    actions = { /* existing settings IconButton — unchanged */ },
)
```

**Channels section header:** new `@Composable private fun ChannelsSectionHeader()` in the same file (matches the file-local `CenteredText` precedent), rendered exactly once above the `LazyColumn` inside the `Loaded` branch. Padding: `start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp` (Figma `15:57`). Style: `MaterialTheme.typography.labelLarge`, colour `MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)` (the 0.85 opacity overlay is the same carve-out pattern #57/#60 use for non-token opacities). The header text comes from `stringResource(R.string.channels_section_header)`.

The header is shown **only in the `Loaded` branch** (not in `Empty` — the empty state still reads "Tap + to start a conversation" without a "Channels" label above it; Figma does not specify an empty-state treatment for the channels section, and #69 will revisit empty-state handling holistically).

**FAB:** Material 3's `FloatingActionButton` already defaults `containerColor` to `colorScheme.primaryContainer`, shape to `FloatingActionButtonDefaults.shape` (16dp rounded square), and elevation to the M3 spec. The existing call already uses `Icons.Default.Add`. **No code change is needed for AC4** — the developer should verify the existing FAB renders identically to Figma `15:106` in a preview screenshot and otherwise leave it alone. This is the explicit precedent the project's `Don't refactor adjacent code "while you're there."` rule covers: AC4 is satisfied by existing defaults, so the spec adds nothing.

### Strings

Add to `app/src/main/res/values/strings.xml`:

- `cd_pyrycode_logo` — content description for the logo icon; copy: `Pyrycode logo`.
- `channels_section_header` — section header label; copy: `Channels`.
- (No string needed for the avatar — the bubble is decorative per § ConversationAvatar.)

### Previews

AC1.5 demands light + dark previews for `ChannelListScreen`, `ConversationRow`, and `ConversationAvatar`.

- **`ConversationAvatar.kt`** — two previews: a 3-row column showing palette variety (one name hashing to each of the three palettes), one light + one dark. Use `previewConversation` factory copied from `ConversationRow.kt`'s pattern.
- **`ConversationRow.kt`** — already has light + dark previews from #17/#19/#20; keep them. They now render the avatar automatically through the restructured `ListItem`. Add no new previews.
- **`ChannelListScreen.kt`** — the existing four previews are light-only (Loaded, Loaded+pill, Empty, Empty+pill). Add **dark** counterparts for Loaded and Empty (two new previews) using `Configuration.UI_MODE_NIGHT_YES` per the precedent in `ConversationRow.kt:163-168`. The pill-specific previews stay light-only — they exercise the (out-of-scope) `RecentDiscussionsPill`, which #69 replaces.

## State + concurrency model

Pure UI changes. No new `viewModelScope` jobs, no new flows, no dispatchers touched. The avatar's initials and palette are derived synchronously inside composition from the `Conversation` already in `ChannelListUiState.Loaded.channels`. `remember(conversation.name, conversation.id) { deriveInitials(...) }` avoids recomputing the regex split on every recomposition.

## Error handling

No new failure modes. The avatar's null/blank-name fallback is deterministic (conversation id → "??" worst-case) and exercised by a unit test (see § Testing).

## Testing strategy

**Unit tests** (`./gradlew test`) — new file `app/src/test/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatarTest.kt`. Scenarios:

- "kitchenclaw refactor" → "kc"
- "leaky-faucet" → "lf"
- "pyrycode discord integration" → "pd"
- "rocd-thinking" → "rt"
- "scratch" (single word) → "sc"
- "" or null → fallback "ab" when id is `"abc-123"`, "??" when id is also blank
- "  spaced  out  name  " → "so" (whitespace trim + split)
- "  " (whitespace-only name, id "x") → "x?"
- Stability: same name hashed twice returns the same palette index (`floorMod(hash, 3)` determinism)

The palette function is `@Composable` so it cannot be a plain unit test; instead expose the index calculation as `internal fun paletteIndexFor(key: String): Int = Math.floorMod(key.hashCode(), 3)` and unit-test the index. The composable wrapping reads `MaterialTheme.colorScheme` and is exercised through the preview screenshots.

**Compose previews** — visual verification per § Previews. The developer runs each preview at least once via Android Studio's preview pane (or the Compose preview screenshot tool if installed) before opening the PR.

**Instrumented tests** — none. There is no behaviour change on tap; the FAB, settings action, and row click wiring are all unchanged, and #46's existing `ChannelListScreenTest` (if present) continues to pass without modification.

## Open questions

- **Sleeping-dot position vs Figma.** Figma `15:68` renders the dot *after* the channel name; the current implementation renders it *before*. AC1.5 says "preserved unchanged" — the spec freezes the existing position. If product wants the dot moved, file a follow-up; do not change it under this ticket.
- **Avatar accessibility.** The avatar is treated as decorative (no `contentDescription`). If a future accessibility pass flags this — e.g. TalkBack users would benefit from announcing "kc avatar" — add `Modifier.semantics { contentDescription = ... }` then; not in scope here.
- **Palette hash collisions.** Two adjacent channels with the same `name.hashCode() % 3` will share a colour. That is acceptable visual behaviour (Figma row 2 and row 4 both use tertiary-container). No mitigation needed.
