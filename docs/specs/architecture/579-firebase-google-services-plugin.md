# #579 — Apply the Google Services plugin when `app/google-services.json` exists

## Files read

- `gradle/libs.versions.toml` → `[plugins]` table; no `google-services` entry exists at `1e2f8a2c`. Adds `googleServices` version and a `google-services` plugin alias.
- `build.gradle.kts` (root) → `plugins` block; each Android-side plugin is declared `apply false` here so its classpath is shared. The new alias joins them.
- `app/build.gradle.kts` → `plugins` block and the top-level script body; the conditional apply goes after the `android { }` block's plugins are on.
- `settings.gradle.kts` → `pluginManagement` `google()` repository admits `com\.google.*`, so the plugin marker `com.google.gms.google-services.gradle.plugin` resolves from Google Maven with no repository change.
- `app/google-services.json` → client configuration from #906; `mobilesdk_app_id` `1:989241581793:android:90475142351ecb860f17a0`, package `de.pyryco.mobile` (matches `applicationId`; debug has no suffix).

## Design source

N/A: this is build configuration with nothing visible in the UI.

## Change

Pin `googleServices = "4.5.0"` in the catalog. It is the latest release on Google Maven at the time of writing. Its module metadata targets JVM 11, and it registers through the `androidComponents` variant API, which AGP 9 keeps. Declare it in the root `plugins` block with `apply false` so it is on the build classpath. In `app/build.gradle.kts`, apply it with `pluginManager.apply(...)` behind `if (file("google-services.json").exists())`, using the catalog alias's plugin id and not a repeated string literal. A `plugins { }` block cannot be conditional, which is why the apply sits in the script body. Gradle records the file-existence check as a configuration input, so moving the file in or out invalidates any cached configuration. No Firebase SDK dependency is added; #361 owns that. Nothing else moves.

## Testing strategy

No new Kotlin logic, so no unit test. Two build runs prove the acceptance criteria and are recorded in the PR:

1. With the file present: `./gradlew assembleDebug` runs `:app:processDebugGoogleServices`. The generated `app/build/generated/res/processDebugGoogleServices/values/values.xml` contains `google_app_id` = `1:989241581793:android:90475142351ecb860f17a0`.
2. With the file moved aside: `./gradlew assembleDebug test` succeeds. `./gradlew :app:tasks --all` lists no `process*GoogleServices` task, which shows the plugin is not applied. The file is moved back afterwards.

## Documentation handoff

Pending for the documentation stage: add a short Firebase subsection under `README.md` § Build. List project ID `pyrycode-mobile`, sender ID `989241581793` and Android app ID `1:989241581793:android:90475142351ecb860f17a0`. Say the committed `app/google-services.json` is client configuration only and that service-account credentials live in the password manager. Say the plugin applies only when the file is present, so builds without it succeed with push disabled.
