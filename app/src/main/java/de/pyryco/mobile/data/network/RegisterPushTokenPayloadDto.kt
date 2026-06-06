package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `register_push_token` request payload (#359): the phone→binary request that
 * tells the paired daemon where to send a wake notification when the phone is backgrounded.
 * **Encode-only** — the phone sends it; the only correlated reply is an empty `ack` on success or an
 * `error` on failure (there is no typed response payload to decode). Always encode through
 * [MobileJson] (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/push.go` `RegisterPushTokenPayload` (spec #275); golden
 * `internal/protocol/testdata/register_push_token.json`; `docs/protocol-mobile.md`
 * § `register_push_token`. Three fields, **all required**, in Go-struct order (`platform`, `token`,
 * `device_name`). All non-nullable with no defaults — none is optional on the wire.
 *
 *  - [platform] is the push platform. It is carried as a plain `String` (not an enum): the value is
 *    the constant `"fcm"` (Android) supplied at the call site, and an encode-only DTO models only
 *    what it sends. (The server also accepts `"apns"` for iOS; out of scope here.) This mirrors the
 *    server, which keeps `Platform` a `string` and validates at the dispatcher.
 *  - [token] is the opaque FCM registration token — a sensitive routing credential. It is held only
 *    transiently for one round-trip; it is never logged (the #346 no-secrets posture).
 *  - [deviceName] equals `NoiseClientInfo.deviceName` (the same value the `hello` payload sends); the
 *    server dedupes on the `(platform, token, device_name)` triple.
 */
@Serializable
data class RegisterPushTokenPayloadDto(
    val platform: String,
    val token: String,
    @SerialName("device_name") val deviceName: String,
)
