package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `modal_answer` control payload (#438): the phone→binary message that answers a
 * surfaced permission/choice modal (#437). **Encode-only** — the phone sends it; the only correlated
 * reply is an empty `ack` on success or an `error` on failure (incl. the ungranted-device reject,
 * pyrycode#702/#703), there is no typed response payload to decode. Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`. The encode mirror of #437's decode
 * DTO [ModalShownPayloadDto].
 *
 * Wire SSOT: server pyrycode#701 (`modal_answer = {modal_id, option_id, answer_token}`);
 * `docs/protocol-mobile.md` § Modal (v2). Three fields, **all required**, in Go-struct order
 * (`modal_id`, `option_id`, `answer_token`). All non-nullable with no defaults — none is optional on
 * the wire.
 *
 *  - [modalId] is the opaque id of the modal being answered (decoded by #437, echoed verbatim). The
 *    daemon validates it against its own current outstanding modal (first-answer-wins; a stale id is
 *    rejected) — this DTO neither parses nor trusts it.
 *  - [optionId] is the opaque id of the selected `options[].id` (decoded by #437, echoed verbatim).
 *    The daemon maps it against its own recorded option list; it never trusts a phone-asserted
 *    conversation.
 *  - [answerToken] is a **client-minted idempotency key** — uniqueness and stability matter, secrecy
 *    does not (pyrycode#701): it lets the daemon collapse a replayed/reordered answer to a no-op. It
 *    is **not** authorization (that is [modalId] validity plus the per-device answer gate
 *    pyrycode#702); a guessed/predictable token grants nothing.
 *
 * Never log the payload or these fields — the modal they answer may name a sensitive command/path
 * (e.g. "Allow `rm -rf build/`"), mirroring [RegisterPushTokenPayloadDto]'s never-log posture.
 */
@Serializable
internal data class ModalAnswerPayloadDto(
    @SerialName("modal_id") val modalId: String,
    @SerialName("option_id") val optionId: String,
    @SerialName("answer_token") val answerToken: String,
)

/**
 * Mobile Protocol v2 `modal_cancel` control payload (#438): the phone→binary message that cancels a
 * surfaced modal (#437). **Encode-only** — the phone sends it; the only correlated reply is an empty
 * `ack` on success or an `error` on failure. Always encode through [MobileJson], never a default
 * `Json`.
 *
 * Wire SSOT: server pyrycode#701 (`modal_cancel = {modal_id}`); `docs/protocol-mobile.md`
 * § Modal (v2). One field, **required**, non-nullable. Unlike [ModalAnswerPayloadDto], cancel carries
 * **no** idempotency token — a re-cancel of an already-resolved modal is a stale-[modalId] reject the
 * daemon handles, so no mobile-side dedup is needed.
 *
 *  - [modalId] is the opaque id of the modal being cancelled (decoded by #437, echoed verbatim). The
 *    daemon validates it against its own outstanding modal — this DTO neither parses nor trusts it.
 *
 * Never log the payload or [modalId] — see [ModalAnswerPayloadDto].
 */
@Serializable
internal data class ModalCancelPayloadDto(
    @SerialName("modal_id") val modalId: String,
)
