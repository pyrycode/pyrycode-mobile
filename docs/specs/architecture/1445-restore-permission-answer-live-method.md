# #1445: restore the permissionAnswer live method

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the `@Ignore("blocked on #1445 …")` on `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, and `openChatRow`, which already accepts Send or `cd_thread_interrupt` since `501cd547`.
- `scripts/e2e-emulator.sh`: the curated `LIVE` `TEST_TARGET` list, where #1312 replaced the method's line with a #1445 exclusion comment.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM = len(curated_live_methods())`, so the minimum follows the list with no edit.

## Change

#1428 merged with the isolation (`877fb880` adds the `@Ignore`, `3bb7a458` drops the `TEST_TARGET` line). This ticket reverses both: remove the `@Ignore` line from the method, and put its `TEST_TARGET` line back in the `LIVE` list in place of the #1445 exclusion comment. The `org.junit.Ignore` import stays, since other methods use it. No production code moves; the underlying `openChatRow` fix is already on main.

## Testing strategy

- `python3 scripts/test_android_test_gate.py` checks the curated list against the runnable methods and `LIVE_MINIMUM`.
- `./gradlew compileDebugAndroidTestKotlin` for the test source.
- The full live suite, run by the live gate (`## Live tests: all`), is AC2's evidence: executed, failed and skipped counts, with this method executed and passed.
