# Compose evidence

Split out of [Development verification](development-verification.md#compose-evidence)
to keep that file under the search size cap. Compose device-capture conventions, ATD
hazards, IME/resize ordering and the shared capture harness live here.

Compose tests should assert the contract independently of the implementation.
Give a row, state and callback a value that the production code cannot derive from
the expected assertion. For a visible transition, assert the state before the
action, wait for a positive effect of that action, then assert the resulting
absence or replacement. Use `useUnmergedTree = true` when a merged semantics
container hides per-row text or controls.

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
412x892/320x700 pair. #1430 extracted the copy into a shared
`app/src/androidTest/java/de/pyryco/mobile/design/ViewportRule.kt` with a
public `@Viewport(size, fontScale)` annotation; both classes now use it
unchanged otherwise. The same package holds the
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
the skip path fires. `ChannelInfoCaptureTest` and `AttachmentVisualCaptureTest`
still call `captureToImage` unprotected; that sweep is tracked under #1046, not
fixed here.
