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

## Security review

**Verdict:** PASS (two SHOULD FIX items, both implemented; see Revisions)

**Findings:**

- [Trust boundaries] No findings. The only inputs are the four `pyry.upload.*` Gradle properties and `-PversionCode`, all supplied by whoever runs the build. That is the same trust level as the build script itself. `GitCommitCountValueSource` runs `git` from `PATH` exactly as `GitShaValueSource` already does, and accepts only a positive integer from its output (`toIntOrNull`), so a strange repository state yields `1`, never an arbitrary value. Nothing reaches `app/src` or the runtime app.
- [Tokens, secrets] No findings in the design. The two passwords are read through `providers.gradleProperty` into the `release` signing config and nowhere else. The guard captures only the list of *missing names*, never a value, and the `GradleException` message joins those names. Checked with a throwaway PKCS12 keystore whose password was a unique canary, injected as `ORG_GRADLE_PROJECT_`-prefixed environment variables: the canary appeared in none of the `bundleRelease --info` output, `help --debug` output (8277 lines), `app/build/`, the project `.gradle/` state (including execution history), or the Gradle daemon logs written during the run. The build has no Develocity or build-scan plugin (`settings.gradle.kts`), so nothing is published. The operator-facing guidance on *where* the passwords come from goes to the documentation handoff: `-P` on the command line shows in the host's process list, and `~/.gradle/gradle.properties` is plaintext on disk, so the README should steer passwords through `op run` environment injection. Upload-key rotation and reset go through Play Console and are OUT OF SCOPE (operator process, no code).
- [File / storage] Findings on path resolution, no MUST FIX. `storeFile` goes through `file()`, which resolves a relative path against `app/` and does not expand `~`. Both cases fail loudly rather than degrade. `~/upload.jks` makes `validateSigningRelease` fail with `Keystore file '<worktree>/app/~/upload.jks' not found`. The message names the path, which is not a secret, and does not fall back to the debug key, so no bundle is written. A relative path that does resolve would put the keystore inside the working tree, which the ignore patterns cover (next item). The README handoff asks for an absolute path. Expanding `~` in the build script was rejected because the loud failure already prevents misuse and the fix would only add code.
- [File / storage — ignore patterns] SHOULD FIX, implemented. `*.jks` and `*.keystore` miss PKCS12, which has been `keytool`'s default store type since JDK 9 and is commonly saved as `.p12` or `.pfx`. `.gitignore` now also covers `*.p12` and `*.pfx`. `git check-ignore` confirms all four, including a file dropped at `app/upload.jks`. No tracked file matches any of them.
- [Inter-process / Android surface] No findings. No manifest, component, intent or permission change. `versionCode` now varies per commit in debug and release, and nothing in `app/src` reads `BuildConfig.VERSION_CODE`. The Firebase key restriction needs the Play signing key's fingerprint. The ticket assigns that to the operator, so it is OUT OF SCOPE here.
- [Crypto] No findings. Signing is AGP's `apksigner` and `jarsigner` path with the operator's key, with no primitive chosen here. Play App Signing holds the app signing key, and this key is only the upload key.
- [Network & I/O] Not applicable. The build makes no network call beyond Gradle's existing dependency resolution.
- [Error messages, logs] No findings. The guard's message is a static sentence plus property names. AGP's own signing errors name the keystore path and alias, never a password (verified above for the missing-file case).
- [Concurrency] Not applicable. The two value sources run once at configuration. The guard task holds a plain `List<String>` and no script reference, so it stays configuration-cache-safe.
- [Threat model — debug-key fallback reaching Play] SHOULD FIX, implemented. The fallback must never produce an upload-bound artifact. Play takes new apps only as an `.aab`, and `bundleDebug` produces a debuggable bundle that Play rejects. The one path at risk is therefore `bundleRelease`, which the guard blocks: with any property missing it fails at `checkReleaseUploadSigning`, before `packageReleaseBundle`, and no `app-release.aab` exists afterwards (verified). The weakness was the wiring. `tasks.configureEach { if (name == "packageReleaseBundle") … }` matches silently, so if an AGP upgrade renamed the task, the guard would lapse without anyone noticing and a debug-signed release bundle could be written. The wiring is now `afterEvaluate { tasks.named("packageReleaseBundle") { … } }`, and a rename fails configuration of every task. Verified by renaming the target temporarily: `Task with name 'packageReleaseBundleRenamed' not found in project ':app'`. A debug-signed `assembleRelease` APK remains possible by design (ticket: local release builds keep working), and it is not upload-bound.
- [Threat model — configuration cache] OUT OF SCOPE, recorded for whoever enables it. The configuration cache is off in `gradle.properties`, so no configuration-time value is persisted today. If it is turned on, the signing config's values become part of the stored cache entry under `.gradle/configuration-cache/`. Re-check the canary scan then.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- 2026-09-24, verifier FAIL on PR #1028 (the plan lacked the security review the `security-sensitive` label requires). The review above was run after implementation, because the plan commit `dea1d738` already preceded the code. It changed two things:
  - **Bundle guard wiring.** Before: `tasks.configureEach` matched `packageReleaseBundle` by name. Now: `afterEvaluate { tasks.named("packageReleaseBundle") }`, so a renamed AGP task fails configuration loudly instead of silently dropping the guard. The guard task, its message and its configuration-cache-safe capture are unchanged.
  - **`.gitignore`.** It adds `*.p12` and `*.pfx` beside `*.jks` and `*.keystore`.
  - **Documentation handoff addition.** The README should tell the operator to use an absolute `pyry.upload.storeFile` path, because `~` is not expanded and a relative path resolves against `app/`. It should also say to prefer `op run` environment injection for the passwords, because `-P` shows in the process list and `~/.gradle/gradle.properties` is plaintext on disk.
