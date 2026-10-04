#1646 focus-reveal rework, 2026-10-04

The native regression placed an earlier Other field wholly beneath the header and
requested semantic focus, matching keyboard/accessibility navigation without a
pointer tap through chrome. Before repair the field stayed at 29.7..61.9 dp while
the header ended at 69.7 dp. The calibrated red run executed 1, failed 1, errors 0,
skipped 0. Its log is /tmp/builder-1646/focus-red.log on the builder host.

The corrected focused JVM selection executed 148, failed 0, errors 0, skipped 0
across 21 classes. jvm-counts.txt enumerates them; inline-question-results.xml
retains the focus tests. The new native case checks both obscured bounds, viewport
shrink/restore/re-shrink, pending attachments and multiline composer changes.
The strengthened earlier-field test checks chrome clearance and its unchanged
physical anchor on every frame. Existing last-field/actions, follow/history,
child gestures, geometry and controls all ran.

The initial device class executed 12, failed 1, errors 0, skipped 0, exit 1.
device-setup-failure.xml retains the new method's host-focus timeout before field
setup; all 11 existing methods passed. The new method now uses the established
CLOSE_SYSTEM_DIALOGS host-focus recovery already present in the pointer test.
The second device class also executed 12, failed 1, errors 0, skipped 0, exit 1.
Its failure was still setup: the +10 px calibration was clamped at the oldest end.
device-reveal-failure.xml retains that assertion (no production reveal assertion
was reached). Setup now calibrates in the other direction if the oldest end
consumes nothing, and still asserts complete header overlap before requesting
focus. The corrected class never executed: a 1200-second device-lock wait and a
600-second retry expired. The stream scenario also expired after a 600-second
wait (exit 75, zero tests executed). These are not passing or skipped-test
evidence. The final holder was the other ticket's real-claude-gate-1697 live run.
Required remaining check: QuestionBatchModalTest, especially
ime_keeps_an_earlier_other_clear_of_chrome_on_open_dismiss_and_reopen, followed by
the focused deterministic stream scenario. Native focus coverage, lint,
assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck passed.

Hardware capture fixtures and renderer did not change in this repair. Prior full
Pixel 8 real-system-bar framebuffer captures, four Figma comparisons and viewport/
inset sidecars remain in the parent directory and rework/. They are not reruns of
this focus repair. Fresh complete dispatcher gates and documentation are pending.
