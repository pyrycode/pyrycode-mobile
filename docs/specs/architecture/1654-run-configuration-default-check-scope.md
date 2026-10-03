# #1654: scope the run configuration's "no Default" check away from thread messages

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `openRunConfiguration()`, whose tree-wide
  `onAllNodesWithText("Default", …)` assertion also matches the seeded reply "Workspace picker — default to the current
  cwd …"; `noWorkspaceAction()`, the analogue that narrows a text check with an extra matcher.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` draws
  `ThreadComposerFooter` (status details, model, effort and permission rows) in the scaffold's bottom bar, outside the
  `thread-message-region` box that holds every thread message.

## Design source

N/A: test-only change with no visual effect.

## Change

Replace the tree-wide assertion with
`rule.onAllNodes(hasText("Default", substring = true, ignoreCase = true) and !hasAnyAncestor(hasTestTag("thread-message-region"))).assertCountEquals(0)`.
Every run-configuration row still falls under the check, because the footer sits outside the message region, while
thread messages, wherever the list is scrolled, no longer do. The fake seed text stays as it is, and the second capture
that calls `openRunConfiguration()` picks up the same fix. No production code changes.

## Testing strategy

The change is to the assertion itself. `./gradlew compileDebugAndroidTestKotlin` proves it compiles, and the dispatcher's
UI gate runs `ThreadDesignCaptureTest` on the emulator.
