# #1280 — Format the thread status reading condition

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `hasReading` expression in the composer status band — sole formatter violation and change target.
- `docs/knowledge/features/thread-screen.md` → status band context; the visual contract does not change.
- `docs/knowledge/features/development-verification.md` → forced `spotlessCheck` is the formatter proof even when Gradle has cached earlier results.

## Change

Put each existing Boolean condition of `hasReading` on its own line, keeping the same order and operators. This changes only whitespace; the status band and its behavior remain identical. The non-fatal `Theme.kt` warning is outside this ticket.

## Testing strategy

Run `./gradlew spotlessCheck --rerun-tasks --console=plain` before and after the edit to show the formatter failure and its resolution. Check the diff for whitespace-only Kotlin changes, then run the focused build gates. No new logic assertion is needed for a whitespace-only edit.

## Documentation handoff

None requested by the ticket.
