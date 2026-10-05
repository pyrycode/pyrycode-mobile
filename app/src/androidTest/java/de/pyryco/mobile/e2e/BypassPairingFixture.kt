package de.pyryco.mobile.e2e

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/** A one-shot, scenario-entry pairing fixture. Never include its fields in diagnostics. */
internal class BypassPairingFixture private constructor(
    val serverId: String,
    val pairCode: String,
    val peerToken: String,
    val serverStaticPublicKey: String,
) {
    companion object {
        fun request(): BypassPairingFixture {
            try {
                val args = InstrumentationRegistry.getArguments()
                val port = requireNotNull(args.getString("bypassFixturePort")).toInt()
                require(port in 1..65535)
                val authorization = requireNotNull(args.getString("bypassFixtureAuthorization"))
                require(authorization.matches(Regex("[A-Za-z0-9_-]{43}")))
                return Socket().use { socket ->
                    socket.connect(InetSocketAddress("10.0.2.2", port), 5_000)
                    socket.soTimeout = 60_000
                    socket.getOutputStream().write(
                        (
                            "POST /pair HTTP/1.0\r\nAuthorization: Bearer $authorization\r\n" +
                                "Content-Length: 0\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                    )
                    // Bound the complete response, including headers, before parsing any credential fields.
                    val response = socket.getInputStream().readNBytes(20_481)
                    check(response.size <= 20_480)
                    val text = response.toString(Charsets.UTF_8)
                    check(text.startsWith("HTTP/1.0 200 "))
                    val separator = text.indexOf("\r\n\r\n")
                    check(separator in 1..4096)
                    val body = text.substring(separator + 4)
                    check(body.length <= 16_384)
                    val payload = JSONObject(body)

                    fun field(key: String): String = payload.getString(key).also { check(it.isNotEmpty()) }
                    BypassPairingFixture(
                        serverId = field("serverId"),
                        pairCode = field("pairCode"),
                        peerToken = field("peerToken"),
                        serverStaticPublicKey = field("serverStaticPublicKey"),
                    )
                }
            } catch (_: Exception) {
                // Parsing/transport exceptions may carry credential material: discard their messages/causes.
                throw AssertionError("bypass scenario-entry pairing fixture failed; see private harness diagnostics")
            }
        }
    }
}
