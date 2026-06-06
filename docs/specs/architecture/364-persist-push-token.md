# Spec #364 — Phase 4 FCM push: persist the push token in `AppPreferences`

**Size:** XS. **Standalone** — no blocker, no consumer cascade. Purely additive: one new DataStore key + one getter/setter pair on the existing `AppPreferences`, mirroring `defaultWorkspace` minus the default.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:66-81` — the `defaultWorkspace` getter/setter pair (lines 66-71) and the `private companion object` key block (lines 73-81). **This is the exact pattern to clone**, except the getter has no `?: default`.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:22-44` — test scaffold: `TemporaryFolder` + `PreferenceDataStoreFactory.create(scope, produceFile)` + `CoroutineScope(Dispatchers.IO + Job())`, cancelled in `@After`. New tests use `runBlocking { … prefs.X.first() }` (lines 46-59) — **not** `runTest`; follow the file's idiom.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:170-176` — `setDefaultWorkspace_roundTripsCustomCwd`: the round-trip shape your `setPushToken` round-trip test mirrors.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:47` — `single { AppPreferences(get()) }`. Confirms the constructor is unchanged by this slice, so **no DI edit is required**. (Read-only verification; do not modify.)

## Context

Phase 4 FCM push needs a durable wake target: the phone's FCM registration token, so the daemon can push a notification when the phone is backgrounded. The token must survive process death so the phone re-registers it on every reconnect without waiting for Firebase to re-mint it.

This slice adds **durable storage only**. It is dormant until written:

