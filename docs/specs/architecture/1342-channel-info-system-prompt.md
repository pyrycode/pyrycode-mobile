# #1342 — System prompt section in Channel info, and desktop's clear rule

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SystemPromptEditor.kt` → `SystemPromptEditor`, `SystemPromptEditorState.Loaded` — the read/draft/save/clear state this ticket finally mounts; `canSave`/`canClear` change here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt` → `ChannelInfoSheet`, `ChannelInfoSheetContent`, `SectionHeader`, `ActionsGrid` — the section slots between Memory and Actions and borrows the tonal action buttons.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onOverflowEvent` (`ChannelInfo`, `ChannelInfoDismiss`, `Archive`, `DeleteConfirm` are the four places the sheet opens or closes), `pendingChannelInfo`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadEvent` — three new members; `SaveAsChannelSubmit.toString` is the redaction precedent.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `state.channelInfoOpen` host of `ChannelInfoSheet`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ThreadScreen(...)` call that collects the view model's sibling flows.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the thread destination passes `repository(serverId, bundle)`, the `StableConversationRepository` facade, so an editor bound at construction survives a reconnect.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `submitChannelEdit` — the `promptToWrite` rule.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt` → `mapError` — `conversation.not_found` arrives as `IllegalArgumentException`, not `RelayErrorException`; every other code arrives as `RelayErrorException(code)`.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt` → `setSystemPrompt` — wire refusal codes are `protocol.malformed` and `conversation.not_found` (protocol-mobile.md § Setting a conversation's system prompt).
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → the Edit channel prompt well (`FieldWell`, `PROMPT_MIN_LINES`, 112 dp) the section's field mirrors.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/SystemPromptSection.tsx` → `deriveSystemPromptSection`, `WRITE_REJECTED`, copy constants — the contract.
- `../pyrycode-desktop/src/renderer/src/screens/channels/EditChannelDialog.tsx` → `promptWriteFor`.
- `docs/knowledge/features/system-prompt-editor.md` — the reconnect-bind reason the editor stayed out of list modals; the `backgroundScope` test pitfall.
- `docs/knowledge/features/channel-info-sheet.md` — section order, inline-literal convention, `ThreadScreenChannelInfoTest`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_createEditArchiveChannel_readsPromptBack`, `awaitPromptField`, `openChannelEditor`, `CHANNEL_INFO_ITEM`.

In-flight overlap check: no other `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48 (Channel Info Sheet); field borrowed from https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=500-2120 (Edit channel content).

No frame draws this section; the ticket directs borrowing. A `SectionHeader("System prompt")` (`labelLarge`, `onSurfaceVariant`) after Memory; a hint line (`bodySmall`, `onSurfaceVariant`); a filled prompt well in the Edit channel field's shape (6 dp rounded, 16 dp inset, 112 dp / 4-line minimum, `bodyMedium`) on the sheet's own `surfaceContainerHighest`, because the modal's `modalFieldContainer` token is keyed to the modal surface, not `surfaceContainerLow`; a `bodySmall` byte count under it (`error` colour when over); status lines in `bodySmall`; then two equal-width `FilledTonalButton`s, Save and Clear, as `ActionsGrid` draws its cells. The Figma MCP server was not authenticated in this run, so the summary is from the ticket's borrowing instructions and the shipped components rather than a fresh node read.

## Context

Desktop's Channel info carries a System prompt section; mobile's does not, and `SystemPromptEditor` (#824) has never been constructed. The thread is the caller it was designed for: its repository is the `StableConversationRepository` facade, so the construction-time bind is safe there. Separately, Edit channel stores `""` for an emptied box where desktop clears the prompt (`promptWriteFor`).

## Design

### `SystemPromptEditor.kt`

- `Loaded.canSave` becomes `!saving && !overLimit`; `canClear` becomes `!saving` — `deriveSystemPromptSection`'s rules. `changed` stays (still the honest "box differs" fact), but no gate reads it.
- New `enum class SystemPromptRefusal { Malformed, NotFound, Unclassified }`.
- `Loaded` gains `refusal: SystemPromptRefusal? = null` (set together with `saveFailed = true`, cleared by `begin`) and `saved: Boolean = false` (set when a write's ack lands, cleared by `begin`). Redacted `toString` adds both (no prompt content).
- `refusalFor(error: Exception): SystemPromptRefusal` (internal, top-level): `RelayErrorException` with `protocol.malformed` → `Malformed`; `RelayErrorException` with `conversation.not_found` or `IllegalArgumentException` → `NotFound` (`RelayRequests.mapError` turns `conversation.not_found` into `IllegalArgumentException`; the only other IAE source, the pre-flight length `require`, is unreachable behind `canSave`); anything else → `Unclassified`. Only the code is read, never `message`.
- Write-line derivation stays in the composable (below), from `saving`/`saved`/`refusal`.

### `ThreadEvent` (`ThreadUiState.kt`)

`data class SystemPromptEdit(val text: String)` with a redacted `toString`, `data object SystemPromptSave`, `data object SystemPromptClear`.

### `ThreadViewModel`

- `private val promptEditor = MutableStateFlow<SystemPromptEditor?>(null)`.
- `val systemPrompt: StateFlow<SystemPromptEditorState?>` — `promptEditor.flatMapLatest { it?.state ?: flowOf(null) }`, `stateIn(viewModelScope, WhileSubscribed(5_000), null)`.
- `ChannelInfo` (only when the CAS opens the sheet) constructs `SystemPromptEditor(viewModelScope, repository, conversationId)`. Every path that closes the sheet (`ChannelInfoDismiss`, `Archive`, `DeleteConfirm`) goes through one private `closeChannelInfo()` that sets `pendingChannelInfo = false` and `promptEditor = null`. A dropped editor's in-flight write still completes (as desktop's does); its state is simply no longer observed.
- `SystemPromptEdit` / `Save` / `Clear` forward to `promptEditor.value?.edit/save/clear`.

### `ChannelInfoSheet.kt`

- `ChannelInfoSheet` and `ChannelInfoSheetContent` gain `systemPrompt: SystemPromptEditorState? = null`, `onSystemPromptChange: (String) -> Unit = {}`, `onSystemPromptSave: () -> Unit = {}`, `onSystemPromptClear: () -> Unit = {}` (after `modifier` on the shell, for `ComposeParameterOrder`). `null` omits the section — a preview/test seam; `ThreadScreen` always passes a value.
- Private `SystemPromptSection(state, ...)` between Memory and Actions, independent of `mutationsSupported` (desktop shows it for every conversation):
  - `Loading` → "Reading the stored prompt from the daemon"; `Unavailable` → "Couldn't read the stored system prompt." No field, no buttons in either.
  - `Loaded` → hint, field (`testTag(CHANNEL_INFO_PROMPT_FIELD_TAG)`, `contentDescription` "System prompt for this channel"), count "N / 8192 bytes", over-limit line, differs line when `appliedStatus == Differs`, write line, Save/Clear (`enabled = canSave` / `canClear`).
  - Write line: `saving` → "Saving"; `saved` → the Saved line; `saveFailed` → `WRITE_REJECTED[refusal ?: Unclassified]`; else none.
- Copy is desktop's verbatim (inline literals, the file's convention).

