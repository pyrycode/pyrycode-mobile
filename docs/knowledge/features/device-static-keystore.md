# Device static keystore — per-server Noise `s` keypair custody

The phone's **long-term cryptographic identity** in Mobile Protocol v2. As the `Noise_IK` **Initiator**, the phone holds a static (`s`) keypair per paired server; this is the seam that generates it once, persists it under hardware-backed protection, and hands the consumer the raw bytes the software DH needs — without ever writing the private key to disk in plaintext.

Package: `de.pyryco.mobile.data.crypto` (`app/src/main/java/de/pyryco/mobile/data/crypto/`). Two files — `DeviceStaticKeyStore.kt` (the portable contract) and `KeystoreDeviceStaticKeyStore.kt` (the Android-bound impl). Landed in [#291](../codebase/291.md); reuses the existing `app_prefs` DataStore — **no new file, no new dependency**. The mechanism choice is [ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md).

> **Greenfield.** Nothing references this yet. The consumer is [#275](https://github.com/pyrycode/pyrycode-mobile/issues/275) (the Noise_IK session), which calls `loadOrCreate(serverId)` and feeds the raw private bytes to `Curve25519DHState.setPrivateKey` as the initiator's local static key.

## The contract

```kotlin
interface DeviceStaticKeyStore {
    /** Generate-or-load the per-server device static keypair. Idempotent: never
     *  regenerates once persisted; survives process death. Throws
     *  DeviceStaticKeyException on Keystore/crypto failure — caller treats as
     *  "needs re-pair" and must NOT regenerate (a fresh key changes the identity). */
    suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair

    /** 32-byte device public key for serverId, from the plaintext mirror only
     *  (no Keystore round-trip). null if no keypair has been generated yet. */
    suspend fun publicKey(serverId: String): ByteArray?
}

/** Raw 32-byte X25519 (Curve25519) scalars. Plain class — reference equality;
 *  compare the byte arrays directly (assertArrayEquals), never the holder. */
class DeviceStaticKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

class DeviceStaticKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

The interface has **zero `android.*` imports** — it is the portable seam. The Keystore-bound impl (`KeystoreDeviceStaticKeyStore`) carries all the platform code, behind it. This is the CMP-walk-back-safe shape for a `data/` key custodian: on a Compose Multiplatform move the impl becomes an `androidMain` actual, the interface stays in `commonMain`. (It does **not** trip the `data/` no-`android.*` rule — that trigger is a `Context` constructor param, and the constructor takes only `DataStore<Preferences>`. See [[data-layer-android-import-exception]].)

## How custody works (mechanism (a): wrap-at-rest)

noise-java does the X25519 DH **in software on raw private bytes**, so a non-exportable Keystore key can't both stay in-Keystore *and* be DH-able. The resolution ([ADR 0006](../decisions/0006-keystore-wrap-at-rest-device-static-key.md)): keep the raw scalar as **AES-256-GCM ciphertext at rest**, decrypt it into RAM only transiently while a handshake runs.

**Wrap key (Keystore-resident, shared):** a single AES-256-GCM key, alias `pyrycode.device_static_wrap`, get-or-created in `AndroidKeyStore` (`PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, GCM/NoPadding, 256-bit). Non-exportable, uid-scoped, TEE-backed on API-33. No user-auth requirement (headless reconnect must work), no forced StrongBox (avoids `StrongBoxUnavailableException`).

**Persistence (two string prefs per server-id in `app_prefs`):**

| Pref key | Value | Secret? |
|---|---|---|
| `pyrycode.device_static.<serverId>` | `base64(iv ‖ ciphertext)` — the AES-GCM-wrapped private scalar, 12-byte IV prepended | yes (ciphertext) |
| `pyrycode.device_static_pub.<serverId>` | `base64(32-byte public key)` — the fast-read mirror | no |

The two key namespaces **diverge at the fixed char right after `pyrycode.device_static`** (`.` vs `_`), so no attacker-influenced `serverId` (it arrives via the QR pairing payload) can make a private-blob key collide with another server's public-mirror key. **Do not** switch the mirror to a `.pub` suffix appended after `serverId` — `serverId = "foo.pub"` would then clobber server `foo`'s mirror. Both prefs are written in **one `dataStore.edit { }`**, so a half-keypair (pub without priv) is structurally unreachable.

## Data flow

```
loadOrCreate(serverId)            ── withContext(Dispatchers.IO), mutex.withLock ──
  read prefs[priv], prefs[pub]
  ├─ both present → AES-GCM unwrap(priv) ─────────────────► DeviceStaticKeyPair(pub, scalar)
  ├─ both absent  → SecureRandom() 32-byte scalar
  │                 Noise.createDH("25519") → derive pub   (byte-matches #275's DH)
  │                 AES-GCM wrap(scalar)  (Keystore-generated IV)
  │                 edit { prefs[priv]=b64(iv‖ct); prefs[pub]=b64(pub) } ─► DeviceStaticKeyPair(pub, scalar)
  └─ exactly one  → throw DeviceStaticKeyException("corrupt …")

publicKey(serverId)  → read prefs[pub] only → base64-decode → 32 bytes   (no Keystore op)
```

Two invariants worth internalising:

- **Idempotent under concurrency.** A single `Mutex` (one Koin singleton → one lock) wraps check-then-generate, so "never silently regenerates" is a *deterministic* guarantee, not a hope that callers don't race.
- **The public mirror is derived through noise-java**, not Android's `XDH` — `Noise.createDH("25519")` → `setPrivateKey(scalar,0)` → `getPublicKey(…)`. This guarantees the stored mirror is byte-identical to what #275's `Curve25519DHState` will produce from the same private bytes, eliminating any cross-implementation X25519 assumption.

## Failure model — never silently regenerate

Every Keystore/crypto failure surfaces as one typed `DeviceStaticKeyException` (mirroring how the Keystore APIs themselves signal — by throwing). #275 owns how it reaches the UI.

The load-bearing rule: a **decrypt failure** (tampered/corrupt blob, wrap key gone, credential reset, restore-to-new-device) **throws — it does NOT regenerate.** A fresh keypair would change the device identity, the server would reject the handshake (close `4426`), and idempotency would be silently violated. The caller treats the throw as "needs re-pair" (a future ticket). This invariant is *negatively* tested (`keystoreLoss_throwsAndDoesNotRegenerate`).

**No key material in logs or exception messages.** The private scalar, the unwrapped buffer, and the `iv ‖ ct` blob never reach `Log`/`Timber` or a `DeviceStaticKeyException` message. Loggable on these paths: the event, the `serverId`, the public key. Exception messages carry the cause-class + the failed operation, never bytes.

## Wiring & usage

DI (Koin, `AppModule.kt`):

```kotlin
single { KeystoreDeviceStaticKeyStore(get()) } bind DeviceStaticKeyStore::class
```

Consumer (the shape #275 will use):

```kotlin
val kp = deviceStaticKeyStore.loadOrCreate(serverId)   // raw 32-byte X25519
hs.localKeyPair.setPrivateKey(kp.privateKey, 0)        // Curve25519DHState, initiator's local s
// kp.publicKey is byte-identical to what the DH derives from kp.privateKey
```

## Edge cases & limits

- **No JVM unit test.** Real `AndroidKeyStore` needs a device/emulator, so the round-trip lives in `androidTest/` (`KeystoreDeviceStaticKeyStoreTest`). `./gradlew check` compiles it; `connectedAndroidTest` on a device proves persistence. A fresh store instance over the same DataStore is the automatable proxy for an app restart (real process death is survived because the DataStore file and the Keystore alias both outlive the process).
- **Transient RAM exposure.** The raw scalar is in process memory while a keypair is loaded — inherent to software Noise, the security-reviewed tradeoff (see ADR 0006). Bounded by the uid-scoped wrap key.
- **Backup.** Android auto-backup of `app_prefs` would copy only ciphertext; Keystore keys never migrate, so a restored backup can't unwrap → re-pair path. Excluding `app_prefs` from backup is app-wide manifest hardening, deferred to a backup-policy ticket.
- **Rotation / revocation.** Deferred (v3, `pyry rotate-static-key`); "never silently regenerate" is the intentional anti-rotation stance for v2.

## Related

- Ticket notes: [`../codebase/291.md`](../codebase/291.md)
- Decision: [ADR 0006 — Keystore wrap-at-rest for the device static key](../decisions/0006-keystore-wrap-at-rest-device-static-key.md), [ADR 0004 — vendor `noise-java`](../decisions/0004-vendor-noise-java-crypto.md)
- Sibling Phase 4 wire layer: [Mobile Protocol v2 — wire layer](mobile-protocol-v2-wire-layer.md) ([#273](../codebase/273.md))
- Consumer: [#275](https://github.com/pyrycode/pyrycode-mobile/issues/275) (Noise_IK session)
- Spike: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/`).
