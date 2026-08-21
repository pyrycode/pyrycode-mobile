# Pyrycode Mobile

Android client for [Pyrycode](https://github.com/pyrycode/pyrycode) — talk to claude sessions from your phone.

## Status

As of 2026-08-22: UI complete (Phases 0-2 shipped); **Phase 4 backend is live** — the client has held a stable v2 `Noise_IK` session against the production relay since 2026-07-03. The UI still binds `FakeConversationRepository` by default behind the compile-time `USE_RELAY_REPOSITORY = false` flag in `app/build.gradle.kts`; the [pre-ship gate](#pre-ship-gate) exercises the real stack end to end.

This is a personal project under active development. Not yet on Play Store.

## Stack

Native Kotlin + Jetpack Compose + Material 3. Single Gradle module. Min SDK 33 (Android 13), target SDK latest stable.

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug    # install on a connected device/emulator
./gradlew test
```

Requires a recent Android Studio (Hedgehog or later).

## Pre-ship gate

Before the operator sees the real stack, run the live real-claude end-to-end gate — the mobile parallel of the daemon's `make e2e-realclaude`:

```bash
bash scripts/e2e-preship-gate.sh
```

This runs the curated live rung-3 scenarios (the real app on an emulator → host `pyry` daemon → real claude, against the production relay over `wss://`, on the isolated `e2e-live` instance). The command bakes in `LIVE=1` and the `e2e-live` defaults — there is no env-var incantation to remember.

**Run it when:**

- **before installing a new APK build on a device**, so you are never the first to discover the real stack can't answer a live send; and
- **whenever a daemon or relay change touching the mobile surface lands** — run it alongside the daemon's own `make e2e-realclaude`.

**Cost:** a few real claude turns and a few minutes of wall clock, subscription-covered (it does **not** meter tokens).

The scenario set and the exact turn cost live in [docs/e2e-interactive-stream.md § Pre-ship gate](docs/e2e-interactive-stream.md#pre-ship-gate) — that document is the single authority for gate scope and cost, and this README deliberately does not restate its numbers. Prerequisites and full mechanics (relay URLs, isolation, first-run assumptions) live there too.

## License

MIT. See [LICENSE](LICENSE).
