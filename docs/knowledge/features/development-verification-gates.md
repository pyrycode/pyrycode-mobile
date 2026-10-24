# Development verification — change surface, Gradle gates and where a test goes

Split out of [Development verification](development-verification.md) on 2026-10-02 to keep that
document under the 50000-byte size cap the docs guard enforces. Every section below moved here
verbatim and kept its heading, so its anchors are unchanged. Part of
[Development verification](development-verification.md); see that document for the other topics
and its links.

## Establish the change surface

Search production callers, test doubles and Compose call sites before changing a
type or callback. A declaration search misses callers hidden behind a ViewModel,
repository facade or defaulted composable parameter. Search both the symbol and
the user-visible text when removing a field or action, because KDoc, fixtures and
semantics labels can keep the old contract alive after the compiler is satisfied.

Trace the declared interface type to its assigned implementation. Matching method
names do not prove that two repositories or transports have the same behavior. For
a new sealed event or wire arm, inspect every type switch, mapper, logging site,
state projection and test table. A default branch can silently ignore a new case.

## Live Claude reply assertions

Keep ordinary reply requests and assertions flexible about wording. Require exact
wording only when that wording is the feature under test. Use recorded conversation,
turn and agent identities to distinguish replies from user prompts and other replies.
Check completion, delivery and attribution. A reply may span several text rows or
paragraphs, and a background agent's later reply may have its own turn identity.
Precise commands that create controlled test conditions and fixed app labels remain
appropriate. Display tests should use controlled fixtures when they need fixed prose.

### Shared thread evidence: legacy and authoritative (#2169)

`ThreadEvidence.kt` (`app/src/sharedTest/java/de/pyryco/mobile/e2e/`) gives both the
legacy (`observeMessages`) and the daemon-authored (`AuthoritativeThreadSnapshot`)
presentations a common evidence shape, so migration helpers do not have to special-case
either source. It is test-only: nothing in it opens a query, advertises capability or
changes a production binding.

**The shared types.** `ThreadEvidenceKind` (`Legacy` / `Authoritative`) tags a
`ThreadEvidenceOwner` — host, conversation, a reference-identity `connection` token, and,
for authoritative evidence, epoch/generation. `ThreadEvidenceOwner.matches` requires every
field equal, including reference identity on `connection`, so a reused host/conversation
pair on a different connection, or the same connection carried over a stale epoch or
generation, is a different owner and cannot settle against retained evidence. A legacy
caller that only has a repository, not a connection-scoped host, reports `host = null`
rather than inventing attribution — never read a null host as "unknown but probably this
one." A `ThreadEvidenceRecord` pairs one original `Message` with the full daemon text,
session/turn, and the rendered `agentId`/`toolRunId` the presentation layer assigned it;
for authoritative records it also keeps the raw `ThreadItemDto` fact. `ThreadEvidence`
wraps an owner, its records and an `admitted` flag, and exposes `loadedMainReply`,
`settledReply` and `userEchoCount`. `ThreadEvidenceSource` is construction-only
(`.legacy(...)` / `.authoritative(...)`); it wraps an `observe(conversationId)` flow and
performs no side effects itself.

**Loaded selection vs. strict settlement.** `loadedMainReply()` answers "is there a
reply a human could select right now" — nonblank, non-streaming, no agent block, and a
main-thread parent. It does not require delta evidence, so an ordinary history message
that was never streamed still counts. Main-thread parent means different things per
source: legacy evidence reads the raw `Message.parentToolUseId`; authoritative evidence
requires the daemon parent to be absent or explicitly `0` *and* no projected Agent block
ID, because a positive parent with no resolved block is an orphaned child, not main-thread
evidence, and a parent explicitly cleared to `0` is not the same fact as a nonempty scoped
presentation identity that happens to render in the main column. `settledReply(...)`
answers a stricter question — "is this specific session/turn/agent family's reply
completely delivered" — and the two sources settle differently:

- **Legacy** filters records by raw parent (`parentToolUseId == agentId`) within the
  session/turn, then delegates to `holdsSettledAssistantReply`, which still requires the
  original contiguous-delta and full-length checks. Nothing here loosens that contract.
- **Authoritative** filters by `fact.kind == "assistant_message"` and the projected
  `agentId`, then requires every record in that family to be inactive, carry a
  `done`/`finished` status on *its own* item, and have nonblank full text. A `turn_end`
  fact or any fallback/unsupported-kind row never joins the family and can never block or
  fake a settlement. Critically, settlement reads the assistant item's own completion, not
  whether its parent agent (which may still be running) has terminated — a still-running
  parent does not prevent its own completed child item from settling.

