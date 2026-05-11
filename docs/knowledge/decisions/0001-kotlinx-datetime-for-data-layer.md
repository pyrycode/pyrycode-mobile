# ADR 0001 — Use `kotlinx.datetime.Instant` in the data layer

**Status:** Accepted (2026-05-11, with #2).

## Context

The first data-layer ticket (#2) introduced `Conversation`, `Session`, and `Message` with timestamp fields. Android ships `java.time.Instant` (`min-sdk 33` covers it without desugaring), which would be the path of least resistance.

CLAUDE.md's "Don't" section calls out Compose Multiplatform as an explicit walk-back trigger: the data layer must stay portable. `java.time` is JVM-only — adopting it in `data/` would silently couple every value type and every repository signature to the JVM, and unwinding that later means touching every model, every fake, and every test.

## Decision

Every timestamp in the `data/` package — and every consumer signature that surfaces a timestamp — uses `kotlinx.datetime.Instant`.

`kotlinx-datetime` is wired through the version catalog:

```toml
# gradle/libs.versions.toml
[versions]
kotlinxDatetime = "0.6.2"

[libraries]
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "kotlinxDatetime" }
```

Declared once as `implementation(libs.kotlinx.datetime)` in `app/build.gradle.kts` — that puts it on the test classpath transitively, so no separate `testImplementation`.

## Rationale

- **Walk-back cost.** The whole point of the "Don't" rule is that a Compose Multiplatform pivot should not require rewriting the data layer. Picking the portable type now is the cheapest possible insurance.
- **No runtime penalty.** `kotlinx.datetime.Instant` on JVM is a thin wrapper around `java.time.Instant`; the JVM-only convenience methods we'd want are still reachable via `toJavaInstant()` if and when a UI formatter needs them — at the UI boundary, not in `data/`.
- **No plugin requirement.** Unlike `kotlinx-serialization`, `kotlinx-datetime` is a plain library; no Gradle plugin to apply.
- **Deterministic test fixtures.** `Instant.fromEpochSeconds(0)` is one call, no parse failure mode, no clock dependency.

## Alternatives considered

- **`java.time.Instant`.** Rejected — see Context. Saves nothing, costs the walk-back.
- **Long epoch-millis.** Type-unsafe (every `Long` looks like every other `Long`). Rejected.
- **A project-local `Timestamp` typealias.** Adds indirection without solving the portability question — the underlying type is still what matters.

## Consequences

- Any new `data/` field that needs a timestamp uses `kotlinx.datetime.Instant`.
- UI-layer formatting (relative times, absolute dates) crosses the boundary by converting at the call site — `kotlinx-datetime` ships its own formatters, or `toJavaInstant()` hands off to `java.time` if needed. Either is fine; the rule is only that `data/` doesn't import `java.time`.
- The repository layer (next ticket) and every downstream `UiState` carry `Instant` through unchanged.
- If Compose Multiplatform ever ships, the `data/` package moves to `commonMain` with zero edits to timestamp handling.

## Related

- Feature doc: `../features/data-model.md`
- Ticket notes: `../codebase/2.md`
- CLAUDE.md → "Don't" → Compose Multiplatform walk-back clause.
