# Spec — Spotless + ktlint formatter wiring (#83)

## Files to read first

- `build.gradle.kts` (root, full file — ~4 lines) — current root plugins block; Spotless declaration lands here.
- `app/build.gradle.kts` — confirm there is no formatter config to remove; Spotless is wired at root, not here.
- `gradle/libs.versions.toml` (full file — ~60 lines) — version catalog. Add `spotless` + `ktlint` to `[versions]` and `spotless` to `[plugins]`. Follow the existing alphabetical-by-key ordering within each section.
- `settings.gradle.kts` — confirm `pluginManagement.repositories` includes `gradlePluginPortal()` (it does). Spotless is hosted there; no repository change is needed.
- `CLAUDE.md` (root) — "Test-first" convention applies broadly, but does not apply to mechanical formatter wiring; do not author a "test" for Spotless. The Acceptance Criteria are themselves the test (`./gradlew spotlessCheck` and `./gradlew check`).
- `.editorconfig` (root, **new file** — see § Reconciling ktlint defaults with Compose conventions). Contains a single exemption for `@Composable` functions; nothing else.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` (lines 180-189) — `Routes` object with PascalCase `const val` route constants. Will be renamed to `SCREAMING_SNAKE_CASE` (Kotlin official style) in the same auto-fix commit; references inside `MainActivity.kt` flip with the rename.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:20` — `const val DefaultScratchCwd` at top-level. Will be renamed to `DEFAULT_SCRATCH_CWD`. Run `codegraph_impact DefaultScratchCwd` before renaming to confirm consumer surface (architect's check showed it is referenced only inside the seed data layer; verify).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:223` — one seed-data string literal slightly over 140 chars; break across two `seedMsg(...)` invocations or extract the string to a local `val`.
- `app/src/test/java/de/pyryco/mobile/ExampleUnitTest.kt:5` and `app/src/androidTest/java/de/pyryco/mobile/ExampleInstrumentedTest.kt:9` — Android Studio template wildcard imports (`import org.junit.Assert.*`). Expand to the specific assertion(s) actually used (or delete the entire template file if unused — check first).
- Developer's rework comment on issue #83 (the `## needs-rework:architect — ktlint defaults vs. Compose conventions` comment) — the failure case list this spec revision is responding to. Read it before starting to confirm the inventory of remaining violations matches what `spotlessApply` reports on your machine.

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
- **A minimal `.editorconfig` IS created** at the repo root — see § Reconciling ktlint defaults with Compose conventions. It contains exactly one ktlint property: the `@Composable` exemption for `function-naming`. No other rules are configured, disabled, or relaxed.
- **No custom rules, no `ktlint().setUseExperimental(true)`, no per-file `@Suppress("ktlint:...")` annotations.** The only deviation from defaults is the single `.editorconfig` line documented below.
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

### Reconciling ktlint defaults with Compose conventions

**Context for this section:** The first developer attempt wired Spotless + ktlint exactly per the original spec, then hit 16 ktlint violations that the auto-fixer refused to mechanically resolve. The blocker is `standard:function-naming` rejecting `@Composable` functions, because every Compose composable in this repo is PascalCase — which is the **framework-mandated** Compose convention (Google samples, the Compose compiler's own diagnostics, AOSP code all use PascalCase for composables). Renaming all composables to camelCase would break the codebase's idiomatic Compose usage. This is not "style drift" — it is ktlint's default being wrong for the Compose stack. ktlint upstream acknowledges this and ships an explicit escape hatch (`ktlint_function_naming_ignore_when_annotated_with`) introduced specifically for Compose.

**Decision: add a single `.editorconfig` line for the Compose exemption.** Nothing else. This is the standard ktlint-for-Compose configuration and reverses the original spec's "no `.editorconfig`" stance, on the evidence that the original stance was incompatible with the Compose framework convention used throughout the repo.

Create `.editorconfig` at the repo root with exactly these contents:

```ini
root = true

[*.{kt,kts}]
ktlint_function_naming_ignore_when_annotated_with = Composable
```

Notes:

- `root = true` stops `.editorconfig` resolution from walking above the project. Defensive against future parent-directory `.editorconfig` files at the OS / home level.
- The exemption applies to both `.kt` and `.kts`. Composables can technically be defined in either; covering both is cheap.
- **Do not add any other ktlint properties to this file.** No `max_line_length`, no `ktlint_standard_property-naming = disabled`, no whitespace overrides. If a future ticket needs another exemption, that ticket adds it with its own evidence-based justification.
- The "Belt-and-Suspenders Means Different Fabric" principle still holds: the deterministic gate is Spotless + ktlint defaults; the `.editorconfig` line is a single, documented exemption for a framework-mandated convention, not a general escape hatch.

### Handling the remaining non-auto-fixable violations

With the Compose exemption in place, 11 of the 16 violations disappear. The other 5 are mechanical and land in the same auto-fix commit:

1. **`property-naming` on top-level / route `const val` properties** — rename to `SCREAMING_SNAKE_CASE` (the Kotlin official style for `const val`):
   - `MainActivity.kt:180` `SetupUrl` → `SETUP_URL`
   - `MainActivity.kt:183-188` `Welcome`, `Scanner`, `ChannelList`, `DiscussionList`, `ConversationThread`, `Settings` → `WELCOME`, `SCANNER`, `CHANNEL_LIST`, `DISCUSSION_LIST`, `CONVERSATION_THREAD`, `SETTINGS`
   - `Conversation.kt:20` `DefaultScratchCwd` → `DEFAULT_SCRATCH_CWD`
   - **Update all references in the same commit.** Routes are referenced from `MainActivity.kt`'s `NavHost` block (`composable(Routes.Welcome)` etc.); confirm with `codegraph_impact <symbol>` before renaming. The existing `STOP_TIMEOUT_MILLIS`, `RECENT_DISCUSSIONS_LIMIT`, `FALLBACK_CHAR`, `INITIALS_LENGTH` `const val`s in the repo already follow this convention — the renames bring the outliers into line, not introduce a new style.
   - **Do not** add a `property-naming` exemption to `.editorconfig`. The Kotlin official style applies here; the existing PascalCase names were the drift, exactly the kind ktlint defaults are supposed to surface.
2. **`no-wildcard-imports`** in the two Android Studio template files — expand `import org.junit.Assert.*` to the specific assertion(s) actually used (`import org.junit.Assert.assertEquals`, etc.). If a template file is completely unused (the `Example*Test.kt` scaffolds typically are), **delete it** rather than fixing it; check with `codegraph_callers` on each test class first to confirm nothing else references it.
3. **`max-line-length`** in `FakeConversationRepository.kt:223` — break the seed-data string by extracting it to a local `val` above the `seedMsg(...)` invocation, or by splitting the string literal across two concatenated literals. Either is fine; prefer whichever produces less diff churn.

After applying these mechanical fixes, re-run `./gradlew spotlessCheck` and confirm zero violations. If new violations appear (e.g. a rename triggered a chained-call wrapping rule), resolve them by running `./gradlew spotlessApply` again — do not hand-edit unless ktlint refuses to auto-fix.

**Updated total file count for the auto-fix commit** (relative to the original estimate): the Compose exemption removes 11 files from the manual-edit list, but the property-name renames touch `MainActivity.kt`, `Conversation.kt`, and any other files that import the renamed symbols (likely 2-4 additional files for route references). Net edit fan-out is still well below the 10-call-site red line for in-ticket changes.

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
- **`format("misc")` vs Prettier / Jackson.** The minimum-dependency choice is trim + newline. If a future ticket wants structurally-formatted JSON or Prettier-formatted Markdown, it can swap the `format("misc")` block. Not in scope here.
- **Gradle Wrapper Kotlin files.** `gradle/wrapper/**` is excluded from the misc glob (wrapper scripts are not ours to reformat). Verify there are no `.kt` / `.gradle.kts` files inside `gradle/wrapper/` (there shouldn't be) so the Kotlin globs don't accidentally reach them.

## Resolved during rework (was: open)

- ~~**No `.editorconfig` decision is deliberate.**~~ **Reversed during rework.** A minimal `.editorconfig` with the single Compose function-naming exemption is now part of this ticket. See § Reconciling ktlint defaults with Compose conventions. Justification: ktlint's `function-naming` default is incompatible with the framework-mandated Compose convention; the upstream-provided exemption is the standard ecosystem fix. Future exemptions remain disallowed without their own evidence-based justification.
