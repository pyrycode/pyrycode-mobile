# Architecture: device X25519 static keypair persisted via Android Keystore (#291)

## Files to read first

- `app/src/main/java/com/southernstorm/noise/protocol/Curve25519DHState.java:73-102` — `generateKeyPair` / `setPrivateKey` / `getPublicKey`. **The load-bearing byte contract:** `setPrivateKey(raw,0)` copies 32 bytes **verbatim** as the private scalar, then derives the public via `Curve25519.eval`. This is exactly what #275 will call with our stored private key, and it is how this ticket must derive the mirrored public key so the two agree byte-for-byte.
- `app/src/main/java/com/southernstorm/noise/protocol/Noise.java:54,87` — `Noise.random(byte[])` and `static DHState createDH(String) throws NoSuchAlgorithmException`. `Curve25519DHState` is package-private, so the only way to reach it from Kotlin is `Noise.createDH("25519")`. Note the checked `NoSuchAlgorithmException`.
- `app/src/main/java/com/southernstorm/noise/protocol/DHState.java:58-99` — `generateKeyPair` / `getPublicKey` / `setPrivateKey` / `getPrivateKey` contract; confirms the consumer wants **raw bytes** it feeds via `setPrivateKey`, which is what our abstraction returns.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:1-90` — the `DataStore<Preferences>` persistence idiom (string keys in a `companion object`, `dataStore.data.map { … }` reads, `dataStore.edit { … }` writes). The wrapped-private blob + public mirror persist the same way; no new persistence dependency.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:22-37` — Koin module. Note the existing `single<DataStore<Preferences>>` provider (reuse it) and the `single { … } bind Interface::class` idiom. Add the store binding here.
- `docs/specs/architecture/272-phase4-networking-crypto-dependencies.md` — sibling `security-sensitive` Phase-4 crypto spec; the `## Security review` section and the "no new repository / no new dependency" posture this ticket continues.
- `docs/knowledge/decisions/0004-vendor-noise-java-crypto.md` — why noise-java is vendored in-tree (`com.southernstorm.noise.*`), pure-Java, no JCE/BouncyCastle. Context for why we generate/derive through it rather than Android's `XDH` provider.
- Vault doc **"Phase 4 — Noise Client Spike Findings"** (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`) — § "noise-java IK initiator call sequence" shows the proven `hs.localKeyPair.generateKeyPair()` line annotated *"device static (Android: Keystore)"*, the suite string, and the alias convention `pyrycode.device_static.<server-id>`. (codegraph won't surface this — it's a markdown vault note.)
- An existing instrumented test, e.g. `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt:1-30` — only for the `@RunWith(AndroidJUnit4::class)` + `ApplicationProvider` harness scaffold. **This ticket's test is a plain instrumented unit test, not a `ComposeTestRule` test** — ignore the Compose-specific parts.

## Context

Phase 4 chain. Mobile Protocol v2 (`Noise_IK_25519_ChaChaPoly_BLAKE2s`) makes the phone the handshake **Initiator**; its static (`s`) key is the device's long-term cryptographic identity for a given paired server. The proving spike (2026-05-29) confirmed the initiator call sequence and annotated `hs.localKeyPair.generateKeyPair()` as *"device static (Android: Keystore)"*.

This ticket lands the persistent keypair primitive that #275 (the Noise_IK session) consumes as the initiator's local static key. Requirements: **one keypair per paired server-id**, generated once and never silently regenerated, the private key protected by the Android Keystore and never written to disk in plaintext, and the 32-byte public key mirrored for fast read without a Keystore round-trip. Identifier convention `pyrycode.device_static.<server-id>`.

This is a data-layer key-custody primitive — **not UI-visible**, so there is no `## Design source` / Figma section (correctly absent from the ticket body).

## Design

### Mechanism decision (the central design call) — mechanism (a): wrap-at-rest

The ticket names the tension: noise-java's `Curve25519DHState` performs the X25519 DH **in software on raw private-key bytes** (`Curve25519DHState.java:139-143`), so it cannot consume a non-exportable Keystore key directly. The two proven shapes:

| | **(a) wrap raw key with a Keystore AES-GCM key — CHOSEN** | **(b) non-exportable `KEY_ALGORITHM_XDH` Keystore key + `KeyAgreement("XDH")`** |
|---|---|---|
| Private-key custody | Raw key AES-256-GCM-encrypted at rest under a hardware-backed Keystore wrap key; decrypted into process RAM transiently during a handshake | Raw key never materialises in app RAM (on devices that hardware-back XDH) |
| #275 consumption | `hs.localKeyPair.setPrivateKey(priv, 0)` on the **existing** `Curve25519DHState` — zero extra code | #275 must author a **custom `DHState`** whose `calculate()` delegates to `KeyAgreement("XDH")`, and splice it into handshake setup — couples #275 to the Keystore |
| Hardware backing reality | AES-GCM is TEE-backed on effectively all API-33 devices | XDH/X25519 TEE/StrongBox backing is **OEM-spotty**; frequently falls back to software-keystore — the headline custody win often doesn't materialise |
| Surface | Smaller; stays within S | "More code" (per ticket) |

**Chosen: (a).** It satisfies every AC, keeps the #275 seam clean (the abstraction returns raw bytes; the consumer is *not* coupled to the Keystore mechanism, per the ticket's explicit requirement), gets *reliable* hardware backing fleet-wide via the AES-GCM wrap key, and is the smaller surface. The residual exposure — the raw private key is in process memory transiently during a handshake — is **inherent to software Noise** (noise-java does software DH regardless of mechanism) and is the explicit tradeoff the ticket names. The § Security review formally walks it.

PO's sizing note flagged that mechanism (a) has a natural envelope-then-store split *if it exceeds S*. It does not (see § Sizing); no split.

### Package & types

New sub-package `data/crypto/` (parallels `data/network/`).

```kotlin
// data/crypto/DeviceStaticKeyStore.kt
interface DeviceStaticKeyStore {
    /** Generate-or-load the per-server device static keypair. Idempotent: never
     *  regenerates once persisted. Throws DeviceStaticKeyException on Keystore /
     *  crypto failure (caller treats as "needs re-pair" — does NOT regenerate). */
    suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair

    /** 32-byte device public key for serverId, read from the plaintext mirror only
     *  (no Keystore round-trip). null if no keypair has been generated yet. */
    suspend fun publicKey(serverId: String): ByteArray?
}

class DeviceStaticKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

`DeviceStaticKeyPair` holds the two raw 32-byte arrays. **Gotcha to flag for the developer:** a Kotlin `data class` with `ByteArray` fields gets reference-equality `equals`/`hashCode` — do not rely on structural equality for it; tests compare the `ByteArray` fields with `assertArrayEquals`, not the holder's `equals`. Either keep it `data class` with this caveat documented, or make it a plain class. (No `copy`/destructuring need exists.)

```kotlin
// data/crypto/DeviceStaticKeyStore.kt (same file)
class DeviceStaticKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)
```

### Implementation: `KeystoreDeviceStaticKeyStore(dataStore)`

Constructor takes **only** `DataStore<Preferences>` — `AndroidKeyStore` operations go through the keystore daemon and need no `Context`.

**Persistence (reuses the existing `app_prefs` DataStore — no new file, no new dependency):** two `stringPreferencesKey`s per server-id —
- `pyrycode.device_static.<serverId>` → base64 of `iv ‖ ciphertext` (the AES-GCM-wrapped private key, 12-byte IV prepended). This is the AC's logical identifier convention, used verbatim for the private blob.
- `pyrycode.device_static_pub.<serverId>` → base64 of the 32-byte public key (the fast-read mirror; non-secret).

**Collision-safety (server-id is treated as opaque and may be attacker-influenced via the QR pairing payload):** the two keys differ in the fixed character immediately after `pyrycode.device_static` (`.` for the private blob vs `_` for the public mirror), so **no server-id value can make a private-blob key collide with another server's public-mirror key.** Do **not** use a `.pub` *suffix* appended after the server-id — `serverId = "foo.pub"` would then write `pyrycode.device_static.foo.pub`, colliding with server `foo`'s public-mirror slot and breaking per-server isolation (AC #1). DataStore preference keys are plain map keys (not filesystem paths), so beyond this prefix discipline no server-id sanitisation is required.

Both written in **one `dataStore.edit { }`** block so persisted state is all-or-nothing — partial state (pub without priv) is structurally unreachable in normal operation. The `app_prefs` DataStore lives in app-private internal storage (the app's `datastore/` dir under `filesDir`, `MODE_PRIVATE`) — never external storage / MediaStore; the at-rest artifact is the AES-GCM ciphertext, and the wrap key is Keystore-resident and **uid-scoped** (no other app on the device can invoke it to unwrap the blob even if it read the file).

**Wrap key (Keystore):** a single shared AES-256-GCM key, alias `pyrycode.device_static_wrap`, get-or-create via `KeyGenerator.getInstance("AES", "AndroidKeyStore")` with `KeyGenParameterSpec(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)` → `setBlockModes(GCM)`, `setEncryptionPaddings(NoPadding)`, `setKeySize(256)`. Leave `setRandomizedEncryptionRequired` at its default (`true`): the Keystore then **generates a fresh random IV per encrypt op** (read it from `cipher.iv` after `init(ENCRYPT_MODE, key)` and persist it with the ciphertext), which removes the entire GCM-nonce-reuse footgun from the developer's hands. **Do not** set `setUserAuthenticationRequired(true)` (would break headless reconnect). **Do not** force `setIsStrongBoxBacked(true)` (raises `StrongBoxUnavailableException` on devices without a secure element); plain `AndroidKeyStore` is TEE-backed on API-33 → satisfies "hardware-backed where the device supports it".

**Generate (first access for a server-id), behaviour contract:**
1. 32 bytes from `java.security.SecureRandom()` → the private scalar (platform RNG, properly seeded on Android).
2. Derive the public: `Noise.createDH("25519")` → `setPrivateKey(scalar, 0)` → `getPublicKey(pub, 0)` → `destroy()`. Deriving through noise-java (not Android `XDH`) guarantees the mirror is byte-identical to what #275's `Curve25519DHState` produces, eliminating any cross-implementation X25519 assumption.
3. AES-GCM-encrypt the scalar under the wrap key; persist `base64(iv ‖ ct)` + `base64(pub)` in one `edit { }`.
4. Return `DeviceStaticKeyPair(pub, scalar)`.

**Load (repeat access):** read both prefs; if the priv blob is present, split off the 12-byte IV, AES-GCM-decrypt → raw scalar, return `DeviceStaticKeyPair(pub, scalar)`. **Idempotent — never regenerates when the blob exists** (AC #2).

**Fast public read:** `publicKey(serverId)` reads only the `.pub` pref → base64-decode → 32 bytes; **no Keystore op** (AC #4).

**Data flow:**

```
loadOrCreate(serverId)
  └─ mutex.withLock:                       ← serialises check-then-generate (idempotency)
       read prefs[priv], prefs[pub]
       ├─ present  → AES-GCM unwrap(priv) ───────────────► DeviceStaticKeyPair(pub, scalar)
       └─ absent   → SecureRandom scalar
                     Noise.createDH("25519") → derive pub
                     AES-GCM wrap(scalar)  (Keystore-generated IV)
                     edit { prefs[priv]=b64(iv‖ct); prefs[pub]=b64(pub) }  ► DeviceStaticKeyPair(pub, scalar)
