# Spec — Sleeping-session indicator on `ConversationRow` (#20)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:32-80` — the composable as it stands after #17/#19. The new dot rendering extends the `headlineContent` `Row` (`:44-65`) at its leading edge. **Do not touch** `formatRelativeTime`, `previewText`, `condenseWorkspace`, the `supportingContent` slot, or the `trailingContent` slot. The two `@Preview` composables at `:127-154` and the `previewConversation` helper at `:156-165` are the only preview-side edits.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — five-field `data class` plus the `DefaultScratchCwd` constant at `:19`. The new field `isSleeping: Boolean = false` appends at the end of the constructor (after `lastUsedAt`). Default value is the load-bearing decision — it keeps the 9 existing `Conversation(...)` constructor sites compiling unchanged.
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt` — three-field `data class` whose `endedAt: Instant?` is the heuristic anchor. Read-only; do not modify.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:32-44` — the `observeConversations` projection. This is the one place that stamps `isSleeping` onto the emitted `Conversation`. The projection has both halves of the lookup (the `ConversationRecord` carries `sessions: Map<String, Session>` and the `Conversation` carries `currentSessionId`), so the derive is one expression — no new repository surface, no new state shape.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:253-280,318-381` — the `seed-channel-joi-pilates` block and the `seedChannel(...)` factory. One seed gets its current session marked closed so the indicator is visible at runtime, not only in previews. The factory grows one optional parameter.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:58-97` — the `ChannelListScreenLoadedPreview`. The third channel ("scratch ideas") gets `isSleeping = true` so the list-level preview also exercises the indicator.
- `docs/specs/architecture/19-conversation-row-workspace-label.md` — the sibling-decoration precedent. Its §3 ("Label rendering — `Text` inside a `Row` in `headlineContent`") establishes the composition pattern this ticket extends. The "no chips, no surfaces, no extra widgets when a `Box`/`Text` suffices" discipline carries forward. The "label has natural width, name flex-weights, decorations are children of the same `Row`" geometry is reused — the dot is one more natural-width child at the leading edge.
- `docs/specs/architecture/17-conversation-row-composable.md` — the parent spec. Import discipline (additive only; alphabetised), preview shape, M3-token-bound styling, and the `private` helpers-in-the-same-file precedent all carry forward.
- `gradle/libs.versions.toml` and `app/build.gradle.kts:46` — confirm only `androidx-compose-material3` is on the classpath. `material-icons-extended` is **not** declared; do not add it (see §3 below for why a `Box`-based dot is chosen over an icon).

## Context

