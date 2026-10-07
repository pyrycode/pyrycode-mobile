# #1878 — Scope the side-copy timestamp assertion

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SideMessageCopy.kt`: `assertSideMessageCopy` selects the source row but checks timestamps across the screen.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the ping method checks the displayed reply and copies both repository messages.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: the held-stream method copies streaming, sent and finished messages.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt`: `formatShortDateTime` supplies the locale/zone-specific timestamp and clipboard writes cap source at 100,000 characters.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageBubble` owns a non-merging `message-row`, independent side copy and host-controlled metadata visibility.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRowToggleTest.kt`: real bubble fixtures and injectable clipboard patterns.
- `docs/knowledge/features/message-bubble.md`, `message-bubble-testing.md`, `development-verification-gates.md`: metadata is timestamp text; use shared tests for deterministic Compose coverage, device tests for daemon/relay traffic.
- `docs/specs/architecture/1817-side-copy-timestamp-toggle.md`: the existing helper and source-copy contract.

## Change

Move the existing helper to `app/src/sharedTest/java/de/pyryco/mobile/e2e/SideMessageCopy.kt` so JVM regression tests exercise the same assertion that the live and scripted tests use. Keep its package, signature and five call sites unchanged. Reuse its source-row matcher for both timestamp assertions, matching the complete `formatShortDateTime` value for the message with the current device locale and timezone in the unmerged tree. Banners, message-body separators and timestamps in other rows must not count. Preserve the pointer tap, clipboard baseline and exact `message.content.take(100_000)` comparison. No production or visual changes, new dependencies or live-test skips.

This is one test-selector deliverable: about 220 written lines including the plan, no production files, no exported production types, no coordinated caller updates and no new state-machine branches. Codegraph found no callers; repository search confirms five. Remote feature branches have no overlap with the helper or planned regression files.

## Testing strategy

Add `SideMessageCopyTest` under shared tests, mounting real bubbles in a scrollable list with platform clipboard access. First run its separator/banner regression against the unchanged helper and observe the false assertion. Then repair the helper and rerun. Cover hidden timestamps with banner and source-body separators, an unrelated row's visible timestamp, exact source including trailing whitespace, and the 100,000-character cap. Negative controls must reject a timestamp already visible before copying and one revealed by a clipboard write after copying, proving both checks remain effective.

Run focused shared regression and existing metadata/copy coverage, lint, assembly, Android-test compilation and forced Spotless. Run the affected deterministic `stream` scenario for real device clipboard and isolated daemon/relay integration (the existing device-only reason), then the named live ping repair method through the isolated live gate. Push before the final whole unit/shared suite, assembly and `scripts/pre-verify.py --gradle` after merging main.

The dispatcher owns fresh full live-gate acceptance after verification: the named ping method must run and pass with executed/failed/skipped counts. List `all` under the PR's Live tests because this helper is shared by live tests; preserve `needs-real-claude`.
