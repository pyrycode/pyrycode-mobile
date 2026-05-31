# Architecture: encrypted PairedServerStore (Keystore-wrapped credential record) (#294)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/crypto/DeviceStaticKeyStore.kt:1-44` — **the portability-split precedent.** Portable interface + model + typed exception in one android-import-free `data/crypto/` file. Mirror this file's shape exactly (KDoc tone, exception class, no `android.*`).
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystoreDeviceStaticKeyStore.kt:1-179` — **the impl to clone-and-adapt.** Lift verbatim: `getOrCreateWrapKey()` (lines 115-130), `wrap`/`unwrap` (94-113), `encode`/`decode` Base64 helpers (144-146), the `private companion object` constants (161-178), the `withContext(Dispatchers.IO)` wrapper. **Diverge** on: graceful `load()` (return `null`, don't throw — see § Error handling), JSON-serialize-then-encrypt (not raw bytes), single fixed pref key (no per-server namespacing), a **new** wrap-key alias, and **no `Mutex`** (no check-then-generate idempotency to serialise — see § State + concurrency).
- `app/src/androidTest/java/de/pyryco/mobile/data/crypto/KeystoreDeviceStaticKeyStoreTest.kt:1-132` — **the test harness to clone.** `@RunWith(AndroidJUnit4::class)`, per-test unique DataStore filename via `UUID` (line 34), `@After` deletes the wrap-key alias + the test DataStore (42-49), `newStore()` factory, fresh-instance reload as the process-death proxy (54-66), the "persisted ≠ plaintext" read-raw-pref pattern (69-79), the wrap-key-loss negative test (109-126 — but here it asserts `null`, not a throw).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` — the existing `@Serializable` data-class idiom in `data/` (the only current `@Serializable` user). `PairedServer` follows the same annotation pattern; kotlinx.serialization is multiplatform, so it does not break `data/` portability.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:24-40` — Koin module. Note the existing `single { KeystoreDeviceStaticKeyStore(get()) } bind DeviceStaticKeyStore::class` (line 32) — add the paired-store binding directly beneath it, same shape, same `get()` for the shared `DataStore<Preferences>`.
- `gradle/libs.versions.toml:51` + `app/build.gradle.kts:10,99` — confirms `kotlinx-serialization-json` (the dep) and the `kotlin.serialization` plugin are already wired. **No new dependency, no gradle edit.**
- `docs/specs/architecture/291-device-x25519-static-keypair-keystore.md` — the sibling spec; § Mechanism decision + § Security review establish the wrap-at-rest posture this ticket reuses.
- `docs/knowledge/decisions/0006-keystore-wrap-at-rest-device-static-key.md` — ADR for the AES-GCM-under-Keystore mechanism; this ticket is a second consumer of the same accepted mechanism (no new ADR — see § Open questions).

## Context

Phase 4 chain. Reconnect after process death needs the **real** paired-server identity, not a boolean. The Noise_IK session (#275) reads `serverStaticPublicKey`; the relay WS client (#276) reads `relayUrl` + `token` + `serverId`. This ticket lands the encrypted store that persists those four values and makes them readable after process death.

It ships **dormant** — no consumer is wired. The start-destination swap and the `pairedServerExists` boolean removal live in the sibling switch-over ticket; the boolean stays the sole live source of paired-state truth until then. This mirrors #291, which shipped `DeviceStaticKeyStore` before its consumer (#275) existed.

This is a data-layer credential-custody primitive — **not UI-visible**, so there is no `## Design source` / Figma section (correctly absent from the ticket body, as in #291).

## Design

### Mechanism: reuse #291 / ADR-0006 wrap-at-rest, with three deliberate divergences

The encryption mechanism is settled by ADR-0006 and proven in `KeystoreDeviceStaticKeyStore`: AES-256-GCM under a hardware-backed, non-exportable, uid-scoped `AndroidKeyStore` wrap key, per-encrypt Keystore-generated IV, `base64(iv ‖ ciphertext)` in the existing app-private `app_prefs` DataStore. This ticket is a **second consumer of that same mechanism** — not a new mechanism. The three divergences from #291 are what this spec pins down:

