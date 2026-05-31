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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
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
                object : WebSocketListener() {
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
    }

    // ---- Design: malformed stored relayUrl surfaces Down, does not throw -------

    @Test
    fun connect_malformedRelayUrlSurfacesDownAndDoesNotThrow() {
        val transport = newTransport(relayUrl = "not a valid url")
        transport.connect() // must not throw despite the bad stored URL

        val down =
            runBlocking { withTimeout(TIMEOUT_MS) { transport.events.first() } } as TransportEvent.Down
        assertNotNull(down.cause)
    }

    // ---- Design: malformed inbound JSON is rejected at the untrusted boundary ---

    @Test
    fun inbound_malformedJsonTearsDownWithDown() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
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

    /** Server-side WS listener that captures inbound text frames for assertion. */
    private class RecordingServerListener : WebSocketListener() {
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
