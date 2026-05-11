# Spec — Conversation / Session / Message Data Classes (#2)

## Files to read first

- `CLAUDE.md` (repo root) — "Conversations model" section defines what `isPromoted`, `currentSessionId`, and `sessionHistory` mean; the data classes mirror that prose. The "Don't" section's Compose Multiplatform walk-back is the reason for `kotlinx.datetime.Instant` over `java.time.Instant`.
- `gradle/libs.versions.toml` — Kotlin `2.2.10`, no `kotlinx-datetime` entry yet. The version catalog is the single place to declare the new dependency; do not add a raw coordinate to `app/build.gradle.kts`.
- `app/build.gradle.kts` — confirms single-module layout, Java 11 target, no existing data-layer dependencies. The new `implementation(libs.kotlinx.datetime)` line goes in the existing `dependencies { }` block alongside the other `implementation(libs.*)` lines.
- `settings.gradle.kts` — `mavenCentral()` is already declared under `dependencyResolutionManagement`. `kotlinx-datetime` resolves from Maven Central; no new repository needed.
- `app/src/test/java/de/pyryco/mobile/ExampleUnitTest.kt` — establishes the existing unit-test idiom (JUnit 4, `org.junit.Test`, `org.junit.Assert.*`). New tests follow this exact import set — JUnit 5, Kotest, or Truth are not on the classpath and are not to be added.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — confirms the project's existing package convention (`de.pyryco.mobile.*`). No code in this ticket depends on Activity / Compose, but the package root is shared.

## Context

This is the first ticket that creates the `data/` package. Three foundational types — `Conversation`, `Session`, `Message` — plus the `Role` enum, with no consumers yet. The next ticket (`ConversationRepository` interface + `FakeConversationRepository`) consumes this schema; the UI tickets after that consume it transitively.

The schema is fixed by the issue body's Spec block — AC1 asserts exact field names, types, nullability. The architect's degrees of freedom are: (a) file layout, (b) dependency wiring, (c) test structure. Everything else is pinned by AC.

## Design

### File layout — 3 files in `app/src/main/java/de/pyryco/mobile/data/model/`

