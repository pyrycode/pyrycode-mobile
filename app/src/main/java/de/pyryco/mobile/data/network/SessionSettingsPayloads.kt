package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

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
    /**
     * #650 (daemon #1687): one of `default` / `acceptEdits` / `plan` / `auto` / `dontAsk`, under the same
     * presence contract. `bypassPermissions` is reachable only as `yolo = true`, and the daemon rejects a
     * frame carrying both fields, so the `init` guard makes one unconstructible.
     */
    @SerialName("permission_mode") val permissionMode: String? = null,
) {
    init {
        require(yolo == null || permissionMode == null) { "set_session_settings carries yolo or permission_mode, never both" }
    }
}

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

/**
 * Mobile Protocol v2 `request_session_settings` request payload (#590): the phone→binary ask for the run
 * configuration of the conversation it names. **Encode-only** — the daemon answers with a correlated
 * `session_settings` reply ([SessionSettingsPayloadDto]). Always encode through [MobileJson].
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `request_session_settings`. **One key, always
 * present**, so nothing here is elided by [MobileJson]'s `explicitNulls = false`. Correlation rides
 * [Envelope.inReplyTo], so there is no request-id key — the [RequestHistoryPayloadDto] decision.
 *
 * [conversationId] **selects which session the reply describes**; it is not a populated/all-zero switch
 * over a daemon-wide answer. It is never an error frame: an empty id, a conversation this daemon does
 * not host, and one bound to no session are all answered with the same all-zero reply, which is what
 * keeps this verb from being a conversation-membership probe. The daemon leaves a conn that did not
 * negotiate `interactive` fully inert — no reply at all — so the sender gates on that capability first.
 *
 * Encode-only — model only what is sent (the [RenameConversationPayloadDto] / [SetSessionSettingsPayloadDto]
 * discipline), not the full decode surface.
 */
@Serializable
data class RequestSessionSettingsPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * Mobile Protocol v2 `session_settings` reply payload (#590): the daemon's correlated answer describing
 * the session bound to the conversation the request named. **Decode-only** — the structural half of the
 * boundary that turns the untrusted [Envelope.payload] of a `session_settings` envelope into a domain
 * [SessionSettings] via [toSessionSettings].
 *
 * **Every original field here is required, with no default**. The wire emits all seven originals
 * unconditionally (no omission tag), so an absent key is a malformed reply rather than a silently-defaulted one — and each
 * zero is a *read* answer rather than a manufactured one. That matters most for [permissionMode], whose
 * `""` means "no current-child confirmation is available" and must never be punned into the write half's
 * `"default"`; a defaulted field would report Manual approval for a session whose posture is unknown.
 * [HistoryPagePayloadDto] takes the same no-defaults posture for the same reason.
 *
 * **It carries no `effective_effort` field, deliberately.** That key has three states a consumer must
 * keep apart — omitted, explicit `null`, a string — which no single
 * Kotlin field can express under [MobileJson]'s `explicitNulls = false`: a `String?` decodes an omitted
 * key and an explicit `null` identically. It is read by presence off the same [JsonObject] in
 * [toSessionSettings] instead; see [readEffectiveEffort].
 *
 * [model] / [effort] are **arbitrary daemon strings**, and `""` is a real value ("no override, inherited
 * default") rather than an absence. Never map either through `Model` or `Effort`.
 *
 * [usedTokens] / [windowTokens] are [Long] because the wire's `int` is 64-bit Go-side (the pyrycode#720
 * width trap). `window_tokens: 0` means the usage reader is unwired — never render a percentage from it.
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `session_settings`.
 */
@Serializable
data class SessionSettingsPayloadDto(
    @SerialName("session_id") val sessionId: String,
    val model: String,
    val effort: String,
    val yolo: Boolean,
    @SerialName("permission_mode") val permissionMode: String,
    @SerialName("used_tokens") val usedTokens: Long,
    @SerialName("window_tokens") val windowTokens: Long,
    /**
     * #1111: the optional capabilities object. The wire omits it for a conn without `multi_agent` and for a
     * reply that resolved no session, so absence decodes as "no list" rather than failing the frame.
     */
    val capabilities: SessionCapabilitiesDto? = null,
    /** Optional raw report: malformed additions must not discard the original session settings. */
    @SerialName("memory_search") val memorySearch: JsonElement? = null,
)

@Serializable
internal data class MemorySearchReportDto(
    val availability: String,
    val providers: List<MemorySearchProviderDto>,
)

@Serializable
internal data class MemorySearchProviderDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val installed: Boolean,
    val enabled: Boolean,
    val availability: String,
)

/**
 * The `session_settings` reply's `capabilities` object (#1111, pyrycode #2646), declaring only the keys this
 * client reads; [MobileJson] ignores the rest. Both arrays are required because the daemon always sends
 * them, so an object missing one fails the frame. [slashCommands] and [mcpServers] default to `true` so an
 * object from a daemon without pyrycode #2670 still decodes and disables nothing.
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `capabilities` (multi_agent, #2646).
 */
@Serializable
data class SessionCapabilitiesDto(
    @SerialName("effort_levels") val effortLevels: List<String>,
    @SerialName("permission_modes") val permissionModes: List<String>,
    @SerialName("slash_commands") val slashCommands: Boolean = true,
    @SerialName("mcp_servers") val mcpServers: Boolean = true,
    /** Raw token avoids the serializer accepting a quoted boolean; only literal true enables it. */
    @SerialName("mid_turn_input") val midTurnInput: JsonElement? = null,
)

