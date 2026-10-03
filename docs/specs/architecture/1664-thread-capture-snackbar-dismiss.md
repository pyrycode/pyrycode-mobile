# #1664: Thread design captures dismiss the error snackbar instead of waiting out its timer

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: the `waitUntil(15_000)` after the `failure-notice` capture in `rowAndNoticeFramesAt412By892` and after the `refusal-switch-back-failed-snackbar` capture in `refusalStateFramesAt412By892`, the only two snackbar waits in the design captures.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen`'s remembered `SnackbarHostState`, whose `archiveErrors` and `sessionSettingsErrors` collectors call `showSnackbar` with the default `SnackbarDuration.Short`. Read only; nothing changes there.
- Compose `ui-test` 1.10.4 `AndroidComposeUiTestImpl.waitUntil` and Material3 1.4.0 `SnackbarHost` (bytecode in the Gradle cache): what drives the timer, and the dismiss action `FadeInFadeOutWithScale` puts on each snackbar.

## Design source

N/A: test-only fix. The captured frames, their Figma node ids and their comparisons are unchanged.

## Change

**Diagnosis.** The cause is in the test, not in production code. Under the compose rule, `SnackbarHost`'s 4 s `delay` runs on the rule's test dispatcher, so it runs on virtual time. With the snackbar on screen and nothing awaiting a frame, the rule is idle, and virtual time moves only when `waitUntil` advances it. Each poll advances it by one 16 ms frame, sleeps 10 ms and fetches the semantics tree, which waits for Espresso idle. Dismissing the snackbar therefore takes about 255 polls, so the wall clock is 255 times the cost of one poll. That stays under 15 s on a quiet emulator. Under the two-shard gate a poll costs more than about 60 ms, and the wait times out. Focused runs never reproduce it.

**Fix.** A private helper, `dismissSnackbar(text)`, replaces both waits. It performs `SemanticsActions.Dismiss` on the node that defines that action and has a descendant showing `text`. Material3 puts this action on each snackbar for accessibility, and it calls `SnackbarData.dismiss()`. The helper then waits, with the usual 5 s budget, until no node shows `text`. The exit animation runs on frames, which the idle sync advances, so no timer is involved. Both captures that show a snackbar are taken before the dismiss, and both later captures are taken after the text is gone, so the frames keep the same states. No node id, comparison or tolerance changes.

## Testing strategy

These are device-only captures with real pixels. The existing capture comparisons and the strict marker waits are the assertions, and the helper's wait fails loudly if the dismissal does not take effect. Run `rowAndNoticeFramesAt412By892` and `refusalStateFramesAt412By892` focused on `pixel2Api33AtdDebugAndroidTest`, then the whole class. Read the executed, failed and skipped counts from the managed-device XML. The sharded gate result comes from the dispatcher's UI gate.
