# Spec: route start destination via `PairedServerStore`; remove `pairedServerExists` boolean (#295)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:60-98` — the `setContent` body: where the start-destination decision lives (`produceState` at 74–79; `PyryNavHost` call at 88–93). The two read/write swap sites are here.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:124-138` — the `Routes.SCANNER` composable: the `onTap` lambda that currently calls `appPreferences.setPairedServerExists(true)` (line 130). This is the write swap site. `ScannerScreen(onTap = …)` stays unchanged.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:18-51` — the `PairedServerStore` interface (`load(): PairedServer?` at 25, `save(record)` at 31) and the `PairedServer` data class (43–51, four `String` fields + redacting `toString`). The KDoc at lines 15–16 is the one-line doc touch this ticket updates.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStore.kt:42-74` — the impl. Confirms `load()` is graceful (`null` on absent/undecryptable, never throws) and `save()` throws `PairedServerStoreException` on Keystore/IO failure. **The store does no field-shape validation** — it persists whatever strings it's given, so the stub values below are for forward-compat wire-shape only; no test in this ticket asserts them.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:15-20,80-89` — the `pairedServerExists` flow (15–16), its setter (18–20), and the `PAIRED_SERVER_EXISTS` key (81). These three are removed. Note `booleanPreferencesKey` stays imported (still used by `use_wallpaper_colors`, `default_yolo`, `notifications_enabled`).
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt:46-57` — the two obsolete test cases to delete (`pairedServerExists_defaultsToFalse`, `setPairedServerExists_true_isReflectedInNextEmit`). Leave the `@Before`/`@After` harness and all other cases intact.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:35` — the Koin binding `single { KeystorePairedServerStore(get()) } bind PairedServerStore::class`. **Already present — no DI change.** Inject via `koinInject<PairedServerStore>()`.
- `docs/knowledge/features/paired-server-store.md` — the #294 feature doc. § "Failure model" and § "Wiring & usage" describe the `load()`/`save()` contract this ticket consumes. The "Dormant" callout (line 7) is what this ticket's KDoc edit makes obsolete in the code.

## Design source

N/A — refactor. No UI is added or changed: the start-destination decision and the Scanner placeholder `onTap` are wiring swaps behind identical visible behaviour (scan → channel list; restart-while-paired → channel list; otherwise → welcome). `ScannerScreen` stays a stateless composable; no Figma anchor required.

## Context

Today `AppPreferences.pairedServerExists: Flow<Boolean>` — a bare DataStore boolean — gates the NavHost start destination in `MainActivity`, and the Scanner route's placeholder `onTap` flips it to `true`. #294 (merged) delivered the encrypted `PairedServerStore` (the real four-field credential record) but left it dormant. This ticket is the **strangler-fig completion**: it atomically swaps both the read (start-destination decision) and the write (Scanner `onTap`) over to the store and deletes the boolean in the same change. There is never a moment with two live sources of paired-state truth — the boolean is the sole live source until this slice removes it.

QR scanning is **explicitly out of scope** (a downstream ticket). The Scanner stays a placeholder tap that persists a stub record.

## Design

Three production files change. No new types, no new DI registration.

### 1. Start-destination read (`MainActivity.kt`, the `setContent` body)

Inject the store alongside the existing `appPreferences` injection (which stays — theme/wallpaper still use it):

```kotlin
val pairedServerStore = koinInject<PairedServerStore>()
```

Re-key the existing `produceState<Boolean?>` block on `pairedServerStore` instead of `appPreferences`, and change the producer body from `value = appPreferences.pairedServerExists.first()` to a record-presence check:

- `value = pairedServerStore.load() != null`

The surrounding tri-state (`null` → blank `Surface` while loading; `true` → `Routes.CHANNEL_LIST`; `false` → `Routes.WELCOME`) is preserved verbatim. `load()` is a `suspend fun` doing its own `withContext(Dispatchers.IO)`, so it's called directly inside the `produceState` coroutine — no extra dispatcher hop here. Behaviour: a present, decryptable record → channel list; absent **or** undecryptable record → welcome (graceful re-pair, per #294's `load()` contract — never throws).

Remove the now-unused `kotlinx.coroutines.flow.first` import **only if** no other site in the file still uses it (it does not — `pairedServerExists.first()` was the sole use). Confirm during edit.

### 2. Scanner `onTap` write (`MainActivity.kt`, the `Routes.SCANNER` composable)

Replace the `koinInject<AppPreferences>()` in this composable with `koinInject<PairedServerStore>()`. Keep `rememberCoroutineScope()`. The `onTap` lambda persists a stub record, then navigates on success:

- `scope.launch { runCatching { store.save(stubRecord) } … navigate-on-success }`

**Stub record** — a `PairedServer(...)` built from throwaway, wire-shaped placeholder constants (overwritten when QR pairing lands). Define them as `private` top-level `const val`s (or a single `private val` factory) near `Routes`, with a comment marking them placeholders:

