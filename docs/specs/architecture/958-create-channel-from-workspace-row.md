# #958 — Create channel from a Channels-section workspace row

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel` (`openAddWorkspace`, `submitAddWorkspace`, `submitChatName`, `openWorkspaceEditor`, the `hostState` combine), `HostChannelListState.isHostConnected`, `AddWorkspaceState` — the shapes this ticket copies: open-by-own-host, resolve the repository at the press, `compareAndSet` terminals, flags not messages, navigation through `hostNavigationChannel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`, `ChannelListScreen`, `AddWorkspaceModalBinding` / `WorkspaceEditorModal`, `treeSection` — where the workspace row is drawn per section and the modal bindings live.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeWorkspaceRow`, `TreeRowControl`, `boundedRowText` — the row gains the plus; the control already gives a 48dp, own-semantics-node target that does not fold the row.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/HostWorkspaceGroup.kt` → `HostWorkspaceGroup` — the target is `serverId` + `cwd`, never `displayName`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt` → `SaveAsChannelDialog` — nearest analogue for the modal wrapper (buffers in `remember`, trimmed name + verbatim prompt, `nameEditable`).
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `ChannelFormFields` — the reused form (#957); it already focuses the name field inside the dialog's own composition.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` — the shell.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createChannel(name, workspace)` (#956), `setSystemPrompt`, `SystemPromptLimit`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ChannelListEvent` dispatch in the channel-list route.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `Fixture`, `Repo`, `seedCollidingWorkspaces` — two hosts holding the same `cwd`; the test double gains `createChannel` / `setSystemPrompt`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `workspaceRowPencil_inBothSections_...` — the row-control screen test this ticket mirrors.
- `docs/knowledge/features/save-as-channel-dialog.md` § "ThreadViewModel: the two-write state machine" — the create/prompt sequencing and why a result after dismissal must be inert; § "Not used: SystemPromptEditor" — same reasoning applies here (the prompt opens empty, nothing is read).
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § "Add controls (#738)" — `TreeRowControl` construction and the naming rule (each repeated control names its row).

In-flight overlap: #878 (tree attention dot) touches `ConversationTreeRows.kt`, `ChannelListScreen.kt`, `strings.xml` in different functions; #883 touches `MainActivity.kt` and `strings.xml` elsewhere; #946 touches `strings.xml`. No real dependency — edits here stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (shell), content https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2435, row controls https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=399-1059

The content is the #957 channel form inside the `MobileModal` shell with the title "Create channel": a `labelLarge` SemiBold "Channel name:" above an empty filled well, "Channel system prompt:" above a multi-line well, Cancel + OK footer (all already built by `MobileModal` + `ChannelFormFields`). The workspace row's hover state draws a pencil then a plus at the trailing edge in `primary`; the phone draws both permanently (no hover), as `Icons.Filled.Edit` then `Icons.Filled.Add` in the existing 48dp `TreeRowControl`.

## Context

Desktop's Create channel (pyrycode-desktop `CreateChannelDialog`) on the phone. The phone can create chats (host row plus) and promote chats (Save as channel), but not create a channel directly. The operation (`createChannel`, #956) and the form (`ChannelFormFields`, #957) already exist; this ticket wires an entry point, a modal and a two-write state machine on `ChannelListViewModel`. The live round trip is #676, not this ticket.

## Design

### Row: `TreeWorkspaceRow`

Gains `onAddTapped: (() -> Unit)? = null`. When non-null it draws a `TreeRowControl(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_tree_workspace_new_channel, bounded))` after the pencil — `bounded` is the row's already-clamped name, so the daemon-authored name reaching TalkBack is bounded exactly as the pencil's. The screen passes it only in `ConversationTreeSection.Channels`, bound to `ChannelListEvent.TreeWorkspaceAddTapped(group.serverId, group.cwd)`. Chats rows pass `null` and draw no plus.

### Events (`ChannelListEvent`)

- `TreeWorkspaceAddTapped(serverId: String, cwd: String)` — the row's own host and exact `cwd`.
- `CreateChannelSubmitted(name: String, systemPrompt: String)` — trimmed name, verbatim prompt; `toString()` redacts the prompt (the `SaveAsChannelSubmit` precedent).
- `CreateChannelDismissed` — Cancel, Close and Back.

`MainActivity` maps them to `vm.openCreateChannel(serverId, cwd)`, `vm.submitCreateChannel(name, systemPrompt)`, `vm.dismissCreateChannel()`.

### State

```kotlin
data class CreateChannelState(
    val serverId: String,
    val cwd: String,
    val saving: Boolean = false,
    /** Set once the daemon confirmed the create; a retry then writes only the prompt, to this id. */
    val createdConversationId: String? = null,
    val createFailed: Boolean = false,
    val promptFailed: Boolean = false,
)
```

`HostChannelListState.createChannel: CreateChannelState?`. No typed value lives on the state — the name and prompt stay in the modal's own buffers — so no operator text reaches a logged state.

`hostState`'s typed `combine` is at five flows. The add-modal group becomes `combine(addWorkspace, addWorkspaceRecent, createChannel, ::Triple)` — both are the list's "add" modals — so the outer arity stays five.

### View-model transitions

- `openCreateChannel(serverId, cwd)`: opens `CreateChannelState(serverId, cwd)` only when the list holds that host and that host's snapshot has an active **channel** at exactly `cwd` (the Channels-section row's own content); otherwise logs `create_channel_open_rejected code=unknown_workspace` and opens nothing. Selection and navigation untouched.
- `submitCreateChannel(name, systemPrompt)`:
  1. Ignore when closed, `saving`, the prompt is over `SystemPromptLimit`, or (not yet created and) the trimmed name is empty.
  2. Resolve `hostSource.repositoryFor(state.serverId)` at the press. `null` → publish the flag of the leg that would run (`createFailed` before the create, `promptFailed` after), send nothing.
  3. Publish `pending = state.copy(saving = true, createFailed = false, promptFailed = false)`.
  4. If `createdConversationId == null`: `live.createChannel(trimmed, state.cwd)`. Failure → `compareAndSet(pending, pending.copy(saving = false, createFailed = true))`, stop.
  5. Blank prompt (`isBlank()`) → write nothing; finish.
  6. Otherwise publish `created = pending.copy(createdConversationId = id)` by `compareAndSet(pending, created)` (inert if dismissed), then `live.setSystemPrompt(id, systemPrompt)` verbatim. Failure → `compareAndSet(created, created.copy(saving = false, promptFailed = true))`, stop. The chain is not cancelled by a dismissal after OK — the operator already pressed OK — only the modal's visibility follows the dismissal.
  7. Finish: `compareAndSet(<last published>, null)`; if it succeeds, set `lastOpenedTarget` and send `HostConversationTarget(serverId, id)` on `hostNavigationChannel` (as `submitAddWorkspace`); if it fails (dismissed), log `code=dismissed` and open nothing.
