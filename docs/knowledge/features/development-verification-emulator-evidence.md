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

The dispatcher runs the seven scripted scenarios as one step,
`python3 scripts/android-test-gate.py scripted-all`. It boots the managed
device's AVD once, headless and read-only from its snapshot, on a free console
port, and runs each scenario against it through the harness's `connected` device
with `ANDROID_SERIAL` pinned, so parallel tickets never share an emulator. Each
scenario still gets its own daemon, relay, pairing and app install. The step
names each scenario's result on stderr and stops the emulator even when the
dispatcher's time cap kills it. Before the ui gate has ever created the AVD it
falls back to the managed device per scenario. Measured 2026-09-23: 61 seconds
against 152 for seven separate runs. `scripted <scenario>` stays the focused
command for one scenario.

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
