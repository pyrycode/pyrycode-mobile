# Spec: GitHub Actions CI — `check` + `assembleDebug` (#85)

## Files to read first

- `build.gradle.kts` (full, ~30 lines) — Spotless config; **note the `format("misc")` block targets `**/*.yml`**, so the new workflow file is linted by `./gradlew spotlessCheck`. End the YAML with a newline and avoid trailing whitespace, or run `./gradlew spotlessApply` after writing it.
- `app/build.gradle.kts` (lint + test config) — confirms `lintChecks(libs.compose.lint.checks)` and `lint { abortOnError = true }` (or equivalent) are wired; `./gradlew check` should fail on lint errors. The workflow does not need to re-invoke lint separately.
- `gradle/libs.versions.toml` — `agp = "9.2.1"`, `kotlin = "2.2.10"`, ktlint `1.5.0`, spotless `7.0.4`. Used to confirm JDK requirement (see Open questions).
- `gradle/wrapper/gradle-wrapper.properties` — `gradle-9.4.1-bin.zip`. Wrapper is committed; `./gradlew` is the entry point in CI.
- `settings.gradle.kts:16` — `foojay-resolver-convention` is configured, so Gradle will auto-provision any toolchain JDK the build requests. The host JDK in CI only needs to satisfy Gradle/AGP itself, not the project's `JavaVersion.VERSION_11` compile target.

## Context

PRs and pushes to `main` currently rely on the developer agent's self-run of `./gradlew test && lint && assembleDebug` plus a code-review verification. Both are stochastic. Spotless (#83) and Compose Lint Checks (#84) have landed and are wired into `./gradlew check`, so a single deterministic CI invocation now covers formatting, lint, unit tests, and a debug build.

This ticket adds the workflow file and nothing else. No branch protection rule (deferred — informal "wait for green" is acceptable for now).

## Design

### Single file: `.github/workflows/ci.yml`

**Triggers**

- `pull_request` (default events: opened, synchronize, reopened). **NOT** `pull_request_target` — that variant runs in the base-repo context with secrets and is unsafe for fork PRs.
- `push` to `main`.

**Concurrency**

- `concurrency.group: ci-${{ github.ref }}` with `cancel-in-progress: true` on PR refs, so successive pushes to the same PR cancel the prior run. (Don't cancel on `main` pushes — let merged commits complete.)

**Permissions**

- Top-level `permissions: contents: read` (least-privilege). No write permissions are needed.

**Jobs**

A single job `build` running on `ubuntu-latest` with these steps in order:

1. **Checkout** — `actions/checkout@v4`, default fetch depth (1 is fine; we don't need history for `check assembleDebug`).
2. **Gradle Wrapper validation** — `gradle/actions/wrapper-validation@v4` (the modern replacement for the standalone `gradle/wrapper-validation-action`; also exposed via a flag on `setup-gradle@v4`, but a dedicated step is clearer in the log).
3. **Setup JDK** — `actions/setup-java@v4` with `distribution: temurin`, `java-version: 17`. (See Open questions for the JDK 17 vs 21 decision.) Do not set `cache: gradle` here — `setup-gradle` handles caching.
4. **Setup Gradle** — `gradle/actions/setup-gradle@v4`. No `arguments` input; we invoke Gradle explicitly in the next step so the failure surface is clearer. Default caching is fine (caches `~/.gradle/caches`, `~/.gradle/wrapper`, and the configuration cache).
5. **Run `./gradlew check assembleDebug`** — single `run:` step. `check` runs Spotless + lint + unit tests; `assembleDebug` produces the debug APK. Combining them in one Gradle invocation is faster than two (shared configuration, daemon warmup).

**Action versions:** Use `@v4` for `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`, and `gradle/actions/wrapper-validation`. Developer should verify these are the current stable major at implementation time via `context7` or each action's GitHub repo README; if any has rolled to v5, bump.

### What the spec deliberately does NOT include

- No matrix (single OS / single JDK). Add later only if we need to test multiple JDKs.
- No `connectedAndroidTest` (requires an emulator runner, separate ticket).
- No artifact upload (APK is throwaway at this stage).
- No build scan publishing.
- No `pull_request_target`, no `workflow_dispatch`, no scheduled runs.
- No branch protection rule changes (explicitly out of scope per ticket Technical Notes).

## Failure modes

- **Wrapper validation fails** — someone tampered with `gradle/wrapper/gradle-wrapper.jar`. CI must fail; this is the security gate.
- **Spotless violation** — `./gradlew check` fails the `spotlessCheck` task. CI shows the diff; developer runs `./gradlew spotlessApply` locally and pushes the fix.
- **Lint error** — `./gradlew check` fails the `lint` task (abortOnError). CI surfaces the lint report path; developer fixes.
- **Test failure** — `./gradlew check` fails the `test` task. CI surfaces the failing test class. (Test reports are in `app/build/reports/tests/` but we don't upload them in this ticket; rerun locally for now.)
- **Gradle cache poisoning** — extremely unlikely with `setup-gradle@v4`'s scoped caches; if it happens, push an empty commit or delete the cache via the Actions UI.
- **AGP 9.2.1 / Gradle 9.4.1 requires JDK 21** — covered in Open questions; the fix is a one-line bump to `java-version: 21`.

## Testing strategy

The workflow IS the test. Validation: the PR introducing `ci.yml` must show a green check from this workflow before merge (AC #5).

Local validation before push:

- `./gradlew spotlessApply check assembleDebug` from the repo root must succeed. If `check` passes locally on JDK 17, CI on JDK 17 will pass too (same Gradle wrapper, same toolchain via foojay).
- `./gradlew spotlessCheck` specifically must pass on the new `ci.yml` (it's covered by the `misc` formatter target).

No unit / instrumented tests to write.

## Open questions

1. **JDK 17 vs 21 for the CI host.** AGP 8.x required JDK 17; AGP 9.x's exact minimum at version 9.2.1 should be confirmed at implementation time. Two ways to check:
   - Try JDK 17 in the first push. If `./gradlew` fails with a JDK-version error from AGP, bump to `java-version: 21`.
   - Or check the AGP 9.2.1 release notes via context7 / Google's developer docs before the first push.

   Default to JDK 17 in the initial workflow file; bump only if needed.

2. **`setup-gradle@v4` vs `@v3`.** The ticket body says `@v3`. As of writing, `@v4` is the current major (released early 2025). Developer should use `@v4` unless context7 / the action's README indicates otherwise; treat the ticket's `@v3` as guidance, not a hard pin.

## Out-of-scope follow-ups (do NOT do in this ticket)

- Branch protection rule requiring CI green before merge.
- `connectedAndroidTest` via an emulator runner.
- Build scan publishing to develocity.
- APK artifact upload.
- Caching tuning beyond `setup-gradle@v4` defaults.
