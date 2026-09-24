# #678 — Show the mobile background task list

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/BackgroundTask.kt` → `BackgroundTaskRoster`, `BackgroundTask`, `BackgroundTaskUpdate` — the shapes the panel renders; `liveCount` excludes `isFinished` tasks and adds `droppedTasks`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `observeBackgroundTasks`, `observeLiveBackgroundTaskCount` — the two per-conversation reads this ticket consumes; neither logs, because every task string is claude-authored.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt` → `BackgroundTaskProjection.apply` — the #677 fold; the ViewModel test drives it with real frames so "started then completed shows 0" is proven end to end below the coordinator.
- `app/src/test/java/de/pyryco/mobile/data/repository/BackgroundTaskProjectionTest.kt` → companion `started`, `terminal` — frame builders the ViewModel test reuses.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ThreadViewModel` factory, its `questionBatch = { id -> bundle?.coordinator?.observeQuestionBatch(id) ?: flowOf(null) }` binding, and the demo early-return — the pattern this ticket copies so the count and panel belong to the open host.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → constructor (`questionBatch` lambda), `absentActions` flow and the `state` combine's trailing `.combine(absentActions)` — where the roster and count join the UI state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState.absentActions` — the neighbouring Actions-menu field.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `ComposerAction`, `footerMenu` (`FooterControl.Actions` arm) — the menu row source; labels are client-owned enum constants.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`'s `openControl` (`remember(state.conversationId)`) and the `OptionsOverlay` `onSelect` Actions branch — where the row opens the panel.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal`, `MobileGateModal`, private `MobileModalShell`, `ModalCancelButton` — the shell; both public entry points draw a submit/cancel footer.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `IdentityRow` — the Figma "read only textfield" rendering (labelLarge SemiBold label, bodyMedium value) the panel rows mirror.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` → `setThread`, `footerButton`, `actionRow` — the ThreadScreen harness the panel screen test mirrors.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/FooterMenuTest.kt` → `actions_listsTheThreeRowsInOrder_withNothingSelected` and siblings — pin the row list; they change with the new row.
- `pyrycode-desktop` `src/renderer/src/screens/conversation/BackgroundTaskPanel.tsx` → `BackgroundTaskPanelView` — content, copy and the three-way branch.
- `../pyrycode/docs/protocol-mobile.md` § `background_task_updated`, § `background_task_roster` — wire SSOT (not restated here).
- `docs/knowledge/features/mobile-modal.md` — shell caller contract.

