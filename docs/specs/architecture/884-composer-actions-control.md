# #884 — Actions control in the composer footer

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `FooterControl`, `FooterMenu`, `footerMenu`, `footerControlEnabled`, `ThreadComposerFooter`, `FooterButton`, `PermissionModeOption` — the extension point the Actions control plugs into, and the precedent for a closed, client-owned option table.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt` → `OptionsOverlayOption`, `OptionsOverlay`, `OptionsColumn` — the shared overlay. It has no disabled row, and its rows are radio choices.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: `openControl` / `openMenu` / the overlay `onSelect` dispatch — where a footer control's selection is routed.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `state` (the five-arm combine, at Kotlin's typed ceiling), `sendMessage`, `sendNewSession`, `onOverflowEvent`, `launchGuardedRepoCall` use — the send and reset paths reused here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState`, `ThreadEvent.NewSession`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeSlashCommandMenu`, `SlashCommandMenu`, `SlashCommandMenuRow` (#882): `null` = no frame heard; `droppedCommands`; per-row `truncatedFields`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `setSlashCommandMenu` — seeding for the ViewModel tests.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `ThreadScreen(...)` call that binds `vm::…` handlers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` → the overflow's Reset session item under `mutationsSupported` (stays).
- `docs/knowledge/features/thread-composer-footer.md` — names `FooterControl` as the extension point for #655's Actions control; `footerMenu` / `footerControlEnabled` are the one place the enable rule is written.
- `docs/knowledge/features/options-overlay.md` § Trust boundary — labels may be daemon-authored; the Actions menu is fully client-owned.
- `docs/knowledge/features/thread-overflow-menu*.md` — the `ThreadEvent.NewSession` path (#540/#625).

In-flight overlaps (build-through, additive edits only): #883 touches `ThreadScreen.kt` / `MainActivity.kt` parameters, #932 changes `ThreadViewModel.sendMessage`, and #878/#895/#904 touch `strings.xml`. This design leaves `sendMessage` unchanged, so a later merge with #932 does not conflict on it.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (footer `110:3494`, overlay `533:1958`)

The `Input footer` row opens with `Actions ^`, a footer button identical in shape to the model, effort and permission buttons: `M3/body/small` label in `Schemes/Primary` with a small up-chevron at a 4dp gap and a 16dp gap to the next button. Tapping it opens the shared `Options overlay` above it, a 6dp-rounded column of body-small primary rows. No new tokens or assets are needed. The existing `FooterButton` and `OptionsOverlay` already reproduce both, including their documented colour and row-height deviations.

## Context

Desktop has a composer Actions menu (`ComposerActionsMenu.tsx`, `composerActionAvailability.ts`). This ports it: Reset session, Compact session (`/compact`), and Knowledge capture (`/knowledge-capture`), with the command rows greyed out only when the conversation's published slash-command menu (#882) proves the command absent. No ADR is needed.

## Design

### Client-owned action table (`ThreadComposerFooter.kt`)

```kotlin
enum class FooterControl { Model, Effort, Permission, Actions }

enum class ComposerAction(val value: String, val label: String, val command: String?) {
    ResetSession("reset", "Reset session", null),
    CompactSession("compact", "Compact session", "/compact"),
    KnowledgeCapture("knowledge-capture", "Knowledge capture", "/knowledge-capture");
    companion object { fun fromValue(value: String): ComposerAction? }
}
```

`ComposerAction` is public because it crosses `ThreadScreen`'s public signature. Labels are Kotlin constants, following `PermissionModeOption`'s precedent, so `footerMenu` stays a pure, JVM-testable function. Nothing published is ever a label, value or command.

### Proof of absence (`ThreadViewModel.kt`)

```kotlin
internal fun absentComposerActions(menu: SlashCommandMenu?): Set<ComposerAction>
```

This returns the command-bearing actions that `menu` proves absent. Proof needs all of the following: `menu != null`, `droppedCommands == 0`, no row whose `truncatedFields` contains `"name"` or `"aliases"`, and no row whose `name` or any alias equals `command.removePrefix("/")` exactly. `ResetSession` is never in the set. Published strings are only compared here. They never leave the function, and they are never rendered, logged or sent.

`ThreadViewModel` derives `absentActions: Flow<Set<ComposerAction>>` from `repository.observeSlashCommandMenu(conversationId).map(::absentComposerActions)`, then applies `onStart { emit(emptySet()) }` and `distinctUntilChanged()`. `onStart` stops a test double that never emits from stalling `state`. The five-arm `state` combine is full, so this joins through one more `.combine(absentActions) { s, a -> s.copy(absentActions = a) }` before `stateIn`. This follows the `runningModel` precedent.

`ThreadUiState` gains `val absentActions: Set<ComposerAction> = emptySet()`.

### Menu and enable rule (`ThreadComposerFooter.kt`)

`footerMenu(control, runConfig, mutationsSupported: Boolean = true, absentActions: Set<ComposerAction> = emptySet())`. The defaulted parameters keep every existing caller and test compiling. The Actions branch returns `FooterMenu(options, selectedValue = "", notListed = 0, actions = true)`. Its options are `ComposerAction.entries` in order, `ResetSession` is dropped unless `mutationsSupported`, and each option has `enabled = it !in absentActions`. The Actions menu is never `null`.

`footerControlEnabled(Actions, …)` is always `true`. A command send needs no session id and no idle run configuration, and the menu is never empty.

`FooterMenu` gains `val actions: Boolean = false`.

`ThreadComposerFooter` draws the Actions button first, per Figma's `Actions · Auto · Opus · Max` order. Its label is `R.string.thread_footer_actions` ("Actions") and its click label is `R.string.thread_footer_open_actions`. It is always enabled.

### Overlay (`OptionsOverlay.kt`)

- `OptionsOverlayOption` gains `val enabled: Boolean = true`.
- `OptionsOverlay` gains `actions: Boolean = false`. When `actions` is set, rows are `clickable(enabled, role = Role.Button)` with no `selectableGroup` and no selected highlight. An action menu is not a radio group. Otherwise the existing `selectable` radio rows apply, with `selectable(enabled = option.enabled)`.
- A disabled row renders its text in `colorScheme.onSurface` at the M3 disabled alpha of 0.38 and is inert.

### Dispatch (`ThreadScreen.kt`, `MainActivity.kt`)

- `ThreadScreen` gains `onComposerCommand: (ComposerAction) -> Unit = {}`. `MainActivity` binds it to `vm::onComposerCommand`.
- `openMenu` passes `state.mutationsSupported` and `state.absentActions` to `footerMenu`.
- `onSelect` for `FooterControl.Actions` resolves `ComposerAction.fromValue(value)`. `ResetSession` becomes `onOverflowEvent(ThreadEvent.NewSession)`, the existing #540/#625 path. Any other action becomes `onComposerCommand(action)`. The overlay then closes. Outside tap and Back use the overlay's existing `onDismiss`.

### ViewModel send (`ThreadViewModel.onComposerCommand`)

`fun onComposerCommand(action: ComposerAction)` runs as follows:

- It returns without sending when `action.command == null`. Reset never goes through this function.
- It also returns when `action in state.value.absentActions`. This is the deterministic backstop behind the greyed row. It logs `event=composer_action action=<value> outcome=absent`.
- Otherwise it runs `launchGuardedRepoCall { effortRecall.awaitWrite(); repository.sendMessage(conversationId, command) }`. This is the same guarded body `sendMessage` runs, minus the draft clear and the attachment pick-up, so the typed draft and #932's pending attachments stay untouched. It logs `event=composer_action action=<value> outcome=sent` after the send is accepted. The log carries static codes only.

`sendMessage` itself is not modified.

## State + concurrency model

- There are no new jobs beyond the one `launchGuardedRepoCall` per tap on `viewModelScope` (Main), cancelled with the ViewModel.
- `absentActions` is cold, rides the `state` `WhileSubscribed(5_000)` sharing, and is re-derived per emission of the #882 menu. A reconnect or host switch that resets the menu to `null` re-enables every row.
- Screen-side state is unchanged: `openControl` and the anchors stay plain `remember`.

## Error handling

- A command send fails exactly as a composer message does. `launchGuardedRepoCall` swallows `RelayErrorException`, `IllegalStateException` and `UnsupportedOperationException`. The repository inserts no confirmed echo, so the command never appears as sent in the thread. That is the existing surfacing for a failed composer message, and it is inherited here unchanged.
- Reset failure uses the existing `newSessionErrors` snackbar.
- Greyed rows are inert in the UI and refused in the ViewModel.

## Testing strategy

- **Unit, `FooterMenuTest`:** the Actions menu lists the three rows in order, with Reset omitted when `mutationsSupported` is false. An absent action's option is disabled. `footerControlEnabled(Actions)` is true even with no session.
- **Unit, new `ComposerActionAvailabilityTest`, over `absentComposerActions`:**
  - Compact is absent for a complete menu lacking `compact`.
  - It is present, meaning not absent, when `menu == null`, when `droppedCommands > 0`, when any row truncates `name`, when any row truncates `aliases`, and when an alias equals `compact`.
  - Matching is exact, so `Compact` and `/compact` do not match.
  - Reset is never absent.
- **Unit, new `ThreadViewModelComposerActionsTest`:** fixture is a `ConversationRepository by FakeConversationRepository` delegate that records `sendMessage`.
  - Compact sends `/compact` to the VM's own conversation id, and the draft is unchanged.
  - A thrown send is swallowed without crash, and the draft is unchanged.
  - A seeded complete menu without `compact` puts it in `state.absentActions`, and `onComposerCommand(CompactSession)` sends nothing.
  - An alias match leaves it available.
  - `ResetSession` through `onComposerCommand` sends nothing.
- **Compose, `ThreadComposerFooterTest` in sharedTest (Robolectric):**
  - The Actions button opens the overlay with the three rows in order.
  - Reset dispatches `ThreadEvent.NewSession`, and Compact dispatches `onComposerCommand(CompactSession)`.
  - An absent Compact row is not enabled and a tap dispatches nothing.
  - An outside tap closes the overlay without dispatching.
  - Back closes it too, through the overlay's existing `BackHandler`, if Robolectric can deliver Back. Otherwise the outside-tap case covers dismissal.
- **Rung-3:** #679 proves the live behaviour, per the AC. No scenario lands here.

## Documentation handoff

The ticket names no documentation requirement. The documentation stage should fold the Actions control into `docs/knowledge/features/thread-composer-footer.md`. That covers the new `FooterControl.Actions`, `ComposerAction`, and the absence proof. The same fold belongs in `options-overlay.md`, which has new disabled rows and action-mode rows. Pending for the documentation stage.

## Open questions

- Whether the Robolectric 320dp width fits the fourth footer button. If an existing footer test loses a button at that width, the fix belongs in the test host's width (`DeviceConfigurationOverride.ForcedSize`), not in the footer layout.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only untrusted input is the workspace-authored `SlashCommandMenu` (#882), and it crosses into the client at exactly one place: `absentComposerActions`. There, `name`, `aliases` and `truncatedFields` are compared against client constants, and the function returns a `Set<ComposerAction>`. No published string is stored on `ThreadUiState`, rendered, logged or sent. Labels, row values and commands are `ComposerAction` constants. The screen hands the ViewModel a `ComposerAction`, never a string. `ThreadScreen` resolves the overlay's `value` through `ComposerAction.fromValue`, which ignores anything unknown. So the most a hostile menu can do is grey out Compact or Knowledge capture. It cannot enable anything, rename anything or change what is sent.
- [Trust boundaries] No findings. The proof is fail-open by construction. `droppedCommands` must be exactly `0`, so a negative or malformed count proves nothing. A truncated `name` or `aliases` anywhere proves nothing. Matching is exact string equality against `command.removePrefix("/")`, so a Unicode lookalike can only make a command read as absent, never make a different command send.
- [Trust boundaries] SHOULD FIX (Phase B). `onComposerCommand` must re-check `action in state.value.absentActions` and refuse. A greyed row must be inert even if a stale overlay composition delivers a tap. The plan already specifies this. The verifier should confirm it landed and is tested.
- [Tokens / secrets] No findings. No token, key or credential is created, read or stored. The command send rides the existing Noise session through `repository.sendMessage`.
- [File / storage] No findings. Nothing is persisted. `absentActions` is derived in memory per emission. The draft and pending attachments are left untouched, not written.
- [Android attack surface] No findings. There is no new intent, deep link, pending intent, provider or WebView. The overlay draws in the screen's own window, as it did before.
- [Crypto] No findings. No primitive is touched.
- [Network & I/O] No findings. The menu arrives on the existing `slash_command_list` frame, whose size is bounded by the transport's frame handling. The absence scan is linear in rows × aliases of one already-decoded frame, per emission. The send is the existing `send_message` request with its existing reply wait.
- [Logs] No findings. `event=composer_action action=<ComposerAction.value> outcome=sent|absent` carries client constants only. There is no conversation id, command output or menu content, and `RelayLog` is debug-gated.
- [Concurrency] No findings. Each tap runs one `launchGuardedRepoCall` on `viewModelScope`, cancelled with the ViewModel. Repeated taps send repeated commands, exactly as repeated composer sends would. This is not deduplicated, deliberately, to match the composer. `absentActions` is cold and shares `state`'s `WhileSubscribed`.
- [Threat model] OUT OF SCOPE. What `/knowledge-capture` or `/compact` does once received belongs to claude's and the workspace's own trust domain. The phone sends the same fixed text a user could type into the composer. A workspace that defines a hostile `knowledge-capture` command is no more reachable here than by typing it. Live behaviour is #679's.
- [Threat model] No findings. For UI leakage, only fixed client labels are drawn. Reset session keeps the overflow item's `mutationsSupported` gate, and the existing `ThreadEvent.NewSession` path is reused unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
