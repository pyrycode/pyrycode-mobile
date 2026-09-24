# #1007 — send `client_version` as `pyrycode-mobile/MAJOR.MINOR.PATCH`

## Files read

- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `NoiseClientInfo` binding in `appModule` — binds `clientVersion` to bare `BuildConfig.VERSION_NAME` today; the one production edit.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt` → `NoiseClientInfo` — carries the value into the `hello` payload; unchanged.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt` → the `User-Agent` header — reuses `clientInfo.clientVersion`; the new value is fine there, unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/settings/AboutScreen.kt` → keeps showing bare `BuildConfig.VERSION_NAME`; unchanged.
- `app/build.gradle.kts` → `defaultConfig.versionName` literal `"1.0"`.
- `../pyrycode/docs/protocol-mobile.md` § `hello`, "`client_version` format (#2576)" — the format rules the test encodes.

In flight: `feature/955` edits `app/build.gradle.kts` (the managed-device block), a different block; built through.

## Design source

N/A — no layout change; the About screen's version line reads "1.0.0" instead of "1.0".

## Change

`versionName` in `app/build.gradle.kts` becomes `"1.0.0"`. `AppModule.kt` gains a top-level `internal fun mobileClientVersion(versionName: String = BuildConfig.VERSION_NAME): String` returning `"pyrycode-mobile/$versionName"`, and the `NoiseClientInfo` binding calls it instead of using `BuildConfig.VERSION_NAME` directly. The `hello` and the relay socket's `User-Agent` both pick it up through `NoiseClientInfo.clientVersion`; the About screen keeps reading `BuildConfig.VERSION_NAME`, so nothing else moves.

## Testing strategy

New `app/src/test/java/de/pyryco/mobile/di/ClientVersionTest.kt`, a plain JUnit test with a test-local checker implementing the spec's rules (app name `pyrycode-mobile`, exactly one `/`, three `.`-separated decimal parts with no leading zeros except `0`, no prefix or suffix, at most 32 UTF-8 bytes):

- `mobileClientVersion()` — the value the binding uses, built from the real `BuildConfig.VERSION_NAME` — passes the checker.
- `mobileClientVersion("1.0")` (two parts) and `mobileClientVersion("1.0.0-beta")` (suffixed) fail it, as do a leading-zero part and an over-32-byte version, so the checker is shown to reject what the spec rejects.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/about-screen.md` states that `versionName` is `MAJOR.MINOR.PATCH` and is bumped on each release, because hosted daemons compare it with a configured minimum.
