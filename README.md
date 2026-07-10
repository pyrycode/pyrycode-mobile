# Pyrycode Mobile

Android client for [Pyrycode](https://github.com/pyrycode/pyrycode) — talk to claude sessions from your phone.

## Status

UI complete (Phases 0-2 shipped); **Phase 4 backend integration in progress** — Noise_IK encrypted transport over WebSocket to pyrycode-relay (live 2026-05-29); UI still runs against `FakeConversationRepository`, real backend is a Koin module swap.

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

This runs the live rung-3 ping scenario (the real app on an emulator → host `pyry` daemon → real claude, against the production relay over `wss://`, on the isolated `e2e-live` instance). The command bakes in `LIVE=1` and the `e2e-live` defaults — there is no env-var incantation to remember.

**Run it when:**

- **before installing a new APK build on a device**, so you are never the first to discover the real stack can't answer a live send; and
- **whenever a daemon or relay change touching the mobile surface lands** — run it alongside the daemon's own `make e2e-realclaude`.

**Cost:** one real claude turn (the ping scenario), a few minutes of wall clock, subscription-covered (it does **not** meter tokens).

Prerequisites and full mechanics (relay URLs, isolation, first-run assumptions) live in [docs/e2e-interactive-stream.md § Pre-ship gate](docs/e2e-interactive-stream.md#pre-ship-gate).

## License

MIT. See [LICENSE](LICENSE).
