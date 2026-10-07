# Development verification — Compose evidence

Split out of [Development verification](development-verification.md) on 2026-10-02 to keep that
document under the 50000-byte size cap the docs guard enforces. Every section below moved here
verbatim and kept its heading, so its anchors are unchanged. Part of
[Development verification](development-verification.md); see that document for the other topics
and its links.

## Compose evidence

Compose tests should assert the contract independently of the implementation.
Give a row, state and callback a value that the production code cannot derive from
the expected assertion. For a visible transition, assert the state before the
action, wait for a positive effect of that action, then assert the resulting
absence or replacement. Use `useUnmergedTree = true` when a merged semantics
container hides per-row text or controls.

For overlapping chrome, semantic display and full list containment do not establish
readability. Measure the clear area between the header and composer and assert
focused fields, tested button edges and swipe coordinates against those bounds.
Content padding reserves end positions but does not narrow Compose's relocation
viewport; a field fully behind a bar can otherwise be considered already in view.
Scope `LocalBringIntoViewSpec` to the containing list and restore local specs for
nested message scrollables and the field's own well, or those small scrollables
inherit the outer chrome reservations. Check an already-readable field's physical
anchor on every frame, as well as focus from behind both bars and composer resizing.

`performScrollTo` uses the drawing viewport, so it can leave a target under chrome.
Wait for window focus after display resizing, move the physical click target into
clear bounds, assert the selected state, and verify action edges before tapping.
A chrome pointer node can exclude underlying siblings without consuming child
movement; gradual attachment swipes and composer selection drags must cross touch
slop successfully. A tap-only test misses cancellation of those recognizers.

