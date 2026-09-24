# System prompt editor — shared channel system-prompt editing state

`ui/conversations/components/SystemPromptEditor.kt` (`de.pyryco.mobile.ui.conversations.components`)
holds one conversation's system-prompt editing state: what is stored, whether the running session
already uses it, the operator's in-progress draft, and save/failure tracking. Added in #824, shaped
like [`HostEditorController`](host-editor.md): a plain Kotlin object with no Compose or Android
imports, constructed over the owning view model's `viewModelScope`. It is still unused, by three
callers in a row now. #666 split into [Save as channel](save-as-channel-dialog.md) (#957) and
[Create channel](mobile-modal.md#callers) (#958), and neither wired it in. Both flows write a system
prompt only into a **new or freshly promoted** conversation, which has no stored prompt and no running
session to read `appliedStatus` from, so there is nothing for this editor's read-then-track shape to do
for them: they call `SystemPromptLimit.fits`/`utf8Bytes` directly for the byte-limit check and
`setSystemPrompt` once, verbatim, after their own create/promote leg confirms — no `requestSystemPrompt`,
no `draft`/`confirmed` distinction, no refresh read.

[`EditChannelModal`](mobile-modal.md#callers) (#667) is exactly the caller this editor was described as
remaining available for — an **existing** conversation's already-stored prompt, edited against a
possibly-live session — and it still did not adopt it, for a reason specific to this class rather than
to the create/save-as flows: **`SystemPromptEditor` binds one repository at construction.** A host's
repository is per connection ([`HostConversationSource.repositoryFor`](dependency-injection-host-conversation-source.md)),
[`LifecycleConnectionDriver`](lifecycle-connection-driver.md) closes it on background, and the foreground
reconnect produces a new one. An operator who backgrounds the app mid-edit and returns would hold an
editor whose every further `save()` goes to a retired repository and fails forever — the construction-time
bind that makes this class simple for a caller with one stable repository (chat/thread editing, where
`SystemPromptEditor` was designed in) is the same bind that breaks it for a list-screen modal that can
outlive a reconnect. #667's `ChannelListViewModel` instead reads the prompt itself once (waiting for
`repositoryFor(serverId)` to become non-null) and resolves the repository **again, at the OK press** —
the same discipline every other write in that view model already followed
([`submitChatName`](channel-list-viewmodel.md#wiring) and siblings) before this ticket gave the prompt a
reason to need it too. It copies this editor's `changed` rule (an absent `confirmed` reads as `""`) and
its redacted `toString()`, but keeps its own `ChannelPromptReading` sealed type rather than reusing
`SystemPromptEditorState.Loaded` — the reading is kept in a state flow separate from the write's own
`compareAndSet` target, which `SystemPromptEditorState.Loaded` conflates by design (see the Design section
of `docs/specs/architecture/667-edit-channel-modal.md`).

This class remains available for a caller with a genuinely construction-stable repository. A caller
whose repository can retire out from under it — any list-screen modal reached through
`HostConversationSource` — should follow #667's shape instead: read once through the repository
resolved when available, write through the repository resolved at the press.

## Shape

```kotlin
sealed interface SystemPromptEditorState {
    data object Loading : SystemPromptEditorState
    data object Unavailable : SystemPromptEditorState

    data class Loaded(
        val confirmed: String?,
        val appliedStatus: SessionPromptStatus?,
        val draft: String,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : SystemPromptEditorState {
        val draftBytes: Int
        val overLimit: Boolean
        val changed: Boolean
        val canSave: Boolean
        val canClear: Boolean
    }
}

class SystemPromptEditor(
    scope: CoroutineScope,
    repository: ConversationRepository,
    conversationId: String,
) {
    val state: StateFlow<SystemPromptEditorState>
    fun edit(text: String)
    fun save()
    fun clear()
}
```

`Unavailable` is a failed read, a different statement from "no prompt stored" — that is a `Loaded`
whose `confirmed` is `null`. Editing and saving are impossible from `Loading` or `Unavailable`, so an
unread prompt can never be overwritten. `confirmed` keeps `null` (no prompt), `""` (an explicit empty
prompt) and text apart in both directions — the same three-state contract
[`ConversationRepository.requestSystemPrompt`/`setSystemPrompt`](conversation-repository.md#823-requestsystemprompt--setsystemprompt--the-conversation-scoped-system-prompt-contract)
established in #823, held verbatim through this state too since the prompt is untrusted
operator-authored text. `appliedStatus` is `null` for "unknown" — the gap between a save landing and
its follow-up read returning, or that follow-up read failing outright — never derived from `confirmed`.

`changed` reads an absent `confirmed` as an empty box (`draft != confirmed.orEmpty()`), so opening a
conversation with no prompt and saving sends nothing rather than storing `""`; an emptied box over a
stored `""` is likewise unchanged, while an emptied box over stored text is a real change that sends
`""` verbatim. `draftBytes`/`overLimit` call `SystemPromptLimit.utf8Bytes`/`MAX_BYTES` (#823) rather
than re-counting — the one place the 8192-byte cap lives. `Loaded.toString()` is overridden to redact
`confirmed` and omit `draft` entirely, so a crash trace or a logged state cannot carry the prompt; the
generated `data class` `toString` would have printed both verbatim (flagged as a security review
SHOULD FIX, applied before merge).

## Behavior

- **Open.** `init` launches one `requestSystemPrompt` call. Success publishes
  `Loaded(confirmed = reading.systemPrompt, appliedStatus = reading.sessionPromptStatus, draft = reading.systemPrompt.orEmpty())`.
  Failure publishes `Unavailable`. No retry — an owner that wants another attempt constructs a new
  editor.
- **`edit(text)`** is a no-op outside `Loaded`; otherwise replaces `draft`. Allowed while `saving` is
  true — a completing write publishes through `update {}` on whatever `Loaded` is current, so it never
  clobbers text typed during the write.
- **`save()`/`clear()`** share one private `write(value)`: `save` sends `draft` verbatim (`""`
  included) when `canSave`; `clear` sends `null` when `canClear`. Both gate through `begin`, a
  `compareAndSet` loop that flips `saving = true, saveFailed = false` atomically, so two taps cannot
  start two writes.
- **Write outcome.** Success publishes `confirmed = value, appliedStatus = null` immediately — the
  write's ack carries neither the value nor the status — then issues a second `requestSystemPrompt` and
  publishes only its `sessionPromptStatus`; the value that was just saved stays `confirmed` even if the
  refresh fails or a concurrent writer changed the stored prompt in between (decided explicitly in the
  architecture doc's Open Questions: the refresh adopts status only, never a re-read `systemPrompt`).
  `saving` clears after the refresh settles either way. A failed write leaves `draft` and `confirmed`
  untouched and sets `saveFailed = true`, so calling `save()` again sends the exact same draft. A clear
  additionally resets `draft` to `""` on success.
- **No session call.** Only `requestSystemPrompt` and `setSystemPrompt` are ever called — nothing
  starts, resets or restarts a session. `appliedStatus` reports when a saved prompt takes effect (the
  conversation's next session start); the editor never acts on that.

## Logging

Every `RelayLog.d` line is a static event name only — `system_prompt_read`,
`system_prompt_read_failed`, `system_prompt_saved`, `system_prompt_save_failed`,
`system_prompt_refresh_failed` — never the prompt, its byte length, the conversation id, or an
exception's `message` (not assumed content-free). `CancellationException` is re-thrown on every path
before it reaches a `catch (error: Exception)` block, per the `HostEditorController`-established
convention: letting it escape `viewModelScope` would kill the process.

## Binding and concurrency

Repository and conversation id are constructor-bound, so two `SystemPromptEditor` instances for the
same conversation id over two different hosts' repositories read from and write to only their own
repository — there is no shared state between them. `scope` must be the owning view model's
`viewModelScope`, exactly as `HostEditorController` requires: clearing the owner cancels the open read
and any in-flight write.

## Testing

`SystemPromptEditorTest` (`app/src/test/java/de/pyryco/mobile/ui/conversations/components/`) uses a
`ConversationRepository by FakeConversationRepository()` double that scripts `requestSystemPrompt` /
`setSystemPrompt` (queued readings or throws, a gate to hold a read open, and a record of every write)
— the `HostChannelListViewModelTest.Repo` delegation idiom. Fourteen cases cover: inertness while
`Loading`; `null`/`""`/text readings loading distinctly with their status; a failed read landing
`Unavailable` with edit/save staying inert; UTF-8 byte counting at and past the 8192-byte boundary;
verbatim save and `null` clear; an unchanged draft (no-prompt and stored-`""` cases) sending nothing;
a successful save refreshing status from a second read; refresh failure leaving `appliedStatus = null`
while `confirmed` stays the saved value; a failed save keeping both `draft` and `confirmed` so a retry
resends the same draft; two editors over two repositories touching only their own; and a `RelayLog`
capture asserting no line carries the prompt, its byte count or the conversation id.

**Coroutine-scope pitfall (test-writing only, not a code trap):** constructing the controller with
`runTest`'s `backgroundScope` as its `scope` looks natural but is wrong — `advanceUntilIdle()` does not
run coroutines launched in `backgroundScope`, so the editor never leaves `Loading` and every test fails
on the test's own setup, not on the code under test. Pass the `TestScope` itself (or call
`runCurrent()` after launching in `backgroundScope`). See
[Development verification § Test scheduling and harnesses](development-verification.md#test-scheduling-and-harnesses)
for the general rule.

No device test: nothing renders.

## Related

- [Conversation repository](conversation-repository.md#823-requestsystemprompt--setsystemprompt--the-conversation-scoped-system-prompt-contract) — `requestSystemPrompt`/`setSystemPrompt`, `SystemPromptReading`, `SessionPromptStatus`, `SystemPromptLimit` (#823), the contract this state drives
- [Host editor](host-editor.md) — the shape this state follows: plain-Kotlin controller, owner-scoped, `compareAndSet`-terminal transitions
- [Mobile modal § Callers](mobile-modal.md#callers) — `EditChannelModal` (#667), the caller this class was built for and the reason it stays unclaimed: a per-connection repository bind that a background/foreground reconnect retires
- [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) — `openChannelEditor`/`submitChannelEdit`, the repository-resolved-at-the-press shape this editor's would-be caller uses instead
- Spec: `docs/specs/architecture/824-system-prompt-editor.md`, including the security review
