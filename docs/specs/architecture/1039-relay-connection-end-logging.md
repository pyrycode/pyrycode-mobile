# #1039 — Log why each relay connection ended

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt` → `OkHttpRelayTransport.terminate`, `failLocally`, `connect`, `close`, `Listener.onClosing` / `onClosed` / `onFailure` — every end of a transport funnels into `terminate`'s CAS; the line goes there so it is written exactly once.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` → `NoiseSessionPump.teardown`, `drive`, `onOpenFrame`, `initiateRekey`, `close` — every pump end funnels into `teardown`'s CAS; the triggers are the call sites.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog` KDoc (the adoption sentence and the MUST NOT list), `RelayLog.sink` / `enabled` test seams.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayTransport.kt` → `TransportEvent.Down` — `reason` can be relay-supplied text, so it never enters a line.
- `app/src/test/java/de/pyryco/mobile/data/network/OkHttpRelayTransportTest.kt` → MockWebServer scenarios for peer close (4401), dial failure, malformed JSON, malformed URL; the new assertions ride these.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt` → `Fixture`, `openSession`, the fatal-frame tests (`handshake_*`, `open_undecryptableNoiseMsgTearsDownWithoutCrash`, `open_malformedEnvelopePlaintextTearsDown`, `open_unknownFrameTypeTearsDown`, `open_strayNoiseRespWithNoRekeyInFlightTearsDown`, `timerRekey_respTimeoutTearsDownWhenRespNeverArrives`, `transportDown_*`, `close_isIdempotent`).
- `scripts/android-test-gate.py` → `fresh_reports`, `main`'s single-run copy loop, `run_scripted_all`'s per-scenario copy loop.
- `scripts/test_android_test_gate.py` → `test_live_gate_collects_only_fresh_reports_from_selected_device_path`, `test_scripted_all_runs_every_scenario_and_names_the_failures` — the fixtures the logcat-copy test mirrors.
- Tests that capture `RelayLog.sink` elsewhere (`RelayConnectionFactoryTest`, the ViewModel tests) construct neither the real transport nor the real pump, so the new lines cannot disturb their exact-list assertions.

In-flight overlap: #955, #1017, #1020 and #1021 edit `LIVE_MINIMUM` or `managed_avd` in `scripts/android-test-gate.py` (and #955 its test). Not a dependency; my edits there are additive and local to the copy loops.

## Design source

N/A — no UI; diagnostic logging and a test-gate script.

## Context

Live e2e runs show the phone's fresh relay connection dropping about a second after its handshake. The daemon is ruled out; nothing on the phone records which side ended the connection or with what code. `RelayLog` exists for exactly this and names a close code as safe. This ticket adopts it in the transport and the pump and makes the gate keep the per-test logcat that would carry the lines. The fix for the drop itself is a later ticket, in whichever repo the lines point at.

## Design

### Transport line — `OkHttpRelayTransport`

`terminate` gains a fixed `end` label and an optional code for the line, and writes one `RelayLog` line inside the winning CAS branch, before the `Down` is sent (so a consumer that has seen the `Down` also sees the line):

`event=transport_end end=<label>[ code=<n>][ peer_closing=<n>][ cause=<SimpleClassName>]`

| Path | `end` | `code` |
|---|---|---|
| `close()` | `local_close` | 1000 |
| `Listener.onClosed` | `peer_close` | peer's close code |
| `Listener.onFailure` | `failure` | HTTP status when `response != null` |
| `failLocally` | `protocol_violation` | the local close code sent (1002 / 1003 / 1009) |
| `connect`'s bad-request catch | `invalid_request` | — |

- `cause=` is `Throwable.javaClass.simpleName` when the `Down` has a cause. Never the message.
- `peer_closing=`: `Listener.onClosing` records the peer's close code in a `@Volatile` field. When the socket then ends by any path other than `peer_close` (typically `onFailure` because the relay dropped TCP before the close handshake finished), the line carries that code. Without it, a relay 1011 close followed by an EOF would log as a bare `failure` — the very case this ticket must tell apart.
- Level: `i` for `local_close` / `peer_close`, `w` for the rest.
- Never in the line: `Down.reason` (relay-supplied on a peer close), the exception message, the relay URL/host, the token, the server id.

### Pump line — `NoiseSessionPump`

`teardown(cause)` becomes `teardown(trigger: String, cause: Throwable?)` and writes, inside the winning CAS branch:

`event=pump_teardown trigger=<label>[ cause=<SimpleClassName>]` — `i` when `cause == null`, else `w`.

| Call site | `trigger` |
|---|---|
| `drive`: `sessionFactory.create()` throws | `session_create_failed` |
| `drive`: first-frame wait times out | `handshake_deadline` |
| `drive`: inbound completes before any frame | `handshake_transport_down` |
| `drive`: first frame is not `noise_resp` | `handshake_wrong_first_frame` |
| `drive`: `readResp` / base64 throws | `handshake_resp_rejected` |
| `onOpenFrame` `noise_msg`: base64 decode / `decrypt` throws | `open_decrypt_failed` |
| `onOpenFrame` `noise_msg`: `Envelope` parse throws | `open_parse_failed` |
| `onOpenFrame`: unknown frame type | `open_unexpected_frame_type` |
| `onOpenFrame` `noise_resp` with no re-key in flight | `open_unexpected_noise_resp` |
| `onOpenFrame` `noise_resp`: `readRekeyResp` throws | `rekey_resp_rejected` |
| any other exception out of the open collector | `open_frame_failed` |
| `initiateRekey` watchdog | `rekey_deadline` |
| open collector completes (transport Down) | `transport_down` |
| `close()` | `close` |

The two `null`-first-frame cases are split (timeout vs `NoSuchElementException`) because they answer different questions: a timeout means the daemon never replied, an empty completion means the transport died first. `PumpState.Closed.cause` is unchanged for both.

**Labelling open-state faults.** `onOpenFrame` wraps each risky step with a private helper that rethrows any non-cancellation exception as a private `OpenFrameFault(trigger, cause)`. `drive`'s collector catch unwraps it: `teardown(fault.trigger, fault.cause)`, so `PumpState.Closed.cause` stays the original exception (the existing tests that assert `cause is NoiseSessionException` keep passing). An unwrapped exception falls back to `open_frame_failed`.

The pump's own `transport.close()` shows up in the transport's line as `end=local_close code=1000`; the pump line immediately before it is what explains it.

### KDoc

- `OkHttpRelayTransport` / `NoiseSessionPump` class KDoc: replace the "emits no logs" statement with the one-line-per-end contract and the MUST NOT reminder.
- `RelayLog` KDoc: replace "This ticket delivers the facility only … adopts it where needed" with: the transport and the pump log each connection end (#1039); `RelayConnectionSupervisor` and `RelayRepositoryCoordinator` stay silent.

### Gate script — `scripts/android-test-gate.py`

- New `fresh_logcats(directory, started_ns)`: `logcat-*.txt` under `directory` (recursive) with `st_mtime_ns >= started_ns`, sorted — the sibling of `fresh_reports`.
- `main` (ui / scripted / live): after copying the XML, copy each fresh logcat to `run_dir / f"{index}-{path.name}"`. Copied before `combine_reports` so a failing or malformed report still leaves the logcat behind — the failing run is the one someone needs to read.
- `run_scripted_all`: copy each scenario's fresh logcats to `run_dir / f"{scenario}-{index}-{path.name}"` next to the XML copy, before the next scenario overwrites them.
- The logcat files are artifacts only; they never reach `dispatcher.xml` or stdout (the report stays free of app logs, per the module docstring).

## State + concurrency model

No new coroutines or scopes. The transport's line is written on whichever OkHttp thread or caller wins `terminated`'s CAS; the pump's on whichever coroutine or caller wins its CAS. `RelayLog.sink` is called at most once per transport and once per pump. The `peer_closing` field is `@Volatile`, written on the OkHttp reader thread in `onClosing`, read under the CAS winner.

## Error handling

Logging adds no failure mode: `RelayLog` is compiled out of release (`BuildConfig.DEBUG`) and the lambda is only built when enabled. The `OpenFrameFault` wrapper preserves `CancellationException` (rethrown unwrapped) and the original cause on `PumpState.Closed`.

## Testing strategy

Unit tests, JVM, through `RelayLog.enabled` / `RelayLog.sink` (set in `@Before`, restored in `@After`; the transport capture list is synchronized because OkHttp threads write it):

- `OkHttpRelayTransportTest`:
  - local `close()` after Up → exactly one `event=transport_end end=local_close code=1000` line.
  - peer close 4401 "unauthorized" → one `end=peer_close code=4401` line; no line contains `unauthorized`.
  - dial failure → one `end=failure` line with `cause=` naming the exception class; no line contains the host/port.
  - malformed inbound JSON → one `end=protocol_violation code=1002 cause=…` line.
  - malformed stored URL → one `end=invalid_request cause=IllegalArgumentException` line.
  - Every captured line is checked for absence of the token, server id and dial host.
- `NoiseSessionPumpTest`: each existing teardown test additionally asserts the single `event=pump_teardown` line and its trigger — `handshake_deadline`, `handshake_resp_rejected`, `handshake_wrong_first_frame`, `open_decrypt_failed`, `open_parse_failed`, `open_unexpected_frame_type`, `open_unexpected_noise_resp`, `rekey_deadline`, `transport_down`, `close` (and `close()` twice yields one line). One new test for `handshake_transport_down` (inbound completes before any frame).
- `scripts/test_android_test_gate.py`: a live run whose fake `e2e-emulator.sh` writes a fresh and a stale `logcat-*.txt` next to the XML → the fresh one is in the run's artifact directory, the stale one is not, and the logcat text never reaches stdout; the scripted-all test asserts each scenario's logcat is copied with its scenario prefix.

Run: `./gradlew testDebugUnitTest --tests "*OkHttpRelayTransportTest" --tests "*NoiseSessionPumpTest"`, `python3 -m unittest scripts/test_android_test_gate.py`, `lint`, `assembleDebug`.

No new e2e scenario: this is diagnostic, not an operator-facing flow. AC 4 (the dispatcher's post-verification live run keeps logcat files that contain the lines) is proven by that run, not by this branch.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/relay-log.md`: the transport and the pump now log each connection end; list the `end=` and `trigger=` labels above.
- Any feature overview stating `OkHttpRelayTransport` or `NoiseSessionPump` emits no logs: correct it.
- `docs/knowledge/features/development-verification.md`: a device gate run keeps each test's logcat in its `build/dispatcher-tests/<mode>-*` directory; to diagnose a relay drop, read `RelayLog` lines there alongside the daemon's `daemon.log` by timestamp.

## Open questions

- Exact location of AGP's per-test logcat under the managed-device results directory (directly in `<device>/` or a nested folder). Resolved by searching recursively, so either works.
