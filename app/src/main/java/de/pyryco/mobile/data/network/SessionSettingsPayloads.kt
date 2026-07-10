package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `set_session_settings` request payload (#543): the phone→binary request that
 * applies the operator's model / effort / YOLO selection to a **running session**. **Encode-only** —
 * the phone sends it; the daemon replies with a `session_settings_updated` ack carrying only the
 * target `session_id` ([SessionSettingsUpdatedPayloadDto]). Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/settings.go` `SetSessionSettingsPayload` (#844). [sessionId] is
 * the **target session** (not a conversation id) and is the routing key — always sent.
 *
 * [model] / [effort] / [yolo] carry a **presence contract**: an *omitted* field means "leave
 * unchanged"; a *present* field (including `""` / `false`) means "set to this value". This DTO
 * expresses it via nullable-default-null fields under [MobileJson]'s `explicitNulls = false`: a `null`
 * field is elided from the JSON, a non-null value is always emitted. This mirrors the server struct's
 * pointer + `omitempty` fields exactly — an omitted `yolo` can never masquerade as a sent `false`. The
 * daemon re-validates `model` / `effort` server-side; the phone forwards the caller's strings verbatim
 * (as [RenameConversationPayloadDto] forwards the dialog's name).
 *
 * Encode-only — model only what is sent (the [RenameConversationPayloadDto] / [SendMessagePayloadDto]
 * discipline), not the full decode surface.
 */
@Serializable
data class SetSessionSettingsPayloadDto(
    @SerialName("session_id") val sessionId: String,
    val model: String? = null,
    val effort: String? = null,
    val yolo: Boolean? = null,
)

/**
 * Mobile Protocol v2 `session_settings_updated` reply payload (#543): the daemon's correlated ack that
 * the settings were applied and persisted. **Decode-only** — carries **only** the target `session_id`;
 * it does **not** echo the applied settings (the client already knows what it sent), so the decode's
 * sole purpose is validating the reply shape, ack-style like `send_message`. Request↔reply correlation
 * rides `Envelope.inReplyTo`, not this field.
 *
 * Wire SSOT: server `internal/protocol/settings.go` `SessionSettingsUpdatedPayload` (#844).
 */
@Serializable
data class SessionSettingsUpdatedPayloadDto(
    @SerialName("session_id") val sessionId: String,
)
