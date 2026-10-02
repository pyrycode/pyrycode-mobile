# Development verification

Shared verification guidance for Kotlin, Jetpack Compose and the Android
emulator. Read the current source and the owning feature topic before applying a
historical lesson.

## Map

Split on 2026-10-02 to keep this document under the 50000-byte cap the docs guard
enforces. Each section named below moved verbatim, heading and anchors intact,
into its own document:

- [Development verification — change surface, Gradle gates and where a test goes](development-verification-gates.md) —
  `Establish the change surface`, `Gradle and source checks`, `Where a screen test goes`, `Device gate`
- [Development verification — Compose evidence](development-verification-compose-evidence.md) —
  `Compose evidence`, `Probe the evidence itself`
- [Development verification — test scheduling and harnesses](development-verification-test-scheduling.md) —
  `Test scheduling and harnesses`
- [Development verification — JVM logging, emulator and real evidence](development-verification-emulator-evidence.md) —
  `JVM logging and formatting`, `Emulator and real evidence`, `Documentation evidence`, `Archive refresh regression`

Nothing stays in this document except this map.
