# 1025 — Sign release bundles with an upload key; version code per commit

## Files read

- `app/build.gradle.kts` → `GitShaValueSource`, `defaultConfig`, `buildTypes.release` — the pattern the version-code source mirrors, and the debug-signed release type this ticket changes.
- `.gitignore` — has no keystore patterns yet.
- `gradle.properties` — configuration cache is not enabled, but the guard must stay safe under it (ticket note).

## Design source

N/A — build configuration only; nothing UI-visible.

## Context

Play App Signing registers the first upload's key as the upload key, and rejects any upload whose `versionCode` is not higher than the last. Today release is debug-signed and `versionCode = 1`. Overlap: `feature/955` also edits `app/build.gradle.kts`, but only the `managedDevices` block; this change stays out of it.

## Change

All in `app/build.gradle.kts`, plus two `.gitignore` lines (`*.jks`, `*.keystore`), which the ticket requires even though `.gitignore` is outside the builder's usual file list.

- **Version code.** A new `GitCommitCountValueSource : ValueSource<Int, None>` runs `git rev-list --count HEAD` the way `GitShaValueSource` runs `rev-parse`, returning `1` (today's value) when git fails or prints no integer. `defaultConfig.versionCode` = `-PversionCode` (parsed with `toInt()`, so a malformed override fails loudly) or else the value source. It stays in `defaultConfig`, so debug carries it too.
- **Upload signing.** The four property names `pyry.upload.storeFile`, `pyry.upload.storePassword`, `pyry.upload.keyAlias`, `pyry.upload.keyPassword` are read through `providers.gradleProperty`, so `-P`, `~/.gradle/gradle.properties` and `ORG_GRADLE_PROJECT_*` all work. A blank value counts as missing. When none is missing, a `release` signing config is created from them and `buildTypes.release` uses it; otherwise `release` keeps `signingConfigs.getByName("debug")`. Configuration never fails either way.
- **Bundle guard.** A task `checkReleaseUploadSigning` captures the list of missing property names at configuration (a plain `List<String>`, no script references, so configuration-cache safe) and throws a `GradleException` naming each one in its action when the list is non-empty. `packageReleaseBundle` — the task that writes the intermediary bundle before `signReleaseBundle` writes `app-release.aab` — depends on it, wired lazily via `tasks.configureEach` by name. `bundleRelease` therefore fails before any `.aab` is written, and `assembleRelease`, `assembleDebug`, `test` and `lint` never reach the guard.

## Testing strategy

No new logic reaches `app/src`, so there is no unit test; proof is by build commands against a throwaway keystore made with `keytool` in a scratch directory outside the repository:

- all four properties set → `./gradlew bundleRelease` succeeds; `jarsigner -verify -verbose -certs` on `app-release.aab` shows the throwaway certificate's DN.
- one property dropped → `./gradlew assembleRelease` succeeds (debug key); `./gradlew bundleRelease` fails naming the missing property, and no `app-release.aab` exists afterwards.
- `aapt2 dump badging` on the APK shows `versionCode` = `git rev-list --count HEAD`, and `N` under `-PversionCode=N`.
- `./gradlew assembleDebug lint` plus a scoped unit test run; the full `check` is the verifier's gate.

## Documentation handoff

Pending for the documentation stage: README gains a "Release builds" section covering the four `pyry.upload.*` properties, injecting the passwords with `op run` via `ORG_GRADLE_PROJECT_`-prefixed variables, the version code rule (`git rev-list --count HEAD`, overridable with `-PversionCode=N`), the full-clone requirement, and the `./gradlew bundleRelease` command. No real path or secret.
