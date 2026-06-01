package de.pyryco.mobile.data.network

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * parse + validate + map tests for [parsePairingPayload] (#320). JUnit4, mirroring
 * MobileWireCodecTest. The untrusted scanned string is the OUTER base64url-no-pad
 * wrapper (Go `base64.RawURLEncoding`); the inner `server_static_pubkey` is base64-std
 * — the two-alphabet trap. Every reject path must yield [PairingParseResult.Failure]
 * without ever throwing, and no [Failure.reason] may echo the token or key bytes (AC4).
 */
class PairingPayloadParserTest {
    private val validPubkey = base64StdEncode(ByteArray(32))

    private fun json(
        server: String = "srv-1",
        relay: String = "wss://relay.example.com",
        token: String = "tok-123",
        pubkey: String = validPubkey,
    ): String = """{"server":"$server","relay":"$relay","token":"$token","server_static_pubkey":"$pubkey"}"""

    /** Wrap a JSON string in the outer base64url-no-pad transport encoding the scanner emits. */
    private fun wrap(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))

    // ---- Success + exact field mapping ----------------------------------------

    @Test
    fun validWrapper_mapsAllFieldsToPairedServer() {
        val result = parsePairingPayload(wrap(json()))

        assertTrue(result is PairingParseResult.Success)
        val server = (result as PairingParseResult.Success).server
        assertEquals("srv-1", server.serverId)
        assertEquals("tok-123", server.token)
        assertEquals("wss://relay.example.com", server.relayUrl)
        assertEquals(validPubkey, server.serverStaticPublicKey)
    }

    @Test
    fun roundTrip_encodeQrPayloadThenParse_recoversFields() {
        val original =
            QrPayload(
                server = "srv-x",
                relay = "ws://10.0.0.5:8080",
                token = "secret-tok",
                serverStaticPubkey = validPubkey,
            )
        val result = parsePairingPayload(wrap(MobileJson.encodeToString(original)))

        assertTrue(result is PairingParseResult.Success)
        val server = (result as PairingParseResult.Success).server
        assertEquals(original.server, server.serverId)
        assertEquals(original.token, server.token)
        assertEquals(original.relay, server.relayUrl)
        assertEquals(original.serverStaticPubkey, server.serverStaticPublicKey)
    }

    @Test
    fun tolerates_unknownExtraField() {
        val raw =
            """{"server":"s","relay":"wss://r","token":"t",""" +
                """"server_static_pubkey":"$validPubkey","extra":"ignored"}"""
        assertTrue(parsePairingPayload(wrap(raw)) is PairingParseResult.Success)
    }

    // ---- Outer wrapper rejection (the std-alphabet trap) -----------------------

    @Test
    fun rejects_outerNotBase64Url() {
        assertTrue(parsePairingPayload("!!!") is PairingParseResult.Failure)
    }

    @Test
    fun rejects_outerStdAlphabetWithSlashOrPlus() {
        // "///+" is valid base64-STD (contains '/' and '+') but NOT url-safe.
        assertTrue(parsePairingPayload("///+") is PairingParseResult.Failure)
    }

    // ---- JSON rejection (non-JSON, trailing data, absent fields) ---------------

    @Test
    fun rejects_validBase64UrlButNonJson() {
        assertTrue(parsePairingPayload(wrap("not json")) is PairingParseResult.Failure)
    }

    @Test
    fun rejects_trailingBytesAfterObject() {
        assertTrue(parsePairingPayload(wrap(json() + "garbage")) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(json() + "{}")) is PairingParseResult.Failure)
    }

    @Test
    fun rejects_absentRequiredFields() {
        val noServer = """{"relay":"wss://r","token":"t","server_static_pubkey":"$validPubkey"}"""
        val noRelay = """{"server":"s","token":"t","server_static_pubkey":"$validPubkey"}"""
        val noToken = """{"server":"s","relay":"wss://r","server_static_pubkey":"$validPubkey"}"""
        assertTrue(parsePairingPayload(wrap(noServer)) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(noRelay)) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(noToken)) is PairingParseResult.Failure)
    }

    // ---- Present-but-empty field rejection -------------------------------------

    @Test
    fun rejects_presentButEmptyFields() {
        assertTrue(parsePairingPayload(wrap(json(server = ""))) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(json(relay = ""))) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(json(token = ""))) is PairingParseResult.Failure)
    }

    // ---- Relay validation ------------------------------------------------------

    @Test
    fun rejects_malformedRelay() {
        // Unparseable, wrong scheme, and missing host all fail.
        assertTrue(parsePairingPayload(wrap(json(relay = "::::"))) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(json(relay = "http://x"))) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(json(relay = "ftp://x"))) is PairingParseResult.Failure)
        assertTrue(parsePairingPayload(wrap(json(relay = "wss://"))) is PairingParseResult.Failure)
    }

    // ---- Pubkey validation (base64-std + exactly 32 bytes) ---------------------

    @Test
    fun rejects_pubkeyWrongLength() {
        val raw = json(pubkey = base64StdEncode(ByteArray(31)))
        assertTrue(parsePairingPayload(wrap(raw)) is PairingParseResult.Failure)
    }

    @Test
    fun rejects_pubkeyNonBase64() {
        assertTrue(parsePairingPayload(wrap(json(pubkey = "!!!"))) is PairingParseResult.Failure)
    }

    // ---- No-leak: reason never echoes the token or key bytes (AC4) -------------

    @Test
    fun failureReason_neverContainsTokenOrPubkey() {
        val secretToken = "SUPER_SECRET_TOKEN_VALUE"
        val distinctivePubkey = "DISTINCTIVE_PUBKEY_NOT_BASE64_STD_VALUE"
        val raw =
            """{"server":"s","relay":"wss://r","token":"$secretToken",""" +
                """"server_static_pubkey":"$distinctivePubkey"}"""

        val result = parsePairingPayload(wrap(raw))

        assertTrue(result is PairingParseResult.Failure)
        val reason = (result as PairingParseResult.Failure).reason
        assertFalse(reason.contains(secretToken))
        assertFalse(reason.contains(distinctivePubkey))
    }
}
