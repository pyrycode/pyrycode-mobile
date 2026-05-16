# Spec — tool-call collapsed/expanded component (#131)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:5-22` — `Message(id, sessionId, role, content, timestamp, isStreaming, toolCall)` and `data class ToolCall(toolName: String, input: String, output: String)`. **Note: `input` is a flat `String`, not structured params** — the primary-arg derivation in this ticket reduces accordingly (see § Design / Primary-arg derivation).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:333-356` — the one seeded `Role.Tool` fixture today: a `Read` tool call on `ThreadScreen.kt`. `input = "<path>"`, `output = <multi-line Kotlin source>`. The expanded-state preview fixture mirrors this shape; the Edit and Bash fixtures in this ticket's previews are new in-file constants (not added to the fake repo).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:52-58` — current `Role.Tool -> Unit` arm. This ticket replaces that arm with a route into `ToolCallRow`. **No other change to this file**; the User / Assistant variants and previews stay as they are.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:237-282` — current `private fun CodeBlock(content, language)`. This ticket widens its visibility from `private` to `internal` so `ToolCallRow` can call it for the code-ish output rendering (AC6). **One keyword change**; the body is unchanged. The two existing call sites at lines 125 and 132 keep compiling without edits — `internal` is a superset of `private`-within-file.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:57-65` — file-private code-block layout constants (`CodeBlockCornerRadius`, `CodeBlockHorizontalPadding`, `CodeBlockVerticalPadding`, `CodeBlockPadding`). Stay file-private; this ticket does not need them outside `MarkdownText.kt`.
- `docs/specs/architecture/128-message-bubble-user-assistant-variants.md:67-77` — the `when (message.role)` dispatcher contract: `Role.Tool -> Unit` is a deliberate no-op pending #131. This ticket converts that arm into a route to `ToolCallRow`.
- `docs/specs/architecture/128-message-bubble-user-assistant-variants.md:105-126` — § Spacing tokens. `MessageRowVerticalSpacing = 12.dp` is file-private to `MessageBubble.kt`. **`ToolCallRow.kt` redeclares its own file-private `MessageRowVerticalSpacing = 12.dp`** for symmetry — see § Design / Spacing.
- `docs/specs/architecture/130-code-block-syntax-highlighting.md:80-124` — the visual contract for `CodeBlock(content, language)`: rounded `surfaceContainer` rectangle, monospace `bodyMedium`, optional top-right language label, horizontal scroll. Reused verbatim for code-ish tool output (AC6) by passing `language = null` — no syntax highlighting in #131; the same rounded-monospace surface is the visual goal.
- `gradle/libs.versions.toml:41-43` — existing `androidx-compose-material-icons-core` library alias. This ticket adds one sibling line for `material-icons-extended` (needed for `Description`, `Terminal`, `Build` — see § Library wire-up). The `Edit` icon used by AC5 is already in `material-icons-core`.
- `app/build.gradle.kts:80-90` — `dependencies { }` block. Add one `implementation(libs.androidx.compose.material.icons.extended)` line near the existing `material.icons.core` import.
- `docs/knowledge/features/message-bubble.md` — the feature note's "`Role.Tool -> Unit` … pending #131" line is realised here; the file will be updated by the documentation phase, not by this ticket.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread frame `16:8`. The tool-call card in the design (`16:28` – `16:31`) is a left-aligned, rounded-12dp tile with `surface-container` fill and a 1dp `outline-variant` border, padded `12px × 8px`, containing a two-tone single-line label: tool name in `Roboto Mono Regular 13px / tertiary` (`#ffb59f` dark) followed by the file path + meta in `body-small / on-surface-variant` (`#c2c7cf` dark) — no leading icon, no expand affordance, no chevron.

**Three deliberate deviations from the Figma to follow the ticket body, which is authoritative:**

