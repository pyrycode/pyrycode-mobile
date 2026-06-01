package de.pyryco.mobile.data.crypto

import com.southernstorm.noise.crypto.Blake2sMessageDigest

private const val STATIC_KEY_SIZE = 32
private const val FINGERPRINT_BYTES = 8

private val COLON_HEX = HexFormat { bytes.byteSeparator = ":" }

/**
 * Derive the human-comparable static-key fingerprint the user reads against the desktop's
 * `Static-key fp:` line to catch a wrong-server / MITM during QR trust-on-first-use pairing.
 *
 * Byte-for-byte parity with pyrycode#432's `Fingerprint([32]byte) string` is the entire contract:
 * the full **256-bit** BLAKE2s digest of the key, truncated to its first **8 bytes** (64-bit —
 * load-bearing security, not a tuning knob; a 32-bit fingerprint is brute-forceable), rendered as
 * colon-separated lowercase hex (exactly 23 chars, `^[0-9a-f]{2}(:[0-9a-f]{2}){7}$`). A divergence
 * of one byte makes the human comparison meaningless and silently defeats the check.
 *
 * Computes the full digest then slices — NOT a BLAKE2s configured to emit 8 bytes ("BLAKE2s-64"),
 * which folds the output length into its init block and yields a different digest. The vendored
 * [Blake2sMessageDigest] is fixed-256-bit-output by construction, so that trap cannot bite here;
 * it is constructed directly rather than via `MessageDigest.getInstance("BLAKE2S-256")`, which
 * needs a JCA provider Android does not ship.
 *
 * Pure, synchronous, no I/O, no logging. The input is a public key, so neither input nor output is
 * secret. [staticKey] must be the raw 32-byte X25519 static public key (the base64-std decode is the
 * caller's concern — kept in `data/network`, not pulled into `data/crypto`); a wrong length is a
 * programmer error and fails loud rather than producing a meaningless fingerprint.
 *
 * @throws IllegalArgumentException if [staticKey] is not exactly 32 bytes.
 */
fun staticKeyFingerprint(staticKey: ByteArray): String {
    require(staticKey.size == STATIC_KEY_SIZE) { "static key must be 32 bytes" }

    val digest = Blake2sMessageDigest().digest(staticKey)
    return digest.toHexString(0, FINGERPRINT_BYTES, COLON_HEX)
}
