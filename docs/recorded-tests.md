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
The current routine runner contains 68 recordings. Three further original methods are
owned by the existing scripted suite because both versions execute the same assertions:

| Original scenario | Retained scripted scenario | Shared assertions |
| --- | --- | --- |
| Session-error recovery | `session-error` | Retained and dropped backlog recovery, message identity and original-host restoration |
| Finished reply selection | `selection-copy` | Real Android word selection, Copy menu and clipboard result |
| Reply suggestion | `reply-suggestion` | Long-press send, one user echo and revisioned suggestion removal |

The manifest names each exact scripted method. The inventory requires that annotated
method and its original harness mapping to remain enabled. It accepts no other scripted
exemptions. These three methods remain in the explicit live selection. Their recordings
are no longer shipped or counted as successful captures. No other recording used them.
The 73 original methods therefore comprise 68 recordings, three scripted owners and
two obsolete workspace methods. Every scripted scenario remains in the normal gate.

Recording capture, replay and strict compatibility commands below select the 68 retained
recordings. Scripted ownership never supplies a capture or compatibility receipt.
Missing recordings, changed contracts, package mismatches and incomplete counted
evidence still fail. Historical 71-recording receipts below describe the earlier selection.
Removing the repeated routine native executions accounts for 42.416 seconds in one
retained Mac run. This is expected test work saved, not a measured whole-verifier gain.

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
replays per scenario, matching contracts and no skipped methods. The suite index declares
version 1 of its query policy for all recordings, including historical captures. It enables
only the three bounded models below. Raw captured messages and their capture hashes remain
verifiable. Standalone recordings without this suite policy retain strict query counts
unless both recordings declare the same model. Unknown policy keys or versions fail.

The full suite preserves all mutating commands in recorded order. Independent connections
may progress separately. Background queries match their complete owner, payload and count.
Their responses wait for their actual correlated requests and preceding recorded commands.
Moving a query earlier cannot expose a future action's answer.

During suite replay, `RecordedSuiteDocument.replayClientInfo` supplies the phone's
`device_name` from captured `register_push_token` requests to the native connection
factory. Only owners named `phone` or prefixed `phone/` supply this label; peer identities
stay separate. The current client's version is retained. A recording without phone
registration keeps the runner's identity, and inconsistent captured phone labels fail.
Live capture uses the actual device identity. This lets an ARM capture replay on an x86
emulator while every registration still matches its complete recorded payload, count and
server owner. Arbitrary device names remain invalid. Capture provenance and raw fixture
bytes are unchanged.

File downloads are read-only queries. They may race other reads, including a model
lookup after reconnect. The attachment identity and complete request remain exact.
Upload chunks and user messages remain ordered commands. Downloaded chunks cannot be
released before the actual request or across a test observation checkpoint.
A connection's end and close also wait for all earlier required requests on that
connection. Read-only requests still count. Another connection can finish independently.
Recorded Android inputs, such as a push token becoming available or a push wake, affect
the whole application graph. They wait for all earlier required requests and lifecycle
events before delivery. They cannot move across a captured app restart and accidentally
change an earlier connection's requests. Independent ordinary connection frames still
progress separately. Only the declared automatic read notifications may remain deferred.
The shared test-driven host disconnect also waits for its recorded close prefix before
cancelling the application jobs. A stored push token alone does not prove its registration
request has run. Replay waits for the actual required requests and queued replies, then
lets the original disconnect proceed. This uses the already recorded lifecycle event;
it does not add observations to historical captures or omit registration requests.

The suite declares three limited scheduling models separately from Claude content:

- Automatic phone read acknowledgements follow content actually presented by the real UI.
  They may coalesce or omit an incidental visibility window. The suite explicitly declares
  `readReceiptPolicy: presented-visible-history-v4`; historical capture bytes stay unchanged.
  Every actual read must increase and refer to history delivered to that phone on that host.
  A peer's captured read notification waits for the actual phone acknowledgement. If its
  connection ends first, only that unsent notification is cancelled. Ordinary frames,
  required queries, user commands and explicit peer reads remain exact. Original read-specific
  UI assertions still require actual confirmation. The migration adds no blanket final-read
  assertion to unrelated feature tests. Standalone v2/v3 recordings retain their stricter
  recorded-final-progress requirement unless the suite explicitly applies its newer policy.
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
Checking the pump's close state and accepting a send share one lock. A sender queued
before teardown cannot reuse an earlier open-state check after that connection closes.
Recorded stream events retain cadence. Real push delivery, encryption, transport recovery
and timing obligations retain their external checks.

### Observation checkpoints and separate verification results