- `dismissCreateChannel()`: `createChannel.value = null`; sends nothing.

Catches cover `Exception` and rethrow `CancellationException`. Logs are static event names only (`create_channel_opened`, `_open_rejected`, `_rejected code=unavailable`, `_started`, `_failed`, `_prompt_failed`, `_created`, `_dismissed`) — never the name, path, prompt, ids or exception message.

### Modal: `CreateChannelModal` (new, `ui/components/CreateChannelModal.kt`)

```kotlin
@Composable
internal fun CreateChannelModal(
    serverId: String,
    cwd: String,
    onSubmit: (name: String, systemPrompt: String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    nameEditable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
)
```

`MobileModal(title = "Create channel")` around `ChannelFormFields`. Name and prompt buffers are `remember(serverId, cwd)` (not `rememberSaveable`: the prompt may hold a pasted secret), both opening empty; the name field takes focus via `ChannelFormFields`' own effect. OK enabled when `hostAvailable && name.text.isNotBlank() && SystemPromptLimit.fits(prompt)`; OK reports `(name.text.trim(), prompt)`. `nameEditable = false` once the create is confirmed.

### Screen binding

`CreateChannelModalBinding(hostState, onEvent)` next to the other bindings: present exactly while `hostState.createChannel != null`; `hostAvailable = hostState.isHostConnected(state.serverId)`; `nameEditable = state.createdConversationId == null`; `loading = state.saving`; `error` resolves `create_channel_failed` / `create_channel_prompt_failed` (static).

### Strings

`create_channel_title` ("Create channel"), `cd_tree_workspace_new_channel` ("New channel in %1$s"), `create_channel_failed`, `create_channel_prompt_failed`.

## State + concurrency model

One `MutableStateFlow<CreateChannelState?>` on the view model, joined into `hostState`. The write chain runs in `viewModelScope.launch`; clearing the view model cancels it. Terminal transitions are `compareAndSet` against the exact state object published before each write, so a result landing after a dismissal or reopen is inert. The `saving` guard runs on Main before the launch, so two taps cannot start two creates.

## Error handling

Create failure → modal stays, typed values in the composable buffers, `createFailed` static error, OK creates again. Prompt failure after a confirmed create → modal stays, name disabled, `promptFailed` static error, OK retries only `setSystemPrompt` on `createdConversationId`. Unavailable host at the press → the same flags without a write. OK is additionally disabled on screen while the modal's own host is not connected.

## Testing strategy

