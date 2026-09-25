# Mobile parity release candidate (#680)

## Files read

- `scripts/e2e-emulator.sh` → the `LIVE` branch that builds `TEST_TARGET`: the curated real-Claude list the live gate runs.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, `main`, `combine_reports`: the live gate's executed-test floor and its count checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → the `interactiveTurn_*` methods: the five suites' live scenarios.
- `app/build.gradle.kts` → `GitShaValueSource`, `USE_RELAY_REPOSITORY`: the Settings version row shows `git rev-parse --short HEAD` at build time; the real-data binding is the default.
- `docs/specs/architecture/528-live-mobile-baseline.md`: the previous revision-linked live result and the record's shape.
- `docs/knowledge/features/development-verification.md` § "Emulator and real evidence": what screenshots and records may contain.
- Suite tickets #847–#850, #965–#967, #674 (#1016, #1017), #676 (#1085–#1090), #684: the methods each suite added to the live list.

## Design source

**Figma:** the five frames supplied on 2026-09-19.

- Scanner https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2
- Pair with code https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147
- Channel list https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8
- Conversation thread https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8
- Mobile modal shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

All five are dark 412×892 frames. The scanner is a "Pairing" top bar over a dark camera well with four corner brackets and a scan line, a `pyry pair` hint card and a "paste the pairing code" text link. Pair with code is the same top bar over a radial blue glow, with Host name and Pairing code filled fields, a full-width Pair button, Cancel and an open-source footer. The channel list has settings and archive icons, then per-host sections with a server icon, workspace folders and conversation rows with status dots, the selected row highlighted with a pencil. The thread has a back arrow, title and overflow menu, assistant and user bubbles with timestamps and copy icons, a "Thinking…" line, attachment chips, a composer and a footer of Actions, permission, model, effort and context readings. The modal shell is a full-screen "Edit host" sheet with a close button, identity and relay rows, a Host name field, Unpair host, and Cancel and OK buttons.

## Context

This ticket is the final check of the parity batch authorised on 2026-09-19. It adds no product code. It holds the verification record for one candidate commit, plus any live-list additions the five suites left out. A defect found here is filed as its own issue and listed as a gap; it is not fixed in this PR.

## Candidate definition

The ticket names the PR head as the candidate, but the record cannot contain the SHA of the commit that holds it, and the Settings version row shows the SHA the APK was built at. The candidate is therefore this plan's commit: the first commit on `feature/680`. Every later commit on the branch changes only this file, so the PR head's product tree (`app/`, `scripts/`, `gradle/`, build scripts) is byte-identical to the candidate's. The record states the `git diff --stat` that shows it. The dispatcher's gates on the PR head therefore run the candidate's product.

## Design

The record has four sections and a gap list, filled in below after this commit:

1. **Candidate**: the commit, its main parent, and the mobile and daemon revisions the gate scripts print.
2. **Gates**: `./gradlew assembleDebug test lint` once at the candidate, with its exit status and test count; the dispatcher's `ui`, `scripted-all` and `live` gates, pending until the dispatcher runs them; each suite's live methods by name, and any case a suite proves only deterministically.
3. **Layout**: emulator screenshots of the candidate compared in words with the five frames, then the keyboard, back, rotation and small-screen checks. Screenshots are not committed.
4. **Feature checks**: the passing test or observation that proves each of the seven items.
5. **APK and gaps**: the build command, commit and SHA-256 of the real-data debug APK, and one issue per gap, including push-notification status.

The live list already carries every live method from the five suites (44 methods, `LIVE_MINIMUM` 44), so no script change is planned. If the record finds a missing method, it is added to `TEST_TARGET` and `LIVE_MINIMUM` is raised with it.

## Testing strategy

No new tests. The proof is the gate run at the candidate plus emulator observations. The layout checks use an emulator on this machine. The channel list and thread need a paired host with conversations; the builder does not run a daemon with real Claude, so those two frames are compared on the same commit's demo binding (`-PuseRelayRepository=false`), which renders the same composables over seeded data. The scanner, pair-with-code and Settings version row are observed on the real-data APK itself.

## Documentation handoff

Pending for the documentation stage, after the live gate passes:

- `docs/e2e-interactive-stream.md` § Verification status: name this candidate as the current live baseline, with the date, mobile and daemon revisions and each executed method's outcome, as #528 did.
- `README.md` § Status: one line saying the parity candidate was verified at this commit, linking this record's gap list, and stating push-notification status per the gap list below.

## Open questions

- Whether the emulator on this machine can host the layout checks inside the run budget. Resolved in the record below.
