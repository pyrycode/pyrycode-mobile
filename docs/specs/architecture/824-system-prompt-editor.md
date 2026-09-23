# #824 — System-prompt editing state (`SystemPromptEditor`)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `requestSystemPrompt`, `setSystemPrompt`, `SystemPromptReading`, `SessionPromptStatus`, `SystemPromptLimit` — the #823 read/write this state drives, and the one byte-limit helper it must reuse rather than re-count. `setSystemPrompt` throws `IllegalArgumentException` for over-limit (before sending) and unknown conversation, `RelayErrorException` for other server errors, `IllegalStateException` when disconnected.
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorController` — the shape to follow: a plain object taking the owner's `viewModelScope`, one `MutableStateFlow`, `CancellationException` re-thrown, every other failure caught (an escaping throw in `viewModelScope` kills the process), content-free `RelayLog.d` events.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog.d`, and the `enabled`/`sink` test seams the unit test captures to prove nothing prompt-shaped is logged.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `FakeConversationRepository` — delegated to by the test double (`ConversationRepository by FakeConversationRepository()`, the `HostChannelListViewModelTest.Repo` idiom) so only the two prompt members are scripted.
- `app/src/test/java/de/pyryco/mobile/ui/settings/DebugBundleDownloadControllerTest.kt` — controller-test idiom: `runTest`, `advanceUntilIdle`, `RelayLog` sink capture and restore.
- `docs/knowledge/features/conversation-repository.md` § the #823 paragraph — `null` / `""` / text are three distinct states in both directions; status is independent of the prompt; text is held verbatim and never logged.
- pyrycode-desktop `systemPromptWriteStore.ts`, `EditChannelDialog.tsx` (`seedFrom`, `promptWriteFor`) — desktop seeds both absent and `""` as an empty box. Mobile diverges on purpose: the ticket makes clearing its own action that sends `null`, so an emptied box saves `""` verbatim.

## Design source

Figma: N/A — plain-Kotlin state with no rendering; the modals that draw it are #666 and #667.

## Context

#666 (create / save-as channel) and #667 (edit channel) both edit a conversation's system prompt. This ticket adds the state both of their view models will own, so the load / draft / save / failure rules are written once. It renders nothing and is wired into nothing; those tickets do that.

## Design

