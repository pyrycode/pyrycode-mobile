# Shared mobile modal (#638)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt` → `CreateFolderDialogInternal`: Dialog window owns its subcomposition; caller focus effects belong inside content.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialogTest.kt` → `CreateFolderDialogTest`: local Compose fixture and callback assertions.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: adaptive color slots; fixed primary slots and custom shapes are not configured.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → `primaryContainerLight`, `primaryContainerDark`: available container colors.
- `gradle/libs.versions.toml`: existing Compose, Material 3 and test dependencies suffice.
- `docs/knowledge/features/create-folder-dialog.md` → internal state: focus requests must run in the dialog subcomposition.
- `docs/knowledge/features/development-verification.md` → Compose evidence: establish preconditions, assert positive transitions; dispatcher owns API 33 execution.
- `docs/specs/architecture/528-live-mobile-baseline.md` → prerequisite establishes the API 33 evidence pipeline, not modal functionality.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369
Shared component: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=489-1942

Read both design contexts and the mobile screenshot. A full-height rounded column has 28 dp horizontal / 24 dp vertical padding, 20 dp section gaps, titleLarge header and circular close glyph, a 60%-opacity inversePrimary divider, centered scrollable content and a centered outlined Cancel / filled OK footer with 20 dp spacing. Host fields are illustrative and are not shipped.

The reference uses an unconfigured onPrimaryFixed background and 44/6 dp shapes. Use the existing primaryContainer/onPrimaryContainer pair and extraLarge/small shapes to remain adaptive in light and dark themes; explicitly document this deviation in source and PR. Preserve the exported close path in a vector drawable, tinting it through primary over onPrimary. Enlarge action targets to 48 dp for accessibility.

## Context and size

One deliverable: reusable presentation shell; no consumers migrate. Estimate approximately 550 total written lines (production, tests, resource, plan), one production Kotlin file, one new internal composable, no consumer signature changes, four AC, zero new error/reject state-machine branches. This fits the refiner's estimate and the #213 analogue. Refreshed remote branches and found no overlap for the proposed component, test or drawable paths. No security-sensitive label.

## Design

`ui/components/MobileModal.kt` exposes internal `MobileModal(title, onDismissRequest, onSubmit, modifier, submissionEnabled, loading, error, content: @Composable ColumnScope.() -> Unit)`.

Use Compose `Dialog` with platform default width disabled, outside dismissal disabled and decor fitting disabled. Apply safe-drawing and IME insets once around the full-size Surface. Header/footer remain outside the weighted vertically scrollable content column. Short content centers vertically; overflow scrolls, including the final item and focused editable fields. Error is a polite accessible live-region text inside the content without replacing the slot.

Close, Cancel and dialog Back each invoke dismissal once; no automatic visibility changes. OK is enabled only for submissionEnabled && !loading, invokes only submission, and retains its name alongside a small progress indicator. Use M3 controls for keyboard activation, semantics and minimum sizes. Dialog provides a separate focus window, trapping navigation and restoring the underlying window's launching focus on teardown; the shell does not request focus on recomposition. Content callers may request focus inside the slot.

## State + concurrency model

No ViewModel, domain state, IO, jobs or repository. Caller owns visibility, form values, error and loading. Only remembered scroll state belongs to the shell. Dialog lifecycle emits debug-only static open/close logs and callback routes emit static dismissal/submission codes; never title, form values or error text.

## Error handling

No new IO failures. Caller error is presented as text using error semantics and error color. The content remains composed across loading and error updates. Consumers own cancellation and operation errors.

## Testing strategy

Author local instrumented tests first and compile RED for the absent shell, then compile GREEN. Cover callback counts including Back; disabled/loading gating; error semantics and retained entered text/focus; short centered and overflowing content; small-size inset/IME behavior; button names, roles and targets; hardware Tab/Enter containment and launch-focus restoration. Include light/dark 412 × 892 previews. No JVM logic changes, so no unrelated unit suite. Run spotlessApply, lint, assembleDebug and compileDebugAndroidTestKotlin. Dispatcher executes the API 33 tests; compilation is not runtime proof. No daemon-facing flow or real-Claude scenario is introduced.

## Documentation handoff

No documentation-only acceptance criteria or explicit documentation handoff in the ticket. Later documentation stage may record the reusable shell API and theme deviations in the owning UI topic; no shared docs edited here.

## Open questions

None. Device execution and visual runtime evidence remain dispatcher-owned.

## Revisions

- 2026-09-20, PR #699 verifier rework: the API 33 gate executed all seven modal tests; launcher focus and IME visibility failed before their acceptance assertions. Set `LocalInputModeManager` to keyboard mode after composition before requesting launcher focus, and do the same inside the dialog window. Keep Tab containment, Enter activation and launcher-restoration assertions; no caller focus workaround is added to production.
- The required AOSP ATD image omits LatinIME ([Android managed-device documentation](https://developer.android.com/studio/test/managed-devices#atd)). Add `MobileModalTestIme`, its service declaration in `app/src/androidTest/AndroidManifest.xml`, and `app/src/androidTest/res/xml/mobile_modal_test_ime.xml` only to the test APK. The service uses Java and Android framework classes so its separate process does not require Kotlin/Compose libraries supplied by the target APK during instrumentation; additionally run `compileDebugAndroidTestJavaWithJavac` and package the test APK to check this fixture. Temporarily select this real `InputMethodService` during the IME test and restore the previous selection/enabled state in `finally`. Wait for dialog window focus, assert editable focus, explicitly show the IME, and retain positive platform IME visibility, nonzero inset, final-item reachability and footer-above-IME checks. This tests actual OS insets without requiring an IME installed in the ATD image; it does not inject synthetic insets or change the production modal.
- Rework remains one presentation deliverable: one production Kotlin file overall, no new production types or consumers, four AC, no new production failure branches, approximately 680 total written lines including the test-only fixture and these revisions. Refreshed remote feature branches have no overlapping modal edits. Dispatcher owns the repaired API 33 run and subsequent visual judgment; test compilation alone is not a device pass.
