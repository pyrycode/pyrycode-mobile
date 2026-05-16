# 177 — Archive row with inline restore + snackbar

## Context

`ArchivedDiscussionsScreen` currently renders archived items via a private `ArchivedDiscussionRow` wrapper that composes `ConversationRow` with `Modifier.alpha(0.65f)` and a long-press `DropdownMenu` carrying a single "Restore" action. Figma 18:2 specifies a purpose-built row: avatar + name + "Archived <relative-time>" subtitle, with a trailing 40dp icon-button that restores in one tap. Tapping that button confirms via a `Snackbar` reading `Restored <name>` (no Undo — that's a separate ticket).

This slice introduces `ArchiveRow`, swaps it into both tabs of `ArchivedDiscussionsScreen`, deletes the long-press wrapper, and wires a snackbar via a one-shot effect channel on the existing `ArchivedDiscussionsViewModel`.

#176 (tab header) is now closed; this is the second slice from #125.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

Row anatomy is a `ListItem`-shaped layout (12dp horizontal padding, 12dp vertical, 12dp gap): leading 40dp circular avatar (using the existing `ConversationAvatar` for color/initials parity with the channel list), a column with name in `MaterialTheme.typography.titleMedium` on `colorScheme.onSurface` and a `bodySmall` subtitle "Archived <relative-time>" on `colorScheme.onSurfaceVariant` at 75% opacity, and a trailing 40dp `IconButton` containing a 22dp restore-arrow icon on `colorScheme.onSurfaceVariant`. No row-level alpha dimming; archived rows render at full opacity in this design.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt` — current screen; `ArchivedDiscussionRow` (private, ~25 lines) is what's being replaced; `LoadedBody` is where the swap happens
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt` — `ArchivedDiscussionsUiState`, `ArchivedDiscussionsEvent`, and `onEvent`'s `RestoreRequested` branch are all extended here
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:28-78` — existing row layout to keep visually consistent; **lines 30-32** hold the `displayName` fallback (`"Untitled channel"` / `"Untitled discussion"`) that `ArchiveRow` must mirror
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatar.kt:31-54` — reuse for the leading avatar; takes a `Conversation`, sizes itself to 40dp
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt` — `formatRelativeTime(instant)` is `internal`; the new row is in the same package, so the call is direct
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:219-235` — `Routes.ARCHIVED_DISCUSSIONS` composable; the route is where `vm.effects` is collected
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt:251-264` — existing `restoreRequested_callsUnarchive_withConversationId` test; its `RestoreRequested(...)` constructor call needs updating to pass the new `displayName` argument
- `app/src/main/res/values/strings.xml:8,25-32` — existing strings around archive/restore; new `restored_snackbar`, `cd_restore_archive`, and `archived_relative_subtitle` strings join here; `restore_action` becomes unused and is deleted

## Design

### New component — `ArchiveRow`

Location: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ArchiveRow.kt`.

Signature:

```kotlin
@Composable
fun ArchiveRow(
    conversation: Conversation,
    displayName: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`displayName` is hoisted as a parameter (not computed inside) so the caller — which already computes it to drive the `RestoreRequested` event payload — passes the same string used in the snackbar. This avoids duplicating the fallback logic in two places.

Behavior:
- Renders as a `Row` with the avatar + name/subtitle column + trailing `IconButton`, matching the Figma anatomy in the Design source section.
- Subtitle string is built via `stringResource(R.string.archived_relative_subtitle, formatRelativeTime(conversation.lastUsedAt))` — see § Strings.
- The trailing `IconButton` uses `Icons.Filled.Restore` (counter-clockwise arrow, semantic match for "restore from archive") sized to 22dp via `Modifier.size(22.dp)` on the `Icon`, with `contentDescription = stringResource(R.string.cd_restore_archive, displayName)`. The `IconButton`'s default 48dp touch target is overridden to 40dp via `Modifier.size(40.dp)` to match Figma.
- The row itself is NOT clickable in this slice; only the trailing icon-button is interactive.

`Conversation.lastUsedAt` is used as the archive timestamp source. A dedicated `archivedAt` field is out of scope (would require model + repository changes well outside this slice).

### Modifications — `ArchivedDiscussionsScreen.kt`

- Delete the private `ArchivedDiscussionRow` composable (lines ~134-159 in the current file) and its `ArchivedDiscussionsRowMenuPreview` (~last preview). Drop now-unused imports: `androidx.compose.foundation.layout.Box`, `androidx.compose.material3.DropdownMenu`, `androidx.compose.material3.DropdownMenuItem`, `androidx.compose.ui.draw.alpha`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`, `androidx.compose.runtime.getValue`, `de.pyryco.mobile.ui.conversations.components.ConversationRow`.
- Add new imports: `de.pyryco.mobile.ui.conversations.components.ArchiveRow`, `androidx.compose.material3.SnackbarHost`, `androidx.compose.material3.SnackbarHostState`, plus `androidx.compose.ui.platform.LocalContext`, `androidx.compose.runtime.LaunchedEffect`, `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.emptyFlow`.
- Extend `ArchivedDiscussionsScreen`'s signature with a third parameter:

  ```kotlin
  effects: Flow<ArchivedDiscussionsEffect> = emptyFlow(),
  ```

  The default keeps existing previews and any future call sites that don't care about effects from breaking.
- Inside `ArchivedDiscussionsScreen`, before `Scaffold`:
  - `val snackbarHostState = remember { SnackbarHostState() }`
  - `val context = LocalContext.current`
  - `LaunchedEffect(effects, snackbarHostState) { effects.collect { effect -> when (effect) { is ArchivedDiscussionsEffect.RestoreSucceeded -> snackbarHostState.showSnackbar(context.getString(R.string.restored_snackbar, effect.displayName)) } } }`
- Add `snackbarHost = { SnackbarHost(snackbarHostState) }` to the `Scaffold` parameters.
- In `LoadedBody`'s `LazyColumn`, replace the `ArchivedDiscussionRow(...)` call site with:

  ```kotlin
  val displayName = conversation.displayName()
  ArchiveRow(
      conversation = conversation,
      displayName = displayName,
      onRestore = {
          onEvent(ArchivedDiscussionsEvent.RestoreRequested(conversation.id, displayName))
      },
  )
  ```

  Where `Conversation.displayName()` is a private file-local extension function in `ArchivedDiscussionsScreen.kt` mirroring the existing fallback logic (`name?.takeIf { it.isNotBlank() } ?: if (isPromoted) "Untitled channel" else "Untitled discussion"`). Keep it private to this file — `ConversationRow` keeps its own inline copy; **do not refactor `ConversationRow`** as part of this ticket.

### Modifications — `ArchivedDiscussionsViewModel.kt`

- Add a new sealed effect type at the top of the file, next to `ArchivedDiscussionsEvent`:

  ```kotlin
  sealed interface ArchivedDiscussionsEffect {
      data class RestoreSucceeded(val displayName: String) : ArchivedDiscussionsEffect
  }
  ```

- Update `RestoreRequested`:

  ```kotlin
  data class RestoreRequested(
      val conversationId: String,
      val displayName: String,
  ) : ArchivedDiscussionsEvent
  ```

- Inside the VM class, add:

  ```kotlin
  private val _effects = Channel<ArchivedDiscussionsEffect>(Channel.BUFFERED)
  val effects: Flow<ArchivedDiscussionsEffect> = _effects.receiveAsFlow()
  ```

- Replace the `RestoreRequested` branch in `onEvent`:

  ```kotlin
  is ArchivedDiscussionsEvent.RestoreRequested ->
      viewModelScope.launch {
          repository.unarchive(event.conversationId)
          _effects.send(ArchivedDiscussionsEffect.RestoreSucceeded(event.displayName))
      }
  ```

  If `unarchive` throws, the effect is not sent (no snackbar) — matches the AC's "on successful restore". The exception propagates to the coroutine and is logged by the default uncaught-exception handler; UI error surfacing for failed restores is out of scope for this slice.

- New imports: `kotlinx.coroutines.channels.Channel`, `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.receiveAsFlow`.

### Modifications — `MainActivity.kt`

In the `Routes.ARCHIVED_DISCUSSIONS` composable block, pass `effects = vm.effects` into `ArchivedDiscussionsScreen(...)`. The `onEvent` lambda's `RestoreRequested` branch already forwards via `vm.onEvent(event)` — that keeps working since the event now carries `displayName` end-to-end. No other changes here.

### Strings

Add to `app/src/main/res/values/strings.xml`:

- `<string name="archived_relative_subtitle">Archived %1$s</string>` — subtitle text under the name. `%1$s` is the `formatRelativeTime` output (e.g. "2 weeks ago" — note: `formatRelativeTime` currently emits forms like `"2w ago"` / `"Yesterday"`; the Figma copy "2 weeks ago" is illustrative only, we use what the helper produces).
- `<string name="restored_snackbar">Restored %1$s</string>` — snackbar copy.
- `<string name="cd_restore_archive">Restore %1$s</string>` — IconButton contentDescription.

Delete `<string name="restore_action">` — it was used only by the now-deleted `DropdownMenuItem`. Grep first; if any other reference appears, leave it and note in the PR.

## State + concurrency model

- `ArchivedDiscussionsUiState` is unchanged — the existing `Loading` / `Loaded` / `Error` shape covers the row swap with no new fields.
- A new `ArchivedDiscussionsEffect` sealed type is introduced for one-shot UI signals (snackbar). Channel is `Channel.BUFFERED`; restore taps are user-initiated and infrequent, so backpressure isn't a concern but the buffered channel guarantees an effect emitted while the screen is briefly paused (e.g. configuration change in flight) isn't dropped on the floor.
- `effects` is exposed as a cold `Flow` via `receiveAsFlow()`. The route composable collects it inside `LaunchedEffect(effects, snackbarHostState)`. Each emitted effect is consumed exactly once; subsequent collectors of the same channel wouldn't see past values (which is what we want — no replay of stale snackbars on recomposition).
- All work runs on `viewModelScope` (Main dispatcher); `repository.unarchive` is a `suspend fun` and dispatches internally if needed. Tab selection remains a `MutableStateFlow<ArchiveTab>` write — no change.
- Cancellation: when the screen leaves composition, the route's `koinViewModel` keeps the VM alive across configuration changes; `viewModelScope.launch` for restore completes regardless. If the user navigates back before the launched coroutine sends the effect, the effect is buffered in the channel and the next consumer (none) won't see it — which is correct.

## Error handling

- Repository load errors continue to surface as `ArchivedDiscussionsUiState.Error` via the existing `.catch` operator on the combined flow. Unchanged.
- A failed `repository.unarchive` (currently never throws in `FakeConversationRepository`; the production Ktor implementation lands in Phase 4) skips the effect emission, so no snackbar. UI-level error feedback for the failure case is deferred. If we want it later, the natural shape is `ArchivedDiscussionsEffect.RestoreFailed(displayName)` plus a different snackbar string — but no code for that here.
- `Channel.send` from `viewModelScope.launch` cannot throw under `Channel.BUFFERED` (unbounded buffer), so no try/catch is needed around it.

## Testing strategy

Unit (`./gradlew test`) — extend `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt`:

- Update existing `restoreRequested_callsUnarchive_withConversationId` to pass a displayName: `RestoreRequested("disc-7", "Untitled discussion")`. Behavior assertion (`unarchiveCalls`) is unchanged.
- New test `restoreRequested_emitsRestoreSucceededEffect_afterUnarchive` — given a `RecordingRepo`, dispatch `RestoreRequested("disc-7", "old-project-experiments")`, then collect the first emission from `vm.effects` and assert it's `ArchivedDiscussionsEffect.RestoreSucceeded(displayName = "old-project-experiments")`. Use `vm.effects.first()` inside the `runTest` block, started in a separate `launch` before the event is dispatched so the collector is subscribed when `send` runs.
- New test `restoreRequested_doesNotEmitEffect_whenUnarchiveThrows` — given a repository whose `unarchive` throws (use a new `throwingUnarchiveRepo` helper modeled on the existing `erroringRepo`), dispatch `RestoreRequested`, advance, and assert that no effect appears in a `withTimeoutOrNull(100.milliseconds) { vm.effects.first() }` — i.e. result is `null`.

No new instrumented tests. `ArchivedDiscussionsScreen` has no existing Compose tests; introducing a baseline is out of scope for this slice. The row's visual behavior is verifiable via the existing `@Preview` composables (add 1 new preview for `ArchiveRow` in light + dark, mirroring the pattern in `ConversationRow.kt`).

## Open questions

- **Restore icon — `Filled.Restore` vs `Filled.History` vs `AutoMirrored.Filled.Undo` vs `Filled.Replay`.** Spec picks `Icons.Filled.Restore` for semantic match. The Figma node uses an inline SVG (not a Material icon constant), so an exact match is impossible — if the rendered glyph reads visibly wrong against the Figma screenshot, the developer may swap to `Icons.Filled.Replay` (more closely matches the counter-clockwise-circular-arrow shape in the Figma render). Note the choice in the PR.
- **Undo action** is explicitly out of scope per ticket; the snackbar shows only the message text, no action button.