Do not synthesize delta evidence for authoritative items to make them look like legacy
ones, and do not treat receipt, delivery acknowledgement, or a query checkpoint as
settlement — `userEchoCount` requires the user message's own `fact.status == "delivered"`
when a fact is present, not just that a row with matching text exists.

**Full text vs. bounded rendered text.** Authoritative records keep the full daemon
`fact` content (unwrapped from its `JsonObject`/`JsonPrimitive` wire shape) separately
from whatever the presentation layer bounds for display. A record can have complete full
text while its rendered text is blank or truncated; `loadedMainReply()` requires both the
record's `fullText` and the underlying message content to be nonblank, so a complete item
whose bounded presentation renders blank is correctly excluded from "something a user can
select," even though the daemon considers the item done.

**Identity provenance.** Authoritative identities are never derived from raw tool/task
IDs read off the wire fact — they come from `projectAuthoritativeThread` (for
`agentBlockId` / scope-qualified item IDs) and `foldToolRuns` (for `toolRunId`), the same
functions production rendering uses, so test evidence and the screen agree on what a
rendered row's identity is even when two hosts or agent families reuse the same raw tool
ID. Legacy identities come from `foldQueuedRows` → `foldBackgroundAgentBlocks` →
`foldToolRuns`, matching production's legacy presentation fold order; a legacy message
with no resolved rendered block still keeps its raw parent/segment attribution in the
record; that raw attribution is usable for legacy's own raw-parent settlement check but
never establishes main-thread *loaded-selection* attribution by itself.

**The harness binding is a single seam.** `harnessThreadEvidence(repository)` is the one
function every consumer calls; today it always returns `ThreadEvidenceSource.legacy(...)`.
Production activation will change only that function's body to follow the real
negotiation (once a daemon advertises `thread` support), and no consumer needs to migrate
again. The binding controls test observation only — it cannot itself enable an app route,
and it must never infer daemon support from cached or empty data. `ThreadEvidenceSource`'s
authoritative factory exists and is exercised by fixtures now, ahead of that activation,
precisely so the later seam change carries no risk of its own.

