# #740 — e2e: reach Archived through the list's own archive entry

Short plan (§ A4): one new always-on scenario plus curated live-suite registration. No production code.

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_archiveRestore_roundTripsListMembership` (its step 9 is the arrival analogue), `awaitChannelList`, the companion's `CD_OPEN_SETTINGS` / `ARCHIVED_TITLE` block. The new test and the `CD_OPEN_ARCHIVE` constant land here.
- `app/src/main/res/values/strings.xml` → `cd_open_archive = "Open archive"`, the string the constant mirrors.
- `scripts/e2e-emulator.sh` → the `LIVE` branch's `TEST_TARGET` class#method list. LIVE runs a curated list, not the whole class, so a method absent from it never runs under `android-test-gate.py live` (AC 2).
- `scripts/android-test-gate.py` → `main`'s live `minimum = 8`, the executed-count floor for the live gate.

## Design source

N/A — no new surface; this adds a test against the bar #737 already shipped to the design.

## Change

Add `interactiveTurn_listArchiveEntry_opensArchived` to `InteractiveStreamE2ETest`: `awaitChannelList()`, assert `ARCHIVED_TITLE` has zero nodes (so the arrival is a genuine inversion — the list draws no "Archived" text), tap `hasContentDescription(CD_OPEN_ARCHIVE)`, `waitUntil` `ARCHIVED_TITLE` appears, assert it displayed. No `awaitConnected`, no seeded conversation, no prompt, no claude turn — the bar is on every draw of the list. Add `CD_OPEN_ARCHIVE = "Open archive"` beside `CD_OPEN_SETTINGS` with its `strings.xml` sync note. `interactiveTurn_archiveRestore_roundTripsListMembership` stays unchanged.

Register the method in the LIVE `TEST_TARGET` list in `scripts/e2e-emulator.sh` (octet → nonet, still 3 turns; update the adjacent comments and PASS line) and raise the live gate's `minimum` from 8 to 9 in `scripts/android-test-gate.py` so a run that silently drops the new method fails the count check.

## Testing strategy

The scenario is itself the proof. Compile with `./gradlew compileDebugAndroidTestKotlin`. The live suite needs real claude and is the dispatcher's post-verifier gate (`needs-real-claude`); the builder does not run it.
