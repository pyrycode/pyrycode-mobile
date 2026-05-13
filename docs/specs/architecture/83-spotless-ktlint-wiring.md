# Spec — Spotless + ktlint formatter wiring (#83)

## Files to read first

- `build.gradle.kts` (root, full file — ~4 lines) — current root plugins block; Spotless declaration lands here.
- `app/build.gradle.kts` — confirm there is no formatter config to remove; Spotless is wired at root, not here.
- `gradle/libs.versions.toml` (full file — ~60 lines) — version catalog. Add `spotless` + `ktlint` to `[versions]` and `spotless` to `[plugins]`. Follow the existing alphabetical-by-key ordering within each section.
- `settings.gradle.kts` — confirm `pluginManagement.repositories` includes `gradlePluginPortal()` (it does). Spotless is hosted there; no repository change is needed.
- `CLAUDE.md` (root) — "Test-first" convention applies broadly, but does not apply to mechanical formatter wiring; do not author a "test" for Spotless. The Acceptance Criteria are themselves the test (`./gradlew spotlessCheck` and `./gradlew check`).

## Design source

N/A — tooling/infra ticket; no UI surface.

## Context

Three Figma-anchored PRs (#57, #61, #60) shipped consistent style by chance. Stochastic agent runs will drift over many tickets without a deterministic gate. Per [[instruction-design#Belt-and-Suspenders Means Different Fabric]], a deterministic formatter is the missing fabric next to the existing stochastic developer + code-review checks.

This ticket establishes **only the formatter**. Compose Lint Checks and the CI workflow that runs `./gradlew check` on PRs land in separate tickets (split from #72).

## Design

### Where Spotless is applied

Apply Spotless **at the root `build.gradle.kts`** and target files by glob (`**/*.kt`, `**/*.gradle.kts`, `**/*.md`, etc.). Rationale:

- Single-module project today (`include(":app")`). A root-level Spotless block is simpler than a `subprojects { ... }` block and equally correct.
- Non-Kotlin formats (Markdown, JSON, YAML) live outside `app/` (e.g. `docs/**/*.md`, `README.md`). Root-level globs reach them naturally; module-level globs would not.
- Adding a second module later does not require moving Spotless — the root globs already walk the new module's source tree.

### Version catalog additions (`gradle/libs.versions.toml`)

Under `[versions]`, add (alphabetical placement):

```toml
ktlint = "1.5.0"
spotless = "7.0.4"
```

Under `[plugins]`, add (alphabetical placement):

```toml
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

Note for the developer: these are the architect's pinned baselines. Before committing, run `./gradlew spotlessCheck` and confirm both plugins resolve. If either fails to resolve (yanked / unpublished version), use the latest published version on `plugins.gradle.org` (Spotless: `com.diffplug.spotless`) and `mvnrepository.com` (ktlint: `com.pinterest.ktlint:ktlint-cli` or the equivalent artifact Spotless consumes) and update the catalog. Do not unpin or float versions.

### Root `build.gradle.kts` shape

The final root file is structurally:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)            // ← applied at root, not `apply false`
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("**/*.md", "**/*.json", "**/*.yml", "**/*.yaml")
        targetExclude(
            "**/build/**",
            "**/.gradle/**",
            "**/node_modules/**",
            "gradle/wrapper/**"
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
```

Notes on the shape:

- **`apply false` is deliberately omitted for Spotless.** The other two plugins are applied per-module (in `app/build.gradle.kts`); Spotless is the only plugin that runs at the root project itself.
- **`ktlint(libs.versions.ktlint.get())`** — Spotless's `ktlint()` accessor accepts a version string. Wiring it through the catalog keeps the ktlint version pinned in one place (the catalog), not duplicated in build script literals.
- **No `.editorconfig` is created.** Per AC and Technical Notes, defaults only. Surface drift; do not paper over it.
- **No custom rules, no `ktlint().setUseExperimental(true)`, no rule suppressions.** Defaults only.
- **`format("misc")`** uses Spotless's built-in generic formatter (no extra dependencies) — `trimTrailingWhitespace` + `endWithNewline`. This satisfies the AC ("Markdown / JSON / YAML formatters configured") without introducing a Node.js / Prettier dependency or a Jackson dependency. The AC does not require structural reformatting of JSON/YAML; it requires *a formatter configured*. Trim + newline is the minimum-dependency choice and aligns with the "defaults only" Technical Note.

### Task wiring (verify, do not configure)

Spotless auto-wires `spotlessCheck` into the standard `check` task lifecycle when the plugin is applied. No manual `tasks.named("check") { dependsOn("spotlessCheck") }` is needed. The developer verifies by running `./gradlew check` and observing `spotlessCheck` in the executed-task list.

If for some reason the auto-wire does not happen in this Gradle / AGP version (unlikely; Spotless has done this since 6.x), add an explicit `dependsOn` at the root — but verify the auto-wire path first; don't pre-emptively add the explicit hook.

### The auto-fix pass

After wiring is in place and `./gradlew spotlessCheck` reports formatting violations on existing files (it will — none of the current ~35 `.kt` files have been ktlint-formatted, and Markdown/etc. files may have trailing whitespace), the developer runs:

```bash
./gradlew spotlessApply
```

…once, reviews the diff, and commits **the wiring + the auto-fix in the same PR**. Per the ticket body: "The auto-fix commit may touch many existing files; that's expected and lands as part of this PR."

Two-commit shape is acceptable inside the PR (one commit for wiring, one for the auto-fix pass) and may be helpful for code review, but a single squashed commit is also fine. Either way, `main` is green on `./gradlew spotlessCheck` after the PR merges.

### Files the auto-fix pass will touch (approximate)

- `app/src/main/java/de/pyryco/mobile/**/*.kt` — ~25 files
- `app/src/test/java/de/pyryco/mobile/**/*.kt` — ~5 files
- `app/src/androidTest/java/de/pyryco/mobile/**/*.kt` — ~1 file
- `*.gradle.kts` (root, `app/`, `settings.gradle.kts`) — 3 files
- `*.md` (README, CLAUDE, docs) — many, but only those with trailing whitespace or missing final newline; most will be untouched.
- `gradle/libs.versions.toml` — left alone (TOML is not in scope).

The expected diff is mechanical: trailing-whitespace trims, final-newline additions, ktlint's default formatting (import ordering, trailing commas in some positions, spacing). **Do not hand-edit files to "improve" the auto-fix output.** If ktlint's default disagrees with what's in the repo today, the repo loses — that's the whole point of the gate.

## State + concurrency model

N/A — build-tool wiring.

## Error handling

N/A — Spotless surfaces formatting failures via Gradle task failure; that is the entire error-handling model.

If `spotlessCheck` fails on CI after this lands (when CI exists), the failure message points at the offending file and rule. Developers run `./gradlew spotlessApply` locally and recommit.

## Testing strategy

No unit or instrumented tests are added. The AC defines the tests:

1. `./gradlew spotlessCheck` passes after the auto-fix pass.
2. `./gradlew check` (which now invokes `spotlessCheck`) is green.

Both run locally as the final verification before the developer commits. The instrumented-test suite (`./gradlew connectedAndroidTest`) is not in scope and may be skipped — it requires a device.

## Open questions

- **Spotless / ktlint version drift.** Pinned to `spotless = 7.0.4` and `ktlint = 1.5.0` (architect's baseline). If either version is unavailable or yanked at implementation time, the developer picks the latest published version and updates the catalog — do not unpin. Document any version bump in the PR description.
- **No `.editorconfig` decision is deliberate.** A future ticket may add one if specific style decisions (e.g. max line length) need to be encoded. This ticket is "defaults only".
- **`format("misc")` vs Prettier / Jackson.** The minimum-dependency choice is trim + newline. If a future ticket wants structurally-formatted JSON or Prettier-formatted Markdown, it can swap the `format("misc")` block. Not in scope here.
- **Gradle Wrapper Kotlin files.** `gradle/wrapper/**` is excluded from the misc glob (wrapper scripts are not ours to reformat). Verify there are no `.kt` / `.gradle.kts` files inside `gradle/wrapper/` (there shouldn't be) so the Kotlin globs don't accidentally reach them.