Create the directory by writing the files; Gradle picks it up under the existing `app/src/main/java/` source root (same pattern as `ui/onboarding/` in #7).

| File | Contents |
|------|----------|
| `Conversation.kt` | `data class Conversation` |
| `Session.kt` | `data class Session` |
| `Message.kt` | `data class Message` **and** `enum class Role` |

Rationale for co-locating `Role` in `Message.kt`: `Role` is tightly bound to `Message.role` with no independent consumers anywhere in the planned architecture, and the enum is three lines. A separate `Role.kt` adds a navigation hop with zero payoff. One-class-per-file otherwise — that's the idiomatic Kotlin convention and matches how future readers will expect to find `Conversation` and `Session`.

### Exact source

Each file starts with `package de.pyryco.mobile.data.model` and one import: `import kotlinx.datetime.Instant`. **Do not** import `java.time.Instant` (CLAUDE.md "Don't" — data layer must stay portable for the Compose Multiplatform walk-back). **Do not** add `@Serializable`, `@JsonClass`, or any other annotation — Out of Scope is explicit that the wire-protocol serialization is deferred.

`Conversation.kt`:

```kotlin
package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Conversation(
    val id: String,
    val name: String?,
    val cwd: String,
    val currentSessionId: String,
    val sessionHistory: List<String>,
    val isPromoted: Boolean,
    val lastUsedAt: Instant,
)
```

`Session.kt`:

```kotlin
package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Session(
    val id: String,
    val conversationId: String,
    val claudeSessionUuid: String,
    val startedAt: Instant,
    val endedAt: Instant?,
)
```

`Message.kt`:

```kotlin
package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Message(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Instant,
    val isStreaming: Boolean,
)

enum class Role { User, Assistant, Tool }
```

Field order, names, types, and nullability are copied verbatim from the AC. Do not reorder for "logical grouping" or rename `claudeSessionUuid` to `claudeSessionId` — AC1 pins the exact names.

### Dependency wiring — `kotlinx-datetime` via version catalog

Edit `gradle/libs.versions.toml`:

Add under `[versions]` (alphabetical order — between `junitVersion` and `kotlin`):

```toml
kotlinxDatetime = "0.6.2"
```

Add under `[libraries]` (place near the bottom, after the Compose entries — there is no existing `kotlinx-*` grouping):

```toml
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "kotlinxDatetime" }
```

Edit `app/build.gradle.kts` — add to the `dependencies { }` block, grouped with the other `implementation(libs.*)` lines (e.g. just after `implementation(libs.androidx.core.ktx)`):

```kotlin
implementation(libs.kotlinx.datetime)
```

Notes:
- **Version `0.6.2`** is the latest stable compatible with Kotlin 2.2.x and is published to Maven Central. Do not switch to a `-SNAPSHOT`, `-RC`, or `-alpha` build.
- **Catalog accessor.** Gradle converts `kotlinx-datetime` to `libs.kotlinx.datetime` (the `-` becomes a dot in the accessor). Do not write `libs.kotlinx-datetime` — that won't compile.
- **No additional Gradle plugin.** `kotlinx-datetime` is a plain library; do not apply `kotlin("plugin.serialization")` or similar — that's for `kotlinx-serialization`, which is out of scope.
- **Do not add `kotlinx-datetime` under `testImplementation` separately.** The `implementation` configuration is on the test classpath transitively; declaring it twice is redundant and creates a future maintenance trap.

### Test layout — 3 files in `app/src/test/java/de/pyryco/mobile/data/model/`

| File | Contents |
|------|----------|
| `ConversationTest.kt` | equality / hashCode / copy tests for `Conversation` |
| `SessionTest.kt` | equality / hashCode / copy tests for `Session` |
| `MessageTest.kt` | equality / hashCode / copy tests for `Message` **and** the `Role.values()` test |

Same JUnit 4 idiom as `ExampleUnitTest.kt`:

```kotlin
import org.junit.Test
import org.junit.Assert.*
```

Each `data class` test contains three `@Test` methods (one per AC2 assertion):

1. **`equals_and_hashCode_match_for_identical_instances`** — construct two instances with the same field values via two separate constructor calls; assert `a == b` and `a.hashCode() == b.hashCode()`.
2. **`copy_overrides_named_field_and_preserves_rest`** — call `.copy(<oneField> = <newValue>)`; assert the overridden field changed and that one other field of a different type stayed the same. (One spot-check is enough — the test asserts `copy` semantics, not a full field census.)
3. **`copy_produces_a_new_instance`** — `assertNotSame(original, original.copy())` (reference inequality). This is the cheapest way to express "new instance" without depending on the overridden field's value.

`MessageTest.kt` additionally has a fourth `@Test`:

4. **`role_has_exactly_user_assistant_tool`** — `assertEquals(listOf(Role.User, Role.Assistant, Role.Tool), Role.values().toList())`. List equality (not set equality) is intentional: it also pins declaration order, which the issue body's Spec block fixes implicitly. If the developer prefers `Role.entries.toList()` (the Kotlin 1.9+ replacement for `values()`), that is equivalent and acceptable — `entries` returns the same ordered list.

### Constructing `Instant` in tests

Use `kotlinx.datetime.Instant.fromEpochSeconds(0)` (or any fixed value) for test fixtures. Do not use `Instant.parse("...")` from a string — `fromEpochSeconds` is one call, no parse failure mode, and makes "two identical instances" trivially identical. Do not use `Clock.System.now()` — tests must be deterministic.

A small helper at the top of each test file (or a single private `fun fixedInstant() = Instant.fromEpochSeconds(0)`) is fine but optional; inline calls are equally readable for three tests.

## State + concurrency model

N/A — this ticket introduces immutable value types with no observers, no flows, no scopes. The data classes are thread-safe by virtue of being immutable.

## Error handling

N/A — no I/O, no parsing, no failure modes. Constructor invocation cannot fail (no `require(...)` / `init { }` blocks — none called for by the AC, and adding them is scope creep beyond the schema).

## Testing strategy

**Unit only** — `./gradlew test` covers everything. No instrumented tests; no `ComposeTestRule`; no `runTest` (there are no suspend functions or flows in this ticket). The existing `app/src/androidTest/` directory is not touched.

AC5 (`./gradlew test` passes) is satisfied by the three new test files plus the pre-existing `ExampleUnitTest`. AC4 (`./gradlew assembleDebug` passes) is satisfied once the dependency is correctly catalog-wired and the data-class files compile.

## Open questions

None. The schema is fully pinned by AC1; the file layout, dependency wiring, and test structure decisions above are deterministic. If `kotlinx-datetime 0.6.2` cannot be resolved (e.g. Maven Central transient), the developer should report the resolution error rather than substituting `java.time.Instant` — substituting would violate the explicit "Don't" in CLAUDE.md and is the one trap this spec is designed to prevent.
