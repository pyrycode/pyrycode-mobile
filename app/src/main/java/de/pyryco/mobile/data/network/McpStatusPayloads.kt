package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.McpServerStatus
import de.pyryco.mobile.data.repository.McpStatusReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `mcp_status` frame (#1343, pyrycode#2375/#2381/#2420): one conversation's MCP server report. One shape serves
 * the live push, the answer to [McpStatusRequestPayloadDto], and the answer to an accepted [McpReconnectPayloadDto]
 * or [McpTogglePayloadDto], so nothing here reads `in_reply_to`. Decode-only. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `mcp_status`. Every key is **strict-required with no Kotlin
 * default**: `servers` is always an array (`[]` is a report of no servers), `dropped_servers` always a number, and
 * each server always carries all five strings, so a frame missing any of them fails the decode.
 *
 * **SECURITY.** Every server string is claude-authored and unsanitized; `error`'s daemon-side byte cap is a size
 * bound, not sanitization. They are held for display only: never logged, never a key, never in an exception
 * message. `conversation_id` is daemon-authored and is the caller's routing key.
 */
@Serializable
internal data class McpStatusPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val servers: List<McpServerDto>,
    @SerialName("dropped_servers") val droppedServers: Int,
)

/** One `servers` row of [McpStatusPayloadDto]. `name` is the server object's name, not `serverInfo.name`. */
@Serializable
internal data class McpServerDto(
    val name: String,
    val status: String,
    val error: String,
    val scope: String,
    val version: String,
)

/**
 * Map a decoded [McpStatusPayloadDto] to a [McpStatusReport], or **null** for a negative
 * [McpStatusPayloadDto.droppedServers], which no report can carry. Otherwise a verbatim copy in claude's order:
 * the dropped count is never recomputed from the retained length.
 */
internal fun McpStatusPayloadDto.toReport(): McpStatusReport? =
    if (droppedServers < 0) {
        null
    } else {
        McpStatusReport(
            servers = servers.map { McpServerStatus(it.name, it.status, it.error, it.scope, it.version) },
            droppedServers = droppedServers,
        )
    }

/**
 * `mcp_status_request` (#1343, pyrycode#2381): ask for one conversation's current MCP status. Answered by a
 * correlated `mcp_status`, or an `error` carrying `protocol.malformed`, `conversation.not_found` or
 * `mcp_status.unavailable`. v2-only and `interactive`-gated. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § "Asking for MCP status on demand".
 */
@Serializable
internal data class McpStatusRequestPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * `mcp_reconnect` (#1343, pyrycode#2419/#2420): ask the conversation's live child to reconnect one server. An
 * accepted reconnect answers with a correlated `mcp_status`; every refusal is the merged `mcp_actuation.refused`.
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § "Actuating MCP servers on demand".
 */
@Serializable
internal data class McpReconnectPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("server_name") val serverName: String,
)

/**
 * `mcp_toggle` (#1343, pyrycode#2419/#2420): ask for one server to be turned on or off. [enabled] has no default, so
 * it is always encoded whatever [MobileJson]'s default-encoding setting. Answered like [McpReconnectPayloadDto].
 */
@Serializable
internal data class McpTogglePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("server_name") val serverName: String,
    val enabled: Boolean,
)