New captures record explicit checkpoints before a phone send and after the original
scenario succeeds. A checkpoint waits for earlier recorded requests and scheduled
deliveries. Intermediate observations allow only the declared automatic read notifications
to remain deferred until the phone actually acknowledges their content. They never invent
an acknowledgement or release a peer read notification early. Under the declared v4 policy,
connection termination cancels only a still-unsent automatic read notification. Strict older
models continue to require recorded final read progress. Ordinary requests, daemon frames and
connection events remain required. Later queries cannot borrow replies across a checkpoint.
Complete request payloads, permission choices and uploaded bytes retain their existing checks. Captures declare
`checkpointPolicy: test-observations-v1`; older captures keep their original checks and
are not rewritten to invent observations that were never captured.

Every verification attempt writes `verification.json`, including failed attempts.
It reports the real capture, committed replay, fresh replay and strict comparison
separately. A failed real capture leaves the later stages unexecuted. Both replays are
attempted after successful capture, even if one replay fails. A comparison difference
retains the successful stage counts and writes private comparison details. It still
fails verification and never produces a successful compatibility receipt. Review
differences explicitly, as Desktop does; do not call them passing compatibility.
`verify-all` retains each attempt and groups failures by stage. `--methods-file`
selects a diagnostic subset; such a subset cannot satisfy the full live inventory gate.
Real push failures belong to the live capture result. Replay cannot overrule them.
`verify-all --stop-file /absolute/path/STOP` finishes its current scenario, preserves the
completed reports and marks the remaining stages unexecuted. A stopped batch fails acceptance.

Desktop uses one continuous recorded prefix. Mobile deliberately differs: independent
query replies and connections may advance separately, and automatic phone reads use the
bounded model above. The shared guarantees are exact actions, request correlation,
connection termination after required work, observation boundaries and complete consumption.
Do not interpret copying Desktop's checkpoints as copying its entire scheduler.

### Replay isolation

`E2eTestApplication` owns one preference DataStore for the instrumentation process.
Both startup paths and every graph rebuild bind that same instance through a fresh
override module. An ordinary isolated Koin graph can close without discarding the
Application's preference factory or opening a competing owner of `app_prefs`.
Replay isolates values in that store; it does not isolate cases in separate processes
or preference files. The store remains active until process exit.

Replay restores the instrumentation arguments, prior pairings and preferences, then
rebuilds the ordinary application graph after each case, including failures. Rebuilds
run on main with no Activity alive, unregister the old lifecycle observer and dispose
the old registry and host sources. Recorded mode must not leak into another device
test or make its ordinary-mode check silently skip.

A seeded fixture must also restore its own original snapshot and rebuild the ordinary
graph in `finally`. Direct snapshot restoration bypasses the observable pairing-store
revision, so the registry created after replay can otherwise retain a synthetic host
connection after its persisted pairing disappears. Cleanup must release that connection
and restore exactly the original registry hosts as well as persisted state, even when
the seeded assertion fails.

