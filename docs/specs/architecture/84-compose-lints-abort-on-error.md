# Spec — Compose Lint Checks + lint abortOnError (#84)

## Files to read first

- `app/build.gradle.kts` (full file — ~55 lines) — module build script. Two additions land here: a `lintChecks(...)` dependency and a `lint { abortOnError = true }` block. No other change.
- `gradle/libs.versions.toml` (full file — ~55 lines) — version catalog. Add `composeLints` to `[versions]` and `compose-lint-checks` to `[libraries]`. Maintain the existing alphabetical-by-key ordering within each section.
- `build.gradle.kts` (root, full file — ~32 lines) — confirm there is no lint config to remove or coordinate with. Spotless is wired at root; lint is per-module and stays out of root entirely.
- `.editorconfig` (full file — 4 lines) — context only. The Compose function-naming exemption shipped with #83 does not interact with compose-lints (ktlint and Android Lint are separate engines), but both run under `./gradlew check` after this PR, so the developer needs to know both gates exist.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:48-49` — the one composable in this repo that omits `modifier: Modifier = Modifier`. Almost certain to trip `ModifierMissing`; the in-PR fix is mechanical (add the parameter, apply it to the outermost Surface/Box). See § Expected findings.
- All other screen/component composables (`ui/conversations/list/{ChannelListScreen,DiscussionListScreen}.kt`, `ui/conversations/components/{ConversationRow,DiscussionPreviewRow,ConversationAvatar}.kt`, `ui/settings/{LicenseScreen,SettingsScreen}.kt`, `ui/onboarding/{WelcomeScreen,ScannerConnectingScreen,ScannerDeniedScreen}.kt`) — already accept `modifier: Modifier = Modifier`; ack and skip unless lint surfaces a finding against them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/{ChannelListScreen.kt:140-148,DiscussionListScreen.kt:95-96}` — the only `LazyColumn` call sites. Both already pass `key = { it.id }` / `key = "recent-discussions-section"`, so `LazyColumnNoKey` should not fire. Verify after running lint.
- `CLAUDE.md` (root) — confirms "Test-first" applies broadly but does not apply to mechanical lint wiring; the AC is itself the test.
- `docs/specs/architecture/83-spotless-ktlint-wiring.md` — sibling ticket pattern. Same shape (deterministic gate + auto-fix where applicable + no suppressions). Read § "Reconciling ktlint defaults with Compose conventions" for the precedent on how the pipeline handles defaults that disagree with the codebase — applies here too: do not add a baseline or suppressions; if a check is wrong for this codebase, the right answer is an in-PR fix or a follow-up ticket with evidence, not a silencer.

## Design source

N/A — tooling/infra ticket.

## Context

The Compose compiler reports stability issues but does not fail builds. AGP's default `./gradlew lint` runs but has `abortOnError = false`, so findings are advisory only. Per [[instruction-design#Belt-and-Suspenders Means Different Fabric]], a deterministic Compose-specific lint gate is the missing fabric next to the existing stochastic developer + code-review checks.

This ticket adds **only** the lint rules + the abort-on-error switch. Spotless landed in #83. CI (`./gradlew check` on PR) lands separately. The three together form the deterministic side of the belt-and-suspenders pair.

## Design

### Version catalog additions (`gradle/libs.versions.toml`)

Under `[versions]`, add (alphabetical placement):

```toml
composeLints = "1.4.2"
```

Under `[libraries]`, add (alphabetical placement, near the other `androidx-compose-*` entries):

```toml
compose-lint-checks = { group = "com.slack.lint.compose", name = "compose-lint-checks", version.ref = "composeLints" }
```

Coordinates verified against Maven Central as of 2026-05-14: `com.slack.lint.compose:compose-lint-checks:1.4.2` (latest published version). Pin to this version; if it is unavailable or yanked at implementation time, the developer picks the latest published version on Maven Central and updates the catalog. Do not unpin or float.

### `app/build.gradle.kts` additions

Two surgical edits to the existing file. Order within the file is the developer's call; the canonical placement is:

1. Inside the existing `android { ... }` block, after `buildFeatures { ... }`, add:

   ```kotlin
   lint {
       abortOnError = true
   }
   ```

   Single property, single block. No other lint config — no `disable`, no `error`, no `warning`, no `baseline`, no `checkReleaseBuilds`. Defaults stand for everything else.

2. Inside the existing `dependencies { ... }` block, alongside other `implementation(...)` lines (alphabetical-ish), add:

   ```kotlin
   lintChecks(libs.compose.lint.checks)
   ```

   The `lintChecks` configuration is provided by AGP. It pulls the compose-lints rule set into the lint classpath without making the JAR a runtime dependency.

No plugin alias change; compose-lints is consumed as a lint check JAR, not a Gradle plugin. No repository change; Maven Central is already in `settings.gradle.kts` (default for AGP 9.x).

### Why no `lint.xml`, no baseline, no suppressions

The ticket body is explicit: every finding is surfaced and fixed in this PR, or the ticket is routed back for split. This matches the [[instruction-design#Belt-and-Suspenders Means Different Fabric]] principle (a deterministic gate that is silenced becomes a stochastic gate) and aligns with #83's no-`@Suppress` stance.

If a single rule is genuinely wrong for this codebase, the right response is a follow-up ticket with evidence (which finding, why it does not apply, what the project-specific exemption is), not a `lint.xml` disable in this PR. The same upstream-justified-escape-hatch precedent that produced the one-line `.editorconfig` Composable exemption in #83 applies here: exemptions land via their own evidence-based tickets, not by quiet silencing inside a wiring PR.

### Expected findings

The developer runs `./gradlew lint` after wiring is in place. Based on a pre-spec survey of the Compose surface, the high-probability findings are:

1. **`ModifierMissing` on `ScannerScreen`** (`ui/onboarding/ScannerScreen.kt:49`).
   - Signature today: `fun ScannerScreen(onTap: () -> Unit)`.
   - Fix: add `modifier: Modifier = Modifier` as the first parameter after `onTap` (compose-lints expects modifier first-optional, after required params); thread it onto the outermost `Surface` / `Box` inside the function body. Update the single call site in `MainActivity.kt`'s `NavHost` (it does not pass a modifier today; the default keeps the call site unchanged).
   - This is the only screen in the repo missing the parameter; all other screens and components already accept `modifier: Modifier = Modifier`.

2. **`LazyColumnNoKey`** — not expected to fire. Both `LazyColumn` call sites in the repo (`ChannelListScreen.kt:141`, `DiscussionListScreen.kt:96`) already pass `key = { it.id }` (or `key = "..."` for fixed items). Verify after running lint.

3. **`Material2`** — not expected to fire. The repo uses Material 3 (`androidx.compose.material3.*`) for all UI; the `material.icons.*` imports are the icons library (separate artifact, not the M2 UI toolkit) and the rule does not flag them.

4. **`ComposableNaming`, `ContentEmitterReturningValues`, `ContentTrailingLambda`, `ComposableParamOrder`** — none expected, based on a structural read of the screens/components. Composables are PascalCase, return `Unit`, accept content lambdas last where used. Verify after running lint.

If the actual lint run surfaces a finding **not in the list above**, the developer applies the simplest in-place fix and proceeds. If the cumulative fix exceeds size S (>~150 lines of production code added/changed, or >3 files beyond the build files, or >10 consumer call sites needing simultaneous updates), the developer stops and routes the ticket back per `Don't Power Through When Stuck`. Do not add suppressions or a baseline to make the gate green.