The [thread chrome evidence](../../../app/src/androidTest/assets/chrome-1646/README.txt)
uses full `pixel8Api35`, real bars and hardware framebuffer captures for progressive
backdrop blur; JVM pixels or synthetic-inset geometry cannot prove it. It retains
explicit underlap at both bars, four current-Figma comparisons, viewport/inset
sidecars and IME before/open/dismissed/reopened states at newest/history anchors.
The [final #1646 verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/1700#issuecomment-5977891679)
records the fresh complete gates and confirms the earlier-field real-IME
open/dismiss/reopen method passed. See [thread testing](thread-screen-testing.md#testing)
for executed/failed/skipped counts and named methods. Those full-suite passes
resolve the retained focus-repair README's historical failed/busy attempts; they
are not evidence of a separate focused device rerun.

The [pairing chrome evidence](../../../app/src/androidTest/assets/pairing-chrome-1648/README.txt)
retains all three onboarding surfaces, Figma exports fetched on 2026-10-04,
header comparisons and real-inset sidecars. Its focused full `pixel8Api35` run
selected `PairingHeaderCaptureTest` and `ScannerFrameTest` with real bars required;
[api35-green.xml](../../../app/src/androidTest/assets/pairing-chrome-1648/api35-green.xml)
records 4 executed/passed, 0 failures/errors and 0 skipped. The named method
`PairingHeaderCaptureTest.threePairingHeadersKeepTheirGeometryAndBackActions`
is present and passed, as are all three scanner frame methods. It checks actual
pointer Back routing, 48 dp targets, title/rule offsets and Denied's absent rule.
Sidecars record MainActivity, API 35, 412×892, density/font scale 1, hardware
acceleration, `syntheticBars=false` and real 24 px status/navigation bars.

These real-bar hardware pixels establish appearance and sharp foreground;
ATD or injected-inset checks establish geometry without proving hardware blur.
Smooth pairing backgrounds cannot independently quantify progressive blur radius:
verified reuse of the unchanged shared effect relies on the thread's underlap
evidence above. The [#1648 verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/1741#issuecomment-5979693606)
used retained Figma exports; it did not independently refresh the remote frames.

For hardware-keyboard button tests, request `InputMode.Keyboard` through
`LocalInputModeManager` after composition and before requesting focus. Establish
it separately in the launcher and dialog windows. Assert launcher focus before
Enter opens the modal, then assert Tab containment, action activation and focus
restoration after dismissal. A failure before opening the dialog does not test
its focus-restoration contract. See [the shared mobile modal](mobile-modal.md#focus-and-verification).

ATD images omit LatinIME; editable focus or `performTextInput` alone does not
establish that a software keyboard is visible. See Android's
[removed ATD components](https://developer.android.com/studio/test/managed-devices).
`MobileModalTest` supplies a real `MobileModalTestIme` in the test APK, using Java
and Android framework classes because its standalone service process cannot rely
on Kotlin/Compose libraries supplied only by the target APK during
instrumentation. The service declaration requires `BIND_INPUT_METHOD` and remains
under `app/src/androidTest`.

For a separate dialog, translate dialog-root bounds into screen coordinates before subtracting the Activity's effective `DesignCapture.insets()` status inset. Platform root insets alone omit ATD's synthetic bars and can turn a correct 312 dp screen-area origin into 336 dp. This geometry calculation is separate from proving real bars: retain `requireRealSystemBars=true` and nonblank pixels for visual evidence. Fresh managed-device captures may be in `app/build/intermediates/managed_device_android_test_additional_output/` while a legacy outputs folder still holds older ATD sidecars. Check timestamps, API and `syntheticBars` before retaining PNGs and metadata together. See [Delete evidence](../../../app/src/androidTest/assets/design-1220/list/1861-delete-evidence.txt).

ATD can also report zero physical system-bar insets and return black framebuffer
captures while geometry assertions pass. `MainActivityInsetsDeviceTest` injects
24 dp bars only when both reported bars are zero, preserves incoming IME insets,
and records `syntheticBars` with density and measured insets. That path supplies
geometry metadata only. For visual evidence, run the same class on the full
`pixel8Api35` (`google_apis_playstore`) image with
`-Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true`;
it requires nonzero real bars and rejects blank PNGs. Let display configuration
settle before launching the Activity. The
[committed captures and measurements](../../../app/src/androidTest/assets/insets-1149/)
show 412×892 and 360×800 dp; synthetic geometry alone cannot prove pixels.

For thread keyboard regressions, exercise a populated thread through opening,
dismissal and reopening in the real activity. Initial opening alone missed the
pan in #1166; an empty-thread comparison also failed to establish the reported
case. `MainActivityInsetsDeviceTest.populatedThreadKeyboardAt412By892` and
`populatedThreadKeyboardAt360By640` create a temporary fake channel with 30 fixed
messages. The usual demo's animated streaming message can confound scroll-anchor
checks. Establish newest and older anchors before each preservation cycle, leave
messages unchanged, and do not send or manually scroll until draft preservation
and dismissal's index/offset restoration have been checked. Then prove touch
scrolling with the IME open. Check the header in screen coordinates, actual IME
visibility and positive inset, visible messages, enabled send reachability and
the normal 16dp footer gap on both opening and reopening.

The [#1166 captures and XML](../../../app/src/androidTest/assets/insets-1166/)
retain before/open/dismissed/reopened states at 412×892 and 360×640 dp in static
dark, static light and light with wallpaper colours enabled, for both anchors.
They use the full `pixel8Api35` image and `requireRealSystemBars=true` described
above. The [baseline](../../../app/src/androidTest/assets/insets-1166/baseline/)
intentionally retains two failures with the unspecified soft-input policy: the
Back/header control disappears on reopening. Both regressions pass with the
[explicit activity policy](thread-input-bar.md#ime-handling--modifierimepadding-moved-to-the-composer-column).
When changing activity-wide soft-input or inset handling, also rerun
`MobileModalTest.ime_keeps_focused_field_final_item_and_actions_reachable` and
`landscape_ime_keeps_focused_field_following_content_and_actions_reachable`;
the retained `api33-green.xml` includes both alongside the activity checks.

Selecting an IME after the Compose rule launches its activity can recreate that
activity and dispose content installed with `setContent`. `MobileModalTest` uses
an outer rule (`order = 0`, selected by `@WithTestIme`) to select the IME and drain
main-thread configuration delivery before the Compose rule (`order = 1`) launches
the host. Its `finally` restores the previous IME selection and the test IME's
enabled state after host teardown. A guarded view read or longer timeout cannot
revive a disposed dialog. Once the host is stable, wait on the UI thread for the
captured dialog view to initialize and gain window focus, focus the field and
show the keyboard. Assert actual IME visibility and a nonzero inset as well as
displayed content and footer bounds above the keyboard; a scroll test
with no keyboard leaves that contract untested.

The same ordering hazard applies to `wm size`/`wm density`, not only IME
selection: a `@Before` method already runs after the Compose rule's activity is
up, so a resize there (or a second one in the test body) can recreate or
refocus the activity while the launcher briefly holds focus, surfacing as
`IllegalStateException: No compose hierarchies found` (#1402).
`ThreadActivityIndicatorCaptureTest` moved its resize into the same
`order = 0` `TestRule` shape as `MobileModalCaptureTest`: apply density 160 and
the test's final size, wait for idle, run the test, then restore both in
`finally`, with the compose rule at `order = 1`. Where sibling tests in a class
need different final sizes, read the size from a private runtime annotation on
the test method (`@Viewport("320x692")`) instead of branching on the method
name, so the size stays attached to the test it belongs to.
`ToolRowDesignCaptureTest` (#1425) copied the same shape for its own
412x892/320x700 pair, and `MarkdownReaderCaptureTest` (#1467) copied it again
for the same pair. #1430 extracted the copy into a shared
`app/src/androidTest/java/de/pyryco/mobile/design/ViewportRule.kt` with a
public `@Viewport(size, fontScale)` annotation;
`ThreadActivityIndicatorCaptureTest` and `ToolRowDesignCaptureTest` now use it
unchanged otherwise. `MarkdownReaderCaptureTest` and other androidTest capture
classes still carry their own copy of the rule; moving them onto the shared
rule is separate work.

`BackgroundTaskPanelCaptureTest.compactLargeTextKeepsScrolledContentAndCloseGlyphReachable`
(#1783) likewise uses the outer shared `ViewportRule` and a method-level 320x640 viewport,
so setup precedes Activity launch and restoration follows teardown. A failed capture with
“No compose hierarchies found” and destroyed Activities does not establish a panel-layout defect;
keep the width, scroll and close assertions while repairing viewport execution order.

`AttachmentVisualCaptureTest` (#1555) moved onto the shared rule too, but its
geometry test needed two different viewports (412x892 and 320x640), so it
split into one `@Viewport`-annotated method per size, each calling a shared
private helper for the fixture and thread state. Applying the bare
`ViewportRule` would also have forced 412x892 at density 160 onto
`fileArtworkAndLabels_haveReadableContrastOnBothBubblesAndThread`, whose pixel
regions were measured at the device's own density and which makes no viewport
change of its own; a local wrapper `TestRule` gates `ViewportRule` on the
`@Viewport` annotation so only the two geometry methods get it. A class mixing
viewport-dependent methods with device-density pixel assertions needs this
gating, not a class-wide rule. The same package holds the
[`design-1220/` capture harness](../../../app/src/androidTest/assets/design-1220/README.md):
`DesignCapture` (launch, real-bar capture, keyboard and menu helpers) and
`DesignInputs`, a Koin override loaded over the app graph that redefines
`ThreadViewModel`, `ScannerViewModel` and `PairCodeViewModel` so a capture test
can drive any state a Figma audit needs, with no file under `app/src/main/`
changed. Five lessons from building it: Koin 4.0.4's `loadKoinModules`
overrides a `viewModel` definition the same way it overrides `single`, keyed
by type and qualifier; restore by reloading copies of only the overridden
definitions, not by reloading `appModule` itself, which would re-create its
`createdAtStart` eager singletons (the relay registry, the lifecycle driver);
revoking a granted runtime permission such as `CAMERA` or
`POST_NOTIFICATIONS` kills the app process, and the instrumentation with it, so
a capture rule's grant cannot be undone in `finally`; `ThreadViewModel` reads
`questionBatch` only when it gets no `QuestionDraftStore`, so an override that
passes the app's store, as production does, silently drops a test-supplied
question batch; and no thread code reads
`ConversationRepository.observeAttachmentOffers`, so overriding it changes
nothing on screen.

`PromptsDesignCaptureTest` (#1433) audited the questions and permissions board
through this harness and found more: both prompts set `FLAG_SECURE`, which blacks
out `DesignCapture.capture`'s `UiAutomation` screenshot, so a protected surface
needs its own decor-view draw, asserting the flag rather than only recording it.
`DesignCapture.insets()` substitutes synthetic bars for a zero-inset ATD read, so
a capture that bypasses `capture()` for its own draw must read the decor view's
real root insets itself — otherwise a real-bars check can never fail. A capture
also needs a settled frame: right after launch the emulator can report a
transient navigation bar, and right after `FLAG_SECURE` is set it can report no
insets at all for over a second, each silently shifting the layout rather than
failing the test; wait until the bar insets hold steady before drawing, and
assert the test IME's exact inset (240 px) rather than trusting that a keyboard
opened. Compare spacing and sizes against Figma's `get_metadata` coordinates, not
by eye — `minimumInteractiveComponentSize()` wrapping a smaller control inside
`spacedBy` doubles the visible gap between items, and that reads as "match within
4 px" on a visual scan. The harness's fake repository outlives each test in the
process, so channels created per test accumulate and duplicate list rows across a
class; reuse or remove what a test creates instead of assuming a clean list. When
judging whether two capture PNGs across a rerun are the same evidence or stale
leftovers, a byte-identical pair from a deterministic decor-view draw is
expected, not proof of staleness — check provenance in the paired `.txt`
metadata (for example a field like `syntheticBars` that only a rewritten run
carries), not the PNG history. A whole-class run also stops reproducing
*other* tickets' captures byte for byte once `main` has moved since they were
taken — font, spacing or copy changes elsewhere in the fixture ride along with
every method in the class. #1502 added three edge-state methods on a `main`
that had moved since #1501's run; copying the whole new run's output back in
would have overwritten #1501's committed captures with drift that was not
this ticket's to judge. Copy in only the methods the ticket owns and say so
in the index, leaving the rest of the committed evidence as an earlier
ticket's.

`ListDesignCaptureTest` (#1431) audited the list-side surfaces (Channel List,
Archive, Channel Info, Settings, Edit host) through this harness and found
three more. A modal is shown in its own `Dialog`, so the activity window never
takes focus while it is up and `DesignCapture.openKeyboard`'s wait for that
focus times out; drive the keyboard from the dialog's own view instead, read
from `ViewRootForTest.view` on a node inside the modal. Clicking a tab or
control that is already selected leaves the capture identical to the one
before the click — `ArchivedDiscussionsViewModel` always opens on
`ArchiveTab.Discussions`, so a later click on "Discussions" was a silent no-op
that compared the wrong tab against Figma; before a capture, assert content
that only the target state shows, not just that the click happened. And in a
frame with no status bar, measure whether the top bar moved with the content
before calling an offset a row offset — the list's rows read as "20 px low"
against the frame until the 24 px status bar was subtracted, which left the
real error as the whole screen sitting 4 px high, top bar included, not the
rows underneath it. Status-bar icon appearance is not safe to carry into a
Colour verdict either: it differed between runs and even between two captures
of the same surface in one run (`archive.png` against `archive-compact.png`),
so re-read it from the committed capture immediately before writing that
verdict rather than trusting an earlier run's reading.

`ListDesignCaptureTest` (#1504) extended the same walk to the states `670:5299`
added frames for — Edit channel retagged, Edit chat, Create channel, Rename,
Save as channel, the Delete confirmation, three host-row link statuses and
Archive's two empty tabs — and found three more traps. First,
`waitUntil { hasSetTextAction() nodes exist }` after opening a modal from
inside a thread passes at once, because the thread's own composer is already a
set-text node; wait on the modal's own label instead. Second, a `null`
repository on a `HostConversationConnection` gives a host row with no
conversation rows underneath it, which is what let the `PairingRejected` and
`UpdateRequired` host rows for this audit stand up without a second fake.
Third, a dialog capture's `imePx` reading can disagree with the image: the IME
can arrive between `DesignCapture.capture`'s inset read and its
`takeScreenshot()` call, so a modal captured right as its text appears can
record `bottom=0` while the screenshot already shows the keyboard, or the
reverse. Settle the keyboard state explicitly before capturing a dialog-window
modal — as the Edit host steps already do with `awaitModalFocus` /
`awaitModalKeyboard` / `pressBack` — rather than reading the race as a
structural limit of dialog-window sidecars.

`ThreadDesignCaptureTest` (#1432) audited the thread, composer and thread
status states through this harness. It runs in the UI gate on ATD, so a change
to a thread panel, menu or status band can break it. Header-menu captures use
`DesignCapture.openHeaderMenu` and await the always-present Channel info row (#1666). A same-window overlay adds no popup root; a focused composer’s text-selection handle
can add one independently, so root counts cannot establish that the header menu opened. After an image
attachment reaches its ready state, also wait for the decode's indeterminate
progress indicator to clear, or the bubble is captured with a spinner instead
of the photo. Re-export every frame with `get_screenshot` at capture time and
diff it against the earlier export: the Mobile page changed nine thread frames
and gained a section while this audit ran, which cancelled verdicts written
against the older exports. Check a shared-row verdict, such as one table for
the composer and footer across every thread frame, against each frame's own
node data, because disconnected frames restyle shared parts. The Connecting,
Reconnecting and Offline frames dim the staged PDF tile that the connected
frame draws in primary, and the shared verdict hid that until the third review
(#1532). Measure before writing "match": that review re-measured accepted
verdicts and found a 50 px bubble width (#1513), a 14 px list indent (#1533)
and a 3 to 9 px panel drift (#1534) that a visual scan of the side-by-sides had
passed. `scripts/design-compare.py` resizes the Figma export to match the app
image's size, so comparing a component export against a full-screen capture
stretches the export across the whole frame instead of lining it up with the
row. The #1540 refusal-row states crop the capture to the component's own
width and height first and compare that crop against the export; when the
app's row is taller than the component (the switch-back failed line sits
lower than Figma's), pad the export to the crop's height instead of letting
the script resize it.

After capturing a visible snackbar, end its state explicitly before capturing
the next state. Under the Compose test rule, Material3's short snackbar's 4 s
coroutine delay uses virtual time, while `waitUntil` enforces a wall-clock timeout
and advances virtual time only one 16 ms frame per poll. Each poll also sleeps
and synchronizes with Espresso, so roughly 255 polls can exceed a 15 s budget
under sharded emulator load even when focused runs pass. This is a test-clock
dependency, not a production snackbar fault (#1664).
`ThreadDesignCaptureTest.dismissSnackbar(text)` selects the node defining
`SemanticsActions.Dismiss` with a descendant containing the snackbar text,
invokes that accessibility action after the visible-state capture, then requires
text absence within 5 s before the following capture. This preserves the
snackbar frame without waiting for timer expiry or loosening comparisons.

The [#1664 verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1670#issuecomment-5972292335)
and its fresh two-shard XML report 171 executed, 0 failed and 1 skipped;
`ThreadDesignCaptureTest#rowAndNoticeFramesAt412By892` and
`ThreadDesignCaptureTest#refusalStateFramesAt412By892` both executed and passed.
That run establishes the text transitions, not fresh pixel comparisons:
`DesignCapture.capture` writes metadata and returns before taking a screenshot
when `syntheticBars=true`. Capture names, Figma node IDs, comparison assets and
tolerances stayed unchanged, but no fresh PNGs were produced. Check the metadata
before treating a passing ATD capture method as visual evidence; real pixel
evidence requires real system bars and `requireRealSystemBars=true`.

`MarkdownReaderCaptureTest#compactLargeTextKeepsControlsAndBodyReachable`
(unrelated to the #1352 history-paging change, caught in its PR's UI gate and
triaged there) hit the same `wm size` race, confirmed by two focused re-runs
both passing 2/2 — the race is intermittent, not a property of the test
itself, so a single red run proves nothing about whose change caused it.
Filed as #1467 and fixed there: the class moved its resize into the same
`order = 0` `TestRule` shape, reading `compactLargeTextKeepsControlsAndBodyReachable`'s
320x700 size from `@Viewport("320x700")`, with the compose rule at `order = 1`.
`ComposerFieldCaptureTest#compactLargeTextKeepsSendReachable` and
`ChannelInfoCaptureTest#channelInfoScrolledMatches668_5460` hit the same race
again (#1654's sharded UI gate: both failed with "No compose hierarchies
found", both passed 1/1 on a focused re-run against the merge base and the PR
head). Fixed in #1661: both classes dropped their own `@Before`/`@After`
`wm size`/`wm density` pair and private `shell`/`overrideOf` helpers for the
shared `ViewportRule()` at `order = 0` ahead of `createComposeRule()` at
`order = 1`, with `@Viewport("320x692")` on `ChannelInfoCaptureTest`'s two
compact methods and `@Viewport("280x400")` on
`compactLargeTextKeepsSendReachable`. Other androidTest classes still resize
under a running activity, for example `ScannerLivePreviewDeviceTest` — if
"No compose hierarchies found" shows up there or elsewhere, this same
conversion is the first thing to try.

A whole-tree text assertion in a capture test can match seeded thread
content, not just the control it means to check. `ThreadDesignCaptureTest
#openRunConfiguration()`'s `onAllNodesWithText("Default", …)` asserted no
node anywhere contained "default", so it failed whenever the seeded reply
"Workspace picker — default to the current cwd …" was on screen at 320×700
above the expanded run-configuration rows — intermittent, because it depended
on where the thread list happened to be scrolled (#1654, unmasked by #1644).
Scope a tree-wide "absent" check to the surface under test instead:
`hasText(..., substring = true, ignoreCase = true) and
!hasAnyAncestor(hasTestTag("thread-message-region"))` excludes every thread
message while still covering the run-configuration sheet and footer, which
both sit outside that region.

Reply assertions must not depend on total substring-count growth: removing queued
prompt text can offset a newly displayed assistant reply. For fresh discussions
sending only `PING_PROMPT`, `awaitDisplayedPingReply` matches exact,
case-insensitive `ping` under a scrollable ancestor in the unmerged tree and waits
for display. Exact text excludes the full prompt; list scope excludes the title
and backlog. This is a constrained-prompt matcher, not an assistant-role detector.
`PingReplyTest` checks both no-reply states (with and without queued text), then
replaces the queue with a displayed reply while the substring count stays equal.

Off-screen lazy-list content can still exist in semantics. Establish a viewport
before asserting non-display; merely appending a row does not establish it.
`SessionBoundaryVisibilityTest` uses separate Markdown paragraphs to prove a
finalized wrap-up exceeds the viewport, appends the delimiter, scrolls to the
wrap-up at reversed index 1, and asserts the explanation is not displayed.
`awaitDisplayedSessionBoundary` then scrolls to the newest row (index 0) while
waiting and asserts display. Keep the pre-action absence guard separate from this
post-append non-display check. Both regressions run outside the excluded `e2e`
package; see the [LIVE coverage](../../e2e-interactive-stream.md#live-mode-rung-3-live-relay).
Daemon history can locate a failed step, but cannot prove phone rendering. The
[default/explicit-workspace recheck](../../e2e-interactive-stream.md#verification-status)
demonstrates this distinction: successful daemon replies narrowed the failure
boundary, while passing displayed-reply assertions established phone rendering
after the count-based oracle was repaired.

Compose parameter-order lint is a real gate. Put required parameters before
defaulted ones, and keep `modifier` before trailing lambdas according to the
project convention. A screen can compile while `lint` still fails. Keep
Android-only assumptions out of portable domain models and repository contracts.
Platform adapters such as `data/crypto` and `data/preferences` are intentionally
Android-specific, and should stay behind interfaces.

When a suspend send path catches `IllegalStateException`, rethrow
`CancellationException` first. On the JVM cancellation is an
`IllegalStateException` subtype, so a broad catch can turn teardown into a fake
send failure. Tests should cover both cancellation and the intended failure types.

For `@Composable` signature changes, treat a code graph result as advisory. The
current graph can collapse callers into file self-references and miss a real
consumer. Search all `*.kt` files under `app/src` for the composable name and for
the rendered text being removed. This covers production, JVM tests and
`androidTest` call sites in one pass. Record the graph gap when it affects the
blast-radius decision.

A `BasicTextField(state: TextFieldState, ...)` field (Compose foundation 1.10.4,
found migrating [`ThreadInputBar`](thread-input-bar.md#draft-binding--cursor-at-end-undo-and-redo-885-934)
for #934) is not a drop-in replacement for the `value`/`onValueChange` overload in
tests, in three ways:

- It exposes a `ScrollBy` semantics action the legacy field never did. A selector
  that finds "the" scrollable node with `hasScrollAction()` then matches two nodes
  once such a field and a scrollable list share a screen. Match the list with a
  more specific action instead, e.g. `hasScrollToIndexAction()` or
  `hasScrollToNodeAction()`, if only the list should carry it.
- Undo and redo bypass `InputTransformation` — they write the field's buffer
  directly and never call `commitEditAsUser`. A binding that reports edits only
  through the transformation misses them; see the linked section for the
  `snapshotFlow`/`accountedText` fix this required.
- `SemanticsActions.PasteText`, unlike undo/redo, **does** route through
  `Modifier.contentReceiver` the same way a real user Paste does — both under
  Robolectric and on the device — so a paste test needs no text-toolbar
  workaround.

Robolectric's `KeyCharacterMap` ignores the Ctrl meta state, so a test that sends
a hardware Ctrl+Z to a Compose field there actually types a plain "z" and can pass
green without exercising undo at all. A test that must prove undo or redo — as
opposed to typing or pasting — needs a device.

A device's `ClipboardManager` throws `SecurityException` when a test puts a
`content://` URI on the clip that the calling app cannot read; Robolectric's
clipboard does not model this permission check, so the same test passes there and
fails only on the device sweep (#992). Production code must keep refusing the
app's own content URIs (`AttachmentReader.isForeignContentUri`), so granting the
test app read access is not an option. Guard the device run instead —
`assumeTrue("<reason>", Build.FINGERPRINT == "robolectric")` on the affected
method — and name the device-only harness that still proves the real path in the
skip reason and a KDoc line, e.g. `ComposerImagePasteDeviceTest`, which inserts a
real `MediaStore` image, pastes it and removes it afterward.

On a device, a real drag that ends with the finger still down past a scroll edge
can hold the stretch overscroll effect, which keeps drawing frames — `waitForIdle`
then never returns, because the instrumentation idle check treats those redraws as
ongoing activity. Robolectric draws no such frames, so the same test passes there
while hanging the managed device (`ThreadScreenNewestRowTest`, #992; a thread dump
located the hang inside `waitForIdle`). The fix is
`CompositionLocalProvider(LocalOverscrollFactory provides null)` around the test's
`setContent`, which drops only the visual stretch: drags still reach the list as
`NestedScrollSource.UserInput` through the nested-scroll chain, so a test
asserting scroll-yield or auto-follow behavior is unaffected.

`captureToImage()` → `forceRedraw` waits 2000 ms for a fresh frame and throws
`ComposeTimeoutException` on a loaded emulator, independently of whether the
layout assertions around it already passed — seen four times against
`ScannerFrameTest` and `PairCodeScreenTest` (#1038), including once after
`PairCodeScreenTest`'s existing leading `rule.waitForIdle()`, so waiting for idle
first is not sufficient on its own, and again against `ScannerFrameTest`'s dark
412dp atmosphere pixel check on PR #1428 because that call used raw
`captureToImage` instead of the retry helper (#1441). Any assertion built on a
device pixel capture, not only a screenshot PNG, needs the same protection — a
passing layout assertion next to it does not make the capture itself safe.
Retry through the shared `ComposeTestRule.captureWithRetry(name, node)` in
`app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScreenshotCapture.kt`
(up to three attempts with `waitForIdle()` before each retry, returning `null`
and logging a skip if every attempt throws `ComposeTimeoutException`).
`ComposeTestRule.saveScreenshot` calls it to get the PNG and log + skip rather
than fail when every attempt still times out; a pixel-comparison check should
call it directly and run its assertion only when a bitmap comes back, skipping
with a log line otherwise. Catch only `ComposeTimeoutException` in the helper;
any other exception from the capture should still fail the test. The retry
cannot be proven on a healthy emulator because the timeout does not reproduce
on demand — a focused device run only proves captures still succeed, not that
the skip path fires. `ChannelInfoCaptureTest` still calls `captureToImage` unprotected; that sweep
is tracked under #1046, not fixed here. `AttachmentVisualCaptureTest` stopped
calling it instead (#1555, below).

`AttachmentVisualCaptureTest` (#1555) hit this same fixed 2 s `forceRedraw`
timeout on a freshly booted managed emulator with no resize and no focus loss:
logcat showed the activity RESUMED from launch to teardown, with a frame over
2 s ("Davey") during the device's post-boot work. The launcher focus that an
intermittent `ComposeTimeoutException` leaves in a gate's focus record can be
what the display showed after the failed test tore its activity down at
teardown, not proof of a focus race — read the `LifecycleMonitor`
RESUMED → PAUSED lines in logcat before blaming focus (the #1402 ordering
hazard above is still real; it is just not the only cause of this symptom).
The class removed `captureToImage` entirely rather than retrying it: its
thumbnail wait polls semantics instead of a window capture (the placeholder
file tile's merged node carries a "PNG" text label, the decoded `Image` node
carries none), and its pixel captures draw the activity's decor view into a
bitmap and crop to the target node's `boundsInWindow`, the same approach
`capture()` already used elsewhere in the class.

`captureToImage()` also times out under Robolectric itself, not only on a
loaded emulator: a full `ThreadScreen` set under `createComposeRule` throws
`ComposeTimeoutException` after 2000 ms even with `@GraphicsMode(NATIVE)`,
because the thread's own animations keep redrawing and `forceRedraw` never
settles (`ComposerFileTileTintTest`, #1532). Drawing `LocalView.current` into a
`Bitmap` by hand — `root.draw(Canvas(bitmap))` inside `composeRule.runOnIdle`
— and cropping to the target node's `fetchSemanticsNode().boundsInRoot`, the
way `ConversationTreeRowsTest` does, samples the same real pixels without
going through `forceRedraw` at all.

Shared geometry assertions must convert `boundsInRoot` pixels through the composition's
`LocalDensity` before comparing with dp specifications, and use `assertDpEquals`/`pixelDp`
for edge snapping. `BackgroundTaskStopPanelTest` (#1830) originally passed at Robolectric density 1
while comparing a 28dp chevron directly with pixels; the managed Pixel 2's density 2.625 measured
about 74 pixels. The corrected named method and full five-test class passed on Pixel 2 and
Robolectric. A density-1 pass alone cannot establish portable geometry. See the
[background-task control contract](mobile-modal-callers.md#callers) and
[retained Pixel 2 report](../../../app/src/androidTest/assets/task-stop-1830/pixel2-panel-rework-green.xml).

Shared JVM/device pixel fixtures using fixed pixel offsets must pin Compose density
as well as Robolectric's qualifier. `@Config(qualifiers = "xxxhdpi")` does not configure device density, so a
fixed ring-region pixel offset can sample a different part of a 1dp stroke on the
device even when the JVM assertion passes. `ConversationTreeRowsTest.conversationRow_statusDots_matchRedrawnPaintAndBlink`
(#1679) scopes
`CompositionLocalProvider(LocalDensity provides Density(4f))` to its rows and checks
6dp semantic bounds before sampling centre and ring pixels. This fixes the fixture's
physical sampling scale without changing the device display; it proves component
paint, not full-screen geometry at the device's native density. See the
[list attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878).

## Probe the evidence itself

An injection or sanitizer test must forge the exact line shape its reader matches.
For the unrecognized-row sentinel, the hostile value must include the report's row
prefix, not only a newline. Assert the total report line count as an independent
fence. Temporarily widen the sanitizer, run the test and read the resulting failure
before restoring the guard. This proves the assertion can fail instead of merely
proving that the current implementation passes.