One new file: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SystemPromptEditor.kt`. No Compose or Android imports (besides `RelayLog`, which the module's JVM tests already drive).

```kotlin
sealed interface SystemPromptEditorState {
    data object Loading : SystemPromptEditorState
    data object Unavailable : SystemPromptEditorState      // the read failed — not "no prompt stored"
    data class Loaded(
        val confirmed: String?,                            // last value known stored; null = no prompt
        val appliedStatus: SessionPromptStatus?,           // null = unknown (refreshing, or refresh failed)
        val draft: String,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : SystemPromptEditorState {
        val draftBytes: Int; val overLimit: Boolean       // via SystemPromptLimit
        val changed: Boolean                               // draft != confirmed.orEmpty()
        val canSave: Boolean                               // !saving && changed && !overLimit
        val canClear: Boolean                              // !saving && confirmed != null
        override fun toString(): String                    // redacted: no prompt text
    }
}

class SystemPromptEditor(scope: CoroutineScope, repository: ConversationRepository, conversationId: String) {
    val state: StateFlow<SystemPromptEditorState>
    fun edit(text: String)   // no-op unless Loaded
    fun save()               // no-op unless Loaded && canSave; sends draft verbatim
    fun clear()              // no-op unless Loaded && canClear; sends null
}
```

- **Binding.** Repository and conversation id are constructor-bound, so two instances for one id on two hosts' repositories touch only their own (AC 4 holds structurally; a test proves it).
- **Open.** `init` launches one `requestSystemPrompt`. Success → `Loaded(confirmed = reading.systemPrompt, appliedStatus = reading.sessionPromptStatus, draft = reading.systemPrompt.orEmpty())`. Failure → `Unavailable`. No retry of the read: the ticket reads once, and an owner that wants another try constructs a new editor.
- **Unchanged means "equals the stored value, with absent read as empty".** So opening a no-prompt conversation and saving sends nothing and never stores `""`, and an emptied box over a stored `""` is also unchanged. Over a stored text, an emptied box *is* a change and sends `""` verbatim.
- **Save / clear** share one private `write(value: String?)`: set `saving = true, saveFailed = false`; `setSystemPrompt(id, value)`; on success publish `confirmed = value, appliedStatus = null` (the ack carries neither value nor status), then `requestSystemPrompt` again and publish only its `sessionPromptStatus` — the confirmed value stays the one just saved; on refresh failure `appliedStatus` stays `null` (unknown). `saving` goes false after the refresh. A clear additionally resets the draft to `""` on success. A failed write sets `saveFailed = true` and leaves `draft` and `confirmed` untouched, so calling `save()` again sends the same draft.
- **Edits during a save** are allowed; every transition is `update { … }` on the current `Loaded`, so a completing write never overwrites text typed meanwhile.
- **No session call.** Only `requestSystemPrompt` and `setSystemPrompt` are called; nothing starts, resets or restarts a session. The status reports when the saved prompt applies.

## State + concurrency model

The owner passes its `viewModelScope`, as with `HostEditorController`; clearing the owner cancels the open read and any write. Calls arrive on the main dispatcher. `saving` guards against a second write overlapping the first, so at most one write-and-refresh job is in flight. `CancellationException` is re-thrown on every path.

## Error handling

Every repository exception other than cancellation is caught and classified into state: read → `Unavailable`; write → `saveFailed`; refresh read → `appliedStatus = null`. Over-limit never reaches the repository: `canSave` blocks it. Logs are static event names only (`event=system_prompt_read_failed`, `…_saved`, `…_save_failed`, `…_refresh_failed`); never the prompt, its length or the conversation id. `Loaded.toString()` omits the text so a crash trace or a logged state cannot carry it.

## Testing strategy

JVM unit test `app/src/test/java/de/pyryco/mobile/ui/conversations/components/SystemPromptEditorTest.kt`, with a double that delegates to `FakeConversationRepository` and scripts `requestSystemPrompt` / `setSystemPrompt` (queued readings or throws, a gate to hold a call open, and a record of every write). Scenarios:

- Loading before the reading arrives; edit and save are no-ops while loading.
- `null`, `""` and text readings each load distinctly with their status; a failed read is `Unavailable` and edit/save stay inert.
- Draft byte count uses UTF-8 (multi-byte text); exactly 8192 bytes saves, 8193 sends nothing.
- Saving sends the draft verbatim, `""` included; clear sends `null`; an unchanged draft (no-prompt and `""` cases) sends nothing.
- Successful save → confirmed updated, then status refreshed from a second read; refresh failure → confirmed kept, status `null`.
- Failed save → draft and confirmed kept, `saveFailed`; a retry sends the same draft.
- Two editors, same id, two repositories → each reads and writes only its own.
- No log line contains the prompt, its byte length or the conversation id; `toString` carries no prompt text.

No device test: nothing renders.

## Open questions

- Whether the post-save refresh should also adopt the re-read `systemPrompt` (a concurrent writer). Decided no: the AC keeps the saved value confirmed; only the status refreshes.

## Documentation handoff

None requested by the ticket.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the stored prompt is untrusted operator-authored text that crossed from the daemon through #823's `requestSystemPrompt`; the editor holds it as a plain `String`, never parses, trims or interprets it, and renders nothing. The read is not length-bounded by #823's decode (only the relay frame cap bounds it), so a reading over `SystemPromptLimit.MAX_BYTES` loads with `overLimit = true`: it cannot be saved back unchanged, and clearing stays available. Drawing it with a length bound is #666/#667's render-side concern — OUT OF SCOPE here.
- [Tokens, secrets] SHOULD FIX (applied in Phase B) — the prompt may hold a pasted credential. `Loaded` is a `data class`, whose generated `toString` would print `confirmed` and `draft` into any crash trace or logged state; override `toString` to name only the flags. The editor persists nothing (no `SavedStateHandle`, no DataStore); whether an owner saves the draft across process death is #666/#667's decision — OUT OF SCOPE.
- [File / storage] No findings — no filesystem or preference access.
- [Inter-process] No findings — no intents, deep links, providers or WebView.
- [Crypto] No findings — no primitives; transport is #823's existing Noise session.
- [Network & I/O] No findings — one read, one write and one refresh read per save, each through the bound repository; over-limit text never reaches `setSystemPrompt`, and a write is refused while one is in flight, so the editor cannot be driven into a send loop.
- [Logs] No findings as designed — static event names only; never the prompt, its byte count, the conversation id or an exception's `message` (an `IllegalArgumentException` from the repository is not assumed content-free). The unit test captures `RelayLog` and asserts none of the three appears.
- [Concurrency] No findings — every job runs in the owner's `viewModelScope`; `CancellationException` is re-thrown; the `saving` flag is set inside a guarded `update` so two taps cannot launch two writes; completions publish through `update` on the current `Loaded`, so they never overwrite a draft typed meanwhile. The refresh stays inside the same `saving` window, so a stale status from an earlier save cannot land over a later one.
- [Threat model] Hostile daemon frame: covered by holding text verbatim and rendering none of it. UI-side leakage (screenshots, keyboards) belongs to the modals, #666/#667 — OUT OF SCOPE.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
