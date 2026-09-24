# #989 — README: record where the Firebase API key is restricted

## Files read

- `README.md` → `### Firebase` section — the only place the change lands; it already covers where `app/google-services.json` lives and that the Google Services plugin is conditional, but not the key restrictions set in #942.

## Design source

N/A — documentation only, no UI change.

## Change

No production code, resources, build configuration or tests change. The whole
deliverable is a paragraph in `README.md`, which is outside the builder's file set,
so it is handed to the documentation stage below. Nothing else moves: the key
restrictions live in Google Cloud console, not in this repository, and
`app/google-services.json` stays as it is.

## Documentation handoff

**Pending for the documentation stage.**

- **Path and section:** `README.md` § `### Firebase`, as a new paragraph after the
  one ending "…so no token or message is ever delivered." and before the one starting
  "Push is enabled only when the file is present."
- **Requirement (AC 1):** state that the Android API key in `app/google-services.json`
  is restricted in Google Cloud console (done in #942) in two ways: to Android apps
  with package `de.pyryco.mobile` plus the SHA-1 of each signing certificate, and to
  the Firebase Cloud Messaging and Firebase Installations APIs. Say both are set under
  APIs & Services → Credentials, on that API key.
- **Requirement (AC 2):** state that any new signing key, such as another machine's
  debug keystore or a Play app-signing key, needs its SHA-1 added there before builds
  signed with it can register for push; without it they cannot get a push token.
  `./gradlew signingReport` prints the SHA-1 of the configured keys.
- **Constraint:** do not put the key, or any certificate fingerprint, in the README.

Suggested wording, for the documentation stage to adjust to the surrounding prose:

> The Android API key in that file is restricted in Google Cloud console, under
> APIs & Services → Credentials: to Android apps with package `de.pyryco.mobile` and
> the SHA-1 of each signing certificate, and to the Firebase Cloud Messaging and
> Firebase Installations APIs. A new signing key — another machine's debug keystore,
> or a Play app-signing key — needs its SHA-1 added to that key first; until then,
> builds signed with it cannot get a push token. `./gradlew signingReport` prints the
> SHA-1.

## Testing strategy

No new logic, so no new test. `scripts/docs-guard.sh` in the verifier's gate covers
the documentation stage's edit.