### Verification

After wiring + fixes, in order:

1. `./gradlew lint` exits 0 against the current `main` tree. Inspect the report at `app/build/reports/lint-results-debug.html` (and the `release` variant if AGP runs it) to confirm zero errors — warnings still allowed.
2. `./gradlew check` exits 0 (this now invokes Android Lint **plus** `spotlessCheck` from #83 — both must pass).
3. `./gradlew assembleDebug` still produces a debug APK (sanity: lint wiring did not break compilation).

The instrumented suite (`./gradlew connectedAndroidTest`) is not in scope for verification — it requires a device, and this ticket changes no runtime behavior.

### Order of operations for the developer

1. Add the version catalog entries.
2. Add the `lint { abortOnError = true }` block + `lintChecks(libs.compose.lint.checks)` dependency to `app/build.gradle.kts`.
3. Run `./gradlew lint`. Read the report.
4. Fix each surfaced finding in-place. For `ScannerScreen`'s `ModifierMissing`, follow the recipe above.
5. Re-run `./gradlew lint` until exit 0.
6. Run `./gradlew check` — must exit 0 (covers lint + spotlessCheck + unit tests).
7. Commit the wiring + the fixes together (single PR; squashed or two-commit, both fine, mirroring #83's stance).

If at step 3 or 5 the findings list is larger than the "Expected findings" inventory above and the fixes are not mechanical, stop and post a `needs-rework:architect` comment with the lint report attached — that signals the architect to revise the spec rather than the developer absorbing scope.

## State + concurrency model

N/A — build-tool wiring.

## Error handling

N/A — Android Lint surfaces failures via Gradle task failure with `abortOnError = true`; that is the entire error-handling model.

If `lint` fails locally or on future CI (when CI exists, per #72's remaining split tickets), the failure message includes the rule ID, file, and line. Developers fix the finding or, if the rule is genuinely incompatible with this codebase, file a follow-up ticket with evidence — see § "Why no `lint.xml`, no baseline, no suppressions".

## Testing strategy

No unit or instrumented tests are added. The AC defines the tests:

1. `./gradlew lint` exits 0 with `abortOnError = true` and compose-lints registered.
2. `./gradlew check` exits 0.

Both run locally as the final verification before the developer commits.

## Open questions

- **compose-lints version drift.** Pinned to `1.4.2` (latest Maven Central as of 2026-05-14). If yanked or unavailable at implementation time, take the latest published version and update the catalog. Document the bump in the PR description. Do not unpin.
- **AGP-internal Compose lint rules.** AGP 9.x ships some Compose-specific lint rules of its own (separate from Slack's compose-lints). With `abortOnError = true`, those become blocking too. If any AGP-internal Compose rule fires on the current tree, treat it as an "Expected finding #5" and apply the same in-place-fix-or-escalate rule. The Slack rule set is the *additional* surface this ticket adds; flipping `abortOnError` activates the *whole* lint engine's error severity.
- **`release` variant lint.** AGP's default is `checkReleaseBuilds = true`. With minification on the `release` build type, lint may produce additional findings against minified configuration. Verify by running `./gradlew lint` (not just `lintDebug`) so both variants are covered before commit.
- **No baseline now; what about later?** If a future ticket adds a large body of code that legitimately needs lint rules disabled for a sub-tree (e.g. a third-party module brought in-tree), that ticket adds the baseline or the targeted `lint.xml` disable with its own justification. This ticket establishes the no-baseline default; it does not forbid future targeted exceptions.
