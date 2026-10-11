package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class PeerAttachmentStage {
    PeerOpen,
    InitialChannelList,
    InitialPhoneConnection,
    ChatCreation,
    ChatRename,
    Upload,
    SendAcknowledgement,
    TurnCompletion,
    GraphRestart,
    CacheClearing,
    RestartChannelList,
    PostRestartPhoneConnection,
    ThreadOpening,
    HistoryRetrieval,
    FileOpen,
    FileSave,
    SavedOverlay,
    BeforeCleanup,
}

internal class PeerAttachmentEvidence(
    private val snapshot: () -> Map<String, String>,
    private val sink: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Snapshot/log failures are observational; neither may replace the operation's result. */
    internal fun record(
        stage: PeerAttachmentStage,
        phase: String,
    ) {
        val status = phase.takeIf { it in setOf("started", "completed", "failed", "observed") } ?: "unknown"
        val prefix = "method=interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload stage=${stage.name} phase=$status"
        try {
            val fields =
                snapshot().entries.mapNotNull { (key, value) ->
                    validators[key]?.let { validator -> "$key=${value.takeIf(validator::matches) ?: "unknown"}" }
                }
            sink("$prefix observation=available utc_ms=${clock().coerceAtLeast(0)} ${fields.joinToString(" ")}")
        } catch (_: Throwable) {
            // A single fallback attempt also tolerates a broken logger; no raw error text crosses it.
            runCatching { sink("$prefix observation=unknown utc_ms=unknown") }
        }
    }

    internal inline fun <T> step(
        stage: PeerAttachmentStage,
        block: () -> T,
    ): T {
        record(stage, "started")
        try {
            return block().also { record(stage, "completed") }
        } catch (failure: Throwable) {
            record(stage, "failed")
            throw failure
        }
    }

    private companion object {
        val unknown = "unknown"
        val uuid = Regex("(?:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|$unknown)")
        val hash = Regex("(?:[0-9a-f]{64}|$unknown)")
        val boolean = Regex("(?:true|false|$unknown)")
        val count = Regex("(?:[0-9]{1,9}|$unknown)")
        val link = Regex("(?:Connected|Connecting|Offline|Idle|DaemonAbsent|PairingRejected|UpdateRequired|Reconnecting|$unknown)")
        val validators =
            buildMap {
                listOf("chat_x", "attachment_id").forEach { put(it, uuid) }
                listOf("target_host_sha256", "selected_host_sha256").forEach { put(it, hash) }
                listOf("phone_host", "phone_ready", "phone_selected", "peer_open", "cache_cleared").forEach { put(it, boolean) }
                listOf("phone_relay", "phone_daemon").forEach { put(it, link) }
                listOf(
                    "peer_links",
                    "peer_frames",
                    "peer_sampled",
                    "peer_chat_frames",
                    "peer_stored",
                    "peer_ack",
                    "peer_history",
                    "peer_delivery",
                    "peer_terminal",
                    "peer_modals",
                ).forEach { put(it, count) }
            }
    }
}

internal fun peerAttachmentFrameEvidence(
    all: List<Envelope>,
    conversationId: String?,
    state: String,
): Map<String, String> {
    val frames = all.takeLast(4096)
    val chat =
        frames.filter {
            val id = ((it.payload as? JsonObject)?.get("conversation_id") as? JsonPrimitive)?.contentOrNull
            conversationId != null && id == conversationId
        }
    val links =
        Regex("link ([0-9]{1,9})").find(state)?.groupValues?.get(1)
            ?: if ("none settled yet" in state) "0" else "unknown"

    fun chatCount(type: String) = if (conversationId == null) "unknown" else chat.count { it.type == type }.toString()
    return mapOf(
        "peer_open" to state.startsWith("session open").toString(),
        "peer_links" to links,
        "peer_frames" to all.size.toString(),
        "peer_sampled" to frames.size.toString(),
        "peer_chat_frames" to if (conversationId == null) "unknown" else chat.size.toString(),
        "peer_stored" to frames.count { it.type == "attachment_stored" }.toString(),
        "peer_ack" to frames.count { it.type == "ack" }.toString(),
        "peer_history" to chatCount("history_page"),
        "peer_delivery" to chatCount("send_delivered"),
        "peer_terminal" to chatCount("turn_end"),
        "peer_modals" to chatCount("modal_shown"),
    )
}
