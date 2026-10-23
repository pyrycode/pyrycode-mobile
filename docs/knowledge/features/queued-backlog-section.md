# Queued backlog rendering — `QueuedMessageRow`

## Supplied presentation

The row remains a de-emphasized queued message with existing controls and styling.
Authoritative item state and local pending feedback supply it; runtime `foldQueuedRows`,
echo correlation and raw queue snapshots were removed in #2367. Component tests can
prepare literal `ThreadRow.Queued` values without reproducing a content route.
See [queue identity and action ownership](queued-backlog.md).

## Shape

```kotlin
@Composable
fun QueuedMessageRow(
    text: String,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
    onSendNow: (() -> Unit)? = null,
)
```

`QueuedMessageRow` is the promoted, single render path for queued and local pending presentation so they cannot drift apart. It carries no id: `onDrop` and optional `onSendNow` are payload-free triggers, bound by
the caller (`ThreadScreen`'s `onDrop = { onDropQueued(row.queuedMessageId) }`), so the row itself never
holds a `Long` it could leak into a render, a key, or a log. The composable stays **stateless**: no
`ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no internal mutable state.

## What it does

Each row mirrors the sent user bubble, with the waiting state dimmed and actions visible:

- An end-aligned custom `Layout` retains the leading 100dp `MessageRoleInset` and the
  shared `MessageContentGutter`. Only the waiting glyph and bubble receive `QUEUED_ALPHA = 0.6f`.
- The decorative 16dp `Icons.Outlined.Schedule` glyph uses `onSurfaceVariant` and no
  content description. It precedes the action column by 12dp.
- The bubble keeps shared `BubbleShape`, `userBubbleContainer`, `onPrimaryContainer` and
  plain `Text(text, bodyMedium)`. Never use `MarkdownText` for this unsent input, including
  unmatched queued text relayed from another paired device.
- Send now sits above Cancel in a 13dp visual column, 12dp left of the bubble. Both
  glyphs are 12×12dp, have no backing and use full-opacity theme Primary. Send now reuses
  `ic_composer_send`; Cancel uses the uncircled `ic_queued_cancel` X.
- The row's merged semantics announce its text plus `thread_queued_state_desc`
  ("Waiting to send"). Each clickable action retains a separate `Role.Button` node,
  labelled by `cd_thread_queued_send_now` or `cd_thread_queued_drop`.

### Styling

The [queued-action component](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=847-14138)
and [Mobile placement reference](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=848-9517)
replace the earlier trailing controls (#1918). Glyph centres are 25dp apart, offset
12.5dp above and below the bubble midpoint. Each action has an independent 48×48dp
pointer target; the adjoining targets meet at that midpoint, with Send now above and
Cancel below. The paired bubble has a 96dp minimum height so short neighbouring rows
cannot share action targets. Cancel-only centres one glyph and one 48×48dp target on
the bubble, whose minimum height becomes 48dp.

The layout reserves the waiting glyph, its gap, the visual action column and the bubble
gap before measuring text. The bubble keeps the explicit 200dp maximum and shared
20dp horizontal / 16dp vertical padding. The actions are measured against the bubble
height and placed last, so their extended targets receive taps in the small horizontal
overlap with the bubble. Symmetric 8dp row padding retains the 16dp inter-bubble gap
and the thread's existing 8dp bottom rest compensation.

Cancel always remains available. `ThreadScreen` supplies Send now only when the current
fresh session's `runConfig.midTurnInputSupported` is true; false, unknown and held
capability states omit it. `ThreadViewModel.onSendQueuedNow` checks support again.
Each callback remains bound to the selected queued id and owning conversation/repository:
Send now keeps `send_queued_now`, and Cancel keeps the existing drop payload and failure
handling. The row performs no optimistic removal; admitted canonical facts still own its state.
See [capability freshness and delivery placement](queued-backlog.md).

Primary is a deliberate contrast resolution, following
[the sent copy/reply control](message-bubble.md#meta-row-and-copy-control).
The requested Inverse Primary measured 1.61:1 in light and 2.24–2.35:1 beside the dark
thread gradient. Full opacity alone did not make those active glyphs legible. Static
Primary uses `#32628D` in light and `#9DCBFC` in dark. The bubble fill follows
[MessageBubble's theme mapping](message-bubble.md#token-mapping-figma-roles-against-this-apps-two-schemes):
`#003355` at the static dark app root, with `primaryContainer` in other schemes.
The bubble's fill and text and the waiting glyph composite at 60%; actions stay opaque.

### Constants

`BubbleShape`, `BubbleHorizontalPadding`, `BubbleVerticalPadding`, `MessageRoleInset`
and `MessageContentGutter` come from [`MessageBubble.kt`](message-bubble.md).
File-private queued geometry is `QueuedBubbleMaxWidth = 200dp`, `WaitingGlyphSize = 16dp`,
`WaitingGlyphGap = 12dp`, `QueuedActionsWidth = 13dp`, `QueuedActionsGap = 12dp`,
`QueuedActionTarget = 48dp`, `QueuedActionCentreGap = 25dp`, `QueuedActionGlyph = 12dp`
and `QueuedRowVerticalPadding = 8dp`, with `QUEUED_ALPHA = 0.6f` applied selectively.

The 100dp role inset still narrows the available lane. Removing it was previously
rejected because a long queued bubble grew wider than the sent bubble family. Keep
both that inset and the independent 200dp cap; the visual column must be reserved
before measuring wrapped text.

### Testing

`ThreadRowIdentityTest.queueOccurrences_surviveRepeatedHistoryDeliveryAndFoldUpdates`
covers duplicate queued ids, including a matched earlier occurrence, across history
prepends, unrelated delivery, fold changes and repeated/reconnect-equivalent renders.
`ThreadRowsTest` retains one-to-one delivery, replacement and clear coverage. The
[real-screen anchor probe](thread-screen-subagent-tool-rows.md#collapsing-runs-of-consecutive-tool-rows-1635)
keeps unmatched queued rows below its tool anchor.

`QueuedMessageRowGeometryTest` checks exact glyph placement, shared padding, 16dp
inter-bubble spacing and centre/edge/midpoint pointer routing at 320dp and 412dp for
short, wrapped and long unbroken text, paired and Cancel-only, with neighbouring rows.
Keep the 600dp viewport assertion: testing only a narrow lane can miss a removed
200dp maximum. `QueuedBacklogTest` retains capability visibility and queued-id routing.

Token/opacity checks on a flat surface passed while the original glyph tint failed on
the production gradient. `palettes_keepActionsOpaqueAndOnlyBubbleAndWaitingDimmed`
therefore renders the actual `ThreadScreen` as well as flat light/dark surfaces, with
paired and Cancel-only actions and all three text shapes. A row-free second render
supplies each glyph's underlying canvas without duplicating the gradient implementation.
Solid glyph paint must retain Primary at full opacity and clear 3:1 across its footprint;
bubble and waiting paint must still composite at 60%.

The [refreshed capture evidence](../../../app/src/androidTest/assets/queued-actions-1918/README.md)
retains six hardware-rendered 412×892 PNGs and sidecars. Explicit component themes
cover light/dark paired and Cancel-only states because `MainActivity` pins static dark;
`queued-messages` and `queued-long` exercise assembled production dark-gradient placement
with fresh Send now capability. Retained XML records 7 geometry tests passed and both
`ThreadDesignCaptureTest.queuedActions_lightDarkAndCancelOnlyAt412By892` and
`queuedAndToolRowFramesAt412By892` passed in the focused Pixel 8 run: 2 executed,
0 failed/errors/skipped. Capture contrast minima are 6.12:1 light, 11.41:1 flat dark,
10.80:1 queued-messages and 8.38:1 queued-long. The verifier's refreshed UI gate also
ran both capture methods: 200 executed/passed, 0 failed, 1 unrelated skip.

For #1918, the dispatcher ran the full real-Claude suite on `38048ef4fd3a` merged
with main `c94771eba2b0`, report `2026-10-07T20-49-22-497Z`: **65 executed, 63 passed,
2 failed, 0 skipped**. Both existing rung-3 methods
`InteractiveStreamE2ETest.interactiveTurn_sendQueuedNow_reachesRunningTurn` and
`InteractiveStreamE2ETest.interactiveTurn_peerQueue_staysConsistentAcrossClients`
executed and passed in that full run. The two unrelated failures,
`interactiveTurn_finishedReply_systemCopyCopiesSelectedWord` and
`interactiveTurn_newSession_rendersSessionBoundaryDelimiter`, both passed in the
same-tree retry (2 executed/passed). The dispatcher accepted the gate after retry;
this was not a separate focused queue run. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1918#issuecomment-6046978192)
and [real-Claude ladder](../../e2e-interactive-stream.md).


## Position and action ownership

Canonical order/identity determine placement. A current `AuthoritativeQueueTarget`
selects the exact daemon queue id for Drop or Send now. Controls retain active-owner,
generation and revision checks; stale literal controls cannot send through a
replacement connection. Local pending feedback stays separate until exact accepted
device/message identity arrives. No ack or control send optimistically settles it.
