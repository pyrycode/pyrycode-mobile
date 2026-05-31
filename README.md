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

## License

MIT. See [LICENSE](LICENSE).
