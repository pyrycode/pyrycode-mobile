package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `slash_command_list` frame payload (#882): the per-conversation menu of slash commands
 * claude will accept, drawn from its `initialize` control reply. **Decode-only** — the phone never sends
 * one, and the frame declares no inbound verb. Always decode through [MobileJson].
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `slash_command_list`. It arrives on the live
 * interactive lane (with an `event_id`) and as a per-conversation connect-time snapshot (with none); the
 * payload is identical on both, so nothing here branches on the delivery path.
 *
 * **All three frame-level fields are required, with no default**, the [ModelListPayloadDto] posture: a
 * missing key is a malformed frame. [commands] is always an array and never `null`, so `[]` decodes into a
 * present menu with zero rows, and an explicit `null` fails the frame rather than posing as an empty menu.
 *
 * [conversationId] is the frame's own routing key and the only thing a retention may key on: every
 * envelope in the connect-time burst carries the same envelope id, and the burst order is not a contract.
 *
 * [droppedCommands] is carried verbatim and never recomputed from `commands.size`.
 *
 * The DTOs stay `internal` to `data/network`; only the domain [SlashCommandMenu] crosses the package
 * boundary. This file carries multiple top-level types, so the ktlint single-class filename rule does not
 * apply (cf. `ModelListPayloads.kt`).
 */
@Serializable
internal data class SlashCommandListPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val commands: List<SlashCommandListRowDto>,
    @SerialName("dropped_commands") val droppedCommands: Int,
)

/**
 * One element of [SlashCommandListPayloadDto.commands] (#882). [name], [argumentHint], [description] and
 * [aliases] are **required and non-null**; a missing or wrong-typed one fails the whole frame, so there is
 * no partial menu. [truncatedFields] is the one nullable array: omitted and explicit `null` both mean
 * nothing was cut, so [MobileJson]'s `explicitNulls = false` collapsing them is correct.
 *
 * **SECURITY.** Every string here is workspace-authored and unsanitized — see [SlashCommandMenuRow], which
 * carries the obligation to the consumer. Nothing here trims, folds, validates or re-encodes one.
 */
@Serializable
internal data class SlashCommandListRowDto(
    val name: String,
    @SerialName("argument_hint") val argumentHint: String,
    val description: String,
    val aliases: List<String>,
    @SerialName("truncated_fields") val truncatedFields: List<String>? = null,
)

/**
 * Map a decoded `slash_command_list` payload to its domain [SlashCommandMenu] (#882). **Total and
 * non-throwing**: a pure field copy, so the [MobileJson] decode that produced the receiver is the single
 * validate boundary. It authors no message, so no payload content can escape through an exception text.
 */
internal fun SlashCommandListPayloadDto.toMenu(): SlashCommandMenu =
    SlashCommandMenu(
        rows =
            commands.map { row ->
                SlashCommandMenuRow(
                    name = row.name,
                    argumentHint = row.argumentHint,
                    description = row.description,
                    aliases = row.aliases,
                    truncatedFields = row.truncatedFields,
                )
            },
        droppedCommands = droppedCommands,
    )
