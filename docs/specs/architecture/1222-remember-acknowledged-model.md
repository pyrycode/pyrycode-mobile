# Remember acknowledged model choices (#1222)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `rememberedEffort`, `setRememberedEffort`: app-wide DataStore pattern and content-free outcome logs.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onModelSelected`, `sendSessionSettings`: the changed-field guard and acknowledgement boundary.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `thread`: production preferences wiring; demo remains inert.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` → remembered-effort restart tests: real on-disk DataStore proof.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → `onModelSelected` cases: pending, refusal, cancellation and passive readings.
- `docs/knowledge/features/app-preferences.md` → remembered-effort key: the raw preference remains separate from the enum default.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md` → effort's read-on-open behavior; model recall must not copy it.
- `docs/knowledge/features/development-verification.md` → focused JVM test and DataStore evidence guidance.

## Context

`defaultModel` is an enum-valued Settings choice and cannot represent every daemon-published model. A successful thread selection needs a separate app-wide raw value for the companion new-chat ticket. Existing threads continue to display and write their own session settings; no model is read from this new preference on opening a thread.

The in-flight #1193 branch also edits `ThreadViewModel` and its test file. Its changes are in reading and label derivation; this ticket adds local acknowledgement storage and separate tests, so there is no design dependency.

## Design

- `AppPreferences.rememberedModel: Flow<String?>` reads a distinct `remembered_model` DataStore key with `null` for absence, no enum conversion or fallback. `setRememberedModel(value: String): Result<Unit>` writes the exact published argument and reports an IO failure without replacing `defaultModel`. Static outcome logs contain no value.
- `ThreadViewModel` accepts a default-inert suspend model-remembering callback. The production `AppModule.thread` supplies the app-scoped `AppPreferences` write for real hosts; the demo path keeps the inert default. This avoids introducing a model read or recall path into the thread.
- `sendSessionSettings` invokes that callback only for a non-null model after `setSessionSettings` returns successfully and before the passive refresh. A same-value tap or read-only thread sends no write. Effort-only writes do not change the model preference. Rejection, connection failure and cancellation before acknowledgement bypass the callback.
- The preference write is best effort after acknowledgement: a local IO failure is logged by `AppPreferences`, while the acknowledged session change continues to refresh. The old remembered value stays intact if DataStore rejects the edit.

## State and concurrency model

The existing `viewModelScope` job owns the outbound write and preference update. The callback suspends in the same job; cancellation before acknowledgement never enters it. DataStore serializes and persists accepted edits. The preference is a cold Flow and has no thread-side collector. Background connection close follows the existing `sendSessionSettings` cancellation path.

## Error handling

`setSessionSettings` retains its existing error channel and pending revert for server and connection errors. `setRememberedModel` converts `IOException` to `Result.failure` with a static log code; its caller does not turn a local persistence failure into a daemon write failure or expose the raw value.

## Testing strategy

- `AppPreferencesTest`: fresh absence; exact out-of-enum value round trip, independence from `defaultModel`, no write from changing the Settings default, and reopen of the on-disk DataStore.
- Focused `ThreadViewModel` unit tests with a recording callback: acknowledged model writes once with the exact value; passive readings, same-value selection and effort-only selection do not write; server refusal, connection failure and cancellation before acknowledgement preserve a seeded value. Existing model tests cover UI pending and refresh behavior.
- Run the touched test classes, `lint`, `assembleDebug`, and Spotless formatting. No screen or device-only test is needed: there is no new visible state or operator flow. The companion ticket owns the new-chat consumer and its live scenario.

## Documentation handoff

Pending for the documentation stage: document the separate raw `rememberedModel` preference and success-only update in `docs/knowledge/features/app-preferences.md` and `docs/knowledge/features/thread-composer-footer.md`.

## Open questions

None. The existing `sendSessionSettings` success point and `AppPreferences` storage pattern define the seam.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The model value comes from the existing daemon-published menu through `onModelSelected`; `sendSessionSettings` already forwards that value verbatim. This change persists it as opaque text only; it never renders it, builds a URL or path from it, or treats it as an enum.
- [Tokens, storage] A model identifier is not a credential. The existing app-private preferences DataStore is appropriate; this change adds no token or filesystem path handling. DataStore owns atomic persistence.
- [Android and cryptography] No exported component, intent, WebView or cryptographic primitive changes.
- [Network and I/O] The existing framed transport and `setSessionSettings` response decide acknowledgement. No new network call or parser is introduced.
- [Errors and logs] Static success and IO-failure codes only. Neither the published model value nor a server error message enters logs or user-facing errors.
- [Concurrency] The `viewModelScope` job gates the write on acknowledgement; cancellation before acknowledgement cannot update the preference. The DataStore edit is serialized and the previous value survives an IO failure.
- [Threat model] A malicious relay can delay or drop the existing acknowledgement but cannot cause this client to store an unacknowledged model. The new value remains app-private and is never consumed on existing-thread opening. Existing daemon frame validation and screenshot/accessibility exposure are outside this persistence-only change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27
