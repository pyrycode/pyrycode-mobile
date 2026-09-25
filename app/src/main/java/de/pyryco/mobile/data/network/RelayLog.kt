package de.pyryco.mobile.data.network

import com.southernstorm.noise.crypto.Blake2sMessageDigest
import de.pyryco.mobile.BuildConfig

/** Bytes of the BLAKE2s digest [RelayLog.redactConnId] renders — 4 → an 8-char hex correlation token. */
private const val REDACT_BYTES = 4

/**
 * Debug-gated, redaction-safe diagnostic logger for the relay transport.
 *
 * Exists so the next connection-diagnosis session doesn't repeat the "add raw `Log.i`/`Log.w` →
 * diagnose → remember to strip" churn that nearly shipped host / live `conn_id` / close-cause /
 * pump-state / capability values to production logcat (2026-07-03 cross-repo review, #500). This
 * facility is **safe by construction** on two axes:
 *
 * 1. **Cannot reach release output.** Emission is gated on an explicit [enabled] check that defaults
 *    to [BuildConfig.DEBUG] (a compile-time constant, `false` in release). The gate is a real build
 *    check — it does **not** rely on R8 stripping `Log.d`/`Log.v`, which the near-miss's `Log.i`/
 *    `Log.w` calls defeated. There is no UI toggle and no production setter, so nothing flips it on
 *    in a release build.
 * 2. **Cannot assemble a sensitive value in release.** The message is supplied by a `() -> String`
 *    lambda invoked only inside the `if (enabled)` branch, so in release the string is never built.
 *
 * Adopters in the connection path (#1039): [OkHttpRelayTransport] writes one `event=transport_end`
 * line per connection end and [NoiseSessionPump] one `event=pump_teardown` line per teardown, each with
 * a fixed label, a code and a class name only. [RelayConnectionSupervisor] and
 * `RelayRepositoryCoordinator` stay silent: the transport line already carries the `Down` code the
 * supervisor sees.
 *
 * ### MUST NOT be passed into a message lambda
 *
 * The relay **host / URL**, the **pairing token**, **raw key material** (static / ephemeral keys,
 * handshake transcript), the **full `conn_id`** (use [redactConnId]), or **message payloads / bodies**.
 * This mirrors the existing "a stray `Log.d($secret)` must not leak this" contracts on
 * `PairedServer.toString` and `HelloClientPayload.toString`, promoted here to the logger's own doc so
 * it is visible at every future call site. Statically preventing a caller from interpolating a secret
 * into a free-form lambda is not code-enforceable; code-review of the adoption ticket is the belt to
 * this documented suspenders.
 *
 * ### Safe to log
 *
 * Event type, [redactConnId] token, pump state name, close code, capability *names*.
 */
object RelayLog {
    /** Stable logcat tag (`adb logcat -s RelayLog`); a fixed constant, never derived from any value. */
    private const val TAG = "RelayLog"

    /**
     * The gate. Defaults to the compile-time [BuildConfig.DEBUG] constant (`false` in release, so the
     * emit branch is never taken there). Reassigned **only** by unit tests — in production it is
     * written once at class init and never mutated, so it is effectively final at runtime.
     */
    internal var enabled: Boolean = BuildConfig.DEBUG

    /**
     * Emission seam: `(priority, tag, message) -> Unit`. Defaults to `android.util.Log.println`; unit
     * tests swap in a capturing lambda because the module has no Robolectric and real `android.util.Log`
     * throws on plain JVM. Reassigned **only** by tests (see [enabled] for the publication rationale).
     */
    internal var sink: (Int, String, String) -> Unit =
        { priority, tag, message -> android.util.Log.println(priority, tag, message) }

    /** Log at DEBUG priority. [message] is built only when [enabled]. */
    fun d(message: () -> String) {
        if (enabled) sink(android.util.Log.DEBUG, TAG, message())
    }

    /** Log at INFO priority. [message] is built only when [enabled]. */
    fun i(message: () -> String) {
        if (enabled) sink(android.util.Log.INFO, TAG, message())
    }

    /** Log at WARN priority. [message] is built only when [enabled]. */
    fun w(message: () -> String) {
        if (enabled) sink(android.util.Log.WARN, TAG, message())
    }

    /**
     * Redact a daemon-issued `conn_id` into an 8-char lowercase-hex correlation token: the full
     * 256-bit BLAKE2s digest of its UTF-8 bytes, truncated to the first [REDACT_BYTES] and rendered
     * as hex (`^[0-9a-f]{8}$`). Mirrors the digest-then-truncate idiom of `staticKeyFingerprint`.
     *
     * Deterministic — same `conn_id` → same token — so log lines from one connection correlate within
     * a diagnosis session. It reveals **zero** bytes of the live id: a fixed-width digest of an
     * arbitrary-length string can never equal the input (satisfying "not the full input" for every
     * input, including short or empty ones), and preimage recovery from 32 bits is infeasible.
     *
     * The 4-byte width is a *correlation* parameter (avoid casual collisions across the handful of
     * connections in one debug session), **not** the ≥8-byte MITM static-key fingerprint security
     * parameter — no adversary is forging a `conn_id` token, so it is deliberately not "aligned" to 8.
     */
    fun redactConnId(connId: String): String =
        Blake2sMessageDigest().digest(connId.toByteArray(Charsets.UTF_8)).toHexString(0, REDACT_BYTES)
}
