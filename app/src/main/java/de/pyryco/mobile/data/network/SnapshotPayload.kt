package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `request_snapshot` control payload (#374): the phone→daemon request for a
 * one-shot text picture of the current claude screen. **Encode-only** — the phone sends it; the
 * daemon replies with a `screen_snapshot` event ([ScreenSnapshotPayloadDto]). Always encode through
 * [MobileJson] (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol` `RequestSnapshot` struct (pyrycode#617, merged). The single
 * required field's snake_case wire name `conversation_id` is carried by [SerialName] — the
 * Go-interop contract, not cosmetic.
 *
 * Part of pyrycode#596 (ADR 025 § Safe degradation): the screen snapshot is the always-available,
 * parser-independent floor of the degrade strategy — it depends on no screen parser, so it survives
 * any parser break.
 */
@Serializable
data class RequestSnapshotPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * Mobile Protocol v2 `screen_snapshot` event payload (#374): the daemon→phone one-shot text picture
 * of the current claude screen, rendered via tui-driver inside the substrate seal. **Decode-only** —
 * the phone never sends a `screen_snapshot`. Always decode through [MobileJson]
 * (`MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(payload)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol` `ScreenSnapshot` struct (pyrycode#617, merged). Field
 * declaration order matches the Go struct: `conversation_id`, `text`, `ts`. All three are required,
 * non-null `String`s — the strict-decode posture for this untrusted→typed boundary means a malformed
 * frame fails closed with a [kotlinx.serialization.SerializationException] rather than a partial /
 * `null`-punned value.
 *
 *  - [text] is the **rendered text** of the screen — plain rendered text only, never raw control
 *    codes / raw bytes (ADR 025 no-raw-bytes invariant, enforced server-side by the daemon's
 *    tui-driver renderer). This DTO models it verbatim and must **not** mutate, strip, or sanitize
 *    it — decode fidelity is the entire purpose of the parser-independent snapshot floor.
 *  - [ts] is the raw wire timestamp held as a plain `String`. It is **not** parsed to `Instant` here:
 *    the consumer (#375) returns [text] only and never reads [ts]; modeling it strict keeps the
 *    untrusted-boundary decode honest without adding a parse this slice does not need.
 */
@Serializable
data class ScreenSnapshotPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val text: String,
    val ts: String,
)
