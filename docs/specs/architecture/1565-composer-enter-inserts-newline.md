# #1565 — The composer's Enter key inserts a line break

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` — the `BasicTextField` inside `ThreadInputBar`, whose `keyboardOptions` and `onKeyboardAction` change.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBarDraftBindingTest.kt` — the existing composer screen test whose setup the new test mirrors.

## Design source

N/A, per the ticket: the composer's visuals are unchanged; only the system keyboard's action key changes. The verifier's visual check is skipped on purpose.

## Change

`ThreadInputBar`'s `BasicTextField` drops `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)` and `onKeyboardAction = { onSend() }`, along with the two imports that only they used. The field keeps `TextFieldLineLimits.MultiLine(maxHeightInLines = 5)` with the default IME action, so the soft keyboard shows Enter and Enter, soft or hardware, inserts a line break. The send `IconButton`'s `onClick` stays the only send path; nothing else in the composer referenced the keyboard action, and no other composer field uses `ImeAction.Send`.

## Testing strategy

A new Robolectric screen test, `ThreadInputBarEnterKeyTest` in `app/src/sharedTest/.../ui/conversations/thread/`, beside `ThreadInputBarDraftBindingTest`: type text, press the hardware Enter key with `performKeyInput { pressKey(Key.Enter) }`, then assert the draft contains `\n` and `onSend` was never invoked. A second test taps the send button ("Send message") and asserts `onSend` runs once, covering the second criterion alongside the existing thread-screen send tests.

## Revisions

- 2026-10-03: the hardware-Enter test passed before the fix too, because the multi-line `BasicTextField` already turns a hardware Enter into a line break whatever its `imeAction`. `ThreadInputBarEnterKeyTest` therefore adds `field_declaresTheDefaultImeAction_notSend`, which asserts the field's `SemanticsProperties.ImeAction` is `ImeAction.Default`; that one failed against the old `ImeAction.Send` and is the test that guards the change.
