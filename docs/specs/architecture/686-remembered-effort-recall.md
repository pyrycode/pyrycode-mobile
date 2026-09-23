# #686 — Remember successful effort choices across the mobile app

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`
  - `onEffortSelected`, `onModelSelected`: the tap entry points and their guards (`pending`, `selectedEffort`, `skipUnlessWritable`).
  - `sendSessionSettings`: the single effort write path. Recall reuses it; its success branch is where remembering happens.
  - `sessionSettings` (its `onEach` clears `pendingEffort` when a reading lands), `runConfigFlow` (five-arm combine at the typed ceiling), `state` (the combine that also sees `Conversation.currentSessionId`).
  - `sendMessage`: the first-message ordering hook.
  - `forLiveSession`: the replaced-session rule (#650/#889) the recall mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig` (`selectedEffort`, `effortChoices`, `writable`, `pending`, `savedEffort`, `settingsAvailable`, `menuAvailable`). Display rules stay unchanged.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `defaultEffort` (must stay untouched), `editWorkspace` (the IOException-to-`Result` posture to mirror), companion keys.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread` (demo branch vs relay branch; the #807 comment on the removed `AppPreferences`) and the `appModule` `viewModel { … thread(handle, get()) }` call site, the only caller.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall`, which `sendMessage` runs inside.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAppliedEffortTest.kt` → `ScriptedRepo` fixture (scripted readings, ack gate, failure injection, live-session override) that the new VM test mirrors.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` → temp-file DataStore setup and the `pushToken_survivesProcessDeath` restart shape.
- `docs/knowledge/features/app-preferences.md`, `docs/knowledge/features/thread-composer-footer.md`: context only.

In-flight overlaps: #883 and #932 touch `AppModule.kt`, and #891 and #932 touch `ThreadViewModel.kt`. #932 rewrites `sendMessage`'s body, where this ticket adds one line. None is a dependency. The edits here stay additive, and a later merge may touch those files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=16-8

The thread screen: top app bar, message bubbles, and the composer with the one-line run-config footer (`Actions ^ Auto ^ Opus ^ Max ^ Cxt: 84%`) at the bottom. This ticket makes no visual change. A recalled level shows only through the footer's existing pending and settled effort states from #889, so the layout and tokens stay as they are.

## Context

Mobile port of desktop #1549 (app-wide recall) and desktop PR #1554 (selection before the first message). The phone keeps one local remembered effort level across chats, channels and hosts. It is a published level string such as `xhigh`, not an `Effort` enum entry. When the thread opens and the session has no saved effort, the phone writes that level through the normal `set_session_settings` path (`../pyrycode/docs/protocol-mobile.md`). It never goes into `send_message`. Cross-device sync is out of scope. The Settings "Default effort" (`default_effort`) is unrelated and stays untouched.

## Design

### Storage: `AppPreferences` (data layer)

- New key `stringPreferencesKey("remembered_effort")`.
- `val rememberedEffort: Flow<String?>` returns the stored level, or `null` when absent. There is no fallback, so a fresh install reads `null`.
- `suspend fun setRememberedEffort(level: String): Result<Unit>` catches `IOException` into `Result.failure` and logs content-free (`event=remembered_effort_set outcome=success|io_failure`), matching `editWorkspace`'s posture. It never logs the level.

### Seam: `RememberedEffortStore` (new, `ui/conversations/thread/EffortRecall.kt`)

```kotlin
interface RememberedEffortStore {
    suspend fun read(): String?
    suspend fun remember(level: String)
    object None : RememberedEffortStore  // read() = null, remember() = no-op
}
fun AppPreferences.asRememberedEffortStore(): RememberedEffortStore  // rememberedEffort.first() / setRememberedEffort
```

`ThreadViewModel` gains a constructor parameter `rememberedEffort: RememberedEffortStore = RememberedEffortStore.None`, so the demo, fake and existing tests stay inert. `ThreadDestinationFactory.thread` gains a `preferences: AppPreferences` parameter and passes `preferences.asRememberedEffortStore()` only on the relay branch. The demo branch stays inert, like the other seams. The `appModule` call site becomes `thread(handle, get(), get())`.

### Collaborator: `EffortRecall` (new, same file, `internal`)

