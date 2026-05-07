# Pyrycode Mobile

Android client for [Pyrycode](https://github.com/pyrycode/pyrycode) — talk to claude sessions from your phone.

## Status

**Phase 0: UI scaffolding.** Built against a fake data layer; real backend integration is deferred to Phase 4 (after pyrycode ships its mobile API in Phase 3).

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
