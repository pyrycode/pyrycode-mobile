# Paired server store — encrypted custody of the reconnect credential record

The phone's **persisted pairing identity** in Mobile Protocol v2. After QR pairing, the phone holds four values it must re-present to reconnect after process death — the relay URL it dials, the bearer token it sends inside the encrypted hello, the server id, and the server's static Noise public key. This store persists that record **encrypted at rest** and recovers it **byte-faithfully**, so reconnect has the *real* server identity rather than a boolean.

Package: `de.pyryco.mobile.data.crypto` (`app/src/main/java/de/pyryco/mobile/data/crypto/`), co-located with the [device static keystore](device-static-keystore.md). Two files — `PairedServerStore.kt` (the portable contract) and `KeystorePairedServerStore.kt` (the Android-bound impl). Landed in [#294](../codebase/294.md); reuses the existing `app_prefs` DataStore — **no new file, no new dependency**. It is a **second consumer of [ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md)** (wrap-at-rest), not a new mechanism.

> **Dormant.** Nothing references this yet. The consumers are [#275](https://github.com/pyrycode/pyrycode-mobile/issues/275) (the Noise_IK session, which reads `serverStaticPublicKey`) and [#276](https://github.com/pyrycode/pyrycode-mobile/issues/276) (the relay WS client, which reads `relayUrl` + `token` + `serverId`). The `pairedServerExists` boolean stays the live source of paired-state truth until the sibling switch-over ticket swaps the start destination and removes the boolean.

## The contract

```kotlin
interface PairedServerStore {
    /** Decrypt + return the persisted paired server, or null if absent OR
     *  undecryptable / corrupt (graceful → re-pair; never throws, never crashes). */
    suspend fun load(): PairedServer?

    /** Encrypt + persist the record, overwriting any existing one. Throws
     *  PairedServerStoreException on a Keystore / IO failure (the pairing did
     *  not persist — the caller surfaces "try again"). */
    suspend fun save(record: PairedServer)
}

@Serializable
data class PairedServer(
    val serverId: String,              // server identifier
    val token: String,                 // hex; bearer secret, travels inside the encrypted hello
    val relayUrl: String,              // dialed verbatim, /v1/client path included
    val serverStaticPublicKey: String, // base64-STD, 32 bytes
) {
    override fun toString(): String = "PairedServer(serverId=$serverId)"  // redacts the bearer token
}

class PairedServerStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

The interface + record have **zero `android.*` imports** (only `kotlinx.serialization.Serializable`, which is multiplatform) — it is the portable seam. The Keystore-bound impl (`KeystorePairedServerStore`) carries all the platform code, behind it. This is the CMP-walk-back-safe shape for a `data/` credential custodian: on a Compose Multiplatform move the impl becomes an `androidMain` actual, the interface stays in `commonMain`. (It does **not** trip the `data/` no-`android.*` rule — that trigger is a `Context` constructor param, and the constructor takes only `DataStore<Preferences>`. See [[data-layer-android-import-exception]].)

`PairedServer` is a **`data class`** (its fields are all `String`, so structural `equals`/`hashCode`/`copy` are correct and useful) whose **only** override is the redacting `toString()` — the compiler-generated one would render the bearer `token` and leak it to Logcat via a stray `Log.d("$record")`. Serialization is unaffected; the wire needs the real token. (Contrast [`DeviceStaticKeyPair`](device-static-keystore.md), a *plain* class precisely because its `ByteArray` fields make a `data class`'s structural equality a trap.)

## How custody works (mechanism (a): wrap-at-rest)

Same envelope as [#291](../codebase/291.md) / [ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md) — the one divergence is **what** gets encrypted: a JSON document, not raw key bytes.

**Wrap key (Keystore-resident, dedicated):** a single AES-256-GCM key, alias **`pyrycode.paired_server_wrap`** — *distinct* from #291's `pyrycode.device_static_wrap` (no cross-purpose key reuse; this is a symmetric AEAD at-rest key, not #291's asymmetric X25519 identity material). Get-or-created in `AndroidKeyStore` (`PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, GCM/NoPadding, 256-bit). Non-exportable, uid-scoped, TEE-backed on API-33. No user-auth requirement (headless reconnect must work), no forced StrongBox (avoids `StrongBoxUnavailableException`).

**Persistence (one string pref in `app_prefs`):**

| Pref key | Value | Secret? |
|---|---|---|
| `pyrycode.paired_server` | `base64(iv ‖ ciphertext)` — the AES-GCM-wrapped JSON document, 12-byte IV prepended | yes (ciphertext) |

**One record, one fixed pref key.** `serverId` lives *inside* the encrypted blob, never as a pref-key or filesystem-path component — so **none** of #291's attacker-influenced-key-collision surface exists, and there is no per-server namespacing to get wrong. The write is a single atomic `dataStore.edit { }` → no partial-record state, and no `Mutex` is needed (DataStore's `edit { }` is already serialised; concurrent `save()`s are last-writer-wins).

**Byte-faithfulness.** All four fields are stored and reloaded as `String`, verbatim. Only the *outer* JSON document is encrypted; the field Strings pass through `kotlinx.serialization` untouched, so the hex token, the base64-STD pubkey (incl. `+`/`/`/`=`), and the relayUrl with its `/v1/client` path survive byte-identical. #275/#276 receive exactly what the server sent — the store does **no** field-shape validation (it is a faithful persistence layer; validation is owned by the future QR-pairing-input ticket).

## Data flow

```
save(record)                              load()
  └─ IO:                                    └─ IO:
     JSON(record) → UTF-8 bytes                read prefs["pyrycode.paired_server"]
     wrap(bytes)  (Keystore IV)                ├─ null → return null            (absent → re-pair)
     edit { prefs[KEY] = b64(iv‖ct) }          └─ present → decode → (size>IV guard) →
       └─ throws PairedServerStoreException                unwrap → UTF-8 → JSON
          on Keystore/IO failure                          ├─ ok    → PairedServer
                                                           └─ fail  → return null (undecryptable → re-pair)
```

`load()` uses a **non-creating** `KeyStore.getKey` lookup, so a read never mints a wrap key — a missing alias short-circuits straight to `null`.

## Failure model — graceful load, loud save

This is the **inverse** of [#291](../codebase/291.md)'s throw-don't-regenerate contract, and the discriminator is **identity-drift risk**:

- **`load()` is graceful — returns `null`, never throws.** Absent record, wrap key gone (reinstall / credential reset / restore-to-new-device), tampered/truncated blob, bad base64, malformed JSON — every one resolves to `null` → "no paired server" → re-pair. This is safe *here* because the record is **re-fetchable from the QR** (no identity-drift risk), unlike #291's device keypair which is unrecoverable if discarded.
- **`save()` is loud — throws `PairedServerStoreException`** on a Keystore/IO failure (the pairing did *not* persist; the caller surfaces "try again").

The graceful catch set on `load()` is the **specific** `{ GeneralSecurityException, IOException, IllegalArgumentException }` — **never `Exception`/`Throwable`**. This is load-bearing: it lets `CancellationException` propagate (structured-concurrency cancellation honoured) and stops genuine bugs being masked as a silent re-pair loop. Because the catch set is deliberately narrow, an explicit **`blob.size <= 12 → null`** guard sits before the IV split, so a truncated blob's `IndexOutOfBoundsException` (which the set does *not* catch) can't crash the read.

**No secret material in logs or exception messages.** The `token`, the decrypted JSON, the plaintext field values, and the wrapped blob never reach `Log`/`Timber` or a `PairedServerStoreException` message (which carries the cause-class + failed operation, never bytes). The redacting `PairedServer.toString()` is the structural guard against an accidental `Log.d(tag, "$record")`.

## Wiring & usage

DI (Koin, `AppModule.kt`), directly beneath the #291 binding:

```kotlin
single { KeystorePairedServerStore(get()) } bind PairedServerStore::class
```

Consumer shape (#275/#276 will use):

```kotlin
val paired = pairedServerStore.load() ?: return /* → re-pair: no stored server */
// #276 (relay WS client): dial paired.relayUrl, send paired.token + paired.serverId
// #275 (Noise_IK session): use paired.serverStaticPublicKey as the responder's static rs
```

## Edge cases & limits

- **No JVM unit test.** Real `AndroidKeyStore` needs a device/emulator, so the round-trip lives in `androidTest/` (`KeystorePairedServerStoreTest`, 7 scenarios incl. an adversarial `+`/`/`/`=` pubkey + `/v1/client` relayUrl with query + long hex token). `./gradlew check` compiles it; `connectedAndroidTest` on a device proves persistence. A fresh store over the same DataStore is the app-restart proxy (the DataStore file and the Keystore alias both outlive the process).
- **No field-shape validation.** The store persists whatever it's given; relayUrl scheme, pubkey length/base64, token format are the future QR-pairing-input ticket's responsibility. Not exploitable while dormant (no consumer reads it).
- **Backup.** Android auto-backup of `app_prefs` would copy only ciphertext; Keystore keys never migrate, so a restored backup can't decrypt → `load()` → `null` → re-pair (more benign than #291, which throws). Excluding `app_prefs` from backup is app-wide manifest hardening, deferred to a backup-policy ticket.
- **Rollback/freshness.** GCM authenticates integrity, not freshness — replacing the at-rest blob with an older captured ciphertext needs uid-level file access, who could already read the current token; dominated by the accepted at-rest residual. Out of scope.
- **Residual (accepted).** Code running *as this app's uid* on an unlocked device can call the Keystore to unwrap the blob — the same accepted residual as #291; bounded by the uid-scoped wrap key (no co-resident app can unwrap it).

## Related

- Ticket notes: [`../codebase/294.md`](../codebase/294.md)
- Decision: [ADR 0006 — Keystore wrap-at-rest](../decisions/0006-keystore-wrap-at-rest-device-static-key.md) (this store is its second consumer)
- Mirrors / precedent: [device static keystore](device-static-keystore.md) ([#291](../codebase/291.md)) — same portability split + wrap-at-rest mechanism, **inverse** load contract (throw-don't-regenerate vs graceful-null)
- Sibling Phase 4 wire layer: [Mobile Protocol v2 — wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md))
- Consumers (dormant): [#275](https://github.com/pyrycode/pyrycode-mobile/issues/275) (Noise_IK session) + [#276](https://github.com/pyrycode/pyrycode-mobile/issues/276) (relay WS client)
- Wire contract: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/`); QR payload `{server, relay, token, server_static_pubkey}` produced server-side by `pyry pair` (pyrycode [#432](https://github.com/pyrycode/pyrycode/issues/432)).
