# MessageBubble — testing

Test coverage and fixture guidance for [MessageBubble](message-bubble.md).

## Testing

`ThreadStreamingRevealTest` pauses the Compose clock through the real
`ThreadScreen`: pre-open text is immediate, appended text retains its prefix and
reveals progressively, and reopening before catch-up shows all arrived text.
First arrival goes through `ThreadFold` with both empty and historical projections;
a same-key repository replacement must retain partial reveal. A directly built,
correctly timestamped repository fixture misses the live-versus-projection race.
Use enough words to remain partially revealed at the 160 ms checkpoint under
\#1754's word cadence; shortening the checkpoint to 64 ms did not allow reliable
first-arrival layout. Keep prefix, progress and incomplete-text assertions together.

Standalone bubble fixtures omit `threadOpenedAt` and retain zero-start behavior.
For first-arrival timestamp retention and realistic follow fixtures, see
[thread testing](thread-screen-testing.md#testing). The device-only
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_reopenOngoingReplyShowsArrivedPrefixImmediately`
now proves immediate reopen and prefix retention through the isolated daemon/Noise/relay path.
An eventual combined reply can hide a temporary reset through catch-up or finalization: witness
an appended word composing with bounded reveal time, then assert the prefix remains displayed
while the reply is still streaming and its turn non-idle. Release the suffix only after the
reopen assertion, and fence the terminal result separately until after the suffix display
checkpoint. Repository text alone is insufficient. See the
[held-stream sequence and evidence](../../e2e-interactive-stream.md#scenarios-454).
The real-Claude
`InteractiveStreamE2ETest.interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately`
remains ignored and manual/unproven because Claude can finish during navigation; pausing
Compose cannot hold the backend. A full curated live pass does not establish this excluded
method's reopen observation. See the [manual promotion procedure](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

End-to-end word-reveal coverage must witness displayed text while the same reply
is still streaming: a final-body check can pass even if progressive display is broken.
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread`
therefore holds two arrived deltas until the displayed-prefix checkpoint, then uses
an explicit second enqueue to release completion. Require reply identity, repository
settlement and idle phase before checking the final body; a blinking caret's absence
is insufficient. The real-Claude word-reveal twin remains ignored and manual because
its transient window cannot be fenced. Keep cadence and catch-up deadlines in local
step tests. See the [scenario and manual evidence limits](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

`app/src/test/.../components/StreamingRevealStepTest.kt` covers the pure
`nextStreamingRevealLength` helper: a short reply advances one word per 33 ms
step; spaces, tabs, newlines and Unicode whitespace are preserved; a final word
without trailing whitespace is included; an appended snapshot continues from the
retained prefix; empty, finished and whitespace-only inputs reach their end; and
a single 2000-character word is never split. A 2000-character multiword backlog
reaches its end within 15 word-aligned steps (495 ms).

Fixed-snapshot tests cannot detect a producer whose delay restarts on every
arrival. `MessageBubbleTest.streamingReveal_frequentAppends_keepProgressAndCatchUpWithoutLosingThePrefix`
pauses the Compose clock, starts with a 2000-character backlog, and appends every
16 ms for two seconds, faster than the reveal interval. It checks progress during
arrivals, a monotonically retained word-aligned prefix, and each snapshot's
visibility within about 500 ms including one presentation frame (checked at
512 ms). It also checks final catch-up while still streaming and another arrival
after catch-up, guarding against a producer that stops once it reaches the end.
Keep this lifecycle regression alongside the step tests: retaining `produceState`
values across key changes does not retain a cancelled timer or local deadline.
See [Streaming variant](message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184).

`MessageBubbleSelectionTest` covers selection, streaming and code Copy. Compose
1.10.4 uses
`LocalTextContextMenuToolbarProvider`/`TextContextMenuKeys.CopyKey`, not
`LocalTextToolbar`. Use native graphics for handles and a no-op `Magnifier` shadow
for Robolectric dismissal, in a separate class. Devices ignore them.

The device-only real-thread selection pair in [the e2e ladder](../../e2e-interactive-stream.md#what-rung-3-is-made-of)
uses actual pointer input and the platform clipboard. Wait for the repository's exact assistant
row with `isStreaming == false`; visible text alone can still be streaming and unselectable.
Espresso must target `isPlatformPopup()` for Android's floating Copy toolbar: its default
activity root cannot find the action after the long-press. Keep the known selected word and
unrelated clipboard baseline independent of the result assertion. A substituted toolbar test
cannot establish this platform-menu behavior.

For the shared finished-reply assertion, acquire the real clipboard from the running activity,
not the instrumentation target context (#1854). The target manager's invalid `android`
operation package caused Android's package/UID check to reject baseline seeding before selection.
After one actual Copy click, observe the independently known selected word within the existing
deadline on the UI thread: UI idleness alone previously left an immediate read seeing the old
baseline. Retain exact baseline verification and exact-word/shorter-than-reply assertions;
exceptions propagate and an unchanged baseline or whole reply must time out.

`FinishedReplyClipboardTest` requires the real device service for attribution enforcement and
delayed replacement. Its controlled delayed write proves the missing result fence, while a real
manager with invalid target attribution proves the activity acquisition repair. Restore the
instrumentation registry before UI operations. The historical logs establish neither eventual
Copy completion nor the origin of invalid attribution; Android 13 uses a local toolbar and
Compose 1.10.4 writes synchronously, so a suspend API alone proves no scheduling cause. See
the [scenario contract and counted evidence](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

`MessageMetaRowToggleTest` mounts the real thread for timestamp-only show/hide,
single selection, streaming completion, nested links/code copy/attachments and
independent side-copy semantics (#1817). Standalone fixtures retain their visible
timestamp default for finished messages, but streaming always suppresses it.
`ThreadFrameCaptureTest.compactWidthAndEnlargedText_keepFrameControlsReachable`
checks side copy before revealing the timestamp, then checks pointer reachability
and 320dp wrapping with enlarged text. A passing ATD capture and metadata sidecar
do not prove frame pixels: synthetic bars can suppress PNG output. The #1817
verifier did not inspect fresh full-frame pixels or perform manual TalkBack traversal.

Side geometry tests measure the configured viewport rather than Robolectric's
outer window, whose density can differ. Measure the bubble, 13dp column,
11×12dp Copy and 13×12dp Reply glyphs separately from their adjoining 48×48dp
targets. `MessageBubbleTest` and `MessageReplyTargetsTest` require both target
dimensions for both roles at 320dp/412dp, including short/long surfaces and
streaming. A width-only check missed the former 36.5dp target heights. Assert the
25dp glyph-centre gap, bubble-relative centring, containment within the visible
surface and row, and separation from the preceding row's targets. Pointer checks
cover both sides of the shared edge, every outer edge and overlap into the bubble;
copy and reply must route independently with exact source and no timestamp toggle.
Scope source-copy selectors to the non-merging `message-row`, so another message
or a fenced-code control cannot satisfy the assertion. Markdown and streaming
append tests compare current source, including the 100,000-character bound.

The 96dp minimum short surface retains the 12dp visible rest gap. Keep
`BackgroundAgentRestGapTest` alongside action geometry checks: invisible trailing
row space can satisfy containment while breaking the gap. Taller rows reduce the
lazy-list viewport; explicitly reveal the owned target and position pointer taps
clear of the composer before retaining ownership/collapse assertions. Semantic
visibility alone includes the area behind the composer. History fixtures must
position a fresh touch inside the oldest-end demand window without issuing an
earlier touch, retaining the zero-demand then exact one-demand assertions.

The live and scripted copy checks share
`app/src/sharedTest/java/de/pyryco/mobile/e2e/SideMessageCopy.kt` (#1878).
`assertSideMessageCopy` matches the complete `formatShortDateTime` value using
the current locale and timezone, under the copied source row in the unmerged
tree, both before and after copying. A screen-wide ` - ` substring also matches
usage banners and message bodies; another row's visible timestamp must not count.
After the before-copy check, dismiss available benign notice controls before
setting the unrelated clipboard baseline and making the single pointer tap.
Non-dismissible error notices remain visible. `assertIsDisplayed` alone cannot
detect a [Top overlay](thread-top-overlay.md) physically covering that target.
Retain the exact `message.content.take(100_000)` clipboard comparison.

Since #1818 both side helpers tap through `sideMessageActionTapPoint` in
`SideMessageReply.kt`. It returns the glyph's centre when that point is clear of
the header, the composer and the pills under the `thread-top-overlay` tag.
Otherwise it returns the nearest clear point inside the same action's own target,
3dp away from the shared midpoint. It scrolls the list when no point is clear, and
fails, naming the layout, when scrolling cannot help. A short thread starts at the
overlay's own inset (#1509), so a pill that stays up covers the top of the first
row, and the pair lifted the copy glyph 12.5dp into it. Two pills hit this. Every
scripted run shows the non-dismissible failed-MCP pill, which covers the whole
copy target of the short `hello` row. The held `stream` copy tap opened Channel
info and the clipboard kept its baseline; the logcat showed
`event=mcp_failure_acknowledged` where `event=message_copy` was expected. The
scripted method therefore acknowledges that pill and closes Channel info first.
On the live host, leftovers from earlier methods raise the other-conversation
attention pill, which leaves the lower part of the copy target clear.
`assertSideMessageReply` checks the exact staged draft, the end cursor, field
focus and the hidden timestamp. ATD images omit a keyboard, so both stream
methods select the test APK's keyboard through `TestImeRule` before asserting
keyboard visibility.
`MessageReplyImeDeviceTest` reuses `TestImeRule(selectBeforeTest = true)` at
rule order 0, outside the Compose activity rule at order 1. Selection happens
before activity setup and restoration after cleanup, including setup failure;
live/scripted callers retain default-off, explicit `select()` behavior.

`SideMessageCopyTest` exercises this same helper in eight deterministic regressions:
banner/body separators, another row's timestamp, timestamp rejection before copy
and after clipboard write, incorrect source rejection, streaming trailing whitespace,
the 100,000-character cap, and real-thread warning dismissal. The last uses native
graphics and a forced 412dp viewport through `ThreadScreen`; inert banner fixtures
keep their separator text present through both timestamp assertions. The retained
old-helper report executed one regression and failed on two false timestamp matches;
the repaired report executed/passed eight with zero failures or skips. See the
[verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1880#issuecomment-6032131614).
Focused live repair evidence does not replace dispatcher full-suite acceptance:
record the full run's executed, failed and skipped counts and the named ping
method's result separately, as in the [e2e ladder](../../e2e-interactive-stream.md#verification-status).

Fresh #1895 evidence on `275e5071cafc3aa93aa6a6014219a3d0ad785e31`, based on
main `093847a07b2e`, records **4,775 unit/shared executed/passed, 0 failed,
1 skipped**, including all **8 unchanged `SideMessageCopyTest` regressions**,
5 reply-target cases and 15 bubble cases passed. The inherited history skip is
tracked by #1913. The UI gate records **199 executed/passed, 0 failed, 1 skipped**;
`MessageReplyImeDeviceTest.pointerReplyOpensImeAndTypingContinuesAtEndWithoutSending`
passed, while the rename capture was skipped. The full scripted gate records
**21 executed/passed, 0 failed, 0 skipped**, including held-stream and
background-Agent scenarios. See [verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1916#issuecomment-6039977236).

The dispatcher full real-Claude report `2026-10-07T14-22-04-161Z` tested that
branch head merged with main `093847a07b2e`: **64 executed, 63 passed, 1 failed,
0 skipped**. `InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread`
executed and passed in that full suite, preserving displayed reply, exact source,
row-scoped before/after timestamps and reply/IME checks. Archive/restore alone
failed, then passed on the same-tree rerun (**1 executed/passed**); the dispatcher
accepted the gate after that rerun. This is not a failure-free original full run
or a separate focused ping run. See [live gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1895#issuecomment-6040513528)
and [verification status](../../e2e-interactive-stream.md#verification-status).

Palette guards check actual glyph pixels and glyph-on-thread-background contrast
at ≥3:1 across static and wallpaper light/dark, theme changes and completion.
A tint-only assertion passed while the light icon was 1.61:1 against the thread.
Use `threadColors.background`, including the static-dark canvas overlay, rather
than the unmodified global background. Both side glyphs, copy and reply,
are tinted `colorScheme.primary` directly, with no backing: that role reads through
the matching theme rather than the inverted one, so it clears 3:1 against the thread
background on its own.

`app/src/sharedTest/.../components/MessageBubblePaletteTest.kt` uses native Canvas
pixels at the 412dp reference width to check user, finalized assistant, streaming
assistant and queued fills in static dark/light and wallpaper dark/light modes.
The queued expectation composites the user base fill at 0.6 over the background.
Every fixture deliberately sets system mode opposite to app mode, so an accidental
system-mode lookup cannot hide behind matching defaults. The suite also checks
streaming finalization and theme changes without remounting, text-layout colours
for bodies and timestamps, and unchanged global Material containers in static
modes. Sample padding inside the surface, clear of text and rounded corners, to
assert the actual fill rather than only the theme token.

`app/src/sharedTest/.../components/MessageBubbleTest.kt` (new, #644), the rung-2 component-render layer, with a file-local fake `ClipboardManager` provided through `LocalClipboardManager`:

- `bothRoles_renderBodyAndOwnMetaRow` — both roles render their body text and their own meta row.
- `roleAlignment_userSitsRightOfAssistant_andEachClearsTheOppositeInset` — reads both bodies' rects; the user body sits right of the assistant body and each clears the opposite configured viewport edge by the delivered inset plus action reservation.
- `shortAssistantBody_hugsItsContent_whileALongOneStillGrowsToTheLane` — the regression guard for [Fill vs. hug](message-bubble.md#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644), added in the rework cycle.
- `copy_putsOnlyThatMessagesTextOnTheClipboard` / `copy_fromTheUserBubble_putsOnlyTheUserText_onTheClipboard` — tapping one bubble's copy control captures exactly that message's `content`, never the other's.
- `copyControl_carriesItsAccessibleNameAndButtonRole` — addressable by `cd_thread_copy_message`, `Role.Button`.
- `copy_onStreamingMessage_yieldsWhatHasArrived_andTheCaretStillRenders` — a streaming message's caret still renders inside the new container, and its copy control yields the full `content`, not the revealed prefix.

`app/src/test/.../components/MessageMetaRowFormatTest.kt` (new, #644) pins `formatShortDateTime` locale-robustly, the way [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s tests already do for `formatShortTime`: composition and order (`joinsLocalizedShortDateAndShortTimeInThatOrder`), the design's separator (`joinsTheTwoHalvesWithTheDesignsSeparator`), locale- and zone-sensitivity computed through the same `DateTimeFormatter.ofLocalized*` API rather than a literal (`followsTheSuppliedLocaleRatherThanAFixedPattern`, `followsTheSuppliedTimeZone`), and that the result never equals the Figma sample literal under an unrelated locale (`neverEmitsTheFigmaSampleLiteralForAnUnrelatedLocale`).

**`app/src/sharedTest/.../components/MessageAttachmentsTest.kt` (new, #984, Robolectric `@GraphicsMode(NATIVE)`)** mounts the real `MessageBubble` with a fake `LocalAttachmentThumbnailDecoder` and covers: a decoded image and its content description; the image slot's size held equal from loading to loaded; a decode failure falling back to the file row; a file row labelled with its name and type; a long name shortened to one line inside the bubble's lane (asserted on drawn bounds via `useUnmergedTree = true`, not semantics — see [MessageBubble — attachment slot](message-bubble-attachment-slot.md#attachment-slot-since-984)); an unnamed reference showing the generic label until retrieval supplies one; loading; failed-with-retry, where the tap calls `onRetry(id)` for the right attachment among several; not-found with no retry control; attachments rendering in reference order; an attachment-only message drawing no empty text node; every composed attachment reporting itself shown by id; and, since #985, a `Ready` file row's tap and long-press calling `onOpen`/`onSave` with that attachment's `AttachmentTarget`, a `Ready` image's tap doing the same, and `Loading`/`Failed`/`NotFound` items offering neither (see [MessageBubble — attachment slot § Open and save](message-bubble-attachment-slot.md#open-and-save-since-985)). Native graphics mode is what lets the long-name case measure single-line truncation and the fake decoder hand back a real `ImageBitmap`.

**No dedicated `MessageBubbleTest` case for `toolNestingDepth` (#896).** The parameter is exercised through the real `ThreadScreen` fold instead — `ToolRowNestingTest` (`app/src/sharedTest/.../thread/`) mounts the screen with a matched child, a grandchild and an unmatched-parent tool row and asserts the rendered indent steps by level; see [Thread screen § Subagent tool-row nesting](./thread-screen-subagent-tool-rows.md#subagent-tool-row-nesting-896). A unit-level `ToolNestingDepthsTest` covers the depth derivation itself, independent of any composable.

Two pre-existing suites were re-run rather than relaxed across #644's restyle, because both read this surface closely: `ScriptedThreadRenderTest` (the streaming caret glyph through the real fold) and `ScriptedSessionBoundaryTest` (tight text-node rects through the unmerged tree, which a container wrapped around assistant text changes the ownership of — a `Surface` + `Column` adds no semantics node, so the leaf text nodes it addresses survive). Both stayed green unchanged.