### `ThreadScreen.kt` / `MainActivity.kt`

`ThreadScreen` gains `systemPrompt: SystemPromptEditorState? = null`; the sheet host passes `systemPrompt ?: Loading` and routes the three callbacks through `onOverflowEvent`. `MainActivity` collects `vm.systemPrompt` with `collectAsStateWithLifecycle` and passes it.

### `ChannelListViewModel.submitChannelEdit`

`promptWriteFor`: the write happens when `draft != null && draft != read.prompt.orEmpty()`, and its value is `draft.takeIf { it.isNotEmpty() }` — an emptied box over stored text sends `null`. A boolean `writesPrompt` replaces the nullable `promptToWrite` as the "send anything" signal; the `channel_edited` log keeps `prompt=<bool>`.

## State + concurrency model

All on `viewModelScope` (Main). The editor's read and writes are launched in it, so clearing the view model cancels both. One editor per sheet open: reopening constructs a new one and its fresh read. `systemPrompt` is a hot `StateFlow`, `WhileSubscribed`, collected by the route.

## Error handling

Read failure → `Unavailable` line. Write failure → `saveFailed` + classified `refusal` line; draft and confirmed kept. Not-connected (`IllegalStateException`) → Unclassified. Nothing is logged beyond the editor's existing static event names; the prompt, its byte count and exception messages never reach a log.

## Testing strategy

- `SystemPromptEditorTest` (unit): update the unchanged-draft cases to desktop's rule (Save sends `""`/text even unchanged; Clear sends `null` with no stored prompt); add refusal classification (`protocol.malformed`, `IllegalArgumentException` for not-found, `RelayErrorException("conversation.not_found")`, other code, `IllegalStateException`), `saved` set on ack and reset by the next `begin`, `canSave` false over the limit.
- `ThreadViewModelSystemPromptTest` (unit, new): opening Channel info reads once and publishes `Loaded`; dismiss drops it to `null`; reopen reads again; Save/Clear events reach `setSystemPrompt`; a failed read publishes `Unavailable`.
- `ThreadScreenChannelInfoTest` (shared/Robolectric): section header after Memory and before Actions; loading line; unavailable line; loaded field, count, over-limit line, differs line, each write line (Saving/Saved/three refusals); Save/Clear callbacks and disabled states.
- `HostChannelListViewModelTest`: the emptied-box case now asserts `null`; the no-stored-prompt + empty-box case still sends nothing (already covered).
- Rung 3: extend `interactiveTurn_createEditArchiveChannel_readsPromptBack` — after step 6, empty the prompt in Edit channel and OK; assert the host reading is `null`; open the thread → Channel info and assert the section's field is empty with "0 / 8192 bytes"; close, reopen Edit channel and archive as before. Acceptance is the dispatcher's live-suite result. No rung-4 twin: the scripted fakeclaude path does not model a stored prompt.

## Open questions

- Should the section hide when `mutationsSupported` is false? Resolved: no. Desktop shows it for every conversation, and `set_system_prompt` is not one of the gated mutations.

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/system-prompt-editor.md`: the editor is now mounted in Channel info via `ThreadViewModel`; `canSave`/`canClear` follow desktop; refusal classification; clear rule.
- `docs/knowledge/features/channel-info-sheet.md`: the System prompt section, its order and states.
- Edit channel's clear rule (emptied box → `null`) wherever `submitChannelEdit` is described (`channel-list-viewmodel.md` § Wiring / `mobile-modal-callers.md`).
