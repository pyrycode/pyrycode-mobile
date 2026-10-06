package de.pyryco.mobile.data.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Round-trip, wire-vector, and rejection tests for the Mobile Protocol v2 wire
 * models + codec (#273). JUnit4, mirroring NoiseSuiteSmokeTest.kt. Each model is
 * decoded from its exact byte-contract fixture and re-encoded; both directions
 * must recover the value. Base64 fields are pinned against known vectors to prove
 * the standard (NOT url-safe) alphabet with padding.
 */
class MobileWireCodecTest {
    // ---- Per-model round-trip --------------------------------------------------

    @Test
    fun innerFrameV2_roundTrips() {
        val fixture = """{"v":2,"type":"noise_init","data":"aGk="}"""
        val decoded = MobileJson.decodeFromString<InnerFrameV2>(fixture)

        assertEquals(2, decoded.v)
        assertEquals("noise_init", decoded.type)
        assertEquals("aGk=", decoded.data)

        val encoded = MobileJson.encodeToString(decoded)
        assertTrue("v must be emitted", encoded.contains("\"v\":2"))
        assertEquals(decoded, MobileJson.decodeFromString<InnerFrameV2>(encoded))
    }

    @Test
    fun envelope_withInReplyTo_roundTrips() {
        val fixture =
            """{"id":1,"type":"hello","ts":"2026-05-29T12:00:00Z",""" +
                """"payload":{"hello":"world"},"in_reply_to":2}"""
        val decoded = MobileJson.decodeFromString<Envelope>(fixture)

        assertEquals(1L, decoded.id)
        assertEquals("hello", decoded.type)
        assertEquals("2026-05-29T12:00:00Z", decoded.ts)
        assertEquals(2L, decoded.inReplyTo)
        assertTrue(decoded.payload is JsonObject)

        val encoded = MobileJson.encodeToString(decoded)
        assertTrue(encoded.contains("\"in_reply_to\":2"))
        assertEquals(decoded, MobileJson.decodeFromString<Envelope>(encoded))
    }

    @Test
    fun envelope_withoutInReplyTo_decodesToAbsent() {
        val fixture =
            """{"id":7,"type":"hello","ts":"2026-05-29T12:00:00Z","payload":{"k":"v"}}"""
        val decoded = MobileJson.decodeFromString<Envelope>(fixture)

        assertNull(decoded.inReplyTo)
        assertEquals(7L, decoded.id)
    }

    @Test
    fun envelope_withEventId_roundTrips() {
        val fixture =
            """{"id":1,"type":"turn_state","ts":"2026-05-29T12:00:00Z",""" +
                """"payload":{"state":"thinking"},"event_id":42}"""
        val decoded = MobileJson.decodeFromString<Envelope>(fixture)

        assertEquals(42L, decoded.eventId)

        val encoded = MobileJson.encodeToString(decoded)
        assertTrue(encoded.contains("\"event_id\":42"))
        assertEquals(decoded, MobileJson.decodeFromString<Envelope>(encoded))
    }

    @Test
    fun envelope_withoutEventId_decodesToAbsent() {
        val fixture =
            """{"id":7,"type":"message","ts":"2026-05-29T12:00:00Z","payload":{"k":"v"}}"""
        val decoded = MobileJson.decodeFromString<Envelope>(fixture)

        assertNull(decoded.eventId)
    }

    @Test
    fun envelope_withNullEventId_omitsOnEncode() {
        val encoded =
            MobileJson.encodeToString(
                Envelope(id = 1L, type = "message", ts = "2026-05-29T12:00:00Z", payload = JsonObject(emptyMap())),
            )
        assertFalse("a null event_id is omitted, never emitted as 0/null", encoded.contains("event_id"))
    }

    @Test
    fun helloClientPayload_roundTrips() {
        val fixture =
            """{"role":"client","device_name":"Pixel 8","client_version":"1.0.0",""" +
                """"protocol_versions":["v2"],"token":"tok-123"}"""
        val decoded = MobileJson.decodeFromString<HelloClientPayload>(fixture)

        assertEquals("client", decoded.role)
        assertEquals("Pixel 8", decoded.deviceName)
        assertEquals("1.0.0", decoded.clientVersion)
        assertEquals(listOf("v2"), decoded.protocolVersions)
        assertEquals("tok-123", decoded.token)

        val encoded = MobileJson.encodeToString(decoded)
        assertTrue(encoded.contains("\"device_name\":\"Pixel 8\""))
        assertTrue(encoded.contains("\"client_version\":\"1.0.0\""))
        assertTrue(encoded.contains("\"protocol_versions\":[\"v2\"]"))
        assertEquals(decoded, MobileJson.decodeFromString<HelloClientPayload>(encoded))
    }

    @Test
    fun helloAckPayload_roundTrips() {
        val fixture = """{"protocol_version":"v2","server_id":"srv-1","conn_id":"conn-9"}"""
        val decoded = MobileJson.decodeFromString<HelloAckPayload>(fixture)

        assertEquals("v2", decoded.protocolVersion)
        assertEquals("srv-1", decoded.serverId)
        assertEquals("conn-9", decoded.connId)

        val encoded = MobileJson.encodeToString(decoded)
        assertEquals(decoded, MobileJson.decodeFromString<HelloAckPayload>(encoded))
    }

    @Test
    fun qrPayload_roundTrips() {
        val pubkey = base64StdEncode(ByteArray(32))
        val fixture =
            """{"server":"https://s","relay":"wss://r","token":"tok",""" +
                """"server_static_pubkey":"$pubkey"}"""
        val decoded = MobileJson.decodeFromString<QrPayload>(fixture)

        assertEquals("https://s", decoded.server)
        assertEquals("wss://r", decoded.relay)
        assertEquals("tok", decoded.token)
        assertEquals(pubkey, decoded.serverStaticPubkey)

        val encoded = MobileJson.encodeToString(decoded)
        assertTrue(encoded.contains("\"server_static_pubkey\":\"$pubkey\""))
        assertEquals(decoded, MobileJson.decodeFromString<QrPayload>(encoded))
    }

    // ---- Defaults emitted (guards encodeDefaults = true) -----------------------

    @Test
    fun defaults_areEmittedOnEncode() {
        val frame = InnerFrameV2(type = "noise_init", data = "aGk=")
        assertTrue(MobileJson.encodeToString(frame).contains("\"v\":2"))

        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "t")
        val encoded = MobileJson.encodeToString(hello)
        assertTrue(encoded.contains("\"role\":\"client\""))
        assertTrue(encoded.contains("\"protocol_versions\":[\"v2\"]"))
    }

    // ---- #401 AC#1, #1119 AC#1: hello advertises interactive, then multi_agent, on the wire ----

    @Test
    fun hello_advertisesInteractiveMultiAgentAndTaskStopCapabilityOnEncode() {
        // The defaulted capabilities list rides the wire via encodeDefaults = true — same mechanism
        // as protocol_versions. A hello built without an explicit capabilities still carries it.
        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "t")
        assertEquals(listOf(CAPABILITY_INTERACTIVE, CAPABILITY_MULTI_AGENT, CAPABILITY_STOP_BACKGROUND_TASK), hello.capabilities)
        assertTrue(MobileJson.encodeToString(hello).contains("\"capabilities\":[\"interactive\",\"multi_agent\",\"stop_background_task\"]"))
        // toString surfaces the (non-secret) capabilities while the token stays redacted.
        assertTrue(hello.toString().contains("capabilities=[interactive, multi_agent, stop_background_task]"))
        assertTrue(hello.toString().contains("token=***"))
    }

    // ---- #416 AC#2: last_event_id rides when set, omitted when null ------------

    @Test
    fun helloClientPayload_withLastEventId_encodesIt() {
        // A reconnect after observing event_id 42 advertises it verbatim, mirroring Envelope.eventId.
        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "t", lastEventId = 42L)
        assertTrue(MobileJson.encodeToString(hello).contains("\"last_event_id\":42"))
        // toString surfaces the (non-secret) ordinal while the token stays redacted.
        assertTrue(hello.toString().contains("lastEventId=42"))
        assertTrue(hello.toString().contains("token=***"))
    }

    @Test
    fun helloClientPayload_withNullLastEventId_omitsOnEncode() {
        // A fresh connection (nothing observed) omits the field — never `0`/`null` — via explicitNulls=false.
        val hello = HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "t")
        assertNull(hello.lastEventId)
        assertFalse(
            "a null last_event_id is omitted, never emitted as 0/null",
            MobileJson.encodeToString(hello).contains("last_event_id"),
        )
    }

    // ---- in_reply_to omitted when absent (AC #5) -------------------------------

    @Test
    fun inReplyTo_isOmittedWhenNull() {
        val envelope =
            Envelope(
                id = 1L,
                type = "hello",
                ts = "2026-05-29T12:00:00Z",
                payload = MobileJson.parseToJsonElement("""{"k":"v"}"""),
                inReplyTo = null,
            )
        val encoded = MobileJson.encodeToString(envelope)

        assertFalse(encoded.contains("in_reply_to"))
        assertNull(MobileJson.decodeFromString<Envelope>(encoded).inReplyTo)
    }

    // ---- Base64 standard alphabet WITH padding (AC #3) -------------------------

    @Test
    fun base64_usesStandardAlphabetWithPadding() {
        // 0xFF 0xFF -> "//8=" proves the standard alphabet ('/', not url-safe '_')
        // AND padding ('='). Pinned literal, independent of the impl.
        val twoBytes = base64StdEncode(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        assertEquals("//8=", twoBytes)
        assertFalse(twoBytes == "__8")

        // 0xFF -> "/w==" pins the two-char padding case.
        assertEquals("/w==", base64StdEncode(byteArrayOf(0xFF.toByte())))

        val v = ByteArray(32) { it.toByte() }
        assertTrue(base64StdDecode(base64StdEncode(v)).contentEquals(v))
    }

    // ---- outer wrapper: base64url, URL-safe alphabet, no padding (#320 AC #1) --

    @Test
    fun decodeBase64UrlNoPad_decodesKnownNoPadVector() {
        // "hello" -> "aGVsbG8" (url-safe, no '=' padding).
        assertArrayEquals("hello".toByteArray(), decodeBase64UrlNoPad("aGVsbG8"))
    }

    @Test
    fun decodeBase64UrlNoPad_roundTripsUrlEncoderWithoutPadding() {
        val v = ByteArray(40) { it.toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(v)
        assertTrue(decodeBase64UrlNoPad(encoded).contentEquals(v))
    }

    @Test
    fun decodeBase64UrlNoPad_rejectsStandardAlphabetSpecials() {
        // "///+" is valid base64-STD (the '/'/'+' alphabet) but NOT url-safe -> reject (the trap).
        assertThrows(IllegalArgumentException::class.java) {
            decodeBase64UrlNoPad("///+")
        }
    }

    // ---- server_static_pubkey -> 32 bytes + rejection (AC #4) ------------------

    @Test
    fun decodeServerStaticPubkey_acceptsExactly32Bytes() {
        val qr = qrWithPubkey(base64StdEncode(ByteArray(32)))
        assertEquals(32, decodeServerStaticPubkey(qr).size)
    }

    @Test
    fun decodeServerStaticPubkey_rejectsWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeServerStaticPubkey(qrWithPubkey(base64StdEncode(ByteArray(31))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeServerStaticPubkey(qrWithPubkey(base64StdEncode(ByteArray(33))))
        }
    }

    @Test
    fun decodeServerStaticPubkey_rejectsInvalidBase64() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeServerStaticPubkey(qrWithPubkey("!!!"))
        }
    }

    // ---- Secret redaction (security-sensitive) ---------------------------------

    @Test
    fun token_isRedactedInToStringButNotOnTheWire() {
        val hello =
            HelloClientPayload(deviceName = "d", clientVersion = "1.0", token = "SECRET_TOKEN")
        assertFalse(hello.toString().contains("SECRET_TOKEN"))
        assertTrue(hello.toString().contains("***"))
        assertTrue(MobileJson.encodeToString(hello).contains("SECRET_TOKEN"))

        val qr = qrWithPubkey(base64StdEncode(ByteArray(32)), token = "SECRET_TOKEN")
        assertFalse(qr.toString().contains("SECRET_TOKEN"))
        assertTrue(qr.toString().contains("***"))
        assertTrue(MobileJson.encodeToString(qr).contains("SECRET_TOKEN"))
    }

    private fun qrWithPubkey(
        pubkey: String,
        token: String = "tok",
    ) = QrPayload(
        server = "https://s",
        relay = "wss://r",
        token = token,
        serverStaticPubkey = pubkey,
    )
}