1. **The plaintext is a JSON document, not raw key bytes.** `PairedServer` is JSON-serialized (kotlinx.serialization, already wired) → UTF-8 bytes → AES-GCM-encrypted. On load, decrypt → UTF-8 → JSON-deserialize.
2. **`load()` is graceful** — returns `null` for absent *and* undecryptable/corrupt records (no throw, no crash). #291's `loadOrCreate()` throws on decrypt failure because silently regenerating the device identity breaks the handshake; here there is **no identity-drift risk** (the record is re-fetchable from the QR), so an unreadable record resolves to `null` → "no paired server" → re-pair. `save()` may still throw (loudly) — see § Error handling.
3. **A dedicated wrap-key alias** (`pyrycode.paired_server_wrap`), distinct from #291's `pyrycode.device_static_wrap`. No cross-purpose key reuse: this at-rest key is a symmetric AEAD key for the credential blob; it is **not** #291's asymmetric X25519 Noise identity material. No hard dependency on #291.

A fourth simplification falls out of the single-record shape: **one fixed pref key** (no per-server namespacing) → no `serverId`-collision surface, and **no `Mutex`** (no check-then-generate to serialise).

### Package & placement: `data/crypto/`

Co-locate with `DeviceStaticKeyStore` in the existing `data/crypto/` package. That package's purpose is exactly "Keystore-backed encrypted custody + its portable models," and a reviewer compares this ticket against #291. (Alternative considered: a new `data/pairing/` package — semantically the record is connection credentials, not a crypto primitive. Rejected: introduces a new package for two files, splits the two encrypted stores apart, and the ticket body explicitly cites the `data/crypto/DeviceStaticKeyStore.kt` precedent. The AC says "live in `data/`", which `data/crypto/` satisfies.)

### Portable file — `data/crypto/PairedServerStore.kt` (no `android.*` imports)

Three exported declarations, mirroring `DeviceStaticKeyStore.kt`'s portable file:

```kotlin
@Serializable
data class PairedServer(
    val serverId: String,            // server identifier
    val token: String,               // hex; bearer secret, travels inside the encrypted hello
    val relayUrl: String,            // dialed verbatim, /v1/client path included
    val serverStaticPublicKey: String, // base64-STD, 32 bytes
) {
    // Redact the bearer token from the auto-generated data-class toString (leak guard — see Security review).
    override fun toString(): String = "PairedServer(serverId=$serverId)"
}

interface PairedServerStore {
    /** Decrypt + return the persisted paired server, or null if absent OR undecryptable/corrupt
     *  (graceful → re-pair; never throws, never crashes). */
    suspend fun load(): PairedServer?
    /** Encrypt + persist the record, overwriting any existing one. Throws PairedServerStoreException
     *  on a Keystore / IO failure (the pairing did not persist — caller surfaces "try again"). */
    suspend fun save(record: PairedServer)
}

class PairedServerStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

- `PairedServer` is a `data class`: its fields are all `String`, so structural `equals`/`hashCode` are correct and useful (tests assert `assertEquals(record, loaded)` — **unlike** #291's `ByteArray` holder). The **only** override is `toString()`, declared explicitly so the compiler keeps the generated `equals`/`hashCode`/`copy` but uses our redacting `toString`.
- `@Serializable` is `kotlinx.serialization.Serializable` (multiplatform) — **not** an `android.*` import; the file stays Compose-Multiplatform-portable (AC #1).
- **Byte-faithfulness (AC):** all four fields are stored and reloaded as `String`, verbatim. JSON round-trips String content losslessly (escapes are reversible), so the hex token, the base64-STD pubkey (incl. `+` `/` `=`), and the relay URL with its `/v1/client` path survive byte-identical. We do **not** decode the base64/hex field content into bytes anywhere — the field Strings pass through JSON untouched; only the outer JSON document is encrypted. #275/#276 receive exactly what the server sent.

### Impl — `data/crypto/KeystorePairedServerStore.kt` (`android.security.keystore.*` allowed)

Constructor takes **only** `DataStore<Preferences>` (no `Context` — AndroidKeyStore needs none), keeping the data layer portable behind the interface. A platform-custodian impl behind a portable interface importing `android.*` is the accepted precedent (#291; see `[[data-layer-android-import-exception]]`).

Behaviour contracts (lift the helper bodies from `KeystoreDeviceStaticKeyStore`):

- `save(record)`: `Json.encodeToString(record)` → `.toByteArray(UTF_8)` → `wrap(...)` (AES-GCM under `getOrCreateWrapKey()`, IV-prepended, exactly as #291) → `Base64.NO_WRAP` encode → write the single `stringPreferencesKey(PREF_KEY)` in one `dataStore.edit { }`. Wrapped in `withContext(Dispatchers.IO)`. A Keystore/IO failure surfaces as `PairedServerStoreException` (see § Error handling).
- `load()`: read `PREF_KEY`; **null → return null immediately** (no Keystore touch — fast empty path, mirrors #291's `publicKey` fast read). If present: `Base64` decode → split 12-byte IV → AES-GCM decrypt → UTF-8 → `Json.decodeFromString<PairedServer>`. **Any** decode/decrypt/parse failure → return `null` (graceful). Wrapped in `withContext(Dispatchers.IO)`.
- Reuse a single private `Json` instance (default config is fine; the document is internal, not a public API — `ignoreUnknownKeys = true` is a reasonable forward-compat touch but not required).
- Wrap-key lookup on `load()`: prefer a non-creating lookup (`KeyStore.getKey(alias)` → if absent, the persisted blob can't be decrypted → return `null`), so a read never mints a key. Re-creating the key on load is also acceptable (it then fails the GCM tag → caught → `null`); either path satisfies the AC. `save()` uses get-or-create.

**Single pref key:** `stringPreferencesKey("pyrycode.paired_server")` → `base64(iv ‖ ciphertext)`. One record, one key — no per-server namespacing, so **none** of #291's key-collision surface exists (server-id is a field *inside* the encrypted blob, never a pref-key component).

**Wrap key:** alias `pyrycode.paired_server_wrap`, AES-256-GCM, get-or-create identical to #291's `getOrCreateWrapKey` (`PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, 256-bit). Leave `setRandomizedEncryptionRequired(true)` (default) → Keystore emits a fresh IV per encrypt, closing the GCM-nonce-reuse footgun deterministically. Do **not** set `setUserAuthenticationRequired(true)` (breaks headless reconnect) or `setIsStrongBoxBacked(true)` (throws on devices without a secure element).