- The token's *origin* — Firebase's `onNewToken` callback — is the Firebase slice (#361).
- The *reader* — connect-time re-registration orchestration — is the sibling slice.
- A `null` read means "nothing to register yet." Downstream stays inert until the first write lands.

## Design

Single file: `data/preferences/AppPreferences.kt`. Add, following the `defaultWorkspace` shape but with the no-default deviation the ticket calls out:

1. **Companion key** (add to the existing `private companion object`, lines 73-81):
   - `val PUSH_TOKEN = stringPreferencesKey("push_token")`

2. **Getter** — `val pushToken: Flow<String?>`
   - Body: `dataStore.data.map { prefs -> prefs[PUSH_TOKEN] }`
   - **No `?: fallback`.** Absence reads as `null`. This is the one deliberate divergence from every other getter in this file (all others coalesce to a non-null default). The `null` is load-bearing — it is the "dormant / nothing to register" signal the reader slice depends on.

3. **Setter** — `suspend fun setPushToken(token: String)`
   - Body: `dataStore.edit { prefs -> prefs[PUSH_TOKEN] = token }`
   - Single key, overwrite semantics → writing a new value replaces the prior one (AC #4, the rotation path #361 drives). No history is retained.

No new types, no new files, no interface changes, no DI changes. The `AppPreferences` constructor signature is untouched, so `AppModule.kt:47` continues to compile and wire unchanged.

### State / concurrency model

Inherited from the existing pattern — nothing new introduced:

- `pushToken` is a **cold** `Flow<String?>` derived via `map` over `dataStore.data`. It re-emits whenever the backing store changes; collectors decide their own scope/dispatcher.
- `setPushToken` is `suspend`; the caller's coroutine context governs it. DataStore serializes writes internally on its own scope (constructed at `AppModule.kt`), so concurrent `setPushToken` calls are safe and last-write-wins.
- `edit` is atomic per transaction: a write either lands fully on disk or not at all. A read therefore never observes a torn/partial token — relevant to AC #3 (process-death durability) and to the security note on store integrity below.

### Storage classification (deliberate, security-relevant)

The FCM token is stored in **plain `DataStore<Preferences>`, NOT a Keystore-wrapped store**, and this is intentional — see the security review below. The developer must place this key in `AppPreferences` (the existing non-secret app-settings store), **not** in `data/crypto/`. Do not "upgrade" it to Keystore/EncryptedSharedPreferences; that would be miscategorizing it against the codebase's existing trust boundary (`data/crypto/` holds the device static key + server keys; `AppPreferences` holds non-secret settings). Rationale is in the security section — code-review should read that section before flagging a "missing encryption" concern.

### Error handling

No new failure modes introduced beyond DataStore's own:

- **Read:** `dataStore.data` surfaces IO errors as a `Flow` exception; absence is not an error — it is `null`. No fallback, no recovery logic here (the reader slice owns "what to do when null").
- **Write:** `edit` propagates IO failure to the suspending caller. This slice does not catch or retry — the writer slice (#361) decides retry policy. **Do not** add a try/catch that swallows or logs the failure; that would both hide errors and risk logging the token.
- **No input validation.** The token is opaque (per ticket). Store it verbatim — do not trim, parse, or reject by format. The trust boundary for token *content* is the daemon at registration time, not local storage. Validating here would be a defense for an unobserved failure mode (no malformed-token incident exists) and risks rejecting valid future FCM token shapes — defer it.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.preferences.AppPreferencesTest"`). No instrumented test — `AppPreferences` is pure DataStore over a `TemporaryFolder`, no Android UI. Add to the existing `AppPreferencesTest`, reusing its scaffold and `runBlocking { … }` idiom. Scenarios (write as bullet-described tests, not pre-written bodies):

- **`pushToken_defaultsToNull`** — fresh store, never written → `prefs.pushToken.first()` is `null`. (AC #1: getter has no default.)
- **`setPushToken_roundTrips`** — write a token string, read it back equal; write a *different* token, read it back equal (proves overwrite, AC #4). Mirror `setDefaultWorkspace_roundTripsCustomCwd`.
- **`pushToken_survivesProcessDeath`** — (AC #3) the only non-trivial test. Recipe:
  - Use a **dedicated `File`** captured once: `val file = tmp.newFile("push_token_persist.preferences_pb")`. (Do not reuse the `setUp` store — `TemporaryFolder.newFile` throws on a duplicate name, and the goal is two *separate* DataStore instances over one file.)
  - Build instance #1 over its own `CoroutineScope` + `produceFile = { file }`, call `setPushToken("tok-A")` (the suspend returns only after the write is flushed).
  - **Cancel instance #1's scope and `join()` its `Job` before opening instance #2.** This is the load-bearing pitfall: DataStore enforces one active instance per file path and throws `IllegalStateException("There are multiple DataStores active for the same file…")` if a second instance opens while the first is still active. A bare `cancel()` is async — `cancel()` then `job.join()` makes the release deterministic. (The existing `tearDown` already cancels `scope`; this test manages its own scopes and must join.)
  - Build instance #2 over a fresh scope + the **same `file`**, assert `pushToken.first() == "tok-A"`.
  - Clean up instance #2's scope at test end.
- **No "never logged" unit test.** AC #5 is a code-level invariant, not cheaply unit-testable. It is enforced deterministically by (a) the production methods containing zero logging (grep-verifiable; `AppPreferences` has no logging today, so this is a *preserve* constraint, not a new guard) and (b) the security review below. Code-review confirms no `Log.*` / `println` references `token` in the diff.

## Security review (label: `security-sensitive`)

Performed adversarially on this spec before commit, per the `security-sensitive` gate.

**Trust boundaries.** The token crosses two boundaries this slice touches: (1) Firebase → app (origin, #361's concern — out of scope here) and (2) app-memory → on-disk app-private storage (this slice). The on-disk boundary is the Android per-app sandbox: `DataStore<Preferences>` writes to `/data/data/de.pyryco.mobile/files/datastore/…`, unreadable by other apps on a non-rooted device. No user-controlled input reaches this code — `setPushToken`'s argument originates from Firebase, not from a UI field or network peer; it is stored verbatim and never interpreted, so there is no injection/parse surface here. The content trust boundary for the token is the daemon at registration (sibling slice), not local storage (`AppPreferences.kt`).

**Categories walked:**

- **Secret classification / storage tier.** *Verdict: plain DataStore is correct.* An FCM registration token is a device-scoped wake *address*, not a credential: possessing it is insufficient to push — an attacker also needs the project's FCM server key, which lives only on the daemon/backend. The token rotates and is overwrite-only (no history). This is materially lower-value than the device pairing static key, which can sign/decrypt the Noise channel and rightly lives Keystore-wrapped under `data/crypto/`. Mirroring KitchenClaw ADR-007's split (DataStore for non-sensitive, encrypted store for auth tokens), the FCM token sits on the non-sensitive side. Keystore would raise the bar for at-rest key extraction but buys little for a low-value, rotating token while miscategorizing it against the existing trust boundary. The ticket's classification is defensible and adopted.
- **Logging / leakage (AC #5).** *Verdict: enforced.* The only realistic leak is a debug `Log.d(TAG, "setPushToken($token)")`. Production methods MUST contain no logging; error paths MUST NOT echo the token (no try/catch that logs the exception with the value). Deterministically grep-verifiable; `AppPreferences` has zero logging today. No exception path in `edit`/`map` includes the value.
- **Data integrity at rest.** *Verdict: no torn-read risk.* `edit` is atomic per transaction; a read sees either the full prior value or the full new one, never a corrupt/garbage token that could be mis-registered. Absence is a clean `null`.
- **Data lifecycle.** *Verdict: positive.* Single-key overwrite means no stale-token accumulation; the store is cleared on uninstall / clear-data. No retention concern.
- **Input validation.** *Verdict: store verbatim, no validation.* Restated as a finding: nothing user-controlled flows here, the value is opaque infra data from Firebase, and over-validation risks rejecting valid future token formats. Deliberate non-defense.

**Verdict: PASS.** No finding requires a spec revision; the no-logging invariant and the storage-tier rationale are carried into the Design and Testing sections above so they survive into implementation and code-review.

## Open questions

None. The slice is fully specified by the ticket; the only non-obvious implementation detail (the cancel+join before reopening the DataStore in the process-death test) is captured in Testing strategy.