1. **Background slot.** Figma uses `surface-container` (lower tonal step) + a 1dp `outline-variant` border. Ticket AC3 pins **`surfaceContainerHigh`** with **no border**. The flat `surfaceContainerHigh` fill matches the assistant-bubble tonal slot from #128, so within the thread a `Role.User` bubble (`primaryContainer`), a tool card (`surfaceContainerHigh`), and the eventual session delimiter (transparent) form a three-step contrast ladder that's legible without an extra outline stroke.
2. **Leading icon.** Figma has no icon; AC5 requires one ("a leading icon per tool type … `MaterialTheme.colorScheme.onSurfaceVariant`"). We add an 18dp `Icon` on the leading side of the collapsed-row content, tinted `onSurfaceVariant`. No icon-vs-text colour conflict — the icon is the AC's tonal anchor; the tool-name accent stays in `tertiary`.
3. **Expand affordance.** Figma shows the card always-collapsed. AC1+AC2 add the tap-to-toggle expanded state. **No chevron / caret rendered** — the entire surface is the click target via `Modifier.clickable`. A chevron is design-for-hypothetical-future; the tap target is the whole row.

The two-tone tool-name-in-`tertiary` + primary-arg-in-`onSurfaceVariant` split is preserved from Figma — it is the strongest visual cue that this row is a tool call, not a regular message. Tool name uses `MaterialTheme.typography.bodyMedium` with `fontFamily = FontFamily.Monospace`; primary-arg uses the same `bodyMedium` size class without a monospace switch, matching the Figma proportion (`13px` mono / `body-small 12px` proportional). On Compose, both render through `MaterialTheme.typography.bodyMedium` for consistency with the rest of the row primitives.

## Context

`#127` landed `Message(toolCall: ToolCall?)` with one seeded `Read` fixture and a documented invariant: `toolCall` is non-null iff `role == Role.Tool`. `#128` shipped `MessageBubble` with `Role.Tool -> Unit` as the deliberate no-op pending this ticket. `#130` shipped a syntax-highlighted `private CodeBlock(content, language)` inside `MarkdownText.kt`. This ticket adds the `ToolCallRow` composable that renders `ToolCall` instances, routes the `Role.Tool` arm into it, and widens `CodeBlock` to `internal` so the tool-output rendering reuses the same rounded-monospace surface.

Scope contract:

- **New file, one public composable.** `ToolCallRow.kt` is added under `ui/conversations/components/` (alongside `MessageBubble.kt`). The public API is `@Composable fun ToolCallRow(toolCall: ToolCall, modifier: Modifier = Modifier)`. No other public composables, no `ViewModel`, no DI wiring.
- **Wiring decision: route via `MessageBubble`'s `Role.Tool` arm, not `ThreadScreen`.** The arm replaces `Role.Tool -> Unit` with `Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier) }`. Two reasons: (a) the dispatch contract for `Message` already lives in `MessageBubble` — moving Tool-routing one level up to `ThreadScreen` would duplicate the role-fanout and force every future consumer to repeat the unwrap; (b) the `message.toolCall?.let { … }` null-safe unwrap absorbs the data-class invariant violation (`Role.Tool` with `toolCall = null`) silently, exactly matching the no-op posture that `Role.Tool -> Unit` had before. No crash, no defensive throw.
- **Toggle state is composable-local.** `var expanded by rememberSaveable { mutableStateOf(false) }` inside `ToolCallRow`. Survives configuration changes (AC2). **No `ViewModel` coupling** — per technical-notes paragraph in the ticket.
- **Output rendering reuses `CodeBlock(content, language = null)` from #130** (after the visibility-widening). Language inference from file extension (e.g. `.kt` → `kotlin`) is **out of scope** for #131; the developer should not add it. `language = null` keeps the plain-monospace fallback path through #130. Token colouring for code-ish output is a tasteful enhancement deferred to a follow-up.
- **Preview matrix only — no unit tests, no Compose UI tests.** Same precedent as #128 / #129 / #130. The codebase has no Compose UI test infrastructure; building it for this ticket is over-scope.
- **No new icons added as drawable resources.** Distinct icons come from the existing Material icons libraries (`material-icons-core` for `Edit`, `material-icons-extended` — newly added — for `Description` / `Terminal` / `Build`). See § Library wire-up.

## Design

### File

