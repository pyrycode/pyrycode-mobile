# Keep the thread header visible when the keyboard opens

## Files read

- `app/src/main/AndroidManifest.xml` → `MainActivity` declaration — currently leaves soft-input adjustment unspecified.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `onCreate` — edge-to-edge activity owns and consumes system-bar padding.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — bottom-bar column owns IME padding; reversed message list retains its keyed anchor.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` → `launch`, `capture`, `exercise` — real activity, test IME and full-system-bar capture fixtures.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt` → both IME reachability tests — modal regression checks.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `SEED_RECORDS` — populated demo thread, no live daemon needed.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → theme setters — select and restore light/wallpaper preferences in device checks.
- `app/build.gradle.kts` → managed devices — API 33 ATD assertions and API 35 full-image evidence.
- `docs/knowledge/features/thread-input-bar.md` § IME handling — composer lifts as one unit with 16dp footer padding.
- `docs/knowledge/features/thread-screen.md` § Wiring — demo destination uses the existing fake singleton.
- `docs/knowledge/features/development-verification.md` § Compose evidence — actual IME visibility/insets and real bars are required; ATD screenshots do not prove pixels.
- `docs/knowledge/features/mobile-modal.md` § Focus and verification — keyboard checks cover scrollable content and actions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The screenshot and design context show a fixed title/back/menu header above a scrolling message column, with a three-band composer at the foot. Preserve the existing M3 title-large/body-medium/body-small typography, Schemes surface/primary roles, 20dp composer gutter and 16dp bottom spacing; this fix changes window behavior, not assets or styling.

## Change

Declare `adjustResize` for `MainActivity`, subject to a device RED→GREEN experiment, to prevent the unspecified window policy from panning the populated thread. Keep `MainActivity.onCreate` system-bar consumption and `ThreadScreen` composer-only `imePadding` as the single owners of their respective insets. No new state, types, jobs, failure branches or logging paths: existing activity lifecycle logs remain sufficient for this static window-policy correction. Drafts remain heap-only and the existing reversed, keyed list owns scroll state.

One deliverable, three acceptance criteria; expected approximately 350 written lines including tests and this plan, zero production Kotlin files (one manifest), zero exported declarations, zero changed consumer signatures and zero error branches. This agrees with the refiner's S estimate and is below all limits. The branch overlap check found no other feature branches touching the proposed files. The #1149 analogue added 370 lines including its plan and capture metadata; this ticket makes no causal claim about that earlier change.

## Testing strategy

- Extend `MainActivityInsetsDeviceTest` with populated-thread cycles at 412×892 and 360×640dp, including light and wallpaper-colour selections. Use the existing real `MainActivity` launch and test IME, not a synthetic Compose host.
- Before production edits, prove the populated failure with actual IME visibility and positive bottom inset. Check the header's screen position, send reachability, footer distance from the keyboard, visible scrollable messages, unchanged draft, and restored visible-message/offset after dismissal and reopening. Cover both newest anchoring and a scrolled-away anchor, without scrolling during the preservation cycle.
- Capture before/open/dismissed states on the full `pixel8Api35` image with `requireRealSystemBars=true`; retain PNGs and measured geometry under `app/src/androidTest/assets/insets-1166/`. ATD records geometry only.
- Rerun the existing portrait and landscape modal IME checks after the activity-wide change. Run focused activity inset JVM coverage, lint, debug assembly and androidTest compilation; format before committing.
- This corrects local keyboard/window behavior with an existing populated fake fixture, not a new daemon-facing flow; real-Claude/rung-3 and scripted/rung-4 scenarios add no relevant proof. The dispatcher still owns its full acceptance suites.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-input-bar.md` § IME handling with the verified activity soft-input policy and single IME-padding owner; update `docs/knowledge/features/development-verification.md` § Compose evidence with the populated keyboard regression and `app/src/androidTest/assets/insets-1166/` captures. The ticket contains no separate documentation-only acceptance criterion.

## Security review

**Verdict:** PASS

- [Trust boundaries / Android surface] `MainActivity` gains only a static soft-input policy; exported status, intent filters, `NotificationTap.target` validation and routing remain unchanged. No untrusted input selects window flags.
- [Tokens / storage] No credential or persistence changes. Draft text stays in the existing heap-only composer path; tests must not introduce saveable draft state. Evidence contains only existing fake messages and a fixed test draft.
- [Cryptography / network] No wire, cryptographic, relay, authentication or I/O changes. The device fixture uses demo data and no real daemon or credentials.
- [Logs / telemetry] No new production log path; do not log drafts or message bodies to diagnose geometry. Test metadata contains dimensions, inset values and static state names only.
- [Concurrency] No new jobs or flows. Existing list effects and lifecycle connection cancellation remain intact; IME dismissal must not reset their state.
- [Threat model] Third-party keyboards and screenshot/accessibility access are existing platform boundaries; this policy does not grant new access. Real captures must be limited to the synthetic fixture and remain test assets.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

## Revisions

- 2026-09-27: The existing demo's last message has an animated streaming presentation. The regression now populates a temporary channel with 30 fixed messages through `FakeConversationRepository` before launching the activity, then deletes it after the test. No messages are sent or changed during keyboard/anchor checks. Both newest and older anchors are explicitly established; actual touch scrolling is tested only after preservation checks.
- 2026-09-27: API 33 exposed the known Bluetooth crash dialog stealing focus. `openKeyboard` reuses `MobileModalTest`'s close-system-dialogs recovery before requesting the real test IME. Full-image display setup waits for a shorter idle interval because unrelated system events can prevent a continuous one-second interval. Neither adjustment changes production behavior.
- 2026-09-27: Controlled device runs confirmed `adjustResize` alone fixes the observed pan; `MainActivity.onCreate` and `ThreadScreen` remain unchanged. Both fixed-message tests fail against the original unspecified policy on base `dfcb3a8a` and pass with the setting. Six API 33 activity/modal checks and both API 35 real-bar regressions passed without skips; captures and XML are retained in `app/src/androidTest/assets/insets-1166/`, with the intentional failures under `baseline/`. Scoped `MainActivityInsetsTest`, lint, debug assembly, androidTest compilation and Spotless checks passed. No new visual tokens or assets were needed.
