# Recorded Claude client tests

These tests record the Android client's application messages during a controlled real
Claude permission flow. Offline replay uses the same repository, connection coordinator,
ViewModel and Compose thread screen. The source lives in test directories. The normal
application contains no recorder or recording switch.

The first scenario sends a controlled Bash request, arms Allow once, confirms it, observes
the permission close and checks the exact settled assistant reply. The live producer also
proves that the witness file is absent before confirmation and present afterwards.

## Build and run

Use an explicitly owned test emulator. Build and install the normal application and test
APKs with the existing Android SDK and JDK configuration:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb -s "$TEST_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$TEST_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/recorded-exchanges.py replay --serial "$TEST_SERIAL"
```

The instrumented class is `de.pyryco.mobile.recording.RecordedPermissionTest`. It runs in
the ordinary non-live UI selection. Its default is offline replay. No real daemon, Claude
executable, test login or relay connection is used. The app's unused transport throws if
anything attempts a real connection. Existing scripted and live test selections remain.

## Capture and compatibility

Commit the source being exercised before capturing. Use an isolated test daemon executable
with Go source metadata and the existing configured Claude test login. The producer uses
the existing live relay and creates its own daemon home, working directory and permitted
test pairing. It stops that daemon and its children afterwards. It does not alter the
application daemon or saved phone pairings.

```sh
scripts/with-claude-login.sh python3 scripts/recorded-exchanges.py capture \
  --serial "$TEST_SERIAL" --pyry "$PYRY_BIN" --output /absolute/path/to/new-candidate.json
scripts/with-claude-login.sh python3 scripts/recorded-exchanges.py verify \
  --serial "$TEST_SERIAL" --pyry "$PYRY_BIN"
```

Capture records only decoded application messages, with a 2 MB and 5000-frame limit.
Handshake frames, keys and pairing credentials do not enter the recorder. Sensitive
fields, known credential patterns and the actual configured Claude credentials are
rejected. Controlled workspace paths and ephemeral approval values are normalized.
The fixture is suitable for this controlled scenario, not personal conversations.

Only a successful UI run and the required file-effect checks can create a candidate.
Candidates use private permissions and never overwrite existing files. Review a candidate
before committing it. Failure diagnostics stay in the private local build directory.
The producer records the Mobile revision, daemon revision and executable checksum,
Claude version, test APK checksum, negotiated capabilities and capture time.

Verification captures a fresh candidate, compares the logical outgoing request contracts
and replays both the committed and fresh recordings. It never replaces the committed
recording. Changed requests, choices or recovery steps require a reviewed replacement.
Missing login, setup failure and a run with no executed tests fail these explicit commands.

## What replay proves

Every outgoing request must match its complete recorded type and payload. Generated
identities are rebound, and replies follow the actual request identifiers. Commands
retain their action order. Only named read-only queries may reorder within one action.
Daemon frames keep their recorded order and wait for their causal requests. Missing,
extra, duplicate or changed requests fail, including requests after the last UI assertion.

The recording identifies the current production presentation. Mobile has not yet activated
required daemon-built threads in ticket 2175. This test does not turn on a test-only
production route or synthesize thread items. The migration must pass live verification
and refresh the recording when its actual requests change.

Replay proves the recorded permission behaviour. Current-daemon compatibility requires
the explicit live command. Timing, encrypted transport, reconnect, other permissions,
session grants and other live scenarios retain their existing tests and requirements.
Android startup and UI costs remain, so report measured Android times rather than using
Desktop's replay timing as an Android forecast.

## Verified evidence, 2026-10-16

The device was the owned, read-only `claude955` Android ATD emulator, Android 13,
API 33, arm64. The committed fixture has 48 application frames and SHA-256
`71fff5a9926e594b9568b615480e8c797276eee3956f87829aab0086631c49c1`.
Its producer metadata is stored with the asset. It includes the observed initial
read errors and the actual `backfill_since` request. No Desktop retry action was added.

A separate fresh compatibility run used clean daemon main
`4d98f07513f04fa36fa55714b31ecf09e1cb34c2`, executable SHA-256
`af8261315dd0d4f347a523b3a138bbc36b8cb49aecc3c1d8f6bbaada64227fd9`,
and Claude Code 2.1.280. The outgoing contracts matched, and both recordings passed.

| Check | Executed | Failed | Skipped | Android seconds |
| --- | ---: | ---: | ---: | ---: |
| Fresh live capture against current daemon main | 1 | 0 | 0 | 8.210 |
| Committed fixture replay in that check | 1 | 0 | 0 | 2.929 |
| Fresh candidate replay in that check | 1 | 0 | 0 | 2.826 |
| Default asset with credentials removed and producer traps | 1 | 0 | 0 | 3.454 |
| Repeated offline replay with producer traps | 2 | 0 | 0 | 2.863, 2.852 |

These are instrumentation wall times on an already booted emulator. They exclude
build, install and daemon startup. The traps replaced both `claude` and `pyry` on
PATH and recorded zero invocations. Android received no Claude test credentials.
The production debug APK contained neither recording classes nor the recording switch.
The full JVM suite passed 5646 tests. The seven Python contract/export checks passed.
Formatting and the documentation guard passed. The full scripted and live suites
were not rerun for this test-only slice.

## Complete native suite

The permission recording above remains separate. The complete suite inventories every
annotated real-Claude method in `scripts/recorded-suite-inventory.py`. Its native runner
calls each original scenario body through the real repository and Compose interface.
Two workspace scenarios remain explicitly retired because their controls were removed.
Missing recordings fail the inventory. They never count as converted scenarios.
The normal Gradle `check` task enforces this inventory, including capture and replay hashes.

```sh
python3 scripts/recorded-suite-inventory.py
python3 scripts/recorded-mobile-suite.py replay-all --serial "$TEST_SERIAL"
scripts/with-claude-login.sh python3 scripts/recorded-mobile-suite.py verify-all \
  --serial "$TEST_SERIAL" --pyry "$PYRY_BIN" --daemon-source "$PYRYCODE_SRC" \
  --output /absolute/path/to/new-verification-directory
