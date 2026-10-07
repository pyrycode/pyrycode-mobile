# Development verification — JVM logging, emulator and real evidence

Split out of [Development verification](development-verification.md) on 2026-10-02 to keep that
document under the 50000-byte size cap the docs guard enforces. Every section below moved here
verbatim and kept its heading, so its anchors are unchanged, except one inbound link retargeted to
the sibling document its destination moved into. Part of
[Development verification](development-verification.md); see that document for the other topics
and its links.

## JVM logging and formatting

Plain JVM tests have no Robolectric runtime and this module does not enable default
Android return values. A reachable `android.util.Log.*` call throws "not mocked".
Route log emission through an injectable sink, as `data/network/RelayLog.kt` does,
so tests can capture the call and restore process-global flags after each test.
Installing that sink is order-sensitive when the class under test logs from a
constructor path: a test that builds `HostConversationSource` at field
initialization (rather than inside `@Before` or the test body, after the sink is
installed) reaches the source's first `reconcile` log before the sink exists, and
the resulting `android.util.Log` call fails the test as an uncaught exception
before any assertion runs (#840).

Spotless also runs ktlint's filename rule. If a Kotlin file contains one non-private
top-level class-like type, the filename must match that type, including for
`internal` types. Split a result or DTO into its own correctly named file when the
rule requires it; `spotlessApply` cannot repair the filename.

The Spotless message saying it could not autocorrect a theoretically fixable
violation is only a warning. Read the final `BUILD SUCCESSFUL` or `BUILD FAILED`
and the explicit violation list. Use `./gradlew spotlessCheck --rerun-tasks` when
cached output makes the result unclear.

Spotless's unused-import check is name-based, not usage-based. After moving code
out of a file, an import can go unused while the file still calls a same-named
member on an unrelated type — e.g. `kotlinx.coroutines.flow.map` staying imported
after the only `Flow.map` call moved elsewhere, because `List.map` still appears
in the file and the check cannot tell the two `map`s apart. `spotlessApply` will
not remove it either. Check each import a move leaves behind for an actual caller
of that specific symbol, not just any identically-spelled one (#916).

`spotlessApply` (ktlint) rewrites a lower-case `\uXXXX` escape in a Kotlin string
literal into the literal invisible character it denotes — a bidi override or an
isolate stops reading as an escape sequence in the diff and the source file. An
upper-case hex escape (`‮` as written, not what it renders) is left alone.
A hostile-text test fixture (e.g. `StoppedTurnTest`, #1356) stays readable in the
source only if its escapes are typed in upper case.

## Emulator and real evidence

An instrumented test proves behavior in its fixture. It does not prove camera
binding, lifecycle timing, relay compatibility or a real daemon round trip. The
dispatcher runs the UI gate and each zero-real-Claude scripted scenario before
verifier. For a ticket labelled `needs-real-claude`, it runs
`python3 scripts/android-test-gate.py live` after verifier and before documentation
or merge. Record the scenario, app/build version, daemon compatibility and executed
test count. XML evidence is required; a zero exit code with every scenario skipped
is not a passing proof.

The earlier-Other focus evidence (#1797) separates test execution from emulator health.
`E2eInstrumentationRunner.quietSystem` (shipped at `186c399b`) disables emulator Bluetooth,
hides system crash dialogs during instrumentation and closes existing system dialogs;
it restores the dialog setting on finish and leaves Bluetooth off. It does not prevent every
Bluetooth service crash. The unchanged
`QuestionBatchModalTest#ime_keeps_an_earlier_other_clear_of_chrome_on_open_dismiss_and_reopen`
passed separately at `de584470` on `pixel2Api33Atd`, one shard with disabled animations:
**1 executed, 1 passed, 0 failed, 0 errors, 0 skipped**, exit 0
([retained XML and command/revision metadata](../../../app/src/androidTest/assets/focus-1797/README.md)).

The required forced sweep ran with
`UI_GATE_FULL=1 ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
at `b608f49bc1177ec64477c02428ca20337989ee5b`, exit 0. Fresh
`build/dispatcher-tests/ui-k8ql88ff/dispatcher.xml` and
`0-TEST-pixel2Api33Atd_0-_app-.xml` agree: **183 executed, 183 passed, 0 failed,
0 errors, 1 skipped** (184 listed). The exact earlier-Other method passed on
`pixel2Api33Atd_0` in 2.715 s with no failure/error/skipped child. The
[operator record](https://github.com/pyrycode/pyrycode-mobile/pull/1800#issuecomment-5993306258)
retains the exact command, start time, revision, exit status and XML checksums;
its gate log and XML are copied under `/tmp/operator-1797/`. This fulfills the builder
plan/PR's pending dispatcher handoff and resolves the earlier missing force-flag evidence.

After merging newer main, the dispatcher ran
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` at
`22d2bb2faf24c17e9cc3ff197f2617952d1f549d`, exit 0. Fresh
`build/dispatcher-tests/ui-0zkz3qmy/dispatcher.xml` and device XML agree:
**184 executed, 184 passed, 0 failed, 0 errors, 1 skipped** (185 listed).
The named method passed again in 1.811 s with no failure/error/skipped child.
Both sweeps skipped only `RenameDialogCaptureTest#renameAtFigmaViewport`. The test,
runner, focus listener, Gradle wiring and gate script were unchanged between the
qualifying revision and reviewed head. The
[final verifier record](https://github.com/pyrycode/pyrycode-mobile/pull/1800#issuecomment-5994141618)
links both sweeps to their revisions; XML, command/revision/exit records, full
named-method logcats and a SHA-256 manifest are retained together under
`/tmp/verifier-1800/recheck-22d2bb2f/`.

The first sweep at `b4875016` and the current-head sweep each recorded a native
`com.android.bluetooth` SIGABRT during the passing method. The verifier found no
Pyrycode app crash, ANR or focus-failure markers. The qualifying forced run's
named-method logcat had no such crash markers. Credit the shipped mitigation for
permitting the unchanged assertions to complete; these passes do not establish
that Bluetooth service crashes are eliminated or guarantee future focus reliability.
Preserve crash evidence even when system dialogs are hidden, and retain the initial
focus and real-inset assertions. No live-Claude scenario changed.

For a multi-operation live scenario, name each failing wait before attributing it to an older
repair. The question-answer scenario's `QuestionAnswerStage` / `questionAnswerStep` diagnostic
adds fixed operation labels and lazily read, content-free peer link state to coroutine and Compose
timeouts, retaining the original cause and deadline (#1703). Its captured `AwaitPhoneDismissal`
(`await phone answer's question_dismissed on peer`) timed out after 30000 ms with
`session open (link 1, replaced 0×)`: later than #1702's enabled-Continue Compose wait and #1686's
peer admission/key-binding repair. Accepted daemon handshakes do not prove that a phone answer
was sent. Preserve sanitized stderr, counted XML and copied per-test logcat together; removed
gate worktrees can erase the phone evidence needed to correlate them.

An enabled semantic node and successful `performScrollToNode` do not establish a usable physical
tap target. The thread draws beneath header/composer chrome. In #1703's short-thread reproduction,
Continue's tap center was 468.5 while composer top was 461; the real pointer tap emitted no submit
event. The long empty-thread fixture did not expose this. `questionAnswerTarget` now measures the
readable band, applies one scroll adjustment and asserts that the tap center clears chrome before
selection and Continue. `ThreadInlineQuestionTest#phone_question_scroll_sequence_submits_from_a_short_thread`
checks selection and exactly one submit event with the held generation. The historical live
occurrence's missing phone logcat leaves attribution to this reproduced defect an inference,
not an observed tap coordinate. The repaired live method ran and passed in a fresh full suite:
**53 executed, 53 passed, 0 failed, 0 skipped**. See the
[question-answer scenario and revision-linked evidence](../../e2e-interactive-stream.md#verification-status)
for the app/daemon revisions and the distinction from earlier failed captures and same-tree reruns.

The dispatcher runs the twelve scripted scenarios — `ping`, `stream`, `spinner`,
`tool`, `tool-failed`, `tool-progress`, `reconnect`, `offline-retry`,
`replay-order`, `tool-then-text`, `refusal` and `mcp-failed` (#1457, the failed
MCP server pill) — as one step, `python3 scripts/android-test-gate.py
scripted-all`. It boots the managed device's AVD once, headless and read-only
from its snapshot, on a free console port, and runs each scenario against it
through the harness's `connected` device with `ANDROID_SERIAL` pinned, so
parallel tickets never share an emulator. Each scenario still gets its own
daemon, relay, pairing and app install. The step names each scenario's result
on stderr and stops the emulator even when the dispatcher's time cap kills it.
That stop only asks ADB to quit the emulator, so the wait and kill that reap the
owned process sit in a `finally`: an ADB timeout or error once skipped them and
released device custody with the emulator still running (#1833). When the request
fails the emulator is killed at once, and a second cancellation is held off until
it is reaped. A cleanup test that substitutes a successful stop cannot catch this;
inject the ADB failure into the real teardown.
Before the ui gate has ever created the AVD it falls back to the managed
device per scenario. Measured 2026-09-23: 61 seconds against 152 for seven
separate runs (the scenario count has grown since). `scripted <scenario>`
stays the focused command for one scenario.

The current required gate profile is managed `pixel2Api33Atd`, Pixel 2 / API 33 /
AOSP ATD arm64. The full `pixel8Api35` image supplements it for real-system-bar
screenshots ([Compose evidence](development-verification-compose-evidence.md#compose-evidence));
it does not replace the
required gate profile. See the
[revision-linked live baseline](../../e2e-interactive-stream.md#verification-status).
A configured profile name alone does not establish the runtime image. Capture ADB
properties during execution and record the SDK image revision, Claude version,
resolved runner and app/daemon revisions alongside sanitized XML and its checksum.
A missing or unbootable required device is an environment blocker, not a product
regression or a passing test.

Choose an e2e rung from the producer that emits the event. The current mobile
harness uses the daemon's stream-json runner and `fakeclaude` raw replay. Set
`PYRY_FAKE_CLAUDE_STREAM_JSON=1`, provide `PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST`,
and pair `PYRY_FAKE_CLAUDE_STREAM_REPLAY_SECOND` with
`PYRY_FAKE_CLAUDE_STREAM_REPLAY_RELEASE` when a held turn needs a second fragment.
Release it from the queued second message or relay disconnect that the scenario
defines. PTY transcript polling is historical and the current harness rejects a
PTY runner. Keep missing fixtures and skipped captures visible. Before accepting a
capture, check its redacted context, expected event count and reader version. Do
not copy credentials, pairing codes, user prompts, host paths or raw daemon
payloads into evidence.

A client request the daemon serves by waiting on the Claude child — `mcp_status_request`, for one
(#1345) — runs on the same per-connection FIFO app-frame worker as `send_message`, and the daemon's wait
has no timeout of its own. `fakeclaude` only answers `mcp_status` when `PYRY_FAKE_CLAUDE_MCP_STATUS` is
set; unset, it leaves the request unanswered forever. The scripted `reconnect` scenario hung this way once
\#1345 added a reconnect ask: the next `send_message` on that connection queued behind the unanswered
request and never got accepted. `scripts/e2e-emulator.sh` now sets `PYRY_FAKE_CLAUDE_MCP_STATUS=1` in the
deterministic `REPLAY_ENV`, whose canned reply includes a `"failed"` row. Before adding any other ask the
daemon serves this way, check that the scripted fake answers it, not just that unit tests pass — only a
scenario with a live child surfaces this stall. Real Claude answers mid-turn, so production risk is a
child that answers slowly or never; that is filed upstream as pyrycode/pyrycode#2702, not fixed client-side.

A live-gate step that judges the phone's rendering against a fixed timeout starting at the send
conflates two different waits: Claude's upstream think time and the phone's own render time. On
\#1480, step 5 of `InteractiveStreamE2ETest#interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`
gave the phone 90 s (`REPLY_TIMEOUT_MS`) to show a permission prompt, counted from the send, and
only checked the host's permission request after that wait timed out. One upstream response took
5.5 minutes for 215 output tokens, so the test failed before Claude had even asked to use the
tool — a flake with nothing wrong on the phone or daemon side. The fix waits for the host's
`modal_shown` for that chat first, under its own multi-minute upstream-latency budget
(`UPSTREAM_PERMISSION_TIMEOUT_MS`, 6 minutes), and only then holds the phone to the unchanged,
much shorter `REPLY_TIMEOUT_MS`. Each wait gets its own failure message, so a timeout says which
side was slow. Apply the same split to any live-gate wait that starts a short phone-side timeout
at the send rather than at the host's event: it cannot tell "Claude hasn't asked yet" apart from
"the host asked and the phone drew nothing" otherwise.

The demo binding (`-PuseRelayRepository=false`) does not skip onboarding: the start screen is
chosen from the paired-host store, so a demo build's channel list, thread and modal screens are
reachable only after a real pairing. Pair through the app's own paste-a-code flow against a
throwaway, isolated local test daemon and relay built from the configured sibling checkouts (no
Claude binary needed, no turns spent), then stop both afterwards. Two traps found doing this
(#680): a long scratch `HOME` makes the daemon's unix socket path too long, and
`adb shell input text` silently truncates a ~300-character pairing code, so type it in ~50-character
chunks. The demo host is never in the paired-host store, so Edit host itself refuses to open
(`host_editor_open_rejected code=unknown_host`); Edit channel shares the same modal shell and
substitutes for it in a shell-only comparison.

A launch splash is system-drawn before any Compose or Activity-hosted test can attach, so it has
no instrumented capture path at all — not even the `requireRealSystemBars=true` device-test pattern
above, which still launches through an Activity. The managed `pixel2Api33Atd` device cannot
composite a starting window either: `adb exec-out screencap` returns an all-black frame and the
emulator console's own screenshot command returns a static grey frame, before and during launch.
Boot the host's non-ATD `pixel8Api35` AVD instead (`-read-only -no-snapshot`, under the gate's
device lock), install the gate-built APK, and run `am start` and `screencap -p` in a loop inside one
`adb shell` command so there is no adb round-trip between frames; a cold debug start takes roughly
9–12s, so several frames catch the splash. Compare the captured frame against the Figma render at
the device's dp scale factor (#1545). See [Splash screen](splash-screen.md#edge-cases--limitations).

For camera overlays, the dispatcher must verify the real preview layer on the
managed emulator or device when the change concerns it. A unit test or a fake
preview slot cannot prove CameraX binding or that the preview respects the Compose
overlay. For relay and Noise changes, combine deterministic JVM coverage with the
appropriate UI or post-verifier live path; do not claim the latter ran unless its
output identifies the executed scenario and XML evidence.

A device gate run (`ui`, `scripted`, `scripted-all` or `live`) copies each fresh
per-test `logcat-*.txt` into that run's `build/dispatcher-tests/<mode>-*` artifact
directory next to the XML it already copies (`fresh_logcats` in
`scripts/android-test-gate.py`, the sibling of `fresh_reports`) — the next run
overwrites AGP's originals under `androidTest-results/`, so this is the only place
they survive (#1039). To diagnose a relay connection drop, read those
[`RelayLog`](relay-log.md) `event=transport_end` / `event=pump_teardown` lines
alongside the daemon's `daemon.log` by timestamp; neither side logs the other's
cause, so correlating by time is what tells a phone-side, relay-side or network
ending apart.

## Share failure presentation (#1824)

Local share refusals are deterministic presentation outcomes. `ShareErrorNoticeTest` under
`app/src/sharedTest` drives the actual `ShareErrorNoticeHost` collector and `PyryNavHost` with an
injected capture function, rather than injecting text directly into a pill. Its nine tests cover
unreadable capture, repeated capture/size formatting, intake count refusal, selection count and size
refusals across picker-to-thread navigation, Direct Share capture/count failures, accessibility extension
and persistent-notice stacking. Assertions check copy, inert polite semantics, occurrence identity,
expiry, absence of a snackbar ancestor, measured 20dp gutters/28dp clearance, unchanged row bounds on
expiry and no Retry callback from tapping the overlapping inert error surface. The accessibility fixture
checks the Short policy `(4000, icons=true, text=true, controls=false)` and extends it to 12000ms.
A screen-only pill test would miss a collector removed by navigation; a row comparison must distinguish
the picker's header-induced movement from movement caused by showing or clearing the notice.

The [PR testing record](https://github.com/pyrycode/pyrycode-mobile/pull/1872) and
[verifier's counted XML review](https://github.com/pyrycode/pyrycode-mobile/pull/1872#issuecomment-6030009563)
record the 2026-10-07 evidence at reviewed commit `2aa34f33ee3a5bdbc38a06a261d70d6fc1e36e10`:

- Focused/retained JVM classes: **112 executed/passed, 0 failed/errors, 0 skipped**:
  `ShareErrorNoticeTest` (9), `ShareIntakeTest` (9), `ShareActivityTest` (2), `SharePickerTest` (6),
  `TransientErrorNoticeStateTest` (4), `ThreadTransientErrorTest` (12), `ThreadTopOverlayTest` (17),
  `ChannelListScreenTest` (47), `CreateChatFailureNoticeTest` (4) and `NoticePillTest` (2).
  Existing intake, activity, picker and ownership regressions were retained.
- Full JVM suite: **4637 executed/passed, 0 failed/errors, 0 skipped**. The verifier read
  `app/build/test-results/testDebugUnitTest/` from `./gradlew check` and confirmed all nine
  `ShareErrorNoticeTest` methods passed. The builder's XML-derived summaries remain in
  `/tmp/builder-1824/focused-counts.json`, `pill-counts.json` and `final-counts.json`;
  these temporary paths are local evidence locations, not committed artifacts.
- Dispatcher UI gate, `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`:
  **192 executed/passed, 0 failed/errors, 1 skipped** in
  `build/dispatcher-tests/ui-qr4mxhl4/dispatcher.xml`. Only
  `RenameDialogCaptureTest.renameAtFigmaViewport` was skipped; it is not a pass.
- Dispatcher scripted gate, `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all`:
  **19 executed/passed, 0 failed/errors, 0 skipped** in
  `build/dispatcher-tests/scripted-all-7k77vfy9/dispatcher.xml`, including `direct-share`, with
  zero real Claude turns. This gate supplements the JVM failure-presentation proof.

No real-Claude run is claimed here. The unchanged rung-3
`InteractiveStreamE2ETest.interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes`
covers successful sharing and delivery; this slice adds no rung-3 scenario or rung-4 twin.
The builder records Figma comparisons; the verifier checked source reuse and deterministic geometry
but lacked independent remote Figma context/screenshots and received no new share-error capture artifact.
See [navigation](navigation.md#incoming-shares-1728) and
[transient overlay lifetimes](thread-top-overlay.md#the-transient-error-pill-1747) for behavior.

## Share picker fixture isolation (#1885)

`SharePickerTest` remains in `app/src/sharedTest`, covering picker transfer,
Direct Share and unknown-shortcut fallback. The
[verifier's counted review](https://github.com/pyrycode/pyrycode-mobile/pull/1894#issuecomment-6033743941)
records the following complete-class evidence on 2026-10-07:

- Robolectric, dispatcher `./gradlew check`: **6 executed/passed, 0 failed/errors,
  0 skipped**. The verifier inspected fresh
  `app/build/test-results/testDebugUnitTest/TEST-de.pyryco.mobile.ui.conversations.share.SharePickerTest.xml`,
  timestamp `2026-10-07T07:33:56.891Z`.
- Managed Android 13, focused complete-class
  `:app:pixel2Api33AtdDebugAndroidTest --rerun` with instrumentation `class` set to
  `de.pyryco.mobile.ui.conversations.share.SharePickerTest` and `notPackage` set to
  `de.pyryco.mobile.e2e`: **6 executed/passed, 0 failed/errors, 0 skipped**.
  The contemporaneous XML parse of `TEST-pixel2Api33Atd-_app-.xml`, timestamp
  `2026-10-07T07:30:24`, lists all six methods as passed. Later device execution
  replaced the original XML; the verifier inspected the successful command output
  and recorded per-method parse, retained locally in
  `/tmp/verifier-1894/managed-class-recorded-evidence.txt`, rather than claiming a
  new device run.

`unknownDirectShareFallsBackWithCapturedBatchAndDraftUnchanged` is explicitly
confirmed passed in both runs; the device parse also confirms both transfer
methods passed. The separate dispatcher UI gate (**192 executed/passed, 0 failed,
1 skipped**) and scripted-all gate (**19 executed/passed, 0 failed, 0 skipped**)
supplement these results; they do not establish the focused sharedTest class pass.
See [fixture ownership](development-verification-test-scheduling.md#test-scheduling-and-harnesses)
and [share-launch ordering](navigation.md#testing).

## Documentation evidence

Record the failure that would otherwise recur, its cause and the check that catches
it. Put product behavior in the owning feature topic. Put requirements, review
findings and unfinished work on the ticket or PR. Put workflow lessons in the
agent or dispatcher repository. The frozen `codebase/` archive is read-only, and
local Claude memory is not a substitute for a reviewed repository document.

## Archive refresh regression

The live archive test on 2026-09-20 exposed a list decoder that discarded
`is_archived` and reset every row to active on a refresh. List summaries now
preserve that field, with an active default for older server replies. The live
archive and restore scenarios enter through the list toolbar for the selected
host and wait for the restore success snackbar before leaving Archive: the
restore coroutine belongs to that destination's ViewModel. A conversation can
be created before the test's UI or repository wait returns, so fixture cleanup
records each host's conversation IDs before creation and recovers a new ID for
bounded deletion if setup fails. Preserve these checks when changing the list
mapping, Archive navigation or live fixtures. See the [rung-3 Archive coverage](../../e2e-interactive-stream.md#live-mode-rung-3-live-relay).

For a channel prompt edit followed by Reset session, wait for a distinct second
real reply before checking `SessionPromptStatus.Matches`. Reusing the first turn's
reply text could let a display assertion match an old-session message and make the
post-reset check pass without proving the new turn rendered.