`ConversationRow` (#17) renders a single channel/discussion row with name, last-message preview, and trailing relative timestamp. `#19` added the first headline decoration (a condensed workspace label). This ticket adds the second: a small status indicator that distinguishes conversations whose current Claude session is sleeping (closed/evicted) from active ones.

The data layer already has the signal — every `Session` carries `endedAt: Instant?` and the seed/factory paths set it to `null` for live sessions and stamp it with a timestamp when a session closes (`FakeConversationRepository.mintNewSession` at `:161-164`). The job is to surface that signal through `observeConversations` and render it on the row.

The composition seam established by #19 — a `Row` inside `headlineContent` with the name flex-weighted and decorations sitting at their natural width — is the precedent this ticket extends. The dot becomes the leading-edge child of that `Row`, alongside the trailing-edge workspace label.

## Design

### 1. Plumbing — derive `isSleeping` on `Conversation` at projection

**Chosen path: option (c) from the ticket's Technical Notes — a derived field promoted onto `Conversation`.**

Why this over the other two options the ticket offers:

- **Option (a), `isSleeping: Boolean` parameter on `ConversationRow`:** Forces every caller to compute and supply sleeping state. `ChannelListScreen` receives `List<Conversation>` from `ChannelListViewModel`; the screen has no access to `Session` data. To wire (a) end-to-end, `ChannelListUiState.Loaded` would have to change shape (e.g. `List<Pair<Conversation, Boolean>>` or a new `ChannelRow` record), the VM would have to read sessions from a new repository method, and the existing `ChannelListViewModelTest`'s `sampleChannel` fixture would have to grow alongside. That's a structural change to a state-shape that #45 just stabilised. Rejected.
- **Option (b), `currentSession: Session?` parameter on `ConversationRow`:** Same plumbing cascade as (a), but worse — pulls a domain type with three irrelevant fields (`id`, `conversationId`, `claudeSessionUuid`, `startedAt`) through the UI surface just to read `endedAt`. The row doesn't need the session; it needs the derived boolean. Rejected.
- **Option (c), derived field on `Conversation`:** The repository is the only place that already holds both halves of the lookup (the conversation and its session map). Doing the derive there means callers (VM, screen, row) consume an unchanged surface — `List<Conversation>` in, render decisions in the row based on its own input. The model grows one `Boolean` field with a default; no state shape changes; no test fixtures break. **Picked.**

This also matches the long-arc story in `CLAUDE.md` — `Conversation` is described as a server-side entity that the eventual `conversations.json` registry will emit; whether the conversation's current session is active or sleeping is a status fact about the conversation that a real backend will produce alongside `name`, `cwd`, etc. Adding the field now anticipates that shape without committing to it (the field is derived, not stored — Phase 4 will simply parse it from the backend response).

#### 1a. Model change — `Conversation.kt`

Append one field to the constructor parameter list, after `lastUsedAt`:

```kotlin
data class Conversation(
    val id: String,
    val name: String?,
    val cwd: String,
    val currentSessionId: String,
    val sessionHistory: List<String>,
    val isPromoted: Boolean,
    val lastUsedAt: Instant,
    val isSleeping: Boolean = false,
)
```

Why the default `false`:

- The 9 existing `Conversation(...)` constructor sites (4 in tests/previews, 3 in `FakeConversationRepository`, 1 in `ChannelListScreen` preview, 1 in `ConversationRow.kt`'s `previewConversation`) continue to compile without edits. Sites that need to assert sleeping explicitly (preview adjustments below) pass `isSleeping = true` as a named argument.
- It mirrors the existing field discipline — the only optional-ish field today is `name: String?` (modelled with `?`, not a default), because `null` is a meaningful value there. For `isSleeping`, the meaningful default *is* `false` (the active state); a default value is idiomatic.
- It keeps `ConversationTest` (`copy_overrides_named_field_and_preserves_rest`, etc.) green without edits. Same for `ChannelListViewModelTest`'s `sampleChannel`.

#### 1b. Projection — `FakeConversationRepository.observeConversations`

The current projection at `:32-44`:

```kotlin
override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
    state.map { records ->
        records.values
            .map { it.conversation }
            .filter { conv -> when (filter) { ... } }
            .sortedByDescending { it.lastUsedAt }
    }
```

Replace `.map { it.conversation }` with a `.map { record -> ... }` that stamps `isSleeping` onto a `copy`:

```kotlin
override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
    state.map { records ->
        records.values
            .map { record ->
                val current = record.sessions[record.conversation.currentSessionId]
                record.conversation.copy(isSleeping = current?.endedAt != null)
            }
            .filter { conv -> when (filter) { ... } }
            .sortedByDescending { it.lastUsedAt }
    }
```

Notes:

- **`current?.endedAt != null` (not `?.let { it.endedAt != null } ?: false`).** The null-safe `?.` short-circuits the whole expression to `null`, and `null != null` is `false`. Concise idiomatic Kotlin.
- **Defensive interpretation of a missing session.** If `record.sessions[currentSessionId]` is `null` (a programming error — the invariant in `mintNewSession` is that `currentSessionId` always points at a session in the map), the conversation projects as `isSleeping = false` rather than crashing the flow. The fake-repo invariant test `seededChannels_haveTwoSessionsInHistory_endingWithCurrentSessionId` already guards the construction side; we don't need a second crash path at the projection.
- **No new repository method.** The interface in `ConversationRepository.kt` is unchanged. Phase 4's Ktor-backed implementation will parse `isSleeping` from the backend response — same field, different source.

#### 1c. Heuristic justification (AC #3)

**Picked heuristic: `current?.endedAt != null` on the conversation's `currentSession`.**

This is the default heuristic offered in AC #3 (`Session.endedAt != null`), and is the right call for Phase 1:

- **It hooks into an established data-layer invariant.** `mintNewSession` (`FakeConversationRepository.kt:161-164`) is the only path that closes a session — it stamps `endedAt = now` on the formerly-current session and writes a new active session with `endedAt = null`. So `currentSession.endedAt != null` is true exactly when "the current session has been closed and no new one has started yet" — which is the literal definition of a sleeping conversation in the data model.
- **The alternative — idle-threshold against `lastUsedAt`** — would introduce a clock-dependent UI heuristic ("if `now - lastUsedAt > N hours`, treat as sleeping"). That gates rendering behaviour on the test clock vs. wall clock and produces flicker as time advances during a session. The closed-session signal is monotonic and clock-independent. Rejected.
- **The alternative — promoting an explicit field that the repository writes on a timer** — would require background work in Phase 1 to flip the flag when a session ages out. There's no idle-eviction loop in the fake repo today, and adding one is far beyond this ticket's scope. Rejected.
- **Phase 4 alignment.** When the real backend ships, the server will report sleeping state directly (it knows when claude evicted a session). The UI's contract — "render the indicator when `isSleeping` is true" — doesn't care whether the value came from `endedAt != null` derivation or a backend field. The heuristic is replaceable; the contract is stable.

### 2. Seed data — one channel becomes sleeping

For the indicator to be visible in the real app (not only in previews), at least one seeded conversation needs `isSleeping = true` after the projection runs. Add an optional `currentSessionEndedAt: Instant?` parameter to the `seedChannel` factory (`FakeConversationRepository.kt:318-327`), defaulting to `null` (current session active). When passed a non-null timestamp, the factory builds the current `Session` with `endedAt = currentSessionEndedAt` instead of `endedAt = null`.

Diff against `seedChannel`:

```kotlin
private fun seedChannel(
    id: String,
    name: String,
    cwd: String,
    sessionId: String,
    claudeSessionUuid: String,
    lastUsedAt: Instant,
    history: List<SeedSession> = emptyList(),
    currentMessages: List<SeedMessage> = emptyList(),
    currentSessionEndedAt: Instant? = null,
): ConversationRecord {
    val currentSession = Session(
        id = sessionId,
        conversationId = id,
        claudeSessionUuid = claudeSessionUuid,
        startedAt = lastUsedAt,
        endedAt = currentSessionEndedAt,
    )
    // ... rest unchanged
```

Then, in the seed list at `:253-280`, pass the parameter on **`seed-channel-joi-pilates` only**:

```kotlin
seedChannel(
    id = "seed-channel-joi-pilates",
    name = "Joi Pilates",
    cwd = "~/Workspace/joi-pilates",
    sessionId = "seed-session-joi-pilates",
    claudeSessionUuid = "seed-claude-joi-pilates",
    lastUsedAt = Instant.parse("2026-05-08T15:30:00Z"),
    history = listOf( /* unchanged */ ),
    currentMessages = listOf( /* unchanged */ ),
    currentSessionEndedAt = Instant.parse("2026-05-09T03:00:00Z"),
),
```

Why this channel:

- It's the most stale of the three channels (`lastUsedAt = 2026-05-08`, behind `seed-channel-pyrycode-mobile` at `2026-05-10` and `seed-channel-personal` at `2026-05-05`). A user revisiting the app after a few hours away would plausibly find this conversation's claude session evicted. Semantically natural.
- It doesn't disturb the ordering invariants the existing `FakeConversationRepositoryTest` checks — `isSleeping` is read on the *current* session; the `sessionHistory.size == 2` and `currentSessionId == sessionHistory.last()` invariants at `:73-93` are unaffected.
- One sleeping channel out of three keeps the demo proportionate — the list reads as "mostly active, one snoozed" rather than "everything's sleeping."

The timestamp `2026-05-09T03:00:00Z` is chosen to be after `lastUsedAt` (the session can only end at-or-after it started) and before `currentDate` (so it reads as historical, not future-dated).

### 3. Visual treatment — leading status dot inside `headlineContent`

**Picked shape: an 8.dp circular `Box` filled with `MaterialTheme.colorScheme.outline`, rendered as the first child of the existing `headlineContent` `Row`.**

Why a `Box`-based dot, not an icon:

- **The Material icon family that contains a "sleeping" / "moon" glyph (`Icons.Outlined.Bedtime`, `Icons.Filled.NightsStay`, etc.) lives in `androidx.compose.material:material-icons-extended`, which is *not* on the classpath today.** Adding that dependency would inflate the APK by several MB of icon bytecode (the library bundles thousands of icons). Pulling in a multi-MB dependency for one glyph in one composable fails the "no chips, no surfaces, no extra widgets when a simpler primitive suffices" discipline #17/#19 established. A `Box` with `CircleShape` is a single-primitive substitute that satisfies the AC.
- **`Icons.Outlined.Schedule` (a clock face) is in `material-icons-core` and would work without adding a dependency, but a clock reads as "time/duration" rather than "asleep" — semantically muddier.** The leading dot is a widely-recognised status idiom (unread/active dots in chat lists, etc.); for the inverse case ("muted/sleeping") the same affordance with a low-contrast tint reads correctly.
- **The dot's leading position complements the workspace label's trailing position.** #19's label sits to the right of the name (after the flex-weight); the dot sits to the left of the name. The two decorations don't compete for the same headline real estate.

Why `MaterialTheme.colorScheme.outline`:

- M3 `outline` is the semantic role for inactive/decorative borders and dividers — exactly the right register for "this conversation is dormant." It's neither alarming (which `error` would be) nor invisible (which `surfaceVariant` would be against a `surface` background).
- Token-bound throughout, per `CLAUDE.md` ("M3 token-bound throughout — no hardcoded colors or typography"). No `Color(0x...)` literals.
- The `outline` role is colour-scheme-aware — automatically adjusts for dark mode. The "Without message — Dark" preview will exercise this.

#### 3a. Composition

Replace the existing `headlineContent` `Row` (`ConversationRow.kt:44-65`) with:

```kotlin
headlineContent = {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversation.isSleeping) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
        }
        Text(
            text = displayName,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val workspaceLabel = condenseWorkspace(conversation.cwd)
        if (workspaceLabel != null) {
            Text(
                text = workspaceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
},
```

Properties:

- **Dot diameter 8.dp.** Reads as a status mark next to a `titleMedium` headline (typical line-height ~20.dp); large enough to be unmistakable, small enough not to compete with the name.
- **`Arrangement.spacedBy(8.dp)` is unchanged.** The same 8.dp gap that already separates name from workspace-label now also separates dot from name. The visual rhythm is consistent.
- **The dot is absent when `isSleeping == false`.** The `if` is structural — `spacedBy` only contributes gaps between children that actually render, so an active row's headline reads exactly as it did post-#19. AC #2 holds: active rows are visually unchanged from the #17/#19 baseline.
- **No `Modifier.alpha` on the `ListItem`.** Considered (and rejected) was reducing the whole row's opacity in addition to the dot. Two signals would be redundant and would push the supporting-content preview text into a "looks disabled, not tappable" register. The dot alone is the entire signal. The row stays tappable, the preview stays legible.

#### 3b. Imports — additive only

Add to the import block (alphabetised insertion into existing groups):

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
```

Notes:

- All four are in libraries already on the classpath (`androidx.compose.foundation` is a transitive dependency of `androidx.compose.material3`). No `gradle/libs.versions.toml` edit needed.
- **Forbidden, same as #17/#19:** `Color(0x...)` literals, `androidx.compose.material3.AssistChip` / `SuggestionChip`, `androidx.compose.material3.Surface(...)`, `androidx.compose.material.icons.*` (would imply adding `material-icons-extended`).
- After writing, verify with `grep -E 'AssistChip|SuggestionChip|Surface\(|Color\(0x|material\.icons' app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` — must return empty.

### 4. Preview adjustments (AC #4)

#### 4a. `ConversationRow.kt` preview pair

The two existing previews (`ConversationRowWithMessagePreview` at `:127-137` and `ConversationRowWithoutMessagePreview` at `:139-154`) currently cover the two-dimensional matrix (message-present × theme × `isPromoted`/label). Adding a third preview just for sleeping state would bloat the file. Instead, **the "Without message — Dark" preview's conversation flips `isSleeping = true`**, so the dark preview now covers three dimensions: no message, no workspace label (discussion in scratch), AND sleeping. The light preview stays active.

Extend `previewConversation` to take an `isSleeping: Boolean` parameter (default `false`):

```kotlin
private fun previewConversation(
    name: String?,
    isPromoted: Boolean,
    isSleeping: Boolean = false,
): Conversation =
    Conversation(
        id = "preview-1",
        name = name,
        cwd = if (isPromoted) "~/Workspace/Projects/pyrycode-mobile" else DefaultScratchCwd,
        currentSessionId = "session-1",
        sessionHistory = emptyList(),
        isPromoted = isPromoted,
        lastUsedAt = Clock.System.now() - 2.hours,
        isSleeping = isSleeping,
    )
```

Then update only the dark-preview call site:

```kotlin
ConversationRow(
    conversation = previewConversation(name = null, isPromoted = false, isSleeping = true),
    lastMessage = null,
    onClick = {},
)
```

Do **not** rename the existing preview functions or change their `@Preview` annotations. `ConversationRowWithoutMessagePreview` continues to describe the *message* dimension; it now also happens to cover sleeping — that's the deliberate three-dimensions-in-two-previews compaction, same pattern #19 used for two-dimensions-in-two-previews.

**Verifies AC #4** ("at least one `@Preview` composable demonstrates the sleeping state") and AC #2 (the light preview, which now passes `isSleeping = false` by default, continues to render visually unchanged from the #17/#19 baseline).

#### 4b. `ChannelListScreen.kt` preview

The list-level preview at `:58-97` should exercise the indicator at list density (where the dot is most useful — quick visual scan of which channels are dormant). Modify only the third channel literal at `:81-89` to pass `isSleeping = true`:

```kotlin
Conversation(
    id = "channel-3",
    name = "scratch ideas",
    cwd = DefaultScratchCwd,
    currentSessionId = "session-3",
    sessionHistory = emptyList(),
    isPromoted = true,
    lastUsedAt = now - 3.days,
    isSleeping = true,
),
```

Leave the other two preview channels (`channel-1`, `channel-2`) active — same proportionate "mostly active, one snoozed" feel as the seed data.

This preview update is **not** required to satisfy AC #4 (which only asks for at least one preview, and §4a delivers it) — but it's a 1-line addition that exercises the decoration at the list level and matches the visual ergonomics the runtime user will see. Worth it.

### 5. Imports — `FakeConversationRepository.kt`

No new imports needed:

- `Instant` is already imported (used by existing seed timestamps).
- `Session.copy` and `Conversation.copy` use Kotlin's generated `data class` `copy` — no import.
- The new `seedChannel` parameter `currentSessionEndedAt: Instant?` reuses the existing `kotlinx.datetime.Instant` import.

For `ConversationRow.kt`, see §3b.

For `ChannelListScreen.kt`, no new imports — `Conversation` and `DefaultScratchCwd` are already imported.

## State + concurrency model

None. The change is structural and synchronous:

- `Conversation.isSleeping` is a `val Boolean` on a `data class` — Compose-stable. The row only recomposes when the input `Conversation` changes (e.g., session closed → new emission → row recomposes with the new flag). No `remember`, no `LaunchedEffect`, no `derivedStateOf`.
- The projection in `observeConversations` runs inside `Flow.map`, on whatever dispatcher the collector is on (the VM uses `viewModelScope`'s `Main` by default; the `stateIn(SharingStarted.WhileSubscribed)` shares a single upstream subscription). No new dispatcher concerns.
- The `Box` + `background(CircleShape)` is drawn during composition / draw phase — no off-screen work, no allocation per recomposition beyond the `Modifier` chain.

## Error handling

N/A. No I/O, no parse paths, no failure modes.

The only defensive branch is the `current?.endedAt != null` null-safety in the projection (§1b). If `record.sessions[currentSessionId]` is `null` — a programming error — the conversation projects as `isSleeping = false` rather than throwing. This is intentional: the projection should never crash the conversations Flow, and the seed invariant (currentSessionId points at a session in the map) is enforced upstream in the factories.

## Testing strategy

Same posture as #17/#19: AC #6's only build gate is `./gradlew assembleDebug`. No new test file. No widening of `private` visibility for testing alone.

Existing tests should pass without modification:

- `ConversationTest` (4 tests) — `isSleeping: Boolean = false` is a new field with a default; `equals`/`hashCode`/`copy` semantics are unchanged for fixtures that don't mention it.
- `ChannelListViewModelTest` — `sampleChannel` doesn't pass `isSleeping`; gets default `false`; all four tests (`initialState_isLoading`, `loaded_whenSourceEmitsNonEmpty`, `empty_whenSourceEmitsEmptyList`, `error_whenSourceFlowThrows`) are flag-agnostic.
- `FakeConversationRepositoryTest` — the existing invariants (`seededChannels_haveTwoSessionsInHistory_endingWithCurrentSessionId` at `:73-93`, `seededDiscussions_remainEmpty`, the `startNewSession`/`changeWorkspace` tests at `:212-223`) all read fields the projection doesn't disturb. The `seed-channel-joi-pilates` change to `currentSessionEndedAt = 2026-05-09T03:00:00Z` does **not** affect `sessionHistory.size`, `currentSessionId == sessionHistory.last()`, or the `currentSessionId.isNotBlank()` assertions — those touch IDs, not `endedAt`.

If a developer wants to add a focused regression test, the natural spot would be a single test in `FakeConversationRepositoryTest` asserting that the projection stamps `isSleeping = true` for `seed-channel-joi-pilates` and `false` for the other two channels. This is **not required by AC #6** but is cheap if the developer chooses to add it. The dot rendering itself is not tested — the `ComposeTestRule` non-goal from #17/#19 carries forward.

## Open questions

- **Dot diameter and color tone.** Spec picks `8.dp` and `colorScheme.outline`. If in the IDE preview the dot reads either too subtle (lost against the surface) or too loud (competes with the name), a one-step adjustment to `10.dp` or to `colorScheme.onSurfaceVariant` (one or the other, not both) is acceptable without re-running through architect. The AC pins the rule (show/hide/where/semantic colour-role), not the exact diameter or token.
- **Dark-mode contrast.** `colorScheme.outline` in M3 dark themes is typically a medium grey (~0.4 alpha on `onSurface`). The "Without message — Dark" preview will be the first place this is visually verified — if it disappears against the dark surface, the developer should escalate (it would suggest the M3 token wasn't right for this role). Falling back to `colorScheme.primary` at reduced size would be the next step, but try `outline` first.
- **Future Phase 4 wiring.** When the real backend ships, `isSleeping` will be parsed from the conversations endpoint response rather than derived from `Session.endedAt`. The interface contract — `Conversation.isSleeping: Boolean` — is what survives; the projection in `FakeConversationRepository` is throwaway. No spec action needed today, just naming the seam.

## Out of scope (do not implement)

- **Real session-eviction logic** — there's no idle-eviction loop, no background tick that flips active sessions to sleeping after N hours. Per ticket Technical Notes, that's Phase 4 backend territory.
- **Wake-up affordance / re-attach UX** — tapping a sleeping conversation behaves identically to tapping an active one (it routes via the existing `RowTapped` event from #46). What happens on the destination screen when the session is sleeping is a separate ticket.
- **Surfacing the eviction reason** — `BoundaryReason` exists in `ConversationRepository.kt:64` (`Clear` / `IdleEvict` / `WorkspaceChange`), but the row only reports binary sleeping state. Per ticket Technical Notes, the reason is not surfaced.
- **Tooltip / long-press to explain "sleeping"** — accessibility-affordances polish, separate ticket.
- **A `SleepingIndicator` shared component** — the dot is six lines inside `ConversationRow.kt`. Extract when a second consumer appears. YAGNI applies, same trap #19 avoided with `condenseWorkspace`.
- **Adding `material-icons-extended` to the classpath** — see §3 for why. Cross-ticket cost (APK bloat) for single-ticket benefit.
- **Updating older specs (`docs/specs/architecture/2-*.md`, etc.) to reference `isSleeping`** — historical artefacts. The new field doesn't retroactively invalidate them.
- **i18n** — no user-visible string is introduced by this ticket. (The dot has no `contentDescription` because it's purely decorative; the sleeping state is implicit in the visual, and explicit accessibility narration is a follow-up ticket if anyone files it.)
- **Tests for the projection or the dot rendering** — explicit non-goal per §Testing strategy and the precedent in #17/#19.