python3 scripts/recorded-suite-inventory.py --require-live \
  --live-evidence /absolute/path/to/new-verification-directory/summary.json
```

Capture requires committed source. Before either capture or replay, the host checks the
installed app and test package hashes against the local build outputs. Evidence records
those verified hashes. Fresh compatibility evidence lives outside Android assets so its
own package hashes cannot change the test package they describe. The complete gate
requires one common client build and producer, one successful real capture and two counted
replays per scenario, matching contracts and no skipped methods. Older recordings retain
strict query counts unless both recordings declare the same polling model.

The full suite preserves all mutating commands in recorded order. Independent connections
may progress separately. Background queries match their complete owner, payload and count.
Their responses wait for their actual correlated requests and preceding recorded commands.
Moving a query earlier cannot expose a future action's answer.

The suite declares three limited scheduling models separately from Claude content:

- Automatic phone read acknowledgements can coalesce. They must advance through history
  actually delivered to that phone on the same host and reach recorded final progress.
  Explicit peer reads stay exact. Peer read notifications wait for the actual phone read.
  Independent permission prompts and turn events continue while that notification waits.
  The replay rule keeps the screen alive until its required automatic reads settle.
- A single model lookup can race an unsolicited catalogue on the same connection and
  conversation. Omitting a recorded lookup requires an identical independent catalogue in
  the same command interval. On-demand lookups without that evidence stay mandatory.
- Successful settings polling reads the most recently delivered captured settings state.
  Snapshot updates remain in producer order and behind their original user commands.
  A settings write invalidates earlier snapshots for that session, including replies
  already queued for delivery. The next fresh read waits for a post-write snapshot.
  Poll counts can vary. Changed owners or complete query payloads fail. Refused or errored
  settings requests remain exact. A final unanswered settings poll cancelled by closing
  that connection may be omitted only when earlier successful settings snapshots exist.
  It cannot supply a new state or hide a missing response before another client action.
  A legacy backfill probe can vary only when that exact
  probe was captured as unsupported. Other history requests and error types stay exact.

All generated replies retain recorded payloads and correlate to the actual request.
Duplicate request IDs fail. Synthetic response IDs share an allocator above recorded IDs.
A closed replay pump refuses further local sends, matching the normal pump's Boolean
contract. These cannot emit daemon messages. Any explicitly captured refused call still
requires its complete request payload and must be consumed.
Recorded stream events retain cadence. Real push delivery, encryption, transport recovery
and timing obligations retain their external checks.

Failed runs can export separately named private application traces. Their format cannot
be loaded as a successful recording. Compressed diagnostic archives are excluded from
failed exports. Successful archive captures undergo bounded decompression and credential
inspection before promotion. Private host logs and failed traces are never fixtures.
