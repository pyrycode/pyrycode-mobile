# #1010 — `benignFrame_clearsTheArm`: wait on a later visible frame

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedUsageLimitTest.kt` → `benignFrame_clearsTheArm`, the class KDoc's sync-point rule, and `pastResetsAt_neverShows` (the shape this change copies)
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` → `ScriptedThreadHarness` pumps frames on a `Dispatchers.IO` scope, so Compose idling does not wait for a frame to fold
- `app/src/main/java/de/pyryco/mobile/data/repository/UsageLimitProjection.kt` → `UsageLimitProjection.apply` removes the conversation's entry on a benign reading and reads `limit_type` on no control-flow path

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1956 (reference only). The Top overlay's usage pill; no visual change.

## Change

The test pushes `turn_state thinking` before the warning, so after the benign frame `awaitDisplayed(thinkingDescription)` returns at once. The absence assertion can then run before the frame folds. It passed 9/9 in isolation on `76ffdec4` and failed under the verifier's loaded full run, which is a race. The fix moves `pushTurnState("thinking")` to after the benign `allowed` frame. The thinking label then appears only after that frame, and frames fold in order on the one inbound collector, so the wait is a real sync point. This is the shape `pastResetsAt_neverShows` already uses. The assertion is unchanged. Production is untouched: the projection clears on any benign reading whatever its `limit_type`, and the corrected test proves it.

## Testing strategy

The test itself is the proof: `./gradlew testDebugUnitTest --tests "…ScriptedUsageLimitTest"`. If the pill survived the benign frame, the assertion after the corrected sync point would fail every run, not only under load. That result would send the fix to production (AC #2).

## Documentation handoff

None.
