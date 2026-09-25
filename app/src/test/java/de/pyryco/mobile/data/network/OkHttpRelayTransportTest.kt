package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for the single-connection relay WS transport (#306), driven against an in-process
 * [MockWebServer] WS upgrade (the technical-notes "in-process server" for the dial seam). No device —
 * like the sibling `data/network` tests. The real OkHttp reader/dispatcher threads feed the Channel-
 * backed flows, so each scenario bridges them with `runBlocking { withTimeout(...) { ... } }` rather
 * than relying on `runTest` virtual time to advance the network.
 */
class OkHttpRelayTransportTest {
    private lateinit var server: MockWebServer

    /** RelayLog lines, written from OkHttp threads as well as the test thread. */
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val previousSink = RelayLog.sink
    private val previousEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        RelayLog.sink = previousSink
        RelayLog.enabled = previousEnabled
    }

    // ---- AC #1, #4: connect path + required headers ----------------------------

    @Test
    fun connect_dialsV1ClientVerbatimWithAllHeaders() {
        val serverListener = RecordingServerListener()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val transport =
            newTransport(
                serverId = "srv-1",
                token = "tok",
                clientInfo = NoiseClientInfo(deviceName = "Pixel-Test", clientVersion = "1.2.3"),
            )
        transport.connect()
        runBlocking { withTimeout(TIMEOUT_MS) { assertEquals(TransportEvent.Up, transport.events.first()) } }

        val request = server.takeRequest(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertNotNull(request)
        assertEquals("/v1/client", request!!.path)
        assertEquals("srv-1", request.getHeader("X-Pyrycode-Server"))
        assertEquals("tok", request.getHeader("X-Pyrycode-Token"))
        assertTrue(request.getHeader("User-Agent")!!.contains("1.2.3"))
        assertEquals("Pixel-Test", request.getHeader("X-Pyrycode-Device-Name"))

        transport.close()
    }

    // ---- AC #1, #4: device-name header omitted when blank ----------------------

    @Test
    fun connect_omitsDeviceNameHeaderWhenEmpty() {
        assertDeviceNameHeaderAbsent(deviceName = "")
    }

    @Test
    fun connect_omitsDeviceNameHeaderWhenWhitespaceOnly() {
        assertDeviceNameHeaderAbsent(deviceName = "   ")
    }

    // ---- AC #2, #4: outbound serialize -> bare text frame ----------------------

    @Test
    fun send_serializesFrameToBareTextFrameViaMobileJson() {
        val serverListener = RecordingServerListener()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val transport = newTransport()
        transport.connect()
        runBlocking { withTimeout(TIMEOUT_MS) { transport.events.first() } } // await Up

        val frame = InnerFrameV2(type = "noise_init", data = "AAAA")
        assertTrue(transport.send(frame))

        val text = serverListener.received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertNotNull(text)
        assertEquals(MobileJson.encodeToString(frame), text)
        assertEquals(frame, MobileJson.decodeFromString<InnerFrameV2>(text!!))

        transport.close()
    }

    // ---- AC #2, #4: inbound text frame -> InnerFrameV2 (opaque data) ------------

    @Test
    fun inbound_deserializesTextFrameWithOpaqueDataPassThrough() {
        val expected = InnerFrameV2(type = "noise_resp", data = "BBBB")
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : ClosingServerListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(MobileJson.encodeToString(expected))
                    }
                },
            ),
        )

        val transport = newTransport()
        transport.connect()

        val received = runBlocking { withTimeout(TIMEOUT_MS) { transport.inbound.first() } }
        assertEquals(expected, received)
        assertEquals("BBBB", received.data) // passed through unchanged — never decoded here

        transport.close()
    }

    // ---- AC #3: Up on open, Down(code) on server close -------------------------

    @Test
    fun events_emitUpThenDownWithServerCloseCode() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.close(4401, "unauthorized")
                    }
                },
            ),
        )

        val transport = newTransport()
        transport.connect()

        val events = runBlocking { withTimeout(TIMEOUT_MS) { transport.events.take(2).toList() } }
        assertEquals(TransportEvent.Up, events[0])
        val down = events[1] as TransportEvent.Down
        assertEquals(4401, down.code)
        assertNull(down.cause)
        assertEquals(listOf("event=transport_end end=peer_close code=4401"), endLines())
        assertTrue(logs.none { "unauthorized" in it })
    }

    // ---- #1039: each transport end writes one RelayLog line ---------------------

    @Test
    fun close_logsOneLocalCloseLine() {
        server.enqueue(MockResponse().withWebSocketUpgrade(RecordingServerListener()))
        val transport = newTransport()
        transport.connect()
        runBlocking { withTimeout(TIMEOUT_MS) { transport.events.first() } } // await Up

        transport.close()
        transport.close() // idempotent: still one line

        assertEquals(listOf("event=transport_end end=local_close code=1000"), endLines())
        assertNoSecretsLogged()
    }

    @Test
    fun peerClosingThenFailure_logsFailureWithThePeersClosingCode() {
        // The relay sends close 1011 and drops TCP before the close handshake completes: OkHttp reports
        // onClosing(1011) then onFailure, so the line must still carry the peer's code. Driven through a
        // stub factory because a real server cannot reliably drop TCP at exactly that moment.
        val factory = CapturingFactory()
        val transport =
            OkHttpRelayTransport(
                pairedServer = PairedServer("srv-1", "tok", "ws://relay.invalid", "pk"),
                clientInfo = NoiseClientInfo(deviceName = "Pixel-Test", clientVersion = "1.2.3"),
                webSocketFactory = factory,
            )
        transport.connect()
        val listener = factory.listener!!
        listener.onClosing(factory.socket, 1011, "outbox overflow")
        listener.onFailure(factory.socket, java.io.EOFException("frame content"), null)

        assertEquals(listOf("event=transport_end end=failure peer_closing=1011 cause=EOFException"), endLines())
        assertTrue(logs.none { "outbox" in it || "frame content" in it || "relay.invalid" in it })
    }

    // ---- AC #3: Down on dial failure -------------------------------------------

    @Test
    fun connect_emitsDownWithCauseOnDialFailure() {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = "ws://${dead.hostName}:${dead.port}"
        dead.shutdown() // nothing is listening on this port now

        val transport = newTransport(relayUrl = deadUrl)
        transport.connect() // must not throw

        val down =
            runBlocking {
                withTimeout(TIMEOUT_MS) { transport.events.first { it is TransportEvent.Down } }
            } as TransportEvent.Down
        assertNotNull(down.cause)
        assertEquals(
            listOf("event=transport_end end=failure cause=${down.cause!!.javaClass.simpleName}"),
            endLines(),
        )
        assertTrue(logs.none { dead.port.toString() in it })
        assertNoSecretsLogged()
    }

    // ---- Design: malformed stored relayUrl surfaces Down, does not throw -------

    @Test
    fun connect_malformedRelayUrlSurfacesDownAndDoesNotThrow() {
        val transport = newTransport(relayUrl = "not a valid url")
        transport.connect() // must not throw despite the bad stored URL

        val down =
            runBlocking { withTimeout(TIMEOUT_MS) { transport.events.first() } } as TransportEvent.Down
        assertNotNull(down.cause)
        assertEquals(listOf("event=transport_end end=invalid_request cause=IllegalArgumentException"), endLines())
        assertTrue(logs.none { "not a valid url" in it })
    }

    // ---- Design: malformed inbound JSON is rejected at the untrusted boundary ---

    @Test
    fun inbound_malformedJsonTearsDownWithDown() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : ClosingServerListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send("this is not an InnerFrameV2")
                    }
                },
            ),
        )

        val transport = newTransport()
        transport.connect()

        val down =
            runBlocking {
                withTimeout(TIMEOUT_MS) { transport.events.first { it is TransportEvent.Down } }
            } as TransportEvent.Down
        assertNotNull(down.cause)
        assertEquals(
            listOf("event=transport_end end=protocol_violation code=1002 cause=${down.cause!!.javaClass.simpleName}"),
            endLines(),
        )
        assertTrue(logs.none { "not an InnerFrameV2" in it })
        assertNoSecretsLogged()
    }

    // ---- Design: binary inbound frame is rejected at the untrusted boundary -----

    @Test
    fun inbound_binaryFrameTearsDownWithDownAndDeliversNothing() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : ClosingServerListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        // Any binary frame routes to onMessage(_, bytes) -> failLocally; content is irrelevant.
                        webSocket.send(ByteString.of(1.toByte()))
                    }
                },
            ),
        )

        val transport = newTransport()
        transport.connect()

        runBlocking {
            // Terminal Down proves the socket was torn down rather than the frame processed.
            withTimeout(TIMEOUT_MS) { transport.events.first { it is TransportEvent.Down } }
            // terminate() closed inboundChannel with nothing delivered, so the stream completes empty.
            withTimeout(TIMEOUT_MS) { assertTrue(transport.inbound.toList().isEmpty()) }
        }
    }

    // ---- Design: oversize inbound text frame is rejected at the untrusted boundary

    @Test
    fun inbound_oversizeTextFrameTearsDownWithDownAndDeliversNothing() {
        // Strictly greater than the cap (the guard is `>`): 131_073 chars trips it; exactly the cap is accepted.
        // Non-JSON content is intentional — the size check runs before any JSON decode.
        val oversize = "a".repeat(OkHttpRelayTransport.MAX_INBOUND_FRAME_CHARS + 1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : ClosingServerListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(oversize)
                    }
                },
            ),
        )

        val transport = newTransport()
        transport.connect()

        runBlocking {
            withTimeout(TIMEOUT_MS) { transport.events.first { it is TransportEvent.Down } }
            withTimeout(TIMEOUT_MS) { assertTrue(transport.inbound.toList().isEmpty()) }
        }
    }

    // ---- State guards: connect() twice throws; send() after close() is false ----

    @Test
    fun connect_calledTwiceThrowsIllegalState() {
        server.enqueue(MockResponse().withWebSocketUpgrade(RecordingServerListener()))
        val transport = newTransport()
        transport.connect()
        assertThrows(IllegalStateException::class.java) { transport.connect() }
        transport.close()
    }

    @Test
    fun send_afterCloseReturnsFalse() {
        server.enqueue(MockResponse().withWebSocketUpgrade(RecordingServerListener()))
        val transport = newTransport()
        transport.connect()
        runBlocking { withTimeout(TIMEOUT_MS) { transport.events.first() } } // await Up
        transport.close()

        assertFalse(transport.send(InnerFrameV2(type = "noise_msg", data = "AAAA")))
    }

    // ---- defaultClient() security-load-bearing config --------------------------

    @Test
    fun defaultClient_hasSecureLongLivedWebSocketConfig() {
        val client = OkHttpRelayTransport.defaultClient()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.pingIntervalMillis)
        assertEquals(0, client.readTimeoutMillis) // disabled — pingInterval is the liveness mechanism
        assertTrue(client.connectionSpecs.contains(ConnectionSpec.MODERN_TLS))
        assertFalse(client.connectionSpecs.contains(ConnectionSpec.COMPATIBLE_TLS))
    }

    // ---- Helpers ---------------------------------------------------------------

    private fun endLines(): List<String> = synchronized(logs) { logs.filter { it.startsWith("event=transport_end") } }

    /** No line may carry the token, the server id or the dial host (#1039, RelayLog's MUST NOT list). */
    private fun assertNoSecretsLogged() {
        synchronized(logs) {
            assertTrue(logs.toString(), logs.none { "tok" in it || "srv-1" in it || server.hostName in it })
        }
    }

    private fun newTransport(
        relayUrl: String = "ws://${server.hostName}:${server.port}",
        serverId: String = "srv-1",
        token: String = "tok",
        clientInfo: NoiseClientInfo = NoiseClientInfo(deviceName = "Pixel-Test", clientVersion = "1.2.3"),
        client: OkHttpClient = OkHttpClient(),
    ): OkHttpRelayTransport =
        OkHttpRelayTransport(
            pairedServer =
                PairedServer(
                    serverId = serverId,
                    token = token,
                    relayUrl = relayUrl,
                    serverStaticPublicKey = "pk",
                ),
            clientInfo = clientInfo,
            webSocketFactory = client,
        )

    /** Connects with [deviceName] and asserts the relay never received an `X-Pyrycode-Device-Name` header. */
    private fun assertDeviceNameHeaderAbsent(deviceName: String) {
        server.enqueue(MockResponse().withWebSocketUpgrade(RecordingServerListener()))
        val transport = newTransport(clientInfo = NoiseClientInfo(deviceName = deviceName, clientVersion = "1.0"))
        transport.connect()
        runBlocking { withTimeout(TIMEOUT_MS) { transport.events.first() } } // await Up

        val request = server.takeRequest(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertNotNull(request)
        assertNull(request!!.getHeader("X-Pyrycode-Device-Name"))

        transport.close()
    }

    /** A [WebSocket.Factory] that hands back a stub socket and keeps the transport's listener to drive. */
    private class CapturingFactory : WebSocket.Factory {
        var listener: WebSocketListener? = null
        val socket =
            object : WebSocket {
                override fun request() = throw UnsupportedOperationException()

                override fun queueSize() = 0L

                override fun send(text: String) = true

                override fun send(bytes: ByteString) = true

                override fun close(
                    code: Int,
                    reason: String?,
                ) = true

                override fun cancel() = Unit
            }

        override fun newWebSocket(
            request: okhttp3.Request,
            listener: WebSocketListener,
        ): WebSocket {
            this.listener = listener
            return socket
        }
    }

    /**
     * Base server-side listener that echoes the peer's close so the closing handshake completes from
     * the server side. Without it, a client-initiated close leaves the server socket half-open in
     * MockWebServer's `openClientSockets`, and `server.shutdown()` blocks on its drain timeout ("Gave
     * up waiting for queue to shut down"). Every server listener in these tests extends this.
     */
    private open class ClosingServerListener : WebSocketListener() {
        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, null)
        }
    }

    /** Server-side WS listener that captures inbound text frames for assertion. */
    private class RecordingServerListener : ClosingServerListener() {
        val received = LinkedBlockingQueue<String>()

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            received.add(text)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
