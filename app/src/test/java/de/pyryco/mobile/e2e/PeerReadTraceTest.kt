package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerReadTraceTest {
    @Test
    fun traceRetainsCorrelationAndNumericEvidenceWithoutDaemonText() {
        val frame =
            Envelope(
                id = 12,
                inReplyTo = 8,
                type = "thread_items",
                ts = "PRIVATE_TIMESTAMP",
                payload =
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"PRIVATE_CONVERSATION","epoch":"PRIVATE_EPOCH","version":42,
                        "done":true,"items":[{"id":41,"rev":42,"status":"done","active":false,
                        "content":{"text":"PRIVATE_BODY"},"kind":"assistant_message"}]}""",
                    ),
            )
        val trace = requireNotNull(peerReadTrace(frame))
        assertTrue(trace.contains("request=12 reply_to=8"))
        assertTrue(trace.contains("version=42"))
        assertTrue(trace.contains("id=41 rev=42"))
        assertFalse(trace.contains("PRIVATE"))
    }

    @Test
    fun malformedFieldsAndUnboundedListsCannotInjectLogText() {
        val payload =
            """{"version":"PRIVATE_NUMBER","status":"PRIVATE_STATUS","code":"PRIVATE_CODE",
                "message":"PRIVATE_MESSAGE","items":[${List(100) { "{\"id\":$it}" }.joinToString()}]}"""
        val trace =
            requireNotNull(peerReadTrace(Envelope(id = 1, type = "error", ts = "", payload = MobileJson.parseToJsonElement(payload))))
        assertFalse(trace.contains("PRIVATE"))
        assertFalse(trace.contains("id=16"))
        assertTrue(trace.length < 4096)
        assertTrue(peerReadTrace(Envelope(id = 1, type = "PRIVATE_TYPE", ts = "")) == null)
    }
}
