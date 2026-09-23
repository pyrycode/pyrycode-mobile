package de.pyryco.mobile.e2e

import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.OkHttpRelayTransport
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A second paired device on the live harness (#848) — the bounded stand-in for the desktop app or any
 * other client of the same daemon. It pairs with its own `pyry pair` token (`peerToken`, minted by
 * `scripts/e2e-emulator.sh`) and its own throwaway device key, never the phone's, and speaks the mobile
 * wire protocol over the same relay the phone dials, through the app's own [OkHttpRelayTransport],
 * [NoiseSessionFactory] and [NoiseSessionPump].
 *
 * It never touches the app under test: its pairing record and key live in memory only, so the phone's
 * credential store, host list and registry selection are exactly what they were. One peer per scenario,
 * [open]ed once and [close]d in the scenario's `finally`.
 *
 * Every frame the daemon sends it is recorded, so [awaitFrame] also finds a frame that arrived before
 * the wait began. Nothing here logs; failures name a category or an error `code`, never the token, the
 * key or any payload text.
 */
class SecondClientPeer(
    pairing: PairedServer,
) : AutoCloseable {
    private val transport =
        OkHttpRelayTransport(pairing, CLIENT_INFO, OkHttpRelayTransport.defaultClient())
    private val pump =
        NoiseSessionPump(
            transport,
            NoiseSessionFactory(ThrowawayDeviceKeyStore(), SinglePairingStore(pairing), CLIENT_INFO),
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = MutableStateFlow<List<Envelope>>(emptyList())
    private val requestId = AtomicLong()
    private val closed = AtomicBoolean(false)

    /** Dial the relay, complete the Noise handshake and start recording frames. */
    suspend fun open(timeoutMs: Long) {
        withTimeout(timeoutMs) {
            // The events channel buffers, so Up is waiting here even if it fired before this read; the
            // peer is its only reader. The pump expects a transport that is already up.
            transport.connect()
            val event = transport.events.first()
            check(event is TransportEvent.Up) { "peer relay link down: code ${(event as? TransportEvent.Down)?.code}" }
            pump.start()
            val state = pump.state.first { it !is PumpState.Handshaking }
            check(state is PumpState.Open) {
                "peer session closed during handshake: ${(state as? PumpState.Closed)?.cause?.message ?: "transport down"}"
            }
        }
        scope.launch { pump.inbound.collect { envelope -> received.update { it + envelope } } }
    }

    /** Post [text] to [conversationId] as this device, and wait for the daemon's `ack`. */
    suspend fun sendMessage(
        conversationId: String,
        text: String,
        timeoutMs: Long,
    ) {
        val id = requestId.incrementAndGet()
        val request =
            Envelope(
                id = id,
                type = "send_message",
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        SendMessagePayloadDto(conversationId = conversationId, messageId = UUID.randomUUID().toString(), text = text),
                    ),
            )
        check(pump.send(request)) { "peer session is not open" }
        val reply =
            withTimeout(timeoutMs) {
                received.first { frames -> frames.any { it.inReplyTo == id } }.first { it.inReplyTo == id }
            }
        check(reply.type == "ack") { "peer send_message refused: ${reply.payloadField("code") ?: reply.type}" }
    }

    /** The first recorded frame of [type] that names [conversationId], waiting up to [timeoutMs] for it. */
    suspend fun awaitFrame(
        conversationId: String,
        type: String,
        timeoutMs: Long,
    ): Envelope =
        withTimeout(timeoutMs) {
            received
                .first { frames -> frames.any { it.isFor(conversationId, type) } }
                .first { it.isFor(conversationId, type) }
        }

    /** Tear down the session (wiping its keys), the socket and the recorder. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pump.close()
        scope.cancel()
    }

    private fun Envelope.isFor(
        conversationId: String,
        type: String,
    ): Boolean = this.type == type && payloadField("conversation_id") == conversationId

    private fun Envelope.payloadField(name: String): String? = (payload as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull

    /**
     * The peer's own device key, generated once in memory. The daemon learns a device's static key from
     * each handshake and keeps only the token, so any fresh key pairs with a fresh token. Each read is a
     * copy: [NoiseSessionFactory] zeroes the private key it is handed.
     */
    private class ThrowawayDeviceKeyStore : DeviceStaticKeyStore {
        private val publicKey = ByteArray(KEY_LENGTH)
        private val privateKey = ByteArray(KEY_LENGTH)

        init {
            val dh = Noise.createDH(DH_NAME)
            try {
                dh.generateKeyPair()
                dh.getPublicKey(publicKey, 0)
                dh.getPrivateKey(privateKey, 0)
            } finally {
                dh.destroy()
            }
        }

        override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair =
            DeviceStaticKeyPair(publicKey.copyOf(), privateKey.copyOf())

        override suspend fun publicKey(serverId: String): ByteArray = publicKey.copyOf()
    }

    /** The peer's one pairing record, held in memory; the app's credential store never sees it. */
    private class SinglePairingStore(
        private val pairing: PairedServer,
    ) : PairedServerStore {
        override suspend fun load(): PairedServer = pairing

        override suspend fun save(record: PairedServer): Unit = error("the peer's pairing is fixed")
    }

    private companion object {
        val CLIENT_INFO = NoiseClientInfo(deviceName = "e2e-peer", clientVersion = "e2e-peer")
        const val DH_NAME = "25519"
        const val KEY_LENGTH = 32
    }
}
