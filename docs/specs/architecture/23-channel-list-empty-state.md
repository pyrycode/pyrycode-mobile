# Channel list empty state (#23)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:74-101` — the `when (state)` branch table and the private `CenteredText` composable. The Empty branch lives at line 78. The Loaded preview at lines 103-143 is the template for the new Empty preview.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:18-23` — the `ChannelListUiState` sealed interface; confirms `Empty` is a `data object` (no fields to construct in the preview).
- `app/src/main/res/values/strings.xml` — three existing entries; new resource follows the same shape.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:62-72` — the `empty_whenSourceEmitsEmptyList` test continues to pin the existing trigger semantics; no edits needed.

## Context

`ChannelListScreen` renders `ChannelListUiState.Empty` today as a centered placeholder literal `"No channels yet"` (line 78). The ticket replaces that copy with the spec'd `"Tap + to start a conversation"` from Plan.md and adds a `@Preview` so the Empty state is visible in Android Studio alongside the existing Loaded preview. The FAB (`Icons.Default.Add`) referenced by the copy already renders in the Empty state's Scaffold (see `floatingActionButton` block at line 62, added in #22), so the copy and the on-screen affordance line up at runtime.

## Design

### 1. String resource (new)

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="channel_list_empty">Tap + to start a conversation</string>
```

Same pattern as the existing `cd_open_settings` and `cd_new_discussion` entries — keep alphabetical-ish ordering loose, just append.

### 2. Compose body swap

In `ChannelListScreen.kt`, change the Empty branch literal to a `stringResource` lookup. The `CenteredText` helper stays untouched.

Before (line 78):
```kotlin
ChannelListUiState.Empty -> CenteredText("No channels yet", bodyModifier)
```

After:
```kotlin
ChannelListUiState.Empty -> CenteredText(stringResource(R.string.channel_list_empty), bodyModifier)
```

`R` and `stringResource` are already imported at the top of the file.

### 3. New `@Preview` for the Empty state

Append a second private preview composable named `ChannelListScreenEmptyPreview`, mirroring the existing `ChannelListScreenLoadedPreview` (line 103-143). Contract:

- `@Preview(name = "Empty — Light", showBackground = true, widthDp = 412)`
- Wraps `ChannelListScreen` in `PyrycodeMobileTheme(darkTheme = false)` (same as Loaded preview)
- Passes `state = ChannelListUiState.Empty` and `onEvent = {}`
- No `Clock.System.now()` or sample data needed — `Empty` is a `data object`

That's ~10 lines including the function signature. Don't introduce a `@PreviewParameterProvider`, a parameterised preview, or a parent helper that takes a state argument — touch only the Empty branch and add the new function.

### Decision: do NOT widen the VM trigger to "no channels AND no discussions"

The PO body delegates this call to the architect. Choice: **keep today's channels-only trigger**, do not modify `ChannelListViewModel.kt` or its test, and document the deferral as an open question.

Reasoning:
- Widening introduces a new UI case (channels empty, discussions non-empty) that has no clean representation in the current `ChannelListUiState`. `Loaded(emptyList())` would render a blank `LazyColumn` under the TopAppBar — strictly worse than today's "Tap + to start a conversation" copy, which still reads correctly even when discussions exist (the FAB does create a conversation).
- The deferred case is genuinely rare in Phase 1: it requires a user to create one or more discussions and never promote any, while having zero channels. The initial app state and the steady-state both naturally produce `channels=∅, discussions=∅` (true Empty) or `channels=non-empty, ...` (Loaded).
- A future ticket can revisit if/when a "no channels but has discussions" state needs a distinct UI affordance (e.g. "You have N discussions — promote one to a channel?"). That's a product decision, not a Phase 1 plumbing change.

Because the VM is untouched, AC #5 ("If the architect widens... unit tests cover the four cases") does not apply for this ticket — that AC was conditional ("If the architect widens").

## State + concurrency model

Unchanged. `ChannelListViewModel.state` still maps a single `repository.observeConversations(ConversationFilter.Channels)` flow → `Empty` when the emitted list is empty, `Loaded(channels)` otherwise. No new flows, no new dispatcher concerns, no new `viewModelScope` jobs.

## Error handling

Unchanged. The Empty branch is a pure UI presentation swap; no new failure modes. The existing `.catch { }` in the VM continues to surface `Error(message)` on flow exceptions.

## Testing strategy

- **Unit tests (`./gradlew test`):** no new tests required. `ChannelListViewModelTest.empty_whenSourceEmitsEmptyList` (line 62-72) continues to assert the existing trigger semantics and must keep passing unchanged.
- **Compose UI tests:** none in this ticket. The visual change is verified via the new `@Preview` rendered in Android Studio (manual visual check) and via build-time compilation (`./gradlew assembleDebug`).
- **Build:** `./gradlew assembleDebug` must pass. If the developer wants to render the previews from the command line, that is not required for AC.

## Open questions

- **Widening the empty trigger to "no channels AND no discussions":** deferred. If product wants a distinct UI for the edge case (channels=∅, discussions=non-empty), file a follow-up ticket that (a) widens the VM via `combine` and (b) introduces a new `ChannelListUiState` variant or extends `Loaded` with a discussion count + CTA. Do not retrofit `Loaded(emptyList())` for this case.
- **Optional illustration above the copy:** Plan.md leaves this as an open design call. Text-only is correct for Phase 1 per the AC; no design asset exists yet.