/**
 * Map a raw `session_settings` payload to its domain [SessionSettings] (#590) — the **single validate
 * boundary** for this reply, and a two-step decode rather than [HistoryPagePayloadDto]'s one-step
 * [toHistoryPage] because one field's wire shape must not be flattened.
 *
 * Step order is load-bearing:
 *
 * 1. [SessionSettingsPayloadDto] decodes the seven original fields. A payload that is not an object, is
 *    missing a key, or carries a wrong-typed one fails **here**, before any presence read runs — so the
 *    `jsonObject` cast below can never be the thing that throws.
 * 2. [readEffectiveEffort] reads the optional eighth key **by presence** off the same object, which is
 *    the only way to keep an omitted key distinct from an explicit `null` under `explicitNulls = false`.
 *
 * Extension on [JsonElement] rather than on the DTO precisely because step 2 needs the raw object the
 * DTO no longer carries — the [HistoryEntryDto] idea (keep the raw element where decoding must not
 * flatten the wire) applied to one field instead of a whole entry.
 *
 * Required settings still decode atomically. The optional memory-search report degrades to unknown on
 * malformed or future values, so the other settings survive. Emits no log or payload content.
 */
fun JsonElement.toSessionSettings(): SessionSettings {
    val dto = MobileJson.decodeFromJsonElement<SessionSettingsPayloadDto>(this)
    return SessionSettings(
        sessionId = dto.sessionId,
        model = dto.model,
        effort = dto.effort,
        effectiveEffort = jsonObject.readEffectiveEffort(),
        permissionMode = dto.permissionMode,
        yolo = dto.yolo,
        usedTokens = dto.usedTokens,
        windowTokens = dto.windowTokens,
        capabilities =
            dto.capabilities?.let {
                SessionCapabilities(
                    it.effortLevels,
                    it.permissionModes,
                    it.slashCommands,
                    it.mcpServers,
                    it.midTurnInput == JsonPrimitive(true),
                )
            },
        memorySearch = dto.memorySearch.readMemorySearch(),
    )
}

/** A malformed optional report is an unknown reading, never a failed settings reply. */
private fun JsonElement?.readMemorySearch(): MemorySearchReport {
    if (this == null || this is JsonNull) return MemorySearchReport.Unknown
    val report =
        try {
            MobileJson.decodeFromJsonElement<MemorySearchReportDto>(this)
        } catch (_: SerializationException) {
            return MemorySearchReport.Unknown
        }
    val availability = report.availability.memorySearchAvailability() ?: return MemorySearchReport.Unknown
    val providers =
        report.providers.map { provider ->
            val providerAvailability = provider.availability.memorySearchAvailability() ?: return MemorySearchReport.Unknown
            if (providerAvailability == MemorySearchAvailability.Absent) return MemorySearchReport.Unknown
            MemorySearchProvider(
                id = provider.id,
                displayName = provider.displayName,
                installed = provider.installed,
                enabled = provider.enabled,
                availability = providerAvailability,
            )
        }
    if (availability == MemorySearchAvailability.Absent && providers.isNotEmpty()) return MemorySearchReport.Unknown
    return MemorySearchReport(availability, providers)
}

private fun String.memorySearchAvailability(): MemorySearchAvailability? =
    when (this) {
        "available" -> MemorySearchAvailability.Available
        "unavailable" -> MemorySearchAvailability.Unavailable
        "absent" -> MemorySearchAvailability.Absent
        "unknown" -> MemorySearchAvailability.Unknown
        else -> null
    }

/**
 * Read `effective_effort`'s three wire states off a decoded `session_settings` object (#590): an
 * **absent** key is [EffectiveEffort.Unavailable] (the producer cannot report an applied value, which is
 * also every older daemon — so an absent key must decode rather than fail), an explicit **`null`** is
 * [EffectiveEffort.NotReported] (Claude reported no effort parameter), and a **string** is
 * [EffectiveEffort.Applied] verbatim, `""` and unrecognised levels included.
 *
 * [JsonNull] is itself a [JsonPrimitive], so the null branch runs first; a non-string primitive (number,
 * boolean) and a composite (object, array) both fail the frame rather than degrading to one of the three
 * states — a present-but-wrong value is a protocol violation, not a missing reading.
 *
 * The thrown message names **the key and nothing else**: no value, no type fragment, no neighbouring
 * field. An exception message is the one place a payload could escape into a crash report, and AC #2
 * pins that this failure carries no payload content.
 */
private fun JsonObject.readEffectiveEffort(): EffectiveEffort {
    val raw = this[KEY_EFFECTIVE_EFFORT] ?: return EffectiveEffort.Unavailable
    if (raw is JsonNull) return EffectiveEffort.NotReported
    val primitive = raw as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw SerializationException(EFFECTIVE_EFFORT_NOT_A_STRING)
    return EffectiveEffort.Applied(primitive.content)
}

private const val KEY_EFFECTIVE_EFFORT = "effective_effort"

/** Static — never interpolate the offending value; see [readEffectiveEffort]. */
private const val EFFECTIVE_EFFORT_NOT_A_STRING = "session_settings: effective_effort must be a string or null"