```

### DI wiring (`AppModule.kt`)

Add one binding alongside the existing singles:

```kotlin
single { KeystoreDeviceStaticKeyStore(get()) } bind DeviceStaticKeyStore::class
```

`get()` resolves the already-registered `DataStore<Preferences>`. No other call sites — #275 does not exist yet, so there is no consumer cascade.

## State + concurrency model

- Not a ViewModel — no `StateFlow`, no `UiState`/`Event`. Plain `suspend` functions on a Koin singleton.
- **Idempotency under concurrency:** a `kotlinx.coroutines.sync.Mutex` (held across the read-check-generate-write in `loadOrCreate`) makes generate-or-load atomic, giving AC #2's "never silently regenerates" a *deterministic* guarantee rather than relying on callers not racing. Single Koin singleton → one Mutex instance suffices (single-process app).
- **Dispatcher:** wrap the Keystore + cipher work (blocking, CPU/IPC-bound) in `withContext(Dispatchers.IO)`. DataStore reads/writes are already main-safe but are fine inside the same `IO` context.
- **Cancellation/shutdown:** no long-lived jobs. The only durable write is the single `edit { }`; DataStore writes are atomic, so process death mid-`loadOrCreate` either persisted both keys or neither — never a half-keypair. No cleanup hook needed.

## Error handling

All failure modes surface as a single typed `DeviceStaticKeyException` (lowest-assumption contract; mirrors how Keystore APIs themselves signal — by throwing). #275 owns how the failure is surfaced in the UI.

| Failure | Source | Behaviour |
|---|---|---|
| Suite/algorithm missing | `Noise.createDH` → `NoSuchAlgorithmException` | wrap → throw. (Shouldn't happen — vendored suite is present; defensive.) |
| Keystore unavailable / wrap key gone | `KeyStoreException`, `UnrecoverableKeyException` | wrap → throw. |
| GCM decrypt fails (tampered/corrupt blob, key invalidated by credential reset or restore-to-new-device) | `AEADBadTagException` | wrap → throw. **Do NOT regenerate** — a fresh keypair changes the device identity, the server rejects the handshake (close `4426`), and that silently violates AC #2. The re-pair flow is a future ticket. |
| Corrupt store (base64 fails, or `.pub` present but priv blob missing) | decode | wrap → throw; never half-regenerate. |

The single-`edit{}` write makes "pub present, priv missing" unreachable in normal operation; the check is defensive against external tampering of `app_prefs`.

**No key material in logs or exception messages.** The private scalar, the unwrapped buffer, and the wrapped `iv‖ct` blob must **never** reach `Log`/`Timber` (debug or release) or be embedded in a `DeviceStaticKeyException` message. Loggable on these paths: the event, the `serverId`, and the public key (non-secret). Exception messages carry the *cause class* and the operation that failed, not bytes. (No logging framework call is required by this ticket; this is a prohibition, enforced at code-review.)

## Testing strategy

Instrumented (`androidTest/`) — real `AndroidKeyStore` requires a device/emulator. `./gradlew check` compiles it; `./gradlew connectedAndroidTest` on a device proves persistence. A JVM unit test cannot exercise `AndroidKeyStore`, so there is no `test/` companion. `@RunWith(AndroidJUnit4::class)`.

Place at `app/src/androidTest/java/de/pyryco/mobile/data/crypto/KeystoreDeviceStaticKeyStoreTest.kt`. **Isolation:** inject a test-scoped `DataStore` (unique prefs filename via the instrumentation context) so tests never touch real `app_prefs`; `@After` deletes the `pyrycode.device_static_wrap` Keystore alias and clears the test DataStore so runs are hermetic.

Scenarios (developer writes the bodies in the project idiom):

- **generate → persist → reload (AC #5a, AC #1, AC #2).** `loadOrCreate("srv")` on store instance A → `kp1`. Construct a **fresh** store instance B over the *same* DataStore → `loadOrCreate("srv")` → `kp2`. `assertArrayEquals(kp1.publicKey, kp2.publicKey)` **and** private bytes equal. The fresh-instance reload is the automatable proxy for process restart; genuine process death is survived by construction (the DataStore file and the system Keystore alias both outlive the process) — note this in a test comment rather than trying to fork a process.
- **persisted private ≠ raw key bytes (AC #5b, AC #3).** Read the raw DataStore string at `pyrycode.device_static.srv`, base64-decode → assert it is **not equal** to `kp1.privateKey` and its length is `> 32` (12-byte IV + 32-byte ciphertext + 16-byte GCM tag = 60 bytes). Proves Keystore-wrapped, not plaintext.
- **fast public read (AC #4).** `publicKey("srv")` equals `kp1.publicKey`. Document (test comment) that the impl reads only the DataStore mirror.
- **public absent before generate.** `publicKey("never-generated")` is `null`.
- **per-server isolation (AC #1).** `loadOrCreate("A").publicKey` ≠ `loadOrCreate("B").publicKey`.
- **never silently regenerates on Keystore loss (AC #2, negative).** After generating for `"srv"`, delete the wrap-key alias, then `loadOrCreate("srv")` → expect `DeviceStaticKeyException` (the persisted blob cannot be unwrapped), **not** a new keypair. This is the deterministic guard that a decrypt failure does not fall through to regeneration.

## Open questions

- **Wrap-key scope.** Spec recommends a *single shared* AES-GCM key with a per-encrypt Keystore-generated IV (the standard pattern; IV-uniqueness enforced by the platform). A developer who prefers per-server compromise isolation may use `pyrycode.device_static_wrap.<serverId>` instead — both satisfy the ACs. Security review clears the shared-key choice.
- **Transient-buffer zeroization.** Recommended hardening, not AC-mandated: zero the intermediate decrypted-scalar buffer once it is no longer needed (the *returned* array cannot be zeroed by the store). Defense-in-depth; no observed failure requires it.
- **`DeviceStaticKeyPair` equality.** See the `ByteArray`-in-`data class` caveat above — developer chooses plain class vs documented data class.

## Sizing

S — confirmed (PO sized S; not overriding to XS — genuine crypto logic + an instrumented round-trip test). Production source files: `DeviceStaticKeyStore.kt` (new), `KeystoreDeviceStaticKeyStore.kt` (new), `AppModule.kt` (one binding) = **3** (< 5). Total written ≈ 25 (interface+types) + ~110 (impl) + 2 (DI) + ~80 (test) ≈ **220 lines** (< 400). New exported types: `DeviceStaticKeyStore`, `DeviceStaticKeyPair`, `KeystoreDeviceStaticKeyStore` = **3** (< 5). Edit fan-out: one DI call site, no symbol rename, no consumer cascade — **1** (≤ 10). No new dependency (Keystore + `javax.crypto` are framework; noise-java vendored; DataStore + coroutines present) → no `libs.versions.toml` / `build.gradle.kts` / `settings.gradle.kts` edit. Within every red line; ships as one ticket.

## Security review

**Verdict:** PASS

The asset is the device's long-term static **private key** — the phone's per-server cryptographic identity. Disclosure lets an attacker impersonate the phone in the Noise_IK handshake; silent regeneration breaks the server's ability to recognise the device (close `4426`). One MUST FIX was found and fixed inline before this verdict (the key-namespace collision below); the checklist was then re-walked from the top.

**Findings:**

- [Trust boundaries] **MUST FIX — fixed inline.** The original public-mirror key used a `.pub` *suffix* appended after `serverId`. Since `serverId` is attacker-influenceable via the QR pairing payload, `serverId = "foo.pub"` collided server `foo`'s public-mirror slot with `foo.pub`'s private-blob slot, breaking per-server isolation (AC #1) and able to clobber a device identity. Fixed by using two independent fixed prefixes that differ in the character right after `pyrycode.device_static` (`.` for private, `_pub.` for public), making collision impossible for any server-id (§ Design → Persistence / Collision-safety). The unwrap (ciphertext-on-disk → raw key in RAM) is the single, explicit boundary in `KeystoreDeviceStaticKeyStore`.
- [Tokens / secrets] No findings on generation/storage; **SHOULD FIX folded into spec** for logging. Generation uses `SecureRandom()` (not `kotlin.random.Random`); storage is AES-256-GCM ciphertext in app-private DataStore under a non-exportable, uid-scoped Keystore wrap key — never plaintext on disk (AC #3). Added an explicit "no key material in logs or `DeviceStaticKeyException` messages" prohibition (§ Error handling).
- [File / storage] No findings. Storage scope stated as app-private internal (`MODE_PRIVATE`, not external/MediaStore); writes are a single atomic `edit { }` (no partial-keypair state); no filesystem-path concatenation (server-id is a DataStore map-key component, not a path), so no path-traversal/TOCTOU surface.
- [Inter-process / Android surface] No findings — N/A by design. The store is a plain injected singleton; this ticket adds no exported component, Intent filter, deep link, PendingIntent, ContentProvider, or WebView. The Keystore wrap key is OS-uid-scoped, so a co-resident malicious app cannot unwrap the blob even if it reads the file.
- [Cryptographic primitives] No findings. Standard primitives (X25519 via the vendored, reviewed noise-java; AES-256-GCM via the platform `Cipher`); no hand-rolled crypto. **GCM nonce reuse — the one footgun of mechanism (a) — is closed** by leaving `setRandomizedEncryptionRequired(true)`, so the Keystore emits a fresh IV per encrypt; the wrap key serves only this one purpose (no cross-purpose key/nonce reuse). No production secret-comparison path exists, so no constant-time-compare obligation here.
- [Network & I/O] No findings — N/A. This ticket opens no socket; TLS config, frame caps, timeouts, and cert pinning belong to the WS-transport ticket (the key custodied here protects that future traffic).
- [Error messages / logs / telemetry] **SHOULD FIX folded in** (see Tokens). No telemetry/analytics added. Exception messages carry cause-class + failed-operation, never bytes.
- [Concurrency] No findings. A single `Mutex` (one lock — no ordering hazard) makes generate-or-load atomic (AC #2); the atomic `edit { }` plus "cancel before persist shares nothing, so regeneration is safe; cancel after persist returns the same key" closes the cancellation/shutdown window. No `StateFlow` TOCTOU (not a ViewModel).
- [Threat model alignment] Aligned with `protocol-mobile.md` § Security model / ADR 024: a stable, hardware-protected, per-phone identity is exactly what bounds relay-operator-MITM impersonation and lets one phone be revoked without affecting others. Residual (documented, inherent to software Noise): an attacker executing code *as this app's uid* on an unlocked device can unwrap the key — accepted, as noise-java does software DH regardless of mechanism.
- [Backup] **SHOULD-consider, OUT OF SCOPE (names owner).** Auto-backup of `app_prefs` would copy only the ciphertext; Keystore keys are never backed up and do not migrate devices, so a restored backup cannot unwrap (→ our throw-don't-regenerate path → re-pair). Explicitly excluding `app_prefs` from backup (`android:dataExtractionRules` / `fullBackupContent`) is app-wide manifest hardening, deliberately outside this ticket's file set; flag for the manifest/backup-policy ticket.
- [Key rotation / revocation / expiry] **OUT OF SCOPE.** Deferred to a future rotation ticket (`pyry rotate-static-key` is a v3 concern per `protocol-mobile.md`); the "never silently regenerate" rule is the intentional anti-rotation stance for v2.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
