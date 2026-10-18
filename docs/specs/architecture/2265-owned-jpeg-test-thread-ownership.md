# Reliable owned-JPEG orientation regression

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStripTest.kt`: `assertOrientedThumbnail`, its three orientation callers, fixture generation and Compose rule.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt`: `rememberThumbnail` returns from real IO decoding into a Compose effect.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/OwnedPasteCopy.kt`: capture, read and release serialize private-file ownership without touching views.
- `app/src/test/java/de/pyryco/mobile/robolectric/RobolectricTestApp.kt`: per-test application setup.
- `app/src/test/resources/robolectric.properties` and `app/build.gradle.kts`: paused Robolectric execution and shared-test compilation.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`: retain independent owned-source decoding and asymmetric EXIF pixel proof.
- `docs/knowledge/features/development-verification-gates.md` and `development-verification-compose-evidence.md`: native graphics, density conversion and manual main-thread drawing instead of `captureToImage`.
- Installed Compose UI test 1.10.4 bytecode: `AndroidComposeUiTestEnvironment` defaults to `UnconfinedTestDispatcher`, accepts an explicit test dispatcher, and enables standard-dispatcher support.

## Context

One behaviour: reliable native-graphics verification of owned JPEG orientation when the original provider is absent. The repair and its regression proof ship together. No production API, wire contract or visual design changes are intended; no decision record is needed.

Retained #2254 evidence run `04be36a2235825e3b4fa3fa0f220373508d90a089903b3694a2957507cd80861` reports `CalledFromWrongThreadException` (5955 executed, one failed), then a fresh exact-method pass. Earlier #2201 run `5b3b7711c9d44fc7cc6865260eb5e4f131ddad4938e8bf09d0b17ea28b73b2af` reports that exception and a slot-table error. Neither retained summary contains the causal stack. A focused baseline and controlled coroutine-return regression must establish the mechanism before treating a repair as proven.

Remote feature branches checked on 2026-10-18: no overlap in the test or thumbnail implementation.

## Design

Keep the change local to `ComposerAttachmentStripTest`. Exercise the asynchronous return boundary used by `rememberThumbnail` with a controlled IO completion and assert that subsequent effect work owns the view's main thread. First run this regression with the existing rule. If it establishes unconfined resumption on IO, give this class an explicit `StandardTestDispatcher` through `createComposeRule(effectContext)`, allowing Compose's clock/idling support to drain the queued return on the UI thread. Do not change the decoder or use a fake thumbnail.

Retain native graphics, all three EXIF orientation cases, unavailable-provider source, four rendered colour samples with per-channel tolerance below 15, and 45 × 60 dp bounds within 0.5 dp. Draw and bitmap cleanup remain inside `runOnIdle`. Extend lifecycle assertions only where they prove the observed boundary. Any evidence requiring a different repair is recorded under Revisions before implementation departs from this design.

## State and concurrency model

No product state or jobs change. The Compose rule owns composition and effect cancellation. The controlled test uses coroutine completion signals instead of sleeps, with a bounded wait and cleanup that releases any held IO continuation. IO remains on `Dispatchers.IO`; only its continuation back into the effect is queued by the test dispatcher.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| IO finishes after its caller has suspended | Controlled effect-return regression asserts UI ownership before view work. |
| Cold native decode and view draw | `ownedJpegThumbnail_appliesExifRotationWithoutTheOriginalProvider`, five fresh processes. |
| Successive native orientation cases and class-local state | Complete `ComposerAttachmentStripTest`, retaining rotation, mirroring and combined orientation assertions. |
| Composition exits | Controlled regression confirms effect disposal on UI and releases held completion in cleanup; rule retains cancellation ownership. |

## Error handling

Thread and pixel assertion failures remain visible; no exception suppression, retries or assertion relaxation. Preserve failed diagnostic evidence. If focused checks cannot establish the mechanism behind the full-gate failure, submit retained failure evidence and a justified wider investigation through the shared route; focused passes alone are insufficient.

## Testing strategy

Run the controlled regression red before changing the rule, then green. Run five fresh exact-method JVM gates and one fresh affected-class gate on Linux, preserving raw output, counted XML and source revision. Existing PNG, orientation and class interaction checks remain included. Run lint, assembleDebug, shared androidTest Kotlin compilation, Spotless apply/check, then final assemble and `pre-verify.py --gradle` after the last merge of main. Complete JVM/device gates belong to the dispatcher. No device-only test is added.

## Open Questions

- Does controlled IO completion reproduce off-thread effect continuation under the default rule? Resolve with the failing regression and its stack; revise if it does not.
- Does the explicit queued dispatcher preserve all existing class behaviour? Resolve through the affected-class execution.

Sizing: approximately 200–300 written lines including this plan and regression, one test file, zero new exported types or migrated consumer call sites, three acceptance criteria, no new product reject branches; within `behaviour-slices-v1` limits.
