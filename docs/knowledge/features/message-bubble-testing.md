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
[thread testing](thread-screen-testing.md#testing). Reopen-specific real-Claude
`InteractiveStreamE2ETest` coverage and its held-stream
`DeterministicInteractiveStreamE2ETest` twin remain pending in
[#1762](https://github.com/pyrycode/pyrycode-mobile/issues/1762); existing full-suite
live execution does not establish those reopen observations.

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

`MessageMetaRowToggleTest` mounts the real `ThreadScreen` to cover show/hide and single selection, streaming-to-finished taps, links and independently visible code copy, inert attachment states, and the screen-reader toggle and hidden-row timestamp/copy semantics. Standalone `MessageBubbleTest` and palette fixtures retain the visible-row default, so their streaming copy test does not describe thread behavior. `ThreadFrameCaptureTest.compactWidthAndEnlargedText_keepFrameControlsReachable` reveals the row before testing its copy pointer target. Compose semantics assertions do not establish TalkBack's spoken order on a device.

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
- `roleAlignment_userSitsRightOfAssistant_andEachClearsTheOppositeInset` — reads both bodies' rects; the user body sits right of the assistant body and each clears the opposite root edge by at least `MessageRoleInset`.
- `shortAssistantBody_hugsItsContent_whileALongOneStillGrowsToTheLane` — the regression guard for [Fill vs. hug](message-bubble.md#fill-vs-hug-the-streaming-arm-keeps-fillmaxwidth-since-644), added in the rework cycle.
- `copy_putsOnlyThatMessagesTextOnTheClipboard` / `copy_fromTheUserBubble_putsOnlyTheUserText_onTheClipboard` — tapping one bubble's copy control captures exactly that message's `content`, never the other's.
- `copyControl_carriesItsAccessibleNameAndButtonRole` — addressable by `cd_thread_copy_message`, `Role.Button`.
- `copy_onStreamingMessage_yieldsWhatHasArrived_andTheCaretStillRenders` — a streaming message's caret still renders inside the new container, and its copy control yields the full `content`, not the revealed prefix.

`app/src/test/.../components/MessageMetaRowFormatTest.kt` (new, #644) pins `formatShortDateTime` locale-robustly, the way [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s tests already do for `formatShortTime`: composition and order (`joinsLocalizedShortDateAndShortTimeInThatOrder`), the design's separator (`joinsTheTwoHalvesWithTheDesignsSeparator`), locale- and zone-sensitivity computed through the same `DateTimeFormatter.ofLocalized*` API rather than a literal (`followsTheSuppliedLocaleRatherThanAFixedPattern`, `followsTheSuppliedTimeZone`), and that the result never equals the Figma sample literal under an unrelated locale (`neverEmitsTheFigmaSampleLiteralForAnUnrelatedLocale`).

**`app/src/sharedTest/.../components/MessageAttachmentsTest.kt` (new, #984, Robolectric `@GraphicsMode(NATIVE)`)** mounts the real `MessageBubble` with a fake `LocalAttachmentThumbnailDecoder` and covers: a decoded image and its content description; the image slot's size held equal from loading to loaded; a decode failure falling back to the file row; a file row labelled with its name and type; a long name shortened to one line inside the bubble's lane (asserted on drawn bounds via `useUnmergedTree = true`, not semantics — see [MessageBubble — attachment slot](message-bubble-attachment-slot.md#attachment-slot-since-984)); an unnamed reference showing the generic label until retrieval supplies one; loading; failed-with-retry, where the tap calls `onRetry(id)` for the right attachment among several; not-found with no retry control; attachments rendering in reference order; an attachment-only message drawing no empty text node; every composed attachment reporting itself shown by id; and, since #985, a `Ready` file row's tap and long-press calling `onOpen`/`onSave` with that attachment's `AttachmentTarget`, a `Ready` image's tap doing the same, and `Loading`/`Failed`/`NotFound` items offering neither (see [MessageBubble — attachment slot § Open and save](message-bubble-attachment-slot.md#open-and-save-since-985)). Native graphics mode is what lets the long-name case measure single-line truncation and the fake decoder hand back a real `ImageBitmap`.

**No dedicated `MessageBubbleTest` case for `toolNestingDepth` (#896).** The parameter is exercised through the real `ThreadScreen` fold instead — `ToolRowNestingTest` (`app/src/sharedTest/.../thread/`) mounts the screen with a matched child, a grandchild and an unmatched-parent tool row and asserts the rendered indent steps by level; see [Thread screen § Subagent tool-row nesting](./thread-screen-subagent-tool-rows.md#subagent-tool-row-nesting-896). A unit-level `ToolNestingDepthsTest` covers the depth derivation itself, independent of any composable.

Two pre-existing suites were re-run rather than relaxed across #644's restyle, because both read this surface closely: `ScriptedThreadRenderTest` (the streaming caret glyph through the real fold) and `ScriptedSessionBoundaryTest` (tight text-node rects through the unmerged tree, which a container wrapped around assistant text changes the ownership of — a `Surface` + `Column` adds no semantics node, so the leaf text nodes it addresses survive). Both stayed green unchanged.
