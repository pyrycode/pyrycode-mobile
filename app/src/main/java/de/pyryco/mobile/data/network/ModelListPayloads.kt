package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `model_list` frame payload (#791): the per-conversation menu of models claude will
 * accept, drawn from its `initialize` control reply. **Decode-only** — the phone never sends one; the
 * on-demand `request_model_list` ask is #792. Always decode through [MobileJson]
 * (`MobileJson.decodeFromJsonElement<…>(payload)`), never a default `Json`.
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `model_list`. The frame arrives two ways — once
 * per claude child spawn on the live interactive lane (carrying an `event_id`), and as a
 * per-conversation burst on every (re)connect (carrying none) — and the payload is **identical** on
 * both, so nothing here branches on the delivery path.
 *
 * **All three frame-level fields are required, with no default**, the [SessionSettingsPayloadDto]
 * posture: the wire emits all three unconditionally, so a missing key is a malformed frame rather than
 * a silently-defaulted one. In particular [models] is **always an array and never `null`**, so an empty
 * `[]` decodes into a present menu with zero rows and a consumer never needs a null branch — an
 * explicit `null` is out of contract and fails the frame instead of masquerading as an empty menu.
 *
 * **[conversationId] is the frame's own routing key and the only thing a retention may key on.** The
 * reconcile burst walks the daemon's registry in an order that is not a contract and gives every
 * envelope in the burst the same non-load-bearing envelope id, so correlating by position or by
 * `Envelope.id` would cross-route one conversation's menu onto another.
 *
 * **[droppedModels] is carried verbatim and never recomputed** from `models.size`: it is how many
 * entries the producer cut, so `models.size + droppedModels` is the menu's true size. The producer's
 * entry cap is daemon-side and not a wire constant, so nothing here hardcodes one or derives a cap
 * from the retained row count.
 *
 * The DTOs stay `internal` to `data/network` — only the domain [ModelMenu] crosses the package
 * boundary, the [QueueStatePayloadDto] discipline that keeps an untrusted wire type from escaping its
 * decode boundary. This file carries multiple top-level types, so the ktlint single-class filename
 * rule does not apply (cf. `InteractivePayloads.kt`).
 */
@Serializable
internal data class ModelListPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val models: List<ModelListRowDto>,
    @SerialName("dropped_models") val droppedModels: Int,
)

/**
 * Mobile Protocol v2 `request_model_list` request payload (#792): the phone→binary ask for one
 * conversation's model menu, the third and last way a client gets one and the only one it can trigger
 * itself. **Encode-only** — the daemon answers with a correlated [ModelListPayloadDto], *this same
 * frame unchanged*, so there is no reply type to model here. Always encode through [MobileJson].
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § *Asking for a model list on demand*. **One key,
 * always present** (no `omitempty`), so nothing here is elided by [MobileJson]'s `explicitNulls =
 * false`. Correlation rides [Envelope.inReplyTo], so there is **no request-id key** — the
 * [RequestSessionSettingsPayloadDto] / `RequestHistoryPayloadDto` decision.
 *
 * It exists for the window the frame's two unsolicited paths leave open: the live lane emits once per
 * claude child spawn to whoever is connected at that instant and the reconcile runs at handshake, so a
 * conversation **created after the phone connected crosses neither edge**. A conversation with no
 * session is answered too, from the daemon-wide vocabulary — that is the case this verb exists for, so
 * "no session yet" is not a reason to withhold the ask.
 *
 * **SECURITY.** [conversationId] is a **lookup key the daemon validates against its own registry** —
 * naming a conversation is not authorization. It travels as a payload *value* only: never a path
 * component, a filename, a cache lookup or a log field. The empty string names nothing and is refused
 * daemon-side, so the sender declines to put one on the wire rather than sending a frame it knows will
 * be refused. A conn that did not negotiate `interactive` is answered with **nothing at all** — no
 * menu, no error, not even a signal that the conversation exists — which is why the sender gates on
 * that capability before building this payload and never waits on a reply that cannot come.
 */
@Serializable
internal data class RequestModelListPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * One element of [ModelListPayloadDto.models] (#791) — a single published model row.
 *
 * [resolvedModel], [value] and [displayName] are **required non-null strings**, and [effortLevels] is a
 * **required non-null array** under the same never-`null` contract [ModelListPayloadDto.models] carries:
 * `[]` is the positive statement that this model exposes no effort control, which is the one position
 * the wire states for both claude's *absent* and *empty* list. A missing or wrong-typed member here
 * fails the **whole** frame — there is no partial menu, the all-or-nothing posture [toSessionSettings]
 * takes for its own reply.
 *
 * **Two fields carry a default, and each default is a *read* rather than a manufactured value.**
 *
 *  - [supportsAutoMode] absent means `false` by the wire's own contract ("Absent in claude's reply
 *    decodes to `false`, which is the correct reading"), so defaulting it states what the wire states.
 *    This is the opposite of `session_settings`' `permission_mode`, where a default would have invented
 *    a confirmation posture — hence that DTO's no-defaults rule and this one's exception to it.
 *  - [truncatedFields] is the frame's one **nullable** array: `null` means nothing was cut. Omitted and
 *    explicit-`null` mean the same thing here, so [MobileJson]'s `explicitNulls = false` collapsing the
 *    two is correct — the very collapse `effective_effort` had to *avoid* (its three states mean three
 *    different things) and [WorkspaceUpdatedPayloadDto] deliberately *wants*. An out-of-contract `[]`
 *    decodes to an empty list rather than being punned to `null`, so what arrived is what is retained.
 *
 * **SECURITY.** Every string on this row is claude-authored text that crossed the subprocess trust
 * boundary; the daemon bounds it but strips no control character and no terminal escape. Nothing here
 * trims, folds, normalises, re-encodes or validates one — see [ModelMenuRow], which carries the
 * obligation to the consumer that renders it.
 */
@Serializable
internal data class ModelListRowDto(
    @SerialName("resolved_model") val resolvedModel: String,
    val value: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("effort_levels") val effortLevels: List<String>,
    @SerialName("supports_auto_mode") val supportsAutoMode: Boolean = false,
    @SerialName("truncated_fields") val truncatedFields: List<String>? = null,
)

/**
 * Map a decoded `model_list` payload to its domain [ModelMenu] (#791).
 *
 * **Total and non-throwing**: a pure field copy with no validation, no normalisation and no fallback,
 * so the single validate boundary is the [MobileJson] decode that produced the receiver and this
 * mapper can never be the thing that fails. That is [toSessionSettings]'s discipline — all structural
 * rejection in one place, nothing partial afterwards — expressed in the [QueueStatePayloadDto.toQueue]
 * shape, because this frame carries its own routing id and the caller pairs it with the result.
 *
 * It authors **no message at all**, so no payload content can escape through an exception text; the
 * only throwables on this path are kotlinx-serialization's, which the caller catches and discards.
 * [ModelListPayloadDto.droppedModels] is copied across rather than derived from `models.size`.
 */
internal fun ModelListPayloadDto.toMenu(): ModelMenu =
    ModelMenu(
        rows =
            models.map { row ->
                ModelMenuRow(
                    resolvedModel = row.resolvedModel,
                    value = row.value,
                    displayName = row.displayName,
                    effortLevels = row.effortLevels,
                    supportsAutoMode = row.supportsAutoMode,
                    truncatedFields = row.truncatedFields,
                )
            },
        droppedModels = droppedModels,
    )
