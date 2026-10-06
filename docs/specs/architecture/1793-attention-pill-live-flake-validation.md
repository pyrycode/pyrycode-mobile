# #1793: validate the landed attention-pill live-test repair

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_otherConversationAttentionPills_waitingAndFinished` and repair `46c65141a` preserve the complete scenario.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadAttention.kt`: `observeThreadAttention` delays expiry by five seconds; `rememberThreadAttention` collects within `produceState`.
- `docs/knowledge/features/thread-top-overlay.md`: Testing records the virtual-clock dependency and explicit advancement.
- `docs/knowledge/features/development-verification-compose-evidence.md`: Compose evidence explains the same virtual-time versus wall-time failure in #1664.
- `docs/e2e-interactive-stream.md`: Verification status records the named full-suite pass for #1735.
- Dispatcher `logs/claude-operator-1735-evidence/{run1-scoped-fail,run2-clock-fix}/`: XML and static attention lifecycle log events establish failure and repair.

## Change

Validate and record the existing repair, without changing production or test code. The named method is identical to tested commit `46c65141a0985346536ad07529114e9caae594d8`, an ancestor of main. Its composition-owned five-second delay used virtual time, while `waitUntil` polled against ten seconds of wall time. Slow full-suite polls exhausted that budget before virtual expiry. The repair explicitly advances `mainClock` by 5,100ms after B's turn ends. Waiting, tapping into B, the outstanding prompt, returning to A with Waiting, Finished, whole-overlay disappearance, Finished-text absence and no pill on reopening A all remain asserted. This plan is the only repository change; no in-flight branch overlaps its path.

Record the cause, log paths and counted evidence on #1793 and in the PR. In the diagnostic run, `thread_attention_finish_shown` occurred at 12:37:46.723, with no expiry before the 10,000ms timeout at 12:37:57.534. In the repaired run, shown at 12:54:01.482 was followed by expired at 12:54:01.584 after clock advancement.

## Testing strategy

Reuse the existing dispatcher-owned full-live-suite XML, as the ticket explicitly permits for unchanged code. Independently counted `run2-clock-fix/dispatcher.xml`: 56 executed, 55 passed, 1 failed, 0 errors, 0 skipped. The named attention method executed and passed without failure/error/skip children. The sole failure was the separate Stop flake #1721; this records a named-method pass in the full suite, not an all-green live suite. The operator evidence identifies the tested commit above, daemon `a438db4b` and Claude Code 2.1.280.

Run existing `ThreadAttentionTest`, `ThreadAttentionNoticeTest` and `ThreadAttentionNavigationTest` as focused regression checks. No new logic needs a new red/green test, device test, scripted fixture or repair. Run builder formatting, lint, build and final unit/compile/pre-verify checks after the last merge of main. Preserve `needs-real-claude` and list the named method for any later dispatcher-owned gate; do not claim a fresh live run. Existing product documentation already contains the repair and evidence, so no documentation requirement remains pending.
