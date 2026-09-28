# Edit host labels at large text (#1229)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `IdentityRow`, `EditHostModal` — label/value layout and existing display-text bound.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` → `enlargedTextAtCompactWidthKeepsLabelsValuesAndActionsReachable` — ignored compact-width regression and native-graphics assertions.
- `docs/knowledge/features/mobile-modal.md` § Layout and theme — the shell owns scrolling and action reachability.
- `docs/knowledge/features/shared-typography.md` § Verification and limits — shared type metrics remain unchanged; records this failure.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes — shared screen test runs under Robolectric and on device.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The dark modal draws two horizontal label/value rows above the host-name field. The labels use the emphasized M3 `labelLarge` role, values use `bodyMedium`, and each row has a 10 dp gap; the long sample identity is ellipsized. Keep this shape at ordinary scale, allowing a label to grow vertically when enlarged text needs more room while the value retains a bounded one-line slot.

## Change

Constrain the `IdentityRow` label to a responsive share of the row so enlarged text can wrap fully rather than paint outside its measured bounds. Leave the value's one-line ellipsis, the text clamping in `EditHostModal`, and the shell's controls unchanged. Re-enable the existing regression test.

## Testing strategy

- Remove `@Ignore`, run `EditHostModalTest`'s enlarged-text case to see the existing failure, then rerun it after the layout change under Robolectric native graphics and on the managed API 33 device.
- Run the touched test class, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and Spotless formatting.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/mobile-modal.md` § Layout and theme and `docs/knowledge/features/shared-typography.md` § Verification and limits to replace the open #1229 caveat with the verified layout behavior. The ticket specifies no other documentation requirement.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries and UI leakage] `EditHostModal` already bounds identity and relay display text before `IdentityRow`; this change only constrains geometry and preserves plain `Text` rendering and merged row semantics. No new inbound data or rendering sink is introduced.
- [Tokens, storage, and crypto] No changes to credentials, persistence, or cryptography. The existing caller obligation to pass display text remains.
- [Android surface, network, and I/O] No new intents, network operations, files, or permissions.
- [Errors and logs] No new error path or log; values remain absent from logs and the generic error path is unchanged.
- [Concurrency] No jobs or state transitions are added. The shell and host editor retain ownership of interactions.
- [Threat model] Hostile relay or daemon text still passes through the existing bound before display; this ticket changes only measurement at the final UI surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-28
