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

For a focused standalone migration, `recorded-exchanges.py managed-refresh --pyry
<isolated-binary> --output <new-private-directory>` owns the managed device and test
admission. It retains the real capture, both replay results, their native XML and private
logs, and the unchanged strict comparison. A changed contract or failed replay returns
nonzero and requires an explicit semantic review before any fixture replacement.

Managed recording commands acquire host test admission before taking device custody
or building/installing APKs. An inherited admission descriptor must validate against
the original lock and retain its exclusive hold. Pass that same descriptor through
nested harness processes and owned cleanup; a foreign or invalid descriptor fails
rather than creating replacement admission. Scope selection precedes delegation.
This ordering avoids holding a device while waiting for a host lock held by another
worker waiting for that device.

Retain per-method XML, instrumentation output and private phone logs before another
method can replace them. Cleanup runs all release actions while preserving the original
scenario failure as primary. A failed phone-log collection cannot replace that native
result or print private exception text. Failed captures remain diagnostic artifacts.

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

### Unavailable-summary recording integration (#2467)

The [bounded metadata recovery](knowledge/features/remote-conversation-repository-reads-and-thread-store.md#recovering-unavailable-shown-metadata-2493)
retains five genuine replacement assets from
`8534e6ca2babec13ba8ac0981565590fb946dd19`. Their producer was Mobile
`0b93815f1088c28c513aad4f4d83899c3520fea2`, Core
`2c68b595440dd7e8e843af9cff0d9a708bcc81a7`, daemon SHA-256
`d93c56662d0072e8aec9d1466bee3f6845f7cdc55247273f177b48b209bdb6ac`.
All five assets retain their genuine capture bytes, full request payloads,
connection ownership, original scenario assertions and deadlines.

| `RecordedMobileSuiteTest` method | Historical comparison | Reviewed contract |
| --- | --- | --- |
| `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` | IDENTICAL | Terminal summary supplies shown/read 11/0; no follow-up needed. |
| `interactiveTurn_markdownReply_rendersFormattedBody` | CHANGED | Additional empty-payload summary and checkpoint-16 catch-up; push registration before phone-send; correlated follow-up supplies shown/read 13/0. |
| `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies` | CHANGED | Additional checkpoint-14 catch-up and opposite epoch-alias allocation; exact commands and ownership remain. |
| `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` | CHANGED | Additional checkpoint-15/24 catch-ups; list counts and host-2/other-conversation ownership remain exact. |
| `negativeControl_wordClaudeNeverSays_isNeverDisplayed` | CHANGED | Additional checkpoint-14 catch-up; original absence wait remains. |

These are one IDENTICAL and four reviewed CHANGED comparisons, not five passing
compatibility verifications. The five `contractReview.report` references in
`suite.json` pin the retained `8534e6ca2babec13ba8ac0981565590fb946dd19`
report, SHA-256 `6e5aba904081b6035dd074b567d7a80aa78f9352bdf4006860ed7dc0f9c9d343`;
the capture bytes and comparison dispositions are unchanged. Historical genuine capture and fresh strict replay
executed/passed five methods each; the separate negative-control verification still
exited 1 because old replay failed and comparison was CHANGED. Earlier permission
compatibility and five-method integration failures remain failed. Final historical
Linux replay on `cfcb4083fb1425576ed18265309fd88f0fb7d2a1` passed all five;
that result does not certify a later macOS integration or the separate #2494 proof.
The recovered #2467 builder bundle preserves its 199-entry manifest and pointers
to original-machine files omitted by publication limits. Historical #2462 captures
with shortened logs/protobuf differences do not establish original byte completeness,
post-summary numeric facts or a newly proven timeout cause.

**Manual #2493 continuation, macOS.** Candidate
`f5a29a43d9ef3b75f2b443a030a9def052aa711c` passed 40/40 final activation
cases. Eight earlier focused selections at `fed72db87072dfd254fd83c2bebc309dfb824a24`
passed 141/141, zero failures/errors/skips. Its six-method native selection finished
after the builder timeout: 6 executed, 4 passed, 2 failed, zero errors/skips. The
restart method failed at an absent scroll node; model-change failed at terminal
close before test-end. The negative-control, permission, markdown and offline-retry
methods passed. Preserve these failed results, the causal/fixture diagnostics and
source identities. Current-main integration includes the later terminal lifecycle
repair; fresh configured acceptance was still pending at that stage. Complete gates
are dispatcher-owned, including actual execution of all six
required replay methods. Paired final-read native acceptance belongs to #2494.

The retained XML is in agents `logs/evidence/2493/builder-macos-20261023/`,
including `native-final-f5a29a43d/ui-y1h41xli/` and `jvm-final-f5a29a43d/`.
The post-timeout native directory contains XML only: effective APK identities,
phone/replay logs, exit status and cleanup receipts are not established by those
reports. Fresh configured acceptance must retain those receipts.

The later configured macOS run at `52e60852785d8fdb3c7003843bb532a10fc2011f`
reported 5439/5439 JVM passes and 24/24 scripted passes. Its original full UI run
executed 333: 332 passed and one inherited-effort test failed, with the unrelated
RenameDialog capture additionally skipped. That failure passed the exact 1/1
rerun under dispatcher policy; all six #2493 replay methods passed in the original
full run. Independent review authenticated those counts and retained phone logs,
but did not independently authenticate installed APK hashes and complete cleanup
receipts. Source review still returned FAIL for queued recovery authority surviving
withdrawal; passing replay is not proof of that asynchronous ordering. The subsequent
focused
repair and its buffered-order probe required new independent review. Preserve both
the accepted gate disposition and the source finding, rather than treating the
old gates as acceptance of later source changes.

The queued-authority repair at `51738b973b036c29ae9c9f302feb56f2c7286013`
has focused macOS evidence: activation class 41/41, recorded model-change 1/1 and
scripted ping 2/2, all with zero failures/errors/skips and command exit 0. The new
buffered-order regression failed once before repair, then passed within the 41-case
class, including fresh-observation recovery. Native and scripted phases finished;
raw XML, phone logs, source binding, built APK hashes and isolated harness records
are retained under agents `logs/evidence/2493/manual-rework-20261023/`.
The effective test BuildConfig identifies `51738b973`. The focused replay records
`terminal_preparation_finished early_checkpoint=true`. Scripted ping retained its
test daemon/relay logs and source/binary metadata; no real provider turn ran.
These are scoped author checks, not a new independent PASS or the full #2494 proof.
Main subsequently merged cleanly as `a456e5b8e36670a3b17e9e405f11b999f785d3ee`;
its affected activation class passed 41/41 and lint passed. The preceding native
and scripted results retain source `51738b973`, rather than certifying the later
integration. Fresh complete configured acceptance and independent re-review were
still required at that stage; the accepted integrated result follows below. Earlier
causal
bundles and failed attempts remain separately identified; no new provider capture,
Linux execution or IDENTICAL comparison is claimed here.

### Accepted macOS integration (#2493)

The stored independent verifier PASS for [PR #2499](https://github.com/pyrycode/pyrycode-mobile/pull/2499)
reviews `07b3eaadbc8b20ca6a3fbe64987454a5fcbc9cee`, with main
`4a5459864db9b413f854a9120507d7e95b02b990` integrated. This is later evidence
than the earlier failed reviews and scoped author checks above. The accepted run
is `001ffb009e86ea433901162030f582a3cee9af92dfda60ecd6be84001ddf5c69`,
verifier bundle SHA-256 `4cfd1edbf960f569d6863814d26c3b8f55fed74bcd003e70ffe53f639dcc9bce`.
Linux is outside this ticket's verification scope.

| Configured run on macOS | Executed | Passed | Failed | Errors / skipped | Disposition |
| --- | ---: | ---: | ---: | --- | --- |
| JVM with build checks | 5522 | 5522 | 0 | 0 / 0 | Exit 0; activation 41/41, read receipts 20/20, host attention 11/11, tape 49/49, Agent screen 30/30. |
| Original UI | 250 | 248 | 2 | 0 / 0 | Exit 1; both unrelated failures passed exact same-tree rerun. |
| Exact UI rerun | 2 | 2 | 0 | 0 / 0 | Exit 0; accepted under dispatcher policy. |
| Original scripted-all | 24 | 19 | 5 | 0 / 0 | Exit 1; all five failures reproduced on main and accepted as inherited. |
| Exact scripted rerun | 5 | 0 | 5 | 0 / 0 | Exit 1; failures remain failures. |

All six required `RecordedMobileSuiteTest` methods executed/passed in the
**original full UI run**, with no separate focused verifier invocation implied:

- `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`
- `negativeControl_wordClaudeNeverSays_isNeverDisplayed`
- `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`
- `interactiveTurn_markdownReply_rendersFormattedBody`
- `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`
- `interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`

`interactiveTurn_backgroundAgent_followsBottomUntilFinished` also passed there;
its [current-family navigation repair](knowledge/features/thread-screen-agent-navigation-evidence.md#current-family-navigation-after-readiness-2493)
retains the original proof assertions and budgets. The two UI rerun passes were
`interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk` (tracked on
\#2360) and `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`
(first sighting recorded by the dispatcher).

The exact main comparisons at `4a5459864db9` reproduced the five scripted failures:
`interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog` (#2353),
`interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`,
`interactiveTurn_seededChannel_backgroundAgentMovesAndSettles`,
`interactiveTurn_seededChannel_durableGapCatchUp` (all #2521), and
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` (#2469).
The verifier accepted these inherited dispositions; none is a product pass or a
new diagnosis of the historical #2462 failures.

Test BuildConfig identifies `07b3eaadb`; app BuildConfig reports `dev`. Built
app/test APK SHA-256 values are
`e4567ee310eb6542e0767a65cbb51c409b65463d28a39a570fdbfd36c1283acb` /
`73f920ade7434f4e25211965bb63a93571a2e59c452534dd4fc2fc6353e19bb1`.
All 23 original scripted harness bundles authenticated 92/92 manifest entries.
Their inputs bind Mobile `07b3eaad…` and clean supporting Core
`cb556b7697fa6f10685a630fb6a73139ed82cf9c`, containing Core #3711. The outer
daemon SHA-256 is `8c4ef6d6096e6be45009adcf7a16ed6c62f80bbf9f9f04a882a5d622770490ca`;
the separate session-error daemon context records
`a005e3273cb7f172110806e4b46e4f807acd4c9c6bb208bafa9d0d8896d1a5b6`.
Both embedded revisions are unavailable; source-input provenance is separate.
The session-error harness builds its dedicated recovery daemon from `PYRYCODE_SRC`;
pinning only the outer `PYRY_BIN` cannot establish that nested runtime. Authenticate
both binary contexts and their actual source inputs. The earlier scoped author
pass using Core #3711 does not explain older verifier failures or turn these
current inherited failures into passes.
The current relay file SHA-256 is
`17e24131cfd3f5a0bc48f972021db17925347311e3dcdf362f2aa8cfe48fc710`.

At documentation time, counted XML, phone logs and command logs were retained in
`/tmp/verifier-2499/review-07b3eaad/`, including `jvm-z3aiz6a0/`, `ui-4s1b5uwq/`,
`ui-zvo8_p7o/`, `scripted-all-vbauwve9/` and `scripted-all-6ysg9280/`.
Persistent agents `logs/evidence/2493/harness/` holds the original harness traces;
the verifier bundle retains gate logs, teardown/retention records and pointers to
host-only files. These are historical retention locations, not surviving gate
worktree build paths. The counted facts above survive raw-file pruning.

Built hashes are not installed-byte readback. The relay hash identifies a file,
not an independently authenticated executed source revision. Gate completion and
harness teardown were checked; no additional independent per-process cleanup audit
was performed. Genuine captures and one IDENTICAL/four reviewed CHANGED comparisons
remain unchanged. No new provider capture or current-provider acceptance is claimed;
the paired final-read native proof remains assigned to #2494.

### Captured read snapshot prerequisites (#2467)

`SuiteReadReceipts` retains intermediate captured `conversation_updated` snapshots
when qualified phone reads coalesce. Select the earliest covering captured mark
from the current or a preceding action on the same connection/conversation, using
the phone's requested mark rather than a peer's later confirmed progress. Only
`read_up_to` is normalized; shown metadata comes from that captured snapshot.
Otherwise a future snapshot can precede its causal query, or the intermediate
acknowledgement that originally caused a query can disappear.

`RecordedSuiteTape` holds each acknowledgement until its accepted same-owner list
requests and same-conversation thread requests have strictly matched. Deliver ready
acknowledgements before the same prerequisite releases owner termination. Another
connection/conversation cannot supply a prerequisite. Genuine peer notifications
retain their own guards and cannot be discarded merely because their payload
matches a receipt. Mandatory reads, complete consumption, payload/correlation
checks and duplicate rejection remain strict. The retained `SuiteReadReceiptsTest`
probes cover query prerequisites, intermediate snapshots, foreign ownership,
peer-progress isolation, guarded notifications and acknowledgement-before-close.
On accepted macOS `07b3eaadb`, the full JVM run passed all 20 receipt and 49 tape
cases, including `coalescedReadCannotExposeItsSnapshotBeforeTheCapturedQuery`,
`anIntermediateCapturedReadMustNotWaitForTheQueryItCauses`,
`readyReadAcknowledgementsPrecedeTheirOwnersInboundEnd`,
`peerReadProgressCannotChooseAFutureSnapshotForAnEarlierPhoneRead` and
`peerProgressReleasesTheCapturedPhoneNotificationWithoutALocalRead`. See the
[counted integration evidence](#accepted-macos-integration-2493).

## What replay proves

Every outgoing request must match its complete recorded type and payload. Generated
identities are rebound, and replies follow the actual request identifiers. Commands
retain their action order. Only named read-only queries may reorder within one action.
Daemon frames keep their recorded order and wait for their causal requests. Missing,
extra, duplicate or changed requests fail, including requests after the last UI assertion.

Test-driven close also waits for application consumption: after required requests and
scheduled delivery drain, `SuiteConnections.beforeClose` snapshots the selected pump's
queued inbound prefix and waits for each emission to return successfully from its
existing downstream collector. Queuing a terminal reply does not prove the repository
has certified its resumable checkpoint. This wait shares the existing 30-second close
timeout. Cancelled or failed emissions cannot acknowledge consumption; another owner,
peer or replacement pump cannot satisfy that prefix. The fence supplies no reply or
checkpoint and preserves full-payload matching, causal delivery, connection ownership,
observation boundaries and complete tape consumption.

The standalone permission fixture and the full recorded Mobile suite use the actual
production capability selection and negotiated thread route. The standalone screen
receives that route through the production stable repository wrapper. The retired
legacy fixture is preserved under `recorded/retired/` and is not current acceptance.
The genuine replacement and its CHANGED contract review are recorded in
`docs/specs/architecture/2367-permission-recording-review.md`. Changed production
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

Parent attribution remains part of that cross-epoch match. Omitted and explicit-zero
parents both identify the main-thread root; compare nullable `Long` parents with
`(left ?: 0L) == (right ?: 0L)`. Untyped `0` fallbacks can produce incompatible boxed
numeric representations and reject an otherwise identical root row. Nonzero owned
parents must still match exactly: neither a root nor a different parent can replace
them. `DurableGapDiagnosticsTest.omittedAndExplicitRootParentsMatchWithoutErasingOwnedParents`
guards this boundary (#2344). Terminal-turn certification and retained-baseline identity
are separate predicates; finding the exact terminal does not prove baseline retention.
The controlled regression passed after repair; the repaired native captures reused the
epoch, so they do not establish native epoch-replacement coverage.

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

Canonical live progress may cross its own pending catch-up only when an actually
delivered, fully decoded earlier query certifies that exact connection lifecycle,
conversation, epoch and requested checkpoint. Re-drain eligibility after actual daemon
delivery; waiting for another phone request can deadlock progress. Partial or unconfirmed
replies, foreign bases, resets and initial queries cannot supply this certificate.
The pending catch-up and its correlated reply remain required.

Independently attributed live B frames may pass an unrelated A query fence only after
B has an accepted earlier thread query on that same connection lifecycle. Explicit
request, envelope and catalog-row conversation identities must agree. Unknown or
contradictory attribution, earlier same-conversation work, Android input, checkpoints,
read/observation boundaries and physical close still fence release. Recognized metadata
replies can be crossed without releasing their own fence. No sibling query is omitted.

Automatic peer `allow_once` approval in replay waits for the captured action boundary
of that exact peer owner and mapped modal. Readiness is non-consuming: a checkpoint
alone cannot replace the required phone command, and another modal or owner cannot
borrow its approval. Polling can observe independent target facts while the answer
waits. An asynchronous pre-Send approval must leave the actual phone send able to run.
Complete payload, answer-token binding and exactly-once consumption remain mandatory.
Live capture does not defer approvals this way.

For graph/cache restart, freeze the complete family actually persisted after session
rotation. An inactive Agent can still gain final `task_report`/`task_update` fields;
preserve all earlier root key/value pairs and exact complete child content before
freezing, with nondecreasing revisions and unchanged identity/session/turn/parent/order,
`endedOrder`, activity and finished state. Compare the frozen complete content exactly
through restoration and reconnect. Preserve saved checkpoint/coverage separately from
newer held facts; Unverified pre-launch data cannot establish negotiated support.
Check finished placement after a phone message between Agent launch and finish, then
before the recovered new-session message, in both production projection and actual
reversed UI list keys. Launch order and identical text alone miss lost lifecycle order.
See [restart evidence](e2e-interactive-stream.md#negotiated-agent-restart-and-settlement-2246).

Replay proves the recorded permission behaviour. Current-daemon compatibility requires
the explicit live command. Timing, encrypted transport, reconnect, other permissions,
session grants and other live scenarios retain their existing tests and requirements.
Android startup and UI costs remain, so report measured Android times rather than using
Desktop's replay timing as an Android forecast.

### Authoritative runtime and permission replacement (#2367)

The standalone permission screen uses its genuine paired host/device identity,
production capabilities and the coordinator's negotiated route through the production
stable wrapper. Replay binds the captured device through the engine's existing
identity mapping; full payload and causal matching stay strict. Both catch-ups must
complete with correlated certificates in the actual conversation/epoch. No raw
message, assistant delta or history page supplies phone content.

The genuine capture on Mobile `1a22302d728d89a6f9deb2dc25168496490cd05a` and daemon
`fbd5f2c9cd99c2c5864f4772c4e85f74d7a8dbfe` executed/passed
`RecordedPermissionTest.capturedPermissionReachesTheClientAndCompletes` once;
fresh replay also executed/passed it once. Both had 0 failed/skipped. The retired
fixture replay executed once and failed once, with 0 skipped. The refresh command
remains nonzero and strict comparison remains **CHANGED**. The
[semantic review](specs/architecture/2367-permission-recording-review.md) accepts
the genuine replacement, not compatibility success.

The current fixture SHA256 is
`1d0268dd52528e76c9520eb3ef7c84b0965e39ba3d5ace251754bbf0e702a58c`.
The unchanged retired asset `recorded/retired/permission-allow-legacy.json` has SHA256
`71fff5a9926e594b9568b615480e8c797276eee3956f87829aab0086631c49c1`.
The 76-frame genuine exchange contains 11 outgoing requests, two correlated thread
catch-ups, no retired history/backfill request and five settled canonical items.
The sent user identity matches the real phone device/message; the exact completed
assistant reply shares session/turn with its successful terminal. The genuine
permission identity matches the phone answer and remote dismissal.

Native checks retain visible prompt, separate arm/confirm taps, physical pointer
safety, absent/present file witness, completed visible reply, non-streaming/idle
state and complete replay. The file-save capture separately uses the actual loaded
attachment row, one production picker, original confirmation geometry and exact
saved bytes. The raw-history-only `historyTailFramesAt412By892` design capture is
retired; shared loading/retry/offline/unavailable rows retain presentation coverage.

The accepted macOS full gates on Mobile `440c460adf60e9f9a12266b6539d857d6670b873`
executed/passed 5341 JVM methods and 24 scripted methods, with 0 failed/skipped in
those runs. Native UI executed 327, passed 325, failed 2 and skipped 1; its exact
rerun executed 2, passed 1, failed 1 and skipped 0. The restart repeat passed;
model-selection failure was accepted as inherited (#2351). Permission, loaded-file
save and recorded offline/reconnect each executed/passed once in the original full
UI invocation. See [named methods, sources and raw report locations](e2e-interactive-stream.md#authoritative-runtime-removal-2367).
These are replay/scripted results, not fresh real-provider observations.

The offline/reconnect fixture remains unchanged (SHA256
`15dfdcc48840707cf915a9189666f944388aae3ae2563ea5a58f8a42b1852800`). Its
[accepted review](specs/architecture/2245-recording-reviews/04-offline.md) retains
one genuine capture and one fresh replay passed without skips on Mobile
`a671976f427fffebd8aaf88e1a60aecd63adadf0`, daemon
`e596971abb101d44d770dea33b4d29d61300e85e`. Its explicit CHANGED review remains.
The authoritative recovery/rendered-order observations did not change in #2367.
Independent peers' diagnostic raw history and attachment watermarks never populate
or certify phone content. Keep prior rejected, failed, skipped and cancelled runs
at their original source revisions.

### Attention destination acquisition (#2350)

The Waiting pill can appear before destination metadata makes B's initial attention
query eligible. Waiting for its certificate before tapping the pill can therefore
prevent the very query being awaited. Acquiring while that query is active instead
coalesces with it; acquiring after completion sends the second captured query. A
missing second query correctly holds the phone's dismissal/completion frames even
when the independent peer has already observed dismissal. Peer progress alone does
not establish a product completion or expiry defect.

`InteractiveStreamE2ETest.interactiveTurn_otherConversationAttentionPills_waitingAndFinished`,
also called by `RecordedMobileSuiteTest`, arms the test-only `ThreadAcquisitionGate`
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

Finished publication must already own its registered five-second expiry (#2490).
`observeThreadAttention` starts the expiry child undispatched through its first
delay before publishing the pill. Seeing Finished alone previously proved neither
timer registration nor a new turn: certification changes can republish the same
terminal fact. Within the RESUMED destination's collection, eligible completions
are deduplicated by host, conversation and completion key. A repeated identity
cannot renew or resurrect Finished; only a new terminal identity restarts its
five-second lifetime. Identity admission precedes Waiting suppression, so a
discarded completion cannot replay when Waiting clears. Collection cancellation
owns the subscription and timer; reopening starts with current Waiting and no
old Finished. This expiry repair preserves the separate acquisition fence above,
the recording, strict query matching, tape consumption and existing deadlines.

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

#### Counted macOS expiry evidence (#2490), 2026-10-24

The controlled red on unchanged production executed 18 cases: 15 passed, 3 failed,
zero errors/skips. It directly rejected publication before registration, duplicate
renewal and replay after Waiting suppression. The repaired `ThreadAttentionTest`
passed all 18, including `finishedPublicationAlreadyOwnsItsScheduledExpiry`
(present at 4,999 ms, actually absent at 5,000 ms),
`recertifyingSameCompletionCannotRenewOrResurrectFinished` and
`recertifyingCompletionSuppressedByWaitingCannotReplayIt`. The new-completion
renewal and cross-host/conversation key-collision controls also passed. A pill
that never expires still fails the controlled check.

Three consecutive separate invocations of the exact recorded method named above
used `python3 scripts/android-test-gate.py ui --tests <qualified method>`:

| Attempt | Source | Executed | Passed | Failed | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| `native-attention-1` | Resolved merge before commit, retained patch | 1 | 1 | 0 | 0 |
| `native-attention-2` | `f2921821cad5a6487f7a2eb43ef118aad1c4adf8` | 1 | 1 | 0 | 0 |
| `native-attention-3` | `f2921821cad5a6487f7a2eb43ef118aad1c4adf8` | 1 | 1 | 0 | 0 |
| Configured UI gate, reviewed `064c7b76b` | Integrated candidate | 250 | 250 | 0 | 0 |

All focused attempts exited 0 with zero errors; relevant executable inputs match
across the three attempts. The configured UI gate executed and passed this method
and the Model method. Combined JVM/build checks executed/passed 5,570, zero
failed/skipped. Focused presentation, navigation, acquisition and readiness checks
passed 10/10, 4/4, 18/18 and 12/12 respectively, zero failures/errors/skips.
The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2496#issuecomment-6387647718)
retains the scripted gate's initial 24 executed, 19 passed, 5 failed, zero skipped:
four failures reproduced on main (#2521, #2469); the session-error case passed
its dispatcher rerun and remains tracked by #2353. Those initial failures remain
failed evidence.

The [original evidence index](../app/src/test/resources/verification/attention-expiry-2490/evidence.json)
retains causal ordering: expiry, repeated completion during the clock advance,
duplicate suppression, then actual absence. The
[integrated validation index](../app/src/test/resources/verification/attention-expiry-2490/resume.json)
records every focused attempt, counts, source identities and manifests. Raw XML,
phone logs and source patches were retained at
`$AGENTS_REPO_PATH/logs/evidence/2490/`, with integrated checks in
`resume-20261024/`. These are retention-time paths, subject to pruning.

Merge `f2921821c` separately integrates the accepted shared-current-query
acquisition repair from #2505/#2356. Model scenario, helpers, controls and fixture
match the accepted #2356 source; current focused and configured Model passes
support that integration. They do not establish the exact earlier branch/main
interleaving: the original three-method investigation request executed zero checks.
Likewise, the historical #2462 final-absence stack remains retained, but its phone
log and host-load samples were unavailable. Controlled failures and fresh native
diagnostics establish the expiry mechanisms without reconstructing that historical
scheduler sequence. No live Claude turn or recording refresh was required; Linux
was outside this macOS ticket's verification scope.

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

### Inherited-effort settings observation (#2507)

Successful settings polling and current product state are separate boundaries. A complete
correlated reply can pass `SettingsReadProbe`'s synchronous admission check, then a later
captured minimal `session_settings` notification can replace the repository's applied
effort with `Unavailable`. The following complete snapshot updates the replay polling
cache without emitting to the app. If the sheet's poll ran before that minimal notification,
its selection and note can remain unavailable even though the independent read succeeded.
See [native test reads](knowledge/features/remote-conversation-repository-session-settings-read.md#native-test-reads-2305).

Only `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` uses
`RecordedSuiteNative.beforeSettingsObservation` before its final `freshSettings` in replay.
`SuiteConnections.beforeSettingsObservation` waits for three boundaries: the current
action's captured settings have been released by their actual causal requests, scheduled
delivery has drained, and the exact current phone pump has consumed the delivered prefix
downstream. `RecordedSuiteTape.settingsObservationReady` checks the frontier before the
next command without releasing it. An empty queue can still have settings held behind a
query; a routed frame can still await downstream consumption. Progress notifications
resume the frontier check, and closure or replacement fails rather than borrowing another
owner's progress. The fence is bounded by 30 seconds, reads no expected settings contents
and issues no request or reply. Genuine captures and ordinary live execution are unchanged.

The subsequent read retains current-owner correlation. The scenario still clears remembered
effort, uses no saved override, completes an owned phone turn and checks the exact applied
effort label/note and reported permission mode. Missing applied effort remains a failure.
Strict request payload matching, complete tape consumption and the original 30-second
sheet deadline remain required; extra polls or expected-value filtering cannot replace
the observation boundary.

`InheritedEffortReplayTest` drives the real replay hub, authoritative repository and read
probe. It demonstrates the independent applied reading being replaced, holds downstream
consumption, holds settings behind their causal query, repeats observation, rejects a closed
owner and keeps a final omitted applied field unavailable. The original four-control JVM
run executed four and failed four, with zero skips. The later causal-query control executed
once and failed once against the initial fence, with zero skips: delivery alone was insufficient.

The [verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2525#issuecomment-6380015470)
accepted `3a625ea826f00a6b773d54f7a6b824939866f8bc` on macOS, with these counted results:

| Check | Source | Executed | Passed | Failed | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| Final five JVM controls | `e30328514` plus plan-only update | 5 | 5 | 0 | 0 |
| Focused native five controls and exact recorded method | `8de1d65fe` | 6 | 6 | 0 | 0 |
| Affected `SuiteConnectionsTest` | `8de1d65fe` | 29 | 29 | 0 | 0 |
| Configured JVM gate | reviewed candidate | 5547 | 5547 | 0 | 0 |
| Configured UI gate | reviewed candidate | 250 | 247 | 3 | 0 |
| Configured scripted-all gate | reviewed candidate | 24 | 21 | 3 | 0 |

The exact inherited-effort recorded method executed once and passed in both the focused
native run and the configured UI gate. All five controls passed in the configured JVM gate.
The UI gate's three unrelated failures passed a three-test rerun; the three scripted failures
also failed on main and were accepted under #2353 and #2521. Those original failures remain
failures; the verifier accepted their triage. Later test-source changes from the focused
native revision to the reviewed candidate were whitespace only. Linux is outside this
ticket's verification scope. These offline checks establish no fresh provider compatibility.

Raw failed/repaired evidence was retained under
`$AGENTS_REPO_PATH/logs/evidence/2507/builder-macos-20261023/` and
`manual-continuation-20261024/`; the latter's `evidence/native/ui-lfkh7xqn/` holds focused
XML and phone logs. These are retention locations at review time. The unchanged genuine
fixture hashes to `d0e4202a296a688f5e94cddb0f0b39f430ff06c956d44272aa8239f0622cc69c`.
The original #2493 macOS failure at `52e6085278` retained stderr showing Effort present,
selection/note mismatched, no pending marker and a 30000 ms timeout. It had 333 executed,
332 passed and one failed, plus one skipped test. Its retained bundle lacked the original
phone log. The listed preceding scenarios were model change, reconnect footer, interrupted
upload and send queued now; the controlled reproduction requires neither that sequence
nor machine load. The exact historical interleaving and any load contribution remain inferred.

### Shared current-query acquisition

The same test-only acquisition gate also protects reopening X in the model-isolation
scenario. A positive saved checkpoint alone is insufficient: X can still have a newer
catch-up query in flight. Acquiring at that point can coalesce the destination's request
into the active query and leave its separately captured request unmatched.

`ThreadQueryReadiness` observes the existing phone pump. It records actual accepted
history requests and decodes their actual replies with the production query decoder.
A partial batch is not completion. A terminal qualifies only after its downstream
repository collector returns successfully. A queued frame, refused send, malformed
reply, cancelled collector or another conversation cannot qualify. The observer issues
no request and supplies no reply.

The gate requires that receipt on the current host connection as well as the existing
same-generation store certificate. Connection retirement or scope closure cancels the
acquisition. Destination metadata may still run before the query completes. Only the
armed host and conversation wait; all other acquisition keeps its existing behaviour.
The model scenario uses `openChatRowAfterCurrentQuery` before its original fresh X
settings and final Y model assertions. Their payloads, wording and deadlines remain.

The controlled regression seeds an older valid checkpoint, holds a newer query, then
attempts acquisition. The original snapshot-only wait acquires early and fails. The
shared wait requires the newer terminal to be consumed before acquisition can issue
its own real query. Retired-owner and incomplete-consumption checks remain separate.

Acquisition also returns the completion receipt captured before the destination
opens. Capture that receipt synchronously after the temporary observers have joined.
If their cleanup starts another query, wait again within the original timeout.
Recheck the current source, owner, generation and certified snapshot before capture.
The model scenario waits for a different successfully consumed request for
that same conversation on the same current physical source. Its old receipt cannot
satisfy the wait while the new request is queued behind another conversation. Merely
dispatching the new request is insufficient. A replacement connection cannot finish
the retired destination's wait. One monotonic budget starts when the gate is armed,
before row lookup and navigation. Those stages, acquisition and the distinct downstream
completion all consume the same remaining 30 seconds. Navigation cannot restart the
budget; an expired acquisition closes its lease without publishing a receipt. Delayed
navigation and acquisition controls reject a completion at 40 seconds and accept one
at 29 seconds. A fixture must explicitly close its readiness registration on success
and assertion failure; closing its gate or lease alone does not retire the source.

Closing the phone wrapper or completing or cancelling its inbound collector clears
the current registered source. The holder remains available to waiting readers.
Registration and retirement share an identity check so a late old close cannot clear
its replacement or retain the closed connection graph.

The reconnect-footer consumer also uses this fence at its single reopen (#2356).
A context-usage assertion can finish while terminal preparation still lacks the
reopened destination's distinct request. The retained failure timed out in
`SuiteConnections.control` through `LiveSessionCleanupRule.endWhileActivityAlive`,
after the scenario body returned; it did not establish a context-reading timeout.
The old provider path's replacement-source control fails when acquisition happens
during an unconsumed terminal. The shared fence covers acquisition both during and
after consumption while preserving strict requests, source retirement and the
original budget. A passing unfenced native baseline alone is not causal evidence.

IME diagnostics must cover the potentially blocking initial settings/service reads
and the first Compose synchronization boundary, through restoration. Starting the
observer after those reads or cancelling it at selection return misses that window.
The #2356 observer is passive: after five seconds in one phase it emits at most four
samples, each bounded to 24 frames per instrumentation/main thread, and performs no
extra settings, service or shell reads. Final cleanup cancels it even if restoration
fails. A sampled stack in a body that subsequently passes proves neither deadlock
nor a historical IME, renderer or lifecycle cause. See the
[accepted reconciliation](#model-reopen-reconciliation-2356) for its counted limits.

### Recorded terminal preparation (#2491)

A successful scenario return does not imply that its Activity can close. Under
`test-observations-v1`, `test-end` and `scenario-complete` are distinct captured
observations, and required phone queries/replies may remain between them. Keep
request producers and downstream consumers alive until the captured terminal work
has completed. Queue drainage alone cannot establish application consumption.

The recorded Activity callback first runs existing foreground preparation, then
`LiveSessionCleanupRule.endWhileActivityAlive`, then
`SuiteConnections.completeBeforePhoneTeardown`. End is attempted once per evaluation;
ordinary outer cleanup still owns failures and releases replacement Activities.
The tape decides whether scenario-complete precedes physical phone close. If it does,
prepare the required prefix, emit the actual checkpoint while the Activity is alive,
and await downstream consumption. Native finish records the successful early checkpoint
so it is not sent twice. If close precedes scenario-complete, retain that captured
order and leave checkpoint emission to finish. Capture and older-policy paths are unchanged.

All preparation phases share one existing 30-second timeout. Individually bounded
waits must not restart that budget after an observation. Required repeated requests,
owner/generation, payload, correlation, causal order and complete tape consumption
remain strict; later work cannot move across an observation to permit teardown.
Missing work and early close still fail. Preserve the first body/preparation failure
through Activity destruction and attach later cleanup errors as secondary evidence.

The controlled success path includes repeated post-end requests and separately
correlated replies. Negative controls cover missing work, foreign owners/generations,
observation crossing and both recorded close orders. The shared-budget control holds
one delivery for 20 seconds and another for 20 after the observation; restarting the
budget incorrectly passed before correction. Failure/reuse probes require every
owned Activity to close once and prevent a previous evaluation's end or throwable
from being reused. See the [lifetime rule](knowledge/features/development-verification-test-scheduling.md#terminal-replay-producer-lifetime-2491).

**Retained macOS repair evidence.** The source-matched focused-main failure at
`a48e9ced0e9bc7e9193445d576afb6fb498a7b99` first closed at step 32 before test-end,
with required queries pending. The fresh pre-repair native run at
`7835ac552791ee4092d1167fb0de3d26bdc4ea13` separately executed/failed one method
at step 33 after test-end, with three queries and scenario-complete pending.
Later delivery/cleanup failures propagated the first mismatch. These are distinct
attempts, not proof of an Agent ownership or visibility assertion failure.

| Retained attempt | Executed | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Lifecycle controls before repair | 2 | 0 | 2 | 0 | 0 |
| Cleanup controls before repair | 2 | 0 | 2 | 0 | 0 |
| Shared-budget control before correction | 5 | 4 | 1 | 0 | 0 |
| Affected JVM classes after repair | 95 | 95 | 0 | 0 | 0 |
| Exact native method at `00055df3565663f87e0ec378fd5b3bc793a9b833` | 1 | 1 | 0 | 0 | 0 |

The native command was `python3 scripts/android-test-gate.py ui --tests
'de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_replyStaysUnderAgent'`,
exit 0. The phone trace reports `early_checkpoint=true` and strict tape completion.
The unchanged recording SHA-256 is
`40b136aa8f28f08091e179f8e7ba3ae2be644d357554db0f87e862691d9be786`.
Raw XML, phone/replay logs, effective source/APK identities, cleanup receipts and
verified manifests remain under agents `logs/evidence/2491/builder-20261023-macos/`,
including `unit-red`, `cleanup-red`, `deadline-red`, `deadline-green`,
`native-baseline-fail` and `native-verified-pass`. The prior verifier authenticated
these manifests; the manual continuation also read their raw counted reports.

The earlier verifier could not approve after 218 script tests: 217 passed and one
independent readiness fixture failed (#2500); later configured gates did not run.
That attempt remains failed. The #2500 repair was subsequently integrated without
changing the lifecycle implementation.

**Accepted integrated-candidate evidence (2026-10-23, macOS arm64).** Independent
review accepted `eb99f99da89e217aff4ed11365b536576c24a827`, with main
`f1f9c3adb2b733c28247ea81d0cdd4353b607f1a` integrated, with no findings.
The configured gate results were:

| Gate | Executed | Passed | Failed | Errors | Skipped/ignored | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Scripts | 220 | 220 | 0 | 0 | 0 | 0 |
| JVM with build checks | 5411 | 5411 | 0 | 0 | 0 | 0 |
| Complete UI, original run | 333 | 327 | 6 | 0 | 1 | 1 |
| Exact rerun of six unrelated UI failures | 6 | 6 | 0 | 0 | 0 | 0 |
| Scripted-all | 24 | 24 | 0 | 0 | 0 | 0 |

The UI count excludes the additional ignored
`RenameDialogCaptureTest#renameAtFigmaViewport`. The dispatcher accepted the gate
under its flaky-rerun policy; the original six failures remain failures. Existing
trackers #2476, #2368 and #2360 and dispatcher first-sighting records retain them.
The JVM run included all five `RecordedTerminalLifecycleTest` methods and all three
`RecordedTerminalFailureTest` methods, each executed and passed. Lint, Spotless,
assembly, instrumented compilation, pre-verify and docs guard also passed.

`RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_replyStaysUnderAgent`
executed and passed in the **original complete UI run**: 1 executed, 1 passed,
zero failures/errors/skips, in 8.877 seconds. This is distinct from the retained
focused run above and from the six-method rerun. The verifier's XML/phone-log review
confirmed `terminal_preparation_finished early_checkpoint=true`, effective test
BuildConfig `eb99f99da` and finished device cleanup phases. Original Agent child
attribution, collapsed/expanded visibility, uniqueness and separate main-lane reply
assertions remain intact.

The accepted verdict and gate logs are retained in fleet evidence run
`037ec7668cd2ad20d1039a053188da0b3e3111cd508d1f75af2344a2bfbfe318`
(`handoff/verifier-2491.md` and `logs/` in the downloadable bundle). Its accepted
stage result is authoritative even though the worker ended with a timeout.
The transient gate worktree reports have been removed; their paths are not durable
references. The focused source identity above is unchanged; its effective app APK
SHA-256 is `536bc19fbec540d9968b9a5e18d40cdf76300cc53e7f82fb83a33978ae12288c`
and test APK SHA-256 is `8b1fca1faa064af4b534e5a1864968d84a2a2c4b916f551d925aff9dd01b0093`.
No new provider capture was required: replay does not prove current provider behavior.
Linux is outside this macOS ticket's verification scope.

### Downstream consumption before close (#2348)

Keep the selected connection's repository collector alive through `beforeClose`, then
cancel its scope and physically close the transport. `awaitIdle` alone drains delivery
work into the pump's channel; it cannot prove the application processed those envelopes.
The real-repository regression
`SuiteCloseConsumptionTest.closeWaitsForTerminalConsumptionBeforeReplacementResumes`
holds a terminal after its batch applies: checkpoint zero and the pending close must
remain until release, then the replacement's exact recorded request resumes at 12.
An unread peer frame neither releases nor blocks that phone fence.
`cancelledCollectorCannotAcknowledgeTheClosePrefix` retains failure under the original
deadline when the collector is cancelled while the terminal is held.

Synthetic close fixtures also need an actual scoped inbound collector and a correlated
receipt for the complete expected reply. `Flow.first` aborts emission before the
post-emit consumption acknowledgement; merely observing the reply with it cannot
establish close readiness. Keep consumers active through the fence for activity
teardown, pairing removal and both generations of repeated graph restart. Check
reply correlation and collector lifetime before physical close. Preserve missing-work,
changed-payload/epoch/owner and complete-consumption negatives. No recording or
production request contract changed for this repair. See the
[diagnosis and counted macOS evidence](knowledge/features/development-verification-test-scheduling.md#background-permission-replay-consumption-2348).

### Foreground terminal cleanup

Recorded native tests retain the Activity through required phone work on both sides
of `test-end`. Foreground preparation waits for the required phone prefix bounded by
the next observation or test-end. It sends no end control and consumes no physical
close. Request matching, delivery enqueue, tail preparation and the readiness read
share the hub monitor. Otherwise a request can release tape work while its reply is
still waiting to enter the delivery queue, allowing a delivery barrier to pass early.
The serialized hub barrier follows enqueue; explicit close also retains its downstream
consumption fence.

Prepare duplicate tails only when the next command is test-end and no earlier
observation bounds the prefix. Eligibility requires a prior fully decoded and actually
delivered response on the same connection lifecycle, conversation, nonzero checkpoint
and epoch, covering every identical candidate fact. Incomplete or foreign coverage,
changed facts and intervening source changes or commands cannot qualify. Required
repeated queries and duplicate-ID rejection remain strict. Later work cannot cross an
observation to make teardown succeed.

The session cleanup rule sends its original end action exactly once while the Activity
is alive. Terminal preparation follows the recording's actual placement of
`scenario-complete` and phone close. The same rule owns replacement Activities through
this sequence. Restart preparation selects existing phones; terminal teardown can await
recorded replacements. Original requests, observations and complete-tape checks remain.
A scenario failure bypasses successful-scenario preparation and remains primary if
Activity destruction also fails. Required work still fails under the original bounds.
See [scenario cleanup and counted evidence](knowledge/features/development-verification-gates.md#scenario-cleanup-before-activity-teardown).

### Accepted shared-fixture evidence (#2505)

The authoritative verifier stage accepted integrated Mobile
`2a5d3096ef22a1908f878ebb734ad4b050cae78d` on macOS in run
`6651736b89ef2fbca94b73564bba9eeca206d579933e97f60ea8d0855754801b`.
Its exact-candidate verdict is PASS under the retained flaky/inherited dispositions
below; the original configured failures remain recorded.
Linux was outside this ticket's verification scope.

| Configured command or comparison | Executed | Passed | Failed | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| `python3 scripts/jvm-test-gate.py --with-build-checks` | 5491 | 5489 | 2 | 0 | 1 |
| Exact two-method candidate JVM comparison | 2 | 2 | 0 | 0 | 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` | 249 | 249 | 0 | 0 | 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all` | 24 | 19 | 5 | 0 | 1 |
| Exact five-method candidate scripted comparison | 5 | 0 | 5 | 0 | 1 |

All reports have zero errors. Each required `RecordedMobileSuiteTest` method executed
and passed once in the configured UI run, without skips:

- `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`.
- `interactiveTurn_otherConversationAttentionPills_waitingAndFinished`.
- `interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`.
- `interactiveTurn_backgroundAgent_replyStaysUnderAgent`.

`DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_replySurvivesMidTurnReconnect`
executed and passed once in configured scripted-all, without skipping. These were
complete configured runs, not separately focused executions. The JVM XML also contains
17 acquisition, 12 readiness, five decorator, nine phone-teardown and 29 suite-connection
methods, all passed.

The JVM failures were `AuthoritativeWindowRestoreTest#validationInvariant_coldUnavailableOpeningWithholdsReplayUntilRecovery`
(first sighting retained) and
`AuthoritativeOwnedRemovalTest#retirementInvariant_successReleasesRecoveryButFailureRetainsIt`
(tracked by #2523). Both passed the exact candidate comparison. All five scripted
failures persisted in the candidate comparison and failed on merged main
`3c35ff3b4cdf7c52444b0235696b6ab59f0792c1`: session-error recovery (#2353),
earlier-session Agent restart, background Agent settlement and durable-gap catch-up
(#2521), and scripted ping (#2469). The accepted disposition preserves those original
reds; it does not turn the complete suites into all-pass executions.

Retained raw files at review time were under
`$AGENTS_REPO_PATH/logs/evidence/2505/deadline-rework-20261023/configured-2a5d3096/`:
`jvm-fmupk1oe-dispatcher.xml`, `jvm-az6ebf6c-dispatcher.xml`,
`ui-y19i2xwh-dispatcher.xml`, `scripted-all-zji01te7-dispatcher.xml` and
`scripted-all-lizuw0_c-dispatcher.xml`. The accepted run's downloadable evidence bundle
retains these XMLs, manifests, verdict and observation receipts.
`configured-2a5d3096-raw-reports.tar.gz` retains unmodified reports, invocations,
Gradle logs and phone logs, with its digest in `raw-reports-manifest.json`.

Actual installed app/test readback during UI and scripted execution matched retained
APKs. Scripted observations recorded actual running daemon and relay executables:

| Identity | Source | SHA-256 |
| --- | --- | --- |
| Installed app | Reviewed Mobile candidate above | `5510d02f00493e09865526ca88815e7d56793238a3a20ae28a0760c437aa73a5` |
| Installed test | Reviewed Mobile candidate above | `61d2f33cf27bac650a5e57ef157b5a332f4e24e20481549af2b5019a76b6e919` |
| Effective daemon | `cb556b7697fa6f10685a630fb6a73139ed82cf9c` | `64b9456d7f21e41b4d4a89e5db72aee4a9d655e10ca433fd6bf730cf618dbcd6` |
| Effective relay | `72f40c5de9e851e6c6c1ca3c789761320e05ce51` | `17e24131cfd3f5a0bc48f972021db17925347311e3dcdf362f2aa8cfe48fc710` |

The three `v3-complete-*.json` receipts finish with no remaining observed process
identities or owned group members after gate exit. The UI receipt retains a sampling
handover gap: v2 missed service-named emulator ports; frozen v3 continued the retained
identities using numeric ports. It does not prove uninterrupted sampling. Recorded UI
launches no daemon/relay. Reconnect harness evidence was retained under
`$AGENTS_REPO_PATH/logs/evidence/2505/harness/fault-443b8f987cc3314da109a9b5677a7e99/`;
`inputs.json` records source, matching daemon hash, exit 0 and complete retention, with
daemon/fault/relay logs and their verified manifest. Debug APKs embed `dev`; the custom
daemon has no embedded revision. Source provenance and effective hashes establish
these identities. Earlier built-only hashes remain subject to their original limits.

Historical controls and failures remain separate: the snapshot-only acquisition control
failed 1/1; queued-reopening/source-retirement controls ran 10, passed seven and failed
three; observer-cleanup control failed 1/1. Deadline/fixture controls failed 2/2 at the
intended assertions, zero errors/skips; repaired focused checks passed 34/34. Earlier
native cancellations and failed verdicts at `12aeedf5a` and `7e856c463` remain failures.
Raw history was retained under `manual-shared-fixture-20261023-85e7b2085`,
`2505/manual-rework-20261023` and `2505/deadline-rework-20261023` in the agents evidence
root. The [plan revision record](specs/architecture/manual-shared-test-fixture.md#revision-record-and-deadline-repair-plan-2026-10-23)
separates the adopted prototype's historical exception from later combined plan/code
commits `9e94999ac` and `a4ab9eb84`. The plan-only `8faef212a` precedes executable
repair `c504eac18`; no history was rewritten.

Production source and recording bytes are unchanged. No new provider capture or
real-Claude turn was required. Acceptance proves neither unrelated live delivery nor
one cause for all historical flakes. Related tickets retain their own holds, ownership,
approval records and acceptance requirements; this result grants no bulk closure or
resumption.

#### Model-reopen reconciliation (#2356)

The [independent verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2529#issuecomment-6385551176)
accepted `43e5d2819b4335356e274a08be66d901bedb0fa3` on macOS in run
`e5ca11a5e56df784e7c4e8b43bbfb5e3c7076c7d1e2a90eb739a65037130581b`.
The model scenario retains exact saved X/Y isolation across both reopens, current
menu selection/confirmation, saved-versus-applied distinctions, restoration and its
original deadlines. It reuses #2505's current-query mechanism and #2493's refreshed
model recording; production source and recording bytes are unchanged by this repair.
The fixture SHA-256 is
`b523906cc45de9399580e0119b7f79f93d57d68001c9e534ab54744da9767873`.
It differs from #2505's older input, so that older pass alone cannot certify this one.

The original #2351 evidence retained Y's correlated saved `sonnet`/`low` values and
current ownership while its menu was hidden behind unmatched X catch-up work. Its
composite confirmation failure was not independent proof of persistence loss.
The cached-checkpoint, distinct consumed destination request and source-retirement
controls above establish the repaired acquisition boundary. They support, without
proving, attribution of every historical model occurrence. Preserve the diagnostic
history in
[`model-reopen-2351/investigation.txt`](../app/src/androidTest/assets/model-reopen-2351/investigation.txt)
and the checked
[`model-reopen-2356/reconciliation.txt`](../app/src/androidTest/assets/model-reopen-2356/reconciliation.txt).

| Accepted configured result | Executed | Passed | Failed | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| JVM with build checks, fresh execution | 5557 | 5557 | 0 | 0 | 0 | 0 |
| Complete recorded UI, original run | 250 | 250 | 0 | 0 | 0 | 0 |
| Scripted-all, original run | 24 | 20 | 4 | 0 | 0 | 1 |

The required
`RecordedMobileSuiteTest#interactiveTurn_modelChange_roundTripsAndStaysPerConversation`
executed/passed once in that original complete UI run (4.987 s), with zero
failures/errors/skips. Reconnect-footer, interrupted retrieval and ping also each
executed/passed once in that same run. They are not additional focused executions.
The original four scripted failures remained in the exact candidate comparison and
also failed on merged main `4a295611bec8ca3162c461ea0cb1e09fc722fdee`:
earlier-session Agent restart, background Agent settlement and durable-gap catch-up
belong to #2521; scripted ping belongs to #2469. The verifier accepted these inherited
dispositions. The original failures remain failed; scripted-all was not an all-pass run.

The accepted review separately authenticated focused macOS acquisition controls
18/18, readiness controls 12/12, repaired reconnect 1/1, exact model 1/1, and the
admitted interrupted-retrieval → ping pair 2/2, all with zero failures/errors/skips.
The old unfenced replacement-source control remains 1 executed / 0 passed / 1 failed,
zero errors/skips. The unrelated unrepaired reconnect baseline passed 1/1 and does
not demonstrate the repair. Model/reconnect source was
`4574645e3a2227ffcef12f74ca912ae42c51ac8f`; pair/control source was
`f3a893188519b7282ba8306467c5f6adf2240da0`. Review checked relevant helper/scenario/fixture
equivalence: later differences were bounded IME diagnostics and ping's passive
first-wait observer. Raw TestRunner records establish retrieval followed by ping in
one instrumentation process; passing that pair establishes no causal IME repair.

Keep the earlier configured result separate: 40 completed, 39 passed, one reconnect
terminal-cleanup failure, then an unfinished ping with no completed result. The model
method passed in that attempt, but the UI gate was incomplete and scripted-all never
ran. Later success cannot turn the unfinished ping into a pass or establish its
exact historical interleaving. The separate sequence/load assessment
`a5f9f370771aaf4afb5261e8847b7bfb8988e0e79bda0dcb24ac53c97beb7ef5`
acknowledged zero executions. Its historical question and evidence remain retained;
the accepted candidate's configured execution requirements are satisfied. #2368
continues to own later model-sighting reconciliation. Neither renderer precedent
nor the passing ordered pair attributes the original Mac hang to a renderer,
IME, lifecycle or resource defect.

The accepted verifier names the run above for fleet-evidence retention. Its producer
`logs/verifier-gate_#2356_4.log`, `_5.log` and `_6.log` retain the counted configured
XML after the disposable worktree is removed. Manual documentation rechecked these
logs and retained the original output/stderr, extracted XML, hashes and counted
receipt under agents `logs/evidence/2356/manual-documentation-20261024/`.
Focused/history files were retained at review time under agents
`logs/evidence/2356/builder-macos-20261024/`, `recovery-macos-20261024/` and
`sequence-macos-20261024/`. Review authenticated their respective 84, 140 and 38
manifest entries plus #2351's 19-entry original manifest; entries are artifacts,
not extra test executions. These retention paths may later be pruned. Built APK
hashes identify outputs; independent installed-byte readback was not established
for these focused attempts. Offline replay proves no current provider/real-Claude
behavior; Linux was outside this ticket's verification scope.

### Explicit graph-restart readiness (#2347)

The #2493 retained restart corrections also wait for `ProcessLifecycleOwner` to
leave STARTED after Activity destruction and again before replacement launch, under
the existing thread deadline. Rebuilding the graph while a delayed process-stop
callback is pending can otherwise close the replacement connection. Placement
polling treats a temporarily absent or nonunique scroll semantics list as not yet
ready; it does not establish placement or weaken the required assertion. The
separate historical absent-session-boundary scroll failure remains failed evidence.
On accepted macOS `07b3eaadb`,
`RecordedMobileSuiteTest.interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`
executed/passed in the original full UI run (250 executed, 248 passed, two unrelated
failures, zero errors/skips); both failures passed the exact 2/2 rerun. This is
configured replay evidence, not a separate focused run or #2494 final-read proof.
See the [integration identities and cleanup limits](#accepted-macos-integration-2493).

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

Replacement Activity lifetime matters independently of the old graph's restart
fence. In the interactive Agent restart proof, transfer the replacement to
`LiveSessionCleanupRule.withRestartedActivity` before running its assertions.
Keep its request producers and inbound consumers alive through fixture/peer
cleanup and the rule's `end` callback, including `test-end`; only then close
retained Activities once, in reverse registration order. Body failure remains
primary, otherwise end failure, otherwise the first close failure; later failures
are suppressed and every retained close is attempted. The standalone scripted
caller keeps the proof's default lexical lifetime.

An outer Activity teardown fence cannot protect a replacement already closed by
an inner `.use`. [#2476](https://github.com/pyrycode/pyrycode-mobile/issues/2476)
recovered that race at source `cfcb4083fb1425576ed18265309fd88f0fb7d2a1`:
`LifecycleConnectionDriver.onStop` closed `phone/host-0/2` at lifecycle step 69
with request 13/checkpoint 0 still unmapped and `thread_catch_up` and `test-end`
required. Later cleanup exceptions repeated the first sticky mismatch. Waiting
at this inner close for a boundary that requires the later end control would
deadlock the ordering; retain ownership through cleanup instead. Payload, owner,
checkpoint and complete-consumption checks, recording bytes and deadlines stay
unchanged.

`SuiteCloseConsumptionTest.replacementActivitySurvivesResetQueryAndOrderedSessionCleanup`
holds a real repository's same-epoch reset query across proof return. Its reply
must advance certification from 12 to 24, so terminal-tail normalization cannot
stand in for the missing query. Cleanup releases the reply and consumes fixture
cleanup and test-end before physical close. The controlled old lexical lifetime
failed on premature `phone/host/2:close` (1 executed, 1 failed, 0 skipped); the
ownership repair passed all three consumption tests (3 executed/passed, 0
failed/errors/skipped), including the cancelled-collector negative control.
`LiveSessionCleanupRuleTest` covers success and body/end/close failures with
once-only cleanup. Raw XML and extraction patches were retained under agents
`logs/evidence/2476/builder-lifetime-repair/{regression-red,regression-green}/`.
See [counted Linux replay and configured acceptance](e2e-interactive-stream.md#negotiated-agent-restart-and-settlement-2246)
for source revisions and the original failure/rerun evidence.

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

#### Operator-bypass host removal (#2484)

`RecordedMobileSuiteTest#interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`
can render its exact witness token from a history batch before the phone's final
catch-up finishes. In the #2462 macOS failure on `2db93ebd8a`, removing its dedicated
host disposed the phone through `RelayConnectionRegistry.reconcile` while request
82/checkpoint 33 was still unmatched (`consumed=false,mapped=false`). The first
stored mismatch at lifecycle step 39 was `phone/host-3/0:close` instead of
`thread_catch_up`; the later test-end exception rethrew it. Neither peer completion
nor activity teardown protects a host removed inside the scenario.
[The causal ticket evidence](https://github.com/pyrycode/pyrycode-mobile/issues/2484#issuecomment-6355086359)
identifies retained #2462 bundle
`db5461c8f6c2f795f941b095d278cb092cf3f6c602d586d56879963b8dcda166`,
SHA-256 `6a6b24f31c0b914c6968c7d6cc0f1f10d35cc296709078899f7aaf85ad89e193`,
and `logs/verifier-gate__2462_5.stderr.log`. Its failed gate had 333 executed,
331 passed, 2 failed and 1 skipped; the same-worktree rerun passed 2/2 with no
failures/skips, including this method. That rerun did not establish a repair.

The scenario now uses `withOperatorBypassCleanup`, delegating to the existing
peer/fence/host cleanup contract above. The fence runs while phone request producers
and collectors remain alive. Required request delivery and downstream terminal
consumption must both finish before removal. Permission-write classification,
same-session mode readings, the rendered permission prompt, peer approval and exact
witness-token reply assertions remain intact, as do capture bytes, strict matching
and the existing deadline.

`OperatorBypassCleanupTest` reuses the genuine capture's final request, correlated
reply and close tail. Independent deferred gates hold request production after
scenario return and downstream consumption after reply delivery. Both
`finalCapturedCatchUpFinishesBeforeHostRemoval` and
`scenarioFailureStillDrainsAndRemovesHost` must wait through both holds; the latter
also preserves the original scenario throwable. `PermissionIsolationCleanupTest`
continues to reject missing work and another host, connection, conversation, epoch
or checkpoint. Delivery alone cannot authorize removal.

macOS evidence for implementation `244aa1699f508aabc2b3cd0ac4d846d2fd410d2d`:

| Run | Executed | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Controlled original cleanup | 2 | 0 | 2 | 0 |
| Controlled repaired cleanup, unchanged regression | 2 | 2 | 0 | 0 |
| Focused permission cleanup class | 7 | 7 | 0 | 0 |
| Focused close-consumption class | 3 | 3 | 0 | 0 |
| Focused native operator-bypass method | 1 | 1 | 0 | 0 |
| Configured JVM/build gate | 5353 | 5353 | 0 | 0 |
| Configured UI gate | 333 | 332 | 1 | 1 |
| Same-tree model-change rerun | 1 | 1 | 0 | 0 |
| Configured scripted-all gate | 24 | 24 | 0 | 0 |

The controlled runs used `python3 scripts/gradle-gate.py testDebugUnitTest --tests
de.pyryco.mobile.e2e.OperatorBypassCleanupTest --console=plain` on working snapshots
based on `fe630c6e3c6e1ba5c469ba1193d9a628847b44d7`. Both old-cleanup cases failed
with `Host removal raced the final captured catch-up`. The identical pre/post test
source hash is `78d52fe1e53a5fe436fe5dc37c9407804954c7512590112f47f798aa8fb40f8b`;
final formatting only moved an import. Native replay used
`python3 scripts/android-test-gate.py ui --tests` with the exact method named above.
It passed focused (27.931 seconds) and in the configured UI gate (25.482 seconds),
without failure or skip. The full JVM gate also passed both new regression methods,
`missingPhoneWorkFailsAtTheExistingDeadlineAndStillRemovesHost` and
`aReplayMismatchRemainsFirstThroughCleanup`.

Configured acceptance used `python3 scripts/jvm-test-gate.py --with-build-checks`
and `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py` with
`ui` and `scripted-all`. The raw UI gate exited 1; only the unrelated model-change
method failed. [Verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2487)
accepted its passing same-worktree rerun under the dispatcher's flake policy,
tracked on #2368. `RenameDialogCaptureTest#renameAtFigmaViewport` was skipped,
never passed. Skips are additional to executed counts. This acceptance supersedes
the PR body's earlier pending-gate note; later main sweeps remain dispatcher-owned.

Raw XML, source snapshots/diffs, revision metadata and manifests were retained at
`$AGENTS_REPO_PATH/logs/evidence/2484/` in `controlled-before/`, `controlled-after/`,
`permission-cleanup/`, `close-consumption/` and `native-244aa1699/`. The last also
holds phone logs and phases; its native XML SHA-256 is
`13a63d7f26ee5e7613a496daefe259b068b071f7396931cb721a78ff6b972597`.
Accepted verifier bundle
`64bef236585eb0531e7d614bb508ddd721e933390d16f2b961f8aab4152ac431`
retains `handoff/verifier-2484.md`, counted gate XML in
`logs/verifier-gate__2484_5.log` and `_6.log`, and the timestamped model rerun logs.
These paths describe retention at review time. No fresh live Claude observation
or visual change was required; Linux was outside this ticket's verification scope.

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

Since #2366, `TappingConversationRepository` records parser-gap notices from the
projection of the destination's authoritative `NegotiatedThread.observe` snapshots.
It forwards support, store, progress, query leases, paging and cancellation to that
same source. The optional passive probe taps that existing subscription; it starts
no independent collection or query. Legacy snapshot delegation remains only for
retained legacy tests until #2367.

Recorded scenario assertions and capture/replay contracts are unchanged; this fixture
migration required no recording refresh and claims no new live compatibility run.
The checked inventory remains 18 scripted harness clients, now 91 methods: two new
render/query/revision invariants and one local-send identity invariant, with two
context method renames. All 91 executed and passed without skips in the accepted
Linux JVM/UI gates, including all six device sentinel methods. The
[complete counted evidence and inventory](e2e-interactive-stream.md#authoritative-fixture-migration-evidence-2366)
retain the scripted refusal failure, exact comparisons and existing skips separately.
The complete UI gate also executed and passed
`RecordedMobileSuiteTest.interactiveTurn_peerStartedTurn_continuesOnPhone` once,
with zero failures/skips for that method; its existing display/reopen assertions remain.

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
