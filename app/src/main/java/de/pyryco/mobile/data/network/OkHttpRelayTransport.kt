package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OkHttp-backed [RelayTransport] (#306). Dials the paired relay's `/v1/client` endpoint, carries bare
 * [InnerFrameV2] JSON **text** frames in both directions, and surfaces [TransportEvent]s for a
 * supervisor to drive a reconnect loop on top. Mirrors the byte-faithful OkHttp idiom proven by the
 * 2026-05-29 Noise client spike.
 *
 * Ships **dormant**: no Koin binding is added and nothing consumes it yet (same pattern as
 * `PairedServerStore`/`NoiseSessionFactory`). The sibling supervisor ticket wires the shared client +
 * singleton.
 *
 * **Concurrency.** The transport owns no coroutine and no scope — OkHttp owns its reader/dispatcher
 * threads, and two [Channel]s bridge those callbacks to [Flow]. [webSocket] is `@Volatile` (written in
 * [connect], read in [send]/[close]); [connectStarted] guards single-use; the single terminal [Down]
 * + channel-close runs exactly once via [terminated]'s CAS, closing the race between `onClosed`,
 * `onFailure`, a local protocol-violation close, and an app [close]. [send]/[connect]/[close] are
 * non-suspend and safe to call from any dispatcher.
 *
 * **Logging (#1039).** Each end writes exactly one [RelayLog] line from the terminal CAS:
 * `event=transport_end end=<label>` with `local_close` ([close]), `peer_close` (`onClosed`), `failure`
 * (`onFailure`), `protocol_violation` (a local reject of a relay frame) or `invalid_request` (bad stored
 * request data), then the close code or HTTP status when there is one, the peer's close code seen in
 * `onClosing` when the end was not a clean peer close, and the cause's class name. Never the peer's close
 * reason text, an exception message, the relay URL, the server id or the token.
 *
 * @param webSocketFactory the dial seam — a configured [OkHttpClient] (`OkHttpClient implements
 *   WebSocket.Factory`). Production passes the shared client built from [defaultClient]; tests drive
 *   the same seam with an in-process `MockWebServer`.
 */
class OkHttpRelayTransport(
    private val pairedServer: PairedServer,
    private val clientInfo: NoiseClientInfo,
    private val webSocketFactory: WebSocket.Factory,
) : RelayTransport {
    private val inboundChannel = Channel<InnerFrameV2>(capacity = Channel.BUFFERED)
    private val eventsChannel = Channel<TransportEvent>(capacity = Channel.BUFFERED)

    override val inbound: Flow<InnerFrameV2> = inboundChannel.receiveAsFlow()
    override val events: Flow<TransportEvent> = eventsChannel.receiveAsFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    private val connectStarted = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)

    /** The peer's close code from `onClosing`, kept for the end line when the handshake never finishes. */
    @Volatile
    private var peerClosingCode: Int? = null

    override fun connect() {
        check(connectStarted.compareAndSet(false, true)) {
            "connect() may be called once per transport instance"
        }
        val request =
            try {
                buildRequest()
            } catch (e: IllegalArgumentException) {
                // Malformed stored relayUrl, or a header value with illegal CR/LF/control chars (a
                // hostile QR-sourced serverId/token cannot inject extra headers — OkHttp rejects it).
                // Surfaced uniformly as a Down so the supervisor handles all failures via events.
                terminate(END_INVALID_REQUEST, TransportEvent.Down(code = null, reason = "invalid relay request", cause = e))
                return
            }
        webSocket = webSocketFactory.newWebSocket(request, Listener())
    }

    override fun send(frame: InnerFrameV2): Boolean = webSocket?.send(MobileJson.encodeToString(frame)) ?: false

    override fun close() {
        webSocket?.close(WS_NORMAL_CLOSURE, null)
        terminate(END_LOCAL_CLOSE, TransportEvent.Down(code = WS_NORMAL_CLOSURE, reason = null, cause = null))
    }

    /**
     * Builds the upgrade [Request] from [pairedServer] + [clientInfo].
     *
     * URL: scheme-convert for OkHttp's `HttpUrl` (which rejects `ws`/`wss`), then append `/v1/client`
     * as a literal path on the URL **string** so the slash is never percent-encoded. Never `/v2/client`
     * — the relay serves the `/v1/` namespace only; `v2` is the inner-frame `"v":2`.
     */
    private fun buildRequest(): Request {
        val httpUrl =
            pairedServer.relayUrl
                .replaceFirst("wss://", "https://")
                .replaceFirst("ws://", "http://")
        val dialUrl = httpUrl.trimEnd('/') + "/v1/client"

        val builder =
            Request
                .Builder()
                .url(dialUrl)
                .header("X-Pyrycode-Server", pairedServer.serverId)
                .header("X-Pyrycode-Token", pairedServer.token) // relay requires non-empty; ignored under v2
                .header("User-Agent", clientInfo.clientVersion)
        // Build.MODEL is a non-nullable String, so "available" means non-blank, not non-null.
        if (clientInfo.deviceName.isNotBlank()) {
            builder.header("X-Pyrycode-Device-Name", clientInfo.deviceName)
        }
        return builder.build()
    }

    /**
     * Runs the terminal path at most once: logs the end, emits the single [Down], then closes both
     * channels so [inbound]/[events] complete (buffered frames drain to the consumer first). The CAS makes
     * this idempotent across every termination trigger. [end] is a fixed label; [logCode] is the code the
     * line reports, which for a local protocol-violation close is the code sent rather than [Down.code].
     */
    private fun terminate(
        end: String,
        down: TransportEvent.Down,
        logCode: Int? = down.code,
    ) {
        if (terminated.compareAndSet(false, true)) {
            logEnd(end, logCode, down.cause)
            eventsChannel.trySend(down)
            eventsChannel.close()
            inboundChannel.close()
        }
    }

    /** The one end line. Content-free by construction: only fixed labels, integers and a class name. */
    private fun logEnd(
        end: String,
        code: Int?,
        cause: Throwable?,
    ) {
        val closing = peerClosingCode.takeIf { end != END_PEER_CLOSE }
        val message = {
            buildString {
                append("event=transport_end end=").append(end)
                code?.let { append(" code=").append(it) }
                closing?.let { append(" peer_closing=").append(it) }
                cause?.let { append(" cause=").append(it.javaClass.simpleName) }
            }
        }
        if (end == END_LOCAL_CLOSE || end == END_PEER_CLOSE) RelayLog.i(message) else RelayLog.w(message)
    }

    /**
     * Locally rejects a protocol violation from the untrusted relay: closes the socket with [closeCode]
     * and surfaces a terminal [Down]. The ordered encrypted stream upstream cannot skip a frame, so the
     * transport tears down rather than dropping — the supervisor reconnects with a fresh handshake.
     * [reason] is category-only; [cause] carries the detail for the supervisor.
     */
    private fun failLocally(
        webSocket: WebSocket,
        closeCode: Int,
        reason: String,
        cause: Throwable,
    ) {
        webSocket.close(closeCode, reason)
        terminate(END_PROTOCOL_VIOLATION, TransportEvent.Down(code = null, reason = reason, cause = cause), logCode = closeCode)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            eventsChannel.trySend(TransportEvent.Up)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (text.length > MAX_INBOUND_FRAME_CHARS) {
                // Post-receive cap: OkHttp's stable API reads the whole message before delivery, so the
                // one offending allocation already happened; this prevents propagating it / repeated growth.
                failLocally(
                    webSocket,
                    WS_MESSAGE_TOO_BIG,
                    "inbound frame exceeds size cap",
                    ProtocolException("inbound frame exceeds size cap"),
                )
                return
            }
            val frame =
                try {
                    MobileJson.decodeFromString<InnerFrameV2>(text)
                } catch (e: SerializationException) {
                    failLocally(webSocket, WS_PROTOCOL_ERROR, "malformed inbound frame", e)
                    return
                }
            // Blocks the OkHttp reader thread when the buffer fills -> TCP flow-control. Never drops a
            // frame (a drop would break the upstream Noise nonce sequence). `data` is opaque, untouched.
            inboundChannel.trySendBlocking(frame)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            failLocally(
                webSocket,
                WS_UNSUPPORTED_DATA,
                "unexpected binary frame",
                ProtocolException("relay sent a binary frame; expected text"),
            )
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            // Acknowledge the server's close to complete the WS closing handshake; onClosed follows. If
            // the peer drops TCP first, onFailure ends the socket instead and the end line keeps this code.
            peerClosingCode = code
            webSocket.close(WS_NORMAL_CLOSURE, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            terminate(END_PEER_CLOSE, TransportEvent.Down(code = code, reason = reason, cause = null))
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            // reason is category-only (no dial URL / token); the full Throwable rides in cause.
            terminate(END_FAILURE, TransportEvent.Down(code = response?.code, reason = t.javaClass.simpleName, cause = t))
        }
    }

    companion object {
        /**
         * Max inbound WS text-frame length. The wire contract caps plaintext at 65519 B; the wire
         * [InnerFrameV2] (base64 of plaintext+16 plus JSON envelope) is ≈ 87.5 KB at the maximum. The
         * wire frame is pure ASCII (JSON + base64-std), so `text.length` ≈ byte count. 128 KiB is
         * comfortable headroom over a max legitimate frame.
         */
        const val MAX_INBOUND_FRAME_CHARS = 131_072

        private const val WS_NORMAL_CLOSURE = 1000
        private const val WS_PROTOCOL_ERROR = 1002
        private const val WS_UNSUPPORTED_DATA = 1003
        private const val WS_MESSAGE_TOO_BIG = 1009

        // The fixed end labels of the #1039 end line.
        private const val END_LOCAL_CLOSE = "local_close"
        private const val END_PEER_CLOSE = "peer_close"
        private const val END_FAILURE = "failure"
        private const val END_PROTOCOL_VIOLATION = "protocol_violation"
        private const val END_INVALID_REQUEST = "invalid_request"

        /**
         * The securely-configured shared client for relay dials. `readTimeout`/`callTimeout` are
         * deliberately 0 (a non-zero value would kill a healthy idle/long-lived WS); `pingInterval`
         * provides liveness + dead-peer detection instead. `MODERN_TLS` (TLS 1.2+) for `wss://`;
         * `CLEARTEXT` retained for `ws://` local/dev relays; legacy `COMPATIBLE_TLS` dropped. No
         * `HttpLoggingInterceptor` — it would dump the `X-Pyrycode-Token` header.
         *
         * The sibling supervisor binds **one** app-wide client from this and passes it in; a fresh
         * `OkHttpClient` per reconnect would leak thread pools.
         */
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
                .build()
    }
}
