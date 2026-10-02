# #1460 — Real-Claude e2e: Compact session from the Actions menu with a file attached

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` (step 3, the compacting indicator and divider waits), `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` (the `ActivityIntentStub` and `finally` cleanup), and the helpers `attachDocument`, `insertDownload`, `deleteFixtures`, `openActions`, `actionRow`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt`: `ATTACHMENT_STRIP_TEST_TAG`, and each tile's content description is the file's display name.
- `scripts/e2e-emulator.sh`: the `LIVE` branch's curated `TEST_TARGET` list. The gate floor in `scripts/android-test-gate.py` counts that list, so it needs no hand edit.
- `docs/specs/architecture/1348-actions-command-pending-files.md`: the Actions command carries the pending files and clears the strip.

## Design source

N/A: test-only, no new visuals.

## Change

Overlaps #1340 and #1346 in `InteractiveStreamE2ETest.kt`; both add separate methods, so this edit stays additive.

Add one `@Test`, `interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip`, to `InteractiveStreamE2ETest`. It creates a fresh named chat (new prefix `e2e1460-compact-`), opens it and sends one ping so claude spawns and publishes `/compact`, which enables the Compact session row. It then attaches one small text fixture through `attachDocument`, taps Compact session in the Actions menu, and waits for the compacting indicator, then a compaction divider in the thread, then the indicator going. Last, it waits for the fixture's tile under `ATTACHMENT_STRIP_TEST_TAG` to disappear. The `ActivityIntentStub` monitor is removed and the fixture deleted in a `finally`. That makes two real-claude turns: the ping and the one compaction. The waits are copied from the existing Compact method rather than factored into a shared helper, so the existing live method stays unchanged. The method joins the curated `LIVE` list in `scripts/e2e-emulator.sh` with a one-line comment. No production code changes.

If the live gate shows that Claude does not compact reliably when the attachment block follows `/compact`, the fallback in the third acceptance criterion applies: `@Ignore` the method with the observed behaviour in its KDoc, take it off the curated list and post the finding on the ticket.

## Testing strategy

The method is itself the test, and it is device-only because it needs the emulator, a host daemon and real Claude. The builder checks that it compiles with `./gradlew compileDebugAndroidTestKotlin` and that the curated list matches the runnable methods with `python3 scripts/test_android_test_gate.py`. The dispatcher's live gate (`python3 scripts/android-test-gate.py live`) supplies the run evidence, and the PR lists the method under `## Live tests`.