`RecordedSuiteIsolationDeviceTest` covers ordinary graph closure with real file I/O,
two successive incomplete replays with ordinary graph closure between them, and a
successful original ping scenario. Two additional probes await synthetic registry
ownership before checking cleanup after normal return and deliberate assertion failure.
See [Device gate evidence for #2208](knowledge/features/development-verification-gates.md#linux-ownership-repair-evidence-2208)
for counted Linux results. These checks remain offline; they do not establish fresh
Claude compatibility, encrypted transport, real push delivery or live timing.

Failed runs can export separately named private application traces. Their format cannot
be loaded as a successful recording. Compressed diagnostic archives are excluded from
failed exports. Successful archive captures undergo bounded decompression and credential
inspection before promotion. Private host logs and failed traces are never fixtures.

### Read acknowledgements and temporary prompts

The migration exposed valid temporary permission and question controls being retained as
unidentified durable content. That permanently prevented later read checkpoints after a prompt.
The production projection now recognizes these two strictly decoded controls only when they
have no durable history identity. It creates no history fact and grants no visibility.
The real viewport still refuses read qualification while a prompt obscures the thread.
Unknown classes, malformed controls and unexpected durable entries retain their barriers.

A blanket final-read assertion was also adding a timing requirement to unrelated scenarios.
A successful live run can acknowledge a row in the short interval before a prompt covers it;
replay can render the same required screens without that interval. The v4 model follows actual
presentation and actual acknowledgements. It preserves original feature assertions and the
explicit read/attention tests instead of forcing that incidental interval to repeat.

### Cross-architecture replay evidence, 2026-10-17

Verification scope for [#2201](https://github.com/pyrycode/pyrycode-mobile/issues/2201)
was Linux x86_64, Android API 33. The original reported eleven-case failure output was
not recovered. The failed results below are fresh controlled reproductions using the
exact pre-repair document and native factory from `50ba564df`, with current regressions.
A temporary unit-only API bridge returned the runner identity; native reproduction used
the original factory directly. The bridge is absent from the repaired source.

The phone-identity regression sends the registration through the strict tape before
asserting identity equality. Checking equality first would fail without demonstrating
the actual request mismatch. Keep the separate wrong-registration-payload rejection
check so selecting the captured input cannot conceal relaxed matching.

| Check | Executed | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Pre-repair document regressions | 11 | 8 | 3 | 0 |
| Pre-repair affected native method | 1 | 0 | 1 | 0 |
| Restored recording unit selection, `f402e5972` | 85 | 85 | 0 | 0 |
| Restored full native recorded class, `f402e5972` | 71 | 71 | 0 | 0 |
| Current recording unit selection, `323213178` | 86 | 86 | 0 | 0 |
| Current full native recorded class, `323213178` | 71 | 71 | 0 | 0 |

All rows had zero errors. The failed document case
`replayUsesCapturedPhoneNameWhileKeepingCurrentVersionAndExactTape` and native method
`interactiveTurn_pingPrompt_footerShowsContextUsage` reached the strict
`register_push_token` mismatch. Both passed after restoration; the native method passed
within each full 71-case run. All eleven document cases, including
`replayStillRejectsWrongRegistrationPayloads`, passed in both repaired unit selections.
The native runs removed credentials and recorded zero producer invocations. All 73
original recording/index JSON files retained their hashes.

The [content-free evidence index](../app/src/androidTest/assets/recorded-2201/evidence.json)
records commands, revisions, report hashes and retained failed/repaired evidence locations.
Raw evidence was stored under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2201/`:
`rework-20261016-identity/` holds the failed/repaired XML, phone logs, source snapshots
and reproduction patch; `current-20261017/` holds the current unit and native reports.
Their manifests verified all 111 and 100 files respectively. These private paths identify
where raw captures were retained at review time; the counted results above survive pruning.

The separate scripted read-confirmation failure
[#2198](https://github.com/pyrycode/pyrycode-mobile/issues/2198) is resolved by
[PR #2199](https://github.com/pyrycode/pyrycode-mobile/pull/2199), merged as `a0c7db693`
and included through `8ddba7509`. Its complete Linux dispatcher `scripted-all` run at
`894bc402a` executed and passed 22 tests, with zero failures, errors or skips, including
both ping methods. Raw counted XML was retained at
`resolved-2198-20261017/logs/verifier-gate__2198_8.log` under the same evidence root;
all 128 bundle manifest entries verified. The
[verifier PASS and evidence review](https://github.com/pyrycode/pyrycode-mobile/pull/2207#issuecomment-6228349651)
distinguishes this historical run from the current candidate's complete scripted gate.
Earlier failed captures remain failures. macOS was outside this ticket's verification scope.

## Complete-suite rollout evidence, 2026-10-16

At rollout, the inventory accounted for all 73 original native methods. All 71 active
scenarios had reviewed genuine recordings. Two workspace scenarios were explicitly retired.
The current routine selection is described above and does not rewrite these historical results.

| Check | Reported | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Genuine real-Claude capture | 71 | 0 | 0 |
| Saved recording replay | 71 | 0 | 0 |
| Fresh recording replay | 71 | 0 | 0 |
| All promoted recordings in the normal device run | 71 | 0 | 0 |
| Complete normal device selection | 335 | 0 | 1 |
| Full JVM check | 5718 | 0 | 0 |
| Recording host checks | 35 | 0 | 0 |

The normal device run used the actual 66-class selection, Gradle's configured focus
listener and a writable screenshot output directory. It preserved counted XML and
screenshot outputs. The existing rename pixel-capture test skipped because its rendered-content
assumption failed on the ATD image; its source already reserves that pixel evidence for the
full Pixel 8 image. Every recorded scenario executed and passed. The run removed
Claude credentials and used failing producer-launch traps. Neither producer was invoked.

All genuine captures used Mobile `788314223b701ea5c6a0b530ac255451de97e36e`,
daemon `c2e7bec7a434014dfc2089f64d507898c79f8e89`, Claude Code 2.1.280 and Android
API 33. The application package stayed byte-for-byte unchanged during subsequent
player repairs. Passing per-case replay receipts retain their actual player revisions.
The final combined run exercised every promoted recording together from source
`355af9088e3e60564a7b43d33e5f3019aa9b83ef` on one final app and test package.
Capture package hashes identify the producer before asset promotion. The separate
combined-run receipt identifies the package containing the promoted recordings.

Strict comparison of the older and fresh captures reports 71 differences. Its failures
remain failures. New genuine observation checkpoints account for the recorder upgrade.
Complete normalized action payloads and counts match in all 71 cases; connection
lifecycles match in all 71. One automatic peer approval changed order. Seven cases
have reviewed background-query count variations. Each recording still requires its
own complete requests and counts. The exact successful fresh captures become the
reviewed baseline. No successful strict-compatibility receipt is claimed.

The [structured rollout evidence](evidence/recorded-claude-rollout-review.json) records
actual producer and player identities, every counted scenario, the final combined
result and the explicit comparison decisions. It is a manual rollout review, not
input to the strict compatibility gate. Real service and timing obligations remain live.
