# #1507: capture the channel list's waiting marks against `640:2440`

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt`: `install` / `uninstall` and the
  `hostModal` / `questionBatch` flows. The new list source is added here.
- `app/src/androidTest/java/de/pyryco/mobile/design/PromptsDesignCaptureTest.kt`: `promptsWaitInTheirOwnChats`
  takes the `switch-list` capture against `640:2440`; the marks are asserted and re-captured there.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` (read only): `HostConversationConnection`
  folds `modals` and `questionBatches` into `attention`; `demo` leaves both inert. The internal constructor
  is what the harness builds its own source from.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` (read only): `single<HostConversationSource>` is the
  binding that `ChannelListViewModel` and `PyryNavHost` resolve. `AttentionNotifier` is created at start
  and keeps the app's own source, so the harness source raises no notifications.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` (read only):
  `ConversationStatusDot` fills `WaitingForAnswer` with `colorScheme.warning` inside the shared ring, and
  names it "Waiting for your answer".
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, "Attention dot (#878)": the `15:8`
  blue filled dot is the Idle dot on the selected (open) row, not an attention state. The verdict leans on
  this: `640:2440`'s filled dot on "Client planning" is the same status-dot instance on the open row.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=640-2440

The full channel tree; every row is the `Channel` component (`103:2968`) with a 6x11 status-dot slot whose
circle (r 2.5, centre 3,8) the export gives as ring `#9DCBFC`. "Client planning" (`I640:2440;103:2972`) sits
on `Schemes/On Primary` with the dot filled in its ring colour; both "kitchenclaw refactor" rows
(`132:3901`, and `398:7266` on `Schemes/Primary Container`) are hollow rings. No row in the frame draws a
waiting fill. This ticket changes no UI; the frame is the comparison target.

## Change

`DesignInputs.install` also overrides `single<HostConversationSource>` with a source built from one demo
connection (`DEMO_SERVER_ID`, the fake repository) whose `modals` is `hostModal` and whose
`questionBatches` is `questionBatch` mapped to a list (`stateIn` on a scope `DesignInputs` owns). The list
therefore reads the same prompts the thread does, as production's one host source feeds both.
`uninstall` restores the app's instance, disposes the harness source and cancels the scope. Nothing under
`app/src/main/` changes, and tests that leave both flows empty draw the list exactly as before.

`promptsWaitInTheirOwnChats` asserts, before its `switch-list` capture, that the "Client planning" and
"kitchenclaw refactor" rows carry "Waiting for your answer" and "Release notes" reads "Idle". The capture
is re-taken on `pixel8Api35` with `requireRealSystemBars=true`, compared with `figma-640-2440.png` through
`scripts/design-compare.py`, and item `640:2437` in `design-1220/prompts/index.md` gets the marks' verdict
(shape, colour, position for both rows, measured against `get_metadata`) in place of the "not judged"
entry, with each mismatch routed.

## Testing strategy

Device-only by necessity: the capture needs real pixels on the full image, like the rest of the class. The
focused run is `PromptsDesignCaptureTest#promptsWaitInTheirOwnChats` on `pixel8Api35`; its new assertions
read each row's merged content description, so they fail if the source override does not reach the list.
`./gradlew compileDebugAndroidTestKotlin` covers the other design classes that install the same inputs.