In-flight overlap check (2026-09-24): no other `origin/feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The shared full-height modal shell shown as Edit host: a `primaryContainer` surface (the codebase's mapping of `Schemes/On Primary Fixed`), a `titleLarge` header with the circled close glyph, a 60 % `inversePrimary` separator, a content column of "read only textfield" rows (`labelLarge` SemiBold label + `bodyMedium` value, both `onPrimaryContainer`), and a centred footer of outlined `primary`-bordered buttons with `bodyLarge` Medium labels. No frame exists for the task list itself; its content follows desktop's `BackgroundTaskPanel`. The read-only panel keeps header, separator and footer, drops OK, and shows a single outlined **Close** footer button.

## Context

#677 shipped the per-conversation roster store; nothing on the UI reads it. This ticket adds the reader: an Actions-menu row carrying the live count, and a read-only panel. No ADR needed.

## Design

### Shell — `MobileModal.kt`

New entry point beside the other two:

```kotlin
@Composable
internal fun MobileReadOnlyModal(
    title: String,
    closeLabel: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
)
```

Calls `MobileModalShell(gate = false, error = null)` with a footer of one `ModalCancelButton(label = closeLabel, onClick = dismiss)`. Close glyph, Close button and Back all route to `onDismissRequest`; outside taps do not dismiss (shell rule). `MobileModalShell` stays private.

### Menu row — `ThreadComposerFooter.kt`

- `ComposerAction.BackgroundTasks("background-tasks", "Background tasks", null)`, last in menu order. `command == null` already keeps it out of `absentComposerActions` and makes `onComposerCommand` a no-op for it.
- `footerMenu(..., backgroundTaskCount: Int = 0)`: the Actions arm labels this row `"Background tasks ($count)"` — the parenthesised-count shape desktop uses for its partial-list copy, no pluralisation. The count is an `Int`, the only non-constant in the label.

### Screen — `ThreadScreen.kt`

- `var backgroundTasksOpen by remember(state.conversationId) { mutableStateOf(false) }` — screen-local, never saveable, keyed on the conversation like `openControl`.
- Actions `onSelect`: `ComposerAction.BackgroundTasks -> backgroundTasksOpen = true` before the `else -> onComposerCommand(action)` branch.
- `footerMenu(...)` is passed `state.backgroundTaskCount`.
- `if (backgroundTasksOpen) BackgroundTaskPanel(roster = state.backgroundTasks, onDismiss = { backgroundTasksOpen = false })`. Closing only flips local state: no callback, no event, nothing sent.
- No new `ThreadScreen` parameter, so `MainActivity.kt` is untouched.

### Panel — new `ui/conversations/thread/BackgroundTaskPanel.kt`

```kotlin
@Composable
internal fun BackgroundTaskPanel(roster: BackgroundTaskRoster?, onDismiss: () -> Unit)
```

Draws `MobileReadOnlyModal(title = "Background tasks", closeLabel = "Close")`. Body, in order:

1. Partial-list notice `"Partial list (N not shown)"` when `roster != null && roster.droppedTasks > 0` — a sibling of the branch, so it shows on an empty task list too.
2. Branch on the roster itself before touching `tasks`: `null` → "No background-task report yet"; `tasks.isEmpty()` → "No background tasks"; otherwise one row per task in roster order.
3. A task row (a non-clickable `Column`, `semantics(mergeDescendants = true)`), each field a separate `Text`, each marker a separate sibling `Text` "Truncated by the daemon" directly after its field:
   - `description` (bodyMedium) — marked when `task.truncatedFields` contains `description`.
   - `taskType` (labelLarge SemiBold) — marked when `task.truncatedFields` contains `task_type` (the wire name).
   - `Finished` label when `task.isFinished` (client-owned; covers `isFinished` with `finish == null` after a reconnect).
   - latest mid-life update when `latestUpdate != null`: its `patch`, or "No change reported" when `patch == ""` — marked when **`latestUpdate.truncatedFields`** contains `patch`.
   - terminal summary when `finish?.summary` is non-empty — marked when **`finish.truncatedFields`** contains `summary`.
   The task list and the update lists are never merged or crossed.
- Text bound: `panelText(raw)` drops ISO control characters except `\n` / `\t` and caps at `MAX_PANEL_TEXT_CHARS = 4096`; a client cut also shows the marker (the field was cut, whoever cut it). The daemon's caps are byte caps at or below that, so in practice only the daemon's report marks a field.
- Task rows use no `key()` and no testTag built from task data.
- Logs one content-free debug event on open: `event=background_tasks_panel_opened reading=unreported|empty|listed tasks=<n> dropped=<n>` (numbers and client constants only; the shell's own opened/closed events cover the rest).
- Copy lives in `strings.xml` (a resource file, alongside the existing `thread_footer_*` strings); the menu row label stays an enum constant like its siblings.

### ViewModel — `ThreadViewModel.kt`, `ThreadUiState.kt`, `AppModule.kt`

- `ThreadUiState`: `backgroundTasks: BackgroundTaskRoster? = null`, `backgroundTaskCount: Int = 0`.
- `ThreadViewModel` constructor, beside `questionBatch`:
  - `backgroundTasks: (conversationId: String) -> Flow<BackgroundTaskRoster?> = { flowOf(null) }`
  - `backgroundTaskCount: (conversationId: String) -> Flow<Int> = { flowOf(0) }`
- A private `backgroundTaskReading` flow combines the two (each seeded with `onStart` — `null` / `0` — so a silent source cannot stall `state`), and the `state` chain gains one more `.combine` after `absentActions` copying both into the UI state.
- `AppModule` relay path: `backgroundTasks = { id -> bundle?.coordinator?.observeBackgroundTasks(id) ?: flowOf(null) }`, `backgroundTaskCount = { id -> bundle?.coordinator?.observeLiveBackgroundTaskCount(id) ?: flowOf(0) }`. The demo early-return passes neither, so the defaults give 0 and `null`.

## State + concurrency model

No new coroutine. Both flows are cold per-collector projections of the coordinator's `StateFlow` (`distinctUntilChanged` already), joined into the existing `state` `stateIn(viewModelScope, WhileSubscribed(5_000))`, so they cancel with it. Panel visibility is Compose-local (`remember`), dies with the screen and resets on another conversation. A background close of the socket leaves the roster wherever #677's store leaves it; the panel simply re-renders.

## Error handling

No failure mode: reading only. A roster that never arrives renders "No background-task report yet". Nothing is sent, so there is nothing to fail.

## Testing strategy

- **Unit, `ThreadViewModelBackgroundTasksTest`** (new, `app/src/test/.../thread/`):
  - no lambdas (demo / non-relay host) → `backgroundTaskCount == 0`, `backgroundTasks == null`.
  - lambdas bound to a real `BackgroundTaskProjection` exactly as the coordinator binds them (`rosters.map { it[id] }`, `liveCount ?: 0`): a `started` frame → count 1 and a one-task roster; its `terminal("completed")` → count 0 while the roster still holds the task with `isFinished`.
  - the lambdas receive this ViewModel's own conversation id.
- **Unit, `FooterMenuTest`**: the Actions list gains the fourth row "Background tasks (0)" / value `background-tasks`; the label carries the passed count; it stays enabled when every command is absent.
- **Robolectric screen test, `BackgroundTaskPanelTest`** (new, `app/src/sharedTest/.../thread/`):
  - via `ThreadScreen`: the Actions row shows the state's count; tapping it opens the panel; Close (footer) and the close glyph each dismiss with no `onComposerCommand`/`onOverflowEvent` call.
  - `null` / empty / populated each show their own sentence and not the others'.
  - dropped notice shows on a populated and on an empty roster, and not at `droppedTasks = 0`.
  - markers: description, task_type, patch and summary each marked from their own list; a `task.truncatedFields = ["patch"]` marks no patch (crossover), and `truncatedFields = ["taskType"]` marks nothing (wire-name trap).
  - empty patch → "No change reported"; finished task → "Finished" and its summary.
  - daemon text nodes carry no click action.
- No device-only test. The live scenario is #679's.

## Open questions

- None blocking. If the shell's centred content column reads oddly for a long list, it still scrolls (the shell's `verticalScroll`); no change to the shell's layout is planned.

## Documentation handoff

Pending for the documentation stage: fold the panel and `MobileReadOnlyModal` into `docs/knowledge/features/mobile-modal.md` § Callers (a third entry point) and the thread/Actions-menu coverage in `docs/knowledge/features/thread-screen.md`. The ticket body names no specific reference doc.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — daemon text enters Compose at one place, `BackgroundTaskPanel`, already decoded into `BackgroundTaskRoster` by #677's `BackgroundTaskProjection`. Every field passes `panelText` (control characters dropped, 4096-char cap) and reaches `Text` only: never markdown, a link, a clickable row, the clipboard, a `key()`, a testTag, a content description built from it, or a log.
- [Trust boundaries] SHOULD FIX (addressed in design) — `truncatedFields` entries are daemon strings; they are only compared (`contains`) against client constants and never rendered, so a hostile name cannot inject text. The marker copy is a separate client-owned `Text`, never concatenated to the field, so a description ending "Truncated by the daemon" cannot pass for the client's claim.
- [Trust boundaries] No findings — `droppedTasks` and `liveCount` are the only daemon-derived values shown outside a field, both `Int`; the notice renders only on `> 0`, so a negative count shows nothing. `liveCount` saturates (#677).
- [Tokens] No findings — no tokens touched.
- [File / storage] No findings — nothing stored; panel visibility is non-saveable `remember` state.
- [Inter-process] No findings — no intent, deep link, WebView or provider.
- [Crypto] No findings — no crypto touched.
- [Network & I/O] No findings — closing the panel sends nothing; `BackgroundTasks` has `command == null`, so `onComposerCommand` returns before any send even if called.
- [Logs] No findings — one debug-only log of client constants and counts; the shell logs only fixed event names.
- [Concurrency] No findings — no new coroutine; flows join the existing `stateIn` and cancel with it. Host and conversation scoping come from the factory's `bundle` and the ViewModel's own id, so another host's roster cannot reach this screen.
- [Threat model: hostile daemon frame] No findings — an oversized or control-laden field is bounded at render; an unknown `status` value is not switched on (only `isFinished` is read).
- [Threat model: UI-side leakage] OUT OF SCOPE — the panel inherits the non-gate shell's `SecureFlagPolicy.Inherit`, like Edit host; task text is claude's own output the thread already shows. No ticket needed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
