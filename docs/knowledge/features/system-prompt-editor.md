# System prompt editor — shared channel system-prompt editing state

`ui/conversations/components/SystemPromptEditor.kt` (`de.pyryco.mobile.ui.conversations.components`)
holds one conversation's system-prompt editing state: what is stored, whether the running session
already uses it, the operator's in-progress draft, and save/failure tracking. Added in #824, shaped
like [`HostEditorController`](host-editor.md): a plain Kotlin object with no Compose or Android
imports, constructed over the owning view model's `viewModelScope`. It went unconstructed by any
caller for three tickets in a row: #666 split into [Save as channel](save-as-channel-dialog.md) (#957) and
[Create channel](mobile-modal-callers.md#callers) (#958), and neither wired it in, because both flows write a system
prompt only into a **new or freshly promoted** conversation, which has no stored prompt and no running
session to read `appliedStatus` from, so there is nothing for this editor's read-then-track shape to do
for them: they call `SystemPromptLimit.fits`/`utf8Bytes` directly for the byte-limit check and
`setSystemPrompt` once, verbatim, after their own create/promote leg confirms — no `requestSystemPrompt`,
no `draft`/`confirmed` distinction, no refresh read.

[`EditChannelModal`](mobile-modal-callers.md#callers) (#667) is exactly the caller this editor was described as
remaining available for — an **existing** conversation's already-stored prompt, edited against a
possibly-live session — and it still did not adopt it, for a reason specific to this class rather than
to the create/save-as flows: **`SystemPromptEditor` binds one repository at construction.** A host's
repository is per connection ([`HostConversationSource.repositoryFor`](dependency-injection-host-conversation-source.md)),
[`LifecycleConnectionDriver`](lifecycle-connection-driver.md) closes it on background, and the foreground
reconnect produces a new one. An operator who backgrounds the app mid-edit and returns would hold an
editor whose every further `save()` goes to a retired repository and fails forever — the construction-time
bind originally ruled out using it in a reconnect-sensitive list modal. That list
binding retired in #1582. The retained
[`ChannelEditorController`](channel-list-viewmodel.md#channeleditorcontroller-667--1561),
now driven by thread Edit, keeps the separate prompt-read and press-resolved write contract.
It copies this editor's `changed` rule (an absent `confirmed` reads as `""`) and
its redacted `toString()`, but keeps its own `ChannelPromptReading` sealed type rather than reusing
`SystemPromptEditorState.Loaded` — the reading is kept in a state flow separate from the write's own
`compareAndSet` target, which `SystemPromptEditorState.Loaded` conflates by design (see the Design section
of `docs/specs/architecture/667-edit-channel-modal.md`).

**#1342 is the caller this class was designed for.** `ThreadViewModel` constructs a `SystemPromptEditor`
for the thread's own repository — the [`StableConversationRepository`](stable-conversation-repository.md)
facade, which survives a background/foreground reconnect by design, unlike the per-connection repositories returned by
`HostConversationSource.repositoryFor`. The construction-time bind this class
has always had is exactly what that facade makes safe. `ThreadViewModel` constructs one each time the
Channel info sheet's `ThreadEvent.ChannelInfo` opens it (so every open re-reads the prompt) and drops it
to `null` on every path that closes the sheet (dismiss, Archive, Delete) — see
[ChannelInfoSheet § System prompt section](channel-info-sheet.md#system-prompt-section) for the host-side
wiring. A caller whose repository can retire out from under it — any list-screen modal reached through
`HostConversationSource` — should still follow \#667's shape instead: read once through the repository
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
        val refusal: SystemPromptRefusal? = null,
        val saved: Boolean = false,
    ) : SystemPromptEditorState {
        val draftBytes: Int
        val overLimit: Boolean
        val changed: Boolean
        val canSave: Boolean
        val canClear: Boolean
    }
}

enum class SystemPromptRefusal { Malformed, NotFound, Unclassified }

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

`changed` still reads an absent `confirmed` as an empty box (`draft != confirmed.orEmpty()`), but since
\#1342 no control gates on it — this caller's Save and Clear follow desktop's `deriveSystemPromptSection`
instead: `canSave = !saving && !overLimit` sends `draft` verbatim whatever it holds (including `""`, and
including an unchanged draft), and `canClear = !saving` sends `null` whenever no write is running,
regardless of whether a prompt is stored. `changed` remains the honest "box differs from the last
reading" fact — `ChannelListViewModel`'s own, separately-owned `ChannelPromptReading` copies this exact
rule for `EditChannelModal`'s write gate, as it always has (see
[ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring)).
`draftBytes`/`overLimit` call `SystemPromptLimit.utf8Bytes`/`MAX_BYTES` (#823) rather
than re-counting — the one place the 8192-byte cap lives. `refusal` (`SystemPromptRefusal`: `Malformed`,
`NotFound`, `Unclassified`) classifies a failed write by its code alone, set together with `saveFailed`
and cleared by the next `begin`; `saved` marks the last write's ack and is likewise cleared by the next
`begin`. `Loaded.toString()` is overridden to redact `confirmed` and omit `draft` entirely, so a crash
trace or a logged state cannot carry the prompt; the generated `data class` `toString` would have
printed both verbatim (flagged as a security review SHOULD FIX, applied before merge). `refusal` and
`saved` carry no prompt content, so `toString()` prints both as-is.

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
- **Write outcome.** Success publishes `confirmed = value, appliedStatus = null, saved = true` immediately
  — the write's ack carries neither the value nor the status — then issues a second `requestSystemPrompt`
  and publishes only its `sessionPromptStatus`; the value that was just saved stays `confirmed` even if the
  refresh fails or a concurrent writer changed the stored prompt in between (decided explicitly in the
  architecture doc's Open Questions: the refresh adopts status only, never a re-read `systemPrompt`).
  `saving` clears only after the refresh settles either way — so the write line keeps showing "Saving" and
  both Save and Clear stay disabled for the whole gap between the ack and the refresh landing, on purpose
  (#1342 code review NIT, left as-is: clearing `saving` on the ack would let a second write race the
  first write's own status re-read). A clear additionally resets `draft` to `""` on success — **when** the
  ack lands, not when the button is pressed, unlike desktop's `onClear`, which empties the box immediately;
  text typed into the box while a clear is in flight is overwritten when the ack arrives (#1342 code
  review NIT: no acceptance criterion depends on the immediate-clear timing, and the on-ack shape is the
  same one every other write here already follows).
- **Write failure and refusal.** A failed write leaves `draft` and `confirmed` untouched and sets
  `saveFailed = true`, so calling `save()` again sends the exact same draft. It also sets `refusal` via the
  top-level `refusalFor(error: Exception): SystemPromptRefusal`, which reads only the failure's type and
  `RelayErrorException.code` — never `message` — in this order: `RelayErrorException("protocol.malformed")`
  → `Malformed`; `RelayErrorException("conversation.not_found")` → `NotFound`;
  `kotlinx.serialization.SerializationException` → `Unclassified` (checked **before** the next branch,
  because it is an `IllegalArgumentException` subclass — it is what `setSystemPrompt` throws when a
  `conversation_updated` ack fails to decode, and by then the daemon has probably applied the write, so the
  no-record line would be wrong); any other `IllegalArgumentException` → `NotFound`, because
  `RelayRequests.mapError` rewrites the wire's `conversation.not_found` into that exception type rather
  than a `RelayErrorException` — a classifier keyed only on `RelayErrorException.code` would show the
  unclassified line for a missing channel; anything else → `Unclassified`. `ChannelInfoSheet`'s
  `SystemPromptRefusal.line()` turns each case into desktop's matching `WRITE_REJECTED` line.
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
— the `HostChannelListViewModelTest.Repo` delegation idiom. Sixteen cases cover: inertness while
`Loading`; `null`/`""`/text readings loading distinctly with their status; a failed read landing
`Unavailable` with edit/save staying inert; UTF-8 byte counting at and past the 8192-byte boundary;
verbatim save (including an unchanged draft, since #1342 removed the `changed` gate on `canSave`) and
`null` clear with no stored prompt; `canSave` false over the byte limit; a successful save refreshing
status from a second read; refresh failure leaving `appliedStatus = null` while `confirmed` stays the
saved value; a failed save keeping both `draft` and `confirmed` so a retry resends the same draft and
classifying the failure (`protocol.malformed` → `Malformed`; `RelayErrorException("conversation.not_found")`
and a bare `IllegalArgumentException` → `NotFound`; `kotlinx.serialization.SerializationException` →
`Unclassified`, ahead of the `IllegalArgumentException` branch it would otherwise fall into; anything
else → `Unclassified`); `saved` set on a write's ack and reset by the next `begin`; two editors over two
repositories touching only their own; and a `RelayLog` capture asserting no line carries the prompt, its
byte count or the conversation id.

`ThreadViewModelSystemPromptTest` and `ThreadScreenSystemPromptTest` cover the #1342 caller: the first
that opening Channel info constructs an editor and reads once, that dismiss/Archive/Delete each drop it to
`null`, that a reopen reads again, and that `SystemPromptEdit`/`Save`/`Clear` events forward to it; the
second (shared/Robolectric) that the section renders after Memory and before Actions with the Loading,
Unavailable and Loaded (field, byte count, over-limit line with `error(...)` semantics, differs line, each
write line) states and that Save/Clear fire the expected callbacks and disabled states. See
[ChannelInfoSheet § Tests](channel-info-sheet.md#tests).

**Coroutine-scope pitfall (test-writing only, not a code trap):** constructing the controller with
`runTest`'s `backgroundScope` as its `scope` looks natural but is wrong — `advanceUntilIdle()` does not
run coroutines launched in `backgroundScope`, so the editor never leaves `Loading` and every test fails
on the test's own setup, not on the code under test. Pass the `TestScope` itself (or call
`runCurrent()` after launching in `backgroundScope`). See
[Development verification § Test scheduling and harnesses](development-verification-test-scheduling.md#test-scheduling-and-harnesses)
for the general rule.

No device test: nothing renders.

## Related

- [Conversation repository](conversation-repository.md#823-requestsystemprompt--setsystemprompt--the-conversation-scoped-system-prompt-contract) — `requestSystemPrompt`/`setSystemPrompt`, `SystemPromptReading`, `SessionPromptStatus`, `SystemPromptLimit` (#823), the contract this state drives
- [Host editor](host-editor.md) — the shape this state follows: plain-Kotlin controller, owner-scoped, `compareAndSet`-terminal transitions
- [ChannelInfoSheet § System prompt section](channel-info-sheet.md#system-prompt-section) — the #1342 caller: `ThreadViewModel` constructs and drops this editor around the Channel info sheet's open/close, and the composable that renders `state`
- [Mobile modal § Callers](mobile-modal-callers.md#callers) — `EditChannelModal` (#667), the caller this class was designed for but stays unclaimed: a per-connection repository bind that a background/foreground reconnect retires
- [ChannelEditorController](channel-list-viewmodel.md#channeleditorcontroller-667--1561) — `open`/`submit`, the repository-resolved-at-the-press shape `EditChannelModal` uses instead, and the #1342 clear rule (an emptied box over a stored prompt sends `null`) that the controller now shares with this class's own `clear()`
- Spec: `docs/specs/architecture/824-system-prompt-editor.md`, including the security review
- Spec: `docs/specs/architecture/1342-channel-info-system-prompt.md` — mounting this editor in Channel info, desktop's `canSave`/`canClear` rules, and `refusalFor`
