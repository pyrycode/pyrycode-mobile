# #1722 — Version name carries the build number

## Files read

- `app/build.gradle.kts` — `defaultConfig.versionCode` resolves the override or commit count; `versionName` is fixed.
- `docs/knowledge/features/about-screen.md` — Versioning requires three decimal parts; About and the client identity consume the generated value.
- `app/src/test/java/de/pyryco/mobile/di/ClientVersionTest.kt` — checks the generated version against the client format contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/AboutScreenTest.kt` — checks the version row consumes `BuildConfig.VERSION_NAME`.

## Design source

N/A — build metadata only; no layout or interaction change. No in-flight branch overlaps `app/build.gradle.kts`.

## Change

Set `defaultConfig.versionName` to `"1.0.$versionCode"`, reading the already resolved version code. Both the commit-count default and `-PversionCode=N` therefore produce `1.0.N`. Keep the resolver and all other configuration unchanged. One deliverable, one production line, no new types, signatures, state or failure branches; including this plan, total written work stays below 30 lines.

## Testing strategy

Before implementation, generate the release manifest with `-PversionCode=3901` and assert the code/name pair; observe the fixed name fail. After implementation, inspect release APK metadata for both the commit-count default and the override, confirming the manifest version Android settings reads. Run existing `ClientVersionTest` and `AboutScreenTest` with the override, plus `lint`, `assembleDebug`, `spotlessApply` and forced `spotlessCheck`. No new test source is needed for this build-property change. Android settings device display remains a later-stage manual acceptance check.