**Data flow:**

```
save(record)                              load()
  └─ IO:                                    └─ IO:
     JSON(record) → UTF-8 bytes                read prefs[PREF_KEY]
     wrap(bytes)  (Keystore IV)                ├─ null → return null            (absent → re-pair)
     edit { prefs[PREF_KEY] = b64(iv‖ct) }     └─ present → decode → unwrap → UTF-8 → JSON
       └─ throws PairedServerStoreException              ├─ ok    → PairedServer
          on Keystore/IO failure                         └─ fail  → return null  (undecryptable → re-pair)
```

### DI wiring (`AppModule.kt`)

One binding, directly beneath the existing `DeviceStaticKeyStore` binding:

```kotlin
single { KeystorePairedServerStore(get()) } bind PairedServerStore::class
```

`get()` resolves the already-registered `DataStore<Preferences>`. No consumer exists (#275/#276 not wired) → no call-site cascade. Edit fan-out: **1**.

## State + concurrency model

- Not a ViewModel — no `StateFlow`/`UiState`/`Event`. Plain `suspend` functions on a Koin singleton.
- **No `Mutex`.** Unlike #291, there is no check-then-generate idempotency to serialise: `load()` is a pure read, `save()` is a pure overwrite. DataStore's `edit { }` is already serialised and atomic per-DataStore, so concurrent `save()`s are last-writer-wins with each write atomic — no half-record state (single key, single edit). Dropping the `Mutex` is a deliberate simplification, not an omission.
- **Dispatcher:** `withContext(Dispatchers.IO)` around the Keystore + `Cipher` work (blocking, IPC-bound), as #291. DataStore is main-safe but fine inside the same `IO` context.
- **Cancellation/shutdown:** no long-lived jobs. The only durable write is one atomic `edit { }`; process death mid-`save()` either persisted the full blob or nothing — never a partial record. No cleanup hook. **Cancellation safety on `load()`:** the failure catch must be a *specific* exception set (below), never `Exception`/`Throwable` — `CancellationException` (a subtype of `IllegalStateException`, not of the caught types) must propagate so structured-concurrency cancellation is honoured.

## Error handling

The inverted-contract relative to #291 is the heart of this ticket:

| Path | Failure | Source | Behaviour |
|---|---|---|---|
| `load()` | record absent | pref key missing | return `null` (fast path, no Keystore touch) |
| `load()` | wrap key gone (reinstall, credential reset, restore-to-new-device) | `KeyStore.getKey` null / `AEADBadTagException` | return `null` (→ re-pair) |
| `load()` | tampered / truncated blob, bad base64 | `IllegalArgumentException`, `AEADBadTagException` | return `null` |
| `load()` | malformed JSON / schema drift | `SerializationException` (⊂ `IllegalArgumentException`) | return `null` |
| `save()` | Keystore unavailable / IO failure | `GeneralSecurityException`, `IOException` | wrap → throw `PairedServerStoreException` |

- `load()` catches the **specific set** `{ GeneralSecurityException, IOException, IllegalArgumentException }` and returns `null`. `AEADBadTagException ⊂ GeneralSecurityException`; `SerializationException ⊂ IllegalArgumentException`; `Base64.decode` throws `IllegalArgumentException`. None of these is a `CancellationException` supertype, so cancellation propagates. **Do not** catch `Exception`/`Throwable`.
- `save()` mirrors #291's `runCatchingKeystore` wrap: re-throw `PairedServerStoreException` as-is, wrap `GeneralSecurityException`/`IOException` into `PairedServerStoreException(cause.javaClass.simpleName)`. The typed exception keeps the portable interface's error contract portable (no `android.*`/`javax.*` type leaking as the API contract).
- **No secret material in logs or exception messages.** The `token` (bearer secret), the decrypted JSON, the plaintext field values, and the wrapped blob must **never** reach `Log`/`Timber` or be embedded in a `PairedServerStoreException` message. Exception messages carry the *cause class* + failed operation, not bytes. The redacting `PairedServer.toString()` is the structural guard against an accidental `Log.d(tag, "$record")` leaking the token (see § Security review). No logging-framework call is required by this ticket; this is a prohibition enforced at code-review.

## Testing strategy

Instrumented (`androidTest/`) — a real `AndroidKeyStore` requires a device/emulator; a JVM unit test cannot exercise it, so there is no `test/` companion. `./gradlew check` compiles it; `./gradlew connectedAndroidTest` on a device proves persistence. `@RunWith(AndroidJUnit4::class)`.

Place at `app/src/androidTest/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStoreTest.kt`. Clone #291's harness: unique per-test DataStore filename (`UUID`), `@After` deletes the `pyrycode.paired_server_wrap` alias + the test DataStore, a `newStore()` factory.

Use an **adversarial sample record** to prove byte-faithfulness — pubkey containing `+`/`/`/`=`, relayUrl with the `/v1/client` path (+ a query param), token as a long hex string.

Scenarios (developer writes bodies in the project idiom):

- **save → load round-trip recovers all four fields byte-accurate (AC #5a).** `save(sample)` then `load()` → `assertEquals(sample, loaded)`, **and** assert each field individually (`serverId`, `token`, `relayUrl`, `serverStaticPublicKey`) to make byte-faithfulness explicit, especially the adversarial pubkey/relayUrl content.
- **process-death proxy.** `save(sample)` on store A; construct a **fresh** store B over the *same* DataStore; `B.load()` → equals `sample`. (Comment: genuine process death is survived by construction — the DataStore file and the Keystore alias both outlive the process.)
- **empty default (AC #5b).** Fresh store, no prior `save` → `load()` returns `null`.
- **persisted blob is ciphertext, not plaintext (AC #2).** Read the raw DataStore string at `pyrycode.paired_server`, base64-decode → assert the decoded bytes do **not** contain the `token` as a UTF-8 substring (and are longer than the plaintext JSON by the IV+tag overhead). Proves encrypted-at-rest.
- **undecryptable record → null, no throw (AC #4 — the key divergence from #291).** `save(sample)`; delete the `pyrycode.paired_server_wrap` alias; `load()` → asserts `null` (not an exception, not a crash, not a regenerated record).
- **corrupt blob → null.** Write a non-base64 / garbage string directly into the `pyrycode.paired_server` pref; `load()` → `null` (covers the `IllegalArgumentException`/`SerializationException` paths).
- **overwrite (last-writer-wins).** `save(a)`, `save(b)`, `load()` → equals `b`. (Optional; cheap, proves `save` overwrites cleanly.)

## Open questions

- **No new ADR.** ADR-0006 already accepts AES-GCM-under-Keystore wrap-at-rest; this ticket is a second consumer of that decision, not a new architectural choice. The documentation phase may add a one-line "also consumed by #294" pointer to ADR-0006's *Related* section after merge — not a developer deliverable. (Flag, not a task.)
- **`ignoreUnknownKeys`.** Recommended-not-required forward-compat for the `Json` config if the record schema ever grows; harmless either way.
- **Transient-buffer zeroization.** Recommended defense-in-depth (zero the decrypted UTF-8 buffer after parse), not AC-mandated; no observed failure requires it. The returned `PairedServer` (immutable Strings) cannot be zeroed.

## Sizing

S — confirmed (PO sized S; not overriding to XS — genuine crypto + serialization logic + an instrumented round-trip test, materially more than a trivial XS). Production source files (excluding test + md + spec): `PairedServerStore.kt` (new), `KeystorePairedServerStore.kt` (new), `AppModule.kt` (one binding) = **3** (< 5). New exported types: `PairedServer`, `PairedServerStore`, `PairedServerStoreException`, `KeystorePairedServerStore` = **4** (< 5). Total written ≈ 30 (portable file) + ~85 (impl) + 1 (DI) + ~120 (test) ≈ **236 lines** (< 600). Edit fan-out: one DI call site, no symbol rename, no consumer cascade — **1** (≤ 10). ACs: **5** (≤ 5). No state-machine fan-out (one graceful catch on load, one throw on save). No new dependency (Keystore + `javax.crypto` framework; kotlinx.serialization + DataStore already wired) → no `libs.versions.toml` / `build.gradle.kts` edit. Within every red line; ships as one ticket.

## Security review

**Verdict:** PASS

The asset is the paired-server credential record — above all the `token` (a **bearer secret** that travels inside the encrypted hello), and the server identity (`serverStaticPublicKey`, `serverId`, `relayUrl`) the phone re-establishes its authenticated relay session from. Disclosure of the token lets an attacker impersonate the phone to the relay; the threat the at-rest encryption bounds is a lost/stolen device or file-system extraction. Two SHOULD-FIX items were folded into the spec before this verdict (redacting `toString()`; specific catch set); the checklist was then re-walked from the top with no MUST FIX remaining.

**Findings:**

- **[Trust boundaries]** No code-level finding; one decision named. The single untrusted→trusted boundary is `load()` (ciphertext-on-disk → decrypted JSON → typed `PairedServer`), explicit in one function; downstream holds the typed value. The store performs **no field-shape validation** (relayUrl scheme, pubkey length/base64, token format) — it is a faithful persistence layer, and the record is produced by the future QR-pairing-input ticket. Persisting unvalidated content is not exploitable in *this* ticket (no consumer reads it). **OUT OF SCOPE** — input validation is owned by the pairing-input ticket; named here so it isn't lost.
- **[Tokens / secrets]** **SHOULD FIX — folded into spec.** `PairedServer` is a `data class`, so its compiler-generated `toString()` would render the bearer `token` in plaintext, leaking it to Logcat (`Log.d("$record")`, ADB / `READ_LOGS` on rooted devices) and crash reporters. Closed by declaring an explicit redacting `toString()` (`PairedServer(serverId=…)`) in the portable file — `equals`/`hashCode`/`copy` stay generated — plus the "no secret material in logs or exception messages" prohibition (§ Error handling). Storage is AES-256-GCM ciphertext in app-private DataStore under a non-exportable, uid-scoped Keystore wrap key — never plaintext on disk (AC #2/#3). Generation uses the platform CSPRNG (Keystore-emitted IV); no `kotlin.random.Random`. Token *creation/rotation/revocation* is server/QR-side and re-pair-replaces-whole-record — **OUT OF SCOPE** (no per-token rotation in v2; same stance as #291/ADR-0006). No constant-time-compare obligation — the store never compares the token to anything.
- **[File / storage]** No findings. The pref key is the fixed constant `pyrycode.paired_server`; `serverId` lives *inside* the encrypted blob, never as a pref-key or filesystem-path component — so **none** of #291's attacker-influenced-key-collision surface exists, and there is no path-traversal/TOCTOU surface. Storage is app-private internal (`MODE_PRIVATE` `app_prefs`, not external/MediaStore). The write is a single atomic `edit { }` — no partial-record state.
- **[Backup]** **SHOULD-consider, OUT OF SCOPE (names owner).** Auto-backup of `app_prefs` copies only the ciphertext; Keystore keys are never backed up and don't migrate devices, so a restored backup can't decrypt → our `load()` → `null` → re-pair path (more benign here than #291, which throws). Excluding `app_prefs` from backup (`android:dataExtractionRules` / `fullBackupContent`) is app-wide manifest hardening, outside this ticket's file set — same flag as #291's, for the manifest/backup-policy ticket.
- **[Inter-process / Android surface]** No findings — N/A by design. A plain injected Koin singleton; this ticket adds no exported component, Intent filter, deep link, PendingIntent, ContentProvider, or WebView. The Keystore wrap key is OS-uid-scoped — a co-resident malicious app cannot unwrap the blob even if it reads the DataStore file.
- **[Cryptographic primitives]** No findings. AES-256-GCM via the platform `Cipher`; no hand-rolled crypto. **Dedicated wrap-key alias `pyrycode.paired_server_wrap`, distinct from #291's `pyrycode.device_static_wrap`** — no cross-purpose key reuse (this is a symmetric AEAD at-rest key, not #291's asymmetric X25519 identity material). GCM nonce reuse — the one footgun — is closed by leaving `setRandomizedEncryptionRequired(true)`, so the Keystore emits a fresh IV per encrypt. The plaintext is a JSON document of a typed object (not field-concatenation), so there is no delimiter-injection surface from a field value containing a separator.
- **[Network & I/O]** No findings — N/A. This ticket opens no socket; TLS, frame caps, timeouts, and cert pinning belong to the WS-client ticket (#276). The credentials custodied here protect that future traffic.
- **[Error messages / logs / telemetry]** **SHOULD FIX folded in** (see Tokens). `PairedServerStoreException` messages carry the *cause class* + failed operation, never field values; the wrapped causes are platform `GeneralSecurityException`/`IOException` (which don't embed our plaintext), and the load-path `SerializationException` is caught → `null`, never thrown. No telemetry/analytics added.
- **[Concurrency]** **SHOULD FIX folded in.** A naive implementation of "graceful, never throws" — `catch (e: Exception) { null }` — would swallow `CancellationException` (breaking structured-concurrency cancellation) **and** mask genuine programming bugs as a silent "no paired server" → re-pair loop. Closed by mandating the **specific** catch set `{ GeneralSecurityException, IOException, IllegalArgumentException }` (none a `CancellationException` supertype) on `load()`, never `Exception`/`Throwable` (§ Error handling, § State + concurrency). No `Mutex` is correct here (no check-then-act; DataStore `edit { }` is atomic and serialised — concurrent saves are last-writer-wins with no torn state). Not a ViewModel → no `StateFlow` TOCTOU. Process death mid-`save()` persists the full blob or nothing.
- **[Threat model alignment]** Aligned with `protocol-mobile.md` § Security model and #291/ADR-0006: protecting the token + server identity at rest bounds an at-rest / device-theft attacker from extracting the bearer credential. **Residual (documented, accepted):** an attacker executing code *as this app's uid* on an unlocked device can call the Keystore to unwrap the blob — same accepted residual as #291. A **rollback/freshness** attack (replacing the at-rest blob with a previously-captured older valid ciphertext — GCM authenticates integrity, not freshness) requires that same uid-level file access, who could already read the current token directly, so it is dominated by the accepted residual — **OUT OF SCOPE / accepted.** Mobile-UI threats (screenshot leakage, accessibility eavesdropping, third-party-keyboard logging on token entry) attach to the QR-scan / token-entry UI, not to this dormant data-layer store — **OUT OF SCOPE**, owned by the pairing-input ticket.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
