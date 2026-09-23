package de.pyryco.mobile.ui.conversations.thread

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.network.base64StdDecode
import de.pyryco.mobile.data.network.base64StdEncode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-process Noise peer: the production registry, repositories and routes remain in the test.
 *
 * @param archivedId an extra archived discussion this host holds, or null for none (#715). Opt-in so
 *   the peers the thread and settings route tests build are unchanged; the id is deliberately
 *   caller-supplied, because proving host-local ids means giving two hosts the same one.
 */
internal class NavigationPeer(
    private val serverId: String,
    private val conversationId: String,
    private val key: DeviceStaticKeyPair,
    private val archivedId: String? = null,
) : RelayTransport {
    private var restored = false
    private val frames = Channel<InnerFrameV2>(Channel.UNLIMITED)
    private val links = Channel<TransportEvent>(Channel.UNLIMITED)
    private var pair: CipherStatePair? = null
    val outbound = mutableListOf<Envelope>()
    override val inbound = frames.receiveAsFlow()
    override val events = links.receiveAsFlow()

    override fun connect() {
        links.trySend(TransportEvent.Up)
    }

    override fun close() {
        links.close()
        frames.close()
        pair?.destroy()
        pair = null
    }

    override fun send(frame: InnerFrameV2): Boolean {
        if (frame.type == "noise_init") {
            val handshake = HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.RESPONDER)
            handshake.localKeyPair.setPrivateKey(key.privateKey, 0)
            handshake.start()
            val input = base64StdDecode(frame.data)
            handshake.readMessage(input, 0, input.size, ByteArray(input.size), 0)
            val ack =
                MobileJson
                    .encodeToString(
                        envelope(
                            "hello_ack",
                            """{"protocol_version":"v2","server_id":"$serverId","conn_id":"test","capabilities":["interactive"]}""",
                        ),
                    ).encodeToByteArray()
            val output = ByteArray(ack.size + 96)
            val count = handshake.writeMessage(output, 0, ack, 0, ack.size)
            pair = handshake.split()
            handshake.destroy()
            frames.trySend(InnerFrameV2(type = "noise_resp", data = base64StdEncode(output.copyOf(count))))
        } else {
            val input = base64StdDecode(frame.data)
            val plain = ByteArray(input.size)
            val count = pair!!.receiver.decryptWithAd(null, input, 0, plain, 0, input.size)
            val request = MobileJson.decodeFromString<Envelope>(plain.copyOf(count).decodeToString())
            outbound += request
            val response =
                when (request.type) {
                    "list_conversations" -> envelope("conversations", """{"conversations":[${row()}${archivedRow()}]}""")
                    // Restore is request/reply: the row leaves the archive only on this confirmation,
                    // so a test that never replies proves the row stays put.
                    "unarchive_conversation" -> {
                        restored = true
                        envelope("conversation_updated", archived(false))
                    }
                    "recent_workspaces" -> envelope("recent_workspaces_list", """{"workspaces":[{"path":"/$serverId/recent"}]}""")
                    "create_workspace_folder" -> envelope("workspace_folder_created", """{"path":"/$serverId/created"}""")
                    "change_workspace" ->
                        envelope(
                            "conversation_updated",
                            row(
                                request.payload.jsonObject
                                    .getValue("cwd")
                                    .jsonPrimitive.content,
                            ),
                        )
                    "create_conversation" -> envelope("conversation_created", row())
                    else -> null
                }
            response?.let { emit(it.copy(inReplyTo = request.id)) }
        }
        return true
    }

    /** Named per host, so which host's archive is on screen is readable from the row itself. */
    private fun archived(isArchived: Boolean) =
        """{"id":"$archivedId","name":"$serverId archived","is_promoted":false,"is_archived":$isArchived,""" +
            """"cwd":"/$serverId/original","last_message_ts":"2026-09-01T00:00:00Z","last_used_at":"2026-09-01T00:00:00Z"}"""

    private fun archivedRow() = if (archivedId == null || restored) "" else ",${archived(true)}"

    private fun row(cwd: String = "/$serverId/original") =
        """{"id":"$conversationId","name":"$serverId chat","is_promoted":true,"cwd":"$cwd","last_message_ts":"2026-09-01T00:00:00Z","last_used_at":"2026-09-01T00:00:00Z"}"""

    private fun emit(envelope: Envelope) {
        val plain = MobileJson.encodeToString(envelope).encodeToByteArray()
        val output = ByteArray(plain.size + 16)
        val count = pair!!.sender.encryptWithAd(null, plain, 0, output, 0, plain.size)
        frames.trySend(InnerFrameV2(type = "noise_msg", data = base64StdEncode(output.copyOf(count))))
    }

    private fun envelope(
        type: String,
        payload: String,
    ) = Envelope(id = 1, type = type, ts = "2026-09-01T00:00:00Z", payload = MobileJson.parseToJsonElement(payload))

    companion object {
        fun key(): DeviceStaticKeyPair {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val private = ByteArray(32).also { dh.getPrivateKey(it, 0) }
            val public = ByteArray(32).also { dh.getPublicKey(it, 0) }
            dh.destroy()
            return DeviceStaticKeyPair(public, private)
        }
    }
}
