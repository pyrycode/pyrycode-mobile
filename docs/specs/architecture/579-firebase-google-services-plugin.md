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

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only new input is `app/google-services.json`, a file in the repository that the build reads at configuration time. It is reviewed like any other source change. Nothing reads it at runtime, and no daemon, relay or push data crosses a boundary in this change.
- [Tokens, secrets, credentials] OUT OF SCOPE, operator action filed as #942. The file carries the Firebase Android API key (`current_key`). The plugin writes it into the APK as `google_api_key` and `google_crash_reporting_api_key`. Committing it is acceptable because it is client configuration that ships in every APK, where anyone can read it. It is not a server credential. It is only safe while the key is restricted in Google Cloud to package `de.pyryco.mobile` with its signing-certificate fingerprints, and to the Firebase Cloud Messaging and Installations APIs. The repository cannot show whether that restriction is in place, so #942 asks the operator to check and apply it. `oauth_client` is empty, so no OAuth client ID is added. Service-account and server credentials stay in the password manager. They cannot reach the build this way: the plugin reads only the `client` configuration schema, and a service-account JSON has a different shape.
- [File / storage] No findings. The `file("google-services.json").exists()` check runs at configuration time on a path inside the project that the developer controls. No attacker can influence the path, so the check-then-use gap does not matter. Nothing is written to device storage.
- [Android attack surface] No findings. The plugin adds only string resources and no manifest entries. Without a Firebase SDK, which #361 adds, there is no `FirebaseInitProvider`, no messaging service and no receiver. The push wake path and its payload handling belong to #361 and its security review.
- [Cryptographic primitives] No findings. No cryptography is added or touched.
- [Network & I/O, build supply chain] No findings. `googleServices = "4.5.0"` is an exact pin in the catalog. The `pluginManagement` `google()` repository in `settings.gradle.kts` is searched first and admits `com\.google.*`, so the marker `com.google.gms.google-services` and the artifact `com.google.gms:google-services` both resolve from Google Maven. The repository has no `gradle/verification-metadata.xml`. That is the existing policy for every plugin and library, not something this change introduces. At runtime the change adds no network code.
- [Error messages, logs, telemetry] No findings. No runtime logging is added. `firebase-analytics` stays out by operator decision; #361 adds only `firebase-messaging`. The plugin's build log prints the file path, not the key.
- [Concurrency] No findings. No runtime code is added.
- [Threat model alignment] No findings. When the file is absent, the plugin is not applied and no resources are generated. No code assumes Firebase is initialised: the only mentions under `app/src` are comments on the push-token registration path, which is dormant until #361 stores a token. Verified locally: `:app:processDebugGoogleServices` writes exactly `gcm_defaultSenderId`, `google_api_key`, `google_app_id`, `google_crash_reporting_api_key`, `google_storage_bucket` and `project_id`.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- **2026-09-23, rework after verifier review on PR #935.** Added the `## Security review` section above. The verifier's MUST FIX was that this `security-sensitive` ticket's plan had none. The design and the code are unchanged. The review found no MUST FIX. It filed #942 so the operator can confirm the API key's Google Cloud restrictions, which the repository cannot show.
