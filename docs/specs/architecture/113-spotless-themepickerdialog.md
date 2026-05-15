# #113 — Fix spotlessCheck violation in ThemePickerDialog.kt

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt:40-60` — the violation site: the `.selectable(...)` → `.padding(...)` chain inside the `Row` modifier
- `.github/workflows/ci.yml` — confirms `./gradlew check assembleDebug` is the gate that's currently failing on `main`

## Context

`main` has been red since #87 merged. `./gradlew spotlessCheck` fails on `ThemePickerDialog.kt:51-52` because ktlint wants the closing `)` of `.selectable(...)` and the chained `.padding(vertical = 8.dp),` collapsed onto a single line. This is a pure-formatting fix produced mechanically by `./gradlew spotlessApply`; no design work.

## Design

Run `./gradlew spotlessApply`. That rewrites lines 51-52 from

```kotlin
                                )
                                .padding(vertical = 8.dp),
```

to

```kotlin
                                ).padding(vertical = 8.dp),
```

No other change. No semantic effect — `Modifier.selectable(...).padding(...)` chains identically regardless of line break.

## Verification

1. `./gradlew spotlessCheck` exits 0.
2. `./gradlew check` exits 0.
3. `git status` shows exactly one modified file: `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt`.

If step 3 shows other modified files, `spotlessApply` found additional pre-existing violations. Stop, revert those files, and file a follow-up ticket — AC explicitly scopes this ticket to the single violation site.

## State + concurrency model

N/A — formatting change.

## Error handling

N/A — formatting change.

## Testing strategy

No new tests. Existing tests must still pass (`./gradlew test`); covered by `./gradlew check`. The composable's RadioButton selectable rows and `padding(vertical = 8.dp)` behave identically — Compose modifier chaining is whitespace-insensitive.

## Open questions

None.
