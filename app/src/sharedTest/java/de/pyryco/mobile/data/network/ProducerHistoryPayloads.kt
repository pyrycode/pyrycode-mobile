package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Independent daemon producer diagnostics, never used to render or certify phone content. */
@Serializable
internal data class RequestHistoryPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val cursor: String,
    val limit: Int,
)

@Serializable
internal data class HistoryPagePayloadDto(
    val entries: List<HistoryEntryDto>,
    val cursor: String,
    @SerialName("at_start") val atStart: Boolean,
)

@Serializable
internal data class HistoryEntryDto(
    @Serializable(with = ReadMarkIdSerializer::class) val id: ULong,
    val type: String,
    val payload: JsonElement,
    val ts: String,
)
