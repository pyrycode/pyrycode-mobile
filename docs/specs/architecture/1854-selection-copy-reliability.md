# Selection Copy reliability (#1854)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/FinishedReplySelection.kt`: `assertFinishedReplySystemCopy` finalization, pointer selection, baseline and immediate read.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_finishedReply_systemCopyCopiesSelectedWord`, enabled live caller using an Android activity rule.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_systemCopyCopiesSelectedWord`, enabled scripted twin.
- `docs/knowledge/features/message-bubble-testing.md`: real pointer selection needs a finalized repository row and Espresso's platform-popup root.
- `docs/e2e-interactive-stream.md`: “Finished-reply partial Copy (#1674)” scenario contract.
- `scripts/android-test-gate.py` and `scripts/e2e-emulator.sh`: `selection-copy` selection and counted fresh artifacts.
- Android 13 `ClipboardManager.setPrimaryClip`, `SystemServiceRegistry` clipboard fetcher, `FloatingToolbarPopup.createInstance` and `LocalFloatingToolbarPopup`: package attribution, per-context manager caching and actual local toolbar dispatch.

## Context

One test-reliability deliverable, with no production or visual change. Recorded failures have two signatures. The #1769 and #1861 failed runs completed the platform click but immediately observed the unrelated baseline; the same mobile revisions passed focused reruns. The #1785 failed run threw `SecurityException: Package android does not belong to 10098` in baseline `setPrimaryClip`, before selection; its same-tree rerun passed.

The helper checks UI idleness, then immediately asserts the clipboard instead of fencing its expected result. A delayed write after idleness is a concrete synchronization gap, not proof that the historical writes eventually completed: the old tests never observed after failing. Android 13 uses the local popup; resolved Compose 1.10.4 native clipboard writes are synchronous. Pointer delivery or posted click timing remains a hypothesis, not an established historical trigger. Android's clipboard manager passes its retained context's `getOpPackageName` into the service, so #1785 demonstrates invalid package attribution in the manager obtained from instrumentation target context. The historical logs do not identify how that context/service acquired `android`; contamination/initialization ordering remains a hypothesis. The repair removes dependence on that context, using the running activity's independently scoped service cache and app attribution.

Evidence is the paired `.log` (counted XML) and `.stderr.log` (stacks and identical mobile revision) files under `$AGENTS_REPO_PATH/logs/`:

- #1769: `2026-10-06T18-56-51-114Z_real-claude-gate_#1769` and `2026-10-06T18-56-51-114Z_real-claude-gate-rerun_#1769`, mobile `bea12415e2632c1e7ed7fbf4f5ea6736684ebf7e`: 61 executed/2 failed/0 skipped versus 2 executed/0 failed/0 skipped.
- #1785: `2026-10-07T00-04-51-814Z_real-claude-gate_#1785` and `2026-10-07T00-04-51-814Z_real-claude-gate-rerun_#1785`, mobile `ac99d26a8c381c5be467de56fdb42f5a113e2344`: 62 executed/1 failed/0 skipped versus 1 executed/0 failed/0 skipped.
- #1861: `2026-10-07T01-07-15-182Z_real-claude-gate_#1861` and `2026-10-07T01-07-15-182Z_real-claude-gate-rerun_#1861`, mobile `ca063579d81ef8deabdc798c19a3d10747ec35ea`: 63 executed/3 failed/0 skipped versus 3 executed/0 failed/0 skipped.

Overlap: closed #1766 has only the bounded-wait change in this helper; it is an investigation lead, not a dependency. No other remote feature branch touches the planned files.

## Design

Change the assertion's receiver to the Android activity Compose rule (both existing callers already use it). Extract test-only helpers for obtaining the activity-owned real clipboard and asserting its exact selected-word result within the existing deadline. Keep the baseline write and independent baseline read, finalized exact assistant row, measured middle-word long press, Espresso real platform Copy and final shorter-than-reply assertion.

Use bounded observation of the actual clipboard, never repeat Copy or select again. Exceptions propagate. Timeout leaves the assertion red; a full reply, unchanged baseline or wrong word cannot pass. No substituted providers, new dependencies, production changes or exported product types.

Estimated written work: about 300 lines including plan, two test-helper seams and four device regression cases, within the 1600-line ceiling. Two unchanged call sites, four acceptance criteria, no state-machine rejects or new exported types. Nearest analogue #1674 has 219 inserted/4 deleted lines across its test/harness and plan commits.

## State and concurrency model

The real Copy callback and clipboard are owned by Android. Read on the UI thread through the rule; bounded `waitUntil` yields between observations so pending UI work can complete. Regression delayed writes use a test-owned coroutine scope, canceled in `finally`; they do not alter production dispatchers or replace the clipboard.

## Error handling

Retain all clipboard exceptions, exact baseline verification and exact selected-word comparison. Read zero-item or absent clips as absent, which cannot satisfy the expected word. The regression ownership fault is confined to the instrumentation target manager and restored in `finally`; activity clipboard calls must still succeed.

## Testing strategy

Add `app/src/androidTest/java/de/pyryco/mobile/FinishedReplyClipboardTest.kt` outside the e2e package. These tests require the real device clipboard service's package/UID enforcement and delayed native clipboard replacement, unavailable through Robolectric's clipboard shadow.

Test first: delayed real clipboard replacement must outlive an initial idle read and satisfy the shared bounded assertion; unchanged baseline and whole-reply replacement must time out; a target-context clipboard manager deliberately attributed to `android` must reproduce the service ownership exception while the shared activity service can seed/read the real baseline. Inject the attribution fault only in this regression via the real manager's retained context, restore it in `finally`, and do not catch exceptions in the selection helper. The explicit negative-control assertion in the regression is not a swallowed e2e exception.

Run the regression class on the managed Android 13 device and `python3 scripts/android-test-gate.py scripted selection-copy`; inspect fresh selected testcase XML, executed/failed/skipped counts and retained artifacts. Run focused existing selection coverage, lint, assembleDebug, Android-test Kotlin compilation and formatting, then merge main, push and run the complete unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`.

The full live gate belongs to the dispatcher. PR `## Live tests` requests `all` because a shared live helper changes. Leave `needs-real-claude` enabled. Fresh full-suite counts and confirmation that the named live method passed remain pending; deterministic evidence does not satisfy that criterion.

## Open Questions

- Resolved: the real legacy manager constructor works on the managed Android 13 device; the public instrumentation registry seam is restored before UI operations. See Revisions; no hidden-API policy change is needed.
- Resolved evidence boundary: historical invalid-attribution initialization origin cannot be reconstructed from retained stacks. The ticket investigation explicitly distinguishes this unknown trigger from the demonstrated invalid package/UID attribution and the reproduced faulty condition.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Finished-reply partial Copy (#1674)”, with established failure causes, reliability changes, counted deterministic evidence and dispatcher-produced full live evidence. Documentation records the live evidence; it does not produce it.

## Revisions

### 2026-10-07 — establish the synchronization evidence boundary

Further reading of Android 13 `FloatingToolbarPopup.createInstance` shows it chooses `LocalFloatingToolbarPopup`, whose menu listener calls Copy directly. The remote-toolbar dispatch explanation in Context was an investigation assumption and is withdrawn. Inspection of the resolved Compose 1.10.4 artifacts also shows `SelectionContainer` launches Copy undispatched and `AndroidClipboard` writes synchronously; the suspend API alone does not prove delayed writes. The retained failing logs prove a stale read after the injected click, not that the historical Copy later completed. Pointer event delivery / posted click completion is a hypothesis. The test defect repaired is the missing fence on the observable clipboard contract; the controlled delayed real-service regression demonstrates it independently. Activity-owned clipboard attribution remains the repair for the separately observed package/UID mismatch; its original initialization trigger remains unknown.

### 2026-10-07 — attribution fixture uses the real service without private-field access

The initial device run rejected reflection on `ClipboardManager.mContext` with `NoSuchFieldException`; the delayed-read case independently failed with the recorded baseline mismatch. Replace private-field mutation with construction through Android's legacy Context/Handler constructor, supplying a ContextWrapper whose operation package is `android`. Register that manager as the instrumentation target context's service only while obtaining the helper's clipboard, using the public `InstrumentationRegistry.registerInstance` API, and restore the original instrumentation in `finally` before UI operations. The manager and service remain real. Make the clipboard acquisition seam an `Activity` extension so the running activity is explicit and registry fault injection stays isolated. This resolves the private-field Open Question without changing device hidden-API policy.
