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

The standalone permission fixture deliberately requests legacy capabilities. It proves
that explicit diagnostic route and supplies no required-thread activation evidence.
The full recorded Mobile suite uses the actual production selection. Changed production
requests require genuine native capture, fresh replay, preserved strict comparisons and
explicit review of every difference. Neither route synthesizes daemon-built thread items.

The independent reply witness observes actual accepted phone history requests and
their separately received completion markers. It checks the producing connection,
conversation, epoch and request identity. Rejected sends and invalid batches cannot
qualify a later completion. Same-epoch resets require a fresh certified query.

The peer-reopen observer taps the destination's existing negotiated thread collection.
It adds no query or collection. Ownership updates retain the same subscription token;
only ending that collection and opening another can establish a reopen. Canonical
fact identity survives changes to rendered row identities.

Saved-checkpoint recovery first proves the actual request from the saved epoch and
checkpoint. A successful bounded tail retains every missed post and baseline fact.
A real correlated reset instead requires its independently decoded current epoch and
fresh certified newest window. That reset may replace the saved epoch; only the recovery
observer follows it, while ordinary live reply ownership stays strict. Physical pulls then request exactly one contiguous older page
at a time. Both paths retain every missed post, the exact independently produced reply,
baseline facts and rendered order. Across an actual epoch replacement, baseline rows
are rebound by their original session, turn, role, full producer text and user message
identity, using only the newly received canonical rows. Same-epoch facts retain exact
canonical identity. Neither path uses a legacy history request.

The offscreen cache check proves the exact certified baseline family is saved. It does
not require unrelated newer live facts to be certified. The same immutable saved snapshot
is also inspected for every raw missed-turn assistant fact, including hidden or partial
facts. This persistence check never grants live admission. Queue-consistency and Markdown
cases reveal their exact targets through existing scroll actions. Dedicated following
checks still require automatic visibility without a reveal.

Completion certification and the shown-content watermark are separate:
settling an existing reply may revise it without creating newly shown content.

Replay failures include structural history-query diagnostics without message content.
Those diagnostics do not change matching or request contracts. An already requested
thread reply may pass an unrelated conversation's unrequested query on the same socket.
Its exact owner, conversation, epoch, checkpoint and request mapping remain mandatory.
Already matched replies for the explicitly recognised metadata requests may also be
crossed. Their own delivery stays fenced. Earlier unreleased canonical facts for the
same conversation, unknown attribution, lifecycle changes and Android inputs still
fence the thread reply. The other query remains required.

Replay proves the recorded permission behaviour. Current-daemon compatibility requires
the explicit live command. Timing, encrypted transport, reconnect, other permissions,
session grants and other live scenarios retain their existing tests and requirements.
Android startup and UI costs remain, so report measured Android times rather than using
Desktop's replay timing as an Android forecast.

### Attention destination acquisition (#2350)

The Waiting pill can appear before destination metadata makes B's initial attention
query eligible. Waiting for its certificate before tapping the pill can therefore
prevent the very query being awaited. Acquiring while that query is active instead
coalesces with it; acquiring after completion sends the second captured query. A
missing second query correctly holds the phone's dismissal/completion frames even
when the independent peer has already observed dismissal. Peer progress alone does
not establish a product completion or expiry defect.

`InteractiveStreamE2ETest.interactiveTurn_otherConversationAttentionPills_waitingAndFinished`,
also called by `RecordedMobileSuiteTest`, arms the test-only `InitialAttentionQueryGate`
for freshly created B. The original Waiting-pill tap lets destination list/metadata
setup proceed; only `NegotiatedThread.acquire` waits. Readiness observes
`NegotiatedThread.store.observe(host, conversation)` directly: the cached provider's
`observe` also restores and persists data, so collecting it would introduce extra
provider work. Readiness requires a nonempty epoch, positive certified checkpoint,
certified coverage and no repair/reset flag. Batch receipt alone is insufficient.

The gate pins the host, conversation, connection identity and store generation when
armed, filters readiness to that generation and rechecks scope, connection and
generation before delegating real acquisition. Other targets and unarmed scenarios
retain normal provider admission. Timeout/cancellation fails the wait; closing the
scope clears the gate and cancels its acknowledgement. The scenario pumps Compose
within its existing thread deadline until actual delegated acquisition is acknowledged,
before Back can cancel it. The real query controller remains responsible for both
exact captured requests, including the second request at checkpoint 13; the gate
neither sends queries nor releases frames.

`InitialAttentionQueryTest` controls initial send, batch receipt, terminal certification
and navigation through real query controllers and a strict tape. The active-query and
completed-query cases require both exact sends; the unfenced control leaves the second
query and phone completion pending. Other probes retain full payload/owner/count
matching, observation fences, conversation and generation attribution, cancellation,
scope closure and default provider admission. Production coalescing, recordings and
reconnect admission are unchanged. The native scenario still requires the original
Waiting display and tap, the same outstanding permission after tap and Back, return
to A, peer-only answer/dismissal, actual Finished display, five-second virtual expiry
(clock advanced 5,100 ms), absence after reopening A and complete tape consumption.
Original deadlines remain in force.