| Field | Placeholder value | Shape rationale |
|---|---|---|
| `serverId` | `"placeholder-server"` | any non-empty string; not used as a key/path component (#294) |
| `token` | `"deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"` | lowercase hex, 64 chars (32 bytes) |
| `relayUrl` | `"wss://relay.invalid/v1/client"` | WS scheme + `/v1/client` path; `.invalid` TLD (RFC 2606) — unmistakably non-routable |
| `serverStaticPublicKey` | `"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="` | base64-STD of 32 zero bytes (44 chars: 43 `A` + `=`) |

The exact bytes are **non-load-bearing** — the store does no validation and no test asserts them. What matters: the four fields are wire-shaped so the persisted record is structurally indistinguishable from a real one for the dormant #275/#276 consumers. The developer may regenerate `serverStaticPublicKey` via any base64-STD encode of `ByteArray(32)` if preferred; the literal above is the recommended value.

**Failure handling — the one real behaviour decision.** The old `setPairedServerExists(true)` was infallible; `store.save()` is documented to throw `PairedServerStoreException` on a Keystore/IO failure. An uncaught throw inside `scope.launch` would crash the app on a placeholder tap — a regression. Faithfully preserving "the tap never crashes" requires catching it:

- On `save()` **success** → `navController.navigate(Routes.CHANNEL_LIST) { popUpTo(Routes.SCANNER) { inclusive = true }; launchSingleTop = true }` (the existing nav block, unchanged).
- On `PairedServerStoreException` → **do not navigate**. The tap is a no-op; the user stays on the placeholder and can re-tap to retry. Catch the specific `PairedServerStoreException`, not `Exception`/`Throwable` (let `CancellationException` propagate — matches #294's narrow-catch discipline).

No error UI is built (scope guard — the Scanner is a placeholder; there is no banner/snackbar surface here). If a log is added on the failure branch, log the exception class only — never the record (the redacting `toString` already guards, but keep the convention explicit).

### 3. `AppPreferences.kt` — remove the boolean

Delete the `pairedServerExists` flow (15–16), the `setPairedServerExists` setter (18–20), and the `PAIRED_SERVER_EXISTS = booleanPreferencesKey("paired_server_exists")` companion entry (81). Nothing else in the file or codebase references them (verified: the only consumers were the two `MainActivity` sites and the two tests). `booleanPreferencesKey` and `Flow`/`map` imports stay (still used by other prefs).

### 4. `PairedServerStore.kt` — KDoc one-liner

The interface KDoc (lines 15–16) currently reads:

> *Ships dormant — no consumer is wired yet; the `pairedServerExists` boolean stays the live source of paired-state truth until the sibling switch-over ticket.*

This ticket **is** that switch-over. Replace it with a line stating the new reality — `MainActivity` now decides the start destination from `load()` (record present → channel list, absent → welcome) and the Scanner placeholder persists a stub via `save()`; the QR consumers (#275/#276) remain dormant. Keep it to one short sentence; do not let the doc claim a deleted boolean is the live source.

## State + concurrency model

- **Start-destination read.** `produceState<Boolean?>` keyed on the (stable, Koin-`single`) `pairedServerStore` launches one coroutine per composition; it cancels with the composition. `load()` switches to `Dispatchers.IO` internally. Re-runs on composition recreation (e.g. rotation) — re-reads the store, cheap. No leak, no manual scope.
- **Scanner write.** `rememberCoroutineScope()` (composition-scoped, Main dispatcher); the launched job calls `save()` which hops to `Dispatchers.IO` internally. The job completes (save + navigate) before the `popUpTo(SCANNER, inclusive)` removes the composable, so cancellation-on-exit is benign. A double-tap launches two jobs → two `save()`s → last-writer-wins (#294: `dataStore.edit` is serialised); `launchSingleTop` keeps nav idempotent. Same shape as the pre-existing code; not in scope to harden further.
- **Single source of truth.** After this change, `PairedServerStore` is the sole paired-state source. No parallel boolean, no `MutableStateFlow` mirror.

## Error handling

| Layer | Failure | Result type | UI surfacing |
|---|---|---|---|
| `load()` (start dest) | absent / undecryptable / corrupt record | `PairedServer?` → `null` | routes to `Routes.WELCOME` (re-pair); never crashes (#294 graceful contract) |
| `load()` (start dest) | in-flight | `Boolean? == null` | blank `Surface` (existing loading state) |
| `save()` (Scanner tap) | Keystore/IO failure | throws `PairedServerStoreException` | caught → no navigation; user stays on placeholder, can re-tap (no error UI per scope guard) |

## Testing strategy

- **Unit (`./gradlew test`).** Delete the two obsolete `AppPreferencesTest` cases. **Add none** — round-trip + empty-default coverage for the store lives on #294's `androidTest` (`KeystorePairedServerStoreTest`), not here (AC #4). The remaining `AppPreferencesTest` cases (theme, wallpaper, model, effort, yolo, notifications, workspace) are untouched and must still pass.
- **No new instrumented test.** The `MainActivity` swap is a trivial branch select (`load() != null`) over a store whose behaviour is already proven by #294's `androidTest`; there is no existing `MainActivity`/navigation test to update, and adding a Compose-host instrumented test for a placeholder route is out of scope. Do not add one.
- **Build gate.** `./gradlew test` (unit) and `./gradlew lint` must pass; `./gradlew assembleDebug` confirms the two `MainActivity` injections and the `AppPreferences` deletion compile cleanly.

## Open questions

- **Save-failure UX.** This spec specifies a silent no-op (stay on placeholder) on `save()` failure, because the Scanner is a placeholder with no error surface. If the PO later wants a visible "pairing didn't save — try again" affordance, that lands with the real QR-scanning ticket, not here. (Decision recorded; not blocking.)

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. No untrusted data crosses a boundary in this ticket: the stub record is hardcoded in-app, not parsed from a QR payload / deep link / network frame. The real untrusted boundary (QR-payload validation → `PairedServer`) belongs to the future QR-scanning ticket; the #294 store deliberately does no field-shape validation (`KeystorePairedServerStore.kt:62-74`), and this ticket adds none — correct, because the only writer here produces trusted constants.
- **[Tokens, secrets, credentials]** No findings. The stub `token`/`serverStaticPublicKey` are public, hardcoded placeholders — not real secrets, identical on every device, and grant no access (no real server accepts `relay.invalid` + a zero pubkey). No RNG/entropy concern (nothing is generated). The record is still persisted through #294's Keystore-wrapped, AES-256-GCM at-rest envelope (app-private DataStore), and `PairedServer.toString()` redacts the `token` field — so even the placeholder never reaches Logcat via a stray `Log.d("$record")`. When QR pairing lands, `save()` overwrites the stub with the real record (last-writer-wins). Lifecycle: creation (stub) ✓; storage (encrypted, #294) ✓; rotation/revocation/expiry are the QR-pairing + relay-client tickets' concern, named out of scope here.
- **[File / storage operations]** No findings. No new file ops; persistence reuses #294's single fixed pref key in app-private DataStore (`pyrycode.paired_server`). `serverId` is hardcoded and, per #294, is never used as a pref-key or filesystem-path component — no path-traversal/TOCTOU surface. Backup/`allowBackup` hardening is app-wide and already deferred to a backup-policy ticket (#294 § Edge cases).
- **[Inter-process / Android attack surface]** No findings — N/A. This ticket adds no `Activity`/`Service`/`Receiver`, no `<intent-filter>`, no deep link, no `PendingIntent`, no content provider, no WebView. The Scanner `onTap` is an internal Compose callback, not an exported entry point.
- **[Cryptographic primitives]** No findings. No new crypto. The AES-256-GCM wrap, the dedicated `pyrycode.paired_server_wrap` Keystore key, and the non-creating `load()` lookup are all #294's, unchanged. No key/nonce reuse introduced; no attacker-controlled value is compared to a secret in this slice.
- **[Network & I/O]** No findings — N/A. No network call in this ticket. The placeholder `relayUrl` is persisted but never dialed (the relay WS client #276 is dormant); `.invalid` is non-routable by construction.
- **[Error messages, logs, telemetry]** No findings. The swap introduces no logging. `save()`/`load()` already log nothing and `PairedServerStoreException` carries only the cause-class + operation, never bytes (#294). Spec explicitly instructs: if the developer adds a save-failure log, log the exception class only, never the record. No telemetry added.
- **[Concurrency]** No findings. Both coroutines are composition-scoped and cancellation-correct (`produceState` cancels with composition; `rememberCoroutineScope` job completes before the Scanner leaves the back stack). The save-failure catch is the specific `PairedServerStoreException`, so `CancellationException` propagates (structured-concurrency honoured). No shared mutable state, no check-then-mutate on a `StateFlow`. Process-death mid-`save()`: `dataStore.edit` is atomic (#294) — either the full ciphertext lands or nothing does; a partial write is impossible, and a missing record on next start → `load()` null → welcome.
- **[Threat model alignment]** No findings. Removing the boolean means the start-destination now reflects the real persisted (encrypted) record: an undecryptable/tampered blob → `load()` null → welcome (re-pair), never a crash or a false "paired" state. Mobile-specific threats (screenshot leakage, accessibility eavesdropping, keyboard logging on token entry) attach to the real QR-token-entry flow, which this ticket does not build — named and deferred to the QR-scanning ticket. **Migration note (not a security finding):** any pre-existing install with `paired_server_exists=true` will, after upgrade, find no encrypted record → route to welcome → re-pair. Phase 0, no production installs (ticket-stated) — benign.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
