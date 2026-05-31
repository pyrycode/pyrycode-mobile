package de.pyryco.mobile.data.network

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Noise session pump (#309): it drives the `Noise_IK` handshake over a
 * landed [RelayTransport] (#306) and runs the open-state `noise_msg` decrypt/dispatch loop on top of
 * a real [NoiseIkSession] (#303). Driven with `runTest`'s virtual clock, a `Channel`-backed fake
 * transport the test pushes frames into, and a real IK [TestResponder] (the in-test mirror of the Go
 * `flynn/noise` peer) so the session is exercised against a real peer, not a stub. Pure data-layer —
 * no device, same posture as [NoiseIkSessionTest] / [RelayConnectionSupervisorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoiseSessionPumpTest {
    // ---- AC 1: drive the handshake → Open, carrying the hello as encrypted early-data -----------

    @Test
    fun start_drivesHandshakeToOpenAndCarriesHello() =
        runTest {
            val f = fixture()
            val pump = f.newPump()

            pump.start()
            runCurrent()

            // Exactly one frame so far: a noise_init the responder accepts; Handshaking until the resp.
            val init = f.transport.sentFrames.single()
            assertEquals("noise_init", init.type)
            assertEquals(PumpState.Handshaking, pump.state.value)
            val helloJson = f.responder.readInit(base64StdDecode(init.data))
            assertEquals("hello", MobileJson.decodeFromString<Envelope>(helloJson).type)

            f.transport.pushInbound(noiseResp(f.responder, connId = "conn-xyz"))
            runCurrent()
            assertEquals(PumpState.Open("conn-xyz"), pump.state.value)

            pump.close()
        }

    @Test
    fun handshake_timesOutAndClosesTransport() =
        runTest {
            val f = fixture()
            val pump = f.newPump()

            pump.start()
            runCurrent()
            assertEquals(
                "noise_init",
                f.transport.sentFrames
                    .single()
                    .type,
            )
            assertEquals(PumpState.Handshaking, pump.state.value)

            advanceUntilIdle() // the 10 s noise_resp deadline elapses with no resp
            assertTrue(pump.state.value is PumpState.Closed)
            assertTrue(f.transport.closeCalls >= 1)
        }

    @Test
    fun handshake_noiseRespMacFailureClosesPump() =
        runTest {
            val f = fixture()
            val pump = f.newPump()

            pump.start()
            runCurrent()
            f.responder.readInit(
                base64StdDecode(
                    f.transport.sentFrames
                        .single()
                        .data,
                ),
            )
            val resp = f.responder.writeResp(ackEnvelope("conn-1"))
            resp[resp.size - 1] = (resp[resp.size - 1].toInt() xor 0x01).toByte() // corrupt the MAC tag

            f.transport.pushInbound(InnerFrameV2(type = "noise_resp", data = base64StdEncode(resp)))
            runCurrent()

            val state = pump.state.value
            assertTrue(state is PumpState.Closed)
            assertTrue((state as PumpState.Closed).cause is NoiseSessionException)
            assertTrue(f.transport.closeCalls >= 1)
        }

    @Test
    fun handshake_wrongFirstFrameTypeClosesPump() =
        runTest {
            val f = fixture()
            val pump = f.newPump()

            pump.start()
            runCurrent()
            f.responder.readInit(
                base64StdDecode(
                    f.transport.sentFrames
                        .single()
                        .data,
                ),
            )

            // First inbound frame is a noise_msg, not the expected noise_resp.
            f.transport.pushInbound(InnerFrameV2(type = "noise_msg", data = base64StdEncode(byteArrayOf(1, 2, 3))))
            runCurrent()

            assertTrue(pump.state.value is PumpState.Closed)
            assertTrue(f.transport.closeCalls >= 1)
        }

    // ---- AC 2: open-state inbound noise_msg → decrypted Envelope, fail-closed on bad frames ------

    @Test
    fun open_inboundNoiseMsgSurfacesDecryptedEnvelope() =
        runTest {
            val f = fixture()
            val os = openSession(f)
            val env = envelope(id = 7L, type = "message", payload = """{"text":"hi"}""")

            f.transport.pushInbound(noiseMsg(os.responderPair, env))
            runCurrent()

            assertEquals(1, os.received.size)
            val got = os.received.single()
            assertEquals(7L, got.id)
            assertEquals("message", got.type)
            assertEquals(env.payload, got.payload)

            os.pump.close()
        }

    @Test
    fun open_undecryptableNoiseMsgTearsDownWithoutCrash() =
        runTest {
            val f = fixture()
            val os = openSession(f)

            // Garbage ciphertext (shorter than the AEAD tag) — the ordered stream cannot skip a frame.
            f.transport.pushInbound(InnerFrameV2(type = "noise_msg", data = base64StdEncode(byteArrayOf(1, 2, 3))))
            runCurrent()

            assertTrue(os.pump.state.value is PumpState.Closed)
            assertTrue(f.transport.closeCalls >= 1)
            advanceUntilIdle()
            assertTrue(os.collector.isCompleted) // inbound completed; no leaked collector
        }

    @Test
    fun open_malformedEnvelopePlaintextTearsDown() =
        runTest {
            val f = fixture()
            val os = openSession(f)

            // Decrypts cleanly, but the plaintext is not a JSON Envelope.
            f.transport.pushInbound(noiseMsgRaw(os.responderPair, "this is not json".encodeToByteArray()))
            runCurrent()

            assertTrue(os.pump.state.value is PumpState.Closed)
        }

    @Test
    fun open_unknownFrameTypeTearsDown() =
        runTest {
            val f = fixture()
            val os = openSession(f)

            // A bare noise_resp mid-stream is the #304 re-key seam; today the else branch tears down.
            f.transport.pushInbound(InnerFrameV2(type = "noise_resp", data = base64StdEncode(byteArrayOf(0))))
            runCurrent()

            assertTrue(os.pump.state.value is PumpState.Closed)
        }

    // ---- AC 3: outbound send encrypts + frames as noise_msg -------------------------------------

    @Test
    fun send_afterOpenEmitsEncryptedNoiseMsg() =
        runTest {
            val f = fixture()
            val os = openSession(f)
            val env = envelope(id = 3L, type = "send_message", payload = """{"text":"hello"}""")

            assertTrue(os.pump.send(env))

            val sent = f.transport.sentFrames.last()
            assertEquals("noise_msg", sent.type)
            val decoded = decryptOutbound(os.responderPair, sent)
            assertEquals(3L, decoded.id)
            assertEquals("send_message", decoded.type)
            assertEquals(env.payload, decoded.payload)

            os.pump.close()
        }

    @Test
    fun send_beforeOpenReturnsFalseAndEmitsNoFrame() =
        runTest {
            val f = fixture()
            val pump = f.newPump()

            assertFalse(pump.send(envelope()))
            assertTrue(f.transport.sentFrames.isEmpty())
        }

    @Test
    fun send_afterClosedReturnsFalse() =
        runTest {
            val f = fixture()
            val os = openSession(f)

            os.pump.close()
            advanceUntilIdle()
            assertFalse(os.pump.send(envelope()))
        }

    // ---- AC 5: lifecycle — Down wipes the session, close() is idempotent, no leaked coroutine ----

    @Test
    fun transportDown_closesPumpWipesSessionAndLeavesNoLeak() =
        runTest {
            val f = fixture()
            val os = openSession(f)

            f.transport.completeInbound() // simulate transport Down (inbound completes)
            advanceUntilIdle()

            val state = os.pump.state.value
            assertTrue(state is PumpState.Closed)
            assertNull((state as PumpState.Closed).cause) // clean Down → no fault cause
            assertFalse(os.pump.send(envelope())) // session wiped → not Open
            assertTrue(os.collector.isCompleted) // collector flow completed → no leaked coroutine
        }

    @Test
    fun close_isIdempotent() =
        runTest {
            val f = fixture()
            val os = openSession(f)

            os.pump.close()
            os.pump.close() // no throw
            advanceUntilIdle()

            assertTrue(os.pump.state.value is PumpState.Closed)
            assertFalse(os.pump.send(envelope()))
        }

    @Test
    fun start_secondCallThrows() =
        runTest {
            val f = fixture()
            val pump = f.newPump()

            pump.start()
            assertThrows(IllegalStateException::class.java) { pump.start() }

            pump.close()
        }

    // ---- Fixture + helpers ---------------------------------------------------------------------

    private fun TestScope.fixture(): Fixture = Fixture(testScheduler)

    /** One connection's worth of collaborators: a fake transport, a real IK responder, a factory. */
    private class Fixture(
        scheduler: TestCoroutineScheduler,
    ) {
        val dispatcher = StandardTestDispatcher(scheduler)
        val responder = TestResponder()
        val transport = FakeRelayTransport()
        val factory =
            NoiseSessionFactory(
                deviceStaticKeyStore = FakeDeviceStaticKeyStore(newDeviceKeyPair()),
                pairedServerStore = FakePairedServerStore(pairedRecord(base64StdEncode(responder.staticPublicKey))),
                clientInfo = NoiseClientInfo("Pixel-Test", "1.0.0-test"),
                ioDispatcher = dispatcher,
            )

        fun newPump() = NoiseSessionPump(transport, factory, dispatcher = dispatcher)
    }

    private class OpenSession(
        val pump: NoiseSessionPump,
        val responderPair: CipherStatePair,
        val received: List<Envelope>,
        val collector: Job,
    )

    /** Starts a pump, drives the handshake to Open, and attaches a single inbound collector. */
    private fun TestScope.openSession(
        f: Fixture,
        connId: String = "conn-xyz",
    ): OpenSession {
        val pump = f.newPump()
        val received = mutableListOf<Envelope>()
        // A foreground child of the test scope (not backgroundScope): advanceUntilIdle() fully drains
        // it and runTest's structured concurrency enforces it completes — both real leak checks. Every
        // openSession test drives the pump to Closed, which closes inbound and completes this collector.
        val collector = launch { pump.inbound.collect { received += it } }

        pump.start()
        runCurrent()
        val init = f.transport.sentFrames.single()
        assertEquals("noise_init", init.type)
        f.responder.readInit(base64StdDecode(init.data))
        f.transport.pushInbound(noiseResp(f.responder, connId))
        runCurrent()
        assertEquals(PumpState.Open(connId), pump.state.value)

        return OpenSession(pump, f.responder.split(), received, collector)
    }

    /** A responder-produced `noise_resp` frame carrying [connId] in its `hello_ack` early-data. */
    private fun noiseResp(
        responder: TestResponder,
        connId: String,
    ): InnerFrameV2 = InnerFrameV2(type = "noise_resp", data = base64StdEncode(responder.writeResp(ackEnvelope(connId))))

    /** A responder-encrypted `noise_msg` frame whose plaintext is [env] (responder.sender → pump.decrypt). */
    private fun noiseMsg(
        pair: CipherStatePair,
        env: Envelope,
    ): InnerFrameV2 = noiseMsgRaw(pair, MobileJson.encodeToString(env).encodeToByteArray())

    private fun noiseMsgRaw(
        pair: CipherStatePair,
        plaintext: ByteArray,
    ): InnerFrameV2 {
        val ct = ByteArray(plaintext.size + 16)
        val n = pair.sender.encryptWithAd(null, plaintext, 0, ct, 0, plaintext.size)
        return InnerFrameV2(type = "noise_msg", data = base64StdEncode(ct.copyOf(n)))
    }

    /** Decrypts a pump-sent `noise_msg` (pump.encrypt → responder.receiver) back to its Envelope. */
    private fun decryptOutbound(
        pair: CipherStatePair,
        frame: InnerFrameV2,
    ): Envelope {
        val ct = base64StdDecode(frame.data)
        val out = ByteArray(ct.size)
        val n = pair.receiver.decryptWithAd(null, ct, 0, out, 0, ct.size)
        return MobileJson.decodeFromString(out.copyOf(n).decodeToString())
    }

    /**
     * Channel-backed fake of the single-connection #306 transport: the test pushes inbound frames,
     * captures outbound sends, and completes `inbound` to simulate a `Down`. The pump receives an
     * already-`Up` transport, so [connect] must never be called.
     */
    private class FakeRelayTransport : RelayTransport {
        private val inboundChannel = Channel<InnerFrameV2>(Channel.UNLIMITED)

        override val inbound: Flow<InnerFrameV2> = inboundChannel.receiveAsFlow()
        override val events: Flow<TransportEvent> = emptyFlow() // the pump never reads events (that's #307)

        val sentFrames = mutableListOf<InnerFrameV2>()
        var closeCalls = 0
            private set

        override fun connect() = error("the pump receives an already-Up transport and must never call connect()")

        override fun send(frame: InnerFrameV2): Boolean {
            sentFrames += frame
            return true
        }

        override fun close() {
            closeCalls++
            inboundChannel.close()
        }

        fun pushInbound(frame: InnerFrameV2) {
            inboundChannel.trySend(frame)
        }

        fun completeInbound() {
            inboundChannel.close()
        }
    }

    /** In-test mirror of the Go flynn/noise responder (copied from NoiseIkSessionTest's pattern). */
    private class TestResponder {
        private val handshake = HandshakeState(PROTO, HandshakeState.RESPONDER)
        val staticPublicKey: ByteArray

        init {
            handshake.localKeyPair.generateKeyPair()
            staticPublicKey = ByteArray(handshake.localKeyPair.publicKeyLength)
            handshake.localKeyPair.getPublicKey(staticPublicKey, 0)
            handshake.start()
        }

        /** Reads msg1 (noise_init); returns the recovered `hello` envelope JSON. */
        fun readInit(init: ByteArray): String {
            val buf = ByteArray(init.size)
            val n = handshake.readMessage(init, 0, init.size, buf, 0)
            return String(buf, 0, n, Charsets.UTF_8)
        }

        /** Writes msg2 (noise_resp) carrying [ackEnvelopeJson] as `hello_ack` early-data. */
        fun writeResp(ackEnvelopeJson: String): ByteArray {
            val payload = ackEnvelopeJson.toByteArray(Charsets.UTF_8)
            val out = ByteArray(payload.size + 96)
            val n = handshake.writeMessage(out, 0, payload, 0, payload.size)
            return out.copyOf(n)
        }

        /** Splits into transport ciphers (responder mirror-swaps, like flynn/noise). */
        fun split(): CipherStatePair = handshake.split()
    }

    private class FakeDeviceStaticKeyStore(
        private val keyPair: DeviceStaticKeyPair,
    ) : DeviceStaticKeyStore {
        override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair = keyPair

        override suspend fun publicKey(serverId: String): ByteArray = keyPair.publicKey
    }

    private class FakePairedServerStore(
        private val record: PairedServer,
    ) : PairedServerStore {
        override suspend fun load(): PairedServer = record

        override suspend fun save(record: PairedServer) = error("save is not exercised by the pump")
    }

    private companion object {
        const val PROTO = "Noise_IK_25519_ChaChaPoly_BLAKE2s"

        fun envelope(
            id: Long = 1L,
            type: String = "send_message",
            payload: String = """{"text":"x"}""",
        ) = Envelope(id = id, type = type, ts = "2026-05-31T00:00:00Z", payload = MobileJson.parseToJsonElement(payload))

        fun ackEnvelope(connId: String): String {
            val payload = HelloAckPayload(protocolVersion = "v2", serverId = "srv-1", connId = connId)
            return MobileJson.encodeToString(
                Envelope(
                    id = 2L,
                    type = "hello_ack",
                    ts = "2026-05-31T00:00:00Z",
                    payload = MobileJson.encodeToJsonElement(HelloAckPayload.serializer(), payload),
                ),
            )
        }

        fun pairedRecord(serverStaticPublicKey: String) =
            PairedServer(
                serverId = "srv-1",
                token = "tok",
                relayUrl = "ws://relay.example",
                serverStaticPublicKey = serverStaticPublicKey,
            )

        fun newDeviceKeyPair(): DeviceStaticKeyPair {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val priv = ByteArray(dh.privateKeyLength).also { dh.getPrivateKey(it, 0) }
            val pub = ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
            return DeviceStaticKeyPair(publicKey = pub, privateKey = priv)
        }
    }
}