#### Counted macOS replay evidence, 2026-10-21

The exact native method was
`de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_otherConversationAttentionPills_waitingAndFinished`.
Focused attempts used `python3 scripts/android-test-gate.py ui --tests` with that method;
the configured full gate used
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`.

| Attempt | Revision | Executed | Passed | Failed | Skipped | Exit |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Pre-tap wait, `ui-gomdojl7` | `ae336abd89b4c03f41ad5ad98d9467c0cf407966` | 1 | 0 | 1 | 0 | 1 |
| Acquisition gate, `ui-mu_hiccu` | `9598c1f6f451aba5b6e21125c1cef1c899aebe29` | 1 | 1 | 0 | 0 | 0 |
| Configured UI gate, `ui-qoysu3xi` | `9598c1f6f451aba5b6e21125c1cef1c899aebe29` | 335 | 332 | 3 | 1 | 1 |

The first attempt timed out before B's initial query was sent. The repaired focused
method passed in 6.82 seconds; its retained logcat contains all eight captured thread-query
sends. The same method passed within the full UI gate (5.233 seconds), with no failure
or skip in its case. The overall gate retained its failures: permission isolation
[#2349](https://github.com/pyrycode/pyrycode-mobile/issues/2349), model selection
[#2351](https://github.com/pyrycode/pyrycode-mobile/issues/2351), and the unrelated offscreen
reply flake. The dispatcher reproduced the first two on main and the flake passed its
focused rerun. The [accepted verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2358#issuecomment-6306475597)
records these dispositions. Dispatcher JVM/build checks passed 6368/6368, with zero
failed/skipped; the final focused acquisition class passed 11/11. The unfenced boundary
control retained 9 executed, 7 passed, 2 failed, 0 skipped.

Raw focused XML, logcat, source identities, exit statuses and manifests were retained at
`$AGENTS_REPO_PATH/logs/evidence/2350/builder-20261021-macos/native-first-fail/` and
`native-final-pass/` under the same root. The accepted verifier evidence bundle is run
`b9f310c06228aa022ca8dd886eb5adeeb1fd9c6176c0a6e2caf3ff6b1ba2dde1`; its
`logs/verifier-gate__2350_5.log` retains the counted dispatcher XML. These references
identify evidence retained at review time; the counts above survive raw-file pruning.
No daemon or Claude launch, replacement recording or current-provider compatibility
claim was needed. Linux was outside this ticket's verification scope.

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
The current routine runner contains 67 recordings. Three further original methods are
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
The 72 original methods therefore comprise 67 recordings, three scripted owners and
two obsolete workspace methods. Every scripted scenario remains in the normal gate.
The word-reveal scenario and its recording were removed on 2026-10-17 at Juhana's
request, because text appearing at once is acceptable.

Recording capture, replay and strict compatibility commands below select the 67 retained
recordings. Scripted ownership never supplies a capture or compatibility receipt.
Missing recordings, changed contracts, package mismatches and incomplete counted
evidence still fail. Historical 71- and 68-recording receipts describe earlier selections.
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
version 2 of its query policy for all recordings, including historical captures. It enables
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
- Suite query policy version 2 uses `recorded-snapshots-v2`. Version 1 remains supported with its original rules.
  Version 2 also permits a settings poll after an empty, explicitly cleared session state was delivered on that
  exact phone connection. The full request must contain only that recorded conversation ID. This exception
  applies only when the same connection is recorded ending before any fresh settings state or intervening
  user command. It supplies no answer. Pending reads are cancelled only when that connection actually ends.
  A replacement connection's snapshot cannot answer them. Captured errors, refusals, changed owners and
  changed request payloads stay strict. Raw capture bytes and producer identities are unchanged.
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
Recorded stream events keep their exact order, but replay compresses their cadence.
Each captured gap replays at one tenth of its length, `SuiteConnections.REPLAY_DELAY_DIVISOR`.
A nonzero gap keeps at least 20 ms, or its captured length when that is shorter, so
consecutive frames still reach the screen in separate UI frames and intermediate states
stay observable. Recordings keep the real captured gaps; only the player shortens them.
Replay proves payload, order and correlation, not timing, so the shorter wait drops no
coverage.

A few recordings replay at their captured speed instead. They are listed by scenario name in
`SuiteConnections.ORIGINAL_SPEED_SCENARIOS` and selected when the suite loads the tape.
Each asserts a transient on-screen state that lasts only as long as a captured gap, so the
scaled gap could end it before the assertion sees it. The new-session scenario is listed
because it waits for the "wrapping up" label, which lasts about 4.5 seconds in the recording.
Add a scenario only after it fails at the scaled speed and the failed wait is such a
transient. Do not add one for a slow or flaky device; a unit test checks that each
listed name has a shipped recording. Checkpoints, close readiness and negative observations wait for released
messages, not tape time, and the negative window stays its fixed one second.
Real push delivery, encryption, transport recovery and timing obligations retain their
external checks.

### Explicit graph-restart readiness (#2347)

An explicit `restartApp` destroys the Activity and rebuilds the graph inside the
scenario, before final Activity teardown. Call
`RecordedSuiteNative.beforePhoneRestart` before Activity destruction, while the
old graph's request producers and replay delivery workers remain alive. In replay,
`SuiteConnections.beforePhoneRestart` snapshots currently registered, unclosed
`phone/` transport owners under the hub monitor, then calls `beforeClose` for each
outside that monitor. It drains required requests and queued delivery under the
existing 30-second bound. Capture and ordinary execution keep a no-op fence.

The snapshot excludes peers, closed owners and future phone generations. Final
`beforePhoneTeardown` instead follows the tape's phone closes before scenario
completion and can wait for replacement owners. Reusing that final fence during
restart could wait for a graph that cannot exist until relaunch. Keep these two
boundaries separate; neither synthesizes queries, changes payload matching or
waives complete tape consumption.

Peer completion alone does not certify phone catch-up. The interrupted-retrieval
tape records peer `turn_end`, the phone's version-0 query and replies establishing
version 20, then a required version-20 query and reply before generation 0 closes.
An earlier version-0 query cannot stand in for that later checkpoint. The original
failure closed at this restart boundary, before the retrieval-triggered cut; later
reconnect and cleanup timeouts propagated the first sticky mismatch, rather than
identifying another attachment failure or the later [#2219 close race](#atomic-transport-close-2219).

`SuiteConnectionsTest` covers current owners, queued replies, successive restarts
and exclusion of independent peers/future generations with
`phoneRestartWaitsForCurrentQueriesWithoutWaitingForFuturePhonesOrPeers`.
`phoneRestartCannotOmitARequiredQuery` and
`phoneRestartStillRejectsAChangedQuery` retain missing-work and changed-checkpoint
failures. See [counted restart evidence](knowledge/features/development-verification-gates.md#graph-restart-replay-evidence-2347)
and [attachment recovery assertions](knowledge/features/attachment-retrieval.md#interrupted-retrieval-restart-replay-2347).

### Scenario host-removal readiness (#2349)

Peer turn completion does not establish phone completion: permission isolation can
finish every A/B assertion while B's required catch-up and its scheduled reply are
still pending. Removing the paired host then disposes the phone connection. The
activity-level `beforePhoneTeardown` fence runs too late for removal inside the
scenario's `finally`.

The Linux #2266 failure reached `RelayConnectionRegistry.reconcile` at lifecycle
step 46: `phone/host-2/0:close` arrived while `thread_catch_up` remained required
(`matched=45 remaining=14`). Delivery and test-end errors rethrew that first stored
mismatch. The #2343 split moved this repair to #2349, implementation
`eaeb636274498f67d50738de3f7b6ca54db3c830`, merged as
`313f151cc10e101675c717f750ca6bc56c13c502`.

`withPermissionIsolationCleanup` preserves the captured lifecycle order: close the
peer, call `RecordedSuiteNative.beforeHostLinkClose(serverId)`, then remove the host.
In replay, the native fence resolves the current phone owner and delegates to
`SuiteConnections.beforeClose`. Its existing 30-second bound waits for required
requests and queued deliveries while the phone graph remains usable. Capture and
ordinary execution keep a no-op fence. No request is fabricated or cancelled to
claim readiness; ownership, full payloads, causal delivery and complete consumption
remain strict, with unchanged recordings and scenario deadlines.

Every cleanup action is attempted even if a preceding action fails. The scenario
throwable remains primary; otherwise the first cleanup throwable is primary. Later
failures are suppressed in peer/fence/host order, excluding self-suppression. The
tape's first stored mismatch remains available through completion checking. A fence
timeout still permits physical host cleanup and fails the test. A timeout at B's
dialog dismissal before cleanup is a separate earlier failure, not evidence that
this fence repairs dismissal.

`PermissionIsolationCleanupTest#hostRemovalWaitsForPhoneCatchUpAndScheduledDeliveryAfterPeerCompletion`
holds a real required phone request after peer completion, then releases it and
retains a delayed correlated reply. Host removal must wait through both phases.
`missingPhoneWorkFailsAtTheExistingDeadlineAndStillRemovesHost` checks the unchanged
30-second failure bound. `aReplayMismatchRemainsFirstThroughCleanup` rejects changed
owner, conversation, epoch and checkpoint; the remaining probes cover scenario and
cleanup failure ordering, self-suppression and ordinary peer-before-host cleanup.

