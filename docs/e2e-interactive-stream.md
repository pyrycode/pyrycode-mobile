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
   Session errors (#1678) are rung-2 proof in `ScriptedSessionErrorTest`: both agents,
   known/unknown copy, isolation, clearing and Sending/Waiting closure through the
   real repository → ViewModel → screen. No daemon failure or live recovery is proved.
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
   with explicit conversation targeting (#625), through the screen-owned header Actions overlay since #1666. The **delete-conversation** scenario (#554) renames a
   discussion to a runtime-unique name, confirms it is present on the channel list, then deletes it from the
   thread (overflow → "Channel info" → "Delete" → the "Delete conversation?" dialog → confirm) and asserts
   it is gone from the list and the thread has popped back (exercises the #532 delete wire against a real
   daemon; no claude turn — delete is a daemon round-trip); and an **archive/restore** scenario (#551):
   rename a discussion to a runtime-unique name, confirm it is present on the channel list, archive it from
   the thread (overflow → "Archive", immediate — no confirm) and assert it is gone from the list, then
   restore it (list toolbar → "Open archive" → the Archived screen's restore affordance) and assert it
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
   (#740): arrive on the channel list, assert the Archived screen's title is absent, open the list's header menu
   (“Open menu”) and select Archive (#1665), then assert the
   title appears — proving the entry #737 shipped reaches Archived on its own. The full round trip
   `interactiveTurn_archiveRestore_roundTripsListMembership` now uses that entry too; no claude turn,
   no seeded conversation — the bar is drawn on every state of the list; and a
   **two-hosts-colliding-conversation-id** scenario (#847): seed one conversation under a shared id but a
   different name on two isolated test daemons, pair the second host through the app's own scanner →
   paste-code flow, and assert each host's row, thread and cache stay separated by `(serverId,
   conversationId)` — re-checked after a rename of one host's conversation, after each host's relay link
   is cut and restored, and after the app's object graph is rebuilt over the same on-device state; no
   claude turn — pairing, navigation, rename and link cycling are daemon round-trips; and a
   **peer-started-turn** scenario (#848): a `SecondClientPeer` — a second paired device standing in for
   the desktop, built from the app's own transport, Noise session and wire codec classes with a
   process-scoped in-memory identity keyed by exact host id and its own `pyry pair` token — sends the
   ping prompt into a chat the phone has renamed
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
   peer's offline turn. The **Offline Retry** scenario (#1286 —
   `InteractiveStreamE2ETest.interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`)
   instead fails the harness-owned daemon, observes the actual Offline pill in the open thread,
   restores the same host and taps Retry before passive reconnect can run, then renders a new
   real-Claude reply. One Claude turn; see [Offline Retry proof](#offline-retry-proof).
   Semi-deterministic. A **`LIVE=1` variant (#527, extended #566 / #541 / #554 / #551 / #562 / #537 / #581 / #740 / #847 / #848 / #849 / #850 / #891 / #946 / #545 / #950 / #965 / #981 / #966 / #967 / #955 / #1016 / #1020 / #1050 / #1021 / #1017)**
   historically ran a **curated set of thirty-seven scenarios** (ping + create-workspace-folder + new-session + delete +
   archive-restore + change-workspace + rename + save-as-channel + list-archive-entry + two-host +
   peer-started-turn + peer-queue-consistency + offline-read-reconcile + status-sheet-running-model +
   footer-context-usage + model-change + inherited-effort + chosen-effort + remembered-effort-recall +
   permission-held-running-tool + stop-running-turn + operator-bypass-permission + permission-answer +
   question-answer + reconnect-footer + reconnect-commands + background-task + background-push-turn-end +
   background-push-prompt + attachments-from-phone + claude-offered-file + peer-attachment + markdown-link +
   mute-channel + interrupted-upload + interrupted-retrieval + cross-host-attachment-recovery, thirty-nine
   real claude turns — five pings (ping, create-workspace-folder, new-session, the peer-started turn's own
   ping, and the offline-read-reconcile scenario's own ping), the peer-queue-consistency scenario's wait
   turn and its drained ping, the offline-read-reconcile scenario's peer offline turn, the
   status-sheet-running-model scenario's own ping, the footer-context-usage scenario's own ping, the
   inherited-effort scenario's own turn, the chosen-effort scenario's own turn, the
   remembered-effort-recall scenario's two turns (one in its fresh chat, one in its fresh channel), the
   permission-held-running-tool scenario's own turn, the stop-running-turn scenario's two turns (the
   held-then-interrupted turn and its follow-up ping), the operator-bypass-permission scenario's two
   turns (the tool-free ping and the outside-workspace Read), the permission-answer scenario's three turns
   (the allowed command, its don't-ask-again repeat, and the peer-allowed prompt in the second
   conversation), and the question-answer scenario's two turns (the phone's answer and the peer's answer),
   the reconnect-footer scenario's two turns (the ping before the cut-and-restore and the ping after it),
   the reconnect-commands scenario's two turns (the ping and the compaction), the background-task
   scenario's one turn (the prompt that starts the task), the background-push-turn-end scenario's own
   turn (the command the peer allows once the phone is absent, ending while it stays away) and the
   background-push-prompt scenario's own turn (the peer's held command, whose prompt surfaces while the
   phone is absent), the attachments-from-phone scenario's own turn
   (the phone's attached message), the claude-offered-file scenario's own turn (the phone's message
   that runs `printf` and calls `send_file`), the peer-attachment scenario's own turn (the peer's
   message naming the file, read back after a history reload), the markdown-link scenario's two turns
   (claude writes a note and replies with a link to it, then rewrites the note), the interrupted-upload
   scenario's own turn (the retried message, once the cut link is restored), the interrupted-retrieval
   scenario's own turn (the peer's message naming the file whose retrieval is cut and retried), and the
   cross-host-attachment-recovery scenario's own turn (the phone's message on host A of #847's colliding
   conversation id), plus a
   possible reset wrap-up turn — delete, archive-restore,
   change-workspace, rename, save-as-channel, list-archive-entry, two-host, model-change and
   mute-channel spend none)
   against the **production relay** over `wss://`
   (TLS) — the pre-ship gate that catches the live-environment failure class a local relay cannot; see
   [Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay). The **operator-bypass-permission**
   scenario (#687) rides this same curated list; [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981)
   fixed the production bug its own diagnosis named — the thread screen, not the repository fold, was
   losing a non-streaming reply under `reverseLayout` once the list overflowed (see its paragraph below).
   The **permission-answer** and **question-answer** scenarios (#966) ride a fourth, dedicated daemon and
   are covered in their own paragraph below, after the operator-bypass-permission paragraph. The
   **reconnect-footer**, **reconnect-commands** and **background-task** scenarios (#967) are covered in
   their own paragraph below, after the permission-answer / question-answer paragraph. The
   **mute-channel** scenario (#1021) is covered in its own paragraph below, after the background-task
   paragraph. The **background-push-turn-end** and **background-push-prompt** scenarios (#955) are
   covered in their own paragraph below, after the mute-channel paragraph. The **interrupted-upload**,
   **interrupted-retrieval** and **cross-host-attachment-recovery** scenarios (#1017) are covered in
   their own paragraph below, after the attachments-from-phone / claude-offered-file / peer-attachment
   paragraph.
   The ping, create-workspace-folder and
   new-session scenarios in `InteractiveStreamE2ETest` require a displayed exact
   ping reply in the message list (#694), independently of disappearing queued text.
   New-session also reveals the delimiter after a potentially tall wrap-up reply, and (#965) now waits for
   the daemon's wrapping-up phase to show live, with no delimiter yet, before the phase clears and the
   delimiter appears; the restarting phase has no live hold and stays proven only by
   `ScriptedResettingTest`. The **stop-running-turn** scenario (#965 —
   `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, method name kept though
   [#1357](knowledge/features/turn-outcome-indicator.md) dropped its Interrupted-label assertion) holds a
   turn open on a command that never returns on its own, taps the composer's Stop, and asserts the stopped
   turn's `cancelled` `stop_reason` and a real reply to a same-thread follow-up — covered in its own
   paragraph below, after the reset scenario. The **model
   and effort settings round trip** (#545 — `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`,
   `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`,
   `interactiveTurn_chosenEffort_appliesFromTheFirstTurn`,
   `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`) is covered in its own
   paragraph below, after the peer scenarios. [#1193](https://github.com/pyrycode/pyrycode-mobile/issues/1193)
   extends the model-change method to select in Run configuration and verify selected radios after
   fresh settings replies for both chats; it remains in `InteractiveStreamE2ETest`.
   [#1308](https://github.com/pyrycode/pyrycode-mobile/issues/1308) turns it into a **one real-claude turn**
   scenario: an inherited chat's post-reply mark is now asserted against the announced model, by the same
   value/`resolvedModel`/family tiers `ThreadRunConfig.selectedChoice` applies, and no radio may ever read
   "Default".
   The **remembered-model new-chat** scenario (#1223 —
   `interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage`) chooses a published model in
   one chat, creates another chat, verifies its model before the first send, receives a real Claude
   reply, and checks the original chat's saved choice stayed put. It runs in `InteractiveStreamE2ETest`.
   **Archive round trip and host isolation** are again live in `InteractiveStreamE2ETest` (#1249):
   `interactiveTurn_archiveRestore_roundTripsListMembership` restores a discussion through the selected
   host's list toolbar after proving its active and archived membership changes;
   `interactiveTurn_twoHostsArchive_staysPerHost` archives and restores A's chat through A's toolbar
   while B's active and archived sets remain unchanged. Both wait for restore completion before Back.
   **Channel create, edit and archive** is live again in `InteractiveStreamE2ETest` (#1251, extended
   #1342): `interactiveTurn_createEditArchiveChannel_readsPromptBack` creates from an empty Channels
   section, reads the original and edited prompt, waits for a distinct reply after Reset session,
   then empties the prompt from Edit channel and polls the host until its reading comes back `null`,
   opens Channel info and checks for an empty box at "0 / 8192 bytes", before restoring through the
   selected host's list-toolbar Archive entry and finding the edited name on the list.
   **Archive order** (#1332 — `interactiveTurn_archiveTwoChats_listsSecondArchivedFirst`): archives two
   freshly created chats on a live daemon, the newer-by-last-use one first and the older one second, then
   opens Archive and asserts the second-archived chat is on top — proving the daemon's `archived_at` stamp,
   not `lastUsedAt`, decides the order; the old order would put the first-archived (newer-by-last-use) chat
   on top instead. Zero claude turns. In the curated `LIVE=1` list in `scripts/e2e-emulator.sh`, which
   raised `LIVE_MINIMUM` by one.
   **Pending session-error recovery:** [#1731](https://github.com/pyrycode/pyrycode-mobile/issues/1731)
   owns `InteractiveStreamE2ETest.interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog`.
   The live harness currently lacks a reproducible daemon failure trigger; this method
   is not implemented or in the curated gate. Its deterministic twin is also pending.
   **Pending coverage:** #679 owns **cross-device** Stop in `InteractiveStreamE2ETest`:
   real turns in A and B, another device most recently using A, and phone Stop in B
   ending B while A continues. #965 proves only the **single-device** case — the phone stopping its own
   turn. The curated live gate does not cover cross-device Stop.
   An open thread recovering a peer's reconnect-window prompt on its own — the gap #850 found — is
   **shipped (#861)**: the still-open thread's reconnect history re-ask now waits for the repository to
   be published, so it reaches a live repository instead of a socket that is up but not yet handshaked.
4. **Emulator + deterministic host** ← **shipped (#431).** The same real app + Noise/relay path, but
   claude is swapped for #642's scripted `fakeclaude` backend replaying raw stream-json fixture bytes.
   No real claude, **zero claude turns**; re-running back-to-back uses the same stream contract. Run it with
   `DETERMINISTIC=1` — see [Deterministic mode (rung 4)](#deterministic-mode-rung-4).
   The `offline-retry` twin (#1286),
   `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_offlineRetryRestoresScriptedReply`,
   drives the same actual Offline state and pill tap, then renders a scripted reply in the seeded thread.
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

**Host system prompt (#1775).**
`InteractiveStreamE2ETest.interactiveTurn_hostSystemPrompt_editsResetsAndCancels` uses the real Edit
host controls to save custom text, confirm it with a fresh daemon read and reopened preview, reset then
Cancel without changing storage, and reset then OK so current equals the daemon-returned default.
Cleanup restores the harness host's original value; fresh reads and restoration are bounded by
`THREAD_TIMEOUT_MS`. The method is in the full curated live selector and spends no Claude turn.
Controller/component fakes cover deterministic transitions; no new
`DeterministicInteractiveStreamE2ETest` twin was added for this storage/editor flow. See
[Host editor](knowledge/features/host-editor.md#testing) for control coverage and the unmerged subtitle
lookup used by both component and live waits.

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
[Add workspace](knowledge/features/mobile-modal-callers.md#callers), not the sheet the name still describes — the
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

Rung 3 covers twenty-four scenarios on this one harness: the **ping** happy path (a constrained reply renders);
a **tool-use** scenario (#481 — a constrained prompt makes real claude run a shell tool, asserting the
tool step renders, keyed on the resolved row's accessible Done status); a
**thinking-spinner** scenario (#482 — a pure-reasoning prompt makes real claude think a beat, asserting
the spinner is displayed mid-turn, keyed tolerantly on the `cd_thread_thinking` content-description); a
**create-workspace-folder** scenario (#566, migrated in #1190 —
`interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`: create a chat through its host's
Chats plus and confirmation → open the thread's Change workspace picker → create and select a folder →
send the ping prompt there → reopen that picker and find the folder in Recent, proving the #564 create
wire, #565 recents wire and live folder use); a **new-session**
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
**immediate — no confirm**) and assert it is **gone** from the list, then restore it (list toolbar → "Open
archive" → the Archived screen's restore affordance) and assert it is **back** in the list — the round
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
`interactiveTurn_saveAsChannel_promotesToChannelTier`, driven through the Chats-section Create chat
confirmation and #957 `MobileModal` form: create a scratch discussion, drive the real thread overflow
"Save as channel…" → `SaveAsChannelDialog` to a
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
`interactiveTurn_listArchiveEntry_opensArchived`: use Open menu → Archive (#1665) and assert the
Archived screen appears, proving the entry #737 put on the list's bar reaches Archived independently of
the Settings route; **zero** claude turns, no daemon round-trip at all — the bar is drawn on every state
of the list); and a **two-hosts-colliding-conversation-id** scenario (#847 —
`interactiveTurn_twoHostsCollidingConversationId_stayPerHost`: seed one **promoted** conversation under a
shared id but a different name on two isolated test daemons, pair the second host through the app's own
scanner → paste-code flow, and assert each host's row, thread and cache stay separated by `(serverId,
conversationId)` — re-checked after a rename, after each host's relay link is cut and restored, and
after the app's object graph is rebuilt over the same on-device state; **zero** claude turns — pairing,
navigation, rename and link cycling are all daemon round-trips. The rename step renames a channel, so
since #1561 it drives the shared `renameOpenThread` helper's Edit-channel path — the menu's Edit opens
Edit channel rather than the rename dialog; see [Follow-ups to ticket](#follow-ups-to-ticket)); and a
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
ping turn, wait until the composer footer's `CONTEXT_USAGE_TEST_TAG` node's content description reports an available percentage (normal, warning or high), with
no percentage hard-coded — proving an available computed `ThreadRunConfig.contextPercent`
renders in the footer; **one** claude turn — the scenario's own ping.
This method does not itself prove an ask was sent, since #1411 the footer can show a percentage from
`SessionSettings` alone with no reading at all — see [Thread composer footer — context usage
segment](knowledge/features/thread-composer-footer-context-usage.md) for that gap and
[#1410](https://github.com/pyrycode/pyrycode-mobile/issues/1410)'s
`interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`, which waits on the reading
itself to close it; and a
**status-band-never-empty** scenario ([#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311) —
`interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy`: a prompt that makes real claude run a
read-only `echo` and then answer in one sentence, sampled continuously from the tap on Send until the turn
goes idle, asserting that every sample busy both before and after its reading checks (the Stop control
present on both reads, which closes the race at the turn's falling edge) shows a reading — the status
glyph, or a Reset-session, connection or "waiting for answers" reading — proving the band never goes dark
across thinking, a running tool, and the `responding` text that used to leave it empty; **one** claude
turn: the scenario's own tool-then-text reply). The tool-use
test asserts the **durable** terminal signal — the resolved row's accessible Done status —
not the transient running spinner: rung 3 has no scripted backend to hold the turn open, so racing the
spinner over a real relay turn is the "never on timing" failure the [Constraints](#constraints) forbid
(it is why rung 4's `tool` scenario needs a two-fragment release).

The **new-session** scenario (#541) is **always-on** (not `@Ignore`d): the delimiter is a **durable**
artifact that survives the turn — unlike #482's transient spinner — so it belongs in the always-on gate,
like #481's tool-name row. Its load-bearing matcher is the delimiter's reason-independent
`SESSION_BOUNDARY_TEST_TAG` (`"session-boundary"`, since #1578), which can **only** come from the rendered
`SessionBoundaryDelimiter`. “Reset session” selects the action; the tagged node proves the
resulting boundary independently of the menu label. The delimiter's **absence is asserted before** the
reset tap (a deterministic guard, no extra claude turn), so its later appearance is attributable to
the action. `new_session` is **fire-and-forget** (pyrycode#831, #540), so nothing waits on or asserts an
ack — the observable is the displayed post-broadcast delimiter. The test scrolls to the newest
row while waiting, since a tall wrap-up can keep it off-screen (#694). **Since #965**, the method also
waits for the status area to show the wrapping-up phase (`thread_resetting_wrapping_up`) before the
delimiter appears, with the tagged node still absent at that point, then waits for every
resetting label to clear before the existing delimiter wait runs. This is **causally held, not raced on
timing**: the daemon's `resetThenRotate` raises `wrappingUp` before it runs a real claude wrap-up turn
(`conversationReset.wrapUp`, up to 400 words) and lowers it only after that turn ends — a full claude
round trip, whether or not handoff notes are stored, correcting an earlier reading in this scenario's own
KDoc that the turn ran only "when handoff notes are enabled". The **restarting** phase, which spans only
the kill and respawn in `startFreshRunner`, has no lever to hold it open live and stays proven only by
`ScriptedResettingTest` (`app/src/sharedTest/.../ScriptedResettingTest.kt`). Cost is unchanged: the ping
turn plus the daemon's reset wrap-up turn.

The **stop-running-turn** scenario (#965 —
`interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`) is likewise **always-on** (not
`@Ignore`d): the stopped turn's `cancelled` `stop_reason` and a real reply to a same-thread follow-up are
**durable** post-conditions, so it belongs in the always-on gate alongside the new-session delimiter.
Since [#1357](knowledge/features/turn-outcome-indicator.md) a cancelled turn shows nothing in the status
area, so the method no longer asserts an Interrupted label there — only that the Stop control itself
leaves once the `cancelled` `turn_end` arrives; the method name is kept because other docs reference it.
It holds a turn
open on a command that cannot end on its own — `STOP_HOLD_PROMPT` asks claude to run, in the foreground,
`python3 -c "import threading; threading.Event().wait()"` and then reply with a fixed token
(`STOP_HOLD_REPLY`) — never a fixed delay; the command ends only when the interrupt kills it, or, far
outside the test's own step, the Bash tool's own two-minute ceiling. A `python3` command raises a
permission prompt that the phone draws as a modal dialog over the composer, so tapping Stop through it
would not match how an operator reaches the button; the #848/#849 `SecondClientPeer`, paired
`--allow-remote-permissions`, allows the prompt once so the dialog closes and the composer's Stop control
(`cd_thread_interrupt`) is reachable. The test taps Stop, then asserts: the peer's first `turn_end` for the
conversation carries `stop_reason == "cancelled"`; and the Stop control is gone. A ping sent in the same open
thread afterward gets claude's real reply (`awaitPingReplyNamingLayer`) and a second `turn_end`, proving the composer still works
after a stop, and no bubble ever carries the held turn's own reply token — the interrupted turn never
finished. Two real claude turns: the held-then-interrupted turn and the follow-up ping. This proves only
the **single-device** case; **cross-device** Stop (a turn started on one device, interrupted from another)
remains [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)'s open scope.

The peer-opening regression in [#1696](https://github.com/pyrycode/pyrycode-mobile/issues/1696)
failed before the held turn was sent. All four retained #1631/#1637 branch/base reports timed out
at peer open; their daemon logs contained respectively **102/84/102/90** `static_key_mismatch`
and `bound_to_other_key` occurrences, with zero redemption-window rejections. The harness reused
one host-A peer token across scenarios, but each old peer generated a new static key after the
daemon bound that token to the first accepted key. The repair reuses #1698's process-scoped,
exact host/token identity store; daemon authentication and readiness waits remain unchanged.
Before sending the held turn, this scenario now opens and closes a prior peer with the identical
pairing, then opens its observing peer. Both must settle through the handshake and correlated
`list_conversations` probe. This catches per-instance identity rotation even when the method runs
alone, without spending another Claude turn. See [the handshake regression](knowledge/features/development-verification-test-scheduling.md#test-scheduling-and-harnesses).

The fresh dispatcher full suite on 2026-10-04 explicitly passed
`InteractiveStreamE2ETest#interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`:
**53 executed, 52 passed, 1 failed, 0 skipped**. The unrelated question-answer failure passed
on a focused same-tree rerun; Stop passed in the original full run. See
[Verification status](#verification-status) for the revisions and retained reports.

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
list → "Open archive" → the Archived screen, which opens on the **Discussions** tab by
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
list's **own** menu → Archive route (#1665), using the client-owned `cd_open_menu` and
`thread_overflow_archive` labels, reaches the Archived screen,
which until #740 was proven only at the event boundary
(`ChannelListScreenTest.archiveEntry_emitsArchiveTapped` before #1665). The full round trip now uses the same
toolbar entry. `awaitChannelList()` is followed
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
`NoiseSessionFactory` and `NoiseSessionPump`, with a process-scoped in-memory X25519 identity
(`PeerDeviceKeyStore`, never the phone's Keystore key) and its own `pyry pair`
token — `peerToken`, minted on host A by `scripts/e2e-emulator.sh`'s `pair_token` alongside the existing
two-host pairing, never logged. The peer's read-only `PairedServerStore` is a file-private in-memory
test double. Its
`PeerDeviceKeyStore` retains one static pair for each server id/SHA-256 token fingerprint for the
instrumentation process (#1686, preserving #1698's lifetime contract), across peer close, reconnect
and app graph rebuild. Creation and publication share one lock so concurrent first readers cannot
bind competing keys. The registry retains no raw token and writes nothing to disk. Different hosts
or tokens select independent identities; relay URL and server-key metadata changes do not rotate one.
The daemon binds a token to its first accepted static key, so generating a new key for each scenario
rejects later peers even when a standalone scenario passes. Every dial still creates fresh Noise
handshake and cipher state. Neither store writes app credentials, the host list or registry selection;
keys and tokens stay out of evidence logs. Returned key arrays are defensive copies because
`NoiseSessionFactory` wipes its private-key input. See
[Test scheduling and harnesses](knowledge/features/development-verification-test-scheduling.md#test-scheduling-and-harnesses)
for the identity and graph-cleanup regressions. A scenario opens one peer (`open()` — dial through a
`RedialingLink` until a link *settles* by answering a `list_conversations` probe, and keep redialing
automatically whenever the live link ends, the way the app's own supervisor does; hardened by #1036 after
a fresh relay connection was found ending within about 50 ms of its handshake — see [Coverage —
hardened](#follow-ups-to-ticket)), sends through it (`sendMessage()` — a `send_message` envelope, awaiting
the `ack` by `in_reply_to`; never resent on a redial, since the daemon does not dedupe it), reads its
frames (`awaitFrame()` — the first recorded envelope of a type naming the conversation id, findable even
if it arrived before the wait began, since every inbound frame is recorded from `open()` onward, and a
prompt the daemon re-sends to a new link is recorded only once), and closes it in `finally` (`close()` —
stops redialing and tears down the live session's keys and transport; idempotent).

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
open, the peer's reply arrives by the daemon's ring replay. When this scenario shipped (#850), **no live
frame carried another device's message text, only a history page did**, so the still-open thread's own
reconnect re-ask — which fired as soon as the socket came up, before the coordinator's repository was
back, and died with an `IllegalStateException` — was the only way `OFFLINE_PROMPT`'s text could reach the
phone at all; that production bug was found and filed rather than fixed,
[#861](https://github.com/pyrycode/pyrycode-mobile/issues/861), and the scenario worked around it by
leaving the thread and reopening the chat's row so a fresh `ThreadViewModel`'s opening history ask ran on
the live repository for both the prompt and the reply. **#861 fixed the bug**: the reconnect re-ask keyed
off the host's `coordinator.currentRepository` going non-null instead of the socket-level
`ConnectionState`, so it no longer fired ahead of the repository it needed, and step 6 dropped the reopen
— it waited in the still-open thread for both the peer's reply and `OFFLINE_PROMPT` to render, relying on
that reconnect re-ask's history page to supply the prompt text.

**[#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) removed the reconnect re-ask outright**
(older history now loads only on the reader's own pull, never on a reconnect — see [Remote conversation
repository § the retry and the two
restarts](knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md#the-retry-and-the-two-restarts-778)),
so this scenario no longer has a history page to lean on for `OFFLINE_PROMPT`'s text. It still needs none:
since pyrycode#2699, landed before #1352, the daemon pushes each delivered user message **live and into
the replay ring**, not only into history, so the missed-event `last_event_id` replay that #1352 explicitly
left untouched now delivers `OFFLINE_PROMPT`'s text itself, with no `request_history` round trip involved.
The scenario's own steps and assertions are unchanged — it still waits in the still-open thread for both
the peer's reply and `OFFLINE_PROMPT` to render with no pull gesture — only its KDoc and step comments
changed, to say the prompt arrives via the reconnect replay rather than naming the now-removed re-ask.
Step 6 still asserts each of the four messages (`PING_PROMPT`, its reply, `OFFLINE_PROMPT`, its reply)
renders **exactly once**, in `boundsInRoot.top` order. Two real claude turns: the phone's ping and the
peer's offline turn. Folded into the pre-ship `LIVE=1` gate as the 13th curated method, taking
`LIVE_MINIMUM` from 12 to 13 and the run's real-claude cost from six turns to eight. The live run that
closed #850 (`python3 scripts/android-test-gate.py live`, 2026-09-23; branch `feature/850` at
`c9b0fdc084` merged with `main` at `923b176bef`) executed all thirteen with no failures or skips; two
earlier live attempts on that branch failed first because the cut raced the phone's own settled reply
rather than the peer's, then because the reconnect assertion ran before the reopened thread's history
page had arrived, before the fixes recorded above landed. The live run that closed #861 (`python3
scripts/android-test-gate.py live`, 2026-09-23; branch `feature/861` at `24c9490d9a` merged with
`origin/main` at `148b9f7225`) re-proved the same thirteen scenarios with step 6's reopen dropped — see
the dedicated entry below. The live run that closed #1352 (dispatcher real-claude gate, 2026-10-02)
re-proved this scenario, among fifty executed with none failed, with the reconnect re-ask gone.

The **offscreen-reply-survives-reconnect** scenario (#1581 —
`interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`) is likewise **always-on**: it
proves that a reply the daemon finishes while its own thread is off screen is not lost across a
reconnect, the rung-3 counterpart of
[#1572](https://github.com/pyrycode/pyrycode-mobile/issues/1572)'s unit-level proof that an open thread
asks for the newest history page every time it gains its host (see [Thread screen — the oldest-end
history demand §
#1572](knowledge/features/thread-screen-oldest-end-history-demand.md#1572-an-open-thread-asks-for-the-newest-history-page-every-time-its-host-becomes-available--at-open-and-again-after-every-reconnect-while-it-stays-open--not-only-the-first-time)).
Chat A gets one settled ping turn, which the phone draws and caches. A's second turn is held on the #849 permission lever
(`WAIT_PROMPT`) until the peer answers it, and the phone leaves A for a second chat, B, before that
happens — so the leave never depends on how fast claude answers. The peer allows the prompt once the
phone is in B, and the turn ends there; A's thread never draws the reply, because it is not open. The
test does not wait on the peer's own `turn_end` to know the phone has the reply, since the peer's copy
can arrive first (the same race the offline-read-reconcile scenario above guards against): it instead
polls the phone's persisted `ReadPosition` for A until `completedTurnId` names that turn, and asserts it
is unread, which is the phone's own record that it folded the `turn_end` — and everything before it on
the ordered inbound stream — while A was not viewed. Only then does it cut and restore the host link with
`setHostLink`. Before reopening A, it also reads `ConversationCache` for A directly and asserts the cache
holds the ping's cached reply but not the held turn's reply, so the live checks that follow cannot pass
on an empty or wrongly keyed read. Reopening A, with no other gesture, must then draw all four rows —
the ping prompt, its reply, the held prompt, and its reply — exactly once each, in `boundsInRoot.top`
order. Without #1572, an open thread only ever asked for the newest page on a never-loaded thread, so A's
cached-but-stale reopen would show just the first two rows and the final wait would time out.

Before any prompt, the scenario opens and closes a prior `runningToolPeer` with the same pairing
(#1692). The observing peer must then complete its own handshake and correlated readiness probe,
with the existing deadline. This exposes token-bound static identity rotation even when the method
runs alone, without adding Claude turns. See [Coverage — hardened](#follow-ups-to-ticket) for the
authentication diagnosis and [Verification status](#verification-status) for the repaired full-suite
pass; the offscreen, unread, cache and four-row recovery assertions remain unchanged.

Two real claude turns: A's ping and A's permission-held command. Folded into the pre-ship `LIVE=1` gate
on the curated list in `scripts/e2e-emulator.sh`, taking `LIVE_MINIMUM` from 51 to 52. The live run that
closed #1581 (dispatcher real-claude gate, 2026-10-03; branch `feature/1581` at `70aee9838d` merged with
`origin/main` at `985ff3ca64`) selected five methods — this one plus four always-run methods — and
reported 5 executed, 4 passed, 1 failed, 0 skipped; this method itself executed and passed.
The one failure, `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, failed once and then
passed on a re-run of the same merged tree, so it was triaged as a pre-existing flake in the suite, not a
regression from this ticket.

The **model and effort settings round trip** (#545) is four **always-on** methods (none `@Ignore`d)
proving the settings read (#590), the applied-effort footer (#889) and the remembered-effort recall
(#686) reach a real daemon, since none of those tickets had live proof of its own. Each method prepares
its own conversations through the paired host's own repository — `RelayConnectionRegistry.connectionFor(serverId)`
— rather than seeding `conversations.json` as the #847 fixture does, because a seeded row has no bound
session and cannot take a model or effort write; `create_conversation` binds one before it replies.
Every method restores the settings it changed with `set_session_settings` on the same session and clears
the remembered level in `finally`, so a red run cannot leave a write behind for a later method.
`interactiveTurn_modelChange_roundTripsAndStaysPerConversation` (**one** real-claude turn, since #1308) keeps
chat X inherited (no pick, no explicit saved model) and sends it one real ping. After the reply, the test
computes the expected marked row itself from the fresh model menu and the host repository's own announced
model, using its own copy of the three-tier rule `ThreadRunConfig.selectedChoice` applies (exact `value`,
then `resolvedModel`, then family; the first tier with any candidate decides, more than one marks nothing).
`awaitAnnouncedMark` (#1497: Run configuration's one-line rows no longer draw `resolved_model`, so two rows
of one family can read alike) first waits until the model radios, in sheet order, read the menu's
non-default Claude labels in menu order, then asserts either that the only marked radio sits at the expected
row's index in that list, or that no model radio is marked and the family note shows outside a radio — proving
which row is marked by position rather than text. Either way, no radio reads "Default". It then opens Run
configuration on X and picks a different published row, confirming the pick
stays marked — no longer the announcement — after leaving and reopening X, while chat Y's own separately
saved model is untouched throughout. The runnable scenario is in `InteractiveStreamE2ETest`; it does not
depend on the separately reported `ThreadRunConfig.running` display text, only on the raw `announcedModel`
key.

`InteractiveStreamE2ETest.interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage`
(#1223, **one** real Claude turn) chooses a nondefault model published for the source chat's agent,
waits for its acknowledged session setting and persisted app preference, then creates a new chat through
the Chats control. It reads the new chat's saved model before sending its first prompt, awaits the live
reply, and reads both conversations' saved choices again. The 2026-09-28 post-verifier live gate passed
all 37 selected methods with no failures or skips.

[#1193](https://github.com/pyrycode/pyrycode-mobile/issues/1193) temporarily excluded six other
`InteractiveStreamE2ETest` methods from the curated live list: five use Settings controls removed by
\#1239 ([#1245](https://github.com/pyrycode/pyrycode-mobile/issues/1245)), and the operator-bypass
permission case had a settle-window assertion failure now diagnosed by
[#1246](https://github.com/pyrycode/pyrycode-mobile/issues/1246). Two older workspace-switching methods
remain ignored and excluded. #1249 restored two of the five Settings-dependent methods through the
list toolbar; #1251 restored the channel method and #1252 replaced the Log data method with a
host-backed archive proof. The remaining two Settings methods and two older workspace-switching
methods stay excluded. With #1208's tool-use method and #1286's Offline Retry proof,
`scripts/e2e-emulator.sh` now lists 44 runnable methods and `android-test-gate.py` requires
44 executed tests. Script tests check the floor and the
restored methods' explicit presence, as well as excluding ignored methods.
`interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` (**one** turn) starts a chat with no
saved or remembered effort, sends the ping prompt, and asserts the next fresh reply carries
`effective_effort` (an omitted key fails the method) and a non-empty `permissionMode`. Since
[#1309](https://github.com/pyrycode/pyrycode-mobile/issues/1309) the thread stays open throughout — it no
longer leaves and reopens to force a fresh subscription — and asserts the footer's effort label/note and
its permission label both settle to that fresh reading's values, because the open thread now re-reads its
settings when the turn ends and (redundantly) when `awaitFooter` opens Run configuration to check.
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
`interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`) rides the curated `LIVE=1`
list in the earlier coverage state (`LIVE_MINIMUM` was 34, [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981)
having restored it on top of #965's stop method, [#966](https://github.com/pyrycode/pyrycode-mobile/issues/966)
having raised it again for the permission-answer and question-answer methods,
[#967](https://github.com/pyrycode/pyrycode-mobile/issues/967) having raised it again for the reconnect and
background-task methods, [#955](https://github.com/pyrycode/pyrycode-mobile/issues/955) having raised it
again for the two push methods, and [#1016](https://github.com/pyrycode/pyrycode-mobile/issues/1016) having
raised it again for the attachments-from-phone and claude-offered-file methods, #1020 and #1050 having each added one more,
and [#1021](https://github.com/pyrycode/pyrycode-mobile/issues/1021) having raised it again for the
zero-turn mute-channel method; see
[Pre-ship gate](#pre-ship-gate)). Running it needs its
own daemon: `scripts/e2e-emulator.sh` starts a
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

**Pairing lifetime (#1756).** After APK build and successful bypass-daemon prerequisites,
`scripts/e2e-emulator.sh` starts `scripts/e2e-bypass-pairing.py` without minting credentials. In
`InteractiveStreamE2ETest`, this method waits for the channel list and connection, grants camera
permission, then requests the fixture immediately before `pairHostByCode`. The authenticated,
one-shot host-loopback request mints the unprivileged phone code and the separate privileged peer
pairing on the isolated daemon, checks their host/key agreement and rewrites the phone relay address
for the emulator. Earlier scenarios can therefore take longer than the daemon's unchanged 15-minute
redemption window without aging these pairings. An authenticated request consumes the fixture even
if minting fails;
it cannot refresh or retry a failed pairing, and the general stale-code rerun does not restart it.
Cleanup stops the helper before removing the isolated HOME. Pairing codes, tokens, keys and the
fixture authorization capability stay out of diagnostics.

The original #1668 full gate failed during `pairHostByCode`, before the permission assertions:
53 executed, 1 failed, 0 errors and 0 skipped, with
`v2.handshake.reject.redemption_window_elapsed`. Its same-tree focused rerun passed 1 executed,
0 failed/errors/skipped; that established the scheduling-dependent expiry rather than a permission
failure. Moving minting after the build (#993) had left the wait for earlier scenarios inside the
redemption lifetime. The fake-clock regression in `scripts/test_e2e_emulator_gradle.py` advances
16 minutes after shell fixture setup and before requesting pairing, without sleeping or invoking
Claude; the old eager lifecycle failed at code age 960 seconds against the 900-second contract.
See [the recorded investigation](https://github.com/pyrycode/pyrycode-mobile/issues/1756#issuecomment-5990055551).

**Full-suite proof (2026-10-05).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` on `feature/1756` at
`0b7267c536`, merged with `origin/main` at `13ceb8b609`: exit 0, **55 executed, 55 passed,
0 failed, 0 errors and 0 skipped**. The retained XML explicitly includes
`InteractiveStreamE2ETest.interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`
with no failure, error or skip. XML artifact on the dispatcher host:
`/Users/juhanailmoniemi/WorkSpace/Projects/pyrycode-mobile-agents/logs/2026-10-05T08-04-49-827Z_real-claude-gate_#1756.log`;
diagnostics are in its `.stderr.log` companion. This is the fresh full curated suite, rather than
a focused rerun. See [the gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1756#issuecomment-5990708689).

Three acceptance steps, all against pyrycode#2510 (`475c406a`)'s `session_settings` reporting the mode
Claude last confirmed for the running child, not merely `yolo`: after a tool-free ping, a fresh reading
reports `bypassPermissions` and the reopened footer settles on Bypass approvals, proving the daemon and
the #650 footer agree from a cold read. Choosing Manual approval must produce an acknowledged
`permission_write`; a refused or failed write fails separately. Once the pending control clears, the
scenario requests another fresh reply for the same session: `default` confirms Manual approval early,
while `bypassPermissions` identifies an acknowledged no-op. The selected Run configuration row must
match that reply. Pending duration alone proves neither outcome; the earlier 15 s timing assertion
could fail before reading the daemon's mode. Then Plan, Bypass approvals and Manual approval each settle
on their own label on the same session with no turn in between, each confirmed by a fresh reading before
the footer is asked to agree. Finally, still on that session, a Read of a file outside the conversation's
workspace — holding a fresh token that appears nowhere in the prompt — raises a permission prompt the test
matches by the file's base name or the word `Read`, scoped to the dialog holding the prompt's own Cancel
button so the phone's own message bubble (which contains both) cannot match; the peer allows it once, and
the assistant's reply must carry the token.

**Step 5's diagnosis (#977).** The peer opens before the Read is sent and records every frame for the
chat, so after `allowOnce` returns the step first waits for the peer's own `turn_end`
(`awaitFrame(chat.id, "turn_end", REPLY_TIMEOUT_MS)`) instead of polling the phone directly. A turn that
never ends inside that wait fails with its own message naming how long the wait was, distinct from the
token message — it no longer masquerades as a missing reply. Once the turn has ended, the phone gets its
own short trailing wait (`PHONE_TRAIL_MS`) for a bubble carrying the token; if none appears, the failure
message (`readReplyDiagnosis` in `InteractiveStreamE2ETest.kt`) reports counts and booleans only — the
number of message bubbles and each one's text length, whether any bubble reads `blocked`, whether the
Read's recorded `tool_result` was `is_error`, the `turn_end`'s `stop_reason` / `outcome` / `is_error`, and
whether any frame the peer recorded after the allow carried the token — never bubble text, payload text or
the token itself. The two live runs that closed this ticket both failed with that second message: the turn
ended normally, the Read's `tool_result` was clean, and claude's reply was exactly the token
(`peerFramesWithToken=true`) — but no bubble on the phone ever carried it. That named a real production
bug, not a flaky assertion or a slow turn: the phone did not render a reply the daemon actually sent.
[#981](https://github.com/pyrycode/pyrycode-mobile/issues/981) found the cause on the phone side — the
thread screen, not the repository fold, was losing a non-streaming reply under `reverseLayout = true` once
the list overflowed (see `knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, "The
newest-row pin (#981)") — and removed the `@Ignore` above. `readReplyDiagnosis` now also reports
`threadHeldToken` (`threadHoldsReply` in `InteractiveStreamE2ETest.kt`): whether the phone's own repository
held an assistant row carrying the token, a boolean (or `unread` on a read failure), never the token or the
row text, so a future failure at this step names its cause from the phone side alone.

An unmet prerequisite — no credential, an unreadable `~/.claude.json`, a daemon revision `PYRYCODE_SRC`
cannot show contains pyrycode `475c406a`, no `claude` on `PATH`, or a fixture failure such as a pairing
that could not be minted — fails only this one method, naming the prerequisite; it never skips and never
fails the run. The script passes the reason as a static code (`bypassUnmet=no_credential`, not a free-text
sentence): `-Pandroid.testInstrumentationRunnerArguments.*` reaches the device through `am instrument -e`,
where a space in the value is not safe, so the method maps the code back to a full sentence itself
(`BYPASS_UNMET_REASONS`) rather than trusting the script to hand it prose — the same reason every other
instrumentation argument this suite passes (`peerToken`, `bypassServerId`, …) is a single token, never a
sentence. On a **failed run**, `cleanup` first copies claude's session transcripts (regular `*.jsonl` files
under `.claude/projects/`; `find -type f` skips symlinks, so a credential file could not reach the copy
even as a planted symlink) from the bypass `HOME` into `WORK_DIR/bypass-transcripts/`, keeping their
relative paths — a failed copy never blocks the rest of cleanup (#977). `cleanup` then kills the dedicated
daemon and removes its isolated `HOME` (with its `~/.claude.json` copy) and the outside-workspace token
file **whatever the exit code** — the transcript copy only ever runs first, it never changes what gets
deleted or when — and never touches the operator's own `~/.pyry/config.json`. The transcripts are the only
record of a failed turn once `HOME` is gone; both live runs that closed this ticket read their kept
transcript to confirm the diagnosis above. Two real claude turns: the tool-free ping and the
outside-workspace Read. See
[Thread composer footer § Permission mode](knowledge/features/thread-composer-footer.md#permission-mode-650)
for the footer's own settle-and-confirm rules this scenario exercises against a real daemon.

The **permission-answer** and **question-answer** scenarios (#966 —
`interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`,
`interactiveTurn_questionAnswer_reachesTheAskingConversation`) prove that a permission prompt and a
clarification question each show only in the conversation that raised them, that the phone's own answer
reaches that conversation's claude, and that an answer another device gives closes the phone's prompt with
no tap. They need a phone that is **allowed** to answer, which every other scenario on this list
deliberately withholds (the running-tool and stop-running-turn scenarios above are peer-answered precisely
so the phone stays unprivileged). Don't-ask-again is also only ever offered on the daemon's **stdio**
permission-prompt path (`always_allow.offered`); the approval-MCP path host A uses for every scenario above
always reports `{offered:false}`, and host A runs under the operator's own `~/.pyry/config.json`, which the
harness will not edit. So `scripts/e2e-emulator.sh` starts a **fourth** test daemon, the **answer daemon**
(§ 4a', `start_answer_daemon`), shaped like the operator-bypass daemon above — its own isolated `/tmp`
HOME, `stdio_permission_prompt: true` in its config — but with **no** operator-bypass argv, so claude's
default mode asks for permission as normal. The phone is paired `--allow-remote-permissions` with **this
host alone**; nothing else pairs the phone with it, so every other scenario still sees an unprivileged
phone, and both #966 methods remove the pairing from `PairedServerCollectionStore` in `finally`. A second
`SecondClientPeer` is paired `--allow-remote-permissions` on the same host, gaining `awaitQuestion` and
`answerQuestion` alongside its existing `awaitPermissionModal` / `allowOnce`. All answer peers using
the suite's one answer-peer pairing share its process-local static identity; closing a peer releases
its session and sockets without rotating that identity. An unmet prerequisite (no
credential, `claude` missing, the isolated HOME failing to build, either pairing failing to mint) sets one
static `ANSWER_UNMET` code that fails only these two methods, naming the cause — it never skips and never
fails the rest of the run.

The permission-answer scenario opens two conversations (A, B) on the answer daemon. A prompt raised in A
(`ANSWER_PERMISSION_PROMPT`, a `python3` command whose output token the prompt text never contains) shows
in A's stream with every decision-context field the frame carries and `always_allow.offered = true`.
[#1306](knowledge/features/permission-modal-overlay.md) moved the request out of its own dialog and into
`ThreadScreen`'s message stream (following #1305's question batch below); the scenario now also ticks the
session-grant checkbox and arms Allow before leaving A for B — B shows no card and no A prompt text, and
returning to A restores the checked grant but clears the arm, so allowing needs two fresh taps rather than
one. Allowing it with don't-ask-again ticked removes the card, and claude's reply — read from the peer's
recorded `assistant_delta` frames, not the phone's own bubble (see #981's diagnosis above) — carries the
token. `assertBashRan` additionally requires a `Bash` `tool_use` and a non-error `tool_result` for it in the
frames the peer recorded for that turn, so a reply claude could compute or recall (`966 * 7`) cannot pass in
place of the command actually running. The same prompt sent again in A shows no second `modal_shown` and
still passes `assertBashRan`, proving the grant held; the same prompt sent in B (whose session holds no
grant) shows the card again, and the peer allowing it removes it with no phone tap. Three real claude turns.

[#1686](https://github.com/pyrycode/pyrycode-mobile/issues/1686) repaired setup before these permission
checks: `SecondClientPeer.open` redialled until its 30-second deadline because a later scenario used a
new static key with the already-bound answer-peer token (daemon #2734). Retained answer-daemon logs
showed `v2.handshake.reject.static_key_mismatch` with `bound_to_other_key`: 18 events on #1631's branch,
12 on its base, and 18 on #1637's branch. The coroutine timeout and captured launcher focus alone did
not identify that operation. The permission method now labels its unchanged open wait with
`peerStep(peer, "open answer peer")`; every permission assertion above remains enabled. Its fresh
full-suite pass is recorded in [Verification status](#verification-status).

The question-answer
scenario opens one conversation and sends a prompt asking claude to call `AskUserQuestion` with two labels
and then echo the chosen one back (`QUESTION_PROMPT`); after selecting `QUESTION_PICK`, the phone reveals
the stable `question-batch-actions` container before the unchanged 30-second wait for enabled Continue
(#1702). Valid selection need not compose that separate lazy row; waiting before revealing it can time
out without reaching the scroll. Continue sends the selected label to the asking conversation;
`question_dismissed` must report `source = remote` and `outcome = answered`, and the completed reply must
name the chosen label and exclude the other. The peer then answers the batch shown for a repeat of the
same prompt, clearing it with no phone tap and completing the second turn. Two real claude turns.

The phone's option and Continue use `questionAnswerTarget` before real pointer taps (#1703).
`performScrollToNode` sees the full drawing viewport, including the area behind thread chrome;
an enabled node can still have its tap center behind the composer. The helper measures the header
and composer, applies one scroll adjustment if needed, and asserts that the actual tap center is
between them. Requiring the whole button rectangle to clear chrome is unnecessary. The short-thread
`ThreadInlineQuestionTest#phone_question_scroll_sequence_submits_from_a_short_thread` checks selection,
tap geometry and exactly one submit event with the held generation; the older long empty-thread
fixture could pass while this path was broken. The actions reveal from #1702 remains necessary.

`QuestionAnswerStage` / `questionAnswerStep` labels setup, peer waits, phone submission, question
removal and both completed-turn waits. Coroutine and Compose timeouts retain their original cause
and add only fixed operation text and lazily read, content-free peer link state; deadlines stay
unchanged. The captured failure was `AwaitPhoneDismissal` — `await phone answer's question_dismissed
on peer` — after 30000 ms with `session open (link 1, replaced 0×)`. It occurred after #1702's
enabled-Continue wait and #1686's peer admission: retained daemon logs showed accepted handshakes,
not key-binding rejects. The red/green fixture proves tap occlusion, but missing historical phone
logcat prevents proving that occurrence's coordinates; attribution to occlusion remains an inference.
The repaired method ran and passed in the fresh full live suite in
[Verification status](#verification-status), preserving both answer round trips without tap retries.

\#1305 moved the batch from its own dialog into
`ThreadScreen`'s scrollable stream (see [Question batch modal § Placement](knowledge/features/question-batch-modal.md#placement-inline-in-threadscreen-since-1305));
`awaitInlineQuestion` / `awaitNoInlineQuestion` scroll the lazy list to the `question-batch-title` tag
instead of waiting on a dialog-scoped title match, and the absence check also requires the
`question-batch-actions` tag and the always-composed "Waiting for answers" status label to be gone, since a
lazy row can be merely offscreen rather than actually absent. Both scenarios resolved open questions from
the plan on their first live pass: claude does supply decision context and does offer don't-ask-again for a
default-mode `python3` ask, and real claude did call `AskUserQuestion` reliably from the scripted prompt, so
no deterministic-only fallback was needed. The protocol doc's paragraph that a `question_answer` is
"resolved by nothing yet" was stale — it is gated by the same per-device `--allow-remote-permissions` bit a
`modal_answer` uses.

The **reconnect-footer**, **reconnect-commands** and **background-task** scenarios (#967 —
`interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`,
`interactiveTurn_reconnect_slashCommandsAndCompactStillWork`,
`interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`) prove that the composer's controls and
command feedback (footer readings and a model change, #545/#946; slash-command suggestions, #885; Compact
session, #874/#884; the Actions-menu background-task count and panel, #678, and the thread's running-task
pill opening the same panel, #1296) still work after the phone's
link is cut and restored, and that a background task real claude starts is tracked through its full
lifecycle. They reuse #545's settings helpers and #850's `setHostLink` / `cycleHostLink`, and #950's
`SecondClientPeer` approval path, rather than repeating those scenarios.

`interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` runs a ping, cuts and restores the link,
and confirms that the footer keeps its context percentage across the reconnect, matching #1317. The
inherited model's selected row follows the held model announcement. Effort and permission settle on a
live settings reply. Since #1397, `freshSettings` skips the held reply that #1320 emits first. The second
ping must finish and deliver a context reading with a token count greater than the held reading. A saved
percentage alone cannot prove freshness, because both turns can round to the same percentage. A model
picked from the footer after that turn is confirmed by another live settings reply.
The #545 `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`
method's effort-label mapping moved into a shared `appliedEffortFooter` helper both methods call, with no
behaviour change. Two real claude turns: the ping before the cut and the ping after it.

`interactiveTurn_reconnect_slashCommandsAndCompactStillWork` runs a ping (spawning claude, which publishes
its slash-command menu), cuts and restores the link, then types `/` and asserts the rows, labels and
completion match `slashCommandTypeAheadRows` / `slashCommandOptions` / `completeSlashCommand` on the
reconnected menu — not restated here. Actions → Compact session then shows the `cd_thread_compacting`
indicator, and the indicator clears. Since #1358 the `compacting` falling edge itself draws the divider,
unreported ("Conversation compacted"), before the `compaction_boundary` frame replaces it in place with
its counts and "by you"; a one-shot read after the edge clears can therefore catch the divider still
unfilled. The test's `dividerText()` helper waits until the text credits the compaction to you
("Conversation compacted … by you") rather than reading once, then asserts **exactly one** compaction
divider in the thread — proving the boundary replaced the edge's own divider in place rather than adding
a second. Two real claude turns: the ping and the compaction. On the live relay the
reconnect's fresh connection can itself drop and be redialled within about a second — \#1051 traced this to
the relay's per-phone outbox overflowing on the daemon's connect-time reconcile burst, fixed in
pyrycode/pyrycode-relay\#154; before #1029 the scenario held the pre-redial connection's repository and the
slash-command read timed out after 30 s. It now reads through `firstOnLive` (`LiveConnectionReads.kt`, `app/src/sharedTest`), which follows
the host's current connection, and the assertion still requires the rows to come from a connection that is
live when they arrive. See [Relay repository coordinator § Edge cases /
limitations](knowledge/features/relay-repository-coordinator.md#edge-cases--limitations) for the underlying
contract.

`interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` asks claude, through the main daemon's
`--allow-remote-permissions` peer (the #950 path), to run a command in the background
(`python3 -c "import time; time.sleep(40)"` — a bare `sleep` of 25s+ is refused by claude's Bash tool, the
same constraint #849's `WAIT_PROMPT` documents). Once the task starts, the visible thread pill opens the
panel while work is running; since #1631 the top overflow's Background tasks row also opens
the same populated panel after dismissing the menu. Since #1668, the method retains its historical
name but asserts Background tasks is absent from Actions. `openBackgroundTasks` and its progress-scenario
caller use the count-free top menu. Once the task finishes, the pill disappears and the top menu reopens
the panel. Since #1751, this `InteractiveStreamE2ETest` scenario separately asserts
the decoded task type is `local_bash` and the visible label is "Command" from both
the running-pill and top-menu entry points. Display labels must have independent
expectations: matching the raw payload against visible text broke both live waits
while deterministic gates stayed green. See the [panel's label rules](knowledge/features/mobile-modal-callers.md).
The panel's own end state
is **not** durable: the terminal `background_task_updated` marks the task Finished, but real claude also
sends an empty `background_task_roster` unprompted after a finish, and `BackgroundTaskProjection`'s
wholesale-replace rule (see [`backgroundTasks`](knowledge/features/remote-conversation-repository-live-stream-and-modals.md#backgroundtasks--the-v2-background-task-decodefold-seam-677))
then drops the task from the panel before the label is ever read. The assertion accepts either "Finished"
or "No background tasks" and still rejects "No background-task report yet" — the live run showed the
empty-roster reading win. One real claude turn: the prompt that starts the task.

The **background-task-progress** scenario (#1076 —
`interactiveTurn_backgroundAgentProgress_showsOnRunningCard`) extends the #967 background-task proof to
the running card's progress block (#1044): it has claude start a `general-purpose` subagent — a
`local_agent` task the daemon reports through the same `background_task_started` /
`background_task_progress` frames — then, while the task is still running, asserts one panel card
carries both an activity line (a prefix of a recorded progress description) and a meta line with a tools
segment, since only the progress block draws the tools segment and the task's opening description cannot
pass for it. It holds the task open with work, not a permission prompt: on this daemon a prompt draws as
a dialog over the composer that would cover the Actions footer, and a *backgrounded* subagent risks ending
before a frame arrives and costs a second turn on its finish notice. So the subagent runs in the
**foreground** (as in the daemon's one measured `task_progress` capture) and is given a run of `Read`
calls, one per message, on missing files inside the chat's working directory — the daemon's claude has no
`Glob` tool, found when the first live attempt asked for it and the subagent made no call at all. One real
claude turn: the prompt that starts the subagent.

The scenario joined the `LIVE=1` list un-ignored under #1107, once
[pyrycode/pyrycode#2661](https://github.com/pyrycode/pyrycode/pull/2661) (merge `b733a68`) closed the
daemon parser gap filed as [pyrycode/pyrycode#2658](https://github.com/pyrycode/pyrycode/issues/2658): a
foreground subagent's first sidechain entry echoes the prompt claude gave it as a `user` text block, and
`(*Parser).emitUser` no longer surfaces that echo as an `unrecognized_message` (`site=user_block`,
`message_type=text`). The scenario's own steps had already passed live at both prior attempts — the
progress frame arrived and the card drew both lines — only the class's `UnrecognizedRowSentinel` (#586)
failed the run, on the same echo, twice out of two. It now belongs in the always-on gate alongside the
background-task scenario above, #967.

No rung-4 twin: the scripted `fakeclaude` backend carries no `compacting`, `slash_command_list` or
`background_task_*` frames (#946 records the same gap for `context_usage`). Two cases in this family stay
proven only deterministically, and are not part of this ticket's own methods: banner notices
(`BannerNoticeRowTest`, `app/src/sharedTest`) and model refusals (`ModelRefusalRowTest`,
`app/src/androidTest`) — real claude does not raise either on demand.

The **mute-channel** scenario (#1021 — `interactiveTurn_muteChannel_roundTripsThroughTheHost`) proves
that Edit channel's Mute notifications checkbox round-trips through the host: nothing is patched
locally, so the modal can only reopen checked because the daemon stored `set_conversation_muted` and
echoed it back in `conversation_updated`. A channel set up on the host directly (a discussion promoted
in its own `cwd`, so no folder is created) is muted through the thread's menu Edit (since #1563; the
list's own Channels row pen reached the same modal through #1561), with OK closing only
once the write is confirmed and the reopened modal reading the flag from the host's own row; the same
round trip proves the clear by unmuting it, and the channel is deleted in `finally`. Zero real-claude
turns — promote, mute, unmute and delete are all daemon round-trips. No rung-4 twin: the checkbox's
write never depends on a claude turn, so the scripted `fakeclaude` backend has no turn to hold open for
it.

The **permission-held running-tool** scenario (#950, repaired by #1528 —
`interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`) is likewise **always-on** (not
`@Ignore`d): the status area naming the tool claude is running (#897's label) is a **durable** fact once
the peer allows the call, so it belongs in the always-on gate alongside the settings scenarios above
(operator-bypass, above, was always-on too until it turned `@Ignore`d — see its own paragraph). The call
is still held the #849 way: it is never auto-allowed, so claude's `tool_use` arrives and the call waits on
a permission prompt that only the `SecondClientPeer` paired with `--allow-remote-permissions` can answer
(the phone stays unprivileged, as every other scenario expects). Since #1483, `ThreadStatusArea` reads
`thread_status_waiting_for_permission` ("Waiting for permission") in place of the status arms while that
prompt is open, so the running-tool label cannot be observed during the hold: the test asserts the
"Waiting for permission" reading is displayed and that the `cd_thread_tool_running` label does not exist.
A quick command's tool call would then close before the phone can be sure to observe it open once allowed,
so #1528 gave the held call its own prompt, `HELD_TOOL_PROMPT`, whose `python3` sleeps 10 s before
printing — long enough to observe once allowed, and well under claude's ~30 s first `tool_progress`
heartbeat, so the label the test waits for has no elapsed reading. The peer allows the command once; while
it runs, the test asserts the exact `cd_thread_tool_running` description (`"Claude is running Bash"`) is
displayed, and once the peer's own `turn_end` frame arrives, the description is asserted gone. One real
claude turn, of at least 10 s. `RUNNING_TOOL_PROMPT`'s quick `python3 -c "print(950)"` is unchanged and
stays reserved for the two background-push scenarios below, which only need the prompt to exist, not to
stay open. The rung-4 `tool` and `tool-progress` scenarios are this scenario's deterministic twins,
proving the same label (without and with an elapsed reading) on every scripted run.

Since #1683, `InteractiveStreamE2ETest` opens and closes a prior `runningToolPeer` with the same
pairing before opening the observing peer. Both opens require the existing handshake/probe readiness;
the prior peer sends no message and approves no permission. This exercises an already-bound token
even when the method runs alone: a successful first attachment cannot detect per-instance static-key
rotation. `peerStep` labels prior/current peer opening, permission-modal arrival, approval/dismissal
and turn completion with content-free link diagnostics, separating those waits from the three status
assertions above. The shared `holdToolOnPermission` also labels its existing waits for the ignored
elapsed scenario. Deadlines and the real permission round-trip remain unchanged. See
[peer identity coverage](knowledge/features/development-verification-test-scheduling.md#test-scheduling-and-harnesses)
and [Verification status](#verification-status).

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

The **background-push-turn-end** and **background-push-prompt** scenarios (#955 —
`interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`,
`interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`) are the first live proof that
a real FCM push from the production relay, not a synthetic message, wakes the backgrounded app and posts
[#685](https://github.com/pyrycode/pyrycode-mobile/issues/685)'s alert. Both `assumeTrue` a `wss://` relay
URL, so they skip on the default loopback whole-class run — only the production relay can send FCM — and
both need a daemon at pyry v0.23.0 or later (`cmd/pyry/push_wake.go`) and the managed device's
`google-atd` image (below), whose Play services obtain the FCM token the `aosp-atd` image cannot.
`awaitPushRegistered` polls `AppPreferences.pushToken` for that token, cycles the host link so the #365
connect-time re-registration has sent it to the daemon, then sleeps out the rest of the daemon's 30 s
per-device wake-coalescing window, measured from when the scenario first saw the phone connected — so an
earlier scenario's wake cannot have suppressed this one's. `sendAppToBackground` starts the system HOME
intent through a shell `am start -W` — not Settings: the `google-atd` image has a Home activity but no
Settings activity, and an earlier attempt at Settings failed outright with `am`'s "unable to resolve
Intent" on this image — then waits for the host's `currentRepository` to go null.
`ActivityScenario.moveToState(CREATED)` does not serve here: it puts an androidx.test activity in front in
the same process, so `ProcessLifecycleOwner` still counts the process as started and
`LifecycleConnectionDriver` never closes the link.

`interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` holds a real turn on a permission
prompt the #950 way while the phone is in front, so that prompt's own alert is spent in the foreground and
is never posted again later. Only once the app is in the background and its host link is down does the
peer allow the command, so the turn ends while the phone is absent, the daemon's `push_wake` reaches the
relay, FCM wakes the app, `onPushWake` reconnects the host, the missed `turn_end` replays, and the
notifier posts exactly one turn-completed alert. Sending that alert's own `contentIntent` — exactly what
the system sends on a tap — opens that conversation's thread, asserted by its run-unique name, the send
control present and the channel-list marker absent. One real claude turn.

`interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` proves the exactly-once
guarantee across a second reconnect inside the same wake window: with the phone already absent, the
peer's turn raises a permission prompt, the push wakes the app, and exactly one prompt alert shows. The
test then cuts and restores the host link a second time, and the daemon shows the still-outstanding
prompt again. Since #1337, the new connection clears held prompts and the source re-emits the
re-shown prompt's alert; the notifier's `AlertLedger` drops the repeat
([Dependency injection — host conversation source § Attention alerts](knowledge/features/dependency-injection-host-conversation-source.md#attention-alerts-685)).
There is no observable second notification to await; the scenario sleeps a bounded settle after the
reconnect, then asserts there is still
exactly one notification and that its `postTime` is **unchanged**. A second `notify` for the same tag
would replace the notification and change its `postTime`, which a bare count cannot see. What this proves
is the operator-visible outcome — one notification, never re-posted.
One real claude turn: the peer's held command. Cleanup cancels the wake watcher, releases the held
permission and awaits turn completion when available, closes the peer and cancels notifications.

The #1694 shared failure occurred before push registration or backgrounding: the uniquely named
conversation's creation/rename was followed by `static_key_mismatch` / `bound_to_other_key`
rejections throughout `SecondClientPeer.open`'s 30-second window. This localization comes from retained
conversation/daemon timestamps and source ordering, not the unlocalized coroutine stack. The peer
must retain the static identity bound to its shared host/token (#1698/#1686). The scenario now opens
and closes a prior same-pairing peer through `use` before opening its prompt peer; both must complete
the handshake and readiness probe, even in a focused run. This guard spends no Claude turn.
`peerStep` names prior/current opening, message acknowledgement and permission arrival; the shared
`withTimeoutDiagnostic` evaluates content-free state only on timeout and preserves its cause.
`setHostLink` similarly distinguishes close from reconnect and reports repository presence. Existing
deadlines and alert assertions remain unchanged. See
[test scheduling and harnesses](knowledge/features/development-verification-test-scheduling.md#test-scheduling-and-harnesses).

No rung-4 twin: the loopback relay the scripted harness dials cannot send FCM, and
[#685](https://github.com/pyrycode/pyrycode-mobile/issues/685) already covers synthetic delivery
deterministically.

The **attachments-from-phone**, **claude-offered-file** and **peer-attachment** scenarios (#1016 —
`interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`,
`interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart`; #1020 —
`interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`) are likewise **always-on** (not
`@Ignore`d): that another client sees the phone's own attachments with their exact bytes, that a file
claude hands over survives a restart, and that another client's upload names its file again after a
history reload, are **durable** post-conditions, so all three join the always-on gate alongside the
reconnect and background-task scenarios above. All three ride the #848 `SecondClientPeer`, extended with
`uploadAttachment`, `retrieveAttachment` and `history`, standing in for the desktop client the attachment
slices (#983–#985) had only been proved against fakes. A new `ActivityIntentStub`, a no-arg
`Instrumentation.ActivityMonitor`, answers the composer's document picker, the system file viewer and the
save picker with `MediaStore` fixtures — the app refuses any content authority of its own, the test APK's
included, so a fixture has to come from outside the app.

`interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` picks a 4 × 4 PNG and a ~100 KB text
document (three 45000-byte chunks, so the phone's own chunking and the daemon's reassembly both run)
through the composer's **Attach files** action and sends them into a fresh chat X. Once the peer sees
X's `turn_end`, `userMessageAttachmentIds` reads the peer's own `history(chatX)` call and asserts it
holds exactly one user message naming two distinct ids; each id's `request_attachment` then returns
bytes whose SHA-256 matches a fixture, and a second chat Y on the same host gains no user message. At
#1016's own ship, the daemon logged the operator's turn in history as a plain `message`/`user` entry
with text only and dropped `attachment_ids` entirely, so this method read the ids from the phone's own
cached sent row instead (`awaitCachedSentAttachmentIds`) — a fact the ticket's plan did not anticipate
and had to rework around (see [Verification status](#verification-status)). That daemon gap was filed as
[#1020](https://github.com/pyrycode/pyrycode-mobile/issues/1020); once it landed, this method went back
to reading the peer's history and `awaitCachedSentAttachmentIds` was deleted.

[#1697](https://github.com/pyrycode/pyrycode-mobile/issues/1697) confirmed this
`InteractiveStreamE2ETest` scenario passes unchanged after #1698/#1686's shared peer identity
repairs. A 30-second timeout plus daemon `static_key_mismatch` / `bound_to_other_key` events
establishes a shared authentication problem, not an attachment-byte defect; distinguish peer
opening from history and retrieval waits before changing the attachment path. The exact-byte,
three-chunk and conversation-isolation checks remain intact. See [Verification status](#verification-status)
for the retained #1686 proof and #1697's fresh full-suite pass.

`interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` (#1020) proves the sibling leg #1016
could not: another client's upload, named on a message, read back after `E2eTestApplication.rebuildGraph`
with the thread cache cleared so the row can only come from history replay. It shipped `@Ignore`d and out
of the LIVE list — with no id in a replayed `message` entry, no row could appear — until #1020's reducer
change let a stored user `message` entry's `attachment_ids` survive into the reduced row (see [Remote
conversation repository — reads and the thread store — history
paging](knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md#history-pages-fold-into-the-same-thread-645)).
With that fix it dropped `@Ignore` and rides LIVE as the curated list's thirty-second method.
[#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) removed the opening history ask that used
to fetch this row for free on reopening chat X after `rebuildGraph` — older pages now load only on the
reader's own pull. The scenario now performs that pull itself through a shared `pullForOlderHistory`
helper (a swipe down on the `thread-message-region` tag), called once after reopening chat X and before
asserting the row, so "the row can only come from history replay" still means exactly that: the reload
reaches the screen through a page this test explicitly asked for, not an ask the app used to make on its
own.

`interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` keeps the phone attached to a fresh chat X
while a prompt has claude write a short file with `printf` and hand it over with the daemon's `send_file`
tool (`pyry_files`), which accepts only a path inside the conversation's workspace. `attachment_offered` is
**live-only, with no daemon-side registry and no replay** — unlike a message's attachment ids, which
#1020 made durable in history, an offered file is never in history at all, so this is a **different**
limit, not the same one under another name, and it remains open. The phone draws the row and its thread
cache holds it as an assistant-side message; after
`rebuildGraph` (cache kept, unlike the other methods' cleared cache) the row is still there exactly once,
and both `ACTION_VIEW` (open) and `ACTION_CREATE_DOCUMENT` (save) yield the fixture's digest. A fresh
device, or a cleared cache, would not show the file — by design, and not asserted here.

**Since #1329, the offered file is fetched by its first tap, not drawn and fetched on sight.** The offer
`send_file` produces carries a name but no MIME type, and the name is a plain `.txt`, outside desktop's
image-extension list — so the row draws deferred (the ready `File field`, no status line, nothing
requested) until `assertOpensAndSaves` taps it. That tap is now what triggers the retrieval, so its wait
for `ACTION_VIEW` grew from `THREAD_TIMEOUT_MS` to `REPLY_TIMEOUT_MS` to cover it; `readyAttachmentRow`
matches a deferred row exactly as it matches a `Ready` one, since both draw identically. The sibling
attachments-from-phone and peer-attachment scenarios are unaffected: one is the phone's own send, which
resolves through `sentOriginal`/`canRead` with no relay request regardless of this rule, and the other is
a history-replayed row, which has no name or type to defer on and so still loads on show.

**The LIVE list only filters; it does not set the order.** JUnit's default `MethodSorters.DEFAULT` runs a
class's methods by name hash, not by the list's own sequence, so a method's place in
`scripts/e2e-emulator.sh`'s `TEST_TARGET` string says nothing about when it runs. This ticket's own name
came from that lesson: an earlier live run had two unrelated peers (this scenario's and #848's) go dead —
handshake accepted, then no frames — and the fix was not a code change but a rename, moving both methods to
a part of the hash order where every peer opened nearby had stayed alive across runs (see
[Verification status](#verification-status) for the run-by-run evidence).
[#1053](https://github.com/pyrycode/pyrycode-mobile/issues/1053) later named the cause: the daemon's
connect-time reconcile burst overflowed the relay's per-phone outbox once the daemon held 15–23 sessions,
closing every fresh connection 35–65 ms after its handshake — the same shape #1029 and #1036 traced for the
reconnect and peer-started/stop-running scenarios, fixed in pyrycode/pyrycode-relay#154. The method's own
KDoc now names that cause instead of calling it unexplained; the name, and so its place in the hash order,
did not change. Both methods also now open by
requiring one `request_history` round trip from their peer (`assertPeerAnswers`) before any attachment
step, so a dead peer fails fast, named as a relay or daemon fault, instead of timing out 240 s into a
`send_file` wait. Daemon revision: the live daemon needs pyrycode #2166 (`attachment_offered`'s producer)
and #2169 (`pyry_files` registered on the interactive spawn). No rung-4 twin: the scripted `fakeclaude`
backend can neither call `send_file` nor serve `attachment_chunk` / `request_attachment`, and nothing here
is a transient signal that would need one.

The **interrupted-upload**, **interrupted-retrieval** and **cross-host-attachment-recovery** scenarios
(#1017 — `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`,
`interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`,
`interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`) are likewise **always-on** (not
`@Ignore`d): that a dropped link recovers into exactly one message carrying its bytes, that a failed
retrieval's Retry loads the right file, and that a file pending or sent on one host of a colliding
conversation id never reaches the other host, are **durable** post-conditions, so all three join the gate
beside the #1016/#1020 attachment scenarios above. None of the three cuts the link on a timer: each installs
a wrapping `RelayLog.sink` that still forwards every line, and the first time it sees the line it is armed
for, closes that host's `RelayConnectionSupervisor` synchronously, on the thread that logged it (the private
`cutLinkOn` / `LinkCut` helpers) — so the cut lands on a step the app itself has just taken, and can never
race the daemon's reply.

`interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` (AC-1) attaches a ~100 KB, three-chunk
document to a fresh chat X and sends `PING_PROMPT`; the cut is armed for `event=attachment_chunk … index=1
total=3` — the second of the three chunks — so the third is never sent and the daemon can neither complete
the file nor answer `attachment_stored`. The send fails, the composer keeps the text and the file (the
tile's own Remove control, drawn only while no send is running, is the sign the failed send has ended), and
the peer's view of X holds no user message. With the link restored and Send tapped again, the peer's view of
X holds exactly one user message, whose one named id (read from the peer's own history, #1020) fetches the
fixture's exact bytes. One real-claude turn.

`interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` (AC-2) has the `SecondClientPeer` — not
claude — upload a document into a fresh chat X and name it on a message, then restarts the phone with X's
thread cache cleared, so the row can only come from history replay; the cut is armed for
`event=attachment_request id=<id>`, before the request is sent, kept armed by scrolling the row onto screen
until it fires. The row — unnamed, since only a completed retrieval supplies the display name — shows the
failed state with Retry; with the link restored, Retry brings it to ready under the uploaded name, and both
opening and saving it yield the fixture's digest. The plan's original design used a file claude hands over
with `send_file` instead: at the time, interactive mode streamed no live user-message event and history
replay dropped a message's `attachment_ids` (both #1020, then open), so a peer-sent file could not reach the
phone at all, live or by reload, and the retrieval path being interrupted is the same
`request_attachment`/`AttachmentStore`/row-state path whoever sent the file. Once #1020 landed and let a
replayed `message` entry's `attachment_ids` survive into the reduced row, the method was rewritten to use
the peer's own file, as the ticket had originally asked. One real-claude turn: the peer's message.
[#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) removed the opening history ask this
method relied on to surface the row after the restart; it now calls the same `pullForOlderHistory` helper
as `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` once chat X is reopened, before
scrolling the row onto screen to arm the cut.

`interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost` (AC-3) reuses #847's seeded collision — one
conversation id on two isolated test daemons — pairs host B by code as #847 does, and reads each copy's
current name by id, since #847's own method may rename host A's copy first. With the peer on host A: while
A's composer holds a pending attachment, host B's copy of the conversation shows no tile for it; the send
then lands only on A, whose peer view holds exactly one user message and whose one named id fetches the
fixture's bytes; and afterward host B's copy shows no row or tile with the file's name, B's own thread cache
names no message with that id, and host B's own `fetchAttachment` answers the id `NotFound`. The first live
run found that host A's seeded copy needed a fix: #847 seeds a conversation with no `current_session_id`,
and the daemon refuses `send_message` to an unbound conversation (`no_bound_session`) — no earlier scenario
had ever sent into it. `seed_collision_conversation` (`scripts/e2e-emulator.sh`) now takes an optional
session id; host A's copy gets a run-unique one, so the first send revives that session through the daemon's
restart-recovery path and spawns claude fresh under it, while host B's copy stays unbound, since nothing is
ever sent there. One real-claude turn: the phone's message on host A.

No rung-4 twin for any of the three: the cut itself is deterministic, but proving nothing arrives needs a
real daemon's chunk reassembly, and the scripted `fakeclaude` backend can neither call `send_file` nor serve
`attachment_chunk` / `request_attachment`. See [Verification status](#verification-status) for the mobile
and daemon revisions and the result of the first live run.

`InteractiveStreamE2ETest.interactiveTurn_markdownLink_opensLiveNoteInReader` (#1050, extended #1067; shared reader Actions menu since #1667) is likewise **always-on**: that a
markdown-path link in an assistant reply opens [the live linked-note reader](knowledge/features/markdown-reader-screen.md#linked-note-live-since-1050)
with the host's current content, and that its [Refresh](knowledge/features/markdown-reader-screen.md#copy-and-refresh-menu-since-1067)
re-reads it on demand, are durable post-conditions, unrelated to the attachment scenarios above — the note is
never a stored attachment, so its bytes never ride `SecondClientPeer` or `ActivityIntentStub` the way #1016's/
#1020's do. `runningToolPeer()`'s `SecondClientPeer` is present throughout regardless, as in every scenario
that needs `allowPromptsUntil` to gate a shell permission, but before #1067 it only ever answered prompts and
never originated a chat message itself. Two real claude turns in a fresh chat: claude runs one `printf` to
write a heading-only note and replies with a link to it; the test taps the link (via `performFirstLinkClick`,
retried — a streaming reply reveals the link's source a character at a time, so an early tap can land before
the link exists) and asserts the note's heading and file name in the reader with the composer gone; with the
reader still open, #1067 has that same peer send the rewrite prompt itself (`peer.sendMessage`, in place of the
phone's own composer) so claude rewrites the note with a second `printf` — still two turns, not three — and
choosing Refresh from the reader's overflow shows the new heading and never the old one, with no "Couldn't open
file"; back returns to the thread, and the same tapped link shows the new heading too, proving the reader
re-fetches on every open rather than caching. No rung-4 twin: the scripted `fakeclaude` backend has no
workspace-file read path to hold open, and the phone-side behaviour (classification, the one-read guard, the
failure notice, the copy conversions) is covered by unit and Robolectric tests instead — see [MarkdownText §
Markdown-path links](knowledge/features/markdown-text-internals.md#markdown-path-links-since-1050) and [Markdown reader
screen § Copy and refresh menu](knowledge/features/markdown-reader-screen.md#copy-and-refresh-menu-since-1067).

The **second-host rename and unpair** scenario (#1085 —
`interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched`) is likewise **always-on** (not
`@Ignore`d): rename (#744) and unpair (#745) of a paired host were proven only against fakes
(`EditHostModalTest`, `HostEditor` unit tests) until this scenario drove both live on #847's two-daemon
harness — host A pre-paired, host B paired by code through `pairHostByCode`. It renames B from its host
row's Edit host modal, declines the unpair confirmation (leaving B paired and connected), then confirms
it (removing B's section, its `PairedServerCollectionStore` entry and its `RelayConnectionRegistry`
connection). After every step it checks host A is untouched: same stored label, same conversation ids,
same `nameA` row, and — the load-bearing check — `connectionFor(serverIdA)` is the **same**
`RelayConnectionBundle` **instance** captured before B was ever paired, not merely a `Connected` state.
`RelayConnectionRegistry.reconcile` keys bundles by the stored record, so a state read alone would pass
even if managing B had silently rebuilt A's bundle; identity is what a rebuild would actually break. B's
removal is checked as absence from the **whole** lazy list, not just the composed nodes: the drive
requires `performScrollToNode` to fail for both B's Edit control and B's row, after first waiting for the
Edit host modal itself to close (its own scrollable would otherwise answer the scroll and the check would
pass while B's section still existed off screen). Unpair is phone-local — the daemon does not revoke B's
device token — so B's pair code stays reusable by #847 in the same run; B is still removed from the store
in `finally`, as #847 does, so a red run cannot leave it paired or selected for a later scenario. Zero
real-claude turns: pairing, rename and unpair are daemon round-trips or phone-local. No rung-4 twin: the
scripted harness has one daemon and no second host to manage.

The **status-band-never-empty** scenario ([#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311) —
`interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy`) is **always-on** (not `@Ignore`d): a prompt
that makes real claude run a read-only `echo` and then answer in a short sentence drives one turn through
`thinking`, a tool call and `responding` text — the exact sequence that, before this ticket, left the band
dark for most of its length. From the tap on Send the test samples the band inside one `waitUntil`, using
the composer's Stop control (`cd_thread_interrupt`) as the busy proxy — the same control `stopping` in
[Thread input bar](knowledge/features/thread-input-bar.md) shows exactly while `isBusy` holds and the draft
is blank, so there is no second flaky "is the turn running" signal to keep in sync with the band's own. A
sample is recorded as dark only when the Stop control is present on **both** a read taken before and a
read taken after the reading checks — closing a race where `turn_state{idle}` lands between the two and a
genuinely busy sample is misread as dark at the turn's falling edge. A reading is either the status glyph
(`STATUS_GLYPH_TEST_TAG`, present for thinking, working, a running tool or a stall) or a matched content
description or text for a Reset-session reading, the connection arm's "Connecting"/"Reconnecting" copy (the
unformatted template, so a live countdown does not break the match), a compaction or api-retry reading, or
"waiting for answers" — so a higher-ranked arm shown instead of the glyph is never mistaken for an empty
band. Any busy sample with neither is recorded, and the assertion is that the recorded list is empty; a
non-vacuity check requires at least one busy sample to have been taken, so the scenario cannot pass by never
catching the turn busy. One real claude turn: the scenario's own tool-then-text reply. No rung-4 twin:
`ScriptedStatusLineTest` (`app/src/sharedTest/.../thread/`) already proves the identical sequence
deterministically against the real repository fold — see [Thinking indicator §
Edge cases](knowledge/features/thinking-indicator.md#edge-cases--limitations). The method is in the curated
live selector: `scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET` lists it after
`#interactiveTurn_toolPrompt_rendersToolStepInThread`, and `android-test-gate.py`'s `LIVE_MINIMUM` counts it
(see [Pre-ship gate](#pre-ship-gate)). Its first live pass is recorded in [Verification
status](#verification-status).

The **scanner-confirm-waits-for-host** scenario ([#1394](https://github.com/pyrycode/pyrycode-mobile/issues/1394) —
`interactiveTurn_scannerConfirm_waitsForHostThenOpensList`) is on the LIVE curated selector and counted by
`LIVE_MINIMUM` (not `@Ignore`d): [#1386's
PR #1391](https://github.com/pyrycode/pyrycode-mobile/pull/1391) made `ScannerViewModel` report a pairing done
only after the host answers — `ConfirmPairing` saves the record and moves to `Verifying`, and only
`Paired` lets the `Routes.SCANNER` composable in `MainActivity` navigate to the channel list — proven on
the JVM and in Robolectric but not over the real relay. This drives the scanner's own confirm, not the
paste-link path `pairHostByCode` exercises: it opens the scanner from the list's pair-another-host
control, waits for `ScannerUiState.ReadyToScan`, injects host B's existing pair code as
`ScannerEvent.QrDecoded` (the scanner and the paste-code path share `parsePairingPayload`, so no new
fixture is needed), taps "Confirm pairing", then waits up to `PAIR_TIMEOUT_MS` — covering the view
model's 30 s connection wait plus the pop — for the channel-list tag (`pairHostByScanner`). It asserts
the scanner view model itself ended in `Paired`, so a `Cancelled` return to the list cannot pass by
coincidence, that host B is saved, and that B's seeded conversation (`ARG_COLLISION_NAME_B`) folds away
under B's label and not A's, with the converse check for A. A scanner pairing saves no display name, so
both hosts would read "Unnamed host"; the scenario names B first with
`PairedServerCollectionStore.setDisplayName` (the call the Edit host modal makes) so the two labels
differ. **VM access.** `MainActivity` builds its `NavHostController` inside `setContent`, and Compose's
`NavHost` does not tag the view with it, so the route's `ScannerViewModel` is unreachable by
`nav.getBackStackEntry(...)` the way `PairCodeScreenTest` reaches its own screen-owned nav host. Instead
`pairHostByScanner` swaps in `scannerViewModelModule { captured.set(it) }`, a mirror of `AppModule`'s
`ScannerViewModel` definition that also records the instance Koin builds, and restores the plain mirror
in `finally`; the VM is still created by the route's own `koinViewModel` call in the route's back-stack
entry, so the `Paired` navigation and the pop are the production ones. Because the restore loads the
plain mirror rather than reinstating `AppModule`'s own definition, every later live method in the same
process resolves the scanner view model from the mirror, not from `AppModule`, until the process ends;
keep `scannerViewModelModule` in step with `AppModule`'s definition by hand; the drift has no error if
the two are not kept in step. Zero real-claude turns: pairing and the list are daemon round-trips. B is
removed in `finally`. No rung-4 twin: the deterministic harness provisions one host per invocation and
cannot pair a second, and this scenario needs the second live host that only rung 3 / `LIVE` provisions.
The method joins `scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET` after
`interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy`, and `android-test-gate.py`'s `LIVE_MINIMUM`
counts it (see [Pre-ship gate](#pre-ship-gate)). Its first live pass is recorded in [Verification
status](#verification-status).

The **dormant-channel-history** scenario (#1571 —
`interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend`) is likewise **always-on** (not
`@Ignore`d): that opening a channel whose session the daemon does not hold still shows its earlier
messages, with no send, is a durable post-condition of #1569/#1572's thread-opens-and-asks-for-its-own-
history-page fix — before them a dormant channel opened empty until the first send woke its session. The
live suite cannot restart its daemon mid-run, and the main test daemon's idle eviction is off, so the
after-restart dormant state cannot be produced by cutting and restarting the daemon the way #847's
scenario does; `scripts/e2e-emulator.sh` seeds it instead, before the daemon starts. A new
`seed_dormant_history` writes one finished turn — `send_message`, `assistant_delta`, `turn_end` — into the
daemon's own on-disk history format (pyrycode `internal/history`'s `{"format":"pyrycode.history",
"version":1}` header line, then one `{"id","type","payload","ts"}` line per entry, under
`conversations/<id>/history/segment-<20 digits>.jsonl`) beside a row from `seed_collision_conversation`
that is promoted and bound to a session id the daemon never spawns — the same unbound-after-restart shape
#847's scenario seeds, reused here for dormancy rather than id collision. Payloads mirror pyrycode's
`internal/protocol/testdata/send_message.json`/`assistant_delta.json`/`turn_end.json` fixtures rather than
its `history_page*.json` ones: the latter's `assistant_delta` carries no `turn_id` or `seq`, and the
phone's `AssistantDeltaPayloadDto` requires both, so a reply seeded from a `history_page*.json` shape would
decode and then silently drop, rendering nothing. The run-unique id, name and stored reply text reach the
test as instrumentation arguments (`dormantConversationId`/`dormantName`/`dormantReply`), read by a small
`dormantArg` helper that fails naming the script rather than throwing a bare NPE. The test asserts the
reply is absent before opening the row, then opens it and waits for the reply anchored in a message
bubble — no `pullForOlderHistory`, no send, so it spends no real-claude turn: `requestHistory` has no
caller but the open thread itself, so the stored reply can only reach the phone through that ask. No
rung-4 twin: the scripted path seeds no history log and the twin would prove only the reducer, which
`ThreadViewModelTest` already covers. The seeded channel and its history directory persist in the LIVE
instance across runs, the same way #847's collision rows do; `awaitChannelRow` scrolls to find it, so
nothing breaks, but a future cleanup of accumulated seeded live rows should fold this one in too. The
method joins `scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET` after
`interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip`, and `android-test-gate.py`'s
`LIVE_MINIMUM` counts it (see [Pre-ship gate](#pre-ship-gate)). Its first live pass is recorded in
[Verification status](#verification-status).

The **thinking-spinner** scenario (#482) is the **flakiest** rung-3 scenario and ships **`@Ignore`-gated /
manual**: the spinner has **no durable equivalent** of the tool name — once real claude emits its first
token, `turn_state` flips to `responding` and `isThinking` goes false. Before [#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311)
this left `ThinkingIndicator` early-returning with nothing on screen; the band now shows "Working…"
instead (§ the status-band-never-empty scenario above), but that durable "Working…" reading proves only
that *a* turn is busy, not that the *spinner specifically* was shown mid-turn, so this scenario's own
mid-turn window is still undeterminable the same way. With no scripted backend to hold the turn open and no
way to imperatively pause
  real claude (the levers rung 4's two-fragment release and the #432 component twin's `pushTurnState` have), the
mid-turn window cannot be made deterministic, so the developer cannot prove reliability without operator
infra. It therefore lands as a documented manual case (presence-only, tolerant, keyed on
`cd_thread_thinking`; **no** absence-after-end assertion that would race a 2nd turn, and **no** negative
control — the asserted token is a production content-description, not a claude-output substring). The
operator un-ignores to attempt the run and may promote it to always-on if a pure-reasoning prompt yields a
catchable window; otherwise it stays manual. See the
  [Assumptions](#assumptions-to-confirm-on-first-live-run) entry on the screen-sourced thinking window.

### Offline Retry proof

`InteractiveStreamE2ETest.interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`
(#1286, one real-Claude turn) keeps a fresh thread open while the harness stops its
owned daemon. It observes the actual `offline_retry_target`, restores the same
host identity, taps the displayed pill, requires that host's repository to recover
and the pill to clear, then sends a new phone prompt and awaits its rendered reply.
An intentional supervisor `close()` projects hidden Idle and cannot prove this
path; the older offline-read scenario remains a separate cache/reconciliation proof.

Recovery must precede passive reconnect. Both this method and its rung-4 twin keep
the repository null and the pill visible immediately before the tap, and require
repository recovery within 20 seconds of the sixth failed dial. Daemon restart,
relay registration and the tap consume that same window; the capped backoff's
24-second minimum lies beyond it. A timeout starting after restart or the tap can
otherwise pass on an automatic dial even with a broken Retry callback.

The fault can initially produce ordinary reconnect failures before `4404` daemon
absence. `DaemonAbsent` still derives to UI Offline at the cap; requiring only
`RelayLinkStatus.Offline` would miss it. Restart must wait for relay registration
before Retry, or the requested dial can encounter daemon absence again. These
scenarios exercise the existing lifecycle-checked exact-host action with a real
failure, without injected UI connection state. Only the harness-owned daemon is
stopped; pairing material remains in its private host storage.

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
- Android SDK with the `google-atd` API 33 system image (Play services — #955's push scenarios need it to
  obtain an FCM token; the `aosp-atd` image it replaced could not). AGP auto-provisions it on first run,
  which needs the SDK `cmdline-tools` installed and the image licence accepted (`sdkmanager --licenses`).
  On this machine `cmdline-tools` was absent at authoring time — install it before the first run.
- `python3` (decodes the base64url pairing payload).

The script: starts the relay → starts the daemon (`PYRY_MOBILE_V2=1`, pointed at the loopback relay,
alongside any other harness daemons the mode needs) → builds the app and test APKs
(`assembleDebug assembleDebugAndroidTest`, the same `-PuseRelayRepository` build properties the test task
uses) → mints the ordinary device pairing tokens with `pyry pair` and parses the payload, and starts
the lazy bypass fixture described above → runs
`pixel2Api33AtdDebugAndroidTest`, which finds the APKs already built, with the four values injected as
instrumentation arguments → tears everything down. Minting moves after the build (#993): a daemon redeems
a pairing code only within a 15-minute window, and a slow or contended Gradle build (28 minutes observed on
#966's live gate, against a 3-minute green baseline) can otherwise burn that window before the test task
ever starts, expiring every code at once and failing every pairing method with an anonymous 30-second
timeout that looks like a problem in the branch under test. When the test task fails, the script scans the
harness daemon logs it captured (`DAEMON_LOG` and, when present, the two-host, operator-bypass and answer
daemon logs) for `redemption_window_elapsed` and prints `pairing_codes_stale` naming each daemon whose code
went stale — a daemon rejected a handshake because its pairing code outlived the 15-minute redemption
window before the test redeemed it — instead of leaving the cause to the anonymous timeout. No pairing
code, token or key appears in that message; a clean log leaves the failure output unchanged.

Since 2026-10-05 the script then retries once. A full live run can reach the method that pairs a host after
the window has passed even with minting after the build, as on #1668's gate, where the operator-bypass test
ran 20 minutes in before #1756 moved bypass minting to scenario entry. `retry_with_fresh_codes` reads
the failed methods from the Gradle report, remints the ordinary pairings because the reinstalled app
holds a new key, and reruns only those methods with the fresh codes. The bypass fixture stays one-shot
and is neither restarted nor reminted by this path. `scripts/e2e-rerun-report.py` folds the rerun's
results into the first run's report, so the gate still counts every method. The run passes only when every retried method passes. A second expired code
is reported again and fails the run, and there is never a second retry. The installed-app path of
`scripted-all` never retries.

On the same failed-test path, the script also scans those logs for `msg="transport: disconnected"`
(`WSSClient`'s reconnect loop in pyrycode's `internal/transport/wssclient.go`) and prints
`relay_link_dropped` naming each daemon whose relay link ended some way other than the teardown's own kill
(`context canceled`), with the `time=` of each such end (#1132). A failure whose window overlaps a reported
drop time points at the gate machine's network, not the test or the app under test — this is how the
`InteractiveStreamE2ETest` push-alert flakes in the #1119 live gate were diagnosed: all four harness
daemons lost their relay link within four seconds of each other during a ~30s network gap on the gate
machine. Only the `time=` token is printed; addresses, tokens and keys in the log line are not. A clean
log, or a run that passes, leaves the failure output unchanged.

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

Full live runs and subsets selecting the host-prompt method require a daemon containing merged
pyrycode#2768 handler commit `b3daa0432528188f2829b235877c60627afb8e43` or a descendant.
`require_host_prompt_daemon` checks the binary's recorded revision against Git ancestry before startup;
`PYRYCODE_SRC` must hold both revisions. Outdated or unverifiable binaries fail explicitly rather than
skip the scenario. A daemon with the durable instructions store can still lack the paired-client
host-prompt handlers. Scripted runs and unrelated live subsets retain their existing prerequisites.

The wrapper sets `LIVE=1` and a unique `e2e-auto-…` test instance per invocation (see
[Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay) below), so there is no env-var
incantation to remember — the current selector has 55 runnable `@Test` methods. The historical
inventory below describes the pre-#1193 forty-four-method set; ignored methods are excluded from
the current selector as described under the model and effort settings round trip. It covered ping + create-workspace-folder, #566;
new-session, #541; delete, #554; archive-restore, #551; change-workspace, #562; rename, #537;
save-as-channel, #581; list-archive-entry, #740; two-host separation, #847; peer-started turn, #848;
peer-queue-consistency, #849; offline-read-reconcile, #850; status-sheet running model, #891; footer
context usage, #946; model change, inherited effort, chosen effort and remembered-effort recall, #545;
running-tool status label, #950; stop-running-turn, #965; operator-bypass permission, #687 (fixed #981);
permission-answer and question-answer, #966; reconnect-footer, reconnect-commands and background-task, #967;
background-push-turn-end and background-push-prompt, #955; attachments-from-phone and claude-offered-file, #1016;
peer-attachment, #1020; markdown-link, #1050; mute-channel round trip, #1021; interrupted-upload,
interrupted-retrieval and cross-host-attachment-recovery, #1017; second-host rename and unpair, #1085;
host-backed diagnostic archives, #1252; two-host default workspace and Archive, #1086;
channel create, edit and archive with its prompt read back, #1088;
an attention dot following a
real turn, #1090; background-agent progress on the running card, #1076/#1107)
ride the wrapped mode.

These `InteractiveStreamE2ETest` cases preserve the ping and Reset-session
regressions after host-owned routing, since #847 that two paired hosts whose
conversations share an id stay separate through pairing, rename, link-cycling and a
restart, since #848 that a turn started from another paired device continues on the
phone, since #849 that phone replies, queued sends and drops stay consistent with
that same device's view of the backlog, since #850 that a loaded conversation stays
readable while its host link is cut and reconciles a peer's turn once the link is restored, since
\#965 that the composer's Stop control ends a still-running turn with the Interrupted outcome and the
conversation keeps taking real replies afterward, and that Reset session's wrapping-up phase is causally
held, not raced on timing, since \#955 that a real FCM push from the production relay wakes the
backgrounded app for a turn that ended while it was away and posts one alert whose tap opens the right
thread, and that a permission prompt surfacing while the app is away is alerted exactly once even across
a second reconnect inside the push's wake window, since \#1016 that another client sees the phone's
own attached files with their exact bytes and that a file claude hands over with `send_file` survives a
restart in the phone's own thread cache, and since \#1020 that another client's own upload, named on a
message, is still there — and still opens and saves — after a history reload with the thread cache
cleared, and since \#1017 that a link dropped mid-upload or mid-retrieval recovers without a duplicate
message or a lost file once it is restored and retried, and that a file pending or sent on one host of a
colliding conversation id never reaches the other host. They do not prove **cross-device** Stop, which
belongs to
[#679](https://github.com/pyrycode/pyrycode-mobile/issues/679). The gap #850 found — an open thread
recovering a peer's reconnect-window prompt on its own, without being reopened — is closed by
[#861](https://github.com/pyrycode/pyrycode-mobile/issues/861): #850's scenario proves it directly, with
no reopen step.

**When the dispatcher runs it:**

- **after verifier on a ticket labelled `needs-real-claude`**, so the live stack is checked before
  documentation and merge; and
- **when a daemon or relay change touching the mobile surface lands**, alongside the daemon's own
  `make e2e-realclaude` when that acceptance crosses repositories.

**Historical cost before #1193's exclusions:** forty-four real claude turns across forty-four curated methods — five pings (ping,
create-workspace-folder, new-session, the peer-started turn's own ping, #848, and the
offline-read-reconcile scenario's own ping, #850), plus #849's peer wait turn and its drained
ping, #850's peer offline turn, the status-sheet-running-model scenario's own ping, #891, the
footer-context-usage scenario's own ping, #946, the inherited-effort and chosen-effort scenarios' own
turns, and the remembered-effort-recall scenario's two turns, #545, the
permission-held running-tool scenario's own turn, #950, the stop-running-turn scenario's two turns
(the held-then-interrupted turn and its follow-up ping), #965, the operator-bypass-permission
scenario's two turns (the tool-free ping and the outside-workspace Read), #981, the permission-answer
scenario's three turns (the allowed command, its don't-ask-again repeat, and the peer-allowed prompt in
the second conversation), and the question-answer scenario's two turns (the phone's answer and the peer's
answer), #966, the reconnect-footer scenario's two turns (the ping before the cut-and-restore and the ping
after it), the reconnect-commands scenario's two turns (the ping and the compaction), and the
background-task scenario's one turn (the prompt that starts the task), #967, the background-push-turn-end
scenario's own turn (the command the peer allows once the phone is absent) and the background-push-prompt
scenario's own turn (the peer's held command), #955, the attachments-from-phone
scenario's own turn (the phone's attached message), and the claude-offered-file scenario's own turn (the
phone's message that runs `printf` and calls `send_file`), #1016, the peer-attachment scenario's own
turn (the peer's message naming the file, read back after a history reload), #1020, the markdown-link
scenario's two turns (claude writes a note and replies with a link to it, then rewrites the note), #1050,
and the interrupted-upload, interrupted-retrieval and cross-host-attachment-recovery scenarios' own turn
each (the retried upload, the peer's message whose retrieval is cut and retried, and the phone's message
on host A of the colliding conversation), #1017, and the channel create-edit-archive
scenario's two pings (the first, run with the channel's original prompt, and the second, once a
session spawned after the edit is up), #1088 —
and a reset wrap-up turn each for the new-session scenario's own live child and the channel
create-edit-archive scenario's Reset session, #1088, and the attention-dot scenario's two turns — the
peer's ping in one chat, marking its row Unread, and the peer's allowed command in a second chat, ending
its held permission prompt, #1090, and the background-task-progress scenario's own turn (the prompt that
starts the subagent), #1107. Delete, archive-restore,
change-workspace, rename, save-as-channel,
list-archive-entry, two-host separation, the model-change scenario, the mute-channel round trip
(#1021), the second-host rename and unpair scenario (#1085), the host-backed diagnostic archive
scenario (#1252) and the two-host default-workspace and Archive scenario (#1086) spend no Claude
turns. Allow a few
minutes of wall clock; the run is subscription-covered.

The command must exit successfully and report at least `LIVE_MINIMUM` executed passing
tests, with no skips. `LIVE_MINIMUM` (`scripts/android-test-gate.py`) is the size of
`scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET` list, counted when the script loads, so adding a live
method means adding its list entry and nothing else. `test_live_curated_list_matches_the_runnable_methods`
(`scripts/test_android_test_gate.py`) asserts the list holds exactly the `InteractiveStreamE2ETest`
methods that are `@Test` and not `@Ignore`d, so a method missing from the list, or dropped from it in a
bad merge, reddens that test before a live run. Until 2026-10-01 the floor was a hand-kept running total,
one `LIVE_MINIMUM += N` line per ticket, and two tickets adding live methods at once always conflicted on
it (#1332 and #1337); git keeps that history. The gate's own test pads a copy of the recorded eight-case
fixture (`fixtures/default-workspace-live/588.xml`) with synthetic curated-method cases up to
`LIVE_MINIMUM` (#848). Shell cleanup preserves the original result and retains failure artifacts; a
clean XML report with a failing process status is not a passing gate.

`--tests` runs a chosen subset in place of the curated list, as a comma-separated
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#method` list, with a floor of one executed test:

```bash
python3 scripts/android-test-gate.py live --tests 'de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread'
```

The dispatcher uses it for `PYRY_REAL_CLAUDE_GATE_BASELINE_CMD`. When a live gate fails, it re-runs only
the failed methods on the same merged tree, and a method that passes there is a flake, not the branch's
failure. Methods that fail again are run on `main`, and a failure that reproduces there is inherited.
Before this, every flake in the full list sent the ticket back to the builder, which is how #1016 spent
three rework rounds on failures its branch did not cause.

For the full mechanics — relay URLs, the isolated `e2e-live` instance, prerequisites, and first-run
assumptions — see [Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay). The workflow
summary is in [README § Pre-ship gate](../README.md#pre-ship-gate); the README deliberately does not
restate scenario counts or turn costs — this document is the single authority for gate scope and cost.

## Live mode (rung 3, live relay)

`LIVE=1` runs a **curated set of 54 runnable rung-3 scenarios** — the real app on the emulator, a host `pyry`
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
`pixel2Api33Atd`, Pixel 2 / API 33 / `google-atd` arm64 with Play services/FCM. API 33 is the sole required
version for now; API 35 is deferred.

A 2026-10-04 connected run on the GM1911 with Android 16 failed before either
selected scenario started: Espresso raised `NoSuchMethodException` for
`android.hardware.input.InputManager.getInstance`. Direct adb interaction with
the same app worked. That driver failure supplies no product acceptance result.
The connected runner removed its debug installation during cleanup; reinstall
the debug APK before following up with `scripts/hands-on.sh`. The manual Send now
and normal queue-drain checks recorded in [PR #1673](https://github.com/pyrycode/pyrycode-mobile/pull/1673)
do not replace the curated live gate.

For the background-push proof, keep the production relay, real Claude, push-capable daemon and
FCM-configured `google-atd` device. #1694 changes shared peer/host-link diagnostics, so its acceptance
uses the full live suite. Require the named background-prompt testcase to be present without
failure/error/skip alongside the tested commit and executed/failed/skipped counts. A focused
identity test or scripted reconnect cannot prove FCM delivery; #1698's repaired-baseline pass is
also separate from candidate acceptance. See [Verification status](#verification-status) for the
candidate result and its unrelated focused rerun.

**Send now coverage (#1642).**
`InteractiveStreamE2ETest.interactiveTurn_sendQueuedNow_reachesRunningTurn` is in the curated
rung-3 suite. With a harness-owned held Bash call, it queues a marker through the phone and
taps Send now during the tool, then proves backlog clearing, exactly one delivered user row
after the tool result, and the original turn's final marker reply. The rung-4 twin is
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_sendQueuedNow_placesAfterToolResult`
(`send-now` scripted scenario). Late peer-delivery placement requires the daemon's optional
`sent_now` delivery flag, supplied by pyrycode#2748 / v0.31.2.

The dispatcher's 2026-10-04 full live run used
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live`, branch
`feature/1642` at `08536d2277`, merged with `origin/main` at `c7eb3ca79f`.
Its XML reports **54 executed, 53 passed, 1 failed, 0 skipped**; the named Send now method
is present and passed without failure/error/skip. This was a full-suite result, not a separate
focused Send now run. The report is
`2026-10-04T19-17-49-064Z_real-claude-gate_#1642.log` in the dispatcher repository's `logs/`.
The [initial gate report](https://github.com/pyrycode/pyrycode-mobile/issues/1642#issuecomment-5983893367)
failed on `interactiveTurn_questionAnswer_reachesTheAskingConversation`.
The [operator disposition on 2026-10-05](https://github.com/pyrycode/pyrycode-mobile/issues/1642#issuecomment-5987961154)
records the same lost-answer failure in three unchanged-main focused runs and the #1753
main-only base run, identifies daemon bug pyrycode#2785, and clears the live gate for documentation.
The full suite retains its one failure; manual USB evidence and the Android 16 driver failure
above are separate from this automated named pass.

**What it runs.** The current curated selector passes 54 runnable methods as a comma-separated
`class#method` list. Its source of truth is `scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET`, checked
against `LIVE_MINIMUM` in `scripts/android-test-gate.py`. The #481
`InteractiveStreamE2ETest#interactiveTurn_toolPrompt_rendersToolStepInThread` now rides this full
selector and checks the resolved row's accessible Done status. The restored
`InteractiveStreamE2ETest#interactiveTurn_archiveRestore_roundTripsListMembership` (#551/#1249)
uses the selected host's list toolbar Archive entry and proves the uniquely named discussion is on the
active list, leaves it after archive, appears in Archive, then returns to the active list after restore.
`InteractiveStreamE2ETest#interactiveTurn_twoHostsArchive_staysPerHost` (#1086/#1249) archives and
restores host A's chat through A's Archive while host B's active and archived ID sets stay unchanged.
Both wait for the restore success snackbar before leaving Archive. Their cleanup can recover newly
created chats even when setup fails before their IDs are captured. The two-host scenario also removes
the second pairing.
These are daemon round trips and spend no real Claude turns. The independent
`InteractiveStreamE2ETest#interactiveTurn_listArchiveEntry_opensArchived` (#740) still proves the toolbar
entry reaches Archive without creating a conversation.

`InteractiveStreamE2ETest#interactiveTurn_createEditArchiveChannel_readsPromptBack` (#1088/#1251,
extended #1342) starts with an empty Channels section, creates a channel in its host's default
working folder, and reads back its name and system prompt before and after editing. After Reset
session, a distinct real `pong` reply precedes the prompt-status check, so the check belongs to the
new session. It then empties the prompt from Edit channel, polls the host's own reading until it
comes back `null` — proving `submitChannelEdit` sent `null` rather than `""` — and opens Channel
info to check the System prompt section shows an empty box at "0 / 8192 bytes", the live proof that
desktop's clear rule (#1342) reaches the daemon and that the section reads what Edit channel wrote.
It archives from Edit channel, restores through the selected host's list-toolbar
Archive entry and its Channels tab, then finds the edited name back on the list. The test
restores the pre-existing channel fixtures and deletes its temporary conversations in `finally`.
Two Settings-dependent methods and two older workspace-switching methods remain ignored and
outside the curated selector.

A focused selection of the restored methods is:

```bash
python3 scripts/android-test-gate.py live --tests de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_archiveRestore_roundTripsListMembership,de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_twoHostsArchive_staysPerHost
```

The [#1249 full live gate](#verification-status) executed both selected methods; no separate focused
run is claimed.

The channel method's focused selection is:

```bash
python3 scripts/android-test-gate.py live --tests de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_createEditArchiveChannel_readsPromptBack
```

The [#1251 full live gate](#verification-status) includes this passing testcase. The separate
focused run executed 1, passed 1, failed 0 and skipped 0; its fresh XML names only this method.

The host-backed diagnostic archive selection is:

```bash
python3 scripts/android-test-gate.py live --tests de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts
```

`InteractiveStreamE2ETest#interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts` (#1252)
requests complete archives from both paired hosts by exact registry host ID while B is selected.
It checks that only A's archive contains a marker written to A's daemon log. It exercises no
Settings export or document picker, spends no Claude turn, and cleans up the marker and B pairing.
The builder's focused attempt stopped at Claude authentication preflight: 0 executed, 0 failed,
0 skipped, with no XML. The passing evidence below comes from the full 42-method live suite.

`InteractiveStreamE2ETest#interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend` (#1571,
described in full under [What rung 3 is made of](#what-rung-3-is-made-of)) opens a channel seeded dormant
— bound to a session this run's daemon never spawns — and waits for a reply stored in its on-disk history
log before the daemon started, with no pull and no send. Because the live suite cannot restart its daemon
mid-run and the main test daemon's idle eviction is off, this is the only form of the dormant-after-restart
state a live run can exercise; idle eviction is untested here. It spends no Claude turn. The seed and the
method run on every `LIVE=1` invocation, not behind a focused selection.

#1189 revised Create channel to open from an initially empty host Channels
section and use the daemon default; the folder-settings method keeps the repository
rename/archive and Archive restore round trip without a tree-row pencil. The create-channel test archives
the harness's seeded promoted channel for the empty-section check and restores it in `finally`.
#1190 moves `createChat()` and the save-as-channel setup through the host's Chats plus and confirmation;
the folder-use method now creates a chat before using the thread's picker. At #1190, the two-host defaults method also checked that
Chats creation used the daemon default independently of saved app defaults; #1249 later removed that
obsolete Settings proof and retained the host-isolated Archive round trip. The host-row-only #1087 method
is retired; its selector and the executed-test floor were lowered together.
The pre-#1193 forty-four-method suite spent **forty-four real claude turns** per run — five pings, from ping, create-workspace-folder,
new-session, the peer-started turn's own ping (#848), and the offline-read-reconcile scenario's own
ping (#850), plus #849's peer wait turn and its drained ping, #850's peer offline turn, #891's own
ping, #946's own ping, the inherited-effort and chosen-effort scenarios' own turns and the
remembered-effort-recall scenario's two turns (#545), the permission-held
running-tool scenario's own turn (#950), the stop-running-turn scenario's two turns — the
held-then-interrupted turn and its follow-up ping (#965), the operator-bypass-permission scenario's
two turns — the tool-free ping and the outside-workspace Read (#981), the permission-answer scenario's
three turns — the allowed command, its don't-ask-again repeat, and the peer-allowed prompt in the second
conversation, and the question-answer scenario's two turns — the phone's answer and the peer's answer
(#966); the reconnect-footer scenario's two turns — the ping before the cut-and-restore and the ping after
it, the reconnect-commands scenario's two turns — the ping and the compaction, and the background-task
scenario's one turn — the prompt that starts the task (#967); the background-push-turn-end scenario's own
turn — the command the peer allows once the phone is absent, and the background-push-prompt scenario's own
turn — the peer's held command (#955); the attachments-from-phone scenario's own
turn — the phone's attached message, and the claude-offered-file scenario's own turn — the phone's message
that runs `printf` and calls `send_file` (#1016); the peer-attachment scenario's own turn — the peer's
message naming the file, read back after a history reload (#1020); the markdown-link scenario's two
turns — claude writes a markdown note and replies with a link to it, then rewrites the note, so both the
reader's own Refresh (#1067) and the same tapped link show the new content and not the old (#1050); the
interrupted-upload scenario's own turn — the retried message, once the cut link is restored; the
interrupted-retrieval scenario's own turn — the peer's message naming the file whose retrieval is cut and
retried; and the cross-host-attachment-recovery scenario's own turn — the phone's message on host A of the
colliding conversation id #847 seeded (#1017); the channel create-edit-archive scenario's two pings — the
first, run with the channel's original prompt, and the second, once a session spawned after the edit is up
— plus a reset wrap-up turn for its own Reset session (#1088); the attention-dot scenario's two turns — the
peer's ping in one chat, marking its row Unread, and the peer's allowed command in a second chat, ending
its held permission prompt (#1090); the background-task-progress scenario's own turn — the prompt that
starts the subagent (#1076/#1107); the delete,
archive-restore, change-workspace, rename,
save-as-channel, list-archive-entry, two-host, model-change, mute-channel, second-host
rename-and-unpair, host-backed diagnostic archives, two-host defaults-and-Archive and peer-set
workspace label scenarios each add a
method, not a turn
(create/rename/delete/archive/restore/change-workspace/promote are daemon round-trips;
list-archive-entry is pure navigation with no daemon round-trip at all; two-host separation is pairing,
navigation, rename and link cycling, also daemon round-trips; mute-channel's promote, mute and unmute
are daemon round-trips too, #1021; pairing, rename and unpair are daemon round-trips or phone-local too,
#1085; a mute and two host-ID archive transfers are daemon round-trips too, #1252; folder creation, chat creation,
rename, archive and restore are daemon round-trips too, #1086; and setting and clearing a workspace label
from the peer are daemon round-trips too, #1089).
The full class also includes #950's `@Ignore`d elapsed-reading twin, which stays excluded from LIVE;
`interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`
rode `@Ignore`d until [#1020](https://github.com/pyrycode/pyrycode-mobile/issues/1020) let a stored
`message` entry's `attachment_ids` survive history replay and now rides LIVE with the rest. The
operator-bypass-permission method rides LIVE after
[#981](https://github.com/pyrycode/pyrycode-mobile/issues/981) fixed its missing reply and #1246
replaced its pending-duration assertion with reply-based settlement, and the
permission-answer and question-answer methods (#966) ride LIVE on the dedicated answer daemon described
above. `LIVE=1` is
**mutually exclusive with `DETERMINISTIC=1`**
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
  does **not** meter tokens. A LIVE run started by hand with no `CLAUDE_CODE_OAUTH_TOKEN` fetches the
  long-term login through `scripts/with-claude-login.sh` (2026-10-05), the same 1Password item the
  dispatcher's launcher uses, and falls back to the host's own login only when that fetch is unavailable.
  `python3 scripts/android-test-gate.py live` does the same when started by hand. A throwaway test daemon
  started by hand can borrow it too: `scripts/with-claude-login.sh pyry -pyry-name=<name> ...`.
- The live daemon is started with `-pyry-workdir=$HOME`. The current daemon confines the supervised
  Claude workdir to the runner's home for trust handling, so a temporary checkout outside that boundary
  is rejected before the live suite can start.
- **No relay binary needed** (the daemon dials the production relay; the `RELAY_BIN` preflight is skipped).
- The emulator needs outbound internet + DNS + a system-trusted TLS cert for the relay host. It reaches
  the public relay over its own NAT'd internet — **not** the `10.0.2.2` host alias, which is loopback-only.

Historical cost before #1193's exclusions: **forty-four real claude turns per run across forty-four curated methods** (ping + create-workspace-folder,
\#566 + new-session, #541 + the peer-started turn, #848 + the peer's wait turn and its drained ping,
\#849 + the offline-read-reconcile scenario's own ping and its peer's offline turn, #850 + the
status-sheet-running-model scenario's own ping, #891 + the footer-context-usage scenario's own ping,
\#946 + the inherited-effort and chosen-effort scenarios' own turns and the remembered-effort-recall
scenario's two turns, #545 + the
permission-held running-tool scenario's own turn, #950 + the stop-running-turn scenario's two turns — the
held-then-interrupted turn and its follow-up ping, #965 + the operator-bypass-permission scenario's two
turns — the tool-free ping and the outside-workspace Read, #981 + the permission-answer scenario's three
turns — the allowed command, its don't-ask-again repeat, and the peer-allowed prompt in the second
conversation, and the question-answer scenario's two turns — the phone's answer and the peer's answer,
#966 + the reconnect-footer scenario's two turns — the ping before the cut-and-restore and the ping after
it, the reconnect-commands scenario's two turns — the ping and the compaction, and the background-task
scenario's one turn — the prompt that starts the task, #967; the background-push-turn-end scenario's own
turn — the command the peer allows once the phone is absent, and the background-push-prompt scenario's
own turn — the peer's held command, #955; the attachments-from-phone scenario's own
turn — the phone's attached message, and the claude-offered-file scenario's own turn — the phone's message
that runs `printf` and calls `send_file`, #1016; the peer-attachment scenario's own turn — the peer's
message naming the file, read back after a history reload, #1020; the markdown-link scenario's two
turns — claude writes a note and replies with a link to it, then rewrites the note, #1050; the
interrupted-upload scenario's own turn — the retried message, the interrupted-retrieval scenario's own
turn — the peer's message whose retrieval is cut and retried, and the cross-host-attachment-recovery
scenario's own turn — the phone's message on host A of the colliding conversation, #1017; the channel
create-edit-archive scenario's two pings — the first, run with the channel's original prompt, and the
second, once a session spawned after the edit is up — plus a reset wrap-up turn for its own Reset
session, #1088; the attention-dot scenario's two turns — the peer's ping in one chat, marking its row
Unread, and the peer's allowed command in a second chat, ending its held permission prompt, #1090; the
background-task-progress scenario's own turn — the prompt that starts the subagent, #1076/#1107;
`/clear` spends
none beyond the ping that primes the session; delete, #554,
archive-restore, #551, change-workspace, #562, rename, #537, save-as-channel, #581, list-archive-entry,
\#740, two-host separation, #847, model change, the mute-channel round trip, #1021, the second-host
rename-and-unpair scenario, #1085, the host-backed diagnostic archive scenario, #1252, the two-host
default-workspace and Archive scenario, #1086, and
the peer-set workspace label scenario, #1089,
each spend
none — create/rename/delete/archive/restore/
change-workspace/promote are daemon round-trips, list-archive-entry is pure navigation, two-host
separation is pairing, navigation, rename and link cycling, also daemon round-trips, mute-channel's
promote, mute and unmute are daemon round-trips too, the second-host scenario's pairing, rename and
unpair are daemon round-trips or phone-local, the diagnostic scenario's mute and two host-ID archive transfers
are daemon round-trips too, the two-host defaults-and-Archive scenario's folder creation, chat
creation, rename, archive and restore are daemon round-trips too, the workspace add-rename-archive
scenario's folder creation, chat start, renames, archive and restore are daemon round-trips too, and
the peer-set workspace label scenario's rename_workspace sets and clears are daemon round-trips too),
a few minutes of wall clock, subscription-covered.

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
| `tool-then-text` (#1417) | reply text after a tool step renders below it | `tool-then-text.jsonl` | one |
| `tool-progress` (#950) | the status area's running-tool label adds claude's elapsed reading after a `tool_progress` heartbeat, then clears once the call's `tool_result` lands while the turn stays busy | `tool-progress-open.jsonl` + `tool-progress-result.jsonl` | **two** |
| `reconnect` (#476) | an in-flight reply **survives a mid-turn link drop** and renders exactly once | `reconnect-open.jsonl` + `reconnect-done.jsonl` | **two** |
| `offline-retry` (#1286) | actual Offline pill retries the same host and a new reply renders | `ping.jsonl` | one |
| `replay-order` (#477) | events produced **entirely while offline** replay **in order, each exactly once** | `replay-order-open.jsonl` + `replay-order.jsonl` | **two** (release on disconnect) |
| `refusal` (#1360) | a session-scoped `model_refusal_fallback` row offers "Switch back to Haiku" (the menu label, #1494); the tap writes `haiku` and the button disappears | `refusal.jsonl` | one |
| `mcp-failed` (#1457) | the failed-MCP-server pill (#1345) renders and tapping it opens Channel info on its MCP servers section | `mcp-failed.jsonl` | one |
| `context-overflow` (#1473) | the context notice and Compact pill (#1357) render after a `prompt_too_long` turn end, and tapping Compact reaches the daemon's child | `context-overflow.jsonl` | one |

`refusal` selects
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel`
and belongs to `python3 scripts/android-test-gate.py scripted-all`. No rung-3 twin exists or is planned:
real claude cannot be made to refuse on demand, so this scripted scenario is the end-to-end proof the
[model refusal row](knowledge/features/model-refusal-row.md#switch-back-1360) feature asks for.
`refusal.jsonl` is one claude line, `{"type":"system","subtype":"model_refusal_fallback",...}` with
`scope: "session"`, `original_model: "haiku"`, `fallback_model: "sonnet"`, then a short assistant reply —
`haiku` because the scripted daemon's `set_session_settings` accepts only the models fakeclaude's canned
`initialize` menu offers (`sonnet`, `haiku`), and the line carries no top-level `message` key, which would
stop it reaching the refusal handler ahead of the fold. The test waits for the turn's reply (the model menu
is held once fakeclaude has spawned, so tapping any earlier would race it), taps Switch back, waits for the
button to disappear, then reads a **fresh, non-held** `observeSessionSettings` value from the coordinator's
live repository and asserts it names `haiku` — the daemon round trip, not just the button's disappearance.

`offline-retry` selects
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_offlineRetryRestoresScriptedReply`
and belongs to `python3 scripts/android-test-gate.py scripted-all`. The daemon and
connection state stay real; only the recovered reply comes from `ping.jsonl`.
It shares the [Offline Retry proof boundary](#offline-retry-proof) with rung 3.
The [recorded full live result](#verification-status) establishes real-Claude
recovery; it does not claim a full scripted-suite pass.

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh                      # ping
DETERMINISTIC=1 SCENARIO=stream      PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # stream
DETERMINISTIC=1 SCENARIO=spinner     PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # spinner
DETERMINISTIC=1 SCENARIO=tool        PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool running→done
DETERMINISTIC=1 SCENARIO=tool-failed PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool failed
DETERMINISTIC=1 SCENARIO=tool-then-text PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # reply text below its tool step
DETERMINISTIC=1 SCENARIO=tool-progress PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # running-tool label elapsed → gone
DETERMINISTIC=1 SCENARIO=reconnect    PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # reconnect continuity
DETERMINISTIC=1 SCENARIO=replay-order PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # post-reconnect replay ordering
DETERMINISTIC=1 SCENARIO=refusal      PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # refusal switch-back
DETERMINISTIC=1 SCENARIO=mcp-failed   PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # failed MCP server pill
DETERMINISTIC=1 SCENARIO=context-overflow PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # context notice + Compact pill
```

`mcp-failed` selects
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_failedMcpServerPillOpensChannelInfo`
and belongs to `python3 scripts/android-test-gate.py scripted-all`. Fakeclaude's first `mcp_status`
answer, already enabled on every deterministic run by `PYRY_FAKE_CLAUDE_MCP_STATUS=1`, names
`pyry_mcp_test` as `failed`; the test waits for the [failed-MCP-server pill
(#1345)](knowledge/features/thread-top-overlay.md#the-failed-mcp-server-pill-1345) by its
`thread_mcp_server_failed` prefix, taps it, and asserts [Channel info's MCP servers
section](knowledge/features/channel-info-sheet.md#mcp-servers-section) is shown — not the failed name
itself, because the sheet's own later `mcp_status` ask gets fakeclaude's `connected` answer for
`pyry_mcp_fresh`. No live rung-3 twin is possible: daemon children spawned under `--strict-mcp-config`
load only `pyry_approve` and `pyry_files`, so [pyrycode
#2272](https://github.com/pyrycode/pyrycode/issues/2272) pins the real-Claude shape of a failed server on
the daemon side instead.

`context-overflow` selects
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon`
and belongs to `python3 scripts/android-test-gate.py scripted-all`. `context-overflow.jsonl`'s first
turn replays an assistant line then a `result` with `is_error` true and `terminal_reason`
`prompt_too_long`; the test waits for the status area's [context notice and Compact pill
(#1357)](knowledge/features/turn-outcome-indicator.md), then taps Compact, which calls
`ThreadViewModel.onComposerCommand` and sends `/compact` as an ordinary message (the daemon only
intercepts `/clear`). Fakeclaude's canned `initializeCommands` publishes `compact`, which is what keeps
the pill clickable; every later turn gets fakeclaude's built-in echo-plus-`success` reply, so the test
waits for a **second** exact-text `/compact` node — the phone's own sent row is the first, fakeclaude's
echo is the second — and only the echo proves the command reached the daemon's child. It then asserts
the context notice is gone. No live rung-3 twin is possible: real Claude cannot be driven to
`prompt_too_long` on demand.

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

**`tool-then-text` (#1417)** — reply text written after a tool step must render **below** that step, proving
the per-segment reply order from #1350 through the real daemon, relay and app. `tool-then-text.jsonl` is a
single raw fragment: an `assistant` line (id `ttt-1`) holding text with the marker `foxtrot`, an `assistant`
line sharing id `ttt-1` with a `Bash` `tool_use` whose input is `{}` (empty input keeps the collapsed row's
lead on the tool name rather than a `command`, #1315), the correlated non-error `tool_result`, then an
`assistant` line with a new id `ttt-2` and `stop_reason: end_turn` holding text with the marker `zulu`. The
two markers don't occur in the seeded channel name, the prompt, `Bash` or each other (the #431 false-green
lesson). The test waits for both markers and the `Bash` row to render and the running-tool/thinking CDs to
clear, then asserts by `boundsInRoot` that the `foxtrot` node sits above the tool row and the `zulu` node
sits below it, and that the two markers land in different nodes.

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

**Current live verification — 2026-10-05 (#1775).** The dispatcher ran the fresh full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` on branch `feature/1775`
at `b496a99e6f`, merged with `origin/main` at `9563beab61` (tested mobile revision
`80c40ae883b081abd46afd2c818e0d350d26b7f0`): **55 executed, 54 passed, 1 failed, 0 errors,
0 skipped**, exit 1. The inspected full-suite XML contains exactly one
`InteractiveStreamE2ETest.interactiveTurn_hostSystemPrompt_editsResetsAndCancels` testcase with
no failure, error or skip: the method ran and passed in the full suite. Daemon revision was
`a438db4b9146620b74b0f2a305c3e23a2d144d6a`, with the pyrycode#2768 handler prerequisite satisfied;
Claude Code was 2.1.280.

The sole failure, `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, passed on the
same merged tree's focused rerun: **1 executed, 1 passed, 0 failed, 0 errors, 0 skipped**. The dispatcher
accepted PASS after rerun; this is not a second passing full-suite run. See the
[dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1775#issuecomment-5989909305).
The inspected reports are `2026-10-05T06-56-43-612Z_real-claude-gate_#1775.log` and
`2026-10-05T06-56-43-612Z_real-claude-gate-rerun_#1775.log` under the dispatcher repository's `logs/`,
with revisions in the adjacent `.stderr.log`. The builder's earlier focused host-prompt pass is
separate repair evidence (1 executed/passed, 0 failed/skipped), not the source of these full-suite counts.

**Previous live verification — 2026-10-05 (#1703).** After verification, the dispatcher ran the fresh full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1703`
at `930be240bcd64759a9462722e3cc9a757946e29c`, merged with `origin/main` at `0d2aecc85c`:
**53 executed, 53 passed, 0 failed, 0 errors, 0 skipped**, exit 0. The inspected XML contains exactly
one `InteractiveStreamE2ETest#interactiveTurn_questionAnswer_reachesTheAskingConversation` testcase
with no failure, error or skip: this method ran and passed in the full suite, not a focused rerun.
Daemon revision was `65df98859f32e49ba59a42c4446d650b7625cf62`, Claude 2.1.280. See the
[dispatcher gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1703#issuecomment-5987043605).
The retained report is `2026-10-05T02-12-18-576Z_real-claude-gate_#1703.log` under the dispatcher
repository's `logs/`, with revisions in its adjacent `.stderr.log`.

The earlier diagnostic capture on mobile `55e6b11f477fa25ce1e5cb824b0b6ac4d6f988c4`, merged with main
`6bd48bc3cb52deb744c7522ba019bc2dffba8903`, used the same daemon and Claude revisions:
**53 executed, 51 passed, 2 failed, 0 skipped**. This method failed at `AwaitPhoneDismissal` with
the peer session still open. Main's comparison ran this method alone and failed (**1 executed,
1 failed, 0 skipped**) with an unnamed timeout. Those failures are diagnosis evidence, not repair
acceptance. The sanitized [operation and reproduction record](https://github.com/pyrycode/pyrycode-mobile/issues/1703#issuecomment-5986017015)
distinguishes the observed wait from inferred historical tap occlusion; the removed gate worktree
and phone logcat prevent coordinate confirmation. Reports are
`2026-10-04T23-15-21-659Z_real-claude-gate_#1703.stderr.log` and its `real-claude-gate-base` counterpart.

**Previous live verification — 2026-10-04 (#1666).** The dispatcher ran the fresh full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1666`
at `1628e840ea`, merged with `origin/main` at `6bd48bc3cb` in a detached worktree:
**53 executed, 52 passed, 1 failed, 0 skipped**, exit 1. The fresh full-suite XML explicitly
contains `InteractiveStreamE2ETest.interactiveTurn_newSession_rendersSessionBoundaryDelimiter`
with no failure, error or skip: the named #541 method ran and passed, reaching Reset session
through the new header Actions menu. No separate focused new-session run was required or performed.
The sole failure, `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, passed on a
same-tree rerun: **1 executed, 1 passed, 0 failed, 0 skipped**. The dispatcher accepted PASS after
rerun; this is not a second full-suite pass. See the
[dispatcher gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1666#issuecomment-5985428700).
The inspected reports are `2026-10-04T22-27-14-344Z_real-claude-gate_#1666.log` and
`2026-10-04T22-27-14-344Z_real-claude-gate-rerun_#1666.log` under the dispatcher repository’s `logs/`.

**Previous live verification — 2026-10-04 (#1637).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against
`feature/1637` at `6b6d9fa51c`, merged with `origin/main` at `5efa2a90d4` in a detached
worktree (19 commits behind before merge): **53 executed, 53 passed, 0 failed,
0 errors, 0 skipped**, exit 0. The fresh XML contains exactly one passing testcase
for each of `InteractiveStreamE2ETest#interactiveTurn_twoHostsCollidingConversationId_stayPerHost`
and `InteractiveStreamE2ETest#interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart`,
without failure, error or skip. Both ran and passed in this full suite; no separate
focused live run is claimed. See the
[dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1637#issuecomment-5977974713).
The retained report is `2026-10-04T07-53-48-669Z_real-claude-gate_#1637.log` under the
dispatcher repository's `logs/`, with diagnostics in the adjacent `.stderr.log`.

The original #1581 failure maps to the **code-field lookup after Paste** in
`pairHostByCode` at `70aee9838d`, correcting the issue body's paste-link interpretation.
Scanner disposal blocked main on a pending CameraX provider future; a rerun can
pass when initialization completes before exit. The
[causal explanation](https://github.com/pyrycode/pyrycode-mobile/issues/1637#issuecomment-5972778396)
and [controlled red/green evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1637#issuecomment-5972754369)
show the real-device form opening with initialization still pending after repair
(red 1 executed / 1 failed / 0 skipped; green 1 / 0 / 0). MainActivity was
RESUMED/focused before Paste. Post-cleanup launcher focus does not establish the
activity state during the stall, and the missing historical artifact does not
allow recovery of that run's provider state. See
[scanner testing](knowledge/features/scanner-screen-edge-cases-and-testing.md#focused-verification).

This candidate's earlier full suite executed 53, failed 20 and skipped 0, with
collision passing but offered-file failing. The
[rework attribution](https://github.com/pyrycode/pyrycode-mobile/issues/1637#issuecomment-5977506653)
links those peer authentication failures to the shared token-bound identity defect
repaired by #1686/#1698. Reduced base comparisons changed scenario order and could
misattribute that defect. Their repaired-baseline passes supported the diagnosis;
the fresh candidate full-suite pass above supplies this ticket's acceptance.

**Previous live verification — 2026-10-04 (#1641).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1641`
at `22db59699c`, merged with `origin/main` at `7129f39855` in a detached worktree (0 commits behind):
**53 executed, 52 passed, 1 failed, 0 errors, 0 skipped**, exit 1. The fresh XML contains
`InteractiveStreamE2ETest.interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy` without a
failure, error or skip: this retained running-turn guard executed and passed in the full suite.
The sole failure, `interactiveTurn_questionAnswer_reachesTheAskingConversation`, passed on a same-tree
focused rerun (**1 executed, 1 passed, 0 failed/errors/skipped**). The dispatcher accepted PASS after
rerun; this is not a second passing full suite or a focused run of the status-band method. See the
[dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1641#issuecomment-5977652769).
The XML reports are `2026-10-04T07-00-33-585Z_real-claude-gate_#1641.log` and
`2026-10-04T07-00-33-585Z_real-claude-gate-rerun_#1641.log` under the dispatcher repository's `logs/`.
Controlled Sending/Waiting transitions remain rung-2 proof in `ScriptedLocalSendTest` through
`ScriptedThreadHarness`, independently holding acknowledgement and first turn-state; no new live
scenario or transient-label assertion was added. See [Thinking indicator § Working and
stalled](knowledge/features/thinking-indicator.md#working-and-stalled-1311).

**Previous live verification — 2026-10-04 (#1702).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against repaired PR #1718,
`feature/1702` at `addf5ef693e23f6810191a61b86fcebe1af842b2`, merged with `origin/main` at
`7129f39855` in a detached worktree (0 commits behind before merge): **53 executed, 53 passed,
0 failed, 0 errors, 0 skipped**, exit 0. The fresh compact XML explicitly contains
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_questionAnswer_reachesTheAskingConversation`
with no failure, error or skip: the named method executed and passed in this full suite, preserving both
phone and peer round-trips. This is full-suite evidence, not a focused rerun. See the
[dispatcher gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1702#issuecomment-5977521066).
The retained XML report is `2026-10-04T06-47-10-320Z_real-claude-gate_#1702.log` under the dispatcher
repository's `logs/`, with diagnostics in the adjacent `.stderr.log`. The repaired Continue wait's
`ComposeTimeoutException` on unrepaired trees is distinct from the coroutine
`TimeoutCancellationException` in #1637's later offered-file live failure; the short-viewport
regression proves the lazy-row mechanism (see
[Question batch modal § Testing](knowledge/features/question-batch-modal.md#testing)).

**Previous live verification — 2026-10-04 (#1694).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1694`
at `6edcce71cef99c2ce7e24c3cff56bbf9cd9dfc27`, merged with `origin/main` at `fb2ca8f1a3`
in a detached worktree (0 commits behind before merge): **53 executed, 52 passed, 1 failed,
0 errors, 0 skipped**, exit 1. The fresh XML explicitly contains
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`
with no failure, error or skip: this method ran and passed in the full suite, including both peer
opens, real background FCM wake, exactly one alert and unchanged `postTime` after the second reconnect.
The sole failure, `interactiveTurn_questionAnswer_reachesTheAskingConversation`, passed on a same-tree
focused rerun (**1 executed, 1 passed, 0 failed/errors/skipped**). The dispatcher accepted PASS after
rerun; this is full-suite proof of the background-prompt scenario, not a separate focused run of it
or a second passing full suite. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1694#issuecomment-5976866278).
The retained reports are `2026-10-04T05-01-10-594Z_real-claude-gate_#1694.log` and
`2026-10-04T05-01-10-594Z_real-claude-gate-rerun_#1694.log` under the dispatcher repository's `logs/`.
The earlier #1698 repaired-baseline full suite (53 executed, 1 unrelated failure, 0 skipped, this
method passed) supported the identity diagnosis; it did not establish this candidate's acceptance.

**Previous live verification — 2026-10-04 (#1697).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1697`
at `b1a11e755f`, merged with `origin/main` at `d54d7d9cad` in a detached worktree (0 commits
behind before merge): **53 executed, 53 passed, 0 failed, 0 errors, 0 skipped**, exit 0.
The fresh XML contains exactly one passing
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`
testcase, with no failure, error or skip. The prerequisite #1698/#1686 identity repairs restored
the scenario unchanged from failing base `acf0f6591c`; #1697 required no additional code repair,
longer timeout or weakened assertion. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1697#issuecomment-5976504334).
The retained XML report is `2026-10-04T04-03-12-661Z_real-claude-gate_#1697.log` under the dispatcher
repository's `logs/`, with diagnostics in the adjacent `.stderr.log`.

The retained [#1686 full-suite proof](https://github.com/pyrycode/pyrycode-mobile/issues/1686#issuecomment-5976044606)
also contains exactly one passing phone-attachment testcase: **53 executed, 53 passed, 0 failed,
0 errors, 0 skipped**, exit 0, in `2026-10-04T02-57-03-837Z_real-claude-gate_#1686.log`.
Both are entirely passing full suites. In contrast, #1698's earlier full suite passed this
attachment method but had **53 executed, 52 passed, 1 unrelated failure, 0 errors, 0 skipped**;
its focused question-answer rerun is recorded separately below and is not a second full-suite pass.
No separate focused phone-attachment run is claimed.

**Previous live verification — 2026-10-04 (#1683).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1683`
at `b10c5a6fe02b89f4d55e0da14b4e0c0360e9390b`, merged with `origin/main` at `d54d7d9cad`
in a detached worktree (6 commits behind before merge): **53 executed, 53 passed, 0 failed,
0 skipped**, exit 0. The fresh XML explicitly includes
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`
with no failure, error or skip: the method ran and passed, including both same-pairing peer opens,
the permission-wait reading with no running-tool label, the running Bash label after real peer
approval, and its disappearance after turn completion. This is full-suite evidence; no separate
focused live run was required or claimed. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1683#issuecomment-5976372697).
The retained XML report is
`/Users/juhanailmoniemi/Workspace/Projects/pyrycode-mobile-agents/logs/2026-10-04T03-40-35-710Z_real-claude-gate_#1683.log`,
with diagnostics in the adjacent `2026-10-04T03-40-35-710Z_real-claude-gate_#1683.stderr.log`.

The original #1631/#1637 branch/base stderr reports show coroutine timeouts, not status assertion
failures. Retained daemon logs `pyry-e2e.AOLbIc`, `pyry-e2e.JKsJv3`, `pyry-e2e.Cf5gl9` and
`pyry-e2e.tWgsL4` contain respectively 102/84/102/90 static-key mismatch rejections: sequential peers
rotated the static key for an already-bound token. #1698 repaired that identity custody; #1683
protects it within this scenario. The repeated 30-second failures are consistent with peer opening.
The separate #1631 base 90-second timeout matches `awaitPermissionModal`, but its underlying
delivery/turn cause remains unestablished. Historical full #1698/#1696 XML each records this method
as passed in a run with **53 executed, 1 unrelated failure, 0 skipped**; #1698's retained daemon
log has zero key-mismatch rejections. Those observations support the setup diagnosis and are
distinct from the fresh candidate pass above; they do not establish a status-rendering defect.

**Previous live verification — 2026-10-04 (#1686).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1686`
at `5aa6f23b79`, merged with `origin/main` at `ad547c6425` in a detached worktree (0 commits behind
before merge): **53 executed, 53 passed, 0 failed, 0 skipped**, exit 0. The fresh XML includes
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`
with no failure, error or skip: the named method ran and passed, including A-only visibility, checked
grant retention and cleared arm after navigation, successful phone-approved Bash/output, prompt-free
repeat, and B's independent prompt closing after peer approval. This is full-suite evidence, not a
separate focused run. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1686#issuecomment-5976044606).
The retained XML report is `2026-10-04T02-57-03-837Z_real-claude-gate_#1686.log` under the dispatcher
repository's `logs/`, with diagnostics in the adjacent `.stderr.log`.

**Previous live verification — 2026-10-04 (#1692).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1692`
at `b5e8174949c9cb3c652c0f6cc84301fa31075db5`, merged with `origin/main` at `ad547c6425`
in a detached worktree (0 commits behind before merge): **53 executed, 53 passed, 0 failed,
0 skipped**, exit 0. The fresh XML explicitly includes
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`
with no failure, error or skip: the named method ran and passed in this full suite. Both same-token
peer opens and the unchanged ping-cache, permission-held completion with B open, phone-recorded unread
completion before reconnect, absent held reply in A's cache after reconnect, and four exactly-once
chronological recovered rows passed. This was full-suite evidence, not a separate focused run.
See the [gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1692#issuecomment-5975965445).
The retained XML report is `2026-10-04T02-43-28-151Z_real-claude-gate_#1692.log` under the
dispatcher repository's `logs/`, with diagnostics in the adjacent `.stderr.log`.

**Previous live verification — 2026-10-04 (#1696).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1696`
at `1ce59c32dc`, merged with `origin/main` at `d63e304b8e` (0 commits behind before merge):
**53 executed, 52 passed, 1 failed, 0 skipped**, exit 1. The fresh full-suite XML contains
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`
with no failure, error or skip. Thus both peer opens and the unchanged permission, cancellation,
Stop-removal, same-thread ping, second-turn-end and absent-held-reply assertions passed.
The sole failure, `interactiveTurn_questionAnswer_reachesTheAskingConversation`, passed on a focused
same-tree rerun (**1 executed, 1 passed, 0 failed, 0 skipped**). The dispatcher accepted the gate as
PASS after rerun; this is neither a second full-suite pass nor a focused Stop run. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1696#issuecomment-5975218644).
The retained XML reports are `2026-10-04T00-46-36-696Z_real-claude-gate_#1696.log` and
`2026-10-04T00-46-36-696Z_real-claude-gate-rerun_#1696.log` under the dispatcher repository's `logs/`.

**Previous live verification — 2026-10-04 (#1698).** The dispatcher ran the full
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1698`
at `52b646ac8f`, merged with `origin/main` at `acf0f6591c`: **53 executed, 52 passed, 1 failed,
0 skipped**, exit 1. The fresh full-suite XML explicitly records
`InteractiveStreamE2ETest#interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` as passed,
with no failure, error or skip. Its real peer upload and Claude turn, cache-cleared restart, history
pull, exactly-one filename row and multi-chunk open/save digest checks remain unchanged. The sole
failure, `interactiveTurn_questionAnswer_reachesTheAskingConversation`, passed on a focused same-tree
rerun (**1 executed, 0 failed, 0 skipped**); the dispatcher accepted the gate as PASS after rerun.
This does not claim a second full-suite pass or a separate focused attachment run. See the
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1698#issuecomment-5974876285).
The retained reports are `2026-10-03T23-52-08-061Z_real-claude-gate_#1698.log` and
`2026-10-03T23-52-08-061Z_real-claude-gate-rerun_#1698.log` under the dispatcher repository's `logs/`.
The full run's stderr identifies retained daemon diagnostics in `pyry-e2e.shYptv/daemon.log` under
`/private/var/folders/k0/gc07w9ws319b07n0plnw6y8r0000gn/T/`: **0**
`v2.handshake.reject.static_key_mismatch` and **0** `bound_to_other_key` occurrences, compared with
**102** of each in the earlier #1631 branch log (`pyry-e2e.AOLbIc/daemon.log`) and **84** of each
in its base log (`pyry-e2e.JKsJv3/daemon.log`). The passing attachment method and disappearance of
these rejections support the token-bound identity diagnosis without relaxing daemon authentication.

**Previous live verification — 2026-10-03 (#1581).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live --tests` with five names —
the new `interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk` and
`interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` from the PR's `## Live tests`, plus three
always-run methods (`interactiveTurn_pingPrompt_streamsPingReplyIntoThread`,
`interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`,
`interactiveTurn_twoHostsCollidingConversationId_stayPerHost`) — against `feature/1581` at
`70aee9838d`, merged with `origin/main` at `985ff3ca64` in a detached worktree (0 commits behind before
the merge): **5 executed, 4 passed, 1 failed, 0 skipped**, exit 1, wall clock 1488.5s. This is a selected
run, not full-suite evidence; the new method itself is the subject of this ticket's AC-3. The fresh XML
has a passing testcase for the new method with no failure or error — this is its first live run. The one
failure, `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, failed once and passed on a
re-run of the same merged tree, so the dispatcher treated it as a suite flake rather than this branch's
and moved the ticket on. `LIVE_MINIMUM` rose from 51 to 52, since it is counted from the curated LIVE
list and the ticket added one entry.

**Previous live verification — 2026-10-03 (#1571).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1571` at
`f4248cacee`, merged with `origin/main` at `f4c6598ad6` in a detached worktree (43 commits behind before
the merge): **52 executed, 52 passed, 0 failed, 0 skipped**, exit 0, wall clock 918.9s. This is full-suite
evidence (full suite: 50 merges had landed since the last passing full run); no separate focused live run
is claimed. The fresh XML has a passing testcase for the new method,
`interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend` (AC 2 and 3), with no failure or error
— this is the method's first live run. An earlier attempt on this branch produced no readable test events
(0/0/0/0, exit 1, wall clock 3.6s) and was treated as an unreliable gate result rather than a pass or
fail; this run superseded it. `LIVE_MINIMUM` rose from 51 to 52, since it is counted from the curated LIVE
list and the ticket added one entry.

**Previous live verification — 2026-10-02 (#1460).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1460` at
`5d3187e015`, merged with `origin/main` at `a4d536d9ad` in a detached worktree (0 commits behind before
the merge): **51 executed, 50 passed, 1 failed, 0 skipped**, exit 1, wall clock 1409.7s. This is
full-suite evidence; no separate focused live run is claimed. The one failure,
`interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, failed once and passed when
re-run on the same merged tree, so the dispatcher treated it as a suite flake rather than this branch's.
The fresh XML has a passing testcase for the new method,
`interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip` (see [Follow-ups to
ticket](#follow-ups-to-ticket) above), with no failure or error. `LIVE_MINIMUM` rose from 50 to 51, since
it is counted from the curated LIVE list and the ticket added one entry.

**Previous live verification — 2026-10-02 (#1456).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1456` at
`af31cd6ecd`, merged with `origin/main` at `cad0672435` in a detached worktree (8 commits behind before
the merge): **50 executed, 50 passed, 0 failed, 0 skipped**, exit 0, wall clock 676.1s. This is
full-suite evidence; no separate focused live run is claimed. `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`
has a passing testcase in the fresh XML with no failures or errors in the suite. The ticket added no new
live method and changed no production code — only step 5's wait, which now goes through
`awaitPingReplyNamingLayer` (see § *Coverage — hardened* below) — so this run is the first live exercise
of that wrapper's passing path; it was not exercised by the diagnosis's own `PingReplyDiagnosisTest`,
which is JVM-only and never reaches a device. `LIVE_MINIMUM` stayed at 50.

**Previous live verification — 2026-10-02 (#1352).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1352` at
`4251829d37`, merged with `origin/main` at `8dc5a6b483` in a detached worktree (16 commits behind before
the merge): **50 executed, 50 passed, 0 failed, 0 skipped**, exit 0, wall clock 716.6s. This is full-suite
evidence, requested as `## Live tests: all` because removing the opening history ask could affect any
scenario that expected rows to load on open, not only the three the ticket named. The fresh XML has
passing testcases for all three: `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` (whose KDoc
and step comments now say the peer's prompt arrives via the reconnect replay rather than the removed
reconnect history re-ask — see § *offline-read-reconcile* above), and
`interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` and
`interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` (both of which now perform a
`pullForOlderHistory` gesture after opening chat X, since their rows can only come from history once the
thread cache is cleared and nothing asks for history by itself any more). `LIVE_MINIMUM` stayed at 50;
this ticket added no new live method and renamed none in the curated list.

**Previous live verification — 2026-10-02 (#1369).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1369` at
`6e79da4f`, merged with `origin/main` at `b6c06182` in a detached worktree (11 commits behind before
the merge): **50 executed, 50 passed, 0 failed, 0 skipped**, exit 0, wall clock 722.8s. This is
full-suite evidence; no separate focused live run is claimed. The fresh XML has a passing testcase for
`interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`, restored to the curated LIVE list
after #1305 isolated it (`@Ignore`, dropped from the list) pending this ticket. #1369 added no production
code — #1351 had already fixed the cause, keeping a held `message_id` row's attachments through the
daemon's push of the sender's own delivered message — so this run is the first live confirmation of that
fix for the cross-host collision scenario. `LIVE_MINIMUM` is counted from the curated list since
2026-10-01, so restoring the method's list entry raised the floor by one on its own; no separate edit to
`android-test-gate.py` or its test was needed. See
[question-batch-modal.md's live rung-3 note](features/question-batch-modal.md#testing) for the earlier
isolation chain, from the #966 live run that first caught the regression through #1305's isolation.

**Previous live verification — 2026-10-01 (#1410).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` against `feature/1410` at
`92ba45dd43`, merged with `origin/main` at `ab368c3bf2` in a detached worktree (9 commits behind before
the merge): **48 executed, 47 passed, 1 failed, 0 skipped**, exit 1, wall clock 1146.3s. This is
full-suite evidence; no separate focused live run is claimed. `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`
failed once and passed when re-run on the same merged tree, so the dispatcher treated it as a suite
flake rather than this branch's failure — see the
[re-run evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1410#issuecomment-5942399058). The
fresh XML has passing testcases for the new method,
`interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`, and for
`interactiveTurn_pingPrompt_footerShowsContextUsage`, both required by #1410's AC-3, plus the three
reconnect-with-open-thread methods the new ask also reaches:
`interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`,
`interactiveTurn_reconnect_slashCommandsAndCompactStillWork` and
`interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`. See the
[dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1410#issuecomment-5942043165).

**Previous live verification — 2026-10-01 (#1342).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live --tests ...` against
`feature/1342` at `bf964f0c39`, merged with `origin/main` at `599aa84bfd` in a detached worktree
(27 commits behind before the merge): **8 executed, 8 passed, 0 failed, 0 skipped**, exit 0, wall
clock 392.1s. The selection named the five methods in PR #1407's `## Live tests`
(`interactiveTurn_createEditArchiveChannel_readsPromptBack`,
`interactiveTurn_deleteConversation_removesFromListAndClosesThread`,
`interactiveTurn_twoHostsArchive_staysPerHost`, `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`,
`interactiveTurn_markdownLink_opensLiveNoteInReader`) plus three always-run methods — not a full-suite
run. The fresh XML has a passing testcase for the extended
`interactiveTurn_createEditArchiveChannel_readsPromptBack`, closing AC-5 of #1342: it now empties the
prompt in Edit channel, waits for the host reading to come back `null`, then opens Channel info and
checks for an empty box at "0 / 8192 bytes". See the [dispatcher
evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1342#issuecomment-5939773632).

**Previous live verification — 2026-10-01 (#1337).** The dispatcher ran a focused
`python3 scripts/android-test-gate.py live --tests ...` selection against `feature/1337` at
`62ed2d1072`, merged with `origin/main` at `a8bb98ca56` (0 commits behind before the merge): **7
executed, 7 passed, 0 failed, 0 skipped**, exit 0, wall clock 404.8s. The selection named the four
methods in PR #1406's `## Live tests` (`interactiveTurn_permissionPrompts_heldPerConversation`,
`interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`,
`interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`,
`interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`) plus three always-run methods — not a
full-suite run. The fresh XML has a passing testcase for
`interactiveTurn_permissionPrompts_heldPerConversation`, closing AC-4 of
[#1337](specs/architecture/1337-hold-every-outstanding-prompt.md). See the [dispatcher
evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1337#issuecomment-5938993775).

**Previous live verification — 2026-10-01 (#1332).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=1200 python3 scripts/android-test-gate.py live` against `feature/1332` at
`cc94a05bbc`, merged with `origin/main` at `18ea26526a` (0 commits behind before the merge):
**41 executed, 41 passed, 0 failed, 0 skipped**, exit 0, wall clock 518.8s. This is full-suite evidence;
no separate focused live run is claimed. `LIVE_MINIMUM` rose from 40 to 41 with this ticket's new method,
`interactiveTurn_archiveTwoChats_listsSecondArchivedFirst`, joining the curated selector (see
[Pre-ship gate](#pre-ship-gate)); this is the method's first live run. An earlier attempt reported 38
executed and did not include the new method, because the curated selector and `LIVE_MINIMUM` had not yet
been updated for it; that gap was found in review and fixed before this run. See the
[dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1332#issuecomment-5932798343).

**Previous live verification — 2026-10-01 (#1394).** The dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=1200 python3 scripts/android-test-gate.py live` against `feature/1394` at
`ba11f50add`, merged with `origin/main` at `2e1e2e46cd` (0 commits behind before the merge):
**40 executed, 40 passed, 0 failed, 0 skipped**, exit 0, wall clock 527.1s. This is full-suite evidence;
no separate focused live run is claimed. `LIVE_MINIMUM` rose from 39 to 40 with this ticket's new method,
`interactiveTurn_scannerConfirm_waitsForHostThenOpensList`, joining the curated selector (see
[Pre-ship gate](#pre-ship-gate)); this is the method's first live run. An earlier attempt on PR #1424
executed 39 and did not include the new method, because the curated selector and `LIVE_MINIMUM` had not
yet been updated for it; that gap was found in review and fixed in commit `ba11f50a` before this run.

**Previous live verification — 2026-10-01 (#1311).** The dispatcher ran the full
`python3 scripts/android-test-gate.py live` suite against `feature/1311` at `3fae3a6dc2`,
merged with `origin/main` at `a61c5eb4b1`: **39 executed, 39 passed, 0 failed, 0 skipped**,
exit 0, wall clock 520.7s. The fresh XML contains a passing, unskipped
`InteractiveStreamE2ETest.interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy` testcase —
this is the method's first live run, and `LIVE_MINIMUM` rose from 38 to 39 with its addition to the
curated selector (see [Pre-ship gate](#pre-ship-gate)). This is full-suite evidence; no separate
focused live run is claimed. An earlier attempt on this branch (`32609a7535` merged with
`01d81756d9`) executed 38 and did not include this method, because the curated selector and
`LIVE_MINIMUM` had not yet been updated for it; that gap was found and fixed before this run. See the
[earlier-run evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1311#issuecomment-5928693537)
(38 executed, the gap not yet fixed) and the
[fresh dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1311#issuecomment-5929813330)
for this 39/39 PASS.

**Previous live verification — 2026-09-30 (#1286).** The dispatcher ran the full
`python3 scripts/android-test-gate.py live` suite against `feature/1286` at
`a6fec3f720`, merged with `origin/main` at `1119eb051d`: **44 executed, 44 passed,
0 failed, 0 skipped**, exit 0. The fresh XML contains a passing, unskipped
`InteractiveStreamE2ETest.interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`
testcase. This is full-suite evidence; no separate focused live run is claimed.
See the [dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1286#issuecomment-5914205992).

**Earlier live verification — 2026-09-30 (#1208).** The dispatcher ran the full
`python3 scripts/android-test-gate.py live` suite against `feature/1208` at `5c7283e264`,
merged with `origin/main` at `ad946824a3`: **43 executed, 43 passed, 0 failed, 0 skipped**.
The fresh XML includes a passing
`InteractiveStreamE2ETest.interactiveTurn_toolPrompt_rendersToolStepInThread` testcase.
This is full-suite evidence, with no separate focused live run. See the
[dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1208#issuecomment-5900517078).

**Current live verification — 2026-09-29 (#1252).** The dispatcher ran
`python3 scripts/android-test-gate.py live` against `feature/1252` at `56f9995c4b`,
merged with `origin/main` at `5e8128cde9`: **42 executed, 42 passed, 0 failed, 0 skipped**,
exit 0. The fresh full-suite XML contains a passing
`interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts` testcase. This is full-suite
evidence, not a separate focused run. The builder's focused attempt stopped before execution
because Claude authentication was unavailable (0 executed, 0 failed, 0 skipped; no XML).
See the [dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1252#issuecomment-5888293250).

**Current live verification — 2026-09-29 (#1251).** The dispatcher ran
`python3 scripts/android-test-gate.py live` against `feature/1251` at `f81261c6af`,
merged with `origin/main` at `85186aa1ac`: **41 executed, 41 passed, 0 failed, 0 skipped**,
exit 0, against the curated 41-method selector and `LIVE_MINIMUM = 41`. The fresh XML
contains a passing `interactiveTurn_createEditArchiveChannel_readsPromptBack` testcase. This
is full-suite evidence, including the channel method. A separate focused run on `feature/1251`
at `b84fdfbd` executed 1, passed 1, failed 0 and skipped 0, with exit 0 and fresh XML naming
only that method. See the [full-suite evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1251#issuecomment-5886640553)
and [focused-run evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1251#issuecomment-5886827815).

**Previous live verification — 2026-09-29 (#1250).** The dispatcher ran
`python3 scripts/android-test-gate.py live` against `feature/1250` at `97b0b734d8`,
merged with `origin/main` at `9eefb829be`: **40 executed, 40 passed, 0 failed, 0 skipped**,
exit 0, against the curated 40-method selector and `LIVE_MINIMUM = 40`. The retired
peer workspace-label method was absent. See the [issue's dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1250)
for the recorded run and report path.

**Previous live verification — 2026-09-28 (#1249).** The dispatcher ran the full
`python3 scripts/android-test-gate.py live` gate against `feature/1249` at `35f0140c5d`, merged with
`origin/main` at `4eb735654c`: **40 executed, 40 passed, 0 failed, 0 skipped**, exit 0, against
`LIVE_MINIMUM = 40`. The fresh XML has passing testcases for both
`interactiveTurn_archiveRestore_roundTripsListMembership` and
`interactiveTurn_twoHostsArchive_staysPerHost`. This is full-suite evidence, including those two methods,
not a separate focused run. See the [dispatcher evidence comment](https://github.com/pyrycode/pyrycode-mobile/issues/1249#issuecomment-5872787531)
and its `build/dispatcher-tests/live-*/dispatcher.xml` report; the comment records the tested revisions
and the dispatcher's retained report path.

**Previous live verification — 2026-09-28 (#1246).** The dispatcher ran
`python3 scripts/android-test-gate.py live` against `feature/1246` at `b0e0176649`, merged with
`origin/main` at `a596935bf5`: 38 executed, 38 passed, zero failures or skips, exit 0. The XML
includes `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` as a passing
testcase. This was the full curated gate, not a focused one-method run; its result does not identify
whether the first Manual approval write confirmed `default` early or remained an acknowledged no-op.

**Previous live verification — 2026-09-27 (#1190).** The dispatcher ran
`python3 scripts/android-test-gate.py live` against `feature/1190` at `50712cec51`, merged with
`origin/main` at `0df78d3578`: 44 executed, 44 passed, zero failures or skips, exit 0. This includes
`InteractiveStreamE2ETest`'s migrated Chats creation, save-as-channel, folder-use, two-host defaults and
peer-label paths. The host-row-only #1087 method is no longer curated.

**Previous live verification — 2026-09-27 (#1189).** The dispatcher ran
`python3 scripts/android-test-gate.py live` against `feature/1189` at `e9b1d0339e`, merged with
`origin/main` at `5ffcb50541` in a detached worktree: 45 executed, 45 passed, zero failures or
skips, exit 0. The run included `InteractiveStreamE2ETest`'s revised
`interactiveTurn_createEditArchiveChannel_readsPromptBack`,
`interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`, and
`interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost`. The create-channel method proves
the Channels-section plus works with zero active channels and uses the daemon default folder even when
the app has a different saved default. The label method retains its thread, Settings and cross-host
checks; the folder-settings method retains the daemon rename/archive and Archive restore round trip.
The earlier #1189 live run failed its empty-section precondition because the harness had seeded a
promoted channel. The scenario now archives and restores that fixture around its empty-section proof.

**Earlier parity baseline — 2026-09-25 (#680).** The dispatcher's real-claude gate ran
`python3 scripts/android-test-gate.py live` against `feature/680` at `b4da42eb70` merged with
`origin/main` at `87b6ca3241` (6 commits behind before the merge) in a detached worktree — 45
executed, 45 passed, no failures or skips, exit 0, wall clock 463.9s. `LIVE_MINIMUM` is 45 and this
run executed every curated method. This is the live gate for the mobile parity release candidate,
the final check of the 2026-09-19 parity batch; its full record, including the layout and feature
checks that do not run through this gate, is
[`docs/specs/architecture/680-parity-release-candidate.md`](specs/architecture/680-parity-release-candidate.md).
The candidate commit is `bf3749d8` (product tree identical to main `0abb4a0f`); the merge onto
`87b6ca3241` that the gate actually executed against added only test tooling ahead of it —
`FocusRecordListener` and its `testInstrumentationRunnerArguments` wiring (#1131) and the
failure-path `report_relay_link_drops` diagnostic (#1132) — with no `app/` product or UI change, so
this result still describes the candidate. All 45 methods passed, including both #955 push methods
(`interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` and
`interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`), so
background-notification parity holds for this candidate: Firebase setup (#579), token registration
(#361) and alerts (#685) have all shipped. Two non-blocking gaps remain open: #1135 (Edit channel's
focused field is hidden by the keyboard in landscape, and rotation resets its unsaved name edit by
design) and #1136 (four visual deviations from the supplied Figma frames — scanner top-bar offset,
channel-list row density, assistant bubble colour and modal sheet tone).

| Method | Outcome |
| --- | --- |
| `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` | PASS |
| `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` | PASS |
| `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` | PASS |
| `interactiveTurn_deleteConversation_removesFromListAndClosesThread` | PASS |
| `interactiveTurn_archiveRestore_roundTripsListMembership` | PASS |
| `interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` | PASS |
| `interactiveTurn_renameConversation_relabelsTopBarAndListRow` | PASS |
| `interactiveTurn_saveAsChannel_promotesToChannelTier` | PASS |
| `interactiveTurn_listArchiveEntry_opensArchived` | PASS |
| `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` | PASS |
| `interactiveTurn_peerStartedTurn_continuesOnPhone` | PASS |
| `interactiveTurn_peerQueue_staysConsistentAcrossClients` | PASS |
| `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` | PASS |
| `interactiveTurn_pingPrompt_statusSheetShowsRunningModel` | PASS |
| `interactiveTurn_pingPrompt_footerShowsContextUsage` | PASS |
| `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` | PASS |
| `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` | PASS |
| `interactiveTurn_chosenEffort_appliesFromTheFirstTurn` | PASS |
| `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` | PASS |
| `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` | PASS |
| `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` | PASS |
| `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` | PASS |
| `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` | PASS |
| `interactiveTurn_questionAnswer_reachesTheAskingConversation` | PASS |
| `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` | PASS |
| `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` | PASS |
| `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` | PASS |
| `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` | PASS |
| `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` | PASS |
| `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` | PASS |
| `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` | PASS |
| `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` | PASS |
| `interactiveTurn_markdownLink_opensLiveNoteInReader` | PASS |
| `interactiveTurn_muteChannel_roundTripsThroughTheHost` | PASS |
| `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` | PASS |
| `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` | PASS |
| `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost` | PASS |
| `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched` | PASS |
| `interactiveTurn_logData_savesTheOwningHostsArchive` | PASS |
| `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` | PASS |
| `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` | PASS |
| `interactiveTurn_createEditArchiveChannel_readsPromptBack` | PASS |
| `interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost` | PASS |
| `interactiveTurn_attentionDot_followsARealTurn` | PASS |
| `interactiveTurn_backgroundAgentProgress_showsOnRunningCard` | PASS |

See the verification record for the suite/ticket grouping — Continuity #847–#850, Interaction
#965–#967, Management #676's children (#1085–#1090, plus the related #1021 mute round trip),
Attachment #674's children (#1016, #1017, #1020) and Diagnostic download #684 — and for which cases
each suite proves only deterministically.

**Previous baseline capture — 2026-09-20, 16:12:58–16:13:58 UTC (#528).**
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

- **LIVE verified for #1751 (2026-10-05):** the dispatcher selected five
  `InteractiveStreamE2ETest` methods with `android-test-gate.py live --tests`
  against `feature/1751` at `91acd54dc4`, merged with `origin/main` at `24784c9388`.
  The fresh report `2026-10-05T02-40-19-025Z_real-claude-gate_#1751.log` records
  **5 executed, 4 passed, 1 failed, 0 errors, 0 skipped**. Both
  `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` and
  `interactiveTurn_backgroundAgentProgress_showsOnRunningCard` are present and passed;
  the former verifies raw `local_bash` separately from "Command" at both entry points.
  The only failure, `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`,
  passed on the same merged tree in `2026-10-05T02-40-19-025Z_real-claude-gate-rerun_#1751.log`:
  **1 executed, 1 passed, 0 failed/errors, 0 skipped**. These are selected-suite
  evidence and a one-method rerun, not a full-suite run or separate focused runs of
  the background-task methods. The [dispatcher gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1751#issuecomment-5987310168)
  records the offline-retry failure as nondeterministic and accepted the gate after rerun.
- **LIVE verified for #1665 (2026-10-04):** the dispatcher ran the full
  `InteractiveStreamE2ETest` suite with
  `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live`: **53 executed, 53 passed,
  0 failed/errors, 0 skipped**, exit 0, wall clock 1033.4s. The fresh XML report
  `/Users/juhanailmoniemi/Workspace/Projects/pyrycode-mobile-agents/logs/2026-10-04T14-25-57-932Z_real-claude-gate_#1665.log`
  contains a passing, unskipped testcase for
  `InteractiveStreamE2ETest.interactiveTurn_listArchiveEntry_opensArchived`; its migrated helper opens
  Open menu → Archive and the method asserts Archived appears. This is full-suite evidence, with no
  separate focused live run. The tested branch was `feature/1665` at `c210a62415`, merged with
  `origin/main` at `5b1d6bdfe7` in the gate worktree. See the
  [dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1665#issuecomment-5981193557).

- **LIVE verified for #1668 (2026-10-04):** the fresh full `InteractiveStreamE2ETest` suite ran
  `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live` on `6f366e38dc`, merged
  with `origin/main` at `5b1d6bdfe7`: 53 executed, 52 passed, 1 failed, 0 skipped.
  The fresh XML confirms `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` executed and
  passed in that full suite, covering pill and top-menu opening, Actions omission, and finished-or-empty
  roster assertions. This was not a separate focused run. The sole failure was
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`; its same-tree rerun
  executed 1, passed 1, failed 0, skipped 0. The dispatcher accepted the gate after that rerun.
  See [gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1668#issuecomment-5981025994).
  The reports are `2026-10-04T13-46-23-631Z_real-claude-gate_#1668.log` and the matching
  `real-claude-gate-rerun_#1668.log` under the agents repository's `logs/`.
- **LIVE verified for #1078 (2026-09-26):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1078` at `8d13a8d849` merged with
  `origin/main` at `c76a483330` (0 commits behind before the merge) — 45 executed, 45 passed, no
  failures or skips, exit 0, wall clock 466.4s. `LIVE_MINIMUM` stayed at 45; this ticket added no new
  curated method. This is the first clean run of
  `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` — no nondeterministic
  same-tree re-run needed — since #1017 first tracked its flake, consistent with
  pyrycode/pyrycode-relay#154 being live on the production relay.

- **LIVE verified for #1052 (2026-09-26):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1052` at `32ac5ec892` merged with
  `origin/main` at `4da08dc28b` (0 commits behind before the merge) — 45 executed, 45 passed, no
  failures or skips, exit 0, wall clock 478.6s. `LIVE_MINIMUM` stayed at 45; this ticket added no new
  curated method. This is the first clean run of
  `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` — no nondeterministic
  same-tree re-run needed — since #1029 first tracked its flake, consistent with
  pyrycode/pyrycode-relay#154 being live on the production relay.

- **LIVE verified for #1087 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1087` at `5c425313ad` merged with
  `origin/main` at `6c5ca75e28` (5 commits behind before the merge) — 41 executed, 41 passed, no
  failures or skips, exit 0, wall clock 448.0s. `LIVE_MINIMUM` rose from 40 to 41 with this ticket's
  new method (see [Pre-ship gate](#pre-ship-gate)); this run executed all forty-one curated methods
  against `LIVE_MINIMUM` 41. This is the first live run of
  `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` — it passed outright, proving
  that a workspace added in a new folder from host A's row, renamed and archived from its pencil, and
  restored from A's Archive, round-trips through the real daemon with the label surviving the archive.

- **LIVE verified for #1086 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1086` at `d89dc3e921` merged with
  `origin/main` at `6273f9c957` (0 commits behind before the merge) — 40 executed, 40 passed, no
  failures or skips, exit 0, wall clock 479.6s. `LIVE_MINIMUM` rose from 39 to 40 with this ticket's
  new method (see [Pre-ship gate](#pre-ship-gate)); this run executed all forty curated methods
  against `LIVE_MINIMUM` 40. An earlier attempt on this branch (`dc98faa91f` merged with
  `origin/main` at `dbaf249934`, 6 commits behind) failed at 39 executed / 38 passed / 1 failed: the
  new method's `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` timed out finding host A's
  Settings row, because its matcher looked for the server id on the clickable row itself while the
  `HostIdentityRow`'s own `ListItem` merges those texts onto a descendant of the click target instead.
  The fix (a53e9dde) matched the row by `hasClickAction() and hasAnyDescendant(hasText(serverId))`,
  confirmed against the real `SettingsScreen` under a temporary, since-removed Robolectric probe before
  the passing re-run above. This is the first live run of
  `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` — it passed outright once fixed, proving each
  host's default workspace and Archive stay its own with two real daemons paired: a folder created from
  each host's own Settings becomes that host's stored default and a chat created from that host's row
  lands in it, compared against the daemon's own reported `cwd`; setting one host's default leaves the
  other's untouched; and archiving and restoring one of A's conversations from A's Archive leaves B's
  full list and Archived list unchanged throughout.

- **LIVE verified for #684 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/684` at `7ae8fea8fc` merged with
  `origin/main` at `dbaf249934` (0 commits behind before the merge) on managed `pixel2Api33Atd` —
  mobile revision `7ae8fea8fc1b1503084a09e58610a9b99ea7cc53`, daemon revision
  `fef4d6958ab63ef80ff0a5f3142b2f40ba559531`, Claude Code **2.1.280** — 39 executed, 39 passed, no
  failures or skips, exit 0, wall clock 434.5s. `LIVE_MINIMUM` rose from 38 to 39 with this ticket's
  new method (see [Pre-ship gate](#pre-ship-gate)); this run executed all thirty-nine curated methods
  against `LIVE_MINIMUM` 39. This is the first live run of
  `interactiveTurn_logData_savesTheOwningHostsArchive` — it passed outright, saving host A's diagnostic
  archive (with a run-unique marker only A's daemon logged) unaffected by a mid-transfer selection
  change to host B and a first cancelled picker.

- **LIVE verified for #1066 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1066` at `e7263d546e` merged with
  `origin/main` at `b8e3fd65ab` (4 commits behind before the merge) — 37 executed, 37 passed, no
  failures or skips, exit 0, wall clock 450.0s. `LIVE_MINIMUM` stayed at 37; this ticket added no
  new curated method. This is the first clean run of
  `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` (#1051) — no nondeterministic
  same-tree re-run needed — since #1020 first tracked its flake, consistent with
  pyrycode/pyrycode-relay#154 being live on the production relay.

- **LIVE verified for #1065 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1065` at `07d78c898e` merged with
  `origin/main` at `57fcceb677` (0 commits behind before the merge) — 37 executed, 37 passed, no
  failures or skips, exit 0, wall clock 404.5s. `LIVE_MINIMUM` stayed at 37; this ticket added no
  new curated method. This is the first clean run of
  `interactiveTurn_renameConversation_relabelsTopBarAndListRow` (#537) — no nondeterministic
  same-tree re-run needed — since #1020 first tracked its flake, consistent with
  pyrycode/pyrycode-relay#154 being live on the production relay.

- **LIVE verified for #1059 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1059` at `356c50f571` merged with
  `origin/main` at `2c213c1168` (5 commits behind before the merge) — 37 executed, 37 passed, no failures
  or skips, exit 0, wall clock 419.3s. `LIVE_MINIMUM` stayed at 37; this ticket added no new curated
  method. This is the first clean run of `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`
  (#955) — no nondeterministic same-tree re-run needed — since #1039 first tracked its flake, consistent
  with pyrycode/pyrycode-relay#154 being live on the production relay. See [Coverage —
  hardened](#follow-ups-to-ticket) for the in-repo diagnostic fix.

- **LIVE verified for #1017 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1017` at `3d6620490c` merged with
  `origin/main` at `25f6de5c9f` (0 commits behind before the merge) — mobile revision
  `3d6620490cac0d8f0d0d88e99c986b6b7e9ecbd4`, daemon revision `a909786672cb7302648b11490e030675f1d15d0a`,
  Claude Code **2.1.280** — 37 executed, 31 passed outright, and 6 failed once then passed on an immediate
  re-run of the same merged tree (nondeterministic, not attributed to this branch):
  `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`,
  `interactiveTurn_peerStartedTurn_continuesOnPhone`,
  `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`,
  `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`,
  `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` and
  `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, exit 0 on the re-run, wall clock
  655.1s. `LIVE_MINIMUM` rose from 34 to 37 with this ticket's three methods (see
  [Pre-ship gate](#pre-ship-gate)); this run executed all thirty-seven curated methods against
  `LIVE_MINIMUM` 37. This is the first live run of
  `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` and
  `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost` — both passed outright — and the
  first live pass of `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` in its final,
  peer-file form (#1020 replaced the plan's original `send_file` substitution once history replay could
  carry a peer's `attachment_ids`); it failed once on this run and passed on the immediate re-run, which
  this suite's other nondeterministic same-tree-passes-on-rerun entries treat as a flake, not a defect.
  Two earlier attempts on this branch did not reach this result: a run on `30e6ab41af` merged with
  `origin/main` at `b21e25a8c4` (5 commits behind) executed 34, passed 24, and failed
  `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost` outright, both on the run and its
  re-run — host A's seeded copy of the colliding conversation carried no bound session, and the daemon
  refuses `send_message` to an unbound conversation (`no_bound_session`); the fix (`0c755970`, harness
  only) gives host A's seeded copy a run-unique session id, so the first send revives it through the
  daemon's restart-recovery path (see the plan's 2026-09-25 Revisions and [What rung 3 is made
  of](#what-rung-3-is-made-of)). A follow-up run on `0c755970ea` merged with `origin/main` at `6014e2701b`
  (7 commits behind) produced no result at all, because that merge conflicted.

- **LIVE verified for #1020 (2026-09-25):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1020` at `f84fd671f3` merged with
  `origin/main` at `b21e25a8c4` (0 commits behind before the merge) — 32 executed, 24 passed outright,
  and 8 failed once then passed on an immediate re-run of the same merged tree (nondeterministic, not
  attributed to this branch): `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`,
  `interactiveTurn_peerStartedTurn_continuesOnPhone`,
  `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`,
  `interactiveTurn_renameConversation_relabelsTopBarAndListRow`,
  `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`,
  `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`,
  `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` and
  `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, exit 0 on the re-run, wall clock
  769.6s. `LIVE_MINIMUM` rose from 31 to 32 with this ticket's own method (see
  [Pre-ship gate](#pre-ship-gate)); this run executed all thirty-two curated methods against
  `LIVE_MINIMUM` 32. This is the first live evidence that another client's own upload, named on a
  message, survives a history reload with the thread cache cleared — the row still opens and saves with
  the fixture's exact bytes — and that the phone-to-peer scenario reads its ids from the peer's history
  again rather than the phone's own cache.

- **LIVE verified for #1036 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1036` at `f2e1ecc3b7` merged with
  `origin/main` at `afc5b3cde4` (0 commits behind before the merge), executing all twenty-nine curated
  scenarios: 27 passed outright, and 2 failed once then passed on an immediate re-run of the same merged
  tree (nondeterministic, not attributed to this branch) — `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`,
  this ticket's own target, and `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` (#1016's,
  also seen flaking on #1016's own run below) — exit 0 on the re-run, wall clock 406.5s.
  `interactiveTurn_peerStartedTurn_continuesOnPhone`, this ticket's other target, passed outright. The
  gate's per-test XML carries no failure message text, so which wait `stopRunningTurn` hit on this run is
  not established; see [Coverage — hardened](#follow-ups-to-ticket) for the fix and this run's caveat.

- **LIVE verified for #955 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/955` at `66e46a1dc9` merged with
  `origin/main` at `afc5b3cde4` (5 commits behind before the merge, `origin/main` already carrying
  #1016) — 31 executed, 27 passed outright, and 4 failed once then passed on an immediate re-run of the
  same merged tree (nondeterministic, not attributed to this branch):
  `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`,
  `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`,
  `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` and
  `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`, exit 0 on the re-run,
  wall clock 692.1s. `LIVE_MINIMUM` rose from 27 to 29 with this ticket's own two methods (see
  [Pre-ship gate](#pre-ship-gate)); combined with #1016 already on `main`, this run executed all
  thirty-one curated methods against `LIVE_MINIMUM` 31. This is the first live evidence that a real FCM
  push from the production relay — not a synthetic message — wakes the backgrounded app for a turn that
  ended while it was away and posts one alert whose tap opens the right thread, and that a permission
  prompt surfacing while the app is away is alerted exactly once even across a second reconnect inside the
  daemon's 30 s wake-coalescing window.
  Two earlier live attempts on this branch failed both push scenarios at the background step, before this
  run: 14:56Z (`1037be7def`, 5 of 29 failed) and 15:49Z (`08f613abea`, 4 of 29 failed). The cause was found
  only by re-reading the code, not from either run's stack trace: `sendAppToBackground`'s
  `withTimeoutOrNull { currentRepository.first { it == null } }` returns `null` on a successful
  background exactly as it does on a timeout, so `withTimeoutOrNull`'s own `null`-means-timeout convention
  read every successful background as "the link stayed open" and both push scenarios failed there on
  every run. The two verifier PASSes before this one (14:45Z and 15:31Z) both missed it, reading the
  block from its intent rather than from what `withTimeoutOrNull` actually returns; the fix (`839aa436`)
  ends the block in `.let { true }`, so `null` means only a timeout. A separate, unrelated design flaw was
  caught by review before any live run reached it: AC2's first design waited for a second `Prompt` alert
  on `HostConversationSource.alerts` after the reconnect, which a retained permission modal never emits
  (see [Dependency injection — host conversation source § Attention alerts](knowledge/features/dependency-injection-host-conversation-source.md#attention-alerts-685));
  every live run would have failed there once the background step itself started passing. The fix (in
  commit `08f613abea`, the plan's Revisions) replaces that wait with a bounded settle after the reconnect,
  keeping the one-notification and unchanged-`postTime` assertions as the acceptance check. See the
  background-push-turn-end / background-push-prompt paragraph under
  [What rung 3 is made of](#what-rung-3-is-made-of) for both scenarios' steps in full.

- **LIVE verified for #1016 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/1016` at `c51aea6481` merged with
  `origin/main` at `31234eccda` (0 commits behind before the merge), against a daemon carrying
  `../pyrycode` main's #2166 (`attachment_offered`'s producer) and #2169 (`pyry_files` registered on the
  interactive spawn) — the audit baseline for both repos was mobile `d290cbc3` / daemon `61cb33c4`,
  2026-09-24. It executed all twenty-nine curated scenarios — the curated list's first run with
  `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` and
  `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` on it — 29 executed, 26 passed outright,
  and 3 failed once then passed on an immediate re-run of the same merged tree (nondeterministic, not
  attributed to this branch): `interactiveTurn_saveAsChannel_promotesToChannelTier`,
  `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` and this ticket's own
  `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, exit 0 on the re-run, wall clock
  492.0s. `LIVE_MINIMUM` rose from 27 to 29 with this ticket (see [Pre-ship gate](#pre-ship-gate)); 29
  executed meets it exactly. This is the first live evidence that another client — the `SecondClientPeer`
  standing in for the desktop — sees the phone's own attached files with their exact SHA-256 bytes, and
  that a file claude hands over with `send_file` survives a restart in the phone's own thread cache with
  the same bytes on open and save. It is also the first live evidence of a daemon fact the plan did not
  anticipate: the operator's turn is logged in history as a plain `message`/`user` entry with text only,
  dropping `attachment_ids` — filed as [#1020](https://github.com/pyrycode/pyrycode-mobile/issues/1020),
  which is why `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` ships `@Ignore`d and out of
  the curated list rather than as this ticket's third method. Three earlier runs on this branch found and
  worked through unrelated problems before this one: the first (5 of 30 failed) found the #1020 daemon
  fact itself and a 90 s poll for a `send_message` history entry that never arrives; the second (2 of 29
  failed) found two peers on the first test daemon going dead — handshake accepted, then no frames — which
  a rename moving both new methods to a different point in JUnit's name-hash run order, plus a
  `request_history` liveness probe (`assertPeerAnswers`), addressed without ever confirming a cause; the
  third (1 of 29 failed) found `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` failing for a
  reason this branch does not touch, filed as
  [#1029](https://github.com/pyrycode/pyrycode-mobile/issues/1029) and not this ticket's to fix. See the
  attachments-from-phone / claude-offered-file paragraph under
  [What rung 3 is made of](#what-rung-3-is-made-of) for the daemon-fact and ordering lessons in full.
- **LIVE verified for #967 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/967` at `d33b3593d9` merged with
  `origin/main` at `653c6fb65f` (0 commits behind before the merge), executed all twenty-seven curated
  scenarios — the curated list's first run with
  `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`,
  `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` and
  `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` on it — with twenty-seven passes, no
  failures or skips, exit 0, wall clock 267.5s. `LIVE_MINIMUM` rose from 24 to 27 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); twenty-seven executed meets it exactly. This is the first live evidence
  that the composer footer's model, effort and permission readings settle on a fresh reading after a
  cut-and-restore of the phone's link, that a further real turn brings the accessible context percentage back (the
  reading belongs to the connection, per #946), that a model picked from the footer after a reconnect is
  confirmed by a later fresh reading, that the slash-command suggestions and a manual Compact session still
  work once the link is restored, and that the Actions menu's background-task count and panel track a real
  backgrounded command through its start, its run and its finish. An earlier run on this branch
  (2026-09-24, commit `ed11600e20`, 6 commits behind `origin/main`) executed the same twenty-seven and
  failed two: `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` had already passed the task's
  start, the live count and the panel listing, and `Background tasks (0)` after the finish, but timed out
  waiting for the panel to label the task "Finished" — real claude sends an empty `background_task_roster`
  unprompted after a finish, and the projection's wholesale-replace rule then drops the task from the panel
  before "Finished" is ever read (see the reconnect-footer/reconnect-commands/background-task paragraph
  under [What rung 3 is made of](#what-rung-3-is-made-of)); the assertion now accepts either "Finished" or
  "No background tasks", and the rerun (`d33b3593d9`) showed the empty-roster reading win.
  `interactiveTurn_deleteConversation_removesFromListAndClosesThread` also failed on that earlier run; this
  branch touches neither that method nor any production file, and the failure is tracked separately as
  [#996](https://github.com/pyrycode/pyrycode-mobile/issues/996).
- **LIVE verified for #966 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/966` at `944a911caa` merged with
  `origin/main` at `d291e62025` (0 commits behind before the merge), executed all twenty-four curated
  scenarios — the curated list's first run with
  `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` and
  `interactiveTurn_questionAnswer_reachesTheAskingConversation` on it — with twenty-four passes, no
  failures or skips, exit 0, wall clock 574.4s. `LIVE_MINIMUM` rose from 22 to 24 with this ticket (see
  [Pre-ship gate](#pre-ship-gate)); twenty-four executed meets it exactly. This is the first live evidence
  that a permission prompt and a clarification question each reach only the conversation that raised them,
  that don't-ask-again holds across a repeat command in that conversation while a second, ungranted
  conversation still prompts, and that a prompt or question another paired device resolves closes the
  phone's own dialog with no tap. An earlier run on this branch (2026-09-24, commit `1723e3c6d2`) failed 23
  of 24: every method that pairs the phone timed out with `redemption_window_elapsed` because the harness
  mints pairing codes before the Gradle build and boot, and that phase took 28 minutes instead of its usual
  3 while another builder's focused tests held the same managed device. That is a harness weakness, not a
  product or design defect, filed as [#993](https://github.com/pyrycode/pyrycode-mobile/issues/993); no
  test or design changed before the rerun that passed.
- **LIVE verified for #981 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/981` at `3c41731fa2` merged with
  `origin/main` at `7e820f8e51` (8 commits behind before the merge), executed all twenty-two curated
  scenarios — the curated list's first run with
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` back on it since #977 excluded
  it — with twenty-two passes, no failures or skips, exit 0, wall clock 175.2s. `LIVE_MINIMUM` rose from 21
  to 22 with this ticket (see [Pre-ship gate](#pre-ship-gate)); twenty-two executed meets it exactly. This
  is the first live pass of the #687 scenario since the two runs that closed #977 both failed at its step
  5: the thread screen, not the repository fold, was losing a non-streaming reply once the list overflowed
  under `reverseLayout`; see [Thread screen — list and status row](knowledge/features/thread-screen-how-it-works-list-and-status-row.md)
  for the fix.
- **LIVE verified for #965 (2026-09-24):** the dispatcher's real-claude gate ran
  `python3 scripts/android-test-gate.py live` against `feature/965` at `131f674c25` merged with
  `origin/main` at `8f661f1507` (0 commits behind before the merge), executed all twenty-one curated
  scenarios — the curated list's first run with `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`
  on it, and the first live run of the extended `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` —
  with twenty-one passes, no failures or skips, exit 0, wall clock 211.2s. `LIVE_MINIMUM` rose from 20 to
  21 with this ticket (see [Pre-ship gate](#pre-ship-gate)); twenty-one executed meets it exactly. Two
  earlier runs on this branch (2026-09-24, 03:22Z and 03:37Z) had both #965 methods pass but the run as a
  whole go red on #687's unrelated operator-bypass method, filed as
  [#977](https://github.com/pyrycode/pyrycode-mobile/issues/977); this final run is the first fully green
  one, after #977 excluded #687's method from the curated list on `main`. This is the first live evidence
  that the composer's Stop control ends a still-running turn with the Interrupted outcome, that the
  conversation keeps taking real replies afterward, and that Reset session's wrapping-up phase (raised
  before the daemon's real-claude wrap-up turn and lowered only after it ends) is causally held rather than
  raced on timing; the restarting phase stays proven only by `ScriptedResettingTest`. It also corrects an
  earlier reading in the new-session scenario's own KDoc that the wrap-up turn ran only "when handoff notes
  are enabled" — the daemon runs it for any live child regardless. This does not prove **cross-device**
  Stop, which remains [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)'s open scope.
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
  (see [development verification](knowledge/features/development-verification.md)), followed by
  `python3 scripts/android-test-gate.py scripted-all`, covering `ping`, `stream`, `spinner`, `tool`,
  `tool-failed`, `tool-progress`, `reconnect`, `offline-retry`, `replay-order`, `tool-then-text`,
  `refusal` and `mcp-failed`. Tagged tickets run `live` after verifier.
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

- **Operator-bypass pairing lifetime (#1756):**
  `InteractiveStreamE2ETest.interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`
  retains its full curated selection and permission assertions, with one-shot minting at scenario entry.
  The operator-bypass coverage above records the 55-test full-suite pass and retained XML. The
  delayed-suite regression belongs to `scripts/test_e2e_emulator_gradle.py`; no new
  `DeterministicInteractiveStreamE2ETest` twin was added. The verifier's nonblocking hardening finding
  remains: the single-threaded fixture server has no accepted-socket read timeout, so incomplete
  unauthenticated headers can stall it despite the instrumentation client's deadline.

- **Host system prompt — shipped coverage (#1775):**
  `InteractiveStreamE2ETest.interactiveTurn_hostSystemPrompt_editsResetsAndCancels` covers custom
  save/read/reopen, reset/discard, reset/save to the returned default and original-value cleanup.
  Its explicit full-suite pass and counts, plus the unrelated Stop rerun, are recorded under
  [Verification status](#verification-status). Controller/component fakes cover deterministic
  transitions; no new `DeterministicInteractiveStreamE2ETest` twin was added.

- **Send now — shipped coverage (#1642):**
  `InteractiveStreamE2ETest.interactiveTurn_sendQueuedNow_reachesRunningTurn` and
  `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_sendQueuedNow_placesAfterToolResult`
  cover delivery after a held tool. Full-suite counts, the named pass and the operator's
  unrelated-failure disposition are recorded under [Live mode](#live-mode-rung-3-live-relay).
  #1655 retains the first-confirmation-after-next-turn ordering gap; daemon pyrycode#2785
  owns the lost question-answer failure. Queue-removal-only assertions cannot prove delivery placement.


- #1666 retains `InteractiveStreamE2ETest.interactiveTurn_newSession_rendersSessionBoundaryDelimiter`
  (#541) and its `CD_MORE_ACTIONS` selector, now reaching Reset session through the shared header
  Actions overlay. The fresh full-suite named pass and the unrelated failure/rerun are recorded above.
  Existing header E2E callers remain intact; no new rung-3 scenario or rung-4 deterministic twin was added.

- #1668 revises `InteractiveStreamE2ETest.interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`
  in place: its historical name stays for acceptance tracking, while entry coverage uses the pill and
  count-free top menu and asserts Actions omission. The full live-suite proof is recorded above;
  finished-or-empty roster assertions remain. The shared `openBackgroundTasks` helper also serves
  `interactiveTurn_backgroundAgentProgress_showsOnRunningCard` through the top menu. No new
  `DeterministicInteractiveStreamE2ETest` twin was added; the existing scripted ping scenario retains
  its top-menu panel assertion.

- **Session-error recovery — pending (#1731):**
  `InteractiveStreamE2ETest.interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog`
  must prove real daemon → live relay → phone recovery for retained crash-loop backlog
  without resend, and for abandoned delivery followed by a fresh send. #1678 proves
  only rung 2. The separate daemon-owned prerequisite is an isolated test-only control
  that triggers startup failure, holds retained backlog, advances to give-up/drop,
  and releases failure for real-Claude recovery; its issue number awaits daemon-owner
  filing. The current live harness has no reproducible trigger. A rung-4 twin in
  `DeterministicInteractiveStreamE2ETest` remains pending until that control/fixture
  can hold both states. Neither scenario is part of the pre-ship selector; keep
  `python3 scripts/android-test-gate.py live` unchanged until runnable coverage lands.
  An ignored manual method or skipped run supplies no live proof.

- **Coverage — updated:** [#1631](https://github.com/pyrycode/pyrycode-mobile/issues/1631)
  extends `InteractiveStreamE2ETest.interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`
  with the top-menu opener while a task runs. Its rung-4 coverage is
  `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread`:
  before sending, the top menu opens “No background-task report yet”, dismisses the menu,
  and the panel closes before the scripted reply. Run it with
  `python3 scripts/android-test-gate.py scripted ping`; no new method or selector was added.
  The 2026-10-04 candidate selected live run executed 6, passed 5, failed 1 and skipped 0;
  the background-task and file methods each passed. The question-answer method failed once,
  then passed in a same-tree rerun with 1 executed, 0 failed and 0 skipped. This was a selected
  run, not a full-suite pass. Retained dispatcher reports are
  `2026-10-04T08-35-25-235Z_real-claude-gate_#1631.log` and its `-rerun_#1631.log` counterpart.


- **Coverage — hardened:** [#1637](https://github.com/pyrycode/pyrycode-mobile/issues/1637)
  fixes pending CameraX initialization blocking scanner exit on main, protecting
  `InteractiveStreamE2ETest#interactiveTurn_twoHostsCollidingConversationId_stayPerHost`'s
  scanner → paste-code path. Held-initialization device and seven controlled JVM
  lifecycle checks cover the race; the live helper, per-host rows/threads, rename
  isolation, both reconnects, graph restart and host-B cleanup remain intact.
  Both collision and offered-file ran and passed in the fresh 53-test full suite
  recorded in [Verification status](#verification-status). No scenario or
  `DeterministicInteractiveStreamE2ETest` twin was added.

- **Coverage — hardened:** [#1703](https://github.com/pyrycode/pyrycode-mobile/issues/1703) repairs
  `InteractiveStreamE2ETest#interactiveTurn_questionAnswer_reachesTheAskingConversation`'s phone
  tap positioning beneath thread chrome and labels its timeout operations. The short-thread shared
  regression checks real pointer selection and exactly one generation-scoped submit. The existing
  phone/peer dismissal, chosen-label-only reply and both completed turns remain enabled; the method
  passed in the fresh 53-test full live suite in [Verification status](#verification-status).
  No scenario or `DeterministicInteractiveStreamE2ETest` twin was added, and the pre-ship selector
  and command are unchanged.

- **Coverage — hardened:** [#1702](https://github.com/pyrycode/pyrycode-mobile/issues/1702) repairs
  `InteractiveStreamE2ETest#interactiveTurn_questionAnswer_reachesTheAskingConversation` by revealing
  the actions container after selection and before waiting for enabled Continue. The shared
  short-viewport regression proves valid selection with an uncomposed actions row and generation-scoped
  dispatch after reveal. Both phone and peer round-trip assertions remain enabled; the named method
  passed in the fresh 53-test full live suite recorded in [Verification status](#verification-status).
  No scenario or `DeterministicInteractiveStreamE2ETest` twin was added.

- **Coverage — hardened:** [#1694](https://github.com/pyrycode/pyrycode-mobile/issues/1694) makes
  `InteractiveStreamE2ETest#interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`
  exercise a prior same-pairing peer before its prompt peer, protecting token-bound identity in a
  focused run as well as the full suite. Named timeout diagnostics distinguish peer operations and
  host-link close/reconnect without longer deadlines. Background-before-message ordering, real FCM,
  exactly-one alert, original `postTime` and cleanup remain intact. The named method passed in the
  fresh full candidate suite; see [Verification status](#verification-status) for counts and the
  unrelated question-answer rerun. No scenario or deterministic FCM twin was added.

- **Coverage — confirmed:** [#1697](https://github.com/pyrycode/pyrycode-mobile/issues/1697) records
  the no-code resolution of
  `InteractiveStreamE2ETest#interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` after
  #1698/#1686's shared identity repairs. The picker-selected PNG and 100,000-byte three-chunk
  document still reach the peer through one message in X, with distinct ids, exact SHA-256 digests
  and no user message in Y. See [Verification status](#verification-status) for both the retained
  #1686 proof and #1697's fresh 53-test full-suite pass. No scenario or deterministic twin was added.

- **Coverage — hardened:** [#1683](https://github.com/pyrycode/pyrycode-mobile/issues/1683) protects
  `InteractiveStreamE2ETest#interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` with a
  same-pairing prior-peer open/close and labelled peer waits. The real permission hold, approval,
  running Bash observation and completion clearing remain enabled in the curated suite.
  `DeterministicInteractiveStreamE2ETest`'s `tool` and `tool-progress` twins prove rendering but
  cannot prove this permission round-trip. See [Verification status](#verification-status) for the
  fresh 53-test full-suite pass and the limits of the original timeout diagnosis.

- **Coverage — hardened:** [#1686](https://github.com/pyrycode/pyrycode-mobile/issues/1686) restores
  `InteractiveStreamE2ETest#interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` by
  retaining the answer pairing's static identity across peers. A standalone method can pass while
  later suite methods cannot authenticate an already-bound token. `PeerDeviceKeyStoreTest` covers
  recreation after returned-array wiping, host/token isolation, wrong-host rejection and concurrent
  first access; the permission method labels its open wait without changing its checks or deadline.
  See [Verification status](#verification-status) for the fresh full-suite pass. No scenario or
  deterministic twin was added. The merged tree also retains `PeerDeviceStaticKeyStore` and its
  earlier handshake regressions with a separate registry; these tests do not exercise the live
  peer's new store. Consolidation is the verifier's nonblocking finding on
  [PR #1705](https://github.com/pyrycode/pyrycode-mobile/pull/1705#issuecomment-5975917069).

- **Coverage — hardened:** [#1692](https://github.com/pyrycode/pyrycode-mobile/issues/1692) protects
  `InteractiveStreamE2ETest#interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`
  with a same-token prior-peer open/close before the observing peer. The four #1631/#1637 branch/base
  failures occurred at initial authentication before any prompt: retained daemon logs `AOLbIc`,
  `JKsJv3`, `Cf5gl9` and `tWgsL4` contained respectively 102/84/102/90 `static_key_mismatch`
  rejections with `bound_to_other_key`. Sequential peers rotated the static key for an already-bound
  token; the repair reuses #1698's identity custody. An unsettled/redialing status alone cannot
  distinguish dial, handshake and probe failures, and these runs do not establish a newest-page defect
  or the historical #1036 outbox cause. The JVM regression completes fresh authenticated handshakes
  and encrypted correlated readiness probes across closed sessions; see
  [Test scheduling and harnesses](knowledge/features/development-verification-test-scheduling.md#test-scheduling-and-harnesses).
  The named live method remains enabled and curated with unchanged recovery assertions and deadlines,
  and passed in the fresh full suite recorded in [Verification status](#verification-status).

- **Coverage — hardened:** [#1696](https://github.com/pyrycode/pyrycode-mobile/issues/1696) makes
  `InteractiveStreamE2ETest#interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`
  open and close a prior peer with the same pairing before opening its observing peer. This protects
  #1698's token-bound static identity repair independently of scenario order. The JVM regression
  authenticates successive Noise initiator keys rather than only comparing store arrays. All existing
  Stop assertions remain: cancellation removes Stop, with no Interrupted status-label assertion since
  #1357. The named method passed in the fresh full live suite; see
  [Verification status](#verification-status) for counts and the unrelated focused rerun.
  No scenario, deterministic twin, retry or longer wait was added.

- **Coverage — hardened:** [#1698](https://github.com/pyrycode/pyrycode-mobile/issues/1698) repaired
  shared peer identity custody for
  `InteractiveStreamE2ETest#interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` and other
  peer scenarios. One exact host/token static identity survives sequential peers within an
  instrumentation process, while each dial owns fresh Noise state. The unchanged attachment scenario
  passed in the full live suite; see [Verification status](#verification-status) for counted evidence
  and the prior/repaired daemon rejection comparison. No scenario or deterministic twin was added.

- **Coverage — updated:** [#1563](https://github.com/pyrycode/pyrycode-mobile/issues/1563) is that
  sibling ticket: `treeHost` in `ChannelListScreen` stops passing the Channels/Chats row pen at all,
  matching Figma `15:8`. `openChannelEditor` and `setMuteInEditChannel` now reach Edit channel the way
  #1561 predicted — open the row, then More actions → Edit — and leave the thread once the editor
  closes; `openChannelEditor`'s archive step now waits on `awaitChannelList()` before checking the row is
  gone, since the thread's own archive-then-pop leaves a window where that check would otherwise succeed
  immediately on the still-showing thread. No new test method and no change to the curated selector or
  `LIVE_MINIMUM`. The dispatcher's live gate (branch `feature/1563` at `6f7b08cb5c`, merged with
  `origin/main` at `220e07e412`, 1 commit behind before the merge) ran the selected
  `interactiveTurn_createEditArchiveChannel_readsPromptBack` and
  `interactiveTurn_muteChannel_roundTripsThroughTheHost` plus three always-run methods: 5 executed, 5
  passed, 0 failed, 0 skipped, exit 0. Both named methods have a passing testcase in the fresh output.

- **Coverage — added:** [#1571](https://github.com/pyrycode/pyrycode-mobile/issues/1571) adds
  `InteractiveStreamE2ETest.interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend` to the
  curated LIVE `TEST_TARGET` selector in `scripts/e2e-emulator.sh`; `LIVE_MINIMUM` is counted from that
  list, so it rose from 51 to 52 with no edit to `android-test-gate.py`. See [What rung 3 is made
  of](#what-rung-3-is-made-of) for the scenario and its dormant-history seed. The dispatcher's
  post-verifier full `python3 scripts/android-test-gate.py live` run (branch `feature/1571` at
  `f4248cacee`, merged with `origin/main` at `f4c6598ad6`, 43 commits behind before the merge) executed
  52, passed 52, failed 0, skipped 0; the new method has a passing testcase in the fresh XML with no
  failure — see [Verification status](#verification-status).

- **Coverage — updated:** [#1561](https://github.com/pyrycode/pyrycode-mobile/issues/1561) changed the
  shared `renameOpenThread(newName)` helper: a promoted conversation now renames through the thread's menu's
  Edit, which opens Edit channel (name field tagged `CHANNEL_NAME_FIELD_TAG`, then OK); an unpromoted one
  still goes through Rename. `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`'s AC-2 step
  renames host A's seeded **channel**, so it now drives this ticket's Edit-channel flow against a real
  daemon instead of the rename dialog. No new test method and no change to the curated selector or
  `LIVE_MINIMUM`. The dispatcher's post-verifier full `python3 scripts/android-test-gate.py live` run
  (branch `feature/1561` at `3419cd5883`, merged with `origin/main` at `72a3f328a5`, 0 commits behind
  before the merge) executed 51, passed 51, failed 0, skipped 0; `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`
  and `interactiveTurn_muteChannel_roundTripsThroughTheHost` are both present and passing in the fresh XML.
  Removing the list's row pens (tracked as a sibling ticket) will break
  `interactiveTurn_muteChannel_roundTripsThroughTheHost` and the create/edit-channel scenario
  (`interactiveTurn_createEditArchiveChannel_readsPromptBack`), both of which open Edit channel from a
  Channels row's pen today; they can switch to the thread's menu Edit, which now reaches the same modal.
  Resolved by #1563 — see the top of this list.

- **Coverage — added:** [#1460](https://github.com/pyrycode/pyrycode-mobile/issues/1460) adds
  `InteractiveStreamE2ETest.interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip` to the
  curated LIVE `TEST_TARGET` selector in `scripts/e2e-emulator.sh`; `LIVE_MINIMUM` is counted from that
  list, so it rose from 50 to 51 with no edit to `android-test-gate.py`. #1348 (PR #1414) made the
  Actions-menu command carry the pending files, the same way desktop's `sendText` does, so the daemon's
  `composeAttachmentPrompt` appends the attachment block after `/compact`'s text — Claude Code reads text
  after `/compact` as custom summary instructions, so it was open whether real claude would still compact
  with that block riding along. The new method sends a ping in a fresh chat (so claude spawns and
  publishes `/compact`), attaches one small text fixture through Attach files, taps Compact session, and
  waits for the same `cd_thread_compacting` indicator and compaction-divider signals
  `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` waits for (see the reconnect-commands
  scenario above), then for the fixture's tile to leave the attachment strip. The waits are copied rather
  than factored into a shared helper, so the existing method is unchanged. The fixture is deleted in a
  `finally`. Two real claude turns (the ping and the compaction); no production code changed. The
  dispatcher's post-verifier full `python3 scripts/android-test-gate.py live` run (branch `feature/1460`
  at `5d3187e015`, merged with `origin/main` at `a4d536d9ad`, 0 commits behind before the merge) executed
  51, passed 50, failed 1 and skipped 0; the new method has a passing testcase in the fresh XML with no
  failure. The one failure,
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, passed on a same-tree re-run,
  so the dispatcher treated it as a suite flake rather than this branch's — see [Verification
  status](#verification-status). Real claude does compact reliably with the attachment block after
  `/compact`, so the ticket's `@Ignore` fallback was not needed.

- **Coverage — added:** [#1360](https://github.com/pyrycode/pyrycode-mobile/issues/1360) adds the
  `refusal` scripted scenario (see [Scenarios](#scenarios-454) above) and
  `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel`.
  No rung-3 twin: real claude cannot be made to refuse on demand, so the `TEST_TARGET` LIVE selector and
  `LIVE_MINIMUM` are unchanged, and the real-Claude model round trip stays `@Ignore`d on
  [#1397](https://github.com/pyrycode/pyrycode-mobile/issues/1397). The scripted gate ran this scenario
  alone (each scenario overwrites the prior XML): 1 executed, 0 failed, 0 skipped, and
  `python3 -m unittest scripts/test_android_test_gate.py` (37 pass) covers `refusal`'s entry in
  `SCENARIOS`.

- **Coverage — added:** [#1410](https://github.com/pyrycode/pyrycode-mobile/issues/1410) adds
  `InteractiveStreamE2ETest.interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`
  to the curated LIVE `TEST_TARGET` selector in `scripts/e2e-emulator.sh`; `LIVE_MINIMUM` is counted
  from that list (#1440), so it rises by one with no edit to `android-test-gate.py`. The method proves
  the thread's new opening ask (`ThreadViewModel.askForContextUsage`, which calls
  `ConversationRepository.requestContextUsage`) rather than the footer alone: the peer runs the chat's
  only turn while the phone is offline, so the daemon holds a reading the phone never received and
  replay cannot name (the phone's cursor had no ring events for that chat before the cut). Before the
  reopen the method asserts the host's held reading is still `null`; after `openChatRow` it waits on
  `hostRepository(serverId).observeContextUsage(conversationId).filterNotNull().first()` itself, not
  only the footer text, because since [#1411](https://github.com/pyrycode/pyrycode-mobile/issues/1411)
  the footer renders a percentage from `session_settings` alone even with no reading — a footer-only
  check could pass without the ask. `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` now
  also carries a reconnect ask from the same trigger: it checks the held reading and footer while the
  link is still down, where no ask can race, takes the pre-cut reading as its growth baseline, and
  waits for the reconnect ask's answer to settle before accepting a larger post-turn reading, so the two
  answers (a `detail:"full"` count and a `detail:"summary"` estimate) cannot be read as the same thing.
  `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` and
  `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` also reconnect with a thread open and now
  carry the ask, without asserting on it. No rung-4 twin: the scripted `fakeclaude` path has no
  on-demand context querier to answer `request_context_usage` with a fixture. The dispatcher's
  post-verifier `python3 scripts/android-test-gate.py live` run (branch `feature/1410` at `92ba45dd43`,
  merged with `origin/main` at `ab368c3bf2`) executed 48, passed 47, failed 1 (a confirmed flake, not
  this branch's) and skipped 0, including the new method and all four named methods above passing — see
  [Verification status](#verification-status).

- **Coverage — added:** [#1337](https://github.com/pyrycode/pyrycode-mobile/issues/1337) adds
  `InteractiveStreamE2ETest.interactiveTurn_permissionPrompts_heldPerConversation` to the curated LIVE
  `TEST_TARGET` selector in `scripts/e2e-emulator.sh`, raising `LIVE_MINIMUM` to 41 in
  `scripts/android-test-gate.py` and pinned by `test_live_floor_matches_the_curated_list`. Chats A and
  B each raise a real prompt at once; both show in their own chat; answering A from the phone resolves
  only A's id while B's card stays mounted — proving the `HostModalState` hold-all fold (see
  [Current-modal state](knowledge/features/current-modal-state.md)) against a real daemon, not just the
  single-prompt-replacement case its sibling
  `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` covers. The method proves the
  phone's answer through the peer's `modal_dismissed` for A's id (source `remote`, outcome
  `allow_once`) and the dialog leaving A, and does **not** await A's `turn_end`: the daemon streams
  turn frames only for the conversation a message was last routed to (its `activeConversation`
  follow-active cursor), and B's send in the method moves that cursor to B, so A's `tool_use` /
  `turn_end` after the allow never reach any client — B's `turn_end` is still awaited, since B holds
  the cursor. No rung-4 twin: the ticket's acceptance criteria require only the rung-3 proof. PR
  #1406's `## Live tests` also names `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`
  and `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` as the live guards whose input this
  ticket changes (the former's input now goes `Open` → `Hidden` → `Open` across a reconnect, deduped only by
  `AttentionNotifier`'s ledger). The dispatcher's post-verifier focused `python3
  scripts/android-test-gate.py live --tests ...` run (branch `feature/1337` at `62ed2d1072`, merged with
  `origin/main` at `a8bb98ca56`) executed 7, passed 7, failed 0 and skipped 0, including all four named
  methods passing — see [Verification status](#verification-status).

- **Coverage — updated:** [#1309](https://github.com/pyrycode/pyrycode-mobile/issues/1309) rewrote
  `InteractiveStreamE2ETest.interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` to drop
  `leaveThread` / `openChatRow`: the thread now re-reads its run settings itself when the turn ends and when
  a sheet opens (see [Thread composer footer § Applied
  effort](knowledge/features/thread-composer-footer.md#applied-effort-889)), so a fresh subscription is no
  longer needed to see the settled reading. The method also asserts the fresh reply's `permissionMode` is
  non-empty and that the footer's permission label settles to it, through the same `awaitFooter` helper
  `appliedEffortFooter` already used for effort. The selector and `LIVE_MINIMUM` are unchanged at 43 — this
  is a behaviour change to an existing method, not a new one. The dispatcher's post-verifier
  `python3 scripts/android-test-gate.py live` run (branch `feature/1309` at `eee5056e5e`, merged with
  `origin/main` at `8537b1cf00` in a detached worktree) executed 43 methods, failed 0 and skipped 0,
  including this named method passing.

- **Coverage — updated:** [#1308](https://github.com/pyrycode/pyrycode-mobile/issues/1308) turns
  `InteractiveStreamE2ETest.interactiveTurn_modelChange_roundTripsAndStaysPerConversation` into a
  **one real-claude turn** scenario. Chat X stays inherited through one real ping reply; the test computes
  the expected marked row itself from the fresh model menu and the host repository's announced model, using
  its own copy of the value → `resolvedModel` → family tiers `ThreadRunConfig.selectedChoice` applies (the
  first tier with any candidate decides, more than one marks nothing). `awaitAnnouncedMark` replaces the
  former `awaitNoModelMarked`: it checks every non-default Claude row of the fresh menu, including rows that
  share a family label, so it cannot pass by skipping the rows most likely to be marked wrongly, and it
  requires either exactly the expected row's position in that label-ordered list (#1497 dropped the
  `resolved_model` detail line the rows once differed by) or no model row at all with
  the family note shown outside a radio — either way, that no radio reads "Default". X is then picked to a
  different published row in Run configuration and the pick is confirmed to survive leaving and reopening,
  no longer tracking the announcement; Y's own separately saved model is untouched throughout. The selector
  and `LIVE_MINIMUM` are unchanged at 43 — this is a behaviour change to an existing method, not a new one.
  The dispatcher's post-verifier `python3 scripts/android-test-gate.py live` run (branch `feature/1308` at
  `457c238304` merged with `origin/main` at `e902919aa6`) executed 43 methods, failed 0 and skipped 0,
  including this named method passing.

- **Coverage — updated:** [#1306](https://github.com/pyrycode/pyrycode-mobile/issues/1306) adapted
  `InteractiveStreamE2ETest.interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` to the inline
  permission surface (see [Permission-modal overlay](knowledge/features/permission-modal-overlay.md), which
  moved the request out of its dialog into `ThreadScreen`'s own message stream). `promptDialog` /
  `inPromptDialog` / `awaitPromptDialog` / `awaitNoPromptDialog` now key on the request card instead of the
  dialog's Cancel; `awaitReadPrompt` was rescoped mid-build to the card specifically, because scoping by "an
  ancestor holding Cancel" would also match the phone's own message naming the same file once the request is
  inline. The scenario now additionally ticks the session-grant checkbox and arms Allow in conversation A
  before leaving for B (no card, no A prompt text there), returns to A (the grant checkbox restored, the arm
  cleared), and confirms allowing needs two fresh taps — proving the phone's answer, A's session grant and
  B's peer resolution all survive the inline move. The selector and `LIVE_MINIMUM` are unchanged at 43 — this
  is an adaptation of an existing method, not a new one. The 2026-09-30 full live suite executed 43 methods,
  failed 0 and skipped 0, including this passing method; this was not a separate focused live run.

- **Coverage — added:** [#1286](https://github.com/pyrycode/pyrycode-mobile/issues/1286)
  adds `InteractiveStreamE2ETest.interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`
  to the full live selector, raising it and `LIVE_MINIMUM` to 44. The full live XML
  passed this method with 44 executed, 0 failed and 0 skipped. Its runnable rung-4
  twin is `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_offlineRetryRestoresScriptedReply`,
  selected by `offline-retry` and included in `scripted-all`. Run the twin with
  `python3 scripts/android-test-gate.py scripted offline-retry`; it proves the real
  outage and Retry path with `ping.jsonl`, using zero real-Claude turns. See
  [Offline Retry proof](#offline-retry-proof) for the boundary that excludes passive recovery.

- **Coverage — updated:** [#1296](https://github.com/pyrycode/pyrycode-mobile/issues/1296)
  extended `InteractiveStreamE2ETest.interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`
  to open the panel through the visible thread pill while real background work runs. The Actions path
  still checks the zero count. The 2026-09-30 full live suite executed 43 methods, failed 0 and skipped
  0, including this passing method; this was not a separate focused live run. The selector and
  `LIVE_MINIMUM` remain 43.

- **Coverage — shipped:** [#1208](https://github.com/pyrycode/pyrycode-mobile/issues/1208)
  put #481's existing `InteractiveStreamE2ETest.interactiveTurn_toolPrompt_rendersToolStepInThread`
  into the curated LIVE selector. The test checks the resolved row's accessible Done status, which
  remains present when a described header omits the tool name. The selector and `LIVE_MINIMUM` are
  43; the fresh full suite executed 43, failed 0 and skipped 0, including this passing method.

- **Coverage — shipped:** [#1252](https://github.com/pyrycode/pyrycode-mobile/issues/1252)
  restores `InteractiveStreamE2ETest.interactiveTurn_diagnosticBundles_stayOnTheirOwningHosts`
  through explicit `RelayConnectionRegistry.requestDebugBundle(serverId)` calls. With B selected,
  complete archives from A and B are checked for a marker logged only on A; no Settings or picker
  path is involved. The curated selector and `LIVE_MINIMUM` are 42. The 2026-09-29 full live
  gate executed 42, failed 0 and skipped 0, including the named method.

- **Coverage — shipped:** [#1251](https://github.com/pyrycode/pyrycode-mobile/issues/1251)
  restores `InteractiveStreamE2ETest.interactiveTurn_createEditArchiveChannel_readsPromptBack` to
  the curated live selector through the empty Channels section and selected host's list-toolbar
  Archive entry. Its post-reset prompt check follows a distinct real reply, and fixture restoration
  and conversation deletion remain in `finally`. `LIVE_MINIMUM` is 41; the 2026-09-29 full live
  gate executed 41, failed 0 and skipped 0, including this method. The separate focused run
  executed 1, failed 0 and skipped 0; its fresh XML names the method.

- **Coverage — retired:** [#1250](https://github.com/pyrycode/pyrycode-mobile/issues/1250)
  removed `InteractiveStreamE2ETest.interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`
  after the workspace UI was retired. That branch's curated selector and executed-test floor contained 40
  methods. The 2026-09-29 full live gate executed 40, passed 40, failed 0 and skipped 0;
  no replacement label scenario or focused run of the deleted method is required.

- **Coverage — shipped:** [#1249](https://github.com/pyrycode/pyrycode-mobile/issues/1249)
  restores `InteractiveStreamE2ETest.interactiveTurn_archiveRestore_roundTripsListMembership` and
  `InteractiveStreamE2ETest.interactiveTurn_twoHostsArchive_staysPerHost` through the list toolbar.
  The first proves the discussion's active → archived → active membership; the second proves host B's
  active and archived sets do not change during host A's archive and restore. Both wait for restore
  success before Back and recover fixtures created before an ID could be captured. The curated live
  selector and `LIVE_MINIMUM` are 40; the 2026-09-28 full gate executed 40, failed 0, skipped 0.

- **Coverage — shipped:** [#1246](https://github.com/pyrycode/pyrycode-mobile/issues/1246)
  restored `InteractiveStreamE2ETest.interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`
  to the curated rung-3 selector after replacing its pending-duration check with an acknowledged-write
  outcome and a fresh, same-session mode reply. The reply decides whether the first Manual approval
  choice confirmed early or remained an acknowledged no-op; the selected row must agree. A refusal
  fails separately. The Plan → Bypass approvals → Manual approval and outside-workspace Read witness
  checks remain. `LIVE_MINIMUM` is 38; the 2026-09-28 full live gate executed all 38 methods with no
  failures or skips, including this one.

- **Coverage — shipped:** [#1223](https://github.com/pyrycode/pyrycode-mobile/issues/1223)
  adds `InteractiveStreamE2ETest.interactiveTurn_rememberedModelAppliesToNewChatBeforeFirstMessage`:
  a model chosen in one chat is saved on a newly created chat before its first real Claude turn,
  while the source chat retains its saved choice. The curated live selector and `LIVE_MINIMUM`
  include 37 runnable methods; the 2026-09-28 gate executed all 37 without failures or skips.

- **Coverage — shipped:** [#1193](https://github.com/pyrycode/pyrycode-mobile/issues/1193)
  expands `InteractiveStreamE2ETest.interactiveTurn_modelChange_roundTripsAndStaysPerConversation`
  to choose a published ordinary row in Run configuration and verify each chat's selected radio after
  fresh daemon settings replies, including leaving and reopening the changed chat. The scenario stays
  active in the rung-3 live list. The #1193 selector excluded six other methods at the time, pending
  [#1245](https://github.com/pyrycode/pyrycode-mobile/issues/1245) and
  [#1246](https://github.com/pyrycode/pyrycode-mobile/issues/1246); the latter is now restored.
  Two older workspace-switching methods remain excluded. The selector had 36 runnable methods
  before #1223 added one.

- **Coverage — shipped:** [#1190](https://github.com/pyrycode/pyrycode-mobile/issues/1190)
  moved `InteractiveStreamE2ETest` chat creation to each host's Chats plus and Create confirmation.
  `interactiveTurn_saveAsChannel_promotesToChannelTier` uses that path;
  `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` creates and selects its folder
  through the thread picker; `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` checks daemon
  defaults despite saved app defaults; and `interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost`
  moves A's new chat to the chosen folder before the peer relabels it. The host-row-only
  `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` was retired. The curated selector
  contains 44 methods and the executed-test floor changed with it; the 2026-09-27 live gate passed all 44.

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

- **Coverage — shipped:** [#965](https://github.com/pyrycode/pyrycode-mobile/issues/965) added
  `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, the twenty-first curated `LIVE=1`
  method: the phone's own turn is held open on a command that never returns on its own, the composer's
  Stop control ends it with `stop_reason == "cancelled"` and disappears, and a same-thread follow-up
  gets a real reply afterward. Since #1357 it does not assert an Interrupted status label.
  It also extended `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` to assert the
  Reset session status area's wrapping-up phase live, causally held by the daemon's own real-claude
  wrap-up turn; the restarting phase has no live hold and stays proven only by `ScriptedResettingTest`.
  This is **single-device** Stop only.

- **Coverage — shipped:** [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981) restored
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, the twenty-second curated
  `LIVE=1` method, after [#977](https://github.com/pyrycode/pyrycode-mobile/issues/977) had excluded it.
  The allowed Read's reply reached the phone's repository — a new unit test rules out the fold as the
  cause — but the thread screen never scrolled a non-streaming newest row into view once the list
  overflowed, because `LazyListState` keeps its first visible row anchored by key under
  `reverseLayout = true`. A second composition-scoped effect in `ThreadScreen`, keyed on the newest row's
  identity rather than `isStreaming`, now follows any new newest row — streaming or not — unless the
  reader has scrolled away (the existing `userScrolledAway` yield, unchanged). `readReplyDiagnosis` gained
  `threadHeldToken`, so a future failure at this step names its cause from the phone side alone.

- **Coverage — shipped:** [#966](https://github.com/pyrycode/pyrycode-mobile/issues/966) added
  `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` and
  `interactiveTurn_questionAnswer_reachesTheAskingConversation`, the twenty-third and twenty-fourth
  curated `LIVE=1` methods, on a new fourth test daemon (the answer daemon) that is the only host the
  phone is ever paired `--allow-remote-permissions` with. They prove that a permission prompt (#815–#818)
  and a clarification question (#661) each show only in the conversation that raised them, with the
  permission prompt's decision context and don't-ask-again offer; that the phone's own answer reaches
  that conversation's claude and don't-ask-again lets a repeat command run again with no new prompt; and
  that a prompt or question another paired device resolves closes the phone's dialog with no tap on the
  phone. `assertBashRan` additionally requires a successful `Bash` tool call in the frames the peer
  recorded for the turn, so a reply claude could compute or recall cannot stand in for the command
  actually running. Real claude called `AskUserQuestion` reliably from the scripted prompt on the first
  live run, so the question scenario needed no deterministic-only fallback. See the dedicated paragraph
  under [What rung 3 is made of](#what-rung-3-is-made-of) for the daemon shape and both scenarios' steps.

- **Coverage — shipped:** [#967](https://github.com/pyrycode/pyrycode-mobile/issues/967) added
  `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`,
  `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` and
  `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`, the twenty-fifth, twenty-sixth and
  twenty-seventh curated `LIVE=1` methods. They prove that the composer footer's readings and a model
  change, the slash-command suggestions and a manual Compact session all still work after the phone's link
  is cut and restored, reusing #545's settings helpers and #850's `setHostLink` / `cycleHostLink` rather
  than repeating those scenarios, and that a background task real claude starts is tracked, through the
  main daemon's `--allow-remote-permissions` peer (the #950 path), from its start through the Actions
  menu's live count and panel to its finish. The live run found that real claude sends an empty
  `background_task_roster` unprompted after a finish, which the panel's wholesale-replace fold (#677) then
  reads as no tasks at all — so a finished task's end state in the panel is "Finished" or "No background
  tasks" depending on timing, never both durably; the method accepts either and still rejects an unreported
  panel. See the dedicated paragraph under [What rung 3 is made of](#what-rung-3-is-made-of). Banner
  notices (`BannerNoticeRowTest`) and model refusals (`ModelRefusalRowTest`) remain this family's
  deterministic-only cases — real claude does not raise either on demand.

- **Coverage — shipped:** [#955](https://github.com/pyrycode/pyrycode-mobile/issues/955) added
  `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` and
  `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`, the twenty-eighth and
  twenty-ninth curated `LIVE=1` methods, moving the managed device `pixel2Api33Atd` from the `aosp-atd`
  image to `google-atd` (Play services, so the emulator can hold an FCM token). They are the first live
  proof of [#685](https://github.com/pyrycode/pyrycode-mobile/issues/685)'s alerts against a real FCM push
  from the production relay ([pyrycode-relay#130](https://github.com/pyrycode/pyrycode-relay/issues/130))
  rather than a synthetic message: one alert for a turn that ends while the app is backgrounded, whose tap
  opens the right thread, and exactly one alert for a permission prompt that surfaces while the app is
  away, unchanged across a second reconnect inside the daemon's wake-coalescing window. Two earlier live
  attempts on this branch failed both methods at the background step; the cause,
  `sendAppToBackground`'s `withTimeoutOrNull` block returning `null` on success indistinguishably from a
  timeout, survived two verifier PASSes before a closer read of the code, not a stack trace, found it. A
  second, unrelated design flaw — AC2's first design waited for a re-shown-prompt alert that a retained
  permission modal never emits — was caught by review before any live run reached that step, and fixed
  with a bounded settle instead. See the dedicated paragraph under
  [What rung 3 is made of](#what-rung-3-is-made-of) and [Verification status](#verification-status) for
  both in full.

- **Coverage — shipped:** [#1016](https://github.com/pyrycode/pyrycode-mobile/issues/1016) added
  `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` and
  `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart`, the thirtieth and thirty-first curated
  `LIVE=1` methods, proving that the phone's own attached files (#983–#985) reach another client — the
  `SecondClientPeer` desktop stand-in, extended with `uploadAttachment` / `retrieveAttachment` / `history`
  — with their exact bytes, and that a file claude hands over with `send_file` survives a restart in the
  phone's own thread cache. The live run found that the daemon drops a message's `attachment_ids` from
  history entirely, filed as [#1020](https://github.com/pyrycode/pyrycode-mobile/issues/1020); the
  ticket's third method, `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` (another client's
  upload, named on a message, read back after a history reload with the thread cache cleared), shipped
  `@Ignore`d and out of the curated list until #1020 landed (see below). See the dedicated paragraph under
  [What rung 3 is made of](#what-rung-3-is-made-of) for the daemon-fact and JUnit-ordering lessons, and
  [Verification status](#verification-status) for the run-by-run evidence.

- **Coverage — hardened:** [#1029](https://github.com/pyrycode/pyrycode-mobile/issues/1029) fixed a flake in
  `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` (#967). On the live relay the connection the
  reconnect step opens can drop and be redialled by `RelayConnectionSupervisor` within about a second —
  [#1039](https://github.com/pyrycode/pyrycode-mobile/issues/1039) tracked why before the cause was found;
  [#1051](https://github.com/pyrycode/pyrycode-mobile/issues/1051) traced it to the relay's per-phone outbox
  overflowing on the daemon's connect-time reconcile burst, fixed in pyrycode/pyrycode-relay#154 —
  and the scenario held a one-time snapshot of the pre-redial connection's repository through
  `hostRepository`, so its slash-command read timed out on a connection already torn down. Two generic
  helpers, `firstOnLive` and `callOnLive` in
  `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`, read through the host's *current*
  connection instead of a one-time snapshot; the menu read and `answerChat`'s create/rename now use them. The
  assertion is unchanged: it still requires the commands to arrive on the connection that is live when they
  arrive, never any connection. Other one-shot readers in `InteractiveStreamE2ETest` (`freshSettings`,
  `publishedMenu`, the archive/restore step) still hold a single-connection snapshot and are exposed to the
  same drop; reuse these helpers there if they flake. See [Relay repository coordinator § Edge cases /
  limitations](knowledge/features/relay-repository-coordinator.md#edge-cases--limitations) for the underlying
  contract.

- **Coverage — shipped:** [#1020](https://github.com/pyrycode/pyrycode-mobile/issues/1020) let a stored
  user `message` entry's `attachment_ids` survive into the reduced history row — the daemon side landed as
  pyrycode#2596, and the `TYPE_MESSAGE` arm now reuses the same `storedAttachmentReferences` shape filter,
  dedup and 32-id cap the `TYPE_SEND_MESSAGE` arm already used (#983), gated to `Role.User` rows (see
  [Remote conversation repository — reads and the thread store — history
  paging](knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md#history-pages-fold-into-the-same-thread-645)).
  `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` dropped its `@Ignore` and rejoined the
  curated `LIVE=1` list as its thirty-second method — the method itself already existed, unchanged, on
  `InteractiveStreamE2ETest`. The ticket also restored #1016's `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`
  to reading its ids from the peer's own `history(chatX)` call instead of the phone's cached sent row,
  deleting `awaitCachedSentAttachmentIds`. The dispatcher's post-verifier live run passed at 32 executed,
  24 passed outright and 8 (including this ticket's own two methods) failing once and passing on a same-tree
  re-run — a known suite-wide flake class, not attributed to this branch.

- **Coverage — shipped:** [#1050](https://github.com/pyrycode/pyrycode-mobile/issues/1050) added
  `interactiveTurn_markdownLink_opensLiveNoteInReader`, wiring a markdown-path link in an assistant reply to
  [the live linked-note reader](knowledge/features/markdown-reader-screen.md#linked-note-live-since-1050)
  rather than leaving the tap inert. The note comes from the conversation's own workspace, not another client —
  `runningToolPeer()`'s `SecondClientPeer` is present at #1050's own ship too, for `allowPromptsUntil` to gate
  the shell permission, but only ever answers prompts; it does not carry the note's bytes the way #1016's/
  #1020's attachment scenarios ride a peer. #1067 below is the first ticket to have that same peer originate a
  chat message. `scripts/e2e-emulator.sh`'s `LIVE` list comment reads "33
  methods and 36 turns," counting from `main`'s 32/34 at merge time; that comment was already one off from
  the list's actual method count before this ticket (a pre-existing drift, not introduced here) and
  `LIVE_MINIMUM` (a floor, not an exact count) still holds. The dispatcher's post-verifier live run executed
  33, passed 25, failed 8 — all eight failures passed on a same-tree re-run (a known suite-wide flake class);
  this ticket's own method was not among them and passed outright.

- **Coverage — shipped:** [#1667](https://github.com/pyrycode/pyrycode-mobile/issues/1667)
  moves the reader to the shared Below Actions overlay. The existing
  `InteractiveStreamE2ETest.interactiveTurn_markdownLink_opensLiveNoteInReader` still reaches Refresh;
  no new scenario or deterministic twin was added. The fresh full dispatcher live suite on
  2026-10-05 executed 53, passed 53, failed 0, skipped 0, with this named method present and passed
  in retained XML. See [reader testing](knowledge/features/markdown-reader-screen.md#testing)
  for the command, revisions and linked gate evidence; no separate focused run was required.

- **Coverage — shipped:** [#1067](https://github.com/pyrycode/pyrycode-mobile/issues/1067) added
  [the reader's copy-and-refresh overflow menu](knowledge/features/markdown-reader-screen.md#copy-and-refresh-menu-since-1067)
  and, in rework after the first verifier pass, extended #1050's
  `interactiveTurn_markdownLink_opensLiveNoteInReader` rather than adding a new method: with the reader still
  open after the first tap, the method's existing `SecondClientPeer` — until now only ever an answerer of
  permission prompts in this method — sends the rewrite prompt itself, so claude rewrites the note; the test
  chooses Refresh from the reader's own overflow, and asserts the new heading with the old one and
  "Couldn't open file" both absent, before the existing back-and-reopen assertions run unchanged. Still two real
  claude turns — the rewrite prompt moved from the phone's composer to the peer's rather than adding a third —
  so the suite's method and turn counts are unchanged from #1050's. The copy formats (markdown, plain text,
  HTML) are local conversions with no daemon round trip and are covered by `MarkdownConversionsTest` and
  `MarkdownReaderScreenTest` instead; no rung-3 scenario of their own. The dispatcher's post-verifier live run
  executed 33, passed 25, failed 8 on a nondeterministic same-tree-passes-on-rerun basis (a known suite-wide
  flake class, none of the eight involving this method); this ticket's own extended method passed outright.

- **Coverage — shipped:** [#1021](https://github.com/pyrycode/pyrycode-mobile/issues/1021) added
  `interactiveTurn_muteChannel_roundTripsThroughTheHost`, the thirty-fourth curated `LIVE=1` method. It
  proves the Mute notifications checkbox `EditChannelModal` gained round-trips through the host: the
  reopened modal can only read the flag checked because the daemon stored `set_conversation_muted` and
  echoed it back in `conversation_updated`, since nothing is patched locally. Zero real-claude turns —
  the channel's promote, mute, unmute and delete are all daemon round-trips. See the dedicated paragraph
  under [What rung 3 is made of](#what-rung-3-is-made-of).

- **Coverage — hardened:** [#1036](https://github.com/pyrycode/pyrycode-mobile/issues/1036) fixed the
  intermittent 30 s/90 s timeouts in `interactiveTurn_peerStartedTurn_continuesOnPhone` (#848) and
  `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` (#965). The kept `daemon.log` from
  #1029's own 2026-09-24T21:17Z gate run showed the `SecondClientPeer`'s relay connection closing 39–48 ms
  after its handshake, with no `send_message` reaching the daemon; a #1021 gate run's kept log showed the
  same silent-peer shape independently. This matches the 35–45 ms handshake-to-close window
  [#1051](https://github.com/pyrycode/pyrycode-mobile/issues/1051) traced to the relay's per-phone outbox
  overflowing on the daemon's connect-time reconcile burst (fixed in pyrycode/pyrycode-relay#154); it is the
  same drop [#1039](https://github.com/pyrycode/pyrycode-mobile/issues/1039) tracked on the phone's own
  connections before the cause was found —
  and the peer, unlike the app, never redialled: `SecondClientPeer.open` dialled
  once, so a peer whose first link died stayed dead for the rest of the scenario. `SecondClientPeer` now
  dials through a new `RedialingLink` (`app/src/sharedTest/.../e2e/RedialingLink.kt`, JVM-tested): a link
  counts as up only once it answers a `list_conversations` probe, and a link that ends is redialled behind
  the scenario's own timeout. `request_history`, the settle probe, and prompt answers (`modal_answer` /
  `question_answer`, keyed by their `answer_token`, which the daemon deduplicates) resend on the
  replacement link; `send_message` never does, because the daemon does not deduplicate it — a request that
  is not safe to resend fails with a named `AssertionError` instead of a bare timeout. A prompt the daemon
  re-sends to a new connection (`modal_shown` / `question_shown`, reconciled by the daemon on every new
  interactive connection) is recorded once, so the existing "one prompt raised" counts stay true across a
  redial. Both scenarios now name the peer step that timed out and the peer's link state
  (`SecondClientPeer.linkState()`) instead of a bare `TimeoutCancellationException`; `hostConversationIds`
  and the new `newHostConversationId` follow the host's own redial through `firstOnLive` (#1029's helper),
  naming the phone-side read on a timeout. No assertion changed. **Known gap:** the peer sends no resume
  cursor, so a `turn_end` that arrives while it is between links is not replayed — unobserved in any kept
  log, and now surfaced as a named timeout rather than a silent one if it happens. The post-fix live gate
  (2026-09-24) executed all 29 curated scenarios with `peerStartedTurn` passing outright; `stopRunningTurn`
  failed once with no captured failure message and passed on an immediate re-run of the same merged tree,
  so the dispatcher treated it as the suite's nondeterminism rather than this branch's — see [Verification
  status](#verification-status) for the run. Whether that was the same closed-link race the fix targets or
  a different wait is not established; a repeat should be read against `SecondClientPeer.linkState()` in
  the failure message before assuming the old cause.

- **Coverage — shipped:** [#1017](https://github.com/pyrycode/pyrycode-mobile/issues/1017) added
  `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`,
  `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` and
  `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`, the thirty-fifth through
  thirty-seventh curated `LIVE=1` methods. They prove that a link dropped mid-upload or mid-retrieval
  recovers cleanly once restored and retried — one message with its bytes, one failed row that Retry
  loads — and that a file pending or sent on one host of #847's colliding conversation id never reaches
  the other host. Each cut is fired deterministically from the app's own `RelayLog` line (never a timer),
  so it can't race the daemon's reply. One real-claude turn each. See the dedicated paragraph under
  [What rung 3 is made of](#what-rung-3-is-made-of) and [Verification status](#verification-status) for
  the mobile/daemon revisions and the first live run's result.

- **Coverage — hardened:** [#1059](https://github.com/pyrycode/pyrycode-mobile/issues/1059) made a
  `SecondClientPeer` wait on `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` (#955)
  name why it ended instead of running out an anonymous 90 s timeout. The ticket was filed against a
  pre-#1036 tree, where the peer never redialed a dropped link; by the time it was implemented, #1036 had
  landed, so "the peer's session closes" could no longer mean "one link drops" — that is exactly the event
  #1036 now survives via `RedialingLink`, and failing a wait on it would reintroduce the flake. `closed` (in
  `SecondClientPeer`) became a `MutableStateFlow<Boolean>` that `close()` flips before tearing down; a new
  `awaitPeer` helper (`app/src/sharedTest/.../e2e/PeerWait.kt`, JVM-tested in `PeerWaitTest`) wraps each of
  the class's seven `withTimeout` waits so one still pending when the peer closes for good fails at once
  with `AssertionError("peer session closed while awaiting $what")`, naming the frame or request it
  awaited. A wait that runs out its timeout on a peer that stays open is unchanged — still a bare
  `TimeoutCancellationException` — because five scenario sites (`peerStep`, `awaitTurnEnd`,
  `assertPeerAnswers`, and two inline catches) catch that type to add their own context; the plan's first
  design would have converted the timeout into a named `AssertionError` too, and was reworked away once
  those catch sites turned up (see the plan's own Revisions entry). The flaky test itself now runs its four
  bare peer calls (`open`, `awaitPermissionModal`, `allowOnce`, `awaitFrame` for `turn_end`) through the
  existing `peerStep`, so a future timeout there names the step and `SecondClientPeer.linkState()` instead
  of a bare `Timed out waiting for 90000 ms`. No timeout constant changed. **Known limit (verifier NIT):**
  every peer wait today runs in `runBlocking` on the test thread and `close()` runs later on that same
  thread in `finally`, so the fail-fast path cannot fire in any current scenario — it is proven only by
  `PeerWaitTest`'s virtual-time cases, and will matter once a scenario closes the peer from another
  coroutine while a wait is pending. [#1063](https://github.com/pyrycode/pyrycode-mobile/issues/1063) gave
  the close failure a named type, `PeerSessionClosedError : AssertionError` (same message), so
  `assertPeerAnswers` can tell it apart from a `TimeoutCancellationException` without catching every
  `AssertionError`; both now go through a new `requirePeerAnswer(timeoutMs, request)` helper (`PeerWait.kt`)
  that labels each as "a relay or daemon fault" with matching wording — so `assertPeerAnswers` no longer
  catches `TimeoutCancellationException` directly itself, `requirePeerAnswer` does on its behalf. The known
  limit above still applies to this path (same `runBlocking`-on-test-thread shape), so it remains proven
  only by `PeerWaitTest`, not a live scenario, pending pyrycode/pyrycode-relay#154 on the production relay.

- **Coverage — hardened:** [#1064](https://github.com/pyrycode/pyrycode-mobile/issues/1064) made
  `allowPromptsUntil` (the ten-site helper in `InteractiveStreamE2ETest` that polls a peer's recorded
  frames while allowing permission prompts on the way) fail at once when the peer closes, instead of
  running out its 30 s/90 s timeout. It reuses #1059's `SecondClientPeer.awaiting` (now `internal`, was
  `private`) rather than adding a second closed-session signal: the poll loop now runs inside
  `peer.awaiting(frame, timeoutMs) { ... }`, and a new `frame` parameter, defaulting to `turn_end`, names
  what each caller is waiting for, so the fail-fast can name the frame instead of a bare
  `AssertionError`. Eight callers keep the default; the two background-task calls in
  `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` pass `background_task_started` and
  `background_task_updated`. A peer that stays open is unchanged — the same
  `TimeoutCancellationException` is still caught and rethrown with the step's `failure` text — now also
  carrying `peer.linkState()`. No timeout constant changed. The ticket was filed against
  `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel`'s two recorded flakes (the #1017 and
  #1020 gate at `b21e25a8c4`, and the #1067 gate at `5ff587db7f`), both on pre-#1036 trees where the peer
  never redialed a dropped link. The original cause on file, "the peer never redials," no longer holds on
  main now that #1036 landed a redialing `SecondClientPeer` — a lesson for future flake diagnosis: check
  which harness commits the flaking tree actually contained (`git merge-base --is-ancestor`) before
  trusting a stated cause for a fix. `PeerWaitTest` covers the fail-fast in JVM virtual time and
  `compileDebugAndroidTestKotlin` checks the wiring; AC 3, the live pass with
  pyrycode/pyrycode-relay#154 deployed to the production relay, is the dispatcher's post-verifier
  real-claude gate.

- **Coverage — hardened:** [#1456](https://github.com/pyrycode/pyrycode-mobile/issues/1456) made step
  5's follow-up-ping wait in `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` (#965)
  name which layer lost the reply on a timeout, instead of a bare `ComposeTimeoutException`. A #1410 gate
  run saw the Stop step pass (the peer recorded `turn_end`/`cancelled`, the phone showed Interrupted) and
  then time out in the old `awaitDisplayedPingReply`, with Claude's `ping` reply proven to have reached
  the daemon from the kept transcript but never displayed on the phone — a different shape from #1036's
  dead peer link. The step now goes through `awaitPingReplyNamingLayer`
  (`InteractiveStreamE2ETest.kt`), which on a `ComposeTimeoutException` reads `PingReplyEvidence`
  (`app/src/sharedTest/.../e2e/PingReplyDiagnosis.kt`, JVM-tested in `PingReplyDiagnosisTest`) and throws
  an `AssertionError` naming the first of: host (the peer recorded neither the ping turn's `turn_end` nor
  its reply), phone repository (the peer got the reply, the phone's live repository holds none), thread
  screen (no matching bubble is composed) or thread list (one bubble is composed but not displayed) —
  plus every raw reading, so a reader can disagree with the verdict. The passing path is unchanged; only
  the `ComposeTimeoutException` catch is new. The code reading this ticket did for all three candidates
  (posted on the ticket) found none reproducible from the code: a fresh `turn_id` and reset `seq` on every
  daemon turn (`interactiveTurnEmitterV2.startTurnIfNeeded`/`endTurn` in `../pyrycode/cmd/pyry`) rule out
  the host conflating turns; `HistoryPageReducer.withAssistantDelta` drops a delta only on a repeated
  `seq` or a colliding key, which a fresh `turn_id` cannot hit, and `ThreadFold.render` never removes a
  finished row, ruling out the phone's own reduction; Stop, Interrupted and the composer all sit outside
  the reversed `LazyColumn` and the accepted send re-follows through `FollowNewestEnd`'s `sentMessages`
  effect, ruling out lost scroll-follow. So this ticket files no `pyrycode/pyrycode` ticket and adds no
  pinning test — a genuine single-connection frame loss is still possible on the daemon side
  (`v2.push.drop`, `stream_turn.not_active`) but logs at Debug only, so a future "phone repository" verdict
  from this wrapper is the cue to check those lines first. **Known gap (verifier SHOULD FIX, not
  blocking):** `PingReplyEvidence.failure`'s host check, `peerTurnEnds >= expectedTurnEnds ||
  peerSawReply`, clears the host from the `turn_end` count alone; if Claude answers with anything other
  than exactly `ping`, the message blames the phone repository for a reply the host never sent as `ping`.
  The raw readings printed alongside the verdict still show the truth, which is why the verifier passed
  it rather than sending it back. The post-verifier live gate (2026-10-02) executed all 50 curated
  scenarios with `stopRunningTurn` passing outright — see [Verification status](#verification-status) —
  so the wrapper's passing path is live-proven; its failing path (which layer gets named) has no live
  exercise yet, by nature, since nothing has reproduced the underlying loss since #1410.

- **Coverage — pending:** [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)
  owns the **cross-device** Stop scenario in `InteractiveStreamE2ETest`: with real turns
  in A and B and another device most recently using A, Stop while the phone views B
  must end B while A keeps running. #626's ViewModel/coordinator/repository target
  assertions, `ScriptedThreadRenderTest` affordance coverage and #965's single-device live proof are not
  a substitute; none of them establish the cross-device outcome.
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
  Archived screen on its own (at #740 the full round trip still used Settings; #1249 moved it to the
  same toolbar entry; #1665 migrated `InteractiveStreamE2ETest.interactiveTurn_listArchiveEntry_opensArchived`
  and the round trip to Open menu → Archive; previously proven only at the event boundary,
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
  `OkHttpRelayTransport`, `NoiseSessionFactory` and `NoiseSessionPump` with a process-scoped exact
  host/token identity and its own `pyry pair` token, never the phone's credentials — continues on the phone: always-on (whether the
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
  footer's context circle announces the computed percentage (`context_usage`, published after every
  completed turn): the scenario sends the ping prompt, awaits the reply, and waits for the
  `CONTEXT_USAGE_TEST_TAG` node's content description to contain an available percentage, with no percentage hard-coded. Always-on (a
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
  with `origin/main` at `01042d232b`) executed all fifteen with no failures or skips. The #1660 migration retains this rung-3 scenario in
  `InteractiveStreamE2ETest` and moves footer reading, update, reopen and reconnect assertions to
  content descriptions; normal, warning and high readings all carry a percentage, while unavailable
  carries none. No new `DeterministicInteractiveStreamE2ETest` twin was added. The fresh full dispatcher
  live suite on 2026-10-04 (`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py live`),
  branch `38502955e6` merged with `origin/main` at `4fe49387ad`, executed **53, failed 0, skipped 0**.
  Its fresh XML report explicitly contains
  `InteractiveStreamE2ETest.interactiveTurn_pingPrompt_footerShowsContextUsage` with no failure or skip:
  **PASS**, from the full suite, not a separate focused run. See the
  [gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1660#issuecomment-5982012362).
  The report is `2026-10-04T16-01-32-783Z_real-claude-gate_#1660.log` under the dispatcher repository's
  `logs/`. For full-image footer captures, an available description may precede the hardware frame;
  `ThreadDesignCaptureTest` therefore waits for the warning arc's yellow pixels as well as its 84%
  description. Geometry-only ATD captures do not prove hardware pixels; API-retry status
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