```kotlin
internal class EffortRecall(
    scope: CoroutineScope,
    private val store: RememberedEffortStore,
    private val start: (sessionId: String, level: String) -> Job,
) {
    fun offer(config: ThreadRunConfig, liveSessionId: String)
    fun cancel()                        // a user effort tap
    suspend fun awaitWrite()            // joins the recall write, if one was started
    suspend fun remember(level: String) // on every successful effort write
}
```

State lives on Main only (`viewModelScope`): `decided: Boolean`, `loaded: Boolean`, `remembered: String?`, the last offered input, and `write: Job?`.

- **Load.** `init` launches one `store.read()`. An `IOException` reads as absent (`event=effort_recall outcome=read_failed`). It then sets `loaded` and runs the decision.
- **Decide** (after `offer`, and after the load), once only:
  - Wait (no decision yet) while any of these holds: not loaded, no input, `!settingsAvailable`, `!menuAvailable`, `pending` (an outstanding model tap), or the reading is for a replaced session. Replaced means a non-empty `liveSessionId` that differs from the reading's `sessionId`, and the check runs only when the session is writable.
  - Otherwise mark it decided, then send no write when: nothing is remembered (`none`), `savedEffort` is not empty (`saved_choice`, which covers another device's saved choice), `!writable` (`no_session`), or the remembered level is not among `config.effortChoices` values (`unpublished`).
  - Else `write = start(config.sessionId, level)`.
  - Every outcome logs `event=effort_recall outcome=<code>` with static codes only.
- **Cancel.** If the decision is still open, mark it decided (`outcome=cancelled_by_tap`).
- **Remember.** Drop a level longer than `MAX_REMEMBERED_EFFORT_CHARS` (128, the footer's inert bound). Otherwise `store.remember(level)`.

### `ThreadViewModel` changes

- A field `effortRecall = EffortRecall(viewModelScope, rememberedEffort, ::startRecall)`. `startRecall(sessionId, level)` sets `pendingEffort = level` and returns `sendSessionSettings(sessionId, effort = level) { pendingEffort.value = null }`, the same revert as a tap.
- `sendSessionSettings` returns its `Job`. On a successful ack with `effort != null`, it calls `effortRecall.remember(effort)` before the refresh. Model-only writes, permission writes, failures and readings never remember.
- `onEffortSelected` calls `effortRecall.cancel()` first, before its guards.
- `state`'s combine body calls `effortRecall.offer(runConfig, conv?.currentSessionId.orEmpty())`. That is the one place that sees both the reading and the live session without opening a second settings subscription. The class-level comment says why.
- `sendMessage` calls `effortRecall.awaitWrite()` inside its guarded block before `repository.sendMessage`. Only a started recall write holds a send. A decision that is still open never does.

Isolation follows from construction. The repository is bound to the destination's host, `observeSessionSettings(conversationId)` is scoped to this conversation, and the write addresses the reading's own `sessionId`.

## State + concurrency model

- All recall state is mutated on `viewModelScope` (Main). `offer` runs inside the `state` combine collector, which is `stateIn(viewModelScope)`, so it is also Main. There are no locks.
- The recall write is an ordinary `sendSessionSettings` job in `viewModelScope`, cancelled with the VM. `awaitWrite` joins it. A cancelled join returns, so a send can never hang on it.
- A `WhileSubscribed` restart of `state` re-offers inputs, but `decided` makes the recall at most once per `ThreadViewModel`, which is once per opening.

## Error handling

- A rejected or failed recall write takes `sendSessionSettings`'s catch path: it reverts the pending and sends the `sessionSettingsErrors` signal, like a tap. It never remembers, and `decided` blocks a retry until the thread reopens.
- A store read failure (`IOException`) reads as absent. A store write failure is `Result.failure`, logged content-free. It is ignored at the call site and does not revert the acknowledged write.

## Testing strategy

Unit tests only. No UI change.

- `AppPreferencesTest` (extended):
  - `rememberedEffort` is absent on a fresh store.
  - Set, then read, round-trips.
  - The value survives a restart, meaning a new `AppPreferences` over the same file.
  - Setting it leaves `defaultEffort` at `HIGH`, and `setDefaultEffort` leaves it absent.
- `ThreadViewModelEffortRecallTest` (new; `ScriptedRepo`-style fixture plus an in-memory `RememberedEffortStore`):
  - Nothing is remembered: no write, the footer shows the placeholder, and nothing is stored.
  - Recall: the remembered level is published and the saved effort is empty. Exactly one write goes to this session with that effort, the pending shows the level, and the refreshed reading decides what shows after the ack.
  - A saved choice is preserved. A level the menu does not publish sends no write. A reading with an empty session id (not writable) sends no write.
  - A rejected recall reverts and signals, stores nothing, and a later reading sends no second write.
  - An effort tap before the decision (menu not yet present) cancels the recall. Only the tap's write goes out, and the tap's level is remembered.
  - A successful tap is remembered. A failed tap, a model-only write and passive readings are not.
  - A successful tap with the real `AppPreferences` store survives a restart (a new `AppPreferences` over the same DataStore file).
  - First message: a send while the recall write is outstanding reaches the repository only after the ack. A send made before any reading is not held.
  - Isolation: two conversations share one store. Only the unset one gets a write, addressed to its own session id. A reading for a replaced session triggers nothing until the live session's reading arrives, and then the write addresses the live id.
- No rung-3 scenario. The ticket assigns the live recall and restart proof to #545.

## Documentation handoff

Pending for the documentation stage: fold the remembered-effort key into `docs/knowledge/features/app-preferences.md` and the recall rules into `docs/knowledge/features/thread-composer-footer.md`. The ticket has no documentation section of its own.

## Open questions

- None blocking. A model tap made before the decision defers the decision until a reading clears the pending model. The ticket does not cover this case, and deferring is the safe choice that avoids two writes in flight.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The remembered value is a daemon-authored level string. It crosses into storage only from a write the phone sent and the daemon acknowledged. On recall it is sent only when it equals one of the current row's published `ThreadEffortChoice.value`s (the check in `EffortRecall`'s decision), so another host receives only a string it published itself. It is never rendered directly: the footer shows it only via `pendingEffort` → `effortLabel`'s existing `inert()` path.
- [Trust boundaries] SHOULD FIX (in plan): a hostile daemon could publish an oversized level, and a tap would persist it. `EffortRecall.remember` drops levels longer than 128 chars.
- [Tokens] No findings. No secret is involved. The level is a non-secret preference in the existing `DataStore<Preferences>`, the same storage as the other app-wide defaults.
- [File / storage] No findings. There is no path built from input. DataStore writes atomically. The file is app-private and holds no secret, so backup exposure is the same as `default_effort`.
- [Android surface] No findings. There is no new intent, deep link, PendingIntent, provider or WebView.
- [Crypto] No findings. There is no new primitive.
- [Network & I/O] No findings. It reuses `set_session_settings` through `sendSessionSettings`, with no new frame. At most one recall write per opening and no retry, so a rejecting daemon cannot cause a write loop.
- [Logs] No findings. `event=effort_recall` and `event=remembered_effort_set` carry static codes only, never the level or the session id.
- [Concurrency] No findings. All state is on Main in `viewModelScope`. The single `decided` flag stops a double recall. `awaitWrite` joins a VM-scoped job, which cannot outlive the VM.
- [Threat model] No findings. A replaced-session or other-host reading cannot trigger a write: the repository is host-bound, settings are conversation-scoped, and the replaced-session check waits. OUT OF SCOPE: cross-device sync of the remembered level (undecided, per the ticket).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

### 2026-09-24: no log when nothing is remembered

In debug unit tests `RelayLog` is enabled and its default sink calls `android.util.Log`, which throws on a plain JVM. The plan logged every decision, `none` included. That put a log inside the `state` collector of every thread opening, so each existing `ThreadViewModel` test that uses the inert `RememberedEffortStore.None` would have crashed its state flow. So `EffortRecall` stays silent when no level is remembered, both at the decision and on a cancelling tap. The fresh-install case and every demo or test opening are those silent paths. The other outcome codes (`saved_choice`, `no_session`, `unpublished`, `started`, `cancelled_by_tap`, `read_failed`) are logged as planned. The two test classes that exercise real logging install a capturing `RelayLog.sink` and assert the level never appears in a log line.