**New:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt`

One file, one public composable (`ToolCallRow`), one private stateless content composable (`ToolCallRowContent`), three small private helpers (`iconForTool`, `primaryArg`, `isCodeIsh`), and the preview block. **Production-file count: 1 new + 2 modified Kotlin source files** (`MessageBubble.kt`, `MarkdownText.kt`) + 2 modified build configs (`gradle/libs.versions.toml`, `app/build.gradle.kts`). Under the size-S 5-file threshold.

### Public API

```kotlin
@Composable
fun ToolCallRow(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
)
```

- **Single parameter is `ToolCall`** — the inner payload, not the wrapping `Message`. The Role-dispatch and the null-unwrap happen one level up in `MessageBubble`'s `Role.Tool` arm; `ToolCallRow` is a leaf renderer that consumes the already-unwrapped value.
- **`modifier` defaulted to `Modifier`.** The caller (the `MessageBubble` `Role.Tool` arm, eventually the `LazyColumn` item slot) typically does not pass one.
- **No `onClick` parameter.** Tap-to-toggle is internal to this composable — message-level click semantics (long-press for "Copy", etc.) are not in AC and not in Figma.

### Stateful wrapper + stateless content split

The composable is split for preview ergonomics — two preview cells need to render the same `ToolCall` with `expanded = false` and `expanded = true` side by side, which is awkward when state is internal:

- **`ToolCallRow`** (public, stateful): manages `var expanded by rememberSaveable { mutableStateOf(false) }` and delegates to `ToolCallRowContent`.
- **`ToolCallRowContent`** (private, stateless): receives `(toolCall, expanded, onToggle, modifier)` and emits the layout. Both the stateful wrapper and the previews call into this.

Signature sketches:

```kotlin
@Composable
fun ToolCallRow(toolCall: ToolCall, modifier: Modifier = Modifier)

