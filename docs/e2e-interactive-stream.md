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
   the channel-list FAB → Workspace Picker → create a folder → land in a fresh discussion whose
   workspace is the created folder → send the ping prompt in it → re-open the picker and confirm the
   folder shows in "Recent" (exercises the #564 create wire and #565 recents wire end to end against
   real claude); a **new-session** scenario (#541): with a live, exercised session, open the thread
   overflow menu → tap "New session" → the daemon runs `/clear` and broadcasts `session_transition`, and
   the thread renders the session-boundary delimiter (exercises the #540 fire-and-forget wire and the
   #336 fold end to end against real claude); and a **delete-conversation** scenario (#554): rename a
   discussion to a runtime-unique name, confirm it is present on the channel list, then delete it from the
   thread (overflow → "Channel info" → "Delete" → the "Delete conversation?" dialog → confirm) and assert
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
   conversation-scoped daemon round-trip).
   Semi-deterministic. A **`LIVE=1` variant (#527, extended #566 / #541 / #554 / #551 / #562)** runs a
   **curated sextet** of scenarios (ping + create-workspace-folder + new-session + delete + archive-restore
   + change-workspace, still 3 real claude turns — delete, archive-restore, and change-workspace spend none)
   against the **production relay** over `wss://`
   (TLS) — the pre-ship gate that catches the live-environment failure class a local relay cannot; see
   [Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay).
4. **Emulator + deterministic host** ← **shipped (#431).** The same real app + Noise/relay path, but
   claude is swapped for #642's scripted `fakeclaude` backend replaying a fixed JSONL fixture. No real
   claude, **zero claude turns**; re-running back-to-back yields the same pass. Run it with
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

## What rung 3 is made of

| Piece | File |
| --- | --- |
| Render fix (#337): fold `assistant_delta` into a streaming assistant row, finalize on `turn_end` | `app/.../data/repository/RemoteConversationRepository.kt` (`applyAssistantDelta`, `finalizeAssistantTurn`) + unit tests in `RemoteConversationRepositoryTest.kt` |
| Headless emulator via Gradle Managed Devices | `app/build.gradle.kts` (`testOptions.managedDevices`, device `pixel2Api33Atd`) |
| Test-only credential injection seam | `app/src/androidTest/.../e2e/E2eInstrumentationRunner.kt` + `E2eTestApplication.kt` |
| The instrumented test | `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` |
| Host orchestration | `scripts/e2e-emulator.sh` |

Rung 3 covers eight scenarios on this one harness: the **ping** happy path (a constrained reply renders);
a **tool-use** scenario (#481 — a constrained prompt makes real claude run a shell tool, asserting the
tool step renders, keyed tolerantly on the verbatim tool name `"Bash"` in the tool-row header); a
**thinking-spinner** scenario (#482 — a pure-reasoning prompt makes real claude think a beat, asserting
the spinner is displayed mid-turn, keyed tolerantly on the `cd_thread_thinking` content-description); a
**create-workspace-folder** scenario (#566 —
`interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`: long-press the FAB → Workspace
Picker → create a folder → land in a fresh discussion whose workspace is the created folder → send the
ping prompt into it → re-open the picker from the channel list and assert the folder shows in "Recent",
proving the #564 create wire and #565 recents wire end to end against real claude); a **new-session**
scenario (#541 — `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`: prove the session is live
with the ping, then open the thread overflow menu → tap "New session" → the daemon runs `/clear` and
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
claude turns — create-folder and change_workspace are conversation-scoped daemon round-trips). The tool-use
test asserts the **durable** terminal signal — the tool name in the resolved row —
not the transient running spinner: rung 3 has no scripted backend to hold the turn open, so racing the
spinner over a real relay turn is the "never on timing" failure the [Constraints](#constraints) forbid
(it is why rung 4's `tool` scenario needed a two-drop fence).

The **new-session** scenario (#541) is **always-on** (not `@Ignore`d): the delimiter is a **durable**
artifact that survives the turn — unlike #482's transient spinner — so it belongs in the always-on gate,
like #481's tool-name row. Its load-bearing matcher is the delimiter's reason-independent explanation
line (`"Claude doesn't remember messages above this line"`), which can **only** come from the rendered
`SessionBoundaryDelimiter` — **not** the `"New session"` label prefix, which is byte-identical to the
overflow menu item and so is not selective at rung 3. The delimiter's **absence is asserted before** the
New-session tap (a deterministic guard, no extra claude turn), so its later appearance is attributable to
the action. `new_session` is **fire-and-forget** (pyrycode#831, #540), so nothing waits on or asserts an
ack — the only observable is the post-broadcast delimiter. Total real-claude cost: **one** turn (the ping
proving the session is live); `/clear` spends none.

The **delete-conversation** scenario (#554) is likewise **always-on** (not `@Ignore`d): both post-conditions
are **durable** structural facts — a conversation is in the channel list or not, and the thread has popped
back or not — so, like #541's delimiter and #481's tool-name row, it belongs in the always-on gate. The
seeded discussion is given a **runtime-unique** name (`"e2e554-" + System.currentTimeMillis()`) via
**Rename** (not "Save as channel", which would leave a dedicated-workspace folder accumulating on the
operator's real `~/pyry-workspace` across runs), so its presence is observed on the list *before* the delete
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

The **thinking-spinner** scenario (#482) is the **flakiest** rung-3 scenario and ships **`@Ignore`-gated /
manual**: the spinner has **no durable equivalent** of the tool name — once real claude emits its first
token, `turn_state` flips to `responding`, `isThinking` goes false, and `ThinkingIndicator` early-returns,
leaving nothing on screen. With no scripted backend to hold the turn open and no way to imperatively pause
real claude (the levers rung 4's two-drop fence and the #432 component twin's `pushTurnState` have), the
mid-turn window cannot be made deterministic, so the developer cannot prove reliability without operator
infra. It therefore lands as a documented manual case (presence-only, tolerant, keyed on
`cd_thread_thinking`; **no** absence-after-end assertion that would race a 2nd turn, and **no** negative
control — the asserted token is a production content-description, not a claude-output substring). The
operator un-ignores to attempt the run and may promote it to always-on if a pure-reasoning prompt yields a
catchable window; otherwise it stays manual. See the
[Assumptions](#assumptions-to-confirm-on-first-run) entry on the screen-sourced thinking window.

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

## How to run

```bash
bash scripts/e2e-emulator.sh
```

Prerequisites on the host:

- `pyrycode-relay` and `pyry` on PATH (override with `RELAY_BIN` / `PYRY_BIN`).
- The operator's claude is authenticated on the host — the daemon spawns real claude. The interactive
  path is Max-subscription covered, so this does **not** meter tokens.
- Android SDK with the `aosp-atd` API 33 system image. AGP auto-provisions it on first run, which needs
  the SDK `cmdline-tools` installed and the image licence accepted (`sdkmanager --licenses`). On this
  machine `cmdline-tools` was absent at authoring time — install it before the first run.
- `python3` (decodes the base64url pairing payload).

The script: starts the relay → mints a device token with `pyry pair` and parses the payload → starts
the daemon (`PYRY_MOBILE_V2=1`, pointed at the loopback relay) → runs `pixel2Api33AtdDebugAndroidTest`
with the four values injected as instrumentation arguments → tears everything down.

## Pre-ship gate

The live rung-3 real-claude e2e is the mobile **pre-ship gate** — the command an operator runs so they
are never the **first** real-stack execution. It is the mobile parallel of the daemon's
`make e2e-realclaude`. Run it with:

```bash
bash scripts/e2e-preship-gate.sh
```

The wrapper bakes in the `LIVE=1` + `e2e-live` isolation defaults (see
[Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay) below), so there is no env-var
incantation to remember — the six curated `@Test` methods (ping + create-workspace-folder, #566;
new-session, #541; delete, #554; archive-restore, #551; change-workspace, #562) ride the wrapped mode.

**When to run:**

- **before installing a new APK build on a device**, so the operator is never the first to discover the
  real stack can't answer a live send; and
- **whenever a daemon or relay change touching the mobile surface lands** — run it alongside the daemon's
  own `make e2e-realclaude`.

**Cost:** three real claude turns across six curated methods (the ping scenario + the
create-workspace-folder scenario, #566 + the new-session scenario, #541 — one turn each; `/clear` spends
none; the delete scenario, #554, the archive-restore scenario, #551, and the change-workspace scenario,
#562, each spend **none** — create/rename/delete/archive/restore/change-workspace are daemon round-trips),
a few minutes of wall clock, subscription-covered (it does **not** meter tokens).

For the full mechanics — relay URLs, the isolated `e2e-live` instance, prerequisites, and first-run
assumptions — see [Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay). The operator-facing
summary is in [README § Pre-ship gate](../README.md#pre-ship-gate); the two are cross-linked so they
cannot drift.

## Live mode (rung 3, live relay)

`LIVE=1` runs a **curated sextet of rung-3 scenarios** — the real app on the emulator, a host `pyry`
daemon, and **real claude** — but against the **production relay** (`wss://pyrycode-relay.pyryco.de`)
over TLS instead of a local loopback relay. This is the pre-ship gate: the operator must never be the
**first** real-stack execution, and a local relay structurally cannot catch a live-environment failure
(the 2026-07-03 connect-drop loop was a five-week-stale relay deploy, invisible to any local run).

```bash
LIVE=1 bash scripts/e2e-emulator.sh
```

**What it runs.** Six curated methods, passed as a comma-separated `class#method` list:
`InteractiveStreamE2ETest#interactiveTurn_pingPrompt_streamsPingReplyIntoThread`,
`InteractiveStreamE2ETest#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (#566),
`InteractiveStreamE2ETest#interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (#541),
`InteractiveStreamE2ETest#interactiveTurn_deleteConversation_removesFromListAndClosesThread` (#554),
`InteractiveStreamE2ETest#interactiveTurn_archiveRestore_roundTripsListMembership` (#551), and
`InteractiveStreamE2ETest#interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` (#562), so exactly
**three real claude turns** are spent per run — the delete, archive-restore, and change-workspace scenarios
each add a method, not a turn (create/rename/delete/archive/restore/change-workspace are daemon round-trips).
The full class also includes the
#481 tool-use test, which
stays excluded from LIVE (an extra turn, cost). `LIVE=1` is **mutually exclusive with `DETERMINISTIC=1`**
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
- **Isolated instance, real HOME.** The daemon and `pyry pair` run as `-pyry-name=e2e-live` under the
  operator's **real `$HOME`** — unlike rung 4's isolated `/tmp` HOME. Real claude needs the operator's
  `~/.claude` subscription auth, which an isolated HOME would strip, so isolation here is by **instance
  name**: identity + `devices.json` + `conversations.json` live under `~/.pyry/e2e-live/`, so the
  production instances on this Mac and pyrybox (different names) are never read or written. That directory
  **persists across runs** (a stable test identity); `cleanup()` never removes it.

Prerequisites (on top of the "How to run" list):

- The operator's claude authenticated on the host (as default rung 3) — Max-subscription covered, so it
  does **not** meter tokens.
- **No relay binary needed** (the daemon dials the production relay; the `RELAY_BIN` preflight is skipped).
- The emulator needs outbound internet + DNS + a system-trusted TLS cert for the relay host. It reaches
  the public relay over its own NAT'd internet — **not** the `10.0.2.2` host alias, which is loopback-only.

Cost: **three real claude turns per run across six curated methods** (ping + create-workspace-folder, #566
+ new-session, #541; `/clear` spends none; delete, #554, archive-restore, #551, and change-workspace, #562,
each spend none — create/rename/delete/archive/restore/change-workspace are daemon round-trips), a few
minutes of wall clock, subscription-covered.

First-run assumptions to confirm (grounded in the design, unverified end to end):

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
  probe before booting the daemon would fail faster than the 30 s connect timeout — deferred as an operator
  follow-up (the round-trip already surfaces a down relay).

## Deterministic mode (rung 4)

`DETERMINISTIC=1` swaps real claude for the scripted `fakeclaude` backend (pyrycode #642), which
replays a fixed claude-format JSONL fixture. The emulator app and the Noise/relay path stay real;
**only claude is scripted**, so the run spawns no real claude and consumes **zero claude turns** —
cheap enough to run often, and deterministic enough to re-run back-to-back for the same pass.

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh
```

Extra prerequisites (on top of the rung-3 list, minus the claude-auth one — rung 4 needs no claude):

- Either `PYRYCODE_SRC` (a local pyrycode checkout) **+ `go`** to build `fakeclaude` from
  `internal/e2e/internal/fakeclaude`, **or** `FAKE_CLAUDE_BIN` pointing at a prebuilt binary.
- The `ping` fixture at `scripts/e2e-fixtures/ping.jsonl` (override with `FIXTURE_FILE`).

### Why a seeded channel, not "New discussion"

The rung-3 test taps "New discussion" → `create_conversation`, which mints a **fresh** per-conversation
claude session (a new random UUID, passed to claude as `--session-id`). The daemon's structured-turn
producer then tails *that* conversation's transcript **by that id** (no latest-file fallback). Real
claude honours `--session-id` and writes `<freshId>.jsonl`, so rung 3 aligns — but `fakeclaude` is
**env-only**: it ignores `--session-id` and always writes `<PYRY_FAKE_CLAUDE_INITIAL_UUID>.jsonl`. A
created-discussion turn would land in a file the producer never tails, and the scripted reply would
never reach the phone.

So deterministic mode (mirroring #642's bootstrap-bound model through the real UI) pre-seeds **one
promoted conversation** (a Channel) bound to the bootstrap session id, and a thin test variant taps
**that seeded channel** instead of creating a discussion. One conversation, one session, one fixture
file → the producer tails exactly the file `fakeclaude` writes.

### What the host does (deterministic seams)

1. **Isolated HOME** — pairs and runs the daemon under a short `/tmp/pyry-e2e-det.*` HOME so the
   scripted `.pyry/<name>/` + `.claude/projects/` never touch the operator's real profile (and the
   daemon's unix control socket stays under the ~104-char `sun_path` limit).
2. **Pre-seed** (after `pyry pair`, before daemon start): build/locate `fakeclaude`; compute the
   sessions dir `<HOME>/.claude/projects/<encode(HOME)>` (the daemon's exact tail dir, `/` and `.`
   both → `-`); pre-create `<INITIAL_UUID>.jsonl` (avoids the cold-start tail race); write one
   promoted row to `conversations.json` with `current_session_id == INITIAL_UUID`,
   `is_promoted: true`, `name: "e2e-seed"` (deliberately **not** `"…ping…"`: the seeded channel name
   renders verbatim in the thread top bar, and the reply is asserted as a `"ping"` substring — a
   `"ping"`-bearing channel name would false-green the test on the title alone).
3. **Daemon** — adds `-pyry-claude=<fakeclaude>`, `-pyry-workdir=<HOME>`, and the
   `PYRY_FAKE_CLAUDE_*` env (`TUI=1`, `INITIAL_UUID`, `SESSIONS_DIR`, `JSONL_TRIGGER`).
4. **Fixture-drop watcher** — a background job waits for the `send_message.enqueued` line in
   `daemon.log` (the cursor-stamp fence: `router.Route` stamps the producer cursor, *then* logs
   `send_message.enqueued`), then copies the scenario's fixture onto the JSONL trigger. `fakeclaude`
   appends it verbatim to the live session JSONL; the real producer tails it → `turn_state(responding)`
   → `assistant_delta(…)` → `turn_end` → `turn_state(idle)` → the phone's #337 fold renders the reply.
   The `spinner` scenario drops **twice** (see [Scenarios](#scenarios-454)). (Older daemons may emit a
   different fence token — confirm on first operator run.)

### The fixture format (extension point for #454/#436)

`scripts/e2e-fixtures/*.jsonl` are real files (not inlined), so sibling scenarios can be added. One
claude-format line per turn-event, trailing newline; the daemon's structured producer tails it
line-delimited. The "ping" fixture is a single `assistant` line with `stop_reason: "end_turn"` and
non-empty text:

```json
{"type":"assistant","message":{"id":"ping-1","stop_reason":"end_turn","content":[{"type":"text","text":"ping"}]}}
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

| `SCENARIO` | asserts | fixture(s) | drops |
| --- | --- | --- | --- |
| `ping` (default) | a single-line reply renders | `ping.jsonl` | one |
| `stream` | a multi-`assistant_delta` reply assembles into **one** message | `stream.jsonl` | one |
| `spinner` | the thinking spinner shows mid-turn, then clears at turn end | `spinner-open.jsonl` + `spinner-end.jsonl` | **two** |
| `tool` (#455) | a tool step shows **running** in flight, then **done** after the result | `tool-open.jsonl` + `tool-done.jsonl` | **two** |
| `tool-failed` (#455) | a failing tool step renders **failed** | `tool-failed.jsonl` | one |
| `reconnect` (#476) | an in-flight reply **survives a mid-turn link drop** and renders exactly once | `reconnect-open.jsonl` + `reconnect-done.jsonl` | **two** |
| `replay-order` (#477) | events produced **entirely while offline** replay **in order, each exactly once** | `replay-order-open.jsonl` + `replay-order.jsonl` | **two** (drop B on disconnect) |

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh                      # ping
DETERMINISTIC=1 SCENARIO=stream      PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # stream
DETERMINISTIC=1 SCENARIO=spinner     PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # spinner
DETERMINISTIC=1 SCENARIO=tool        PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool running→done
DETERMINISTIC=1 SCENARIO=tool-failed PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool failed
DETERMINISTIC=1 SCENARIO=reconnect    PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # reconnect continuity
DETERMINISTIC=1 SCENARIO=replay-order PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # post-reconnect replay ordering
```

**`stream`** — `stream.jsonl` is three `text` lines with **distinct** `message.id`s, so the producer
emits three `assistant_delta` envelopes (same `turn_id`, `seq` 0/1/2); the last line's
`stop_reason: end_turn` + non-empty text also yields `turn_end`. The phone's #337 fold concatenates the
three deltas (keyed by `turn_id`) into the single message `"Hello, streamed world"`. The test asserts
the cross-delta-boundary substring `"streamed world"`, present only if the deltas assembled into one
message (never on delta count or the streaming caret).

**`spinner`** — the thinking state is transient: a single fixture with `thinking` then `end_turn` would
flip `isThinking` true→false within one tail cycle, before Compose ever lays out the spinner — an
unobservable race. So the harness holds the turn open and ends it on a **causal** fence, with two drops:

1. **Drop A** (`spinner-open.jsonl`, a `thinking`-only line) fires on the **1st** `send_message.enqueued`
   → `turn_state(thinking)`, held → the spinner stays on indefinitely (no `end_turn`).
2. The test asserts the spinner is shown, then sends a **2nd** message.
3. **Drop B** (`spinner-end.jsonl`, a normal end-of-turn text line) fires on the **2nd**
   `send_message.enqueued` → `turn_state(responding)` (spinner clears) → `turn_end` → `turn_state(idle)`.

Because drop B is gated on the 2nd enqueue — which happens only after the presence-assert passed — the
thinking window is arbitrarily long. There is **no timing dependency and no fixed host delay**; a slow
phone cannot clear the spinner before the presence-assert catches it. The 2nd message's inert transcript
growth injects no event; only drop B's line ends the turn. The two-drop watcher stays a single
background subshell (counting `send_message.enqueued` occurrences to tell the 1st enqueue from the 2nd),
so one `kill` reaps it on teardown.

**`tool`** — a tool step must render **running** in flight and **done** after the result. "Running" is
transient (the fold flips the row to done the instant the correlated `tool_result` arrives), so it reuses
the spinner's **two-drop causal fence**:

1. **Drop A** (`tool-open.jsonl`, a lone `tool_use` line) fires on the **1st** `send_message.enqueued` →
   the fold opens a `Running` `Role.Tool` row keyed by the `tool_use` id, held open (no `end_turn`).
2. The test asserts the running content-description (`cd_tool_running`) is shown, then sends a **2nd**
   message.
3. **Drop B** (`tool-done.jsonl`, the correlated success `tool_result` (`is_error: false`) + a
   turn-ending text line) fires on the **2nd** `send_message.enqueued` → the fold flips the row to
   `Done` and closes the turn.

`Done` has **no positive content-description** (the resolved icon's `contentDescription` is `null`), so
"done" is asserted **indirectly**: the running CD that was present is now absent, the failed CD never
appears, and the tool row is still on screen (the verbatim tool name `"Bash"`). That triad uniquely
identifies a running → done resolution and never keys on timing. **Correlation is load-bearing:** drop
A's `tool_use` `id` must equal drop B's `tool_result` `tool_use_id` (same literal id in both files) or
the fold drops the result and the row never resolves.

**`tool-failed`** — a failing tool step must render **failed**. The failed end state is stable (it does
not auto-resolve), so it needs **no two-drop fence**: a single fixture (`tool-failed.jsonl`) carries
`tool_use` → an error `tool_result` (`is_error: true`) → a turn-ending text line, all in one drop. The
fold renders the row `Running` (briefly) → `Failed`; the test asserts only the terminal `cd_tool_failed`
content-description (tolerant, stable). The `tool_use` line must precede the `tool_result` line so they
correlate. Assertions never depend on the producer-derived `input_summary`/`result_summary` text — only
the status CDs and the verbatim tool name.

**`reconnect` (#476, Layer 2b)** — an in-flight reply must survive a mid-turn relay-link drop. It reuses
the spinner's **two-drop causal fence** with a sever/restore inserted in the gap:

1. **Drop A** (`reconnect-open.jsonl`, a `thinking`-only line) fires on the **1st** `send_message.enqueued`
   → `turn_state(thinking)`, held open. The turn is now streaming.
2. The test asserts the thinking spinner (proving the turn is open at the moment we sever), then
   **severs and restores the phone's relay link** (`severAndRestoreLink()`): `RelayConnectionSupervisor.close()`
   drops the socket and `connect()` re-dials. This is **phone-side only** — the daemon stays up, so its
   in-ring event buffer survives and the reconnecting phone re-advertises `last_event_id` (#416) rather
   than tripping the `resync`/gap path a daemon **restart** (#417) would. The helper awaits the
   coordinator's `currentRepository` going `null` (drop landed) then non-null (fresh Noise pump reached
   `Open`), so the drop+restore is proven, not assumed. The sever/restore injects **no** `send_message`,
   so the two-drop watcher counts exactly two enqueues and is reused unchanged.
3. **Drop B** (`reconnect-done.jsonl`, a complete reply + `end_turn`) fires on the **2nd**
   `send_message.enqueued` → the held-open turn completes to the reconnected phone.

Drop A carries **no** partial text by design: on the drop the connection-scoped repo tears down and the
thread projection clears, so the **whole** reply arrives post-reconnect in drop B. This makes "no missing
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

It forks the reconnect two-drop fence, but the sever and restore **straddle** the event production. The
atomic `severAndRestoreLink()` is split into its two halves — `severLink()` (close + await
`currentRepository == null`) and `restoreLink()` (connect + await `currentRepository != null`) — so the
test can hold an offline window between them:

1. **Drop A** (`replay-order-open.jsonl`, a `thinking`-only line) fires on the **1st**
   `send_message.enqueued` → `turn_state(thinking)`, held open. The test asserts the spinner (the turn is
   open at the moment we sever).
2. **`severLink()`** — the phone goes offline. The test then holds for a bounded `OFFLINE_WINDOW_MS`.
3. **Drop B** (`replay-order.jsonl`, three ordered `assistant_delta` lines + `end_turn`) fires not on a
   2nd enqueue (a severed phone cannot send one) but on the **relay logging the phone-leg disconnect** —
   so the ordered sequence accrues in the daemon's in-ring buffer **entirely while the phone is offline**.
4. **`restoreLink()`** — the fresh Noise `hello` re-advertises `last_event_id` (#416); the buffered
   sequence (all `event_id` above the cursor) replays whole into the fresh, empty repo, which folds the
   deltas in arrival (= production) order.

The offline-window hold is **not** the forbidden fixed-delay-to-catch-a-transient: the buffered events
are **durable** (they replay whenever the phone returns), so erring long is free, and the order /
exactly-once asserts hold whether the deltas arrive as pure replay (window long enough) or a replay/live
mix (window short) — a too-short window only *under-exercises* "entirely offline", never false-greens
(a reordering still breaks the substring) nor false-reds. The window is a determinism quality knob.

Drop A carries **no** partial text by design (same as #476), so the **whole** ordered sequence arrives
post-reconnect into the fresh repo and "no missing segment, in order" holds by construction. The closing
asserts reuse #476's one-deliberate-count exception: the **order** check matches the cross-delta-boundary
concatenation `"alpha bravo charlie"` (present only if the deltas assembled in production order — a
reordered replay breaks the substring), and `assertCountEquals(1)` on it is the dedup invariant on a
genuinely buffered-during-outage re-delivery.

The drop-B disconnect fence is wired as the overridable `DISCONNECT_TOKEN` / `DISCONNECT_LOG` (defaulting
to a `"disconnect"` substring in `relay.log`) and baseline-counted (wait for the count to *increase* past
the level captured after drop A) so a stale connection-churn line cannot false-fire it. The exact relay
token is the chief first-run unknown (see Assumptions).

## Verification status

- **Verified here (host JVM, no device):** the #337 fold (full `RemoteConversationRepositoryTest`
  suite is green), the whole unit suite stays green, and all androidTest sources compile
  (`compileDebugAndroidTestKotlin`). The pairing-payload parser is unit-checked against a synthetic
  payload.
- **Operator-run (needs your infra):** the actual headless-emulator + host-daemon run — for rung 3
  with real claude (`bash scripts/e2e-emulator.sh`, or `LIVE=1 …` for the live-relay variant #527 — see
  [Live mode](#live-mode-rung-3-live-relay)), and for rung 4 with the scripted backend, each of
  the six [scenarios](#scenarios-454) (`DETERMINISTIC=1 SCENARIO=ping|stream|spinner|tool|tool-failed|reconnect …
  bash scripts/e2e-emulator.sh`, `DeterministicInteractiveStreamE2ETest`). That is the point of both
  rungs — prove the emulator↔host↔app chain end to end. Expect to tune on first run; these are
  hand-built first-green prototypes, not hardened gates. Re-running each rung-4 scenario back-to-back
  must yield the same pass — that determinism is the whole point and the thing to confirm on real infra.
- **Negative control:** `InteractiveStreamE2ETest.negativeControl_wordClaudeNeverSays_isNeverDisplayed`
  is `@Ignore`d. Un-ignore it once to confirm the positive assertion can fail (it waits for a word
  claude is never asked to say, so it must time out). Re-ignore after, so it does not burn a turn. The
  tool-use test has its own `@Ignore`d twin, `negativeControl_toolClaudeNeverUses_isNeverDisplayed`
  (#481): same tool prompt, but it waits for a tool name claude is never asked to use (`"Edit"` — the
  prompt asks only for a read-only shell command), so it must time out, proving the `"Bash"` matcher is
  selective. Rung 4 needs no negative control: the scripted backend makes the positive assertion
  deterministic.

## Assumptions to confirm on first run

These are grounded in the source but unverified end to end:

- **Fixture-drop fence token.** The watcher fences on the `send_message.enqueued` line in `daemon.log`
  (current pyrycode HEAD: `router.Route` stamps the producer cursor, then logs `send_message.enqueued`).
  If the operator runs an older daemon that logs a different token, the fixture never drops and the test
  times out — confirm the actual `daemon.log` token on first run and adjust the watcher grep.
- **`pyry pair` before daemon start.** The script mints the token before starting the daemon, so the
  daemon loads it on boot. If the daemon does not recognise the token, try pairing after the daemon is
  up, or restart the daemon after pairing.
- **`-pyry-name=e2e-emulator`** namespaces the daemon socket/identity so it does not clobber a
  production daemon on the host. `pyry pair` and the daemon must use the **same** name so they share
  identity (hence the same `server_static_pubkey`).
- **Connection readiness.** The test waits for `ConnectionState.Connected` before creating a
  conversation. If "Connected" precedes the Noise session being fully Open, `createDiscussion` could
  race; the symptom is the thread step timing out. Add a small settle or a pump-Open wait if so.
- **ATD image vs Play services.** The paired happy path never opens the QR scanner, so `aosp-atd`
  (no Play services) should suffice. If something needs Play services, switch `systemImageSource` to
  `google-atd` in `app/build.gradle.kts` (still headless).
- **Lone `tool_use` opens + holds a turn (#455 `tool` scenario).** Drop A (`tool-open.jsonl`) is a bare
  `tool_use` line (no preceding `responding` text, no `end_turn`), mirroring how `spinner-open.jsonl` is
  a bare `thinking` line. The producer is expected to emit the `tool_use` envelope and leave the turn
  open. If the emitter instead requires a prior event to open the turn, the running-assert times out —
  fix by prepending a `thinking` or short `text` line to `tool-open.jsonl` (it does not affect the
  tool-row assertion, which keys on the tool CD, not the turn state).
- **Claude-format tool field names (#455).** The `tool_use` block `{id, name, input}` and the `user`
  `tool_result` block `{tool_use_id, content, is_error}` are the standard Anthropic transcript shape and
  match pyrycode's tui-driver extractors (`ParseToolUse`/`ParseToolResult`; cf. pyrycode #382/#671).
  Confirm against the operator's pyrycode HEAD on first run; if a field name differs, adjust the fixtures
  only. Correlation is load-bearing: `tool-open.jsonl`'s `tool_use` `id` must equal `tool-done.jsonl`'s
  `tool_result` `tool_use_id` (`toolu_e2e` in both) or the fold drops the result and the row never
  resolves.
- **`tool-failed` SCENARIO token.** The hyphen is fine in the `case` arm and on the CLI
  (`SCENARIO=tool-failed`). If a future operator prefers no hyphen, rename to `toolfail` in lockstep in
  the script `case` and this doc — the `@Test` method name is independent.
- **Held-open turn resumes to the reconnected phone (`reconnect` #476).** After the phone re-attaches
  (new conn_id / Noise session), the relay must route the daemon's continued stream to the new connection,
  and drop B (fenced on the 2nd `send_message.enqueued`) completes the held-open turn. This is the shipped
  relay+daemon contract (#416/#646) but unverified end to end **with a reconnect in the middle** — confirm
  on first run. If the held-open turn does **not** resume to the reconnected phone, that is the
  buildability finding to surface (do **not** restart the daemon to work around it — a restart trips the
  #417 gap path this test must avoid).
- **Brief drop stays in the in-ring window → no `resync` (`reconnect` #476).** The reconnect is immediate
  (test-triggered, no host delay), so `last_event_id` cannot age out of the daemon's bounded buffer → the
  gap-free path runs and the cursor is never `reset()`. `resync` is not cleanly UI-observable, so it is not
  separately asserted; the observable proxy is the exactly-once render (a `resync`-driven full reload would
  be a different code path). Confirm on first run that no `resync` is logged.
- **`ProcessLifecycleOwner` driver doesn't fight the explicit drive (`reconnect` #476).** A stable
  foreground instrumented run emits no `onStart`/`onStop` lifecycle edges, so `LifecycleConnectionDriver`
  won't re-`connect()`/`close()` under the test. If an emulator focus blip does fire one, prefer the
  real-transport-drop variant (`supervisor.currentConnection.value?.close()` + `supervisor.retry()`), which
  doesn't cancel the supervision loop. (Same caveat for `replay-order` #477, which drives the same seam.)
- **Disconnect fence token (`replay-order` #477, PRIMARY UNKNOWN).** Drop B fences on the relay/daemon
  logging the phone-leg drop (a severed phone cannot send a 2nd `send_message`). The watcher greps
  `DISCONNECT_LOG` (default `relay.log`) for `DISCONNECT_TOKEN` (default a `"disconnect"` substring),
  baseline-counted so a stale churn line cannot false-fire. The exact relay/daemon token is unverifiable
  from this repo (that source lives in pyrycode) — **confirm/adjust on first operator run**; if the token
  never appears, drop B never drops and the test times out. Override with `DISCONNECT_TOKEN=… DISCONNECT_LOG=…`.
- **Offline window length (`replay-order` #477).** `OFFLINE_WINDOW_MS = 5_000L` (in the test) must exceed
  [disconnect-detect + watcher poll 0.5s + `fakeclaude` append + producer tail]. Erring long is free (the
  buffered events are durable); erring short only *under-exercises* "entirely while offline" — the
  order/exactly-once verdict still holds — so lengthen it if the replay arrives as a live mix rather than a
  clean post-reconnect batch.
- **Buffered sequence replays in production order (`replay-order` #477).** After the phone re-attaches, the
  relay routes the daemon's buffered stream to the new connection in ascending `event_id`, and the fresh
  repo folds it in arrival (= production) order — shipped #416/#647 contract, but unverified end to end
  **with the whole sequence produced during the outage**. If the replayed order is wrong, that is the
  buildability finding to surface — do **not** restart the daemon to force it (that trips the #417 gap path).
- **Brief drop stays in the in-ring window → no `resync` (`replay-order` #477).** Same as #476: the
  reconnect is immediate, so `last_event_id` cannot age out → gap-free path, cursor never `reset()`. Confirm
  no `resync` is logged.
- **Real claude emits the tool step the phone expects (`tool-use` #481).** The producer must emit a
  `tool_use` envelope (with `name = "Bash"`, carried verbatim) and a correlated `tool_result` for a
  real-claude shell tool — the same shape #455's fixtures simulate (the standard Anthropic transcript
  shape; matches pyrycode's `ParseToolUse`/`ParseToolResult`, cf. pyrycode #382/#671), but unverified end
  to end with real claude. If real claude's tool-use output differs (a different tool name, a changed
  envelope shape), the assertion fails — **that is the drift Layer 3 exists to surface.** Adjust
  `TOOL_NAME` (and/or the prompt to target whichever tool claude reliably uses) on first run; do **not**
  weaken the assertion to a generic match.
- **Tool permission behaviour (`tool-use` #481, the chief first-run unknown — the ping path never hit
  this).** The rung-3 daemon (`scripts/e2e-emulator.sh`) spawns default real claude with no
  permission-bypass flag. If a real tool call interposes the mobile permission modal (#428), the tool
  will not run until approved → the tool-row assertion times out. Resolve in order of preference:
  **(a)** run the e2e daemon's claude in a non-interactive / auto-approve permission mode (a
  host/daemon-side config — *pyrycode-side, not a mobile change*) so `echo` runs without a modal; if the
  daemon already auto-approves this path, nothing is needed — confirm on first run. **(b)** if a modal
  appears and (a) is unavailable, tap the approve affordance in-test before asserting the row — but this
  couples the test to the permission-modal UI (#437/#438) and is the more fragile option. Prefer (a);
  record whichever path is taken here.
- **Prompt reliably triggers the shell tool (`tool-use` #481).** "Run this exact shell command … echo
  pyry481" should make real claude run the shell tool every run. If it sometimes answers inline (no
  tool), tighten the wording, or `@Ignore`-gate the positive test (like the negative control) per the
  no-flaky-always-on rule rather than leaving a flaky always-on test. The exact wording is the
  developer's to tune on first run; the design depends only on "compels one shell tool call" + "omits the
  asserted tool-name token".
- **Screen-sourced thinking window (`thinking-spinner` #482, the chief unknown — why it ships
  `@Ignore`d).** The spinner shows only during `turn_state(thinking)`, the gap between send and real
  claude's first `assistant_delta`, derived by the daemon from claude's live TUI screen. `THINK_PROMPT`
  (`"Without using any tools, … reason this through silently, then reply with only the single word:
  ready. …"`) aims to widen that gap: no tool call (so the #428 permission modal never interposes, unlike
  the tool prompt) plus a beat of internal reasoning before a short-token answer. **Unverifiable from this
  repo** — whether the window is long enough to lay out and poll the spinner over the relay depends on how
  pyrycode's daemon maps claude's pre-output state and on claude's own latency. If real claude answers too
  fast to catch, **keep the test `@Ignore`d** (the default) and record that the promote attempt failed — a
  valid documented manual outcome, **not** a build failure. If claude streams its reasoning as *visible*
  text, `turn_state` flips to `responding` immediately and the spinner clears at once — the "reply with
  only a short token after thinking" framing is the lever; tune the prompt on first run. The prompt is the
  developer's to tune; correctness does not depend on the window being reliably catchable (that is why the
  test is gated).

## Follow-ups to ticket

- **Rung 4 (shipped, #431; extended #454, #455):** deterministic host backend via #642's scripted
  `fakeclaude` — see [Deterministic mode (rung 4)](#deterministic-mode-rung-4). #454 added the
  multi-delta `stream` render + the `spinner` scenario, and #455 added the `tool` / `tool-failed`
  tool-step scenarios (see [Scenarios](#scenarios-454)); #476 (reconnect continuity) and #477
  (reconnect ordering) — the #436 split — extend the same fixture format.
- **Rung 2 (Layer 1a shipped, #432):** the cheap Compose render harness — see
  [Layer 1 — component render harness (rung 2)](#layer-1--component-render-harness-rung-2). Layer 1b
  (#435, rides the same harness) adds tool rows, the session divider, and the connection banner.
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
  fire-and-forget wire and the #336 `session_transition` → `SessionBoundary` fold, always-on (durable
  delimiter artifact, unlike the `@Ignore`d spinner) and folded into the pre-ship `LIVE=1` gate as the
  3rd curated method; Layer-3 (real claude) delete-conversation — **shipped (#554)**, driven end to end
  through the #532 delete wire against a real daemon, always-on (both post-conditions — gone from the list,
  thread popped back — are durable structural facts) and folded into the pre-ship `LIVE=1` gate as the 4th
  curated method (spending **no** extra claude turn — create/rename/delete are daemon round-trips), taking
  the gate from a trio to a quartet at still 3 turns; Layer-3 (real claude) archive/restore round-trip —
  **shipped (#551)**, driven end to end through the #549 archive/unarchive wire, the #556 archive-from-thread
  and #557 restore surfacings against a real daemon, always-on (both durable post-conditions — the unique
  name gone from the active list after archive, then back after restore, a genuine two-direction inversion)
  and folded into the pre-ship `LIVE=1` gate as the 5th curated method (spending **no** extra claude turn —
  create/rename/archive/restore are daemon round-trips), taking the gate from a quartet to a quintet at
  still 3 turns; Layer-3 (real claude) change-workspace — **shipped (#562)**, driven end to end through the
  #560 change_workspace wire and #561 surfacing against a real daemon, always-on (the recorded workspace is a
  durable fact — the `WorkspaceChip` re-labels to the new folder's basename, a genuine absence→presence
  inversion) and folded into the pre-ship `LIVE=1` gate as the 6th curated method (spending **no** extra
  claude turn — create-folder and change_workspace are conversation-scoped daemon round-trips), taking the
  gate from a quintet to a sextet at still 3 turns.
- **#337 full scope:** `seq`-based ordering and replay de-dup across reconnect (a #402 concern; this
  fold concatenates in arrival order, correct within a single connection); and a `make`/Gradle wrapper
  for the orchestration plus fork-sync of any shared `bin/` script per the org convention.
- **Recover `pyrycode#642`** (the parked wire-level run) for the pipeline, or note it there.

## Constraints

- Runs as a local Gradle/script command, **not** a GitHub CI gate (the org does not gate on Actions).
- Assert tolerantly (substring, trimmed, generous timeouts); never on delta counts or timing.
- Keep the test to the single structured path; do not assert the coarse `message` path. As of 2026-06-22 there is no old-app-version support: the operator controls both ends and ships the app and daemon together, so every phone gets the structured stream and the coarse path is dead code slated for removal. See the 2026-06-22 amendment in pyrycode ADR 025.