Unit (`HostChannelListViewModelTest`, `Repo` gains recorded `createChannel` / `setSystemPrompt` with failure counts and a gate):
- Opening targets the row's own host and exact `cwd`; an unknown host, a chat-only path or a mismatched `cwd` opens nothing.
- With two hosts holding the same `cwd`, submit creates only on the modal's host with the trimmed name and exact `cwd`; the other host sees no write; navigation opens `(serverId, created id)`.
- A blank / whitespace prompt sends no `setSystemPrompt`; a non-blank prompt is written verbatim to the **created** id after the create.
- Create failure keeps the modal with `createFailed`, retry creates again; prompt failure keeps it with `promptFailed` + `createdConversationId`, retry sends exactly one create across both attempts and writes only the prompt.
- Unavailable host sends nothing; a submit mid-write is ignored; a result after dismissal does not reopen or navigate; dismissal before OK sends nothing; logs carry no name, path or prompt.

Compose, Robolectric (`app/src/sharedTest/`):
- `ChannelListScreenTest`: Channels workspace rows carry a 48dp plus named after the workspace, Chats rows none; tapping it emits `TreeWorkspaceAddTapped(serverId, cwd)` for its own host and does not fold; the bound modal submits / dismisses through events, shows the static errors while keeping typed values, and OK follows its host's connection.
- `CreateChannelModalTest`: title and both labels; name empty and focused, prompt empty; OK disabled for blank name, unavailable host, over-limit prompt; OK sends trimmed name + verbatim prompt; Cancel and Close send nothing; `nameEditable = false` disables only the name.

Real-claude e2e: #676 carries the rung-3 scenario (named in the ticket); none here.

## Open questions

- Should the open be refused when the host holds no channel at `cwd`? Yes — the plus only exists on a Channels-section row, which exists only for such a channel, matching `openWorkspaceEditor`'s rule.

## Documentation handoff (pending for the documentation stage)

- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Add controls (#738): add the Channels-section workspace row plus.
- `docs/knowledge/features/mobile-modal.md` § Callers: add Create channel.
- `docs/knowledge/features/system-prompt-editor.md`: correct the intro's claim that #666 owns the editor, and say what the create and save-as flows use instead (`SystemPromptLimit` directly; no stored-prompt read).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The workspace name is daemon-authored; it reaches the plus's content description only as `TreeWorkspaceRow`'s `boundedRowText` result, the same clamp the pencil uses. The `cwd` is daemon-authored too: it is never drawn in the modal (the title is a static string), never logged, and `openCreateChannel` accepts it only when the modal's own host's snapshot holds a channel at exactly that path, so the event cannot introduce a path the host did not report. `createChannel` receives it verbatim, as the host reported it.
- [Trust boundaries] No findings. The created conversation id is daemon-authored and used only as a target on the same host (the prompt write and the navigation), exactly as `submitAddWorkspace` uses its created id. A daemon returning another of its own conversations' ids could steer the prompt write there; the daemon is the authority over its own conversations, so this is accepted, not defended.
- [Tokens / secrets] SHOULD FIX, applied in the design: the prompt may hold a pasted credential. It lives only in the modal's `remember` buffer (not `rememberSaveable`, so not in the saved-state Bundle), never on `CreateChannelState`, and `CreateChannelSubmitted.toString()` redacts it. The verifier should check all three.
- [File / storage] No findings — no folder is created (`createChannel` targets the existing `cwd`; `createWorkspaceFolder` is not called) and nothing is persisted on the phone.
- [Inter-process] No findings — no intent, deep link, push or provider surface is added.
- [Crypto] No findings — the transport and Noise session are unchanged; two existing repository calls are reused.
- [Network & I/O] No findings. One OK sends at most one `create_conversation` and one `set_system_prompt`. The `saving` guard runs on Main before the launch, so a double tap cannot create two channels, and a retry after a prompt failure reuses `createdConversationId` and never re-creates. The prompt is bounded by `SystemPromptLimit` on screen and again in `submitCreateChannel` before anything is sent.
- [Logs] No findings — static `RelayLog.d` event names only; never the name, path, prompt, ids or an exception message. Failures are published as flags; the error strings are static app resources, so no daemon text reaches the shell's live region.
- [Concurrency] No findings. The chain runs in `viewModelScope`; every terminal transition is a `compareAndSet` against the exact state object published before it, so a result after a dismissal or a reopen cannot resurrect the modal or navigate. The repository is resolved from the modal's own `serverId` at each press, so a retry after a reconnect addresses a fresh session on the same host, never another host.
- [Threat model] OUT OF SCOPE — third-party keyboard capture of the typed prompt and screen capture of the modal are the same exposure `SaveAsChannelDialog` (#957) accepts; no ticket owns a change there.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