@Composable
private fun ToolCallRowContent(
    toolCall: ToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Pattern mirrors the streaming-vs-static split in #128/#184's `AssistantMessage` → `StreamingAssistantBody` / `StreamingAssistantBodyView` family: a thin stateful wrapper around a pure-rendering inner composable, so previews can drive the visual without re-implementing the state.

### Visual contract

Outer container of `ToolCallRowContent`:

```
Row(modifier.fillMaxWidth().padding(bottom = MessageRowVerticalSpacing))
└── Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(ToolCallCornerRadius),     // 12.dp
        color  = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    └── Column(modifier = Modifier.padding(
            horizontal = ToolCallHorizontalPadding,            // 12.dp
            vertical   = ToolCallVerticalPadding,              // 8.dp
        ))
        ├── CollapsedHeaderRow(toolCall)         // always present
        └── if (expanded) ExpandedBody(toolCall) // conditional
```

- The bottom-padding (`MessageRowVerticalSpacing = 12.dp`) is **on the outer Row**, matching `MessageBubble`'s per-variant pattern. The eventual `LazyColumn` consumer adds no inter-row spacing; the rhythm lives on each variant.
- The `Surface` is `fillMaxWidth` — the tool card stretches edge-to-edge of the column, not capped like the user bubble's `widthIn(max = 320.dp)`. Figma's tool card is a flat one-line text element whose width is content-driven; we render full-width for layout consistency with the eventual `LazyColumn` (the `clickable` target spans the row, so a wider hit area is helpful).
- **`Modifier.clickable(onClick = onToggle)`** uses the default ripple and indication. No custom ripple colour — inherit the M3 default.

#### Collapsed header row

```
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(ToolCallHeaderGap),     // 8.dp
)
├── Icon(
│       imageVector = iconForTool(toolCall.toolName),
│       contentDescription = null,
│       modifier = Modifier.size(ToolCallIconSize),                       // 18.dp
│       tint = MaterialTheme.colorScheme.onSurfaceVariant,
│   )
└── Text(
        text = buildSummaryAnnotated(toolCall),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
```

- `contentDescription = null` because the adjacent `Text` carries the semantic content; the icon is purely decorative. A TalkBack user hears `"Read · path/to/file.kt"`, not `"Read · path/to/file.kt, document icon"`. This matches the existing `ArchiveRow` posture (the leading `Refresh` icon there carries no contentDescription either — the row's text is the semantic anchor).
- `Modifier.weight(1f)` on the `Text` lets it absorb the remaining horizontal space so `maxLines = 1` + `TextOverflow.Ellipsis` clip the primary-arg when long (AC4).
- `buildSummaryAnnotated` is an `@Composable` builder that produces an `AnnotatedString` of the form `"$toolName · $primaryArg"`:

  ```kotlin
  @Composable
  private fun buildSummaryAnnotated(toolCall: ToolCall): AnnotatedString {
      val toolNameColor = MaterialTheme.colorScheme.tertiary
      val argColor = MaterialTheme.colorScheme.onSurfaceVariant
      return buildAnnotatedString {
          withStyle(SpanStyle(color = toolNameColor, fontFamily = FontFamily.Monospace)) {
              append(toolCall.toolName)
          }
          withStyle(SpanStyle(color = argColor)) {
              append(" · ")
              append(primaryArg(toolCall))
          }
      }
  }
  ```

  Two colour-stops (tertiary for the tool name, onSurfaceVariant for the separator + arg) match Figma. The monospace `fontFamily` on the tool name mirrors Figma's `Roboto Mono Regular`. Body text inherits the parent's `bodyMedium` size — no explicit size, no `.sp` literal.

#### Expanded body

```
Column(
    modifier = Modifier.padding(top = ToolCallExpandedTopPadding),       // 8.dp
    verticalArrangement = Arrangement.spacedBy(ToolCallExpandedGap),     // 8.dp,
)
├── ExpandedSection(label = "Input", content = toolCall.input)
└── ExpandedSection(label = "Output", content = toolCall.output)
```

`ExpandedSection` is a private helper composable that takes a `label: String` and a `content: String` and renders:

- A small caption `Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`.
- The body, dispatched by `isCodeIsh(content)`:
  - **Code-ish (`content.contains('\n') || content.length > 80`):** call `CodeBlock(content = content, language = null)` (the `internal`-widened composable from `MarkdownText.kt`). Inherits the `surfaceContainer` rounded rectangle, monospace `bodyMedium`, horizontal scroll, and the no-language-label path (since `language = null`).
  - **Plain (else):** `Text(content, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)` with no surface wrapper — the AC's "plain monospace text on `surface`" reads as "no nested coloured tile, just the screen surface behind the text". The text inherits the parent's padding context (already inside the tool-call surface's `12×8` padding).

The "Input" / "Output" caption labels are intentional copy choices, not exposed as a string resource yet (Phase 0 / English-only). When localisation lands, these move to `strings.xml` along with the rest of the UI copy.

### Primary-arg derivation

```kotlin
private fun primaryArg(toolCall: ToolCall): String = toolCall.input.trim()
```

Given the current `ToolCall(input: String, output: String)` shape (per `data/model/Message.kt:18-22`), the `input` field carries the path (for `Read` / `Edit`) or the command string (for `Bash`) directly as a flat string. There is no multi-param JSON to parse, no first-param lookup. The AC's three cases (`Read`/`Edit` → file path; `Bash` → command; else → first input param) all reduce to a single statement: **use `input` directly**.

**Forward-compatibility:** If a future ticket (e.g. #191's structured payload) replaces `input: String` with a typed `Map<String, JsonElement>` or similar, this function gains real branching logic. For #131, the trivial body is the contract. **Do not pre-bake** a JSON parser, regex, or branch table — the data model doesn't expose those fields.

**Empty-input edge case:** if `input.trim().isEmpty()` (e.g. `Bash` with no command — degenerate), the summary renders as `"Bash · "` with a trailing dot-space. Acceptable visual; the row is still tappable, the expanded body still shows the (empty) input and the output. Not worth special-casing.

### Icon mapping

```kotlin
private fun iconForTool(toolName: String): ImageVector = when (toolName.lowercase()) {
    "read" -> Icons.Outlined.Description
    "edit" -> Icons.Outlined.Edit
    "bash" -> Icons.Outlined.Terminal
    else -> Icons.Outlined.Build
}
```

- Match is **case-insensitive** on the trimmed tool name. The seeded fixture uses `toolName = "Read"` (PascalCase, matching Claude Code's tool names); future tool calls may arrive in lowercase from a wire format. Lower-casing at the comparison point is cheaper than canonicalising the data.
- **Why `Icons.Outlined.*`**: matches the M3 outlined-icon family used by the rest of the app's iconography (`ArchiveRow` uses `Icons.Filled.Refresh`; outlined is preferred for non-action affordances per M3 guidance — these icons are passive metadata, not interactive controls). Both families are functionally equivalent on Compose; the visual weight of outlined sits better against the `surfaceContainerHigh` tile.
- **Why `Build` (a wrench) for the fallback**: M3's `Code` icon (an angle-bracket pair) is also reasonable, but `Build` reads as "tool" more directly. Either is acceptable; the developer may swap if `Code` looks better in preview.

`Icons.Outlined.Description`, `.Terminal`, and `.Build` are in **`androidx.compose.material:material-icons-extended`**, not the `material-icons-core` already on the classpath. The `Edit` icon is in `material-icons-core` (no new dependency required for that one alone). The extended-icons artifact is added in § Library wire-up.

### Code-ish heuristic

```kotlin
private fun isCodeIsh(output: String): Boolean =
    output.contains('\n') || output.length > 80
```

Direct from AC6 ("contains a newline or exceeds ~80 chars"). The `80` threshold matches typical terminal-column conventions and aligns roughly with the `bodyMedium` monospace wrap point on a 412dp canvas. **No special-casing of empty strings** — `"".contains('\n')` is `false` and `"".length > 80` is `false`, so empty outputs render as plain (empty) `Text` rather than an empty `CodeBlock`. Acceptable.

The heuristic is applied per-section: input is checked separately from output. In practice, `Read`'s `input` (a path) is short + single-line, so input always renders plain; `Read`'s `output` (file contents) almost always renders as `CodeBlock`. `Bash`'s input (a command) is usually short + single-line, output varies.

### Library wire-up

`gradle/libs.versions.toml`, in the `[libraries]` section near the existing core-icons line (alphabetically adjacent):

```toml
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
```

No new entry in `[versions]` — the icon artifacts inherit the Compose BOM (no explicit version on the existing `androidx-compose-material-icons-core` line either; the library is BOM-managed via the Compose dependency stack).

`app/build.gradle.kts`, in the `dependencies { }` block (alphabetically adjacent to the core-icons line):

```kotlin
implementation(libs.androidx.compose.material.icons.extended)
```

**APK size note.** `material-icons-extended` ships ~7000 icons. R8 / minify-release strips unused icons via tree-shaking when `minifyEnabled true` is on (the default for release builds); debug builds carry the full set. For Phase 0 we accept the debug-APK bloat (~3–4 MB uncompressed). If a future ticket needs to trim the dependency, the alternative is to inline 3 vector drawables in `res/drawable/` (`ic_tool_read.xml`, `ic_tool_bash.xml`, `ic_tool_fallback.xml`) and load them via `painterResource(...)`. Not pre-built — adopt only if the size cost becomes a problem.

### File-private constants in `ToolCallRow.kt`

Declared at the top of the file, mirroring `MessageBubble.kt`'s style:

```kotlin
private val MessageRowVerticalSpacing = 12.dp
private val ToolCallCornerRadius = 12.dp
private val ToolCallHorizontalPadding = 12.dp
private val ToolCallVerticalPadding = 8.dp
private val ToolCallHeaderGap = 8.dp
private val ToolCallIconSize = 18.dp
private val ToolCallExpandedTopPadding = 8.dp
private val ToolCallExpandedGap = 8.dp
```

- `MessageRowVerticalSpacing = 12.dp` is **redeclared** here (file-private) rather than imported from `MessageBubble.kt`. The duplication is intentional: promoting it to an `internal` shared spacing constant is a refactor that touches all variants, and the size-S budget for this ticket does not include that cleanup. A follow-up that introduces a `Spacing.kt` or `LocalSpacing` provider can collapse the two declarations then.
- Every value is a `dp` literal at the declaration site; the consuming composable references the named constant. No `.dp` literal at a call site (AC adjacent — same posture as #128's spacing-tokens contract).

### Updated call site: `MessageBubble.kt`'s `Role.Tool` arm

The only edit to `MessageBubble.kt`:

```kotlin
// before (line 56):
Role.Tool -> Unit

// after:
Role.Tool -> message.toolCall?.let {
    ToolCallRow(toolCall = it, modifier = modifier)
}
```

- The null-safe `?.let` absorbs the documented invariant (`toolCall` non-null iff `role == Role.Tool`) without a defensive `error("…")`. If a `Role.Tool` message arrives with `toolCall = null` (a data-layer bug), the arm silently renders nothing — identical to the pre-#131 no-op behaviour. **No `!!`, no `requireNotNull`** — same posture as the `Role.Tool -> Unit` it replaces.
- `modifier` is threaded through so `ToolCallRow` receives whatever the caller passes to `MessageBubble`. In practice the caller passes `Modifier`; the threading keeps the public API contract intact for future callers.
- **No other change to `MessageBubble.kt`.** The User/Assistant variants, the streaming logic, the previews, and the file-private constants stay exactly as #128 / #184 left them.

### Updated visibility: `MarkdownText.kt`'s `CodeBlock`

```kotlin
// before (line 238):
private fun CodeBlock(

// after:
internal fun CodeBlock(
```

- **One keyword change.** No body edit, no signature change. The two existing in-file call sites (`MarkdownText.kt:125`, `MarkdownText.kt:132`) compile unchanged — `internal` is a superset of `private`-within-file.
- **Why `internal`, not `public`**: `CodeBlock` is a module-internal implementation detail of the `de.pyryco.mobile.ui.conversations.components` package. Consumers outside the module (none today, but Phase 4 adds Ktor-network code in `data/`) have no use for it. `internal` is the smallest visibility widening that satisfies the cross-file reuse from `ToolCallRow.kt`.
- **Why widen at all, vs. re-implement the visual locally**: the AC ("renders through the #130 code-block component") is direct — reuse the literal composable. Re-implementing the rounded-monospace surface inside `ToolCallRow.kt` would duplicate `Surface(shape = RoundedCornerShape(8.dp), color = surfaceContainer) { Text(monospace, softWrap = false, horizontalScroll) }` — 8–10 lines of boilerplate that would visually drift from #130's contract. Single source of truth wins.
- **No public API leak.** `CodeBlock` stays out of any `Sample`, KDoc-exported, or downstream-consumer surface. Compose UI test infrastructure (when it lands) can still target it via reflection or `@VisibleForTesting`-ish helpers if needed.

### Module wiring

None beyond the library wire-up above. Koin DI is unchanged — `ToolCallRow` is a pure leaf composable with no injected dependency. `ThreadScreen.kt` is **not touched** — the `Role.Tool` routing happens inside `MessageBubble` (per § Wiring decision), and `MessageBubble` is already the consumer of every `Message` reaching the screen.

## State + concurrency model

`ToolCallRow` carries one piece of state: `var expanded by rememberSaveable { mutableStateOf(false) }`. `rememberSaveable` survives Activity recreation (rotation, dark-mode toggle, process death + restore) by serialising the `Boolean` to the saved-instance-state bundle. **No `key` parameter** — the default key (the composable's call-site identity) suffices because the `LazyColumn` item slot uses the `Message.id` as its key (eventual wiring; not in this ticket), so each `ToolCallRow` instance has a stable identity per message.

The stateless `ToolCallRowContent` is pure rendering — no `remember`, no `LaunchedEffect`, no flows, no coroutines. Recomposition is driven by `expanded` flipping and by changes in `toolCall` identity.

`ToolCall` is a Kotlin `data class` with three `String` fields. Compose's stability inference marks it as stable; `ToolCallRowContent` skips recomposition when the same `(toolCall, expanded)` pair is passed by the parent.

Streaming (#184) does not interact with `Role.Tool` — `Message.isStreaming` is ignored by the tool path. If a streaming-tool ticket ever lands, the wrapper composable changes; the leaf renderer stays.

## Error handling

`ToolCallRow` is pure rendering with no I/O, no parsing, no network. Failure modes:

| Failure mode | Treatment |
|---|---|
| `toolCall = null` upstream (data-layer invariant violation) | `MessageBubble`'s `Role.Tool` arm `?.let` silently renders nothing. Same posture as the pre-#131 `Role.Tool -> Unit`. |
| Empty `toolName` | `iconForTool("")` falls through to the `else` branch, returning `Icons.Outlined.Build`. Summary renders as `" · ${input}"` — a leading separator space + arg. Visually odd but renders without crash. Acceptable for a degenerate / never-observed case. |
| Empty `input` | Summary renders as `"${toolName} · "`. Trailing dot-space. Acceptable; see § Design / Primary-arg derivation. |
| Empty `output` | `isCodeIsh("")` is `false`, so the expanded body's output section renders an empty plain `Text`. The "Output" caption stays. Acceptable. |
| Long single-line `output` (>80 chars, no newline) | `isCodeIsh` → `true`, renders through `CodeBlock(language = null)` with horizontal scroll. Long lines scroll instead of wrapping — same behaviour as #130's `softWrap = false`. |
| Very long multi-line `output` (e.g. 10 000+ lines from a large file `Read`) | `CodeBlock` lays out the full `Text` node; Compose handles arbitrarily long text without crashing, but layout cost scales with content length. **Out of scope for #131.** Phase 0 fixtures are bounded; Phase 4 inherits Claude API response-size limits. If users hit it later, a follow-up adds a "Show more" affordance or a max-height cap with internal scroll. |

**No defensive throws.** No `error("Tool role not yet handled")`, no `require(toolCall.toolName.isNotBlank())`, no `check(input.isNotEmpty())`. The composable is total over all `ToolCall` values the data class can construct.

## Testing strategy

### Unit tests — none

Same precedent as #128 / #129 / #130 / #184. The Compose JVM-host / Robolectric infrastructure is not on `testImplementation`; introducing it for this 100-LOC component is over-scope.

The two helpers (`primaryArg`, `isCodeIsh`, `iconForTool`) are trivially correct by inspection:

- `primaryArg`: one-line `input.trim()`. No branches.
- `isCodeIsh`: two-clause `||` over `String.contains` and `String.length`. No branches.
- `iconForTool`: four-arm `when` over a lowercased string. Visible coverage by the preview matrix (Read, Edit, Bash, plus the fallback path can be exercised by a fourth fixture if the developer wants).

If the primary-arg derivation grows non-trivial branching later (e.g. #191's structured payload), a small JVM unit test for that helper is welcome. **Not in #131.**

### Compose UI tests — none

Same precedent as #128 / #129 / #130. No `ComposeTestRule` setup in this codebase. Visual verification is by `@Preview` rendering in Android Studio:

1. **`ToolCallRowLightPreview`** and **`ToolCallRowDarkPreview`** render a 6-cell matrix (3 tools × 2 states) by calling `ToolCallRowContent` directly with `expanded = false` / `expanded = true`. AC7.
2. The collapsed cells exercise the icon mapping (`Description`, `Edit`, `Terminal`) — AC5.
3. The expanded cells exercise the input + output rendering: `Read.output` (multi-line code) hits `isCodeIsh = true` → routes through `CodeBlock`; `Edit.output` (a multi-line diff-ish payload) also routes through `CodeBlock`; `Bash.input` is a short command (plain), `Bash.output` is multi-line stdout (`CodeBlock`). AC6.
4. The collapsed Read cell with a long path demonstrates the `maxLines = 1` + `TextOverflow.Ellipsis` truncation. AC4.
5. The two-tone tool-name-in-tertiary / arg-in-onSurfaceVariant split is visible in every collapsed cell. AC3-adjacent.

**Preview fixtures** are declared as file-private `val`s at the bottom of `ToolCallRow.kt`:

- A `Read` fixture mirroring the seeded one in `FakeConversationRepository`: `toolName = "Read"`, `input = "app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt"` (a long path so the ellipsis fires in the collapsed cell), `output = <Kotlin source snippet ~6 lines>`.
- An `Edit` fixture: `toolName = "Edit"`, `input = "app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt"`, `output = "@@ -42,3 +42,3 @@\n-val Foo = 1\n+val Foo = 2"` (a diff-ish snippet to exercise the multi-line path).
- A `Bash` fixture: `toolName = "Bash"`, `input = "git status"`, `output = "On branch feature/131\nnothing to commit, working tree clean"` (multi-line stdout).

Each `@Preview` wraps the matrix in `PyrycodeMobileTheme(darkTheme = …) { Surface { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { … } } }`, matching the `MessageBubble` preview shape. The `0.dp` spacing on the `Column` is intentional — each `ToolCallRow` instance already carries `padding(bottom = MessageRowVerticalSpacing)` on its outer `Row`, so an outer `spacedBy` would double-count.

**Two `@Preview` functions, not twelve.** A single preview function per theme, each rendering a vertical `Column` of all 6 (tool × state) cells, is the lightest setup that still gives the developer a side-by-side visual comparison.

6. **`./gradlew assembleDebug`** must pass.
7. **`./gradlew lint`** must pass with no new warnings.

Code-review treats successful preview rendering + lint pass as the AC acceptance signal.

### `./gradlew test`

No new unit tests added. Existing test suite stays green.

## Open questions

- **Icon library expansion.** Adding `material-icons-extended` is a ~3–4 MB debug-APK cost for 3 icons. If a future ticket needs to trim, swap to inline vector drawables in `res/drawable/`. Not pre-built — the cost is acceptable for Phase 0, and R8 trims release builds. The developer should confirm `material-icons-extended` is BOM-managed (no explicit version needed); if not, pin to the BOM's resolved version explicitly.
- **Outlined vs Filled icons.** Spec recommends `Icons.Outlined.*`. If the visual reads thin against `surfaceContainerHigh`, swap to `Icons.Filled.*` — same names, same import paths. Visually previewable, developer's call.
- **Code-ish heuristic threshold.** `> 80 chars` is a guess from typical terminal width. If short-but-multi-token outputs (e.g. JSON minified to 79 chars) feel like they should render in `CodeBlock`, drop the threshold to `> 60` or add a `count of non-alphanumeric chars > N` clause. **Not pre-paid here.**
- **Code-block language inference from path extension.** For `Read` / `Edit`, the input path's extension (`.kt`, `.json`, `.kts`, `.sh`) hints at a language for the output. `CodeBlock(content, language = "kotlin")` would syntax-highlight `Read`-on-Kotlin output via the #130 pipeline. **Out of scope for #131**, but cheap to add later — one `extensionToLanguage(path: String): String?` helper, passed into the `CodeBlock` call when the section is "Output" and the tool is `Read`/`Edit`. Defer to a follow-up so the visual baseline lands first.
- **Tap target size.** A single-line collapsed row at `12dp` horizontal × `8dp` vertical padding plus `bodyMedium` text height is roughly `36dp` tall — below M3's 48dp minimum tap target. Acceptable in a scrolling list (the whole row is the target, and adjacent tool calls are spaced by 12dp), but a future ticket might add `minHeight(48.dp)` to the `Surface` if a11y review flags it. Not pre-built.
- **Disclosure affordance.** No chevron / caret today. If users miss that the row is tappable, a thin `Icons.Outlined.ExpandMore` / `ExpandLess` glyph on the trailing edge of the collapsed-row `Row` is the standard M3 disclosure pattern. Hold until preview review surfaces the need.
- **Animating the expand/collapse.** Compose `AnimatedVisibility` around the expanded body would give a soft slide. Out of scope for #131; the boolean toggle is fine for AC1. A future ticket can wrap the conditional in `AnimatedVisibility(visible = expanded) { ... }` without touching the public API.
- **TalkBack semantics for the expanded state.** A screen-reader user toggles the row but receives no announcement of "expanded" / "collapsed". The `Modifier.clickable` carries `onClickLabel` and `Role` parameters that could communicate this — e.g. `Modifier.clickable(onClick = onToggle, onClickLabel = if (expanded) "Collapse" else "Expand", role = Role.Button)`. **Not in AC**; add only if a11y review surfaces the gap.

## Related

- Spec: [`docs/specs/architecture/128-message-bubble-user-assistant-variants.md`](./128-message-bubble-user-assistant-variants.md) — predecessor; defines the `Role`-dispatch `when` this ticket extends, the per-variant `MessageRowVerticalSpacing` rhythm this ticket mirrors, and the preview-pair shape (Light / Dark) this ticket reuses.
- Spec: [`docs/specs/architecture/130-code-block-syntax-highlighting.md`](./130-code-block-syntax-highlighting.md) — defines the `CodeBlock(content, language)` composable this ticket widens from `private` to `internal` and reuses for code-ish tool output (`language = null` path).
- Spec: [`docs/specs/architecture/184-streaming-token-reveal-blinking-caret.md`](./184-streaming-token-reveal-blinking-caret.md) — the stateful-wrapper / stateless-content split pattern (`AssistantMessage` → `StreamingAssistantBody` / `StreamingAssistantBodyView`) this ticket mirrors for `ToolCallRow` → `ToolCallRowContent`.
- Knowledge: [`docs/knowledge/features/message-bubble.md`](../../knowledge/features/message-bubble.md) — feature doc. The "`Role.Tool -> Unit` is the contract until #131" line is realised here; the documentation phase updates this file after merge.
- Knowledge: [`docs/knowledge/features/markdown-text.md`](../../knowledge/features/markdown-text.md) — feature doc for the file `CodeBlock` lives in. The visibility-widening from `private` to `internal` is a non-breaking refactor; the documentation phase will note `CodeBlock` is now callable from `ToolCallRow`.
- Downstream: #191 — structured tool-message payload. Replaces `ToolCall.input: String` with a richer typed shape. When that ticket lands, `primaryArg` and `iconForTool` gain real branching; `ToolCallRow`'s public API stays unchanged.
