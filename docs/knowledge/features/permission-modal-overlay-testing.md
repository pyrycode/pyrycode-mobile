# Permission-modal overlay — testing

Split out of [Permission-modal overlay](permission-modal-overlay.md) (#446/#452/#1306/#1337) on 2026-10-01 to
keep that overview under the docs-guard size cap. This page covers the render surface's own test coverage —
the shared screen test, the device-only capture test, and the rung-3 live scenarios. The render surface
itself, its security obligations and its visual spec status stay on the parent page.

Shared screen test `app/src/sharedTest/.../thread/ThreadScreenModalTest.kt`, available to both unit and
device suites, mirrors `ThreadScreenOverflowTest`'s idiom — the #446 set
(render array-order, exactly-one-default-highlight, dismissed × {remote, local, timeout}, forward-compat
fallback, hidden), extended by #452 with the armed/two-tap/send-error cases, and **adapted by
[#1306](../../specs/architecture/1306-inline-permissions.md) to the inline surface (31/31)**:

- **armed affordance** — `armedOptionId = "allow_once"`: **exactly one** option carries the
  `modal_armed_option_desc` marker and it is `allow_once`; with `armedOptionId = null` **none** does; the
  fail-safe-deny default (`reject_once`) **never** carries it even when a non-default is armed.
- **tap forwarding carries the rendered `modalId`** — tapping the default forwards
  `onModalOption("m1", "reject_once")`; tapping the explicit Cancel button invokes `onModalCancel("m1")`.
- **two-tap confirm (the AC#4 core)** — driven through a small **stateful VM-mimicking stand-in** (a
  `var armed by remember { mutableStateOf<String?>(null) }` whose `onModalOption` mimics #451's branch order),
  recomposing `armedOptionId = armed`: the **first** tap of a non-default arms it (no send recorded) **and**
  renders the armed affordance; the **second** tap of the same option confirms (send recorded).
- **send-error confidentiality** — a `Channel<Unit>` fed into `modalSendErrors` emits once: `modal_send_failed`
  is displayed and no payload substring (`rm -rf`) appears in the snackbar.
- **decision context** (#817) and **always-allow offer** (#818) — unchanged from the pre-#1306 assertions,
  now driven over the inline card: the reason/description/blocked-path labels, the `classifier` / `rule`
  sentence labels and the raw-category fallback; the offer's label and rules render between the context and
  the options, and tapping the row calls `onAlwaysAllowChanged("m1", true)` and **not** `onOption`.
- **#1306's new cases** — no dialog anywhere in the tree; the request renders in an **empty thread**; Back
  invokes the screen's own `onBack` without answering or cancelling; a history reader's scroll position is
  unmoved by request arrival or a grant toggle, and neither raises a second history demand; a new request
  reveals at the newest end (mirroring the #1305 reveal effect); Cancel renders below the card; and 320×700
  at 150% text keeps every decision reachable, wrapped and unclipped.

The compact-width case scrolls to every decision at 1.5× text and checks long labels for wrapping, overflow
and ellipsis. The fold logic remains unit-tested in #445, the decision logic in #451/#818/#1306 (see [Modal
answer flow § Testing](modal-answer-flow.md#testing)), and decode in [Modal
events](modal-events.md#the-four-decision-context-fields-817).

**Device-only `ThreadPermissionCaptureTest`, adapted to assert the activity window instead of the retired
dialog window.** Screen capture and obscured-touch hardening had a Compose dialog's own window to reach
through `(LocalView.current.parent as DialogWindowProvider).window` before #1306; with no dialog, the
device test now reaches `LocalView.current.context` as an `Activity` directly, asserting `FLAG_SECURE` and
the decor `filterTouchesWhenObscured` filter on the **activity** window while a request is present, that an
obscured `MotionEvent` on the default option and on the grant row is dropped while an unobscured one at the
same point acts, and that the prior window policy is restored once the request is removed. A focused
managed-device run (API 33) recorded 3 executed, 0 failed, 0 skipped; the affected
`QuestionBatchModalTest#inline_prompt_protects_capture_rejects_obscured_touches_and_restores_window_policy`
(the shared-owner counterpart) also passed, 1/1. Static-fixture screenshots of the normal, checked, armed
(412 × 892) and compact (320 × 700, 1.5×) states live under `app/src/androidTest/assets/permission-1306/`,
for the app-wide comparison in #1220.

> **Known test-strength NIT (code review, optional, predates #1306):** the send-error confidentiality test
> drives the error over a `Hidden` modal, so the `prompt` (`rm -rf …`) is never composed and the
> `assertDoesNotExist("rm -rf")` passes **vacuously**. The contract is enforced structurally (the event is
> `Unit` + a fixed local string), so not a real gap — but the assertion would be stronger driven over an
> **`Open`** request where the payload is actually on screen.

**Rung 3 (live end-to-end).** `InteractiveStreamE2ETest.interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`
was adapted to the inline card's selectors (`awaitReadPrompt` now scopes to the request card, not an
ancestor holding Cancel — a plain "holds Cancel" ancestor would also match the phone's own message naming
the same file inline). It ticks the grant and arms Allow in conversation A, leaves for B (no card, no A
prompt text), returns to A (grant still checked, arm cleared), and then needs two new taps — proving the
phone's answer, A's session grant and B's peer resolution survive the inline move. The dispatcher's
post-verifier live run recorded **43 executed, 43 passed, 0 failed, 0 skipped** for the full
`InteractiveStreamE2ETest` suite on 2026-09-30, with this method present and passing among them. See [Real-claude e2e coverage](../../e2e-interactive-stream.md).

**[#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md) added a sibling method,
`interactiveTurn_permissionPrompts_heldPerConversation`, on the curated `LIVE` method list in
`scripts/e2e-emulator.sh`:** A and B each raise a real prompt at once, both show in their own chats, and
answering A from the phone resolves only A's id while B's card stays mounted — proving the `HostModalState`
hold-all fold against a real daemon, not just the single-prompt-replacement case the older method covers. It
proves the phone's answer through the peer's `modal_dismissed` for A's id (source `remote`, outcome
`allow_once`) and the dialog leaving A, and does **not** await A's `turn_end`: the daemon streams turn
frames only for the conversation a message was last routed to (its `activeConversation` follow-active
cursor), and B's send in the method moves that cursor to B, so A's `tool_use` / `turn_end` after the allow
never reach any client. B's `turn_end` is still awaited, since B holds the cursor; that an allowed turn ends
at all is proven by the sibling `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, where no
other chat is routed. The dispatcher's post-verifier live run recorded **7 executed, 7 passed, 0 failed, 0
skipped** for the curated selection that includes this method alongside
`interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`,
`interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` and
`interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` — see [the real-claude e2e
ladder](../../e2e-interactive-stream.md) for the curated list and how a method joins it.

> **Known test-strength NIT (code review, optional):** the send-error confidentiality test drives the error
> over a `Hidden` modal, so the `prompt` (`rm -rf …`) is never composed and the `assertDoesNotExist("rm -rf")`
> passes **vacuously**. The contract is enforced structurally (the event is `Unit` + a fixed local string), so
> not a real gap — but the assertion would be stronger driven over an **`Open`** modal where the payload is
> actually on screen. A candidate strengthening when **#440** next touches this test.

> **Test-pitfall lesson (#1337).** `SecondClientPeer.recorded(conversationId)` filters recorded frames on
> the payload's own `conversation_id` field. `modal_dismissed` carries no `conversation_id` (§
> [Current-modal state](current-modal-state.md)), so an assertion of the shape "chat B's peer recorded no
> `modal_dismissed`" always passes whether or not B's prompt was actually left alone — it proves nothing.
> Prove *which* prompt was dismissed with `awaitModalDismissed(modalId)` instead, as
> `interactiveTurn_permissionPrompts_heldPerConversation` does for A's id.

## Related

- [Permission-modal overlay](permission-modal-overlay.md) — the render surface this page tests.
- [Modal answer flow § Testing](modal-answer-flow.md#testing) — the decision-logic tests this surface
  renders the result of.
- [Real-claude e2e coverage](../../e2e-interactive-stream.md) — the rung-3 ladder and curated LIVE list.