#### Counted macOS cleanup evidence, 2026-10-21

[PR #2354](https://github.com/pyrycode/pyrycode-mobile/pull/2354) was accepted at
`96c4e6303f1da310f7c22de5df46a4ca0e972b23`, against merged main
`0dffe4d6b723540e9ab03ae511c240975fe6074a`. This includes #2350's reviewed
acquisition repair through `9598c1f6f451aba5b6e21125c1cef1c899aebe29`, merged by
`01e48af6269733a309e75ed2c7d03041ade44bb0`.

| Attempt | Executed | Passed | Failed | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Old-cleanup control, uncommitted source on `54606253372257c578cb094cc26e967196e101b3` | 1 | 0 | 1 | 0 | 1 |
| Intermediate invalid synthetic-reply fixture, same builder workspace | 7 | 6 | 1 | 0 | 1 |
| Integrated cleanup class, reviewed revision above | 7 | 7 | 0 | 0 | 0 |
| Focused permission replay, `ui-4hhtfo1d`, reviewed revision | 1 | 1 | 0 | 0 | 0 |
| Configured JVM/build gate, reviewed revision | 6375 | 6375 | 0 | 0 | 0 |
| Configured UI gate, `ui-mrji4bh_`, reviewed revision | 335 | 332 | 3 | 1 | 1 |
| Same-tree UI failure comparison, `ui-wv1edlrj` | 3 | 3 | 0 | 0 | 0 |
| Configured scripted-all gate, `scripted-all-k9zr05k_`, reviewed revision | 21 | 19 | 2 | 1 | 1 |
| Same-tree scripted failure comparison, `scripted-all-5guvxb13` | 2 | 1 | 1 | 1 | 1 |
| Session-error baseline, merged main above | 1 | 0 | 1 | 0 | 1 |

The control failed with `Peer completion cannot authorize phone removal`. The
intermediate reply lacked required decode fields; its failure is retained separately
from the corrected passing fixture. The integrated focused classes also passed
acquisition 11/11, connections 23/23, tape 44/44 and activity rule 2/2, each exit 0
with zero failed/errors/skipped. The dispatcher report explicitly confirms the held-work,
missing-work, sticky-mismatch, scenario-failure and ordinary-order probes passed in
the full JVM gate.

The exact native method was
`de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_permissionPrompts_heldPerConversation`,
selected with `python3 scripts/android-test-gate.py ui --tests`. It executed/passed
in the focused run (10.336 seconds) and in the complete configured UI gate
(8.166 seconds), with zero failed/errors/skipped in each case and every permission
stage through RemoveB and EndB. Attention also executed/passed in that UI gate.
The configured commands were `python3 scripts/jvm-test-gate.py --with-build-checks`
and `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
(or `scripted-all`).

Verifier PASS used the dispatcher's explicit failure disposition, not an all-pass
raw suite: model-change, offscreen-reply and background-agent UI failures passed
the same-tree comparison; offscreen is tracked in #2360. Scripted ping passed its
comparison. Session-error failed again and on merged main, accepted as inherited
and tracked in #2344. `renameAtFigmaViewport` and scripted durable-gap were skipped,
not passed. The queued model/offscreen/attention sequence assessment had zero
executions and supplies no sequence evidence.

Earlier reviewed source `ee784e3bce152366b6efefdaaedb082e1caf5cf5` passed focused
permission replay `ui-0iutvnod` (1/1, exit 0) and permission in its UI gate. That
whole gate failed (335 executed, 332 passed, 3 failed, 1 skipped, exit 1); its
model/offscreen/attention comparison failed attention (3 executed, 2 passed,
1 failed, 0 skipped, exit 1). Different-selection attention passes did not resolve
that finding; #2350's integrated acquisition repair and fresh gate evidence did.
The historical #2350 B-dismissal failure remains distinct from the original
post-assertion premature close.

Raw XML/logcat, source snapshots, commands, exits, hashes and counts were retained
under `$AGENTS_REPO_PATH/logs/evidence/2349/`: `builder-20261021-macos/` holds the
control, intermediate failure and original focused pass; `rework-20261021-macos/`
holds the earlier gate/sequence evidence; `integrated-20261021-macos/results/`
holds the reviewed focused results. Accepted verifier bundle
`f2677cc26f813479c66e121e1d41307d95d6bdd911677c5d9baa2b6495ae61be`
retains the verdict and counted dispatcher XML in `logs/verifier-gate__2349_5.log`
and `_6.log`, plus comparison logs. These are review-time locations, not permanent
build paths. No recording, production contract or current-daemon compatibility
claim changed. Linux was outside this ticket's verification scope.

#### In-flight read evidence (#2345)

A later Linux failure in #2333 has a different first error:
`Read receipt checkpoint rejected: target=13 previous=0 delivered=false maximum=null action=10`.
The peer's B-answer stack rethrows this earlier phone-read failure. A's captured
answer notice advances live content after the phone qualifies a read from the
delivered, certified checkpoint-13 window. Consulting only the latest presentation
forgets the earlier valid window; the host-removal fence cannot repair that race.

`SuiteThreadReadReceipts` remembers eligible watermarks before and after delivered
frames, by phone owner, conversation and store generation. Later live content can
advance without revoking proof for an in-flight read. Eligibility still requires
actually delivered metadata, complete query certification and compatible shown
rows. Merely enqueued or partial replies grant nothing. Unknown watermarks and
foreign hosts, clients or conversations remain rejected; existing same-phone/host
retention remains bounded by generation. Repair/reset invalidates earlier proof,
including same-epoch resets, which require fresh complete certification. Payload
shape, monotonicity, action bounds and response correlation remain checked by
`SuiteReadReceipts`. This changes replay evidence, not product read admission.

`SuiteReadReceiptsTest#aReadQualifiedByDeliveredCertifiedContentSurvivesANewerLiveRevision`
controls the ordering; `theRecordedPermissionAnswerCannotEraseTheEarlierCertifiedReadWindow`
reproduces it with the unchanged genuine permission recording. Both failed against
the old validator and pass after repair. Keep the future-watermark, foreign-owner,
query-repair and same-epoch-reset probes alongside these positive cases. All original
isolation assertions remain: A/B hold separate prompts across navigation, the phone's
armed/confirmed answer resolves only A, and the peer answer dismisses B and ends its
turn. Complete request/payload matching, causal delivery and full tape consumption
remain mandatory.

#### Counted Linux evidence, 2026-10-21 (#2345)

The exact native method was
`de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_permissionPrompts_heldPerConversation`.
Three fresh invocations used `python3 scripts/android-test-gate.py ui --tests`
with that fully qualified method on repair
`ca074c0e7161a9cf68d79c74a9531064b58fabbf`. Each completed all 26 permission stages.

| Attempt | Executed | Passed | Failed | Skipped | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: |
| Fresh focused replay, `ui-3rqgkmbz` | 1 | 1 | 0 | 0 | 8.353 |
| Fresh focused replay, `ui-4ds7i5ok` | 1 | 1 | 0 | 0 | 8.275 |
| Fresh focused replay, `ui-zesz89qc` | 1 | 1 | 0 | 0 | 8.528 |

The [accepted verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2361#issuecomment-6309899647)
reviewed `b575f0e5442c3960fd4cd3adb79e5341c6079ce9`. The later configured
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` run
(`ui-swzuz0gv`) executed/passed **335/335**, with **0 failed, 0 errors, 1 skipped**,
exit 0. Its XML contains the exact permission method executed/passed once in
6.328 seconds, with no failure or skip. Only `renameAtFigmaViewport` skipped.
This supplies suite acceptance separately from focused proof. Earlier full-gate
load/order is not fully known; the controlled single-tape ordering reproduced the
residual cause without a wider investigation.

The configured JVM/build gate executed/passed **6381/6381**, zero failed/skipped,
including the delayed catch-up/delivery cleanup regression and all six new
read-evidence probes. The five builder-focused JVM classes passed **109/109**,
zero failed/errors/skipped. Configured `scripted-all` passed **21/21** executed,
zero failed/errors and one skipped `interactiveTurn_seededChannel_durableGapCatchUp`.
All repaired focused and configured runs exited 0.

Historical failures remain failures. At `fcf19d8415`, #2266's UI gate executed
336, passed 335, failed 1 and skipped 1; its unchanged focused rerun passed 1/1,
zero failed/skipped. At `b832d64e85`, #2333 had the same gate counts and a 1/1
focused pass, but the distinct read rejection above. Each old-validator control
executed 1, passed 0, failed 1 and skipped 0. Two fresh baseline native passes
before the residual repair each passed 1/1, zero failed/skipped; they did not
disprove the race. Builder aggregate-selection refusals executed zero tests and
provide no passing coverage.

The [content-free evidence index](../app/src/test/resources/verification/permission-isolation-2345/evidence.json)
retains source identities, fixture/report hashes and historical, controlled,
baseline and repaired results. Its pending gate field records the earlier builder
handoff; the accepted verifier result above supplies the later acceptance.
Raw XML, diagnostics and exact source snapshots were retained at
`$AGENTS_REPO_PATH/logs/evidence/2345/builder-20261021-linux/`, including
`repaired-native-{1,2,3}/`, `read-race-control/`, `recording-read-race-control/`
and `green-unit/`. The verifier checked all 967 manifest entries. Counted dispatcher
XML was retained in `$AGENTS_REPO_PATH/logs/verifier-gate_#2345_5.log` (UI),
`_4.log` (JVM) and `_6.log` (scripted). These are review-time raw locations;
the counts here survive pruning. No recording or production contract changed,
so no fresh Claude compatibility result is claimed. macOS was outside this
Linux ticket's verification scope.

#### Main-sweep occurrence and counted Linux evidence, 2026-10-21 (#2355)

The original main sweep at `3be8ab9163640f10da40c2de0a61682028e49d4c`
failed at lifecycle step 45: `phone/host-2/0:close` preceded two required
`thread_catch_up` requests (`matched=44 remaining=16`). The XML failure element
was empty; stderr identifies the first mismatch, which later delivery and test-end
errors rethrow. The final activity-focus snapshot does not establish causality.
The later pre-repair sweep at `0dffe4d6b723540e9ab03ae511c240975fe6074a`
retained the same early-close failure at step 46 (`matched=45 remaining=14`),
with one catch-up pending. It additionally failed
`RecordedMobileSuiteTest#interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`,
owned by [#2347 / PR #2352](https://github.com/pyrycode/pyrycode-mobile/pull/2352).

Merged repair `f10409c5c48e401911e16ec287894c96f0f14b0a` includes the #2349
cleanup implementation above and #2345's separate read-evidence repair. Registry
reconciliation disposes the phone after pairing removal inside the scenario,
before activity teardown. The scenario fence now keeps phone producers alive
through required catch-up and correlated delivery before removal. The existing
delayed-request/delayed-reply regression covers this occurrence; #2349's accepted
old-cleanup control executed one test and failed it, exit 1. This causal control
and the repaired regression supply evidence beyond an unchanged passing rerun.
The checkpoint-13 read rejection in #2345 remains a distinct first failure.

The [accepted verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2369#issuecomment-6310731943)
reviewed `97fa2ffb984dcf6a8c6c510e0382b64472ed7068` on Linux. Fresh focused
checks used `1782cb8b65c395fc88b80f2393f9c65afd524520` (merged repair plus plan);
subsequent commits added only the evidence index and verification prose.

| Attempt | Executed | Passed | Failed | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Original main sweep, `3be8ab9` | 1817 | 1816 | 1 | 2 | 1 |
| Later pre-repair main sweep, `0dffe4d` | 1817 | 1815 | 2 | 2 | 1 |
| Fresh exact-method replay, `ui-zqi07wfm` | 1 | 1 | 0 | 0 | 0 |
| Unchanged `PermissionIsolationCleanupTest` | 7 | 7 | 0 | 0 | 0 |
| Configured JVM/build gate | 6384 | 6384 | 0 | 0 | 0 |
| Configured UI gate, `ui-bpkgrei7` | 335 | 335 | 0 | 1 | 0 |
| Configured scripted-all gate | 21 | 21 | 0 | 1 | 0 |

All rows had zero errors. The fresh exact-method invocation selected
`RecordedMobileSuiteTest#interactiveTurn_permissionPrompts_heldPerConversation`
with `python3 scripts/android-test-gate.py ui --tests`, creating fresh scenario
state. It passed in 8.219 seconds and completed all 26 permission stages: A/B
isolation across navigation, the armed/confirmed phone answer resolving only A,
and the peer answer dismissing B and ending its turn. Complete payload matching,
causal delivery, full tape consumption, the recording and deadlines were unchanged.
The configured UI command was `ANDROID_GATE_WAIT_SECONDS=2700 python3
scripts/android-test-gate.py ui`; its report contains the exact method passed once
in 6.511 seconds, without failure or skip. Only `renameAtFigmaViewport` skipped.
The JVM gate passed the delayed catch-up/delivery regression; scripted-all skipped
`interactiveTurn_seededChannel_durableGapCatchUp`. These counted gates satisfy the
UI requirement left pending in the builder's index and plan.

The [content-free evidence index](../app/src/test/resources/verification/permission-isolation-2355/evidence.json)
retains revisions, report hashes, exits and counts. Raw XML/logcat, source snapshots
and original stderr were retained under
`$AGENTS_REPO_PATH/logs/evidence/2355/builder-20261021-linux/`, in `fresh-native/`,
`fresh-unit/` and `historical/`; the verifier authenticated all 266 manifest files.
Accepted verifier bundle
`c9ce0376c8e62854de2e22b639d2ed2fd9feb2d20ab128b97f774a52ce7aac98`
retains counted dispatcher XML in `logs/verifier-gate__2355_4.log`, `_5.log` and
`_6.log`. These are review-time raw locations; the counts here survive pruning.

The next post-repair **in-depth main sweep remains pending with the dispatcher**,
including counted results and any newly failing methods. Focused and configured
UI success do not certify the original 1817-test sweep's complete load/order or
retrieval acceptance. Both historical sweeps remain failed. No fresh Claude
compatibility claim is made; macOS is outside this Linux ticket's scope.

### Atomic transport close (#2219)

`RecordedTransport.close` runs on the same hub monitor as `ReplayPump.send`, not only on
the pump's own close-state lock above. Before this, `close` published `localClose` and ran
its lifecycle transition without holding that monitor, so a send already inside the monitor
could observe the transport as closed while the strict tape still required the close step
itself to be consumed first. `hasEligibleRefusal` then saw no pending close and let the send
bypass exact matching, so the tape's captured refused `request_attachment` and its
`inbound_end` were never drained; the scenario's own retry and recovery still passed, and
only the final teardown checkpoint hung for the full 30-second deadline. This produced the
historical fail-then-pass on `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`:
[#2213's Linux UI gate](https://github.com/pyrycode/pyrycode-mobile/issues/2213) failed the
method with replay progress `matched=32 remaining=3` and exactly that close, refused request
and `inbound_end` pending, then its focused rerun passed 1/1 in 6.65 seconds — evidence of the
race, not proof of its absence. An earlier fail-then-pass of the same method occurred in
[#2208](https://github.com/pyrycode/pyrycode-mobile/issues/2208). Serializing `close` on the hub monitor makes a second closer wait behind an unfinished
first close, so the tape's close step is always consumed before any send can see the
transport as closed.

`SuiteConnectionsTest#aRacingCloseCannotHideTheCapturedRefusedAttachmentRequest` forces this
overlap directly: it holds the hub monitor, starts a transport close on another thread,
confirms that thread blocks on the monitor, then closes the pump and sends the refused
request from the holder before releasing it, and asserts the tape still completes.
`aCapturedRefusalStillRejectsAChangedAttachmentAfterClose` is the companion negative case —
a changed attachment identity after close still fails strict matching and leaves the tape
incomplete. Both join the existing `aSendWaitingBehindCloseCannotUseAnEarlierOpenState`.
Against the pre-fix source (`bf1bf2ba0`) the racing-close regression failed twice, each one
executed/one failed/zero skipped, both leaving precisely the refused attachment request and
`inbound_end` unconsumed — the same shape as the historical gate failure. After the fix
(`3c4ccf7f8`), `SuiteConnectionsTest` passed 15/15 and `RecordedSuiteTapeTest` passed 23/23,
and the dispatcher's Linux UI gate executed and passed
`interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` with its original restart,
link-cut, Retry and recovered-file assertions intact. The builder additionally ran five
consecutive focused native executions of that method with no failures or skips (8.897,
8.169, 8.624, 8.274 and 7.809 seconds), all against identical source hashes. The retained
evidence index is
[`recorded-close-2219/evidence.json`](../app/src/test/resources/verification/recorded-close-2219/evidence.json);
raw XML, output, phone diagnostics and source snapshots were kept under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2219/atomic-close-20261017/` at review
time and are pruned after 30 days, so the counted results above, not that path, are the
durable record.

**Second cause: the supervisor's own close could race its cancelled loop.** After the
fix above was in the failing tree, the method stalled again on macOS in [#2173's UI
gate](https://github.com/pyrycode/pyrycode-mobile/issues/2173), on commit `00d9118fd3`:
`setHostLink(up = true)` timed out after 30 s with the repository still absent, and the
same-tree rerun passed. A temporary, uncommitted match trace on a normal device run ruled
out replay scheduling — the list, MCP and context asks matched 258 ms before
`request_history`, so the history reply never had a window to overtake an earlier ask.
The trace instead placed the cut's `attachment_request` on the main thread while the
captured `close` was consumed on the cancelled supervisor loop's own worker thread,
inside its `finally`. `RelayConnectionSupervisor.close` cancelled the loop before reading
`liveConnection`; if the loop's `finally` cleared `liveConnection` and closed its
transport between the cancel and that read, `close()` closed nothing and returned, and
the cut's thread could then send the retrieval request on a transport the loop had not
closed yet. The tape failed on that request before its captured close, the app absorbed
the failure as `Unavailable`, and the next generation's `connect` rethrew the stored
failure — the `host link reconnect timed out; repository present=false` signature. The
same run's `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes` failure
(`step 17`, a chunk reaching the tape before its close) is the same race, not a separate
cause; that scenario's fix is tracked under #2138.

The fix: `close()` now reads the live transport before cancelling the loop, and closes
that transport itself (or one an `Up` published meanwhile) before returning, so it can no
longer return with the socket still open underneath an in-app cut. See
`RelayConnectionSupervisor.close` in
`app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt`.
`RelayConnectionSupervisorTest#close_closesTheLiveTransportBeforeReturning_whenTheCancelledLoopClearsItFirst`
forces the race with a single-thread loop dispatcher that holds `close()`'s caller inside
cancellation dispatch until the loop's `finally` has cleared `liveConnection` and parked
in the transport's close; against the pre-fix source it failed with zero completed closes
(38 executed, 1 failed, 0 skipped), and passed 38/38 after the fix.
`SuiteConnectionsTest` (15 tests, including the two #2220 regressions above) and
`RecordedSuiteTapeTest` (23 tests) both stayed green — a reply-ordering fix in
`RecordedSuiteTape.drain` was drafted for this same failure, tested and then reverted once
the trace pointed at the supervisor instead, so the tape gained no new hold logic from
this ticket. `RecordedSuiteTape.progress()` does keep the `first-failure=<sticky
failure>` field it gained during that investigation, naming the first tape mismatch a
teardown failure is based on.

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

### Passive thread assertion evidence (#2317)

Collecting a cold thread flow is an application action: `observeThreadSnapshot`
sends `backfill_since` before emitting rows. An assertion through `withOwnedReply`,
`currentThread` or `awaitRetainedReply` can therefore consume a recorded request
intended for the reopened destination. Check suppressed replay mismatches before
attributing a reopen timeout to lazy composition. Unsupported-backfill normalization
still requires the exact captured unsupported probe; an unanswered reopen request
cannot be treated as one.

`InteractiveStreamE2ETest.interactiveTurn_peerStartedTurn_continuesOnPhone`, also
run by `RecordedMobileSuiteTest`, opts into `PassiveThreadProbe` before creating
its destination. The destination's `TappingConversationRepository` forwards its
actual snapshots unchanged and taps that existing collection without adding
upstream collections or requests. Other reply-helper consumers retain their
existing contracts. Producer completion alone cannot establish phone delivery.

Passive evidence binds host, conversation, captured connection and subscription
identity. Reopen requires a different active subscription, even when rows are
identical. Ending a subscription clears its snapshot; observing a replaced owner
permanently retires it, so returning to the old connection identity cannot revive
the evidence. Closing the observation scope clears readings and detaches the probe.
Keep these lifetime checks alongside the no-extra-collection/request regression
in `PassiveThreadObservationTest`.

After reopen, require exactly one delivered peer user message with its original
identity and complete content in current admitted data. Reveal and count that row
while composed, before scrolling to each retained assistant row. Retain the same
completed reply's admitted session/turn, row identities and complete observed
content, then revalidate the current passive evidence. Lazy semantics establish
display only while a row is composed; scrolling to the reply must not invalidate
the earlier user display count. See [thread testing](knowledge/features/thread-screen-testing.md#passive-reopen-evidence-2317).

**Counted Linux evidence, 2026-10-19.** The
[accepted verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2319#issuecomment-6275645803)
on `a320834c07f039cc40d98869f05fd0ca04f447ed` records:

| Configured gate | Executed | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `python3 scripts/jvm-test-gate.py --with-build-checks` | 6155 | 6155 | 0 | 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` | 335 | 335 | 0 | 1 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all` | 22 | 22 | 0 | 0 |

The retained full UI report contains
`RecordedMobileSuiteTest#interactiveTurn_peerStartedTurn_continuesOnPhone` as
**1 executed, 1 passed, 0 failed, 0 skipped**; the unrelated skip is
`RenameDialogCaptureTest.renameAtFigmaViewport`. The JVM gate passed all seven
passive-observer regressions, including source replacement/reuse, reopen, failure
cleanup, scope cleanup and exact user identity/content/duplicate rejection, plus
`OwnedReplyObservationTest.retainedReplyRebindsOnlyTheSameCompleteFamilyOnTheCurrentOwner`.
All gates had zero errors. The accepted verifier bundle is run
`bc00cb670de64f6e5dff8a38d9d85253254c03ecbf3fa559636b8c7c2c559902`;
its gate-5 and gate-6 logs retain counted XML after the gate worktree is removed.

Separate builder focused runs at `69be40bebe9278c5779367febf608b5854cf3b87`
and `4a044b47a799a59f874a22f584a4974cdbd34ba1` each executed and passed the
named recorded method once, with zero failures/errors/skips. Focused JVM/shared
classes passed 7 passive-observer, 26 owned-reply, 3 decorator and 12 rendering
cases: **48 executed/passed, 0 failed/errors/skipped**. These focused passes alone
do not establish suite-order independence; the table records the later complete
configured gates separately.

Historical failures remain failures: the baseline on product source `d20e92cd4b5b`
(plan-only revision `1b368c9f8`) and cold-read experiment `2f36172fa47944defb6344127d47f784aad26705`
each executed one case, passed zero, failed one and skipped zero. The controlled
source-retirement regression initially executed five, passed four and failed one,
with zero skips. An intermediate instrumented compilation executed zero tests.
Raw XML/logs and source metadata were retained under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2317/`, in
`builder-20261019-peer-turn/` and `builder-20261019-passive-repair/` at review time.
The [checked investigation summary](../app/src/androidTest/assets/peer-reopen-2317/investigation.txt)
preserves the diagnosis and fixture hash. The repaired runs complete strict replay
with the original recording and show the user already composed before reveal;
they do not establish offscreen content loss as the original cause. No fresh
Claude compatibility capture was required. Other operating systems were outside
this Linux ticket's verification scope.

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
