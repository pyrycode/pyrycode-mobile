# End-to-end test for the interactive event stream

Automated end-to-end coverage for the phone receiving claude's live reply. Manual testing of this
path is cumbersome, so this builds it steadiest-layer-first and climbs toward the flakier full-UI
layer with Compose + Espresso. Canonical design: pyrycode ADR 025; capstone wire test pyrycode #642.

## The ladder (reliable → flaky)

1. **Wire-level Go test** (`pyrycode#642`) — no emulator. Daemon → relay → simulated phone receives the
   structured stream. The steadiest rung; the future deterministic backend for rung 3/4. Lives in
   pyrycode, not here.
2. **Compose render test** — component level, deterministic, no daemon. Feed the thread a scripted
   structured-event stream and assert the assembled thread renders. **Layer 1a shipped (#432):** the
   reusable `ScriptedThreadHarness` drives the scripted stream through the **real**
   `RemoteConversationRepository` fold → `ThreadViewModel` → `ThreadScreen`, plus the first two render
   cases (text deltas → finalized message; `turn_state` → thinking spinner). Layer 1b (#435, split
   3-way, all riding the same harness) adds tool rows (**shipped #472**), the connection banner
   (**shipped #474**), and the session divider (**shipped #473**). See
   [Layer 1 — component render harness (rung 2)](#layer-1--component-render-harness-rung-2).
3. **Emulator + host daemon + real constrained claude** ← **what this directory ships.** The real app
   on a headless emulator connects to a host `pyry` + relay, sends "reply with exactly: ping", and
   asserts "ping" renders. Also covers a **tool-use** scenario (#481): a constrained prompt makes real
   claude run a shell tool and asserts the tool step renders; a **thinking-spinner** scenario (#482,
   the flakiest — ships `@Ignore`-gated / manual): a pure-reasoning prompt makes real claude think a beat
   and asserts the spinner shows mid-turn; and a **create-workspace-folder** scenario (#566): long-press
   the paired host's own add control on the channel list (#738 — the tree's host row, not the retired
   floating button) → Workspace Picker → create a folder → land in a fresh discussion whose
   workspace is the created folder → send the ping prompt in it → re-open the picker and confirm the
   folder shows in "Recent" (exercises the #564 create wire and #565 recents wire end to end against
   real claude); a **new-session** scenario (#541): with a live, exercised session, open the thread
   overflow menu → tap "Reset session" → the daemon wraps up and rotates, then broadcasts `session_transition`, and
   the thread renders the session-boundary delimiter (exercises the #540 fire-and-forget wire and the
   #336 fold end to end against real claude). In `InteractiveStreamE2ETest`,
   `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` selects “Reset session”
   with explicit conversation targeting (#625). The **delete-conversation** scenario (#554) renames a
   discussion to a runtime-unique name, confirms it is present on the channel list, then deletes it from the
   thread (overflow → "Channel info" → "Delete" → the "Delete conversation?" dialog → confirm) and asserts
   it is gone from the list and the thread has popped back (exercises the #532 delete wire against a real
   daemon; no claude turn — delete is a daemon round-trip); and an **archive/restore** scenario (#551):
   rename a discussion to a runtime-unique name, confirm it is present on the channel list, archive it from
   the thread (overflow → "Archive", immediate — no confirm) and assert it is gone from the list, then
   restore it (Settings → "Archived discussions" → the Archived screen's restore affordance) and assert it
   is back in the list (exercises the #549 archive/unarchive wire, the #556 archive-from-thread and #557
   restore surfacings against a real daemon; no claude turn — archive and restore are daemon round-trips);
   and a **change-workspace** scenario (#562): via the real thread overflow "Change workspace…" → Workspace
   Picker → create a new folder, complete a `change_workspace` round-trip to that runtime-unique target path
   and assert the conversation's recorded workspace (the chip) durably re-labels to it (exercises the #560
   change_workspace wire and #561 surfacing against a real daemon; no claude turn — change_workspace is a
   conversation-scoped daemon round-trip); and a **rename-conversation** scenario (#537): create a scratch
   discussion, drive the real thread overflow "Rename" → `RenameDialog` to a runtime-unique title, submit,
   and assert the new title appears durably on **two** surfaces — the thread top bar (in-thread, immediately
   after submit) and the conversation list (after popping back) — exercising the #530 rename wire against a
   real daemon; no claude turn — rename is a conversation-scoped daemon round-trip with no session transition;
   and a **save-as-channel (promote)** scenario (#581, driven through the #957 `MobileModal` form since
   pyrycode-mobile#957): create a scratch discussion, drive the real thread overflow "Save as channel…" →
   `SaveAsChannelDialog` to a runtime-unique channel name and a short system prompt, tap OK, and assert the
   promote round-trip lands on **three** durable surfaces — the thread top bar re-labels in place (no
   pop-back), the `WorkspaceChip` unmounts (the `isPromoted` tier flip), and after backing out the name
   presents on a channel-tagged row and on no chat-tagged row of the assembled conversation tree (the
   promoted tier, read via tag since #731 retired the Discussions drilldown this scenario used to tap into)
   — exercising the #348 promote wire against a real daemon. The chat is promoted **in its own `cwd`** (no
   location choice since #957 withdrew it, matching pyrycode-desktop#1436), so the scenario creates no
   folder on the operator's machine; the prompt exercises the modal's second write, `set_system_prompt`, on
   the same run. No claude turn — promote and the prompt write are conversation-scoped daemon round-trips
   with no session transition. This is the
   **backfill** of the one operator-facing flow that shipped before the real-stack definition-of-done rule
   (pyrycode-mobile-agents#9), and is **red on any daemon older than pyrycode/pyrycode#949**, which registered
   the handler the verb had been answering `unsupported` without; and a **list-archive-entry** scenario
   (#740): arrive on the channel list, assert the Archived screen's title is absent, tap the list's own
   archive entry (`CD_OPEN_ARCHIVE`, the sibling of the settings entry #737 put on the bar) and assert the
   title appears — proving the entry #737 shipped reaches Archived on its own, independently of the
   Settings route `interactiveTurn_archiveRestore_roundTripsListMembership` already covers; no claude turn,
   no seeded conversation — the bar is drawn on every state of the list; and a
   **two-hosts-colliding-conversation-id** scenario (#847): seed one conversation under a shared id but a
   different name on two isolated test daemons, pair the second host through the app's own scanner →
   paste-code flow, and assert each host's row, thread and cache stay separated by `(serverId,
   conversationId)` — re-checked after a rename of one host's conversation, after each host's relay link
   is cut and restored, and after the app's object graph is rebuilt over the same on-device state; no
   claude turn — pairing, navigation, rename and link cycling are daemon round-trips; and a
   **peer-started-turn** scenario (#848): a `SecondClientPeer` — a second paired device standing in for
   the desktop, built from the app's own transport, Noise session and wire codec classes with a throwaway
   in-memory key and its own `pyry pair` token — sends the ping prompt into a chat the phone has renamed
   and has open, and asserts claude's reply renders exactly once while the thread stays open and that the
   peer's message and the reply each render exactly once after the phone leaves and reopens the thread;
   one claude turn — the peer's ping; and a **peer-queue-consistency** scenario (#849 —
   `interactiveTurn_peerQueue_staysConsistentAcrossClients`): the peer starts a turn that has claude run a
   quick command it holds open behind a permission prompt (the peer allows it once), so the phone's ping
   queues behind it. The phone's queued row and the peer's `queue_state` snapshot agree; the phone drops a
   second queued message and the peer drops one of its own, and each disappears from the other device's
   view; once the peer allows the held command, the wait turn ends, the queued ping drains, and the phone
   shows claude's reply exactly once with no leftover queued row. Two claude turns — the peer's wait turn
   and the drained ping; and an **offline-read-reconcile** scenario (#850 —
   `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`): with the phone's own ping turn loaded
   and settled, it cuts its own link to the host and confirms the open thread, the chat's row on the
   list, and the thread reopened from disk all still show that turn; while the phone is offline the peer
   sends a second prompt and its turn ends, and the phone renders neither it nor its reply; on reconnect,
   with the thread still open, the peer's reply arrives by the daemon's ring replay and the prompt text
   arrives through the thread's own reconnect history re-ask
   ([#861](https://github.com/pyrycode/pyrycode-mobile/issues/861) — the re-ask now waits for the
   repository to be published rather than firing on socket-up), with no reopen in between — each of the
   four messages renders exactly once, in order. Two claude turns — the phone's ping and the
   peer's offline turn.
   Semi-deterministic. A **`LIVE=1` variant (#527, extended #566 / #541 / #554 / #551 / #562 / #537 / #581 / #740 / #847 / #848 / #849 / #850 / #891 / #946 / #545 / #687 / #950)**
   runs a **curated set of twenty-one scenarios** (ping + create-workspace-folder + new-session + delete +
   archive-restore + change-workspace + rename + save-as-channel + list-archive-entry + two-host +
   peer-started-turn + peer-queue-consistency + offline-read-reconcile + status-sheet-running-model +
   footer-context-usage + model-change + inherited-effort + chosen-effort + remembered-effort-recall +
   operator-bypass-permission + permission-held-running-tool, seventeen
   real claude turns — five pings (ping, create-workspace-folder, new-session, the peer-started turn's own
   ping, and the offline-read-reconcile scenario's own ping), the peer-queue-consistency scenario's wait
   turn and its drained ping, the offline-read-reconcile scenario's peer offline turn, the
   status-sheet-running-model scenario's own ping, the footer-context-usage scenario's own ping, the
   inherited-effort scenario's own turn, the chosen-effort scenario's own turn, the
   remembered-effort-recall scenario's two turns (one in its fresh chat, one in its fresh channel), the
   operator-bypass-permission scenario's two turns (a tool-free ping, then an outside-workspace Read), and
   the permission-held-running-tool scenario's own turn, plus a
   possible reset wrap-up turn — delete, archive-restore,
   change-workspace, rename, save-as-channel, list-archive-entry, two-host and model-change spend none)
   against the **production relay** over `wss://`
   (TLS) — the pre-ship gate that catches the live-environment failure class a local relay cannot; see
   [Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay). The ping, create-workspace-folder and
   new-session scenarios in `InteractiveStreamE2ETest` require a displayed exact
   ping reply in the message list (#694), independently of disappearing queued text.
   New-session also reveals the delimiter after a potentially tall wrap-up reply. The **model and effort
   settings round trip** (#545 — `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`,
   `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`,
   `interactiveTurn_chosenEffort_appliesFromTheFirstTurn`,
   `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`) is covered in its own
   paragraph below, after the peer scenarios.
   **Pending coverage:** #679 owns cross-device Stop in `InteractiveStreamE2ETest`:
   real turns in A and B, another device most recently using A, and phone Stop in B
   ending B while A continues. The curated twenty-one-scenario gate does not cover it.
   An open thread recovering a peer's reconnect-window prompt on its own — the gap #850 found — is
   **shipped (#861)**: the still-open thread's reconnect history re-ask now waits for the repository to
   be published, so it reaches a live repository instead of a socket that is up but not yet handshaked.
4. **Emulator + deterministic host** ← **shipped (#431).** The same real app + Noise/relay path, but
   claude is swapped for #642's scripted `fakeclaude` backend replaying raw stream-json fixture bytes.
   No real claude, **zero claude turns**; re-running back-to-back uses the same stream contract. Run it with
   `DETERMINISTIC=1` — see [Deterministic mode (rung 4)](#deterministic-mode-rung-4).
5. **Broaden** — multi-delta stream render + thinking indicator **shipped (#454)**, tool-use steps
   (running → done, and failed) **shipped (#455, Layer 2c)**, reconnect continuity (reply survives a
   mid-turn drop) **shipped (#476, Layer 2b)**, and reconnect **ordering** (events buffered while
   offline replay in order) **shipped (#477, Layer 2d)** on rung 4.

## Layer 1 — component render harness (rung 2)

The cheap rung: a Compose render test with **no network, daemon, or emulator-host**, fast enough to gate
every change. It joins the two halves that the rest of the suite covers separately — the **fold**
(scripted events → `List<ThreadItem>`, unit-tested in `RemoteConversationRepositoryTest`) and the
**render** (`ThreadScreen`, component-tested from a hand-built state in `ThinkingIndicatorTest`) — by
driving a scripted event stream through the real fold and rendering the result.

| Piece | File |
| --- | --- |
| Reusable harness — wires the real graph (`FakeSessionPump` → `RemoteConversationRepository` → `ThreadViewModel` → `ThreadScreen`), exposes `push*` scripting + `awaitReady()` | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#432) |
| The two render cases (text concatenation→finalize; `turn_state`→spinner) | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadRenderTest.kt` (#432) |

Key facts (see [`codebase/432.md`](knowledge/codebase/432.md) for the full notes):

- **Drives the REAL repository fold, not the dormant ViewModel fold.** The #337 `assistant_delta` fold
  lives in both `RemoteConversationRepository` (canonical — the live app renders it) and
  `ThreadViewModel.threadItems` (suppressed by a `turnId` render guard whenever the repo already produced
  the row, which it always does live). A fake-repo harness would exercise only the dormant path, so the
  harness wires the **real** repo; the only seam is `FakeSessionPump`.
- **Capability gate open** — built with `negotiatedCapabilities = { setOf("interactive") }`, or the fold
  produces nothing.
- **Subscribe-before-push** — `liveSessionEvents` is `replay = 0`, and `isThinking` is sourced only from
  it, so `awaitReady()` blocks every live push until the VM's collectors have subscribed (proven by the
  seeded channel name rendering in the top bar). That seed name must not be a substring of any asserted
  text (the #431 `e2e-ping`→`e2e-seed` false-green lesson, applied here as `"Harness channel"`).
- **Tolerant asserts only** (substring / presence, generous `waitUntil`) per the Constraints below; the
  text case asserts after `turn_end` (the streaming body reveals progressively and carries the caret).

Layer 1b (**#435**, blocked on #432) extends the same harness with tool rows, the connection banner, and
the session divider — additive scripting methods, no re-wiring. #435 was split 3-way 2026-06-23, **all
three now shipped**: **tool rows (#472)** — `pushToolUse` / `pushToolResult` + `ScriptedToolRowTest`,
asserting running → done (the absence triad) and failed (see [`codebase/472.md`](knowledge/codebase/472.md));
the **connection banner (#474)** — `pushConnectionState` + `ScriptedConnectionBannerTest`, asserting
the connecting / reconnecting-countdown / offline affordances and a present→absent fence for connected
(see [`codebase/474.md`](knowledge/codebase/474.md)); and the **session divider (#473)** —
`pushSessionTransition` + `ScriptedSessionBoundaryTest`, asserting one folded `SessionBoundary` draws a
delimiter positioned between the two cross-session messages, driven through the **real** #336 fold (see
[`codebase/473.md`](knowledge/codebase/473.md)).

| Piece | File |
| --- | --- |
| Tool-step rows (running → done; failed) — `pushToolUse` / `pushToolResult` scripting + builders | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#472), `ScriptedToolRowTest.kt` (#472) |
| Connection banner (connecting / reconnecting / offline; absent when connected) — `pushConnectionState` scripting (lifted `FakeConnectionStateSource` field) | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#474), `ScriptedConnectionBannerTest.kt` (#474) |
| Session-boundary divider (one delimiter, between two cross-session messages) — `pushSessionTransition` scripting + `sessionTransitionEnvelope` builder (ported from `RemoteConversationRepositoryTest`) | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#473), `ScriptedSessionBoundaryTest.kt` (#473) |
| Parser-gap sentinel non-vacuity (a scripted row makes the guard fire; the finding names `site` + a sanitized `message_type` and never the payload body; an unrecognized `site` token drops) — `pushUnrecognizedMessage` scripting + `unrecognizedMessageEnvelope` builder, the one builder assembled through `kotlinx.serialization` rather than string interpolation because `raw` is itself JSON | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#586), `ScriptedUnrecognizedMessageTest.kt` (#586) |

## What rung 3 is made of

| Piece | File |
| --- | --- |
| Render fix (#337): fold `assistant_delta` into a streaming assistant row, finalize on `turn_end` | `app/.../data/repository/RemoteConversationRepository.kt` (`applyAssistantDelta`, `finalizeAssistantTurn`) + unit tests in `RemoteConversationRepositoryTest.kt` |
| Headless emulator via Gradle Managed Devices | `app/build.gradle.kts` (`testOptions.managedDevices`, device `pixel2Api33Atd`) |
| Test-only credential injection seam | `app/src/androidTest/.../e2e/E2eInstrumentationRunner.kt` + `E2eTestApplication.kt` |
| The instrumented test | `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` |
| Host orchestration | `scripts/e2e-emulator.sh` |
| Arrival marker + create-chat helpers (#736, re-pointed at the host row's own control by #738): `awaitChannelList()` / `createChat()` / `openWorkspacePicker()`, keyed on `CHANNEL_LIST_TEST_TAG` and `treeHostAddTestTag(serverId)` | `app/src/main/.../ui/conversations/components/ConversationTreeRows.kt` (`treeHostAddTestTag`) + `app/src/main/.../ui/conversations/list/ChannelListScreen.kt` (marker) + `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` (helpers) |

**Arrival and creation handles (#736, #738).** Every scenario reaches the channel list through the shared
`awaitChannelList()` helper, which waits on the list's own app-authored arrival marker
(`CHANNEL_LIST_TEST_TAG`, set once on the screen's root so both draws — the blank placeholder and the
assembled tree — carry it) instead of waiting on a visible element. `createChat()` (tap) and
`openWorkspacePicker()` (long-press, kept its name across #904 even though it now opens
[Add workspace](knowledge/features/mobile-modal.md#callers), not the sheet the name still describes — the
tag-based handle it drives did not change) both drive a shared `awaitHostAddControl()` helper that waits on and returns
the **paired host's own** add control — `treeHostAddTestTag(serverId)`, `serverId` read from the harness's
own `ARG_SERVER_ID` instrumentation argument — since #738 moved chat creation off the floating button and
onto each host row, and a per-host tag stays unambiguous once a second host is paired where the button's one
fixed content description could not. This moved 33 call sites (20 arrival waits, 11 taps, 2 long-presses, the
two `@Ignore`d manual scenarios included) onto the three helpers while the button was still there, so the
live gate proved the replacement handles before #738 removed the button and left only the two creation
helpers to re-point at the host row's control. See
[Conversation tree](../knowledge/features/channel-list-screen.md#conversation-tree-731) for the marker's
production-side KDoc and its relationship to the tier tags #731 minted the same way, and
[Add controls](../knowledge/features/channel-list-screen-tree-and-controls.md#add-controls-738) for `treeHostAddTestTag`'s own
clamping rule.

Rung 3 covers twenty-two scenarios on this one harness: the **ping** happy path (a constrained reply renders);
a **tool-use** scenario (#481 — a constrained prompt makes real claude run a shell tool, asserting the
tool step renders, keyed tolerantly on the verbatim tool name `"Bash"` in the tool-row header); a
**thinking-spinner** scenario (#482 — a pure-reasoning prompt makes real claude think a beat, asserting
the spinner is displayed mid-turn, keyed tolerantly on the `cd_thread_thinking` content-description); a
**create-workspace-folder** scenario (#566, driven through the modal shell since #904 —
`interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`: long-press the paired host's own add
control on the channel list (#738) → [Add workspace](knowledge/features/mobile-modal.md#callers) → create a folder, which becomes the modal's selection → wait for OK to enable (folder selected, host connected) → OK → land in a fresh discussion whose workspace is the created folder → send the
ping prompt into it → re-open Add workspace from the channel list and assert the folder shows in "Recent",
proving the #564 create wire and #565 recents wire, and #904's modal move, end to end against real claude); a **new-session**
scenario (#541 — `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`: prove the session is live
with the ping, then open the thread overflow menu → tap "Reset session" → the daemon wraps up and rotates, then
broadcasts `session_transition`, and the thread renders the session-boundary delimiter, proving the #540
fire-and-forget wire and the #336 `session_transition` → `SessionBoundary` fold end to end against real
claude); and a **delete-conversation** scenario (#554 —
`interactiveTurn_deleteConversation_removesFromListAndClosesThread`: rename a scratch discussion to a
runtime-unique name, confirm it is present on the channel list, then delete it from the thread — overflow
→ "Channel info" → the sheet's "Delete" → the "Delete conversation?" dialog → confirm — and assert **both**
durable post-conditions: the unique name is gone from the list and the thread has popped back, proving the
#532 delete wire against a real daemon; **zero** claude turns — create/rename/delete are daemon
round-trips); and an **archive/restore** scenario (#551 —
`interactiveTurn_archiveRestore_roundTripsListMembership`: rename a scratch discussion to a runtime-unique
name, confirm it is present on the channel list, archive it from the thread (overflow → "Archive",
**immediate — no confirm**) and assert it is **gone** from the list, then restore it (settings → "Archived
discussions" → the Archived screen's restore affordance) and assert it is **back** in the list — the round
trip closes; proving the #549 archive/unarchive wire, the #556 archive-from-thread and #557 restore
surfacings against a real daemon; **zero** claude turns — create/rename/archive/restore are daemon
round-trips); and a **change-workspace** scenario (#562 —
`interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace`: create a plain discussion, then via the real
thread overflow "Change workspace…" → Workspace Picker → "Create new folder…" → a runtime-unique name,
complete a `change_workspace` round-trip to that new target path and assert the conversation's recorded
workspace durably re-labels to it — read off the `WorkspaceChip` (`"Workspace: <newWorkspace> (change)"`, the
`cwd` basename), proving the #560 change_workspace wire and #561 surfacing against a real daemon; **zero**
claude turns — create-folder and change_workspace are conversation-scoped daemon round-trips); and a
**rename-conversation** scenario (#537 — `interactiveTurn_renameConversation_relabelsTopBarAndListRow`:
create a scratch discussion, drive the real thread overflow "Rename" → `RenameDialog` to a runtime-unique
title, submit, and assert the new title appears **durably** on **two** surfaces after the round-trip — the
thread top bar (in-thread, immediately after submit; there is **no** PopBack, so the thread stays open and
`state.displayName` re-labels in place) and the conversation list (after popping back) — proving the #530
rename wire against a real daemon; **zero** claude turns — create/rename are conversation-scoped daemon
round-trips with no session transition); and a **save-as-channel (promote)** scenario (#581 —
`interactiveTurn_saveAsChannel_promotesToChannelTier`, driven through the #957 `MobileModal` form: create a
scratch discussion, drive the real thread overflow "Save as channel…" → `SaveAsChannelDialog` to a
runtime-unique channel name and a short system prompt, tap OK, and assert the promote round-trip lands on
**three** durable surfaces — the thread top bar re-labels **in place** (there is **no** PopBack), the
`WorkspaceChip` **unmounts** (the `isPromoted` tier flip, in-thread), and after backing out the name is
**present on a channel-tagged row** and **absent from every chat-tagged row** of the assembled conversation
tree (i.e. presented in the promoted channel tier, not among the chats — since #731 replaced the flat list's
Discussions drilldown with a tag read on the single assembled list) — proving the #348 promote wire against
a real daemon. Since #957 there is **no location choice**: the chat promotes in its own `cwd`, so the
scenario creates no folder, and the typed prompt exercises the modal's post-promote `set_system_prompt`
write on the same run; **zero** claude turns — create/promote/prompt-write are conversation-scoped daemon
round-trips, promote being a pure registry op daemon-side); a **list-archive-entry** scenario (#740 —
`interactiveTurn_listArchiveEntry_opensArchived`: tap the list's own archive entry and assert the
Archived screen appears, proving the entry #737 put on the list's bar reaches Archived independently of
the Settings route; **zero** claude turns, no daemon round-trip at all — the bar is drawn on every state
of the list); and a **two-hosts-colliding-conversation-id** scenario (#847 —
`interactiveTurn_twoHostsCollidingConversationId_stayPerHost`: seed one conversation under a shared id
but a different name on two isolated test daemons, pair the second host through the app's own scanner →
paste-code flow, and assert each host's row, thread and cache stay separated by `(serverId,
conversationId)` — re-checked after a rename, after each host's relay link is cut and restored, and
after the app's object graph is rebuilt over the same on-device state; **zero** claude turns — pairing,
navigation, rename and link cycling are all daemon round-trips); and a
**peer-started-turn** scenario (#848 — `interactiveTurn_peerStartedTurn_continuesOnPhone`: a
`SecondClientPeer` sends the ping prompt into a chat the phone has renamed and has open, asserting
claude's reply renders exactly once while the thread stays open, then that the peer's message and the
reply each render exactly once after the phone leaves and reopens the thread — proving a turn started
from another client continues on the phone; **one** claude turn — the peer's ping); and a
**status-sheet-running-model** scenario (#891 — `interactiveTurn_pingPrompt_statusSheetShowsRunningModel`:
after the ping turn, open the Status sheet from the footer's status icon and assert the running-model row
shows a non-empty value that is not the unavailable note, with no model name hard-coded — proving the #890
`model_announced` reading reaches the sheet; **one** claude turn — the scenario's own ping); and a
**footer-context-usage** scenario (#946 — `interactiveTurn_pingPrompt_footerShowsContextUsage`: after the
ping turn, wait until the composer footer's `CONTEXT_USAGE_TEST_TAG` node's text matches `Cxt: \d+%`, with
no percentage hard-coded — proving the daemon's post-turn `context_usage` push reaches
`ThreadRunConfig.contextPercent` and renders in the footer; **one** claude turn — the scenario's own ping;
no reconnect or subscription-time ask is exercised, since [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)'s
Rework 1 removed the phone's `request_context_usage` send outright — see [Thread composer footer § Context
usage segment](knowledge/features/thread-composer-footer.md#context-usage-segment-946)). The tool-use
test asserts the **durable** terminal signal — the tool name in the resolved row —
not the transient running spinner: rung 3 has no scripted backend to hold the turn open, so racing the
spinner over a real relay turn is the "never on timing" failure the [Constraints](#constraints) forbid
(it is why rung 4's `tool` scenario needs a two-fragment release).

The **new-session** scenario (#541) is **always-on** (not `@Ignore`d): the delimiter is a **durable**
artifact that survives the turn — unlike #482's transient spinner — so it belongs in the always-on gate,
like #481's tool-name row. Its load-bearing matcher is the delimiter's reason-independent explanation
line (`"Claude doesn't remember messages above this line"`), which can **only** come from the rendered
`SessionBoundaryDelimiter`. “Reset session” selects the action; the explanation proves the
resulting boundary independently of the menu label. The delimiter's **absence is asserted before** the
reset tap (a deterministic guard, no extra claude turn), so its later appearance is attributable to
the action. `new_session` is **fire-and-forget** (pyrycode#831, #540), so nothing waits on or asserts an
ack — the observable is the displayed post-broadcast delimiter. The test scrolls to the newest
row while waiting, since a tall wrap-up can keep it off-screen (#694). Cost is the ping turn
plus the daemon's reset wrap-up turn when handoff notes are enabled.

The **delete-conversation** scenario (#554) is likewise **always-on** (not `@Ignore`d): both post-conditions
are **durable** structural facts — a conversation is in the channel list or not, and the thread has popped
back or not — so, like #541's delimiter and #481's tool-name row, it belongs in the always-on gate. The
seeded discussion is given a **runtime-unique** name (`"e2e554-" + System.currentTimeMillis()`) via
**Rename** (not "Save as channel", which promotes the conversation rather than merely naming it — the wrong
tier for a scenario that wants to delete a discussion), so its presence is observed on the list *before* the delete
and its `assertCountEquals(0)` after is a genuine present→absent inversion on the same surface — never a
match-everything, never a delta count or timing (the #481 / #566 token discipline, here applied to an
**absence**). The one gotcha: the Channel Info sheet's Delete `ActionCell` and the confirm dialog's button
are **both** the literal `"Delete"` and the sheet stays composed behind the dialog (`ThreadEvent.Delete`
leaves `pendingChannelInfo` true), so the confirm tap is disambiguated by the dialog's sibling `"Cancel"`
button (which the sheet has no equivalent of), never by z-order. Total real-claude cost: **zero** turns —
create/rename/delete are daemon round-trips, and the durable identity is the typed name, so no ping is sent.

The **archive/restore** scenario (#551) is the delete twin extended to a **round trip** and is likewise
**always-on** (not `@Ignore`d): both post-conditions are **durable** structural facts — a conversation is in
the active channel list or not — so, like #554's delete inversion and #541's delimiter, it belongs in the
always-on gate. The seeded discussion is given a **runtime-unique** name (`"e2e551-" + System.currentTimeMillis()`)
via **Rename** (the same reasoning as #554: the Archived screen renders only the display name, and a
scratch discussion's auto-name "Untitled discussion" is non-unique and un-findable there), so its presence
is observed on the list *before* archive and its `assertCountEquals(0)` after is a genuine present→absent
inversion — then its **re-appearance** after restore is a second, opposite inversion on the same surface,
attributable to the restore. Two structural differences from the delete twin: **archive is immediate** —
the "Archive" item sits directly in the thread overflow (no confirm dialog, no Channel-Info sheet, so
**none** of #554's "Delete"-collision disambiguation), and **restore navigates to a second screen** (channel
list → Settings → "Archived discussions" → the Archived screen, which opens on the **Discussions** tab by
default → the renamed discussion is on it, no tab tap). The one gotcha is the **restore-coroutine
cancellation race**: `RestoreRequested` runs `viewModelScope.launch { repository.unarchive(id); … }` scoped
to the **Archived screen's** ViewModel, so the test waits for the **"Restored" success snackbar** before
navigating back — otherwise `popBackStack` would cancel a launched-but-unstarted `unarchive` and the closing
presence check would flake to a timeout. Total real-claude cost: **zero** turns — create/rename/archive/restore
are all daemon round-trips, and the durable identity is the typed name, so no ping is sent.

The **change-workspace** scenario (#562) is likewise **always-on** (not `@Ignore`d): the recorded workspace
is a **durable** fact — the `WorkspaceChip` re-label survives the turn — so, like #554's / #551's list
inversions and #541's delimiter, it belongs in the always-on gate. Via the real thread overflow "Change
workspace…" (`mutationsSupported`-gated, **not** promotion-gated, so reachable on a plain discussion — PR
#572) → the Workspace Picker → "Create new folder…" it drives a runtime-unique target folder (`"e2e562-" +
System.currentTimeMillis()`), then asserts the chip re-labels to that basename. Because `change_workspace`
is **conversation-scoped** (keyed by `conversation_id`, a mirror of `rename`, **no** session transition), it
carries none of the session-scoped `currentSessionId == ""` blocker that re-parks the settings e2e (#545),
and the assertion targets the recorded `cwd` (the chip), never a session id. The unique name's **absence is
asserted before** the change and its appearance in the chip after is a genuine present→absent→present
inversion on the same surface — never a match-everything (the #481 / #566 token discipline). Two gotchas:
(1) the Create tap chains **two sequential daemon round-trips** (`create_workspace_folder` →
`change_workspace`), so a single `waitUntil` spans both; and (2) unlike #566 (whose picker opens over the
message-less channel list), the picker here opens over the **thread**, whose composer is also an editable
field, so the `CreateFolderDialog`'s field is disambiguated by its auto-focus (`hasSetTextAction() and
isFocused()` — the same disambiguation #554 uses for RenameDialog-over-thread). The chip is the assertion
surface **only because no message is sent** (`!hasMessages` gate), so it stays mounted. Total real-claude
cost: **zero** turns — create-folder and change_workspace are daemon round-trips, so no ping is sent.

The **rename-conversation** scenario (#537) is likewise **always-on** (not `@Ignore`d): the recorded name is
a **durable** fact — it survives the round-trip on **two** surfaces — so, like #554's / #551's list inversions
and #562's chip re-label, it belongs in the always-on gate. The four shipped siblings already drive the same
`RenameDialog` as a **seeding** step; this promotes rename from a seed to the **subject**, so only the
assertion target changes. Via the real thread overflow "Rename" (`mutationsSupported`-gated, **not**
promotion-gated, so reachable on a plain discussion — PR #572) → `RenameDialog` it types a runtime-unique
title (`"e2e537-" + System.currentTimeMillis()`) and asserts it on the **thread top bar** (in-thread,
immediately after submit) and the **conversation-list** recents row (after popping back). Because `rename`
is **conversation-scoped** (keyed by `conversation_id`, a mirror of `change_workspace`, **no** session
transition), it carries none of the session-scoped `currentSessionId == ""` blocker that re-parks #545, and
the assertion targets the recorded **name**, never a session id (the #545 lesson). The unique title's
**absence is asserted before** the rename and its appearance on each surface after is a genuine
absence→presence inversion — never a match-everything (the #481 / #566 token discipline). The load-bearing
difference from delete/archive: `RenameSubmit` performs **no** PopBack, so the thread stays open and the top
bar re-labels in place (`state.displayName`); the dialog leaves composition synchronously on Save, so the
top-bar match is never the dismissing field. The one gotcha is the RenameDialog-over-thread two-field
disambiguation (`hasSetTextAction() and isFocused()` + `performTextReplacement` on the pre-filled+selected
field — the same selector the four siblings use). Total real-claude cost: **zero** turns — create/rename are
daemon round-trips, so no ping is sent.

The **save-as-channel (promote)** scenario (#581) is the family's **backfill** member and likewise
**always-on** (not `@Ignore`d): the recorded name and the recorded `isPromoted` flag are **durable** facts, so,
like #537's top-bar re-label and #554's / #551's list inversions, it belongs in the always-on gate.
Save-as-channel shipped (#348) *before* the real-stack definition-of-done rule (pyrycode-mobile-agents#9), and
the gap it left was not theoretical: the daemon never registered the `promote_conversation` handler, so the verb
answered `unsupported` over the real wire and **the promote never happened**, while the mobile suite stayed
green against a fake daemon that answers anything. pyrycode/pyrycode#949 landed the handler (the desktop
parallel is pyrycode-desktop#430); this scenario is the mobile client half, and is **red on any older daemon
binary — that is the regression it exists to catch**. Via the real thread overflow "Save as channel…" — gated
on the conversation being **unpromoted**, **not** on `mutationsSupported` (unlike "Rename" / "Change
workspace…"), so reachable on a fresh discussion regardless of the capability flag — it opens the #957
`MobileModal` form, replaces the focused, pre-filled name field with a runtime-unique name
(`"e2e581-" + System.currentTimeMillis()`) whose **absence is asserted before** the submit, types a short
system prompt into the form's second field, and taps OK. It asserts **three** surfaces, all reading the same
`observeConversations(All)` projection a single confirmed upsert re-emits: the **thread top bar** (in-thread
— the modal closes with **no** PopBack once both writes are confirmed, so the thread stays open and
`state.displayName` re-labels in place), the **`WorkspaceChip` unmount** (the `isPromoted` tier flip — the
chip is gated `!isPromoted && !hasMessages` and no message is sent, so its disappearance is attributable
solely to the promote; the #562 gating fact read in the opposite direction), and, on the assembled
conversation tree (#731), **presence on a channel-tagged row ∧ absence from every chat-tagged row**. Because
`promote` is **conversation-scoped** with **no** session
transition, the assertion is the recorded name and flag, never a session id (the #545 lesson); and the reply is
a **bare conversation object folded by a confirmed upsert**, **not** a `conversation_updated` broadcast (that is
*rename's* shape, from #530), which is why every assertion is on rendered UI rather than a named wire message.
Three gotchas, current since #957: (1) the modal promotes **in place** — there is no location choice any
more (desktop withdrew it, pyrycode-desktop#1436), so `promote(..., workspace = null)` keeps the existing
`cwd` and the scenario creates no directory under the operator's `~/pyry-workspace`; (2) the modal opens
**over** the thread and holds a second field of its own (the prompt), so the name field is disambiguated by
auto-focus (`hasSetTextAction() and isFocused()` + `performTextReplacement` on the pre-filled+selected chat
name — the #537 selector, still verbatim) and the prompt field is reached by its own test tag; and (3) the
menu item `"Save as channel…"` (U+2026) and the modal's exact title `"Save as channel"` are both matched
**exactly**, and step 6 waits for that exact title to leave composition before reading the unique name — the
modal now stays open, holding the name, until both writes are confirmed, so this wait is the proof they
landed and prevents the modal's own field from matching as the top bar. Total real-claude cost: **zero**
turns — create/promote/prompt-write are daemon round-trips, so no ping is sent.

The **list-archive-entry** scenario (#740 — `interactiveTurn_listArchiveEntry_opensArchived`) is likewise
**always-on** (not `@Ignore`d): the bar #737 drew on the channel list's `topBar` slot is a **durable** fact
of every draw of the screen, so the scenario needs no host wait, no seeded conversation, no prompt and no
claude turn — unlike every sibling above, it spends nothing even in daemon round-trips. It proves the
list's **own** archive entry (`CD_OPEN_ARCHIVE = "Open archive"`, the sibling of `CD_OPEN_SETTINGS`, kept
in sync with `cd_open_archive` in `strings.xml` by a comment on the constant) reaches the Archived screen,
which until #740 was proven only at the event boundary
(`ChannelListScreenTest.archiveEntry_emitsArchiveTapped`) and, on a device, only via the Settings route
`interactiveTurn_archiveRestore_roundTripsListMembership` already covers. `awaitChannelList()` is followed
by an absence check (`ARCHIVED_TITLE` has zero nodes — the list draws no "Archived" text) before the tap, so
the arrival after it is a genuine inversion, not a match-everything.

**Known gap, not yet hit:** the tap does nothing until `MainActivity`'s `ChannelListEvent.ArchiveTapped`
branch has a selected host — `RelayConnectionRegistry.selection` is set asynchronously by `reconcile` on
`Dispatchers.Default`, and `awaitChannelList()` waits only on `CHANNEL_LIST_TEST_TAG`, which is present on
the blank placeholder draw too, before the registry has necessarily reconciled. In the curated `LIVE=1`
list this scenario runs 6th of 9 in one shared process (JUnit's hash order), after five methods that
already wait on the host control, so the registry is populated by the time it runs; the race has never
been observed. It would surface in a single-method rerun — a builder reproducing a failure in isolation,
or any reordering that puts this method first — as a `LIST_TIMEOUT_MS` timeout with no other symptom. The
fix, if this is ever hit, is to call `awaitHostAddControl()` (with no click) between `awaitChannelList()`
and the tap; it needs no claude turn (verifier review on PR #838, 2026-09-23).

**Why the tier read needs a tag, not a `contentDescription` match** (the non-obvious fact a future sibling
will want; mechanism changed by #731 — the paragraph below describes the read as it works today, not the
pre-#731 drilldown). The assembled conversation tree **cannot** discriminate its two tiers with a tolerant
text or description matcher: both sections instance the same `TreeConversationRow`, with the same glyph, the
same type scale and no tier word anywhere on the row, and a section's header and its rows are **siblings**
inside one `LazyColumn` — no ancestor/sibling scoping to bet on. (Pre-#731, the flat list had the identical
problem for the same reason: `ConversationRow` and `DiscussionPreviewRow` set a merged `contentDescription`
from byte-identical format strings, `cd_conversation_row` == `cd_discussion_preview_row` == `"%1$s, %2$s"`.)
So the assembling screen tags each conversation row with the tier it drew it in
(`TREE_CHANNEL_ROW_TEST_TAG` / `TREE_CHAT_ROW_TEST_TAG`, `internal const` in `ChannelListScreen.kt`) and the
tier read matches the tag: presence on a channel-tagged row ∧ absence from every chat-tagged row **is** "in
the promoted (channel) tier, not among the chats" — entirely presence/absence, never a delta count (there is
no longer a "See all discussions (N)" counter on this screen at all). Because both tiers are drawn on the one
screen already on display, the read needs no navigation and therefore no arrival marker to get wrong — the
pre-#731 version had to dodge exactly that: `discussion_list_title` was byte-identical to the flat list's own
`recent_discussions_section_header`, so a title wait would have passed instantly, before navigating, and the
absence assertion would then have run against the main list — a false green. That whole failure class is
gone with the drilldown hop itself.

The **two-hosts-colliding-conversation-id** scenario (#847 —
`interactiveTurn_twoHostsCollidingConversationId_stayPerHost`) is likewise **always-on** (not `@Ignore`d):
row, thread and cache separation by `(serverId, conversationId)` (#731, #795–#798) are **durable**
structural facts, so it belongs in the always-on gate like #740's bar and #554's/#551's list inversions.
Daemon-minted conversation ids never collide on their own, so `scripts/e2e-emulator.sh` seeds the
collision before either daemon starts: one run-unique id, a different runtime-unique name
(`"e2e847-a-" + epoch` / `"e2e847-b-" + epoch`) merged into each of two isolated test instances'
`conversations.json` — host A's own instance, and a second `<PYRY_NAME>-b` instance spawned beside it.
Host A is pre-paired by `E2eTestApplication` as usual; host B is paired **through the app's own scanner →
paste-code flow** (`PasteCodeDialog` is unreachable directly — every scanner state offers a "paste the
pairing code" button that opens `PairCodeScreen`), with the harness re-pointing the minted payload's
`relay` at the phone's own relay URL before handing it to the test. `assertHostHoldsConversation` reads
each host's own repository for the seeded id **before** any UI check, so the scenario cannot pass on two
different ids under the hood. Separation is then read twice per checkpoint with no new test tag:
**under its own host** (folding a host's Channels row by its `cd_tree_row_collapse` description hides
only that host's conversation) and **opens its own conversation** (a row's thread shows only that row's
name in the top bar). Both reads are repeated after renaming host A's conversation from its thread
(#537's drive), after cutting and restoring each host's relay link in turn
(`RelayConnectionRegistry.connectionFor(serverId)`'s supervisor close/connect, the #476 pattern applied
per host), and after the app's object graph is rebuilt over the same on-device state — the restart an
instrumented test can perform without killing its own process. `ActivityScenario.recreate()` is the
wrong tool here: it retains the view models, which would keep holding the disposed graph, so the test
destroys the rule's activity first, calls the new `E2eTestApplication.rebuildGraph()` (unregisters the
old `LifecycleConnectionDriver`, `stopKoin`, then `startKoin` carrying the one `app_prefs` `DataStore`
over), then launches a fresh `MainActivity`. Host B is removed from the paired-server store in `finally`,
so a red scenario cannot leave a later scenario's host selection on host B. One gotcha for a future
scanner sibling: the scanner's camera-error and denied states each draw a **plain, non-clickable**
message containing "code instead" before their real paste button, so the paste-link matcher must require
`hasClickAction()` alongside the text match — the first version tapped the plain message on the headless
ATD's camera-error state and never navigated (verifier MUST FIX on PR #856). The live run that closed the
ticket executed all ten curated methods with no failures or skips, which also answered the plan's one
open question: the daemon serves a thread and a rename for the seeded conversation with no
`current_session_id`, so `seed_collision_conversation` needed no session-id binding. Total real-claude
cost: **zero** turns — pairing, navigation, rename and link cycling are all daemon round-trips.

The **peer-started-turn** scenario (#848 — `interactiveTurn_peerStartedTurn_continuesOnPhone`) is
likewise **always-on** (not `@Ignore`d): whether a turn from another client renders on the phone, and
whether it still renders exactly once after the phone leaves and reopens the thread, are **durable**
post-conditions, so it belongs in the always-on gate alongside #740's bar and #554's/#551's list
inversions. It rides a new fixture, `SecondClientPeer` (`app/src/androidTest/.../e2e/SecondClientPeer.kt`)
— a second paired device standing in for the desktop, built from the app's own `OkHttpRelayTransport`,
`NoiseSessionFactory` and `NoiseSessionPump`, with a throwaway in-memory X25519 key
(`Noise.createDH("25519").generateKeyPair()`, never the phone's Keystore key) and its own `pyry pair`
token — `peerToken`, minted on host A by `scripts/e2e-emulator.sh`'s `pair_token` alongside the existing
two-host pairing, never logged. The peer's `PairedServerStore` and `DeviceStaticKeyStore` are
file-private in-memory test doubles whose mutating members error, so the peer never writes the phone's
credential store, host list or registry selection. A scenario opens one peer (`open()` — connect, await
`TransportEvent.Up`, start the `NoiseSessionPump`, await `PumpState.Open`), sends through it
(`sendMessage()` — a `send_message` envelope, awaiting the `ack` by `in_reply_to`), reads its frames
(`awaitFrame()` — the first recorded envelope of a type naming the conversation id, findable even if it
arrived before the wait began, since every inbound frame is recorded from `open()` onward), and closes it
in `finally` (`close()` — tears down the pump's session keys and the transport; idempotent).

The scenario: the phone creates a chat and resolves its id as the one not in a pre-create snapshot, then
renames it to a run-unique name (`"e2e848-" + System.currentTimeMillis()`) **before** any message is
sent, so the daemon's first-message auto-naming (#2159 — a name set by rename is never overwritten) never
fires and the row stays findable afterward. The peer opens, sends the constrained ping prompt into that
conversation, and awaits its own `turn_end` frame — proving AC1, that the peer can send into a
conversation and observe that conversation's frames as its own device. The daemon carries no live frame
that names another device's message text — the turn's stream frames and `queue_state` fan out to every
interactive connection, and the message text reaches the phone only through history — so the scenario
checks what the operator sees, however it is delivered: with the thread still open, claude's reply
renders **exactly once** (`pingReplyMatcher()`, `assertCountEquals(1)`); after the phone leaves the
thread, reopens it by the run-unique name, and both the peer's message and the reply have arrived, each
renders **exactly once**. The prompt count is read inside the thread's scrollable list
(`hasText(PING_PROMPT) and hasAnyAncestor(hasScrollToNodeAction())`), not just anywhere on screen, because
the protocol's multi-device rule can fold the same message into a delivered history bubble and a
`queue_state` queued row — a message drawn once as each would count twice under a looser matcher, and the
top bar is excluded the same way. Total real-claude cost: **one** turn — the peer's ping. Read a failure
on either exactly-once count as a product finding about how the phone folds a turn it did not start, not
as harness flakiness (builder Lessons learned, PR #857). The peer is shared infrastructure: #849's
phone-reply, queue and drop scenario (below) rides it next, and #850's offline-read-reconcile scenario
(below) rides it after that.

The **peer-queue-consistency** scenario (#849 —
`interactiveTurn_peerQueue_staysConsistentAcrossClients`) is likewise **always-on** (not `@Ignore`d): a
displayed reply, a queued row's presence and its clearing are all **durable** post-conditions, so it
belongs in the always-on gate alongside #848's exactly-once counts. It rides #848's `SecondClientPeer`, the
phone creates a chat and renames it before any message is sent (the same #848 precondition, so the daemon's
first-message auto-naming never fires), then the peer opens the conversation and starts a turn: claude runs
a quick, deliberately unapproved command (`python3 -c "print(849)"` — not a bare `sleep`, see below) that
raises a permission prompt the peer alone can answer (`scripts/e2e-emulator.sh` pairs only the peer with
`--allow-remote-permissions`; the phone stays unprivileged, as every other scenario expects). The pending
prompt, not the command's run time, is what holds the peer's turn open for the steps that follow:

- **Phone queues (AC2).** The phone types and sends a ping prompt while the peer's turn is running. It
  shows as a queued row on the phone (`stateDescription` "Waiting to send") and as an item in the peer's
  latest `queue_state` snapshot.
- **Phone drops (AC3).** The phone queues a second message and taps its row's drop control. The daemon
  never replies to `dequeue_message` (see [Queued backlog § Dropping a queued
  entry](../knowledge/features/queued-backlog.md#dropping-a-queued-entry-dequeue_message-466)); the
  confirmation is the next `queue_state` for the conversation lacking the item, which the phone's queued
  row clearing, the peer's snapshot dropping it, and — since [#859](https://github.com/pyrycode/pyrycode-mobile/issues/859)
  — the phone's own echo leaving the thread (`awaitGoneFromThread(DROP_PROMPT)`) all key off. Before #859
  landed, the daemon's silent no-reply meant the phone's own echo stayed as a delivered bubble instead of
  disappearing; the step checked only the queued row, and the equivalent peer-drop assertion below was
  commented out. Both are restored now.
- **Peer queues and drops (AC3).** The peer queues a message of its own. The phone renders it as an
  ordinary, unmatched queued row (`foldQueuedRows`'s tail case — the same fold #781/#782 gave another
  device's item). The peer drops it fire-and-forget; the phone's queued row for it clears once the next
  `queue_state` says so.
- **Drain (AC1 + AC2).** The peer allows the held permission prompt once (`modal_answer` with
  `allow_once`, confirmed only by the matching `modal_dismissed` — the daemon sends no reply to
  `modal_answer` itself). The wait turn ends, the queued ping runs, and the peer sees that turn's
  `turn_end` with an empty backlog. The phone shows claude's ping reply exactly once, the ping prompt
  exactly once, and no queued row for it. Neither dropped message reaches claude, so the phone carries no
  node with either dropped message's exact text.

Two real claude turns: the peer's wait turn and the drained ping. `WAIT_PROMPT` cannot be a bare `sleep`
— Claude Code's Bash tool refuses a leading `sleep N` of 25 s or more unless it runs in the background,
before permission checks even run, so a scenario that needs a long-running turn has to wait on a command
that does not start with `sleep`; the constant's own comment records why. Folded into the pre-ship
`LIVE=1` gate as the 12th curated method, taking `LIVE_MINIMUM` from 11 to 12 and the run's real-claude
cost from four turns to six. The live run that closed the ticket
(`python3 scripts/android-test-gate.py live`, 2026-09-23) executed all twelve with no failures or skips.

The **offline-read-reconcile** scenario (#850 —
`interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`) is likewise **always-on** (not `@Ignore`d):
whether a loaded conversation stays readable with its host link cut, and whether it reconciles a peer's
turn once the link is restored, are **durable** post-conditions, so it belongs in the always-on gate
alongside #848's/#849's peer scenarios. It rides #848's `SecondClientPeer` a third time. The phone
creates and renames a chat (the #848/#849 precondition), then loads history with one ping turn — the cut
waits not only on the peer's `turn_end` but on the phone's own `ConversationCache` holding the settled
assistant reply (`awaitCachedAssistantReply`, polling every `CACHE_POLL_MS`), because the daemon fans
`turn_end` to each interactive connection separately and the peer's copy can arrive first; cutting on the
peer's frame alone risks dropping the phone's own still-streaming row (see [Caching conversation
repository § The merge base moves at a connection
boundary](knowledge/features/caching-conversation-repository.md#the-merge-base-moves-at-a-connection-boundary-not-on-every-emission)). `setHostLink(serverId, up = false)` —
the two-way split of the existing `cycleHostLink` cut/restore drive, keyed on
`RelayConnectionRegistry`'s coordinator repository going `null` or non-`null` rather than
`ConnectionState` — then tears down the connection-scoped repository, so what the open thread, the
chat's list row, and the thread reopened after navigating back all still show is provably retained cache
content, not a live read. While offline the peer sends a second prompt and its turn ends (`turn_end`
occurrence 2 keeps the wait honest); the phone draws neither the prompt nor the reply, the negative
control that shows it really was offline. On `setHostLink(serverId, up = true)`, with the thread still
open, the peer's reply arrives by the daemon's ring replay — the prompt text does not, since no live
frame carries another device's message text, only a history page does. When this scenario shipped
(#850), the still-open thread's own reconnect re-ask fired as soon as the socket came up, before the
coordinator's repository was back, and died with an `IllegalStateException` — a production bug that
ticket found and filed rather than fixed, [#861](https://github.com/pyrycode/pyrycode-mobile/issues/861),
and the scenario worked around it by leaving the thread and reopening the chat's row so a fresh
`ThreadViewModel`'s opening history ask ran on the live repository for both the prompt and the reply.
**#861 fixed the bug**: the reconnect re-ask now keys off the host's `coordinator.currentRepository`
going non-null instead of the socket-level `ConnectionState`, so it no longer fires ahead of the
repository it needs. Step 6 dropped the reopen; it waits in the still-open thread for both the peer's
reply and `OFFLINE_PROMPT` to render, then asserts each of the four messages (`PING_PROMPT`, its reply,
`OFFLINE_PROMPT`, its reply) renders **exactly once**, in `boundsInRoot.top` order. Two real claude
turns: the phone's ping and the peer's offline turn. Folded into the pre-ship `LIVE=1` gate as the 13th
curated method, taking `LIVE_MINIMUM` from 12 to 13 and the run's real-claude cost from six turns to
eight. The live run that closed #850 (`python3 scripts/android-test-gate.py live`, 2026-09-23; branch
`feature/850` at `c9b0fdc084` merged with `main` at `923b176bef`) executed all thirteen with no failures
or skips; two earlier live attempts on that branch failed first because the cut raced the phone's own
settled reply rather than the peer's, then because the reconnect assertion ran before the reopened
thread's history page had arrived, before the fixes recorded above landed. The live run that closed
\#861 (`python3 scripts/android-test-gate.py live`, 2026-09-23; branch `feature/861` at `24c9490d9a`
merged with `origin/main` at `148b9f7225`) re-proved the same thirteen scenarios with step 6's reopen
dropped — see the dedicated entry below.

The **model and effort settings round trip** (#545) is four **always-on** methods (none `@Ignore`d)
proving the settings read (#590), the applied-effort footer (#889) and the remembered-effort recall
(#686) reach a real daemon, since none of those tickets had live proof of its own. Each method prepares
its own conversations through the paired host's own repository — `RelayConnectionRegistry.connectionFor(serverId)`
— rather than seeding `conversations.json` as the #847 fixture does, because a seeded row has no bound
session and cannot take a model or effort write; `create_conversation` binds one before it replies.
Every method restores the settings it changed with `set_session_settings` on the same session and clears
the remembered level in `finally`, so a red run cannot leave a write behind for a later method.
`interactiveTurn_modelChange_roundTripsAndStaysPerConversation` (**zero** claude turns) picks three usable
rows from the published model menu at run time — never a hard-coded model name — writes two conversations
to two of them, changes one from the footer to the third, and asserts the change lands in a fresh settings
reply, survives a reopen, and never touches the other conversation.
`interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` (**one** turn) starts a chat with no
saved or remembered effort, sends the ping prompt, and asserts the next fresh reply carries
`effective_effort` (an omitted key fails the method) and that the reopened footer's label and note match
that value exactly, with no default level assumed.
`interactiveTurn_chosenEffort_appliesFromTheFirstTurn` (**one** turn) picks a model and effort level from
the footer before the first message and asserts claude applies exactly that level on the first turn.
`interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` (**two** turns, one in its
fresh chat and one in its fresh channel) taps an effort level on a priming chat, restarts the app
in-process the way `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` does (destroy the
activity, `rebuildGraph`, relaunch), then opens a fresh chat and a fresh channel — every fixture, including
the priming chat, keeps saved model `""` throughout — and asserts the recalled level reaches both before
their first message and stays the applied value after a real turn in each; a conversation with its own
saved effort keeps it. The remembered and explicit levels come from the published `default` row (failing
with "the published default row does not offer two effort levels" when it offers fewer than two), since
[#972](https://github.com/pyrycode/pyrycode-mobile/issues/972) made `ThreadRunConfig.effortChoices` look up
that row for a `""`-model reading instead of matching `""` against no published row; the priming chat's
footer is asserted against `INHERITED_RUN_CONFIG_LABEL` ("default"), not a row display name. The live run
that closed [#545](https://github.com/pyrycode/pyrycode-mobile/issues/545) also resolved the plan's two
open questions: `createChannel` accepts a created chat's own scratch `cwd` directly (no
`createWorkspaceFolder` fallback was needed), and the first fresh settings reply after a real turn's reply
already carries `effective_effort`. See [Thread composer footer §
Sourcing](knowledge/features/thread-composer-footer.md#sourcing) and [Thread composer footer — remembered
effort recall](knowledge/features/thread-composer-footer-effort-recall.md) for the production ranking and
recall rules these methods assert against.

The **operator-bypass permission** scenario (#687 —
`interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`) is likewise **always-on** (not
`@Ignore`d) but, unlike every scenario above, needs its own daemon: `scripts/e2e-emulator.sh` starts a
third, dedicated `pyry` instance under its own isolated `HOME` (§ 4a, `start_bypass_daemon`), with
`stdio_permission_prompt: true` in its config and its children launched with
`-- --dangerously-skip-permissions --permission-prompt-tool stdio` — the operator-bypass argv pyrycode's
`spawnPermissionDaemon` uses, since the daemon injects no approval gate of its own for operator bypass.
Claude authenticates from `CLAUDE_CODE_OAUTH_TOKEN` (the live gate's own route — the dispatcher keeps it
and the gate removes only `ANTHROPIC_API_KEY`) or `ANTHROPIC_API_KEY` for a manual run, copying the
operator's `~/.claude.json` into the isolated `HOME` on the OAuth route exactly as pyrycode's
`WithWorktreeAuthenticated` does; no credential reaches disk otherwise. A second prerequisite is the
daemon revision itself: `PYRYCODE_SRC` must point at a pyrycode checkout that shows the running `pyry`
binary's revision contains pyrycode `475c406a` (the `session_settings` confirmed-mode fix this scenario
proves). The phone pairs with the dedicated daemon by code, unprivileged, through the same paste-code flow
#847 exercises; a second `SecondClientPeer` pairs `--allow-remote-permissions` to it and answers the one
prompt this scenario raises — the phone itself never gains that privilege ([#966](https://github.com/pyrycode/pyrycode-mobile/issues/966)).
The chat is created with `createDiscussion` on the dedicated host rather than bound to its bootstrap
session: pyrycode's `Pool` mints a session from the bootstrap's own template, so the minted child inherits
the same operator pass-through and starts in bypass too — proven by the scenario's own first assertion,
not assumed.

Three acceptance steps, all against pyrycode#2510 (`475c406a`)'s `session_settings` reporting the mode
Claude last confirmed for the running child, not merely `yolo`: after a tool-free ping, a fresh reading
reports `bypassPermissions` and the reopened footer settles on Bypass approvals, proving the daemon and
the #650 footer agree from a cold read; choosing Manual approval sends a `default` write the daemon
acknowledges without touching a child whose stored mode is already `default` — the control stays pending
for the whole 15 s settle window (`PERMISSION_SETTLE_WINDOW_MS`), not a refusal's instant clear, and
settles back on Bypass approvals with a fresh reading still `bypassPermissions`, proving an
acknowledgement is not read as a confirmation; then Plan, Bypass approvals and Manual approval each settle
on their own label on the same session with no turn in between, each confirmed by a fresh reading before
the footer is asked to agree. Finally, still on that session, a Read of a file outside the conversation's
workspace — holding a fresh token that appears nowhere in the prompt — raises a permission prompt the test
matches by the file's base name or the word `Read`, scoped to the dialog holding the prompt's own Cancel
button so the phone's own message bubble (which contains both) cannot match; the peer allows it once, and
the assistant's reply must carry the token.

An unmet prerequisite — no credential, an unreadable `~/.claude.json`, a daemon revision `PYRYCODE_SRC`
cannot show contains pyrycode `475c406a`, no `claude` on `PATH`, or a fixture failure such as a pairing
that could not be minted — fails only this one method, naming the prerequisite; it never skips and never
fails the run. The script passes the reason as a static code (`bypassUnmet=no_credential`, not a free-text
sentence): `-Pandroid.testInstrumentationRunnerArguments.*` reaches the device through `am instrument -e`,
where a space in the value is not safe, so the method maps the code back to a full sentence itself
(`BYPASS_UNMET_REASONS`) rather than trusting the script to hand it prose — the same reason every other
instrumentation argument this suite passes (`peerToken`, `bypassServerId`, …) is a single token, never a
sentence. `cleanup` kills the dedicated daemon and removes its isolated `HOME` (with its `~/.claude.json`
copy) and the outside-workspace token file whatever the exit code, and never touches the operator's own
`~/.pyry/config.json`. Two real claude turns: the tool-free ping and the outside-workspace Read. See
[Thread composer footer § Permission mode](knowledge/features/thread-composer-footer.md#permission-mode-650)
for the footer's own settle-and-confirm rules this scenario exercises against a real daemon.

The **permission-held running-tool** scenario (#950 —
`interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`) is likewise **always-on** (not
`@Ignore`d): the status area naming the tool claude is running (#897's label) is a **durable** fact for as
long as the call stays open, so it belongs in the always-on gate alongside the settings and
operator-bypass scenarios above. A quick command's tool call closes before the phone can be sure to
observe it open, so the scenario borrows #849's lever instead of timing: `RUNNING_TOOL_PROMPT`'s
`python3 -c "print(950)"` is never auto-allowed, so claude's `tool_use` arrives and the call waits on a
permission prompt that only the `SecondClientPeer` paired with `--allow-remote-permissions` can answer
(the phone stays unprivileged, as every other scenario expects). While the prompt is pending, the test
asserts the exact `cd_thread_tool_running` description (`"Claude is running Bash"`) is displayed; the peer
allows the command once, and once the peer's own `turn_end` frame arrives, the description is asserted
gone. One real claude turn. The rung-4 `tool` and `tool-progress` scenarios are this scenario's
deterministic twins, proving the same label (without and with an elapsed reading) on every scripted run.

**`@Ignore`d**, and deliberately not added to the LIVE curated list: `interactiveTurn_longRunningTool_statusAreaShowsElapsed`
holds the same permission-prompt lever with `ELAPSED_TOOL_PROMPT`'s `python3 -c "import time;
time.sleep(45)"`, then waits for any `cd_thread_tool_running_elapsed` reading (a regex matcher built from
the resource with the elapsed slot wild-carded, since claude's own heartbeat — not the harness — decides
the seconds) before `turn_end` and absence. It cannot be made durable here: claude's first
`tool_progress` heartbeat arrived at 30 s on the one committed capture (Claude Code 2.1.259), leaving only
about 15 s to observe, and only if claude runs the command in the foreground as asked rather than
backgrounding it — a bare `sleep` of 25 s or more is refused outright by claude's own Bash tool, which is
why the command sleeps inside `python3` (the same constraint `WAIT_PROMPT` and #849's wait turn document).
It costs at least 45 s per run for a window neither guaranteed nor timed by the harness. The operator
un-ignores it to check the label's elapsed form against the current claude.

The **thinking-spinner** scenario (#482) is the **flakiest** rung-3 scenario and ships **`@Ignore`-gated /
manual**: the spinner has **no durable equivalent** of the tool name — once real claude emits its first
token, `turn_state` flips to `responding`, `isThinking` goes false, and `ThinkingIndicator` early-returns,
leaving nothing on screen. With no scripted backend to hold the turn open and no way to imperatively pause
  real claude (the levers rung 4's two-fragment release and the #432 component twin's `pushTurnState` have), the
mid-turn window cannot be made deterministic, so the developer cannot prove reliability without operator
infra. It therefore lands as a documented manual case (presence-only, tolerant, keyed on
`cd_thread_thinking`; **no** absence-after-end assertion that would race a 2nd turn, and **no** negative
control — the asserted token is a production content-description, not a claude-output substring). The
operator un-ignores to attempt the run and may promote it to always-on if a pure-reasoning prompt yields a
catchable window; otherwise it stays manual. See the
  [Assumptions](#assumptions-to-confirm-on-first-live-run) entry on the screen-sourced thinking window.

### The render gap fixed first (#337)

The test asserts streamed assistant *text* renders. Before this change there was nothing to assert:
the live stream was reduced only to the thinking indicator (`isThinking`, #406); `assistant_delta`
text was emitted on the events stream but never folded into a visible thread row. `RemoteConversationRepository`
now folds deltas into a `Role.Assistant` message keyed by `turnId` (mirroring how `tool_use`/`tool_result`
already fold), with `isStreaming = true`; `turn_end` flips it to `false`. `MessageBubble` already
renders that via its streaming-caret body, so no UI change was needed.

An interactive phone receives **only** the structured stream, never the whole-turn `message` for the
same turn (the server's fan-out is capability-exclusive: pyrycode `interactive_turn_v2` vs
`assistant_turn_v2`), so the folded row is the canonical reply — no de-dup against a `message` echo.

### The credential seam

`E2eInstrumentationRunner` is the module's `testInstrumentationRunner`. It installs `E2eTestApplication`
for every instrumented run. The branch happens in that app's `onCreate` (which runs *after* the
instrumentation registers its arguments — the runner's `newApplication` runs before, so it cannot read
them). With no e2e relay arguments it behaves exactly like the production app (default fake
repository), so existing component tests are unchanged. When the e2e relay arguments are present, it:

1. pre-writes a `PairedServer{serverId, token, relayUrl, serverStaticPublicKey}` (built from the
   instrumentation args) into the real credential store, so the app boots straight to the channel list
   (no QR scan) and dials the host relay at `ws://10.0.2.2:<port>`; and
2. binds the relay-backed repository (`conversationRepositoryModule(useRelay = true)`) — the runtime
   equivalent of flipping the compile-time `USE_RELAY_REPOSITORY`, scoped to the e2e run.

No secret is hardcoded: the token + keys are minted at run time by `pyry pair`.

The normal app real-server build tracked by product #631 is not a prerequisite for this instrumented
path. `E2eTestApplication` selects its relay-backed repository and writes the runtime pairing record when
the relay instrumentation arguments are present. A run without those arguments keeps the ordinary fake
repository binding. The normal app build remains a separate product acceptance check.

## How to run

Compile the instrumented suite before device execution; the ordinary app build and
JVM tests do not compile `app/src/androidTest`:

```bash
./gradlew compileDebugAndroidTestKotlin
python3 -m unittest discover -s scripts -p 'test_android_test_gate.py'
```

These local checks do not substitute for a live result. Reproduce the managed
API 33 baseline through the report-validating gate, with the authenticated host
and daemon prerequisites below:

```bash
DEVICE=pixel2Api33Atd python3 scripts/android-test-gate.py live
```

API 33 is the sole required Android version for the baseline and routine ticket
gates for now (2026-09-20 decision); API 35 is deferred and requires no run or
artifacts. The default local-relay rung-3 command remains:

```bash
bash scripts/e2e-emulator.sh
```

Prerequisites on the host:

- `pyrycode-relay` and `pyry` on PATH (override with `RELAY_BIN` / `PYRY_BIN`).
- The runner host has Claude authenticated — the daemon spawns real Claude. The interactive
  path is Max-subscription covered, so this does **not** meter tokens.
- Android SDK with the `aosp-atd` API 33 system image. AGP auto-provisions it on first run, which needs
  the SDK `cmdline-tools` installed and the image licence accepted (`sdkmanager --licenses`). On this
  machine `cmdline-tools` was absent at authoring time — install it before the first run.
- `python3` (decodes the base64url pairing payload).

The script: starts the relay → mints a device token with `pyry pair` and parses the payload → starts
the daemon (`PYRY_MOBILE_V2=1`, pointed at the loopback relay) → runs `pixel2Api33AtdDebugAndroidTest`
with the four values injected as instrumentation arguments → tears everything down.

Every rung-3 run (default and `LIVE=1`) logs the revisions under test in step 3: a `mobile revision:`
line (`git rev-parse HEAD` at the repo root) and a `daemon revision:` line (`go version -m` on the
resolved `pyry` binary, when `go` is on PATH). Either prints `unavailable` rather than being omitted
when it cannot be read (#850) — before this, the daemon line was skipped outright without `go` on PATH.

## Pre-ship gate

The live rung-3 real-Claude e2e is the post-verifier mobile **pre-ship gate**. The
dispatcher runs it for tickets carrying `needs-real-claude`, before documentation
and merge, so an operator is never the **first** real-stack execution. It is the
mobile parallel of the daemon's `make e2e-realclaude`. The dispatcher command is:

```bash
python3 scripts/android-test-gate.py live
```

The wrapper sets `LIVE=1` and a unique `e2e-auto-…` test instance per invocation (see
[Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay) below), so there is no env-var
incantation to remember — the twenty-one curated `@Test` methods (ping + create-workspace-folder, #566;
new-session, #541; delete, #554; archive-restore, #551; change-workspace, #562; rename, #537;
save-as-channel, #581; list-archive-entry, #740; two-host separation, #847; peer-started turn, #848;
peer-queue-consistency, #849; offline-read-reconcile, #850; status-sheet running model, #891; footer
context usage, #946; model change, inherited effort, chosen effort and remembered-effort recall, #545;
operator-bypass permission, #687; running-tool status label, #950) ride the wrapped mode.

These `InteractiveStreamE2ETest` cases preserve the ping and Reset-session
regressions after host-owned routing, since #847 that two paired hosts whose
conversations share an id stay separate through pairing, rename, link-cycling and a
restart, since #848 that a turn started from another paired device continues on the
phone, since #849 that phone replies, queued sends and drops stay consistent with
that same device's view of the backlog, and since #850 that a loaded conversation stays
readable while its host link is cut and reconciles a peer's turn once the link is restored.
They do not prove cross-device Stop, which belongs to
[#679](https://github.com/pyrycode/pyrycode-mobile/issues/679). The gap #850 found — an open thread
recovering a peer's reconnect-window prompt on its own, without being reopened — is closed by
[#861](https://github.com/pyrycode/pyrycode-mobile/issues/861): #850's scenario proves it directly, with
no reopen step.

**When the dispatcher runs it:**

- **after verifier on a ticket labelled `needs-real-claude`**, so the live stack is checked before
  documentation and merge; and
- **when a daemon or relay change touching the mobile surface lands**, alongside the daemon's own
  `make e2e-realclaude` when that acceptance crosses repositories.

**Cost:** seventeen real claude turns across twenty-one curated methods — five pings (ping,
create-workspace-folder, new-session, the peer-started turn's own ping, #848, and the
offline-read-reconcile scenario's own ping, #850), plus #849's peer wait turn and its drained
ping, #850's peer offline turn, the status-sheet-running-model scenario's own ping, #891, the
footer-context-usage scenario's own ping, #946, the inherited-effort and chosen-effort scenarios' own
turns, and the remembered-effort-recall scenario's two turns, #545, the operator-bypass-permission
scenario's two turns (a tool-free ping, then an outside-workspace Read), #687, and the
permission-held running-tool scenario's own turn, #950 —
and a reset wrap-up turn when handoff notes are
enabled. Delete, archive-restore, change-workspace, rename, save-as-channel,
list-archive-entry, two-host separation and the model-change scenario spend no Claude turns. Allow a few
minutes of wall clock; the run is subscription-covered.

The command must exit successfully and report at least `LIVE_MINIMUM` executed passing
tests, with no skips. `LIVE_MINIMUM` (`scripts/android-test-gate.py`) is 21 as of #950 —
the curated list's own size, not a looser bound. `test_live_floor_matches_the_curated_list`
(`scripts/test_android_test_gate.py`) counts the `#interactiveTurn_` methods in
`scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET` and asserts it equals `LIVE_MINIMUM`, so the
list and the floor cannot drift apart silently — a method dropped from either side reddens
this test before it reaches a live run. Before #848 the floor was pinned at 8, looser than
the then-ten-method list, specifically so raising it would not redden
`scripts/test_android_test_gate.py`'s recorded eight-case fixture
(`fixtures/default-workspace-live/588.xml` — plan `docs/specs/architecture/740-e2e-list-archive-entry.md`
§ Revisions, reaffirmed for #847). #848 resolved that tension instead of extending it: the
fixture stays byte-for-byte, and the gate's own test pads a copy of it with synthetic
curated-method cases up to `LIVE_MINIMUM`. #849 raised `LIVE_MINIMUM` from 11 to 12, #850
raised it again, from 12 to 13, #891 raised it once more, from 13 to 14, #946 raised it
again, from 14 to 15, #545 raised it again, from 15 to 19, #687 raised it again, from
19 to 20, and #950 raised it again, from 20 to 21, on the same mechanism. Shell cleanup preserves the original
result and retains failure artifacts; a clean XML report with a failing process status is not
a passing gate.

For the full mechanics — relay URLs, the isolated `e2e-live` instance, prerequisites, and first-run
assumptions — see [Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay). The workflow
summary is in [README § Pre-ship gate](../README.md#pre-ship-gate); the README deliberately does not
restate scenario counts or turn costs — this document is the single authority for gate scope and cost.

## Live mode (rung 3, live relay)

`LIVE=1` runs a **curated set of twenty-one rung-3 scenarios** — the real app on the emulator, a host `pyry`
daemon, and **real claude** — but against the **production relay** (`wss://pyrycode-relay.pyryco.de`)
over TLS instead of a local loopback relay. This is the post-verifier pre-ship gate: the dispatcher must
never be the **first** real-stack execution, and a local relay structurally cannot catch a live-environment failure
(the 2026-07-03 connect-drop loop was a five-week-stale relay deploy, invisible to any local run).

Use the gate for the reproducible API 33 baseline:

```bash
DEVICE=pixel2Api33Atd python3 scripts/android-test-gate.py live
```

It invokes `LIVE=1 bash scripts/e2e-emulator.sh`, forces fresh execution, and
collects only `app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd`.
For an explicitly selected `DEVICE=connected` run, it instead reads
`app/build/outputs/androidTest-results/connected/debug`. Reports from the other
path or another managed profile cannot satisfy the gate; stale, missing,
zero-count or failed reports and nonzero process exits fail it. Sanitized output
is saved as `build/dispatcher-tests/live-*/dispatcher.xml` and printed to stdout.
Connected-path regression coverage does not establish a live result on a connected
device. The [recorded baseline](#verification-status) proves only managed
`pixel2Api33Atd`, Pixel 2 / API 33 / AOSP ATD arm64. API 33 is the sole required
version for now; API 35 is deferred.

**What it runs.** Twenty-one curated methods, passed as a comma-separated `class#method` list:
`InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread`,
`InteractiveStreamE2ETest#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (#566),
`InteractiveStreamE2ETest#interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (#541),
`InteractiveStreamE2ETest#interactiveTurn_deleteConversation_removesFromListAndClosesThread` (#554),
`InteractiveStreamE2ETest#interactiveTurn_archiveRestore_roundTripsListMembership` (#551),
`InteractiveStreamE2ETest#interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` (#562),
`InteractiveStreamE2ETest#interactiveTurn_renameConversation_relabelsTopBarAndListRow` (#537),
`InteractiveStreamE2ETest#interactiveTurn_saveAsChannel_promotesToChannelTier` (#581),
`InteractiveStreamE2ETest#interactiveTurn_listArchiveEntry_opensArchived` (#740),
`InteractiveStreamE2ETest#interactiveTurn_twoHostsCollidingConversationId_stayPerHost` (#847),
`InteractiveStreamE2ETest#interactiveTurn_peerStartedTurn_continuesOnPhone` (#848),
`InteractiveStreamE2ETest#interactiveTurn_peerQueue_staysConsistentAcrossClients` (#849),
`InteractiveStreamE2ETest#interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` (#850),
`InteractiveStreamE2ETest#interactiveTurn_pingPrompt_statusSheetShowsRunningModel` (#891),
`InteractiveStreamE2ETest#interactiveTurn_pingPrompt_footerShowsContextUsage` (#946),
`InteractiveStreamE2ETest#interactiveTurn_modelChange_roundTripsAndStaysPerConversation`,
`InteractiveStreamE2ETest#interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`,
`InteractiveStreamE2ETest#interactiveTurn_chosenEffort_appliesFromTheFirstTurn`,
`InteractiveStreamE2ETest#interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` (#545),
`InteractiveStreamE2ETest#interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` (#687), and
`InteractiveStreamE2ETest#interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` (#950), so exactly
**seventeen real claude turns** are spent per run — five pings, from ping, create-workspace-folder,
new-session, the peer-started turn's own ping (#848), and the offline-read-reconcile scenario's own
ping (#850), plus #849's peer wait turn and its drained ping, #850's peer offline turn, #891's own
ping, #946's own ping, the inherited-effort and chosen-effort scenarios' own turns and the
remembered-effort-recall scenario's two turns (#545), the operator-bypass-permission scenario's two
turns — a tool-free ping, then an outside-workspace Read (#687) — and the permission-held
running-tool scenario's own turn (#950); the delete,
archive-restore, change-workspace, rename,
save-as-channel, list-archive-entry, two-host and model-change scenarios each add a method, not a turn
(create/rename/delete/archive/restore/change-workspace/promote are daemon round-trips;
list-archive-entry is pure navigation with no daemon round-trip at all; two-host separation is pairing,
navigation, rename and link cycling, also daemon round-trips).
The full class also includes the
\#481 tool-use test and #950's `@Ignore`d elapsed-reading twin, which
stay excluded from LIVE (extra turns, cost). `LIVE=1` is **mutually exclusive with `DETERMINISTIC=1`**
(real vs scripted claude); setting both fails fast.

**How it differs from default rung 3.** Same instrumented suite, same Gradle Managed Device, same
"pairing values as instrumentation arguments" seam — only the relay and the instance name change:

- The daemon dials `wss://pyrycode-relay.pyryco.de/v1/server` (`/v1/server` **baked in** — a base URL
  silently 404s into a dial-retry loop), while the phone dials the **base** `wss://pyrycode-relay.pyryco.de`
  and appends `/v1/client` itself. `OkHttpRelayTransport` rides TLS for `wss` with **no app-side change**.
  Both URLs derive from one `LIVE_RELAY_HOST` so an override cannot break the asymmetry.
- **No plaintext, no insecure flag on the live path:** every URL is `wss://` and
  `PYRY_ALLOW_INSECURE_RELAY` is **never** set — a dedicated daemon branch omits the flag the loopback
  branch carries.
- **Isolated instance, real HOME.** Direct `LIVE=1` script runs use `-pyry-name=e2e-live`;
  the gate overrides both daemon and pairing names with a unique `e2e-auto-…` name. Both run under the
  runner's **real `$HOME`** — unlike rung 4's isolated `/tmp` HOME. Real Claude needs the runner's
  `~/.claude` subscription auth, which an isolated HOME would strip, so isolation here is by **instance
  name**: identity + `devices.json` + `conversations.json` live under `~/.pyry/<test-instance>/`, so the
  production instances on this Mac and pyrybox (different names) are never read or written. That directory
  **persists after execution**; direct script runs reuse the `e2e-live` identity, and `cleanup()`
  never removes it.

Prerequisites (on top of the "How to run" list):

- The runner host's Claude authenticated (as default rung 3) — Max-subscription covered, so it
  does **not** meter tokens.
- The live daemon is started with `-pyry-workdir=$HOME`. The current daemon confines the supervised
  Claude workdir to the runner's home for trust handling, so a temporary checkout outside that boundary
  is rejected before the live suite can start.
- **No relay binary needed** (the daemon dials the production relay; the `RELAY_BIN` preflight is skipped).
- The emulator needs outbound internet + DNS + a system-trusted TLS cert for the relay host. It reaches
  the public relay over its own NAT'd internet — **not** the `10.0.2.2` host alias, which is loopback-only.

Cost: **seventeen real claude turns per run across twenty-one curated methods** (ping + create-workspace-folder,
\#566 + new-session, #541 + the peer-started turn, #848 + the peer's wait turn and its drained ping,
\#849 + the offline-read-reconcile scenario's own ping and its peer's offline turn, #850 + the
status-sheet-running-model scenario's own ping, #891 + the footer-context-usage scenario's own ping,
\#946 + the inherited-effort and chosen-effort scenarios' own turns and the remembered-effort-recall
scenario's two turns, #545 + the operator-bypass-permission scenario's two turns, #687 + the
permission-held running-tool scenario's own turn, #950; `/clear` spends
none; delete, #554,
archive-restore, #551, change-workspace, #562, rename, #537, save-as-channel, #581, list-archive-entry,
\#740, two-host separation, #847, and model change each spend none — create/rename/delete/archive/restore/
change-workspace/promote are daemon round-trips, list-archive-entry is pure navigation, and two-host
separation is pairing, navigation, rename and link cycling, also daemon round-trips), a few minutes of
wall clock, subscription-covered.

Environment checks for a new host (the recorded API 33 run passed these paths):

- **The live relay accepts the test daemon's `/v1/server` registration** — expected (a normal pyry daemon
  dialing the production relay, exactly as the operator's real instances do; the relay is content-blind +
  token-gated). If it allowlists server identities, that is a finding to surface — do **not** weaken the
  isolation to work around it.
- **Emulator internet + DNS + TLS** — the AVD must resolve `pyrycode-relay.pyryco.de` and complete a TLS
  handshake against a system-trusted CA. A proxy/firewall that blocks it is environmental, not a harness bug.
- **`~/.pyry/e2e-live/` is fully separate from production** — confirm once that `pyry pair` and the
  `-pyry-name=e2e-live` daemon touch only that directory and never the production instance's `devices.json`.
- **A down or stale relay is the signal, not a harness bug.** A red run (the phone's `awaitConnected()`
  timing out) is the exact failure class this mode exists to catch. An optional `curl`/`nc` reachability
  probe before starting the daemon would fail faster than the 30 s connect timeout; the gate already
  reports the down relay as a failed execution.

## Deterministic mode (rung 4)

`DETERMINISTIC=1` swaps real claude for the scripted `fakeclaude` backend (pyrycode #642), which runs
the daemon's current **stream-json** child protocol. The emulator app, Noise/relay path and production
stream parser stay real; **only claude is scripted**, so the run spawns no real claude and consumes
**zero claude turns**.

The harness sets `PYRY_FAKE_CLAUDE_STREAM_JSON=1` and, for each scenario, points
`PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST` at the first raw stream fragment. The fake writes those bytes
unchanged on the first user turn. It does not parse, normalize or append them to a Claude transcript.
Two-fragment scenarios also set `PYRY_FAKE_CLAUDE_STREAM_REPLAY_SECOND` and
`PYRY_FAKE_CLAUDE_STREAM_REPLAY_RELEASE`. The second fragment is held until the configured release
signal appears, then is written once. The signal is caused by the queued second message for held-open
spinner and tool scenarios, or by relay disconnect for reconnect and replay-order scenarios. This keeps
the observable window causal and avoids a host sleep or transcript-file polling.

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh
```

Extra prerequisites (on top of the rung-3 list, minus the claude-auth one — rung 4 needs no claude):

- Either `PYRYCODE_SRC` (a local pyrycode checkout) **+ `go`** to build `fakeclaude` from
  `internal/e2e/internal/fakeclaude`, **or** `FAKE_CLAUDE_BIN` pointing at a prebuilt binary.
- The `ping` fixture at `scripts/e2e-fixtures/ping.jsonl` (override with `FIXTURE_FILE`).

### What the host does (current deterministic seams)

1. **Isolated run state** — the harness builds or locates `fakeclaude`, pairs the test daemon and
   keeps its relay identity and logs in the run's temporary state. The app still uses the real managed
   emulator, Noise session and relay path.
2. **Stream child** — the daemon starts `fakeclaude` through its stream-json runner. The child reads
   user-turn envelopes from stdin and writes the configured raw fixture bytes to stdout. No PTY, Claude
   session transcript or transcript-directory seed participates in this path.
3. **Causal release** — a single-fragment scenario completes from `PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST`. A held-open
   scenario supplies `STREAM_REPLAY_SECOND` plus `STREAM_REPLAY_RELEASE`; the harness creates the
   release after the second queued message is observable, or after the relay disconnect fence for
   reconnect scenarios. The fake consumes the release once and remains available for control requests
   while waiting.
4. **Evidence** — each run records the selected runner, app mode, source revision, managed device,
   command and scenario, then requires instrumented XML with a non-zero executed count. These are
   execution diagnostics, not a claim that a baseline has passed.

The old PTY/transcript-drop design is historical context only. Earlier versions pre-seeded a Claude
session JSONL file and polled `daemon.log` before copying a fixture into it. The current deterministic
route does not use those files or polling fence; PTY is rejected by the current runner contract.

### The fixture format (extension point for #454/#436)

`scripts/e2e-fixtures/*.jsonl` are raw stream-json fragments, kept as real files so sibling scenarios
can be added. The fake writes the selected bytes exactly as stored, including intentionally incomplete,
unknown or malformed lines used by negative parser checks. A completing fragment ends with a top-level
`result` record whose `subtype` is `success`; the preceding `assistant` records carry the visible text
and `stop_reason: "end_turn"` where the turn closes. The `ping` fixture is:

```json
{"type":"assistant","message":{"id":"ping-1","stop_reason":"end_turn","content":[{"type":"text","text":"ping"}]}}
{"type":"result","subtype":"success","is_error":false,"session_id":"43143143-4314-4314-8314-431431431431"}
```

`#454` added the `stream` + `spinner` fixtures below; `#455` added the `tool-open` / `tool-done` /
`tool-failed` tool-step fixtures (same shape, with `tool_use` / `tool_result` content blocks); `#476`
(reconnect) and `#477` (replay-order) build their fixtures on this same shape — `replay-order.jsonl`
mirrors `stream.jsonl`'s three-delta shape (distinct `message.id`s, last with `stop_reason: "end_turn"`).

### Scenarios (#454)

`DETERMINISTIC=1` runs **one scenario per invocation**, selected by `SCENARIO` (default `ping`, which
preserves #431 unchanged). Each scenario maps to a single `@Test` method in
`DeterministicInteractiveStreamE2ETest` and its own fixture(s); the script runs exactly that one method
(`-Pandroid.testInstrumentationRunnerArguments.class=<class>#<method>`):

| `SCENARIO` | asserts | raw fragment(s) | fragments |
| --- | --- | --- | --- |
| `ping` (default) | a single-line reply renders | `ping.jsonl` | one |
| `stream` | a multi-`assistant_delta` reply assembles into **one** message | `stream.jsonl` | one |
| `spinner` | the thinking spinner shows mid-turn, then clears at turn end | `spinner-open.jsonl` + `spinner-end.jsonl` | **two** |
| `tool` (#455) | a tool step shows **running** in flight, then **done** after the result | `tool-open.jsonl` + `tool-done.jsonl` | **two** |
| `tool-failed` (#455) | a failing tool step renders **failed** | `tool-failed.jsonl` | one |
| `tool-progress` (#950) | the status area's running-tool label adds claude's elapsed reading after a `tool_progress` heartbeat, then clears once the call's `tool_result` lands while the turn stays busy | `tool-progress-open.jsonl` + `tool-progress-result.jsonl` | **two** |
| `reconnect` (#476) | an in-flight reply **survives a mid-turn link drop** and renders exactly once | `reconnect-open.jsonl` + `reconnect-done.jsonl` | **two** |
| `replay-order` (#477) | events produced **entirely while offline** replay **in order, each exactly once** | `replay-order-open.jsonl` + `replay-order.jsonl` | **two** (release on disconnect) |

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh                      # ping
DETERMINISTIC=1 SCENARIO=stream      PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # stream
DETERMINISTIC=1 SCENARIO=spinner     PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # spinner
DETERMINISTIC=1 SCENARIO=tool        PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool running→done
DETERMINISTIC=1 SCENARIO=tool-failed PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool failed
DETERMINISTIC=1 SCENARIO=tool-progress PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # running-tool label elapsed → gone
DETERMINISTIC=1 SCENARIO=reconnect    PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # reconnect continuity
DETERMINISTIC=1 SCENARIO=replay-order PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # post-reconnect replay ordering
```

**`stream`** — `stream.jsonl` is three `text` lines with **distinct** `message.id`s, so the producer
emits three `assistant_delta` envelopes (same `turn_id`, `seq` 0/1/2); the last line's
`stop_reason: end_turn` + non-empty text marks the final assistant delta; the following top-level
`result/subtype=success` record closes the stream. The phone's #337 fold concatenates the
three deltas (keyed by `turn_id`) into the single message `"Hello, streamed world"`. The test asserts
the cross-delta-boundary substring `"streamed world"`, present only if the deltas assembled into one
message (never on delta count or the streaming caret).

**`spinner`** — the thinking state is transient: a single fragment with `thinking` then `end_turn` would
flip `isThinking` true→false before Compose lays out the spinner. The harness therefore holds the turn
open and ends it on a **causal** release, with two raw stream fragments:

1. **First fragment** (`spinner-open.jsonl`, a `thinking`-only line) is emitted for the first user turn
   → `turn_state(thinking)`, held → the spinner stays on indefinitely (no `end_turn`).
2. The test asserts the spinner is shown, then sends a **2nd** message.
3. **Second fragment** (`spinner-end.jsonl`, a normal end-of-turn text line) is released by the queued
   second message → `turn_state(responding)` (spinner clears) → `turn_end` → `turn_state(idle)`.

Because the release follows the second enqueue, which happens only after the presence assertion passed,
the thinking window has no fixed host delay. The second message is only the release cause; it does not
provide a transcript file or a second canned reply.

**`tool`** — a tool step must render **running** in flight and **done** after the result. "Running" is
transient (the fold flips the row to done as soon as the correlated `tool_result` arrives), so it reuses
the spinner's **two-fragment causal release**:

1. **First fragment** (`tool-open.jsonl`, a lone `tool_use` line) is emitted for the first user turn →
   the fold opens a `Running` `Role.Tool` row keyed by the `tool_use` id, held open (no `end_turn`).
2. The test asserts the running content-description (`cd_tool_running`) is shown, then sends a **2nd**
   message.
3. **Second fragment** (`tool-done.jsonl`, the correlated success `tool_result` (`is_error: false`) plus
   a turn-ending text line) is released by the queued second message → the fold flips the row to `Done`
   and closes the turn.

`Done` has **no positive content-description** (the resolved icon's `contentDescription` is `null`), so
"done" is asserted **indirectly**: the running CD that was present is now absent, the failed CD never
appears, and the tool row is still on screen (the verbatim tool name `"Bash"`). That triad uniquely
identifies a running → done resolution and never keys on timing. **Correlation is load-bearing:** the
first fragment's `tool_use` `id` must equal the second fragment's `tool_result` `tool_use_id` (same
literal id in both files) or the fold drops the result and the row never resolves.

Since #950, the same held-open window also proves the status area's running-tool label (#897): drop A
carries no `tool_progress` heartbeat, so the label names the tool and carries no elapsed reading, and it
is gone once drop B closes the call. The `tool-progress` scenario below proves the label's elapsed form.

**`tool-progress`** (#950) — the running-tool label must add claude's elapsed reading once a
`tool_progress` heartbeat arrives, and clear once the call's `tool_result` lands. It reuses the `tool`
scenario's two-fragment causal release, but drop A carries a heartbeat and drop B carries **only** the
`tool_result`, no turn end:

1. **First fragment** (`tool-progress-open.jsonl`) is `tool`'s lone `tool_use` line (id `toolu_e2e`)
   followed by one `tool_progress` line in claude's captured shape — `heartbeat: true`, a **string**
   `parent_tool_use_id` equal to the open call's id, and `elapsed_time_seconds: 30`. A heartbeat missing
   either key is dropped by the daemon silently, with no trace to assert against. The test asserts the
   label reads the tool name and `"30s"`, then sends a **2nd** message.
2. **Second fragment** (`tool-progress-result.jsonl`) is the correlated success `tool_result` **alone** —
   no turn end. The turn stays busy, so the label's disappearance can only come from the call closing,
   never from the turn ending. A regression that cleared the label only at turn end would stay green
   against `tool-done.jsonl` (turn end included) and only reddens here.

**`tool-failed`** — a failing tool step must render **failed**. The failed end state is stable (it does
not auto-resolve), so it needs **no two-fragment release**: a single raw fragment (`tool-failed.jsonl`)
carries `tool_use` → an error `tool_result` (`is_error: true`) → a turn-ending text line, all at once. The
fold renders the row `Running` (briefly) → `Failed`; the test asserts only the terminal `cd_tool_failed`
content-description (tolerant, stable). The `tool_use` line must precede the `tool_result` line so they
correlate. Assertions never depend on the producer-derived `input_summary`/`result_summary` text — only
the status CDs and the verbatim tool name.

**`reconnect` (#476, Layer 2b)** — an in-flight reply must survive a mid-turn relay-link drop. It reuses
the spinner's **two-fragment causal release** with a sever/restore inserted in the gap:

1. **First fragment** (`reconnect-open.jsonl`, a `thinking`-only line) is emitted for the first user turn
   → `turn_state(thinking)`, held open. The turn is now streaming.
2. The test asserts the thinking spinner (proving the turn is open at the moment we sever), then
   **severs and restores the phone's relay link** (`severAndRestoreLink()`): `RelayConnectionSupervisor.close()`
   drops the socket and `connect()` re-dials. This is **phone-side only** — the daemon stays up, so its
   in-ring event buffer survives and the reconnecting phone re-advertises `last_event_id` (#416) rather
   than tripping the `resync`/gap path a daemon **restart** (#417) would. The helper awaits the
   coordinator's `currentRepository` going `null` (drop landed) then non-null (fresh Noise pump reached
   `Open`), so the drop+restore is proven, not assumed. The sever/restore injects no user turn. The relay
   disconnect is the release signal for the second fragment.
3. **Second fragment** (`reconnect-done.jsonl`, a complete reply + `end_turn`) is released by the relay
   disconnect → the held-open turn completes to the reconnected phone.

The first fragment carries **no** partial text by design: on the drop the connection-scoped repo tears down
and the thread projection clears, so the **whole** reply arrives post-reconnect in the second fragment. This makes "no missing
text" hold by construction — without depending on uncertain partial-turn replay semantics (events at or
below `last_event_id` are not re-delivered by the gap-free path). The closing assert is the one deliberate
count assertion in the suite: `assertCountEquals(1)` on the assembled reply text `"reconnected reply"` —
the load-bearing dedup invariant (`event_id` high-water + `message_id` upsert, #337/#385). A re-delivered
event must render the reply **exactly once**: no duplicate row, no missing text. That is the final
assembled text (stable), not a transient delta count, so it does not violate the "never on counts" rule,
which targets delta/timing counts. The drop/restore primitive `severAndRestoreLink()` is the reusable
seam #477 (ordering) builds on.

**`replay-order` (#477, Layer 2d)** — a *sequence* of events produced **entirely while the phone is
offline** must replay **in production order, each exactly once**, after reconnect. Where the continuity
case (#476) proves a single reply survives a drop, this proves an ordered backlog buffered during the
outage replays in order — and genuinely exercises the dedup fold on a **buffered-during-outage
re-delivery**, the path #476 did not reach (its reply arrived strictly *after* reconnect, so its
`event_id` was above the advertised cursor → delivered once, never deduplicated).

It forks the reconnect two-fragment release, but the sever and restore **straddle** the event production. The
atomic `severAndRestoreLink()` is split into its two halves — `severLink()` (close + await
`currentRepository == null`) and `restoreLink()` (connect + await `currentRepository != null`) — so the
test can hold an offline window between them:

1. **First fragment** (`replay-order-open.jsonl`, a `thinking`-only line) is emitted for the first user
   turn → `turn_state(thinking)`, held open. The test asserts the spinner (the turn is
   open at the moment we sever).
2. **`severLink()`** — the phone goes offline. The test then holds for a bounded `OFFLINE_WINDOW_MS`.
3. **Second fragment** (`replay-order.jsonl`, three ordered `assistant_delta` lines + `end_turn`) is
   released by the relay logging the phone-leg disconnect. A severed phone cannot send a second message,
   so the ordered sequence accrues in the daemon's in-ring buffer **entirely while the phone is offline**.
4. **`restoreLink()`** — the fresh Noise `hello` re-advertises `last_event_id` (#416); the buffered
   sequence (all `event_id` above the cursor) replays whole into the fresh, empty repo, which folds the
   deltas in arrival (= production) order.

The offline-window hold is **not** the forbidden fixed-delay-to-catch-a-transient: the buffered events
are **durable** (they replay whenever the phone returns), so erring long is free, and the order /
exactly-once asserts hold whether the deltas arrive as pure replay (window long enough) or a replay/live
mix (window short) — a too-short window only *under-exercises* "entirely offline", never false-greens
(a reordering still breaks the substring) nor false-reds. The window is a determinism quality knob.

The first fragment carries **no** partial text by design (same as #476), so the **whole** ordered sequence arrives
post-reconnect into the fresh repo and "no missing segment, in order" holds by construction. The closing
asserts reuse #476's one-deliberate-count exception: the **order** check matches the cross-delta-boundary
concatenation `"alpha bravo charlie"` (present only if the deltas assembled in production order — a
reordered replay breaks the substring), and `assertCountEquals(1)` on it is the dedup invariant on a
genuinely buffered-during-outage re-delivery.

The disconnect release fence is wired as the overridable `DISCONNECT_TOKEN` / `DISCONNECT_LOG`, defaulting
to `phone_unregistered` in `relay.log`, and baseline-counted so a stale connection-churn line cannot
false-fire it. The relay source emits this event when the phone leg is unregistered.

## Interactive runner selection

The current daemon contract uses **stream-json** as the interactive runner. The deterministic harness
uses that same production path and starts `fakeclaude` with `PYRY_FAKE_CLAUDE_STREAM_JSON=1`; its raw
fixture replay is configured through `PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST`, with the optional second
fragment and release signal described above. The run reports the resolved runner before the daemon
starts, together with the app mode, source revision, managed device, command and scenario.

PTY is not an active runner choice for this harness. The current daemon rejects a PTY selection during
preflight, because PTY transcript polling cannot prove the stream-json producer that the product runs.
The old `INTERACTIVE_RUNNER` and per-user config seeding details remain historical implementation context
only and must not be used to diagnose a current deterministic run.

## Verification status

**Current live baseline — 2026-09-20, 16:12:58–16:13:58 UTC (#528).**
The [committed sanitized XML](../scripts/fixtures/live-mobile-baseline/528-api33.xml)
and [runtime/revision context](../scripts/fixtures/live-mobile-baseline/528-api33-context.json)
record `python3 scripts/android-test-gate.py live` (default `DEVICE=pixel2Api33Atd`):
8 executed, 8 passed, 0 failures/errors/skips, process exit 0. All methods below
belong to `InteractiveStreamE2ETest` and each executed once in that run.

| Scenario | Method | API 33 outcome, 2026-09-20 |
| --- | --- | --- |
| Ping | `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` | PASS |
| Create workspace folder | `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` | PASS |
| New session | `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` | PASS |
| Delete | `interactiveTurn_deleteConversation_removesFromListAndClosesThread` | PASS |
| Archive/restore | `interactiveTurn_archiveRestore_roundTripsListMembership` | PASS |
| Change workspace | `interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` | PASS |
| Rename | `interactiveTurn_renameConversation_relabelsTopBarAndListRow` | PASS |
| Save as channel | `interactiveTurn_saveAsChannel_promotesToChannelTier` | PASS |

This result proves **managed `pixel2Api33Atd`, configured Pixel 2, Android 13 / API 33,
AOSP ATD arm64-v8a**, image revision 1. ADB captured the running device at 16:13:12 UTC:
model `Android ATD built for arm64`, build `TE1A.220922.034`; the context retains the
full image fingerprint and installed SDK package revision. It used app
`4390bc739d5d70cb43d8ab2331613c642f74f61a`, isolated daemon
`dccd18286b8f80d113c055322e22df3125bd1735`, Claude Code **2.1.259**, and the resolved
**stream-json** runner against the live relay. App/daemon revisions and the Claude
binary checksum were checked before and after execution and did not change.
The context identifies the maintainer recovery through the dispatcher credential
environment as the producer and includes the sanitized XML checksum. Production
daemon configuration was unchanged; `UnrecognizedRowSentinel` remained active.

API 33 is the sole required version for the baseline and routine ticket gates for
now; **API 35 is deferred**, with no run or evidence required. This capture proves
neither API 35 nor `DEVICE=connected`. Tool-use excluded from LIVE, the ignored
spinner and negative controls remain outside the baseline. No unresolved environment
or product blocker is recorded for this capture. Missing credentials or a missing or
unbootable API 33 device in a later run is an environment blocker, never eight product
failures or a pass. An observed product failure needs an owning-layer issue and
revalidation. The committed context's pending final gate describes its capture-time
handoff; this table does not claim a later execution.

Earlier results and failure history:

- **LIVE verified for #545 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/545` at `be34a72d22` merged with
  `origin/main` at `1406e83167` (0 commits behind before the merge), executed all nineteen curated
  scenarios — the curated list's first run with `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`,
  `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`,
  `interactiveTurn_chosenEffort_appliesFromTheFirstTurn` and
  `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` on it — with nineteen
  passes, no failures or skips, exit 0, wall clock 127.7s. `LIVE_MINIMUM` rose from 15 to 19 with this
  ticket (see [Pre-ship gate](#pre-ship-gate)); nineteen executed meets it exactly. This run resolved the
  plan's two open questions: `createChannel` accepts a created chat's own scratch `cwd` directly, with no
  `createWorkspaceFolder` fallback needed, and the first fresh settings reply after a real turn's reply
  already carries `effective_effort`. This is the first live evidence that the settings read (#590), the
  applied-effort footer (#889) and the remembered-effort recall (#686) round-trip against a real daemon;
  the recall scenario's fresh chat and channel carry an explicit saved model rather than the criterion's
  literal empty one, because a saved `""` model matches no published row and offers no effort levels to
  recall into — filed as [#972](https://github.com/pyrycode/pyrycode-mobile/issues/972).
- **LIVE verified for #946 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/946` at `a951201036` merged with
  `origin/main` at `01042d232b` (0 commits behind before the merge), the
  [recorded run](https://github.com/pyrycode/pyrycode-mobile/issues/946#issuecomment-5805808838)
  executed all fifteen curated scenarios — the curated list's first run with
  `interactiveTurn_pingPrompt_footerShowsContextUsage` on it — with fifteen passes, no failures or
  skips, exit 0, wall clock 103.5s. `LIVE_MINIMUM` rose from 14 to 15 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); fifteen executed meets it exactly. This is the first live evidence
  that the composer footer's `Cxt:` segment renders the percentage Claude reported, sourced from the
  daemon's post-turn `context_usage` push alone — this PR's Rework 1 removed the phone's
  on-subscription `request_context_usage` ask, which had deadlocked the scripted `reconnect` scenario
  on the prior verifier pass.
- **LIVE verified for #891 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/891` at `21fc690f4b` merged with
  `origin/main` at `3c771c7440` (6 commits behind before the merge), the
  [recorded run](https://github.com/pyrycode/pyrycode-mobile/issues/891#issuecomment-5803305486)
  executed all fourteen curated scenarios — the curated list's first run with
  `interactiveTurn_pingPrompt_statusSheetShowsRunningModel` on it — with fourteen passes, no failures or
  skips, exit 0, wall clock 101.7s. `LIVE_MINIMUM` rose from 13 to 14 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); fourteen executed meets it exactly. This is the first live evidence
  that the Status sheet's running-model row renders a non-empty, non-placeholder model string sourced
  from #890's `model_announced` reading after a real turn.
- **LIVE verified for #861 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/861` at `24c9490d9a` merged with
  `origin/main` at `148b9f7225` (2 commits behind before the merge), executed all thirteen curated
  scenarios with thirteen passes, no failures or skips, exit 0, wall clock 100.3s. This is the first
  live run of `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` with step 6's reopen dropped:
  the still-open thread's reconnect history re-ask, now keyed on the host's repository becoming
  available instead of the socket, delivered both the peer's reply and `OFFLINE_PROMPT` without leaving
  the thread.
- **LIVE verified for #850 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/850` at `c9b0fdc084` merged with `main`
  at `923b176bef`, executed all thirteen curated scenarios — the curated list's first run with
  `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` on it — with thirteen passes, no failures
  or skips, exit 0, wall clock 96.3s. `LIVE_MINIMUM` rose from 12 to 13 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); thirteen executed meets it exactly. Two earlier attempts on this
  branch failed first: the phone-side cut raced the peer's `turn_end` rather than the phone's own
  settled reply, so a still-streaming row could be dropped before the offline reads ran; once fixed on
  the phone's own cache, a second run failed because the post-reconnect assertion ran before the
  reopened thread's history page had arrived — the still-open thread's own reconnect re-ask fired before
  its repository was back and died outright, filed as
  [#861](https://github.com/pyrycode/pyrycode-mobile/issues/861) rather than fixed here. The scenario
  reopened the thread after reconnect as the work-around at the time; #861 fixed the re-ask and the
  scenario's step 6 no longer reopens (see the #861 entry above). The plan's Revisions record both
  original findings.
- **LIVE verified for #849 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/849` at `9788df3e67` merged with `main`
  at `e1e96d7139`, the
  [recorded run](https://github.com/pyrycode/pyrycode-mobile/issues/849#issuecomment-5788459590)
  executed all twelve curated scenarios — the curated list's first run with
  `interactiveTurn_peerQueue_staysConsistentAcrossClients` on it — with twelve passes, no failures or
  skips, exit 0, wall clock 90.6s. `LIVE_MINIMUM` rose from 11 to 12 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); twelve executed meets it exactly. Two earlier attempts on this
  branch failed first: a bare `sleep 90` cannot hold a peer's turn open (Claude Code's Bash tool
  refuses a foreground `sleep` of 25 s or more), and, once that was fixed, an unanswered permission
  prompt on the peer's replacement command held the turn open **forever** with no device able to
  answer it, until the harness paired the peer alone with `--allow-remote-permissions`. This run is
  also the first live evidence that `dequeue_message` gets no reply from the daemon: a phone-side drop
  clears the queued row (proven here) but leaves the dropped message's echo in the thread as a
  delivered bubble (filed as [#859](https://github.com/pyrycode/pyrycode-mobile/issues/859), out of
  this ticket's scope), which is why the scenario's phone-drop assertions are narrowed to the queued
  row until that lands.
- **LIVE verified for #848 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/848` merged with `main`,
  the [recorded run](https://github.com/pyrycode/pyrycode-mobile/issues/848#issuecomment-5787618646)
  executed all eleven curated scenarios — the curated list's first run with
  `interactiveTurn_peerStartedTurn_continuesOnPhone` on it — with eleven passes, no failures or
  skips, exit 0, wall clock 83.4s. `LIVE_MINIMUM` rose from 8 to 11 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); eleven executed meets it exactly. This run is also the first
  live evidence that a turn started from a second paired device — the `SecondClientPeer` standing
  in for the desktop — renders on the phone while the thread is open and does not duplicate on
  reopen.
- **LIVE verified for #847 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/847` merged with `main`,
  the [recorded run](https://github.com/pyrycode/pyrycode-mobile/issues/847#issuecomment-5787319723)
  executed all ten curated scenarios — the curated list's first run with
  `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` on it — with ten passes, no
  failures or skips, exit 0, wall clock 76.7s. The gate's floor stayed at 8 (see
  [Pre-ship gate](#pre-ship-gate)); ten executed clears it. This run is also the first live
  evidence that the daemon serves a thread and a rename for the unbound seeded conversation
  the harness writes (no `current_session_id`), resolving the plan's one open question
  without needing a bound session id.
- **LIVE verified for #740 (2026-09-23):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/740` merged with `main`,
  the [recorded run](https://github.com/pyrycode/pyrycode-mobile/issues/740#issuecomment-5786884038)
  executed all nine curated scenarios — the curated list's first run with
  `interactiveTurn_listArchiveEntry_opensArchived` on it — with nine passes, no
  failures or skips, exit 0, wall clock 62.5s. The gate's floor stayed at 8 (see
  [Pre-ship gate](#pre-ship-gate)); nine executed clears it. This run is the first
  live evidence that the scenario's [known host-selection race](#what-rung-3-is-made-of)
  does not fire in the curated ordering; it remains unverified in isolation.
- **Verified on 2026-09-20:** all 238 non-E2E UI tests passed on the managed Android 13
  device after repairing six stale selectors. All seven stream-json scripted scenarios passed
  against daemon revision `dccd182` and relay revision `62f363f`. Unit tests, lint, formatting,
  app build and instrumented compilation passed. The strengthened delete-dialog suite then
  passed all ten tests.
- **LIVE verified for #694:** the [recorded current-head run at `23218b5`](https://github.com/pyrycode/pyrycode-mobile/issues/694#issuecomment-5750540960)
  executed all eight curated scenarios with eight passes, no failures or skips, and
  process exit 0. The verifier also recorded 241 passing routine UI tests, including
  both assertion regressions, at the preceding test revision.
  The wrapper checks Claude login before starting a live suite. Automatic runs use
  the dispatcher's existing 1Password credential; missing credentials produce no passing XML.
- **Default and explicit workspaces verified for #588 (2026-09-20):**
  `InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread`
  (New discussion tap, factory `DEFAULT_SCRATCH_CWD`, no picker) and
  `InteractiveStreamE2ETest.interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`
  (create/select an explicit folder) each executed once and rendered the assistant reply.
  The [committed XML](../scripts/fixtures/default-workspace-live/588.xml) and
  [revision/checksum context](../scripts/fixtures/default-workspace-live/588-context.json)
  record `python3 scripts/android-test-gate.py live` at `2026-09-20T15:12:16.872Z`:
  app `1569fe03d8ac5cdb6b13995175fcb9c6ba5d4d2a`, isolated stream-json daemon
  `dccd18286b8f80d113c055322e22df3125bd1735`, Pixel 2 / API 33 / AOSP ATD
  (`pixel2Api33Atd`), exit 0, eight passed, zero failures/errors/skips.
  The [post-artifact gate at `be20dad578`](https://github.com/pyrycode/pyrycode-mobile/issues/588#issuecomment-5750756209)
  passed all eight again (exit 0, no skips), completing live acceptance.
  No workspace product patch was needed.
- **Earlier #588 failure:** the [12:43 UTC capture](https://github.com/pyrycode/pyrycode-mobile/issues/588#issuecomment-5749901060)
  had five passes and three reply-wait failures, including both workspace cases.
  Daemon histories showed completed replies, but did not prove phone rendering;
  unlike the earlier expired-OAuth baseline, this was not an authentication failure.
  Substring-count growth was an unreliable reply oracle because queued prompt text
  could disappear as the reply arrived. [#694](https://github.com/pyrycode/pyrycode-mobile/issues/694)
  repaired that assertion before revalidation. The failed capture remains in Git
  history; [the plan's revisions](specs/architecture/588-default-workspace-live-recheck.md#revisions)
  retain the triage and its evidence limits.
- **Dispatcher-run:** before verifier, `python3 scripts/android-test-gate.py ui` runs the non-E2E
  device tests, skipped when the branch touches only docs, scripts and the e2e-only sources
  (see [development verification](knowledge/features/development-verification.md)), followed by one `scripted` invocation for each of `ping`, `stream`, `spinner`,
  `tool`, `tool-failed`, `reconnect` and `replay-order`. Tagged tickets run `live` after verifier.
  Reports must be fresh and count executed tests. The live floor is eight. Leave the baseline
  command unset because the shared retry filter currently accepts Go test names.
- **Negative control:** `InteractiveStreamE2ETest.negativeControl_wordClaudeNeverSays_isNeverDisplayed`
  is `@Ignore`d. Un-ignore it once to confirm the positive assertion can fail (it waits for a word
  claude is never asked to say, so it must time out). Re-ignore after, so it does not burn a turn. The
  tool-use test has its own `@Ignore`d twin, `negativeControl_toolClaudeNeverUses_isNeverDisplayed`
  (#481): same tool prompt, but it waits for a tool name claude is never asked to use (`"Edit"` — the
  prompt asks only for a read-only shell command), so it must time out, proving the `"Bash"` matcher is
  selective. Rung 4 needs no negative control: the scripted backend makes the positive assertion
  deterministic.

## Assumptions to confirm on first live run

The deterministic stream-json contract is defined by the current fakeclaude source and the raw fixtures.
The remaining checks here are specific to a real relay or real Claude execution:

- **Pairing and daemon readiness.** The harness starts the isolated daemon before minting the pairing
  payload because current `pyry pair` requires the service to be running. Pairing and the daemon must use
  the same isolated name and identity.
- **Live workdir.** A live run must pass `-pyry-workdir=$HOME` so the daemon's supervised Claude workdir
  satisfies its home-bound trust check. Keep this explicit in diagnostics when a live run uses a temporary
  checkout or another non-home path.
- **Disconnect release.** Replay-order watches `relay.log` for the confirmed `phone_unregistered` event,
  baseline-counted so an older connection event cannot release the second fragment. Override
  `DISCONNECT_TOKEN` or `DISCONNECT_LOG` only when the selected relay build uses a different recorded event.
- **Real Claude tool path.** The live tool scenario still depends on Claude producing a `Bash` tool-use
  envelope and a correlated result. A permission modal or an inline answer is a live-environment finding;
  keep the assertion selective and record the runner and app mode before changing the prompt.
- **Thinking spinner.** The real-Claude spinner remains `@Ignore`-gated and manual because it has no durable
  artifact and the live path cannot hold the turn open. Keep transient spinner attempts separate from the
  automated scripted suite.

## Follow-ups to ticket

- **Coverage — shipped:** [#673](https://github.com/pyrycode/pyrycode-mobile/issues/673) (closed, split)
  used to own reconnect, phone-reply continuity, and history paging
  (scroll-back and reconnect-continuity) in the rung-3 `InteractiveStreamE2ETest`
  harness. All of that is now shipped across its split children: two-host navigation as
  [#847](https://github.com/pyrycode/pyrycode-mobile/issues/847), the tenth curated `LIVE=1`
  method — two paired hosts whose conversations share an id stay separate through pairing,
  rename, link-cycling and a restart; phone-reply continuity, queueing and drops as
  [#849](https://github.com/pyrycode/pyrycode-mobile/issues/849), the twelfth curated
  `LIVE=1` method, riding #848's `SecondClientPeer`; and offline reading with reconnect
  reconciliation as [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850), the
  thirteenth curated `LIVE=1` method, riding the same peer. #850 also found that an open
  thread's own reconnect history re-ask dies outright rather than merely lagging, filed and fixed as
  [#861](https://github.com/pyrycode/pyrycode-mobile/issues/861): the re-ask is now keyed on the host's
  repository becoming available instead of the socket reconnecting, and the same scenario's step 6
  dropped its reopen work-around to prove the still-open-thread shape live.
  [#778](https://github.com/pyrycode/pyrycode-mobile/issues/778) shipped
  the history-page retry and the reconnect/refused-cursor walk restart with
  deterministic coverage only (`ThreadHistoryDemandTest`, `ThreadViewModelTest`,
  `ThreadScreenHistoryTest`) and carried no `needs-real-claude`; #850 is that behaviour's
  live proof. The production-route Compose tests and
  two-peer DI tests for #636 establish deterministic ownership boundaries.

- **Coverage — pending:** [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)
  owns the cross-device Stop scenario in `InteractiveStreamE2ETest`: with real turns
  in A and B and another device most recently using A, Stop while the phone views B
  must end B while A keeps running. #626's ViewModel/coordinator/repository target
  assertions and `ScriptedThreadRenderTest` affordance coverage are deterministic
  proof only; neither they nor the existing curated live gate establish this outcome.
  The dispatcher owns live execution after verification.

- **Rung 4 (shipped, #431; extended #454, #455):** deterministic host backend via #642's scripted
  `fakeclaude` — see [Deterministic mode (rung 4)](#deterministic-mode-rung-4). #454 added the
  multi-delta `stream` render + the `spinner` scenario, and #455 added the `tool` / `tool-failed`
  tool-step scenarios (see [Scenarios](#scenarios-454)); #476 (reconnect continuity) and #477
  (reconnect ordering) — the #436 split — extend the same fixture format.
- **Rung 2 (Layer 1a shipped, #432):** the cheap Compose render harness — see
  [Layer 1 — component render harness (rung 2)](#layer-1--component-render-harness-rung-2). Layer 1b
  (#435, rides the same harness) adds tool rows, the session divider, and the connection banner.
- **Coverage:** #694 strengthens `InteractiveStreamE2ETest` scenarios
  `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`,
  `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` and
  `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`: reply display is
  independent of queued-message counts, and the new-session delimiter is revealed
  after wrap-up. `PingReplyTest` and `SessionBoundaryVisibilityTest` provide routine
  Compose regressions for these shared assertions. The eight LIVE selections and
  workspace/session postconditions remain; #588's default/explicit-workspace
  revalidation passed with [committed evidence](#verification-status).
- **Coverage:** thinking indicator (hardest, screen-sourced) — **shipped (#454)**, alongside the
  multi-delta `stream`-render scenario; tool-use event assertion (running → done, and failed) —
  **shipped (#455, Layer 2c)**; reconnect continuity (reply survives a mid-turn drop) —
  **shipped (#476, Layer 2b)**; reconnect **ordering** (events buffered while offline replay in order) —
  **shipped (#477, Layer 2d)**; Layer-3 (real claude) tool-use renders — **shipped (#481)** (the
  tool-use path is now covered at all three layers: component #472, rung-4 deterministic #455, rung-3
  real-claude #481); Layer-3 (real claude) thinking spinner — **shipped (#482), `@Ignore`-gated manual**
  (the spinner path is now covered at all three layers: component #432, rung-4 deterministic #454, rung-3
  real-claude #482 — the last gated behind a manual switch because the transient spinner has no durable
  artifact and rung 3 cannot hold the turn open); Layer-3 (real claude) create-workspace-folder —
  **shipped (#566)**, driven end to end through the #564 create wire and #565 recents wire and folded
  into the pre-ship `LIVE=1` gate as the 2nd curated method (see [Live mode](#live-mode-rung-3-live-relay));
  Layer-3 (real claude) new-session delimiter — **shipped (#541)**, driven end to end through the #540
  fire-and-forget wire and the #336 `session_transition` → `SessionBoundary` fold.
  `InteractiveStreamE2ETest.interactiveTurn_newSession_rendersSessionBoundaryDelimiter`
  selects “Reset session” with explicit targeting (#625); its pre-action absence and
  displayed-delimiter assertions remain the completion proof. The broader two-device,
  two-conversation proof belongs to #679. This scenario is always-on (durable
  delimiter artifact, unlike the `@Ignore`d spinner) and folded into the pre-ship `LIVE=1` gate as the
  3rd curated method; Layer-3 (real claude) delete-conversation — **shipped (#554)**, driven end to end
  through the #532 delete wire against a real daemon, always-on (both post-conditions — gone from the list,
  thread popped back — are durable structural facts) and folded into the pre-ship `LIVE=1` gate as the 4th
  curated method (spending **no** extra claude turn — create/rename/delete are daemon round-trips), taking
  the gate from a trio to a quartet at three ping turns plus an optional reset wrap-up; Layer-3 (real claude) archive/restore round-trip —
  **shipped (#551)**, driven end to end through the #549 archive/unarchive wire, the #556 archive-from-thread
  and #557 restore surfacings against a real daemon, always-on (both durable post-conditions — the unique
  name gone from the active list after archive, then back after restore, a genuine two-direction inversion)
  and folded into the pre-ship `LIVE=1` gate as the 5th curated method (spending **no** extra claude turn —
  create/rename/archive/restore are daemon round-trips), taking the gate from a quartet to a quintet at
  three ping turns plus an optional reset wrap-up; Layer-3 (real claude) change-workspace — **shipped (#562)**, driven end to end through the
  #560 change_workspace wire and #561 surfacing against a real daemon, always-on (the recorded workspace is a
  durable fact — the `WorkspaceChip` re-labels to the new folder's basename, a genuine absence→presence
  inversion) and folded into the pre-ship `LIVE=1` gate as the 6th curated method (spending **no** extra
  claude turn — create-folder and change_workspace are conversation-scoped daemon round-trips), taking the
  gate from a quintet to a sextet at three ping turns plus an optional reset wrap-up; Layer-3 (real claude) rename-conversation —
  **shipped (#537)**, driven end to end through the #530 rename wire against a real daemon, always-on (the
  recorded name is a durable fact on two surfaces — the thread top bar in-thread and the conversation-list
  recents row, both genuine absence→presence inversions of the same runtime-unique title; rename is
  conversation-scoped with **no** session transition, so the assertion is the recorded name, never a session
  id) and folded into the pre-ship `LIVE=1` gate as the 7th curated method (spending **no** extra claude
  turn — create/rename are conversation-scoped daemon round-trips), taking the gate from a sextet to a septet
  at three ping turns plus an optional reset wrap-up; Layer-3 (real claude) save-as-channel (promote) — **shipped (#581)**, the **backfill** of
  the one operator-facing flow that shipped (#348) before the real-stack definition-of-done rule
  (pyrycode-mobile-agents#9) and had therefore been answering `unsupported` on the real wire unnoticed until
  pyrycode/pyrycode#949 registered the daemon handler (desktop parallel: pyrycode-desktop#430), driven end to
  end through the #348 promote wire against a real daemon, always-on (three durable post-conditions — the
  runtime-unique channel name on the thread top bar in-thread, the `WorkspaceChip` unmount as the `isPromoted`
  tier flip, and presence on a channel-tagged row ∧ absence from every chat-tagged row of the assembled
  conversation tree (the tier read moved onto that tree, off the Discussions drilldown, when #731 retired the
  drilldown); promote is
  conversation-scoped with **no** session transition, and its reply is a bare conversation object folded by a
  confirmed upsert, **not** a `conversation_updated` broadcast, so every assertion is on rendered UI) and folded
  into the pre-ship `LIVE=1` gate as the 8th curated method (spending **no** extra claude turn — create/promote
  are conversation-scoped daemon round-trips, promote being a pure registry op), taking the gate from a septet
  to an octet at three ping turns plus an optional reset wrap-up; Layer-3 (real claude) list-archive-entry —
  **shipped (#740)**, proving the list's own archive entry #737 put on the channel list's bar reaches the
  Archived screen on its own, independently of the Settings route `interactiveTurn_archiveRestore_roundTripsListMembership`
  already covers (previously proven only at the event boundary,
  `ChannelListScreenTest.archiveEntry_emitsArchiveTapped`), always-on (the bar is a durable fact of every
  draw of the list, so the scenario needs no host wait, no seeded conversation and no claude turn — the
  only curated method that spends nothing at all, not even a daemon round-trip) and folded into the
  pre-ship `LIVE=1` gate as the 9th curated method, taking the gate from an octet to a nonet at the same
  three ping turns plus an optional reset wrap-up (the live gate's floor intentionally stayed at 8, not 9 —
  see [Pre-ship gate](#pre-ship-gate)); Layer-3 (real claude) two-host separation — **shipped (#847)**,
  proving that two paired hosts whose conversations share an id — daemon-minted ids never collide on their
  own, so the harness seeds one — stay separate: `scripts/e2e-emulator.sh` starts a second isolated test
  daemon before either starts, and the phone pairs it through its own scanner → paste-code flow
  (`PairCodeScreen`), never `PasteCodeDialog` directly; always-on (row, thread and cache separation by
  `(serverId, conversationId)`, #731/#795–#798, are durable structural facts), re-checked after a rename
  of one host's conversation, after each host's relay link is cut and restored
  (`RelayConnectionRegistry.connectionFor(serverId)`'s per-host close/connect, the #476 pattern applied
  per host), and after the app's object graph is rebuilt over the same on-device state
  (`E2eTestApplication.rebuildGraph`, the restart an instrumented test can perform without killing its
  process — `ActivityScenario.recreate()` would retain view models still holding the disposed graph) —
  and folded into the pre-ship `LIVE=1` gate as the 10th curated method (spending **no** extra claude
  turn — pairing, navigation, rename and link cycling are all daemon round-trips), taking the gate from a
  nonet to ten curated methods at the same three ping turns plus an optional reset wrap-up. The live run
  that closed the ticket (`python3 scripts/android-test-gate.py live`, 2026-09-23) executed all ten with no
  failures or skips, which also resolved the plan's one open question: the daemon does serve a thread and
  a rename for the unbound seeded conversation, so `seed_collision_conversation` needed no session-id
  binding; Layer-3 (real claude) peer-started turn — **shipped (#848)**, proving that a turn started from
  another paired device — a `SecondClientPeer` standing in for the desktop, built from the app's own
  `OkHttpRelayTransport`, `NoiseSessionFactory` and `NoiseSessionPump` with a throwaway in-memory key and
  its own `pyry pair` token, never the phone's credentials — continues on the phone: always-on (whether the
  reply renders once while the thread is open, and whether the peer's message and the reply each render
  once after the phone leaves and reopens the thread, are durable post-conditions) and folded into the
  pre-ship `LIVE=1` gate as the 11th curated method, taking the gate from ten curated methods to eleven and
  the floor from a loose 8 to `LIVE_MINIMUM = 11`, deterministically tied to the curated list's own size
  (`test_live_floor_matches_the_curated_list`) so the two cannot silently drift apart — the first ticket to
  spend a fourth real-claude turn since the trio landed with #566/#541, taking the run from three ping
  turns to four (the peer's own ping) plus an optional reset wrap-up. The live run that closed the ticket
  (`python3 scripts/android-test-gate.py live`, 2026-09-23) executed all eleven with no failures or skips.
  The peer is shared test infrastructure: reconnect and history-paging (offline reading) shipped next
  as [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850) riding it (see below); Layer-3
  (real claude) peer-queue consistency — **shipped (#849)**, proving that phone replies, queued sends and drops
  stay consistent with the peer's own view: the peer opens a chat the phone renamed and starts a turn
  claude holds open behind a permission prompt only the peer can answer (a quick command, never a bare
  `sleep` — Claude Code's Bash tool refuses a foreground `sleep` of 25 s or more before permission
  checks even run), so the phone's ping queues behind it; the phone's queued row and the peer's
  `queue_state` snapshot agree, a phone-side drop and a peer-side drop each clear from the other
  device's view (narrowed on the phone-side "gone from the thread" half until
  [#859](https://github.com/pyrycode/pyrycode-mobile/issues/859), a product bug this ticket found and
  filed rather than fixed — the daemon never acks `dequeue_message`, so the phone's own echo of a
  drop stays in the thread until that lands), and once the peer allows the held prompt the wait turn
  ends, the queued ping drains, and the phone shows claude's reply exactly once with no leftover
  queued row; always-on (every post-condition — a queued row's presence, its clearing, and the
  displayed reply — is durable) and folded into the pre-ship `LIVE=1` gate as the 12th curated method,
  taking the gate from eleven curated methods to twelve, the floor from `LIVE_MINIMUM = 11` to `12`
  on the same `test_live_floor_matches_the_curated_list` mechanism, and the run's real-claude cost
  from four turns to six (the peer's wait turn and its drained ping, alongside the existing four
  pings). The live run that closed the ticket (`python3 scripts/android-test-gate.py live`,
  2026-09-23) executed all twelve with no failures or skips; two earlier attempts on the same branch
  failed first on the bare-`sleep` restriction, then on an unanswered permission prompt with no
  device privileged to approve it, before the harness paired the peer alone with
  `--allow-remote-permissions`; Layer-3 (real claude) offline-read-reconcile — **shipped (#850)**, the
  live proof #795–#798 deferred: a loaded conversation stays readable while the phone's own link to
  the host is cut (the open thread, the chat's list row, and the thread reopened from the on-disk
  cache all still show the loaded turn), and reconciles a peer's turn once the link is restored.
  Rides #848's `SecondClientPeer` a third time; the cut waits on the phone's own
  `ConversationCache` holding the settled reply, not merely the peer's `turn_end`, because the daemon
  fans that frame to each connection separately and a cut on the peer's copy alone could drop the
  phone's own still-streaming row. Always-on (both the offline reads and the post-reconnect
  exactly-once/order checks are durable post-conditions) and folded into the pre-ship `LIVE=1` gate as
  the 13th curated method, taking the gate from twelve curated methods to thirteen, the floor from
  `LIVE_MINIMUM = 12` to `13` on the same `test_live_floor_matches_the_curated_list` mechanism, and the
  run's real-claude cost from six turns to eight (the phone's own ping and the peer's offline turn,
  alongside the existing six). The live run that closed the ticket
  (`python3 scripts/android-test-gate.py live`, 2026-09-23) executed all thirteen with no failures or
  skips; two earlier attempts on the same branch failed first because the cut raced the peer's
  `turn_end` instead of the phone's own settled reply, then because the post-reconnect assertion ran
  before a reopened thread's history page had arrived — the still-open thread's own reconnect re-ask
  fired before its repository was back and died outright, a production bug this ticket found and filed
  rather than fixed, as [#861](https://github.com/pyrycode/pyrycode-mobile/issues/861); the scenario's
  work-around at the time (reopening the thread after reconnect) is recorded in the plan's Revisions.
  #861 later fixed the re-ask and the scenario's step 6 dropped the reopen — see the #861 entry in
  [Verification status](#verification-status); Layer-3 (real claude) status-sheet running model —
  **shipped (#891)**, proving that after one real turn the Status sheet's running-model row shows a
  non-empty model string, distinct from the selected model and sourced from #890's `model_announced`
  reading: the scenario sends the ping prompt, awaits the reply, opens the Status sheet from the footer's
  status icon (`cd_thread_status_expand`), and asserts the running-model row's text is present and is not
  the unavailable note (`status_sheet_running_model_unavailable`), with no model name hard-coded. Always-on
  (the announced-model text is a durable post-turn fact, not a transient spinner) and folded into the
  pre-ship `LIVE=1` gate as the 14th curated method, taking the gate from thirteen curated methods to
  fourteen, the floor from `LIVE_MINIMUM = 13` to `14` on the same `test_live_floor_matches_the_curated_list`
  mechanism, and the run's real-claude cost from eight turns to nine (the scenario's own ping, alongside
  the existing eight) — a deliberate choice over reusing the ping scenario's turn, so each scenario stays
  independently diagnosable (plan `docs/specs/architecture/891-status-sheet-running-model.md` §
  Revisions). The live run that closed the ticket
  (`python3 scripts/android-test-gate.py live`, 2026-09-23, branch `feature/891` at `21fc690f4b` merged
  with `origin/main` at `3c771c7440`) executed all fourteen with no failures or skips; Layer-3 (real
  claude) footer context usage — **shipped (#946)**, proving that after one real turn the composer
  footer's `Cxt:` segment shows the percentage Claude reported (`context_usage`, published after every
  completed turn): the scenario sends the ping prompt, awaits the reply, and waits for the
  `CONTEXT_USAGE_TEST_TAG` node's text to match `Cxt: \d+%`, with no percentage hard-coded. Always-on (a
  reported percentage is a durable post-turn fact, not a transient spinner) and folded into the pre-ship
  `LIVE=1` gate as the 15th curated method, taking the gate from fourteen curated methods to fifteen, the
  floor from `LIVE_MINIMUM = 14` to `15` on the same `test_live_floor_matches_the_curated_list`
  mechanism, and the run's real-claude cost from nine turns to ten (the scenario's own ping, alongside
  the existing nine) — the same one-ping-per-scenario choice #891 made, so each scenario stays
  independently diagnosable (plan `docs/specs/architecture/946-context-usage-footer.md` § Revisions,
  which also records that the phone stopped sending `request_context_usage` on subscription: after the
  verifier's first pass found that a mid-turn ask deadlocked the scripted `reconnect` scenario, Rework 1
  removed the ask outright, so the reading now comes from the daemon's post-turn push alone and this
  scenario needed no change for it). The live run that closed the ticket
  (`python3 scripts/android-test-gate.py live`, 2026-09-24, branch `feature/946` at `a951201036` merged
  with `origin/main` at `01042d232b`) executed all fifteen with no failures or skips; API-retry status
  (attempt N/M) — **rung 2 shipped (#594)**, the
  `ScriptedApiRetryTest` scenarios driving `api_retry` edges through the real #593 repository projection
  into `ThreadViewModel.apiRetry` and `ApiRetryIndicator`, covering both edges (the rising edge, including
  a climbed counter that must re-render rather than dedup, and the clearing edge reverting to whatever the
  turn state says) and both counter cases (a parsed `3/10`, and the counter-less fallback taken by the
  unparsed `0/0`, an incoherent `9/3`, and an absurd `1/2147483647`). Mobile currently has no curated rung-3 or rung-4 scenario for `api_retry`; the
stream-json producer can carry the event, so adding coverage requires a real-Claude scenario or a raw
replay fragment with the event shape. Keep that absence explicit rather than treating the old PTY-only
explanation as a current limitation. Compaction status ("Compacting conversation") —
  **rung 2 shipped (#597)**, the `ScriptedCompactingTest` scenarios driving `compacting` edges through the
  real #596 repository projection into `ThreadViewModel.isCompacting` and `CompactingIndicator`, covering
  both edges (the rising edge replacing the generic thinking label, and showing while the turn state is
  `idle` — the state is conversation-level, not turn-scoped; and the clearing edge reverting to whatever the
  turn state says, either the thinking affordance or nothing at all, so the status never sticks) plus the
  never-received case rendering exactly as today. On/off only — the wire payload is
  `{conversation_id, active}` with no counter, percent, or ETA, so there is no display-gate case of the
  API-retry kind to cover. Mobile currently has no curated rung-3 or rung-4 scenario for `compacting`; the
  stream-json producer can carry the event, and a future raw replay can exercise it. Keep this as an
  explicit coverage gap, distinct from the `@Ignore`d #482 spinner; thinking-token reading
  ("Thinking… ~N tokens this step", decorating the thinking arm rather than adding one) —
  **rung 2 shipped (#803)**, the `ScriptedThinkingProgressTest` scenarios driving `thinking_progress`
  edges through the real #801 repository projection into `ThreadViewModel.thinkingProgress` and
  `ThinkingIndicator`, covering a rising reading, a falling reading (a real restart, not clamped), an
  identical repeat (held, not rewritten), both mutual-exclusion cases (api-retry and compaction each
  still winning the slot over a live reading), and the never-received case rendering exactly as today.
  Mobile currently has no curated rung-3 or rung-4 scenario for `thinking_progress`: the stream-json
  producer can carry the frame, so adding coverage requires a real-Claude scenario or a raw replay
  fragment with the event shape — #679 already owns the rung-3 slot this ticket would otherwise need.
  Keep that absence explicit, the same posture as `api_retry` and `compacting`; parser-gap sentinel (an
  unrecognized-message row fails every live scenario) —
  **rung 3 shipped (#586)**, a single `UnrecognizedRowSentinel` rule field on `InteractiveStreamE2ETest`
  that fails **any** scenario during which an `unrecognized_message` row (#609) reached the thread, naming
  the frame's `site` (as its wire token, the string an operator greps the daemon for) and a sanitized,
  length-capped `message_type` — never the payload body, never a conversation id. Every curated `LIVE=1`
  method becomes a sentinel for free; the ninth, list-archive-entry (#740), inherited the guard with **no**
  per-test wiring when it landed. Red does **not** mean broken: it means claude gained a message kind and the daemon's measured
  ignore-list needs re-taking. Shape: the rule resets a process-global recorder before the body and checks
  it after, fed by an inert pass-through `TappingConversationRepository` installed in
  `E2eTestApplication`'s relay branch. Its `hostConversationModule` also supplies
  `decorateRepository = ::TappingConversationRepository` for exact-host destination
  facades; wrapping only the compatibility binding would miss host-owned thread reads.
  It taps the subscription the app **already** makes rather than
  opening its own, because `observeMessages` issues a full-history `backfill_since` on *every*
  subscription, so a guard with one collector per conversation would put that traffic on the live wire
  during a timing-sensitive real-claude turn (a sentinel that adds flakiness of its own is worse than no
  sentinel). The recorder **accumulates** across the scenario, which is what covers the five curated
  methods that deliberately finish outside the thread on a list surface (delete, archive-restore, rename,
  save-as-channel, and list-archive-entry, which never enters the thread at all): an end-of-run look at
  the thread alone would be vacuous for over half the nonet. A red body keeps its own
  cause — the finding is attached with `addSuppressed`, since the likeliest manifestation of a parser gap
  is the scenario's *own* assertion timing out because the reply never rendered. **The non-vacuity proof
  now covers rung 2 and rung 4** (`ScriptedUnrecognizedMessageTest`, above): the daemon emits
  `unrecognized_message` from `internal/streamsup/parser.go`, the **stream-json** runner. A raw replay
  fragment with an unknown `type` can therefore exercise this parser path on the deterministic harness;
  the active fixture must include that line when the sentinel is intended to prove it. **Live-path
  caveat, recorded rather than engineered around:** the live runner and real Claude remain subject to
  daemon configuration, so the evidence must identify the resolved runner before the daemon starts. No
  success claim belongs in this document until the run has XML evidence and a non-zero executed count.
- **#337 full scope:** `seq`-based ordering and replay de-dup across reconnect (a #402 concern; this
  fold concatenates in arrival order, correct within a single connection); and a `make`/Gradle wrapper
  for the orchestration plus fork-sync of any shared `bin/` script per the org convention.
- **Recover `pyrycode#642`** (the parked wire-level run) for the pipeline, or note it there.

## Constraints

- Runs as a local Gradle/script command, **not** a GitHub CI gate (the org does not gate on Actions).
- Use generous timeouts and selective text assertions; constrained ping replies use exact, case-insensitive message-list matching. Never infer reply arrival from substring-count growth or assert delta timing.
- Keep the test to the single structured path; do not assert the coarse `message` path. As of 2026-06-22 there is no old-app-version support: the operator controls both ends and ships the app and daemon together, so every phone gets the structured stream and the coarse path is dead code slated for removal. See the 2026-06-22 amendment in pyrycode ADR 025.
