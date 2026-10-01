# #1451 — Remove the Failed status dot state

## Files read

- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `ConversationAttention.Failed`, `resolveAttention`'s `failed` parameter, `HostAttentionState.failed` and its use in `onEvent`, `completed`, `opened` and `resolve`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt`: the `Failed` branches in `ConversationStatusDot` and `descriptionRes`.
- `app/src/main/res/values/strings.xml`: `cd_conversation_attention_failed`.
- Tests: `ConversationAttentionTest`, `HostConversationSourceAttentionTest.aTurnReDeliveredAfterAReconnectMarksNothing`, `ConversationTreeRowsTest`, and the androidTest `SidebarTreeCaptureTest` fixture and `InteractiveStreamE2ETest.attentionOf`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

Sidebar row status dot: the `primary` ring with a `warning` (waiting), blinking `tertiary` (running), `success` (unread) or transparent (idle) fill. No new visuals; the `error` fill goes away.

## Change

Desktop's `resolveConversationStatus` has no failed state, so mobile drops it. `ConversationAttention` loses `Failed`; `resolveAttention` loses `failed`; `HostAttentionState` loses its `failed` set, so `completed` no longer classifies the turn outcome (the `turnOutcomeReport` import goes) and `opened` only advances the read position. A Failed or StoppedEarly turn then records a read position like any completed turn, so it resolves Unread when not viewed and Idle once opened. The dot's `Failed` branches and the string go. `AttentionNotifier` never read `Failed`, so nothing else moves.

## Testing strategy

- `ConversationAttentionTest`: the precedence test drops the failed rung; the Failed case becomes "Failed and StoppedEarly turns resolve Unread, and Idle once opened"; the disconnect test expects `a` Unread.
- `HostConversationSourceAttentionTest.aTurnReDeliveredAfterAReconnectMarksNothing` expects Unread for the errored turn.
- `ConversationTreeRowsTest`: the description test covers the four states; a new pixel test renders each state's row and asserts no pixel is the theme's `error` colour.
- androidTest: `SidebarTreeCaptureTest` uses WaitingForAnswer for `c5`; `attentionOf` drops the string. Checked with `compileDebugAndroidTestKotlin`.