**Consumer reads.** `awaitFinishedSelectionReply` still means "a loaded main reply
appeared." `assertReplySuggestionLongPress` now takes the owning host's current
repository holder (#2436). `ReplySuggestionReads` selects conversation/session and
offer together inside `firstOnLive`, then follows that holder for exactly one
verbatim delivered echo and a newer session-scoped clear. Capturing one repository
can strand the offer wait after reconnect while the UI follows its replacement.
See [replacement regression and counted evidence](../../e2e-interactive-stream.md#reply-suggestion-live-connection-observation-2436).

**Lesson for later migrations.** When moving a stream-suite consumer off raw
`observeMessages` or a raw `AuthoritativeThreadSnapshot` read, reach for `ThreadEvidence`
rather than re-deriving ownership or settlement locally: the owner-equality check (`===`
on `connection`, not just equal host/conversation strings) is what makes
`firstOnLive`'s replacement/cancellation guarantees (see
[reconnect-slash-commands-follow-live-connection](../../specs/architecture/1029-reconnect-slash-commands-follow-live-connection.md))
compose correctly with either presentation, and re-deriving it by hand is an easy place to
accidentally accept stale evidence across a reconnect.

## Gradle and source checks

See [Gradle and source checks](development-verification-gradle.md#gradle-and-source-checks)
for build admission, named comparisons, watchdogs, heap evidence and snackbar routing.

### Shared admission and Linux acceptance (#2327)

Containers sharing the Gradle cache must resolve `PYRY_TEST_ADMISSION_LOCK` to
the same host-backed file. Passing admission unit tests cannot prove deployment
identity. The [Gradle admission guidance](development-verification-gradle.md#shared-cache-and-deployed-admission-identity-2327)
records the deployed mismatch, persistent Quadlet correction, effective ownership,
hashed fixture and before/after comparison. The original main sweep at
`7a58afac133204acba692a7c9567f59e224de4f1` failed during journal-cache startup,
before APK compilation or device execution; it establishes no failing device test.

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2332#issuecomment-6384551796)
accepted candidate `6f187e3048229350ec697c7ff5f7257e4883fd56` against merged
main `4a295611bec8ca3162c461ea0cb1e09fc722fdee` on Linux, run
`d5a8a250ab7180ebc260198865ed00990745913561be4d3c8d3528b864f54b2e`.
Configured commands were `python3 scripts/jvm-test-gate.py --with-build-checks`
and `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py` with
`ui` and `scripted-all`:

| Check | Retained result |
| --- | --- |
| Script unit suite | 220 executed/passed, zero failed/skipped, including seven admission lifecycle regressions. |
| JVM/build gate | 5,556 passing methods in Gradle-verified cached XML, zero failed/skipped; **zero newly executed JVM methods**. Lint, formatting, APK assembly and instrumentation compilation completed. |
| Configured UI | Changed-file skip, exit 0, **zero device methods executed**. Accepted #2338 UI evidence supplies 250 executed/passed, zero failed/errors/skipped; relevant application, test and build inputs match. |
| Configured scripted-all | Raw exit 1: 24 executed, 20 passed, four failed, zero errors/skips. Exact recovery method passed once. |
| Same-tree and main selections | Each four executed/failed, zero passed/errors/skips; inherited disposition accepted on existing trackers. |

The failed `DeterministicInteractiveStreamE2ETest` methods
`interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`,
`interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` and
`interactiveTurn_seededChannel_durableGapCatchUp` remain with
[#2521](https://github.com/pyrycode/pyrycode-mobile/issues/2521);
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` remains with
[#2469](https://github.com/pyrycode/pyrycode-mobile/issues/2469).
Accepted comparisons preserve these failures; they do not turn the raw run green.

Required owner #2338 / PR #2509 supplied the accepted repaired-source proof for
`DeterministicInteractiveStreamE2ETest.interactiveTurn_sessionError_recoversDroppedAndRetainedBacklog`.
The [dependency receipt](../../../scripts/e2e-fixtures/2327-journal-lock/session-error-acceptance.json)
retains source/binary identities and accepted run
`b8fb0d854eb72efdd146a2fd8826aa58be8b232d0b11f2bdb1d9b1247c501452`
at `e51627c81871ad29708c8a0a572673bdda97db18`: UI 250/250 and scripted
24 executed, 20 passed, four inherited failures, zero errors/skips, including
recovery 1/1. Native focused captures retain fresh 1/1 pass, intervening reused
1/1 failure at dropped `held_send`, then final reused 1/1 pass, all zero skips.
Both successful captures preserve both arms, the original 20-second assertion,
independent child-input counts and cleanup. See
[session-error recovery evidence](../../e2e-interactive-stream.md#configured-linux-recovery-acceptance-and-remaining-work-2338--2327).
This child proof and the current candidate's configured recovery pass are separate
obligations, now both accepted.

Earlier #2327 evidence stays failed: the original scripted run had 21 executed,
18 passed, three failed and one additional skipped; its same-tree selection had
three executed, two passed, one failed, zero skipped. Recovery failed in both.
Main's historical focused 1/1 pass did not establish the cause. The separate
session-error repair does not reopen the deployed journal-lock correction.

The accepted fleet bundle retains `handoff/verifier-2327.md`, counted XML in
`logs/verifier-gate__2327_6.log`, and timestamped rerun/base logs; bundle digest
`d82ff4ad2c17b240926fbf8b9c3be745bfae092adc871d624bbb2ae1f274d5ae`.
Native dependency XML, input hashes and phone/cleanup captures were retained under
`$AGENTS_REPO_PATH/logs/evidence/2338/integration-linux-20261024/` at review time.
The committed receipts preserve identities and counts beyond raw-file pruning.
The later in-depth main sweep remains dispatcher-owned and pending; record its
result separately when supplied. macOS is outside this Linux ticket's scope.

## Where a screen test goes

Compose screen tests live in `app/src/sharedTest/java`. Gradle adds that folder to
both the unit test set and the androidTest set, so every test there runs twice
over: under Robolectric in `./gradlew test` on every verifier pass, and on the
emulator in the in-depth run. Put a new screen test there by default and run it
with `./gradlew testDebugUnitTest --tests 'fully.qualified.TestClass'`. No
emulator is needed.

Put a test in `app/src/androidTest` only when it needs something Robolectric
cannot give it:

- a real input method, device shell commands or `UiAutomation`, like
  `MobileModalTest`;
- real pixels saved as screenshots, like `ScannerFrameTest`;
- the Android Keystore or real on-device storage, like the `data.crypto` tests;
- the host daemon or relay, which is the e2e package;
- work on a background dispatcher that Robolectric's paused main clock does not
  drive, like `ScriptedUnrecognizedMessageTest`.

A Compose test goes in plain `app/src/test`, not `sharedTest`, when it must never run
on a real device even though it drives a Composable: `sharedTest` also runs on the
emulator in the in-depth run, and a test that exercises a real system surface — like
Android's own permission dialog — would trigger that surface for real there instead
of hitting Robolectric's shadow. `NotificationPermissionPromptTest` (#685, driving
`rememberNotificationPermissionRequest` under `createAndroidComposeRule<ComponentActivity>()`)
is the first such case — see
[Push messaging service § Testing](push-messaging-service.md#testing-685).

A test in the wrong folder fails safe. A device-only test placed in `sharedTest`
fails in `./gradlew test`. A Robolectric-capable test placed in `androidTest` still
runs on every verifier pass, only slower.

Robolectric's settings are in `app/src/test/resources/robolectric.properties`: the
test Application with the fake repository, and the emulator's Pixel 2 height. The
width stays at Robolectric's default 320dp on purpose. Any wider and a dialog
holding a text field never settles, and the test fails after 60 seconds with
`AppNotIdleException` ([robolectric#8460](https://github.com/robolectric/robolectric/issues/8460)).
A test that needs the Pixel 2 width wraps its content in
`DeviceConfigurationOverride.ForcedSize`. This does not resize a dialog's separate
Robolectric window. For a read-only modal, use a window qualifier and pass
`requiredSize` through the modal's modifier, then assert the measured content width;
otherwise the supposed 412dp fixture can still run at 320dp.
[`BackgroundTaskPanelLayoutTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelLayoutTest.kt)
uses `@Config(qualifiers = "w412dp-h892dp")` with no editable fields (#1164).
It checks the content-relative inset at two heights to reject vertical centering,
then checks text reachability and both Close actions in pinned and compact modes.
A test that measures text exactly, such as
single-line truncation or overflow, adds `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
so Robolectric uses real fonts. The device ignores Robolectric annotations.

`ForcedSize` applied under Robolectric's 320dp default window rescales the
density, so the root does not measure at the exact dp passed in —
`ForcedSize(412.dp, …)` measures as 411.61dp, not 412. A test asserting an exact
forced width fails on that rounding; compare within a pixel instead
([#1334](https://github.com/pyrycode/pyrycode-mobile/issues/1334)).

The same rounding happens on the emulator, which is a Pixel 2 at density 2.625:
28dp is 73.5px, lays out as 74px and reads back as 28.19dp. A shared test that
checks a Figma dp value uses `assertDpEquals` or `pixelDp()` from
`ui/PixelSnapping.kt`, which allow one device pixel there and nothing at
Robolectric's density 1. Rows stacked down a screen add their roundings up, so
check a row against its neighbour rather than against the top of the screen.
When the total position or height itself is a design target, retain it and
justify any larger `pixels` allowance from the measured, independently rounded
segments. Check the component gaps and line boxes separately within one pixel
so a permissive total cannot hide a spacing regression. The
[thread geometry examples](thread-screen-testing-coverage.md#testing) retain 97dp and
60dp totals with measured two-pixel allowances; integral-density checks remain
exact even with that allowance. The [reader coordinate checks](markdown-reader-screen-testing.md#fractional-density-coordinate-coverage-2292)
show that even adjacent-block totals can accumulate several text-line and internal-gap
roundings. Their local two-pixel bound has separate constituent checks and a boundary
control tied to the same allowance constant, rejecting three pixels and preserving
the default one-pixel bound.
The device also ignores `@Config` qualifiers and Robolectric's 320dp width,
draws text with its own font, and runs at that density: a test that needs a
412dp frame forces it with `ForcedSize`, scales pixel samples by the composition's
density, and measures a layout box rather than the text inside it.

Text width is never exact, so a test may allow slack on it and on anything a
text's width positions. Line height is different: it is fixed by the design,
not by the text inside it, so a test checks the height of a line and the
vertical rhythm it sets exactly, allowing only the one device pixel
`assertDpEquals` already grants for rounding.

Fetch semantics nodes on the test thread before entering `runOnIdle` for
main-thread text-layout access such as `GetTextLayoutResult`. A semantics query
inside the callback can nest main-thread synchronization; a Robolectric pass
does not establish device safety for that helper.

A shared test class needs `@RunWith(AndroidJUnit4::class)`. The device runner
does not require it, but without it the JVM runs the class outside Robolectric and
every test fails on a null `Build.FINGERPRINT`.

## Device gate

The dispatcher owns routine device execution through
`python3 scripts/android-test-gate.py ui`, which uses the Gradle-managed Android
13 device and does not require Android Studio to be open or an emulator to be
booted manually. By default it runs only the test classes under
`app/src/androidTest` outside the e2e package, on one emulator. The shared screen
tests already ran in `./gradlew check`. `UI_DEVICE_ALL=1` runs every screen test
on two emulators, the in-depth run to use every now and then or before a release. Report the command, XML evidence and executed count. A missing,
zero-count or failed run is not device evidence. Agents author the tests and
triage the supplied failure; a local `connectedAndroidTest` run is optional.

The default device selection also includes the captured Claude client scenarios in
`de.pyryco.mobile.recording`. These exercise the original native UI assertions using
application messages recorded from successful real Claude runs. They start no Claude
process or real daemon. `./gradlew check` requires complete recording inventory and
counted capture/replay evidence. Use the explicit full-suite live compatibility command
when checking a new daemon or refreshing a recording. It requires a fresh real capture,
replays the reviewed and fresh recordings and compares their request contracts.
Real FCM delivery, encrypted transport and timing obligations keep their live checks.
See [recorded client tests](../../recorded-tests.md#complete-native-suite) for commands,
recording review rules and the limits of replay evidence.

### Scenario cleanup before activity teardown

Final Activity teardown must also preserve request-producing jobs when the captured
phone close follows test-end. `RecordedActivityRule` runs foreground preparation
after successful scenario assertions, before Activity destruction. Wait for required
phone work before the next observation or test-end, then drain serialized delivery.
Do not wait for test-end itself, later close or registry disposal in this prefix.
Terminal preparation after test-end retains the actual captured observation/close
order, including replacement Activities. An original scenario exception stays primary;
Activity destruction failures are suppressed onto it.

In #2360's original offscreen failure, delayed process stop reached
`LifecycleConnectionDriver.onStop` with request 13/checkpoint 28 unconsumed, then
closed phone generation 1 at step 53. Later test-end cleanup rethrew that sticky
mismatch. The passing same-tree rerun did not explain the race; preceding-test state
and host load remained hypotheses. The controlled hub probe withheld the request
until foreground preparation and kept request acceptance separate from queued reply
delivery. It failed before repair and passed afterward.

Tape release alone cannot certify hub enqueue. `SuiteConnections.beforePhoneTeardown`
holds the same monitor as `request` while preparing eligible tails, enqueueing their
replies and checking prefix readiness; the delivery barrier follows that boundary.
`foregroundReadinessCannotPassTapeReleaseBeforeHubEnqueue` pauses a real request in
that gap and proves the fence contends for the monitor. A virtual-dispatcher test
without that gap can pass while broken.

`foregroundTeardownPermitsOnlyADeliveredDuplicateTail` covers preparation when the
next command is test-end and no earlier observation intervenes. Reuse the strict
terminal-tail validator: prior fully decoded, actually delivered coverage on the same
connection lifecycle, conversation, epoch and nonzero checkpoint must cover identical
facts. Incomplete/foreign coverage, changed facts, required repeated queries and duplicate
IDs cannot release missing work. `foregroundPreparationCannotCrossAnObservationCheckpoint`
keeps later work behind the observation. Existing missing-work and early-close controls
retain the original 30-second bounds. See [the replay contract](../../recorded-tests.md#foreground-terminal-cleanup).

The retained macOS rework report records seven executed, five passed and two failed
boundary controls before correction. Repaired focused classes passed 9 phone-teardown,
27 connections, 44 tape and two Activity-rule methods, with zero failures/errors/skips.
Intermediate logging failures remain separate. On `919810b07`, exact reopen passed 1/1
and five historical offscreen checks each passed 1/1, without failure or skip. These
focused results did not establish configured acceptance. The committed
[rework evidence index](../../../app/src/test/resources/verification/recorded-teardown-2360/rework-evidence.json)
retains counts, revisions, commands and raw-file hashes; its raw root at the time was
`$AGENTS_REPO_PATH/logs/evidence/2360/rework-20261021/`.

Accepted integrated #2505 subsequently executed/passed the offscreen method in the
configured UI gate: 249 executed/passed, zero failures/errors/skips. Its JVM/build gate
executed 5491, passed 5489 and failed two, zero skipped; both failures passed the exact
2/2 comparison. All nine phone-teardown and 29 connection methods passed there.
[Accepted shared-fixture evidence](../../recorded-tests.md#accepted-shared-fixture-evidence-2505)
records the verifier PASS, original failures, actual identities and cleanup observations.
This acceptance carries the shared repair; #2360 and other tickets keep their own holds
and evidence reconciliation. Linux was outside this macOS verification scope.

Permission isolation and operator bypass remove their host during scenario cleanup,
before the activity fence. Rendered witness text and peer turn end do not prove
final phone catch-up completion. In #2484, host removal reached
`RelayConnectionRegistry.reconcile` with request 82/checkpoint 33 pending; the
step-39 close mismatch was primary, and test-end rethrew it.

Keep phone producers alive: close the peer, call
`RecordedSuiteNative.beforeHostLinkClose(serverId)`, then remove the host.
`withOperatorBypassCleanup` delegates this order to `withPermissionIsolationCleanup`.
The replay fence retains `SuiteConnections.beforeClose`'s 30-second bound and waits
for required requests, delivery and downstream consumption. Capture and ordinary
execution use a no-op fence. Ownership, payloads and complete tape consumption stay
strict. Attempt every cleanup action; preserve the scenario throwable or first
cleanup failure, suppressing later failures without self-suppression. A fence
failure still removes the host and fails; it cannot repair an earlier dialog failure.

[Permission-isolation evidence](../../recorded-tests.md#counted-macos-cleanup-evidence-2026-10-21)
retains #2349's original failures and accepted comparisons.
[Operator-bypass evidence](../../recorded-tests.md#operator-bypass-host-removal-2484)
retains the controlled 2/2 failures before repair, 2/2 passes afterward, focused
native 1/1 pass and the target's pass in the configured UI gate (333 executed,
332 passed, 1 failed, 1 skipped). Verifier PASS accepted the unrelated #2368
same-tree rerun; focused passes alone do not prove full-gate acceptance. Both
repairs were verified on macOS; Linux was outside scope.

### Graph-restart replay evidence (#2347)

Explicit graph restart needs a [current-phone readiness fence](../../recorded-tests.md#explicit-graph-restart-readiness-2347)
before Activity destruction. Final teardown can include replacement generations;
waiting for those during restart would prevent relaunch. Peer completion alone
does not certify phone catch-up. In the original macOS sweep at
`77b5717d6d3a1dba9f1a92a251162e7f9b3770e2` (1815 executed, 1810 passed,
5 failed, exit 1), retrieval first rejected step 16: generation 0 closed during
`restartApp` → `rebuildGraph` → registry disposal with its version-20 catch-up
still required. Later reconnect/cleanup timeouts propagated that mismatch. This
was a replay lifecycle defect before the retrieval cut, separate from #2219.
Unchanged-main focused replay passed 1/1; that alone proved no suite independence.

Both unfenced controls retained 26 executed, 23 passed, 3 failed, 0 skipped,
exit 1, including an actual premature close. Repaired connections passed 26/26,
strict tape 44/44 and supervisor 38/38, each failed/skipped 0, exit 0. All three
named `phoneRestart` regressions executed and passed in the later JVM gate.

The accepted verifier run `96e226dbfd57508fb601ca933ec7767f8327b4e2862b994afe3945679120c05a`
reviewed `7f5367895698866352ef7ea10ca9b26dd48595ed` on macOS arm64/API 33,
after merge `8a8b2c138` integrated #2349. Retained results are:

| Check | Executed | Passed | Failed | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Focused native retrieval | 1 | 1 | 0 | 0 | 0 |
| JVM/build gate | 6378 | 6378 | 0 | 0 | 0 |
| Configured UI gate | 335 | 334 | 1 | 1 | 1 |
| Same-tree model rerun | 1 | 1 | 0 | 0 | 0 |
| Configured scripted-all gate | 21 | 20 | 1 | 1 | 1 |
| Same-tree session-error rerun | 1 | 1 | 0 | 0 | 0 |

Skipped cases are additional to executed counts. The configured commands were
`python3 scripts/jvm-test-gate.py --with-build-checks` and
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py` with `ui`
and `scripted-all`. Focused native replay used `ui --tests` with
`de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`.
That exact method passed focused (4.793 seconds) and in the complete UI gate
(3.979 seconds), once each without failure or skip. Permission and both other
restart consumers also passed the UI gate. The UI model failure is tracked by
[#2368](https://github.com/pyrycode/pyrycode-mobile/issues/2368); the scripted
session-error failure by [#2353](https://github.com/pyrycode/pyrycode-mobile/issues/2353).
Verifier PASS accepted the dispatcher's same-tree reruns while retaining both
original failures. UI rename capture and scripted durable-gap remained skipped,
never counted as passed or used for retrieval acceptance.

Raw baseline XML, phone diagnostics and original sweep logs were retained under
`$AGENTS_REPO_PATH/logs/evidence/2347/builder-20261021-restart/`; integrated
focused evidence under `rework-20261021-permission-integration-7f5367895/`
beside it. The accepted run's downloadable bundle holds
`handoff/verifier-2347.md`, counted XML in `logs/verifier-gate__2347_5.log` and
`_6.log`, and the two timestamped rerun logs. These identify retention locations
at review time; the counts above survive pruning. Complete configured acceptance
is resolved by this newer verdict; a later main sweep remains dispatcher-owned.
Linux was outside scope. See [the preserved attachment assertions](attachment-retrieval.md#interrupted-retrieval-restart-replay-2347).

### Preference ownership across ordinary tests and replay

`E2eTestApplication` owns one process-lifetime `DataStore<Preferences>` for
`app_prefs`, bound through a fresh override module in both initial startup paths
and every graph rebuild. Ordinary navigation tests also load the shared
`appModule` value into isolated Koin graphs. Closing such a graph clears shared
singleton factories without cancelling an already-active DataStore's I/O scope.
Without the Application override, the next application lookup can create a second
owner of the same file. Carrying a store only during rebuild misses this initial
graph boundary. Keep the production after-graph-close initialization contract
covered by `AppPreferencesTeardownTest`; graph closure alone does not release
DataStore file ownership.

Replay restores arguments, pairings and preferences before rebuilding the ordinary
graph. Seeded fixtures have a second cleanup boundary: restoring their original
raw DataStore snapshot does not increment `ObservablePairedServerStore.revision`.
The ordinary registry created after replay can therefore retain a synthetic host
whose persisted pairing has been removed. In `finally`, restore the snapshot and
rebuild the ordinary graph on main with no Activity alive, including on assertion
failure. A regression must first await the synthetic host in the registry, then
check connection release and exact original preferences, pairings and registry
hosts; persisted-state assertions alone can pass while a connection leaks.
See [replay isolation](../../recorded-tests.md#replay-isolation).

### Linux ownership repair evidence (#2208)

The fresh baseline at `a0c7db693245cce59d67377f672d2bec4c7dca65` reproduced
113 executed, 110 passed and three failed tests. Background-agent and context-footer
failed during duplicate-store replay setup. Delete's empty failure was an
instrumentation process-crash interruption; no independent product assertion
failure was established. The controlled ownership regression failed once before
repair (one executed/failed, zero passed/skipped). Two later registry-cleanup
probes also failed before their fix (two executed/failed, zero passed/skipped).

Final tested Mobile source was `3f4703e08ede017f69e19ba5109762bf94f88035`,
with agents `49cd6dd07c163a7d0e9583850aafb28378ec0b3c`, on Linux x86_64.
The complete command was
`UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui`:

| Selection | Selected/reported | Executed | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Complete profile, shared tests and both managed-device shards | 1694 | 1692 | 1692 | 0 | 2 |
| Shard 0 | 883 | 883 | 883 | 0 | 0 |
| Shard 1 | 811 | 809 | 809 | 0 | 2 |
| Separate focused isolation class plus three original failures | 8 | 8 | 8 | 0 | 0 |

All 71 `RecordedMobileSuiteTest` scenarios and five
`RecordedSuiteIsolationDeviceTest` methods executed and passed, including
`interactiveTurn_backgroundAgent_followsBottomUntilFinished`,
`interactiveTurn_pingPrompt_footerShowsContextUsage` and
`interactiveTurn_deleteConversation_removesFromListAndClosesThread`.
Selected identities, instrumentation outcomes, both shard XMLs and joined XML
reconciled exactly without duplicates or missing cases. The two existing skips
were `RenameDialogCaptureTest#renameAtFigmaViewport` and
`ComposerPasteTest#pastingAnImageUri_reachesTheAttachmentPath_andLeavesTheDraftEmpty`
(Robolectric-only). No skips, acceptance retries or timeout increases were added.
All 1694 retained phone logs contained zero duplicate-DataStore errors; no
inherited application failure was found in this complete profile. The shard-1
progress counter showed 813/811 because it counted the skips twice; XML establishes
the completed inventory.

The [committed evidence index](../../../app/src/androidTest/assets/datastore-2208/evidence.txt)
retains commands, source revisions and counts. Raw XML, instrumentation reports,
phone logs, reconciliation and hash manifests were retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2208/rework-full-local-3f4703e08/`.
The separate managed `mobile-in-depth` task failed during preparation with zero
tests because its cleaned environment omitted the agents path; it is not passing
evidence. [Dispatcher #294](https://github.com/pyrycode/agent-dispatcher/issues/294)
tracks that launcher defect. The complete local profile above ran at the same
pushed candidate. macOS was outside this ticket's verification scope.

### Selection and execution

The `ui` gate skips itself, exiting 0 with `Android gate: ui skipped` on stderr and
no XML, when every path the branch changes since it left `main` is under `docs/` or
`scripts/`, is Markdown, or is one of the e2e-only sources listed in
`E2E_ONLY_SOURCES`: the live and scripted stream tests and the second-client peer.
The rest of the e2e package stays in: the instrumentation runner, the test
application and the unrecognised-row sentinel serve every device test. A skip is
expected on such a branch and is not missing evidence; the scripted scenarios and
the live gate still run. `UI_GATE_FULL=1` forces the suite. A unit test fails if
any other source starts using one of the listed classes.

Every device-using mode (`ui`, `scripted`, `scripted-all`, `live`) holds one
host-wide lock on the Gradle-managed AVD before it drives an emulator, so two
runs from different worktrees never share the device at once (#1071). The lock
is a kernel `flock` on a file beside `avd/gradle-managed` under
`ANDROID_USER_HOME` (or `~/.android`), so every worktree and checkout on the
host contends for the same file regardless of where it runs from. A waiting run
prints who holds the device on stderr and, once it acquires it, how long it
waited. `ANDROID_GATE_WAIT_SECONDS` (default 300) bounds that wait; a run that
gives up exits 75 without starting the device task or the e2e harness, naming the
holder's mode, worktree and start time — that exit code means a busy device,
not a test result. A `ui` run that skips itself (above) takes no hold. A direct
`./gradlew …AndroidTest` run bypasses the script and does not take the hold.

Every mode builds the app and test APKs before it queues for the hold, so the
hold covers only boot, install and run (2026-10-05). The e2e modes build with
`-PuseRelayRepository=true`, as `scripts/e2e-emulator.sh` does, and set
`E2E_APKS_BUILT=1` so that script skips its own build. A failed build exits 1
without taking the device. `scripted-all` installs both APKs once on the emulator
it boots. Before each scenario it clears the app's data and grants back the
runtime permissions the install granted, then the harness runs the
instrumentation directly instead of Gradle's device task;
`scripts/instrument-report.py` turns its output into the JUnit report, and a crash,
an unfinished test or an empty run fails. If the install or a clear fails, the
remaining scenarios install through Gradle as before. The `ui` and scripted runs
pass `disableAnimations=true`: `E2eInstrumentationRunner` then sets the window,
transition and animator scales to 0 on an emulator for the run and restores them
when it finishes. The live run keeps animations.

`FocusRecordListener` (`app/src/androidTest/.../e2e/FocusRecordListener.kt`,
registered through the `listener` instrumentation argument in
`app/build.gradle.kts`) logs what the window manager reports when a device
test fails, so a recurring focus failure (`RootViewWithoutFocusException`, an
IME/paste/back `waitUntil` timeout) can be diagnosed instead of re-triaged as
pre-existing (#1131). It never changes a test's result or hides its original
exception; a failure while recording is itself logged and the run continues.
The gate script prints the record to **stderr**, not stdout, so it survives
worktree removal and never touches the dispatcher's XML:
`Android gate: focus record for <Class#method>: …`. The same line is also in
the kept per-test logcat under tag `FocusRecord`. A passing run has no
records — the listener only runs on failure — so a passing gate prints
nothing extra. Fields: `focus` is the window holding input focus,
`focusedApp` the focused activity record, `anr` any
`Application Not Responding: …` window titles or `none`, `activities` each
activity the failing test opened with the last lifecycle stage it reached
(`none` if it opened none), and `error` means recording itself failed (the
other fields are absent). The record is read just after the failing test's
rules tear down, not at the exact instant of failure: an
`ActivityScenarioRule` or Compose rule has usually already closed the test
activity by the time `testFailure` fires, so `focus` and `focusedApp`
commonly name whatever the teardown left focused, on the managed emulator
`com.android.fakesystemapp/.launcher.EmptyHomeActivity`, not the test
activity. Read `activities` first: `DESTROYED` means that launcher focus is a
teardown leftover and the test failed on its own assertion, so read the
assertion, not the focus. `RESUMED` beside a foreign `focus` is a real loss
of focus. #1809 is the cost of skipping this: 38 failures on the main sweep,
every one showing the launcher, were triaged as a shared focus fault when
they were density and Robolectric-size assertions (fixed by #1808). A
system dialog holding focus over the test is still captured. A
crash dialog (`Application Error: …`) shows under `focus`, not `anr` — the
first real record (#1131) found one blocking `com.android.bluetooth`, not an
ANR. The listener remains diagnostic. Separately, `E2eInstrumentationRunner.quietSystem`
now disables emulator Bluetooth, hides system crash dialogs for the run and closes existing
system dialogs (shipped at `186c399b`). It restores the dialog setting on finish and leaves
Bluetooth off. The [earlier-Other evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence)
shows the unchanged IME method passing even during a native Bluetooth crash; this mitigation
does not establish that the emulator service itself is repaired.

## Full-cap storage proofs

Ordinary `./gradlew test` excludes the four `FullRetentionTest` cases that repeatedly
rewrite and restore 100000 disk rows. Fast retention policy, cache and history tests still run.
Run `./gradlew test -PfullRetentionTests=true` after changing cache retention or saved history
position handling. The flag includes those proofs in the ordinary suite.

Pipeline implementations and their tests live in `pyrycode-mobile-agents/scripts`.
The existing product script paths are compatibility launchers.
Deploy the agents checkout first and set `AGENTS_REPO_PATH` for a non-sibling installation.
Test the moved tools with the agents repo's `bin/test-mobile-tools /path/to/pyrycode-mobile`.
