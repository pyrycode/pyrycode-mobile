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

For a ticket labelled `needs-real-claude`, the dispatcher runs the live real-Claude end-to-end gate after verifier and before documentation or merge — the mobile parallel of the daemon's `make e2e-realclaude`:

```bash
python3 scripts/android-test-gate.py live
```

This runs the curated live scenarios through the real app, test daemon and real Claude against the production relay over `wss://`, under a unique `e2e-auto-*` test identity. The wrapper selects `LIVE=1`, builds test-only host binaries from configured sibling sources, and checks Claude authentication before starting the suite. The dispatcher supplies the source paths, SDK paths and its existing 1Password credential. For a manual run, use the same environment.

**The dispatcher runs it when:**

- **the ticket carries `needs-real-claude`**, so the live stack is exercised after the verifier result; and
- **the acceptance requires a real daemon and real Claude**, alongside the daemon's own `make e2e-realclaude` when that change crosses repositories.

**Cost:** a few real claude turns and a few minutes of wall clock, subscription-covered (it does **not** meter tokens).

The scenario set and the exact turn cost live in [docs/e2e-interactive-stream.md § Pre-ship gate](docs/e2e-interactive-stream.md#pre-ship-gate) — that document is the single authority for gate scope and cost, and this README deliberately does not restate its numbers. Prerequisites and full mechanics (relay URLs, isolation, first-run assumptions) live there too.

## License

MIT. See [LICENSE](LICENSE).
